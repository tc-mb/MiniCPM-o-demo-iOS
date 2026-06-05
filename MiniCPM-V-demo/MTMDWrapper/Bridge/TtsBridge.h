//
//  TtsBridge.h
//  MiniCPM-V-demo
//
//  Thin C bridge over VoxCPM2Runtime (tools/omni/voxcpm2).
//  Exposes a pure C surface so Swift callers can invoke TTS without
//  enabling C++ interop on every translation unit.
//

#ifndef TTS_BRIDGE_H
#define TTS_BRIDGE_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Initialize VoxCPM2 runtime with two GGUF files.
///
/// @param base_lm_path   Path to VoxCPM2-BaseLM-Q4_K_M.gguf.
/// @param acoustic_path  Path to VoxCPM2-Acoustic-F16.gguf.
/// @return true on success, false on failure.
bool tts_init(const char * base_lm_path, const char * acoustic_path);

/// Generate speech from text and write 16-bit WAV to output_path.
///
/// @param text          Input text to synthesize.
/// @param cfg_value     Classifier-free guidance scale (0.5–5.0, typical 2.0).
/// @param timesteps     Inference timesteps (1–20, typical 5).
/// @param ref_wav_path  Optional reference audio for voice cloning; pass NULL or
///                      empty string to use voice-design mode (no cloning).
/// @param output_path   Output WAV file path (48 kHz mono 16-bit PCM).
/// @return true on success, false on failure.
bool tts_generate(const char * text,
                  float        cfg_value,
                  int          timesteps,
                  const char * ref_wav_path,
                  const char * output_path);

/// Release the VoxCPM2 runtime and free all resources.
void tts_free(void);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // TTS_BRIDGE_H
