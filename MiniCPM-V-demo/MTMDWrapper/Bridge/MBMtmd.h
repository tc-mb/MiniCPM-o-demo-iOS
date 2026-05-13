//
//  MBMtmd.h
//  MiniCPM-V-demo
//
//  Thin C bridge over upstream llama.cpp mtmd public API.
//
//  Replaces the previous demo-private `mtmd-ios.h` that lived inside our
//  llama.cpp fork.  This file deliberately:
//
//    - exposes a pure C surface (no std::string in the header) so it can be
//      consumed via the Swift Bridging Header without enabling C++ interop
//      on every Swift translation unit;
//    - only #includes the public mtmd headers shipped in llama.xcframework
//      (mtmd.h / mtmd-helper.h / llama.h / ggml.h) — no demo-private
//      llama.cpp patches;
//    - keeps the call signatures 1:1 with the old `mtmd_ios_*` family so
//      MTMDWrapper.swift only needs symbol renaming, not behavioural changes.
//
//  The implementation lives in MBMtmd.mm and is built into the iOS app target
//  itself (NOT into the framework), so syncing llama.cpp upstream stays a
//  near-zero-conflict operation.
//

#ifndef MB_MTMD_H
#define MB_MTMD_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque context.
typedef struct mb_mtmd_context mb_mtmd_context;

// Initialization parameters.
//
// Differences vs the legacy `mtmd_ios_params`:
//   - `model_path` / `mmproj_path` are NOT in this struct; they are passed as
//     dedicated arguments to `mb_mtmd_init` so Swift callers don't need to
//     juggle `withCString` lifetimes when populating a struct field.
//   - `coreml_path` is gone (CoreML / ANE path is dropped while we re-sync to
//     upstream master).
//   - `image_max_slice_nums` is replaced by `image_max_tokens`, since the
//     upstream mtmd_context_params uses a token-budget knob instead of a
//     slice-count knob and there is no runtime slice-count override anymore.
//     -1 means "model default" (MiniCPM-V will pick its own slice count).
//   - `n_ubatch` is exposed so the iOS / desktop caller can pick a per-device
//     memory budget.  See MBDeviceMemoryProbe.swift for how the iOS app maps
//     `os_proc_available_memory()` to a tier; `0` means "use bridge default"
//     (currently 512 — a conservative middle ground).
typedef struct mb_mtmd_params {
    int   n_predict;
    int   n_ctx;
    int   n_ubatch;            // physical batch size; dominates GPU compute buffer (set 0 for default)
    int   n_threads;
    float temperature;
    bool  use_gpu;
    bool  mmproj_use_gpu;
    bool  warmup;
    int   image_max_tokens;
} mb_mtmd_params;

// Loop return value.
//
// `token` is a heap-allocated UTF-8 C string owned by the caller; free with
// `mb_mtmd_string_free`.  `is_end == true` signals end-of-generation; in that
// case `token` may be NULL.
typedef struct mb_mtmd_token {
    char * token;
    bool   is_end;
} mb_mtmd_token;

// Default params (mirrors mtmd-cli style: temperature sampling, top_k/p
// disabled to align with MiniCPM-V generation_config.json).
mb_mtmd_params mb_mtmd_params_default(void);

// Construct a context.
//
// `model_path` and `mmproj_path` MUST point to existing GGUF files; both are
// owned by the caller and may be freed as soon as `mb_mtmd_init` returns.
// `params` may be NULL, in which case `mb_mtmd_params_default()` is used.
//
// Returns NULL on failure; failure reasons are written to stderr via the
// standard llama / mtmd log channels.  We can't expose them via
// mb_mtmd_get_last_error here because we never created a ctx to attach them
// to.
mb_mtmd_context * mb_mtmd_init(const char * model_path,
                               const char * mmproj_path,
                               const mb_mtmd_params * params);

// Release all resources owned by the context.
void mb_mtmd_free(mb_mtmd_context * ctx);

// Prefill an image (general "user attached an image" path).  Internally:
//   bitmap = mtmd_helper_bitmap_init_from_file(image_path)
//   chunks = mtmd_tokenize(<__media__>, [bitmap])
//   mtmd_helper_eval_chunks(..., logits_last=false)
// Advances n_past.  Returns 0 on success, non-zero on failure (use
// mb_mtmd_get_last_error for details).
int mb_mtmd_prefill_image(mb_mtmd_context * ctx, const char * image_path);

// Prefill a single video frame.  Currently behaves the same as
// mb_mtmd_prefill_image; kept as a separate entry point so the iOS video
// pipeline can later attach frame-specific markers without touching Swift.
int mb_mtmd_prefill_frame(mb_mtmd_context * ctx, const char * image_path);

// Prefill a chat-formatted text turn.  `role` is one of "user" / "assistant"
// / "system".  Internally wraps `text` in MiniCPM-V 4.6's chatml template
// fragment (the same template the mtmd-ios bridge used to apply manually,
// because the model's GGUF chat_template does not encode the
// enable_thinking=false prefix).  Advances n_past with logits_last=true so
// the next call to mb_mtmd_loop can sample.
int mb_mtmd_prefill_text(mb_mtmd_context * ctx, const char * text, const char * role);

// Sample one token, decode it back into the KV cache, and return it.
// Repeated calls drive generation forward until is_end becomes true (EOG).
mb_mtmd_token mb_mtmd_loop(mb_mtmd_context * ctx);

// Free a token string previously returned via mb_mtmd_token.token.
void mb_mtmd_string_free(char * str);

// Last error message for the given ctx; empty string if no error.  Lifetime
// is tied to ctx; copy if you need to keep it.
const char * mb_mtmd_get_last_error(mb_mtmd_context * ctx);

// Wipe the KV cache (sequence 0) and reset n_past to 0.  Used between turns
// when starting a fresh conversation; does NOT tear down the model.
bool mb_mtmd_clean_kv_cache(mb_mtmd_context * ctx);

// Runtime override of the per-image slice-count knob.
//
// NOTE: this is a no-op on upstream master, which removed the
// runtime-tunable slice-count API.  Slice control now happens at init time
// via mb_mtmd_params.image_max_tokens (translated from the demo's slice
// slider by the Swift layer).  We keep this entry point so the existing
// Swift call site (MTMDWrapper.setImageMaxSliceNums) compiles and links
// without a behavioural surprise — the actual slider UX will need a redesign
// (re-init the context to take effect) tracked separately.
void mb_mtmd_set_image_max_slice_nums(mb_mtmd_context * ctx, int n);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // MB_MTMD_H
