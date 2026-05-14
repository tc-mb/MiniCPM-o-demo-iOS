// HarmonyOS NAPI port of MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp.
//
// The inference state machine, sampling defaults and prompt formatting are
// kept byte-for-byte identical to the JNI version (and therefore in lockstep
// with the iOS demo's mtmd-ios.cpp) so all three demos share a single,
// reviewed copy of the multi-modal prefill / generation logic. Only the
// ABI-facing layer is rewritten to use Node-API:
//
//   * jstring          <-> napi_get_value_string_utf8 / napi_create_string_utf8
//   * jbyteArray       <-> napi_get_arraybuffer_info
//   * jint return code <-> napi_create_int32
//   * processUserPrompt + while(generateNextToken) loop
//                      -> single streamUserPrompt(prompt, n, onToken, onDone)
//                         using napi_threadsafe_function so the JS thread is
//                         never blocked by decode/sample.

#include "napi/native_api.h"

#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "hilog_log.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

// Inference parameters mirror the iOS / Android demos exactly. See
//   MiniCPM-V-demo/MTMDWrapper/MTMDParams.swift  (iOS)
//   MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp  (Android)
constexpr int   N_THREADS               = 4;
constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
// MiniCPM-V-4.6 video understanding feeds up to 64 frames * ~64 visual
// tokens each into the KV cache; 4096 is not enough.  iOS demo
// (MBHomeViewController+LoadModel.swift) bumps n_ctx to 8192 only on
// V-4.6 load so older / non-vision models keep the cheaper 4096 budget.
constexpr int   V46_CONTEXT_SIZE        = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 2048;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.7f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static mtmd_context                     * g_ctx_vision;
// MiniCPM-V family version of the loaded mmproj. 0 = not minicpmv / no mmproj.
// 5  = V-4.0 / 6  = o-4.0 / 100045 = o-4.5 / 46/460/461 = V-4.6 (instruct / thinking)
static int                                g_minicpmv_version = 0;

// Most recent slice cap requested by the upper layer.  Persists between
// loadMmproj calls so a user-chosen value survives a model unload/reload.
// Initial fallback = 9 (MiniCPM-V's built-in upper bound) so anything
// that calls into native before ArkTS has had a chance to thread the
// user-preference value through gets the "high quality" default rather
// than the legacy "no slicing" one.  In practice every real call site
// uses LlamaEngine.loadModel(...) which calls loadMmproj(path, n) with
// the persisted value.  Mirrors llama_jni.cpp on Android.
static int                                g_image_max_slice_nums = 9;

// Effective n_ctx used to create g_context.  Set by Prepare(); read by
// the few stay-under-context guards scattered through this file.
// Mirrors g_n_ctx in MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp.
static int                                g_n_ctx = DEFAULT_CONTEXT_SIZE;

// =============================================================================
// Generic NAPI helpers
// =============================================================================

static std::string napi_get_string(napi_env env, napi_value v) {
    size_t len = 0;
    napi_get_value_string_utf8(env, v, nullptr, 0, &len);
    std::string s(len, '\0');
    if (len > 0) {
        napi_get_value_string_utf8(env, v, &s[0], len + 1, &len);
    }
    return s;
}

static napi_value napi_make_int(napi_env env, int v) {
    napi_value out = nullptr;
    napi_create_int32(env, v, &out);
    return out;
}

static napi_value napi_make_string(napi_env env, const std::string &s) {
    napi_value out = nullptr;
    napi_create_string_utf8(env, s.c_str(), s.size(), &out);
    return out;
}

static napi_value make_undefined(napi_env env) {
    napi_value u = nullptr;
    napi_get_undefined(env, &u);
    return u;
}

// =============================================================================
// init / load / loadMmproj / prepare / systemInfo / unload / shutdown
// =============================================================================

napi_value Init(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    llama_log_set(minicpm_hilog_callback, nullptr);
    mtmd_helper_log_set(minicpm_hilog_callback, nullptr);

    if (argc >= 1) {
        std::string path = napi_get_string(env, argv[0]);
        LOGi("Loading backends from %{public}s", path.c_str());
        ggml_backend_load_all_from_path(path.c_str());
    } else {
        LOGw("Init: no nativeLibDir provided, falling back to default search");
    }

    if (ggml_backend_reg_count() == 0) {
        LOGw("init: No backends loaded from path, trying ggml_backend_load_all()");
        ggml_backend_load_all();
    }
    LOGi("init: Backend count: %{public}zu", ggml_backend_reg_count());

    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
    return make_undefined(env);
}

napi_value Load(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    llama_model_params model_params = llama_model_default_params();

    std::string model_path = napi_get_string(env, argv[0]);
    LOGi("Load: Loading model from: \n%{public}s\n", model_path.c_str());

    FILE *f = fopen(model_path.c_str(), "rb");
    if (!f) {
        LOGe("Load: Cannot open model file! Permission denied or file not found: %{public}s", model_path.c_str());
        return napi_make_int(env, 1);
    }
    fseek(f, 0, SEEK_END);
    long file_size = ftell(f);
    fclose(f);
    LOGi("Load: Model file size: %{public}ld bytes (%{public}.2f GB)",
         file_size, file_size / (1024.0 * 1024.0 * 1024.0));

    auto *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        LOGe("Load: llama_model_load_from_file returned null!");
        return napi_make_int(env, 2);
    }
    g_model = model;
    LOGi("Load: Model loaded successfully!");
    return napi_make_int(env, 0);
}

napi_value LoadMmproj(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    if (!g_model) {
        LOGe("LoadMmproj: Base model must be loaded first!");
        return napi_make_int(env, 1);
    }

    std::string mmproj_path = napi_get_string(env, argv[0]);
    // Fallback only used if ArkTS forgot to pass the slider value (it
    // shouldn't, but we want a safe default that matches the rest of the
    // demo).  9 = MiniCPM-V upper bound, see LlamaEngine.ets::DEFAULT_IMAGE_SLICE.
    int image_max_slice_nums = 9;
    if (argc >= 2) {
        napi_get_value_int32(env, argv[1], &image_max_slice_nums);
    }
    LOGd("LoadMmproj: Loading mmproj from: \n%{public}s\n", mmproj_path.c_str());

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu             = false;
    mparams.print_timings       = false;
    // Upstream master mtmd replaced the runtime slice-count knob with a
    // token-budget knob (`image_max_tokens`) decided at mtmd_context init
    // time.  The chat-page slider is therefore advisory only on this
    // build; we still remember its value in g_image_max_slice_nums for
    // logging but pass -1 (= model default) to upstream mtmd.  The
    // SetImageMaxSliceNums NAPI hook stays so existing ArkTS callers
    // compile; see iOS opt-r1's MBMtmd for the same downgrade.
    g_image_max_slice_nums     = image_max_slice_nums;
    mparams.image_max_tokens   = -1;
    mparams.n_threads          = N_THREADS;

    g_ctx_vision = mtmd_init_from_file(mmproj_path.c_str(), g_model, mparams);
    if (!g_ctx_vision) {
        LOGe("LoadMmproj: Failed to load mmproj model!");
        return napi_make_int(env, 2);
    }

    // mtmd_get_minicpmv_version() was a fork-only API removed by upstream
    // master mtmd.  The ArkTS layer now infers the MiniCPM-V family
    // version from the selected ModelInfo id and pushes it down via
    // setMinicpmvVersion() right after this call returns.  Symmetric with
    // Android (setMinicpmvVersionNative) and iOS opt-r1
    // (MBHomeViewController+LoadModel).

    LOGi("LoadMmproj: mmproj loaded! Vision: %{public}s, slice-cap-hint: %{public}d "
         "(advisory only on master mtmd; effective slicing follows model default)",
         mtmd_support_vision(g_ctx_vision) ? "yes" : "no",
         g_image_max_slice_nums);
    return napi_make_int(env, 0);
}

// Returns the MiniCPM-V family version most recently set by the ArkTS
// layer (0 = unset / no vision).  Backs the video-understanding gate in
// LlamaEngine.ets.  Replaces the legacy mtmd_get_minicpmv_version
// introspection that the fork mtmd-ios.h used to expose but upstream
// master mtmd removed.
napi_value GetMinicpmvVersion(napi_env env, napi_callback_info /*info*/) {
    return napi_make_int(env, g_minicpmv_version);
}

// ArkTS-driven setter for the mmproj's MiniCPM-V family version.
// Affects Prepare()'s n_ctx selection and the assistant-turn prefix
// logic; should be called immediately after a successful loadMmproj.
// Mirrors setMinicpmvVersionNative on Android.
napi_value SetMinicpmvVersion(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int v = 0;
    if (argc >= 1) {
        napi_get_value_int32(env, argv[0], &v);
    }
    g_minicpmv_version = v;
    LOGi("SetMinicpmvVersion: minicpmv_version set to %{public}d", g_minicpmv_version);
    return make_undefined(env);
}

// Per-image slice cap.  No-op on upstream master mtmd: the
// `mtmd_set_image_max_slice_nums` runtime API was removed, and slice
// control now happens at mtmd_context creation time via
// `image_max_tokens`.  Kept so existing ArkTS call sites
// (LlamaEngine.setImageMaxSliceNums / prefillVideoFrames) continue to
// compile; the value is informational only.
napi_value SetImageMaxSliceNums(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    int n = 1;
    if (argc >= 1) {
        napi_get_value_int32(env, argv[0], &n);
    }
    g_image_max_slice_nums = n;
    LOGi("SetImageMaxSliceNums: image_max_slice_nums hint set to %{public}d "
         "(advisory only on master mtmd)", n);
    return make_undefined(env);
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("init_context: model cannot be null");
        return nullptr;
    }
    LOGi("init_context: Using %{public}d threads (aligned with iOS/Android demos)", N_THREADS);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("init_context: Model trained with only %{public}d ctx; enforcing %{public}d",
             trained_context_size, n_ctx);
    }
    ctx_params.n_ctx           = n_ctx;
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = N_THREADS;
    ctx_params.n_threads_batch = N_THREADS;

    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("init_context: llama_new_context_with_model() returned null");
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp           = temp;
    sparams.top_k          = 0;
    sparams.top_p          = 1.0f;
    sparams.penalty_repeat = 1.0f;
    return common_sampler_init(g_model, sparams);
}

napi_value Prepare(napi_env env, napi_callback_info /*info*/) {
    // MiniCPM-V-4.6 video understanding (up to 64 frames per turn) needs
    // a larger KV budget than the 4096 default; everything else stays at
    // 4096 to keep memory pressure low on older / non-vision models.
    // Matches the iOS demo's MTMDParams.nCtx = 8192 on V46MultiModel.
    const bool is_v46 = (g_minicpmv_version == 46) ||
                        (g_minicpmv_version == 460) ||
                        (g_minicpmv_version == 461);
    const int  n_ctx  = is_v46 ? V46_CONTEXT_SIZE : DEFAULT_CONTEXT_SIZE;
    LOGi("Prepare: minicpmv_version=%{public}d -> n_ctx=%{public}d", g_minicpmv_version, n_ctx);

    auto *context = init_context(g_model, n_ctx);
    if (!context) { return napi_make_int(env, 1); }
    g_context = context;
    g_n_ctx = n_ctx;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return napi_make_int(env, 0);
}

napi_value SystemInfo(napi_env env, napi_callback_info /*info*/) {
    return napi_make_string(env, llama_print_system_info());
}

// =============================================================================
// Long / short term inference state (verbatim from llama_jni.cpp)
// =============================================================================

constexpr const char *ROLE_SYSTEM    = "system";
constexpr const char *ROLE_USER      = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static llama_pos generation_start_position;
static bool g_image_prefilled = false;
static bool g_vision_mode     = false;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position       = 0;
    g_image_prefilled      = false;
    g_vision_mode          = false;
    if (clear_kv_cache) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

// MiniCPM-V-4.6 ships in two flavours that share the same llama base model:
// instruct  (no thinking, model emits the answer directly) and
// thinking   (model emits <think>...</think> before the answer).
// They use different chat templates: instruct hard-codes an empty
// <think>\n\n</think> block in the assistant turn prefix to signal "no
// thinking", while thinking lets the model produce its own block.
//
// Note: convert_hf_to_gguf.py currently hard-codes clip.minicpmv_version = 46
// for ALL MiniCPM-V 4.6 mmproj (does not differentiate instruct/thinking).
// Since this demo only ships the instruct variant, we treat 46 as instruct
// to match the iOS path (which hard-codes the instruct prefix unconditionally).
// If a thinking-flavoured mmproj is ever shipped, it should write 461 to
// avoid this collision.
static const char *assistant_turn_prefix() {
    switch (g_minicpmv_version) {
        case 46:  // MiniCPM-V-4.6 (default tag from convert_hf_to_gguf.py; treated as instruct)
        case 460: // MiniCPM-V-4.6 instruct (enable_thinking = false)
            return "<|im_start|>assistant\n<think>\n\n</think>\n\n";
        case 461: // MiniCPM-V-4.6 thinking (model emits its own <think>...</think>)
            return "<|im_start|>assistant\n";
        default:
            return "<|im_start|>assistant\n";
    }
}

static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("shift_context: Discarding %{public}d tokens", n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position,
                        system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0,
                         system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("shift_context: Context shifting done! Current position: %{public}d", current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role    = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, false);
    chat_msgs.push_back(new_msg);
    LOGi("chat_add_and_format: Formatted %{public}s msg: \n%{public}s\n",
         role.c_str(), formatted.c_str());
    return formatted;
}

static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    LOGd("decode_tokens_in_batches: %{public}d tokens at pos %{public}d", (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);

        if (start_pos + i + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("decode_tokens_in_batches: Won't fit, shifting...");
            shift_context();
        }
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("decode_tokens_in_batches: llama_decode failed w/ %{public}d", decode_result);
            return 1;
        }
    }
    return 0;
}

// =============================================================================
// processSystemPrompt
// =============================================================================

napi_value ProcessSystemPrompt(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    reset_short_term_states();

    std::string system_prompt = napi_get_string(env, argv[0]);
    LOGd("ProcessSystemPrompt: \n%{public}s", system_prompt.c_str());

    std::string formatted_system_prompt(system_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }

    if (g_ctx_vision) {
        mtmd_input_text input_text;
        input_text.text          = formatted_system_prompt.c_str();
        input_text.add_special   = current_position == 0;
        input_text.parse_special = true;

        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        int32_t res = mtmd_tokenize(g_ctx_vision, chunks, &input_text, nullptr, 0);
        if (res != 0) {
            LOGe("ProcessSystemPrompt: mtmd_tokenize failed %{public}d", res);
            mtmd_input_chunks_free(chunks);
            return napi_make_int(env, 2);
        }
        llama_pos new_n_past;
        if (mtmd_helper_eval_chunks(g_ctx_vision, g_context, chunks,
                                    current_position, 0, BATCH_SIZE,
                                    true, &new_n_past)) {
            LOGe("ProcessSystemPrompt: mtmd_helper_eval_chunks failed");
            mtmd_input_chunks_free(chunks);
            return napi_make_int(env, 2);
        }
        current_position          = new_n_past;
        generation_start_position = current_position;
        mtmd_input_chunks_free(chunks);
    } else {
        const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                                   current_position == 0, true);
        const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
        if ((int) system_tokens.size() > max_batch_size) {
            LOGe("ProcessSystemPrompt: too long! %{public}d tokens, max %{public}d",
                 (int) system_tokens.size(), max_batch_size);
            return napi_make_int(env, 1);
        }
        if (decode_tokens_in_batches(g_context, g_batch, system_tokens,
                                     current_position, true)) {
            LOGe("ProcessSystemPrompt: llama_decode() failed!");
            return napi_make_int(env, 2);
        }
        current_position += (int) system_tokens.size();
    }
    system_prompt_position = current_position;
    return napi_make_int(env, 0);
}

// =============================================================================
// prefillImage  --  async (Promise-returning)
//
// Why async?  Image prefill = stb_image decode + N-slice ViT + perceiver +
// llama_decode of a few hundred image tokens.  With image_max_slice_nums=1
// it finishes in ~1-2 s, but at slice=9 the ViT runs 10x and the whole
// thing easily takes 10-30 s on a HarmonyOS phone.  Doing that inline on
// the JS thread blocks UI input long enough to trip the OS's
// THREAD_BLOCK_3S watchdog ("APP_INPUT_BLOCK"), which then SIGKILLs and
// auto-restarts the process - users see an instant "flash crash" right
// after picking an image when they bump the slider above 1.
//
// Fix mirrors what `streamUserPrompt` already does on this same file:
// hand the heavy work off to a worker thread.  Here we use libuv-backed
// `napi_async_work` (simpler than tsfn since there's only one completion
// event), and resolve / reject a JS Promise with the int return code.
//
// Ownership: we copy the image bytes out of the JS ArrayBuffer up front
// so we don't depend on JS-side keep-alive while the worker thread runs.
// =============================================================================

struct PrefillWork {
    napi_async_work          work        = nullptr;
    napi_deferred            deferred    = nullptr;
    std::vector<uint8_t>     bytes;       // copied PNG/JPEG/... buffer
    int                      rc          = 0;
    llama_pos                new_pos     = 0;
};

static void PrefillExecute(napi_env /*env*/, void *data) {
    // Worker thread: must NOT touch napi_env or any JS value.  The
    // native llama / mtmd globals are protected against concurrent use
    // by the JS-side `LlamaEngine.serial(...)` chain, which guarantees
    // at most one PrefillExecute is alive at a time.
    auto *w = static_cast<PrefillWork *>(data);
    if (!g_ctx_vision) {
        LOGe("PrefillExecute: mmproj not loaded!");
        w->rc = 1;
        return;
    }

    auto *bitmap = mtmd_helper_bitmap_init_from_buf(
        g_ctx_vision, w->bytes.data(), w->bytes.size());
    if (!bitmap) {
        LOGe("PrefillExecute: Failed to create bitmap (%{public}zu bytes)", w->bytes.size());
        w->rc = 3;
        return;
    }

    mtmd_input_text text;
    text.text          = mtmd_default_marker();
    text.add_special   = current_position == 0;
    text.parse_special = true;

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps_cptr[] = { bitmap };
    int32_t res = mtmd_tokenize(g_ctx_vision, chunks, &text, bitmaps_cptr, 1);
    mtmd_bitmap_free(bitmap);

    if (res != 0) {
        LOGe("PrefillExecute: mtmd_tokenize failed %{public}d", res);
        mtmd_input_chunks_free(chunks);
        w->rc = 4;
        return;
    }
    llama_pos new_n_past = 0;
    if (mtmd_helper_eval_chunks(g_ctx_vision, g_context, chunks,
                                current_position, 0, BATCH_SIZE,
                                false, &new_n_past)) {
        LOGe("PrefillExecute: mtmd_helper_eval_chunks failed!");
        mtmd_input_chunks_free(chunks);
        w->rc = 5;
        return;
    }
    mtmd_input_chunks_free(chunks);
    w->rc      = 0;
    w->new_pos = new_n_past;
}

static void PrefillComplete(napi_env env, napi_status /*status*/, void *data) {
    // JS thread: safe to touch napi_env again.  Commit the new global
    // position now (not in the worker) so that JS-observable side
    // effects line up with Promise resolution.
    auto *w = static_cast<PrefillWork *>(data);
    if (w->rc == 0) {
        current_position  = w->new_pos;
        g_image_prefilled = true;
        g_vision_mode     = true;
        LOGi("PrefillImage: done, current_position: %{public}d", current_position);
    }
    napi_value result_code = napi_make_int(env, w->rc);
    if (w->rc == 0) {
        napi_resolve_deferred(env, w->deferred, result_code);
    } else {
        // Reject so JS-side `await engine.prefillImage(...)` throws,
        // matching the pre-existing error-handling expectations.
        napi_reject_deferred(env, w->deferred, result_code);
    }
    napi_delete_async_work(env, w->work);
    delete w;
}

napi_value PrefillImage(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    napi_value promise;
    napi_deferred deferred;
    napi_create_promise(env, &deferred, &promise);

    // Fail-fast: short-circuit on caller errors (mmproj missing, bad
    // ArrayBuffer) before we kick off a thread.  Reject on the JS
    // thread synchronously to keep the rc semantics of the old API.
    auto reject_with_code = [&](int code) {
        napi_value v = napi_make_int(env, code);
        napi_reject_deferred(env, deferred, v);
        return promise;
    };

    if (!g_ctx_vision) {
        LOGe("PrefillImage: mmproj not loaded!");
        return reject_with_code(1);
    }

    void  *data = nullptr;
    size_t length = 0;
    napi_status st = napi_get_arraybuffer_info(env, argv[0], &data, &length);
    if (st != napi_ok || !data || length == 0) {
        LOGe("PrefillImage: invalid ArrayBuffer (status=%{public}d, len=%{public}zu)",
             (int) st, length);
        return reject_with_code(2);
    }

    auto *w = new PrefillWork;
    w->deferred = deferred;
    // Copy bytes so we don't keep a reference into a JS-owned ArrayBuffer
    // while the worker thread is in flight (the ArrayBuffer's backing
    // store could be GC'd or compacted between now and worker entry).
    w->bytes.assign(
        reinterpret_cast<const uint8_t *>(data),
        reinterpret_cast<const uint8_t *>(data) + length);

    napi_value resource_name;
    napi_create_string_utf8(env, "MiniCPMV.PrefillImage", NAPI_AUTO_LENGTH, &resource_name);
    st = napi_create_async_work(env, nullptr, resource_name,
                                PrefillExecute, PrefillComplete,
                                w, &w->work);
    if (st != napi_ok) {
        LOGe("PrefillImage: napi_create_async_work failed (status=%{public}d)", (int) st);
        delete w;
        return reject_with_code(6);
    }
    st = napi_queue_async_work(env, w->work);
    if (st != napi_ok) {
        LOGe("PrefillImage: napi_queue_async_work failed (status=%{public}d)", (int) st);
        napi_delete_async_work(env, w->work);
        delete w;
        return reject_with_code(7);
    }
    return promise;
}

// =============================================================================
// fullReset / cancelGeneration
// =============================================================================

static std::atomic<bool> g_cancel_generation{false};

napi_value FullReset(napi_env env, napi_callback_info /*info*/) {
    reset_long_term_states();
    reset_short_term_states();

    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    g_context = nullptr;

    auto *context = init_context(g_model);
    if (!context) {
        LOGe("FullReset: Failed to reinitialize context!");
        return make_undefined(env);
    }
    g_context        = context;
    g_batch          = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler        = new_sampler(DEFAULT_SAMPLER_TEMP);

    LOGi("FullReset: complete - context recreated, KV fresh, sampler reinitialized");
    return make_undefined(env);
}

napi_value CancelGeneration(napi_env env, napi_callback_info /*info*/) {
    g_cancel_generation.store(true);
    LOGi("CancelGeneration: signalled cancellation");
    return make_undefined(env);
}

// =============================================================================
// streamUserPrompt — folds processUserPrompt + while(generateNextToken) into
// a single async API. Spawns a worker thread, returns immediately. Tokens
// are pushed back to JS via threadsafe functions; when generation ends a
// final onDone(cancelled: boolean) call is made and both tsfns are released.
// =============================================================================

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if      ((*bytes & 0x80) == 0x00) { num = 1; }
        else if ((*bytes & 0xE0) == 0xC0) { num = 2; }
        else if ((*bytes & 0xF0) == 0xE0) { num = 3; }
        else if ((*bytes & 0xF8) == 0xF0) { num = 4; }
        else { return false; }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) { return false; }
            bytes += 1;
        }
    }
    return true;
}

static int run_user_prompt_prefill(const std::string &user_prompt, int n_predict) {
    reset_short_term_states();

    std::string content_for_format(user_prompt);
    if (content_for_format.empty()) {
        content_for_format = " ";
    }

    std::string formatted_user_prompt;
    if (g_ctx_vision) {
        // No default system prompt: aligned with iOS opt-r1's MBMtmd.mm.
        // An English-only "You are a helpful assistant." was observed to
        // bias MiniCPM-V into answering Chinese queries in English. The
        // reference Python pipeline (`AutoModel.chat(...)` /
        // `apply_chat_template`) does not inject one either when the
        // caller passes only a user message. If a system message is
        // required it must be added through processSystemPrompt()
        // *before* the first processUserPrompt() call.
        formatted_user_prompt += "<|im_start|>user\n" + content_for_format + "<|im_end|>\n";
        formatted_user_prompt += assistant_turn_prefix();

        common_chat_msg new_msg;
        new_msg.role    = ROLE_USER;
        new_msg.content = content_for_format;
        chat_msgs.push_back(new_msg);

        LOGi("run_user_prompt_prefill (mtmd, image=%{public}s, minicpmv=%{public}d):\n%{public}s\n",
             g_image_prefilled ? "yes" : "no",
             g_minicpmv_version,
             formatted_user_prompt.c_str());
        g_image_prefilled = false;
    } else {
        const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
        if (has_chat_template) {
            formatted_user_prompt = chat_add_and_format(ROLE_USER, content_for_format);
        } else {
            formatted_user_prompt = content_for_format;
        }
    }

    if (g_ctx_vision) {
        mtmd_input_text text;
        text.text          = formatted_user_prompt.c_str();
        text.add_special   = current_position == 0;
        text.parse_special = true;

        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        int32_t res = mtmd_tokenize(g_ctx_vision, chunks, &text, nullptr, 0);
        if (res != 0) {
            LOGe("prefill: mtmd_tokenize failed %{public}d", res);
            mtmd_input_chunks_free(chunks);
            return 2;
        }
        llama_pos new_n_past;
        if (mtmd_helper_eval_chunks(g_ctx_vision, g_context, chunks,
                                    current_position, 0, BATCH_SIZE,
                                    true, &new_n_past)) {
            LOGe("prefill: mtmd_helper_eval_chunks failed");
            mtmd_input_chunks_free(chunks);
            return 2;
        }
        current_position          = new_n_past;
        generation_start_position = current_position;
        mtmd_input_chunks_free(chunks);
    } else {
        auto user_tokens = common_tokenize(g_context, formatted_user_prompt,
                                           current_position == 0, true);
        const int user_prompt_size = (int) user_tokens.size();
        const int max_batch_size   = g_n_ctx - OVERFLOW_HEADROOM;
        if (user_prompt_size > max_batch_size) {
            const int skipped_tokens = user_prompt_size - max_batch_size;
            user_tokens.resize(max_batch_size);
            LOGw("prefill: User prompt too long! Skipped %{public}d tokens", skipped_tokens);
        }
        if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
            LOGe("prefill: llama_decode() failed!");
            return 2;
        }
        current_position += user_prompt_size;
        generation_start_position = current_position;
    }

    stop_generation_position = current_position + n_predict;
    return 0;
}

// Result handed off from the worker thread to the JS thread via the tsfn
// dispatch queue. data points to a heap-allocated std::string for token
// emissions, or to a TokenDone marker for the final callback.
struct TokenEvent {
    bool        is_done    = false;
    bool        cancelled  = false;
    std::string text;
};

struct StreamCtx {
    napi_threadsafe_function tsfn_token = nullptr;
    napi_threadsafe_function tsfn_done  = nullptr;
    std::string              prompt;
    int                      n_predict  = 100;
};

static std::atomic<bool> g_stream_running{false};

// Called on the JS thread by the tsfn dispatch queue.
static void TokenJsCallback(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    auto *evt = static_cast<TokenEvent *>(data);
    if (env != nullptr && js_cb != nullptr && !evt->is_done) {
        napi_value arg;
        napi_create_string_utf8(env, evt->text.c_str(), evt->text.size(), &arg);
        napi_value undef;
        napi_get_undefined(env, &undef);
        napi_call_function(env, undef, js_cb, 1, &arg, nullptr);
    }
    delete evt;
}

static void DoneJsCallback(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    auto *evt = static_cast<TokenEvent *>(data);
    if (env != nullptr && js_cb != nullptr) {
        napi_value arg;
        napi_get_boolean(env, evt->cancelled, &arg);
        napi_value undef;
        napi_get_undefined(env, &undef);
        napi_call_function(env, undef, js_cb, 1, &arg, nullptr);
    }
    delete evt;
}

static void stream_worker(StreamCtx *ctx) {
    bool cancelled = false;
    int  rc        = run_user_prompt_prefill(ctx->prompt, ctx->n_predict);
    if (rc != 0) {
        LOGe("stream_worker: prefill failed %{public}d", rc);
    } else {
        while (!g_cancel_generation.load()) {
            if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
                LOGw("stream_worker: Context full! Shifting...");
                shift_context();
            }
            if (current_position >= stop_generation_position) {
                LOGw("stream_worker: STOP: hitting stop position %{public}d",
                     stop_generation_position);
                break;
            }

            const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
            common_sampler_accept(g_sampler, new_token_id, true);

            const bool is_eog = llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id);

            common_batch_clear(g_batch);
            common_batch_add(g_batch, new_token_id, current_position, {0}, true);
            if (llama_decode(g_context, g_batch) != 0) {
                LOGe("stream_worker: llama_decode failed for generated token");
                break;
            }
            current_position++;

            if (is_eog) {
                LOGd("stream_worker: id=%{public}d IS EOG, stopping", new_token_id);
                chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
                break;
            }

            auto new_token_chars = common_token_to_piece(g_context, new_token_id);
            cached_token_chars += new_token_chars;

            if (is_valid_utf8(cached_token_chars.c_str())) {
                if (!cached_token_chars.empty()) {
                    auto *evt = new TokenEvent;
                    evt->text = cached_token_chars;
                    napi_call_threadsafe_function(ctx->tsfn_token, evt, napi_tsfn_blocking);
                    assistant_ss << cached_token_chars;
                    cached_token_chars.clear();
                }
            } else {
                LOGv("stream_worker: id=%{public}d append to cache", new_token_id);
            }
        }
        if (g_cancel_generation.load()) {
            cancelled = true;
            // Match Java side's nativeCancelGeneration: reset sampler so the
            // next streamUserPrompt starts from a clean sampling state.
            common_sampler_reset(g_sampler);
            assistant_ss.str("");
            assistant_ss.clear();
            cached_token_chars.clear();
            LOGi("stream_worker: cancelled at pos %{public}d", current_position);
        }
    }

    auto *done_evt       = new TokenEvent;
    done_evt->is_done    = true;
    done_evt->cancelled  = cancelled;
    napi_call_threadsafe_function(ctx->tsfn_done, done_evt, napi_tsfn_blocking);

    napi_release_threadsafe_function(ctx->tsfn_token, napi_tsfn_release);
    napi_release_threadsafe_function(ctx->tsfn_done,  napi_tsfn_release);

    delete ctx;
    g_stream_running.store(false);
}

napi_value StreamUserPrompt(napi_env env, napi_callback_info info) {
    size_t argc = 4;
    napi_value argv[4];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 4) {
        LOGe("StreamUserPrompt: expected 4 args (prompt, n, onToken, onDone)");
        return napi_make_int(env, 1);
    }
    if (g_stream_running.exchange(true)) {
        LOGe("StreamUserPrompt: another stream is already running");
        return napi_make_int(env, 2);
    }
    g_cancel_generation.store(false);

    auto *ctx       = new StreamCtx;
    ctx->prompt     = napi_get_string(env, argv[0]);
    int32_t n_pred  = 100;
    napi_get_value_int32(env, argv[1], &n_pred);
    ctx->n_predict  = n_pred;

    napi_value name_token;
    napi_value name_done;
    napi_create_string_utf8(env, "miniCpmStreamToken", NAPI_AUTO_LENGTH, &name_token);
    napi_create_string_utf8(env, "miniCpmStreamDone",  NAPI_AUTO_LENGTH, &name_done);

    napi_status st;
    st = napi_create_threadsafe_function(env, argv[2], nullptr, name_token,
                                         0, 1, nullptr, nullptr, nullptr,
                                         TokenJsCallback, &ctx->tsfn_token);
    if (st != napi_ok) {
        LOGe("StreamUserPrompt: failed to create tsfn_token (status=%{public}d)", (int) st);
        delete ctx;
        g_stream_running.store(false);
        return napi_make_int(env, 3);
    }
    st = napi_create_threadsafe_function(env, argv[3], nullptr, name_done,
                                         0, 1, nullptr, nullptr, nullptr,
                                         DoneJsCallback, &ctx->tsfn_done);
    if (st != napi_ok) {
        LOGe("StreamUserPrompt: failed to create tsfn_done (status=%{public}d)", (int) st);
        napi_release_threadsafe_function(ctx->tsfn_token, napi_tsfn_release);
        delete ctx;
        g_stream_running.store(false);
        return napi_make_int(env, 4);
    }

    std::thread(stream_worker, ctx).detach();
    return napi_make_int(env, 0);
}

// =============================================================================
// unload / shutdown
// =============================================================================

napi_value Unload(napi_env env, napi_callback_info /*info*/) {
    reset_long_term_states();
    reset_short_term_states();

    if (g_ctx_vision) {
        mtmd_free(g_ctx_vision);
        g_ctx_vision = nullptr;
    }
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    g_context = nullptr;
    llama_model_free(g_model);
    g_model = nullptr;
    g_minicpmv_version = 0;
    return make_undefined(env);
}

napi_value Shutdown(napi_env env, napi_callback_info /*info*/) {
    llama_backend_free();
    return make_undefined(env);
}
