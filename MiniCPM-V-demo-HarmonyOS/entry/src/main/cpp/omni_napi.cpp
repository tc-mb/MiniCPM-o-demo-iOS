// HarmonyOS NAPI bridge for VoxCPM2 TTS via llama.cpp-omni voxcpm2_runtime.
//
// initOmni and ttsGenerate spawn a std::thread for the heavy native call
// (loading 2.8 GB GGUF / 10-60 s CFM decode loop), then resolve/reject the
// JS Promise via napi_threadsafe_function once the worker finishes.
// Same pattern as streamUserPrompt in llama_napi.cpp — proven on HarmonyOS.
// napi_async_work was also tried but did not reliably offload the main
// thread (THREAD_BLOCK_3S watchdog still fired at the 3 s mark).

#include "napi/native_api.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "hilog_log.h"
#include "voxcpm2_runtime.h"

static VoxCPM2Runtime * g_runtime = nullptr;

// =============================================================================
// NAPI helpers
// =============================================================================

static std::string napi_get_string(napi_env env, napi_value v) {
    size_t len = 0;
    napi_get_value_string_utf8(env, v, nullptr, 0, &len);
    std::string s(len, '\0');
    if (len > 0) napi_get_value_string_utf8(env, v, &s[0], len + 1, &len);
    return s;
}

static napi_value napi_make_int(napi_env env, int v) {
    napi_value out = nullptr;
    napi_create_int32(env, v, &out);
    return out;
}

static napi_value make_undefined(napi_env env) {
    napi_value u = nullptr;
    napi_get_undefined(env, &u);
    return u;
}

// =============================================================================
// WAV I/O (verbatim from omni_jni.cpp)
// =============================================================================

static bool writeWavI16(const std::string & path, const std::vector<float> & pcm, int sr) {
    FILE * f = fopen(path.c_str(), "wb");
    if (!f) return false;
    int32_t dataSize = (int32_t)pcm.size() * 2;
    int32_t chunkSize = 36 + dataSize;
    fwrite("RIFF", 1, 4, f); fwrite(&chunkSize, 4, 1, f); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); int32_t fs = 16; fwrite(&fs, 4, 1, f);
    int16_t af = 1; fwrite(&af, 2, 1, f); int16_t nc = 1; fwrite(&nc, 2, 1, f);
    fwrite(&sr, 4, 1, f); int32_t br = sr * 2; fwrite(&br, 4, 1, f);
    int16_t ba = 2; fwrite(&ba, 2, 1, f); int16_t bp = 16; fwrite(&bp, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&dataSize, 4, 1, f);
    for (float s : pcm) {
        float clamped = std::max(-1.0f, std::min(1.0f, s));
        int16_t val = (int16_t)(clamped * 32767.0f);
        fwrite(&val, 2, 1, f);
    }
    fclose(f);
    return true;
}

static bool readWavF32(const std::string & path, std::vector<float> & pcm, int * srOut) {
    FILE * f = fopen(path.c_str(), "rb");
    if (!f) return false;
    uint8_t header[44];
    if (fread(header, 1, 44, f) != 44) { fclose(f); return false; }
    int16_t numCh = header[22] | (header[23] << 8);
    int32_t srate = header[24] | (header[25] << 8) | (header[26] << 16) | (header[27] << 24);
    int16_t bps   = header[34] | (header[35] << 8);
    if (srOut) *srOut = srate;
    fseek(f, 0, SEEK_END);
    long totalSize = ftell(f) - 44;
    fseek(f, 44, SEEK_SET);
    int totalSamples = totalSize / (bps / 8);
    if (numCh > 1) totalSamples /= numCh;
    pcm.resize(totalSamples);
    std::vector<int16_t> tmp(totalSamples * numCh);
    fread(tmp.data(), sizeof(int16_t), totalSamples * numCh, f);
    fclose(f);
    for (int i = 0; i < totalSamples; ++i) {
        float sum = 0.0f;
        for (int c = 0; c < numCh; ++c) sum += tmp[i * numCh + c] / 32768.0f;
        pcm[i] = sum / numCh;
    }
    return true;
}

// =============================================================================
// std::thread + napi_threadsafe_function async helpers
//
// Pattern (same as streamUserPrompt in llama_napi.cpp):
//   1. Create a napi_deferred (Promise) on the JS thread
//   2. Create a napi_threadsafe_function that, when called from any
//      thread, invokes a callback on the JS thread
//   3. The callback resolves/rejects the deferred
//   4. Spawn a std::thread that does the heavy work
//   5. When the worker finishes, call napi_call_threadsafe_function
//      with the result code (void* cast)
// =============================================================================

struct AsyncOp {
    napi_threadsafe_function  tsfn     = nullptr;
    napi_deferred             deferred = nullptr;
    napi_env                  env      = nullptr;
    std::string               baseLmPath;
    std::string               acousticPath;
    std::string               text;
    std::string               refWavPath;
    std::string               outputPath;
    std::string               errorMsg;
    float                     cfgValue          = 2.0f;
    int                       timesteps         = 5;
    int                       rc                = 0;
    bool                      isInit            = false;
};

// The actual tsfn JS callback. data = (void*)(intptr_t)rc.
static void InitOmniWorker(AsyncOp * op) {
    // LOG macros may not reach hilog from detached std::threads on
    // HarmonyOS, so we capture everything into AsyncOp::errorMsg and
    // print it from the JS-thread callback instead.
    op->rc = 0;

    try {
        if (g_runtime) {
            g_runtime->free();
            delete g_runtime;
            g_runtime = nullptr;
        }

        auto * rt = new VoxCPM2Runtime();
        if (!rt->init(op->baseLmPath, op->acousticPath,
                      /*n_gpu_layers=*/-1, /*use_gpu_backend=*/false)) {
            op->errorMsg = rt->last_error();
            delete rt;
            op->rc = 1;
        } else {
            g_runtime = rt;
        }
    } catch (const std::exception & e) {
        op->errorMsg = std::string("exception: ") + e.what();
        op->rc = 1;
    } catch (...) {
        op->errorMsg = "unknown exception";
        op->rc = 1;
    }

    napi_call_threadsafe_function(op->tsfn, (void *)op, napi_tsfn_blocking);
    napi_release_threadsafe_function(op->tsfn, napi_tsfn_release);
    // AsyncOp is deleted by ResolveDeferredCallback on the JS thread.
}

static void TtsGenerateWorker(AsyncOp * op) {
    op->rc = 0;

    try {
        if (!g_runtime) {
            op->errorMsg = "runtime not initialized";
            op->rc = 1;
            napi_call_threadsafe_function(op->tsfn, (void *)op, napi_tsfn_blocking);
            napi_release_threadsafe_function(op->tsfn, napi_tsfn_release);
            return;
        }

        VoxCPM2GenerateParams params;
        params.cfg_value           = op->cfgValue;
        params.inference_timesteps = op->timesteps;
        params.max_steps           = 200;

        std::vector<float> waveform;

        if (!op->refWavPath.empty()) {
            std::vector<float> refPcm;
            if (!readWavF32(op->refWavPath, refPcm, nullptr)) {
                op->errorMsg = "failed to read reference WAV";
                op->rc = 1;
                goto done;
            }
            waveform = g_runtime->generate_with_clone(op->text, refPcm, params);
        } else {
            waveform = g_runtime->generate(op->text, params);
        }

        if (waveform.empty()) {
            op->errorMsg = g_runtime->last_error();
            op->rc = 1;
            goto done;
        }

        if (!writeWavI16(op->outputPath, waveform, g_runtime->sample_rate())) {
            op->errorMsg = "failed to write output WAV";
            op->rc = 1;
            goto done;
        }
    } catch (const std::exception & e) {
        op->errorMsg = std::string("exception: ") + e.what();
        op->rc = 1;
    } catch (...) {
        op->errorMsg = "unknown exception";
        op->rc = 1;
    }

done:
    napi_call_threadsafe_function(op->tsfn, (void *)op, napi_tsfn_blocking);
    napi_release_threadsafe_function(op->tsfn, napi_tsfn_release);
}

// tsfn JS callback.  data = AsyncOp* (worker fills rc + errorMsg).
// Resolves/rejects the JS Promise with either "OK" or the error text,
// so ArkTS callers can log the diagnostic directly.
static void ResolveDeferredCallback(napi_env env, napi_value /*js_cb*/, void * context, void * data) {
    if (env == nullptr || context == nullptr || data == nullptr) {
        if (context != nullptr) delete static_cast<napi_deferred *>(context);
        if (data != nullptr) delete static_cast<AsyncOp *>(data);
        return;
    }

    AsyncOp * op = static_cast<AsyncOp *>(data);
    auto * deferred = static_cast<napi_deferred *>(context);

    if (op->rc == 0) {
        napi_value ok;
        napi_create_string_utf8(env, "OK", NAPI_AUTO_LENGTH, &ok);
        napi_resolve_deferred(env, *deferred, ok);
    } else {
        std::string msg = op->errorMsg.empty()
            ? "unknown native error"
            : op->errorMsg;
        napi_value err;
        napi_create_string_utf8(env, msg.c_str(), NAPI_AUTO_LENGTH, &err);
        napi_reject_deferred(env, *deferred, err);
    }
    delete deferred;
    delete op;
}

static napi_value MakeAsyncPromise(napi_env env, napi_deferred deferred,
                                   void (*worker)(AsyncOp *), AsyncOp * op) {
    // Store the deferred on the heap so the tsfn callback can reference it
    auto * deferredOnHeap = new napi_deferred(deferred);

    napi_value tsfn_name;
    napi_create_string_utf8(env, "miniCpmAsyncOp", NAPI_AUTO_LENGTH, &tsfn_name);

    napi_status st = napi_create_threadsafe_function(
        env, nullptr, nullptr, tsfn_name,
        0, 1, nullptr, nullptr, deferredOnHeap,
        ResolveDeferredCallback, &op->tsfn);
    if (st != napi_ok) {
        LOGe("MakeAsyncPromise: napi_create_threadsafe_function failed (status=%{public}d)", (int) st);
        delete deferredOnHeap;
        delete op;
        napi_value v = napi_make_int(env, 3);
        napi_reject_deferred(env, deferred, v);
        return nullptr;
    }

    LOGi("MakeAsyncPromise: spawning worker thread");
    std::thread(worker, op).detach();
    LOGi("MakeAsyncPromise: thread detached, returning promise");
    return nullptr;  // caller returns the promise
}

// =============================================================================
// initOmni (async)
// =============================================================================

napi_value InitOmni(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    napi_value promise;
    napi_deferred deferred;
    napi_create_promise(env, &deferred, &promise);

    if (argc < 2) {
        napi_value v = napi_make_int(env, 1);
        napi_reject_deferred(env, deferred, v);
        return promise;
    }

    auto * op      = new AsyncOp;
    op->isInit     = true;
    op->baseLmPath   = napi_get_string(env, argv[0]);
    op->acousticPath = napi_get_string(env, argv[1]);

    LOGi("InitOmni: baseLm=%{public}s acoustic=%{public}s",
         op->baseLmPath.c_str(), op->acousticPath.c_str());

    MakeAsyncPromise(env, deferred, InitOmniWorker, op);
    return promise;
}

// =============================================================================
// ttsGenerate (async)
// =============================================================================

napi_value TtsGenerate(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value argv[5];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    napi_value promise;
    napi_deferred deferred;
    napi_create_promise(env, &deferred, &promise);

    if (argc < 5 || !g_runtime) {
        napi_value v = napi_make_int(env, 1);
        napi_reject_deferred(env, deferred, v);
        return promise;
    }

    auto * op     = new AsyncOp;
    op->isInit    = false;
    op->text       = napi_get_string(env, argv[0]);
    op->refWavPath = napi_get_string(env, argv[3]);
    op->outputPath = napi_get_string(env, argv[4]);

    double cfgDbl = 0;
    napi_get_value_double(env, argv[1], &cfgDbl);
    op->cfgValue = (float)cfgDbl;

    int32_t ts = 10;
    napi_get_value_int32(env, argv[2], &ts);
    op->timesteps = ts;

    MakeAsyncPromise(env, deferred, TtsGenerateWorker, op);
    return promise;
}

// =============================================================================
// ttsFree — synchronous
// =============================================================================

napi_value TtsFree(napi_env env, napi_callback_info /*info*/) {
    LOGi("TtsFree");
    if (g_runtime) {
        g_runtime->free();
        delete g_runtime;
        g_runtime = nullptr;
    }
    return make_undefined(env);
}
