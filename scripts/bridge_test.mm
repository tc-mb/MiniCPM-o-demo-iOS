//
//  bridge_test.mm
//
//  Native (macOS / CLI) smoke-test for the demo's MBMtmd C bridge.
//
//  This program intentionally exercises the SAME call sequence MTMDWrapper.swift
//  uses on iOS:
//
//    mb_mtmd_init(model_path, mmproj_path, &params)
//    mb_mtmd_prefill_image(ctx, image_path)
//    mb_mtmd_prefill_text(ctx, prompt, "user")
//    while (!is_end) { mb_mtmd_loop(ctx) ... }
//    mb_mtmd_clean_kv_cache(ctx)
//    mb_mtmd_free(ctx)
//
//  If this binary streams sensible tokens for a real image, the bridge is
//  correctly wired against upstream master's mtmd public API.  Failures here
//  would otherwise only surface inside the iOS app, where they are slower to
//  diagnose.
//
//  Build / run: see scripts/build_bridge_test.sh.
//

#import "MBMtmd.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

static double now_sec() {
    using namespace std::chrono;
    return duration<double>(steady_clock::now().time_since_epoch()).count();
}

static void usage(const char * argv0) {
    fprintf(stderr,
        "Usage: %s <model.gguf> <mmproj.gguf> <image-or-empty> <prompt>\n"
        "\n"
        "  model.gguf       Path to the MiniCPM-V LLM GGUF (e.g. MiniCPM-V-4_6-Q4_K_M.gguf)\n"
        "  mmproj.gguf      Path to the mmproj GGUF (must be master-compatible)\n"
        "  image-or-empty   Path to a JPEG/PNG, or \"\" to skip image prefill\n"
        "  prompt           User prompt text\n",
        argv0);
}

int main(int argc, char ** argv) {
    if (argc != 5) {
        usage(argv[0]);
        return 2;
    }

    const char * model_path  = argv[1];
    const char * mmproj_path = argv[2];
    const char * image_path  = argv[3];
    const char * prompt      = argv[4];

    mb_mtmd_params params = mb_mtmd_params_default();
    params.n_ctx            = 4096;
    params.n_threads        = 4;
    params.temperature      = 0.7f;
    params.use_gpu          = true;
    params.mmproj_use_gpu   = true;
    params.warmup           = true;
    params.image_max_tokens = -1;  // model default

    // MB_UBATCH env var override — handy for sweeping the n_ubatch knob from
    // the shell when measuring memory / latency tradeoffs on a new device,
    // without recompiling.  0 / unset = let the bridge pick its default.
    if (const char * env = std::getenv("MB_UBATCH")) {
        int v = atoi(env);
        if (v > 0) {
            params.n_ubatch = v;
            fprintf(stderr, "[bridge_test] MB_UBATCH=%d (overriding bridge default)\n", v);
        }
    }

    // MB_IMAGE_MAX_TOKENS env var override — used to sanity-check the
    // tier-based image_max_tokens cap that the iOS app picks via
    // MBDeviceMemoryProbe.recommendedImageMaxTokens (e.g. 64 on tiny
    // tier).  Drop this knob and the model will fall back to its GGUF
    // metadata default (~9 slices for MiniCPM-V 4.6).
    if (const char * env = std::getenv("MB_IMAGE_MAX_TOKENS")) {
        int v = atoi(env);
        params.image_max_tokens = v;
        fprintf(stderr, "[bridge_test] MB_IMAGE_MAX_TOKENS=%d (overriding model default)\n", v);
    }

    fprintf(stderr, "[bridge_test] init: model=%s\n", model_path);
    fprintf(stderr, "[bridge_test] init: mmproj=%s\n", mmproj_path);

    double t0 = now_sec();
    mb_mtmd_context * ctx = mb_mtmd_init(model_path, mmproj_path, &params);
    if (!ctx) {
        fprintf(stderr, "[bridge_test] FAIL: mb_mtmd_init returned NULL\n");
        return 1;
    }
    fprintf(stderr, "[bridge_test] init OK in %.2fs\n", now_sec() - t0);

    if (image_path && image_path[0] != '\0') {
        fprintf(stderr, "[bridge_test] prefill_image: %s\n", image_path);
        t0 = now_sec();
        int rc = mb_mtmd_prefill_image(ctx, image_path);
        if (rc != 0) {
            fprintf(stderr, "[bridge_test] FAIL: mb_mtmd_prefill_image rc=%d, err=%s\n",
                    rc, mb_mtmd_get_last_error(ctx));
            mb_mtmd_free(ctx);
            return 1;
        }
        fprintf(stderr, "[bridge_test] prefill_image OK in %.2fs\n", now_sec() - t0);
    }

    fprintf(stderr, "[bridge_test] prefill_text(role=user): %s\n", prompt);
    t0 = now_sec();
    int rc = mb_mtmd_prefill_text(ctx, prompt, "user");
    if (rc != 0) {
        fprintf(stderr, "[bridge_test] FAIL: mb_mtmd_prefill_text rc=%d, err=%s\n",
                rc, mb_mtmd_get_last_error(ctx));
        mb_mtmd_free(ctx);
        return 1;
    }
    fprintf(stderr, "[bridge_test] prefill_text OK in %.2fs\n", now_sec() - t0);

    fprintf(stderr, "[bridge_test] generating (max 256 tokens)...\n");
    fprintf(stdout, "\n=== generation ===\n");
    fflush(stdout);

    t0 = now_sec();
    int n_tokens = 0;
    std::string full_output;
    const int max_tokens = 256;
    while (n_tokens < max_tokens) {
        mb_mtmd_token tok = mb_mtmd_loop(ctx);
        if (tok.token) {
            fprintf(stdout, "%s", tok.token);
            fflush(stdout);
            full_output += tok.token;
            mb_mtmd_string_free(tok.token);
        }
        n_tokens++;
        if (tok.is_end) break;
    }
    double dt = now_sec() - t0;

    fprintf(stdout, "\n=== /generation ===\n");
    fprintf(stderr, "[bridge_test] generated %d tokens in %.2fs (%.1f tok/s)\n",
            n_tokens, dt, n_tokens / dt);
    fprintf(stderr, "[bridge_test] full_output length=%zu chars\n", full_output.size());

    // Exercise clean_kv_cache + free to make sure their teardown paths are safe.
    if (!mb_mtmd_clean_kv_cache(ctx)) {
        fprintf(stderr, "[bridge_test] WARN: mb_mtmd_clean_kv_cache returned false\n");
    } else {
        fprintf(stderr, "[bridge_test] clean_kv_cache OK\n");
    }

    mb_mtmd_free(ctx);
    fprintf(stderr, "[bridge_test] free OK -- all done\n");
    return 0;
}
