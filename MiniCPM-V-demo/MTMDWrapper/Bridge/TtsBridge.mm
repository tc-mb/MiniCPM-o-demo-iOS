//
//  TtsBridge.mm
//  MiniCPM-V-demo
//
//  Implementation of the TtsBridge C wrapper over VoxCPM2Runtime.
//  Handles WAV I/O (float32 <-> int16) and delegates to the runtime.
//

#import "TtsBridge.h"

#include <llama/voxcpm2_runtime.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace {

struct RiffChunkHeader {
    char     id[4];
    uint32_t size;
};

/// Read a WAV file and return float32 mono samples (normalized to [-1, 1]).
/// Returns empty vector on failure.
std::vector<float> readWavF32(const char * path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) { return {}; }

    char riff_id[4];
    uint32_t file_size;
    char wave_id[4];
    in.read(riff_id, 4);
    in.read(reinterpret_cast<char *>(&file_size), 4);
    in.read(wave_id, 4);
    if (!in || std::strncmp(riff_id, "RIFF", 4) != 0 ||
        std::strncmp(wave_id, "WAVE", 4) != 0) {
        return {};
    }

    uint16_t num_channels   = 1;
    uint32_t sample_rate    = 16000;
    uint16_t bits_per_sample = 16;

    // Parse chunks until we find "fmt " and "data"
    bool have_fmt  = false;
    bool have_data = false;
    std::vector<float> samples;

    while (in) {
        RiffChunkHeader chunk;
        in.read(reinterpret_cast<char *>(&chunk), sizeof(chunk));
        if (!in) { break; }

        if (std::strncmp(chunk.id, "fmt ", 4) == 0) {
            // Read format chunk (at most 40 bytes, enough for PCM + extended)
            uint8_t fmt_buf[40] = {};
            size_t nRead = std::min(static_cast<uint32_t>(sizeof(fmt_buf)), chunk.size);
            in.read(reinterpret_cast<char *>(fmt_buf), nRead);
            if (nRead < 16) { return {}; }
            // Skip any remaining fmt bytes
            if (chunk.size > nRead) {
                in.seekg(chunk.size - nRead, std::ios::cur);
            }
            uint16_t audio_format    = *reinterpret_cast<uint16_t *>(fmt_buf + 0);
            num_channels             = *reinterpret_cast<uint16_t *>(fmt_buf + 2);
            sample_rate              = *reinterpret_cast<uint32_t *>(fmt_buf + 4);
            bits_per_sample          = *reinterpret_cast<uint16_t *>(fmt_buf + 14);
            have_fmt = true;
            if (audio_format != 1 && audio_format != 0xFFFE) {
                // Only PCM (1) or WAVE_FORMAT_EXTENSIBLE (0xFFFE) supported
                return {};
            }
        } else if (std::strncmp(chunk.id, "data", 4) == 0) {
            if (!have_fmt) { return {}; }

            size_t sample_count = chunk.size / (bits_per_sample / 8);
            samples.resize(sample_count);

            if (bits_per_sample == 16) {
                std::vector<int16_t> raw(sample_count);
                in.read(reinterpret_cast<char *>(raw.data()), chunk.size);
                for (size_t i = 0; i < sample_count; ++i) {
                    samples[i] = static_cast<float>(raw[i]) / 32768.0f;
                }
            } else if (bits_per_sample == 8) {
                std::vector<uint8_t> raw(sample_count);
                in.read(reinterpret_cast<char *>(raw.data()), chunk.size);
                for (size_t i = 0; i < sample_count; ++i) {
                    samples[i] = (static_cast<float>(raw[i]) - 128.0f) / 128.0f;
                }
            } else {
                return {};
            }

            // Convert to mono if stereo
            if (num_channels == 2) {
                std::vector<float> mono(samples.size() / 2);
                for (size_t i = 0; i < mono.size(); ++i) {
                    mono[i] = (samples[2 * i] + samples[2 * i + 1]) * 0.5f;
                }
                samples.swap(mono);
            }
            have_data = true;
            break;
        } else {
            // Skip unknown chunk
            in.seekg(chunk.size, std::ios::cur);
        }
    }

    if (!have_data) { return {}; }
    return samples;
}

/// Write float32 mono samples as 16-bit PCM WAV file.
/// Returns true on success.
bool writeWavI16(const char * path, const std::vector<float> & pcm, int sample_rate) {
    std::ofstream out(path, std::ios::binary);
    if (!out) { return false; }

    uint32_t data_size = static_cast<uint32_t>(pcm.size() * 2); // 16-bit = 2 bytes/sample
    uint32_t file_size = 36 + data_size; // header (44 bytes) - 8 bytes of "RIFF" + size

    // RIFF header
    out.write("RIFF", 4);
    out.write(reinterpret_cast<const char *>(&file_size), 4);
    out.write("WAVE", 4);

    // fmt chunk
    out.write("fmt ", 4);
    uint32_t fmt_size = 16;
    uint16_t audio_format = 1; // PCM
    uint16_t num_channels = 1;
    uint16_t bits_per_sample = 16;
    uint32_t byte_rate = sample_rate * num_channels * bits_per_sample / 8;
    uint16_t block_align = num_channels * bits_per_sample / 8;

    out.write(reinterpret_cast<const char *>(&fmt_size), 4);
    out.write(reinterpret_cast<const char *>(&audio_format), 2);
    out.write(reinterpret_cast<const char *>(&num_channels), 2);
    out.write(reinterpret_cast<const char *>(&sample_rate), 4);
    out.write(reinterpret_cast<const char *>(&byte_rate), 4);
    out.write(reinterpret_cast<const char *>(&block_align), 2);
    out.write(reinterpret_cast<const char *>(&bits_per_sample), 2);

    // data chunk
    out.write("data", 4);
    out.write(reinterpret_cast<const char *>(&data_size), 4);

    // Convert float to int16
    for (float sample : pcm) {
        float clamped = sample;
        if (clamped > 1.0f) clamped = 1.0f;
        if (clamped < -1.0f) clamped = -1.0f;
        int16_t val = static_cast<int16_t>(clamped * 32767.0f);
        out.write(reinterpret_cast<const char *>(&val), 2);
    }

    return out.good();
}

// ---- Global runtime instance ----

static VoxCPM2Runtime * g_runtime = nullptr;

} // anonymous namespace

// ---- C API ----

bool tts_init(const char * base_lm_path, const char * acoustic_path) {
    if (!base_lm_path || !acoustic_path) { return false; }
    if (g_runtime) { delete g_runtime; }

    g_runtime = new VoxCPM2Runtime();
    // use_gpu_backend=false: PAD shader in Metal only supports dim-0 padding;
    // VoxCPM2 LocEnc/LocDiT needs multi-dim PAD, falls back to CPU.
    return g_runtime->init(base_lm_path, acoustic_path, -1, false);
}

bool tts_generate(const char * text,
                  float        cfg_value,
                  int          timesteps,
                  const char * ref_wav_path,
                  const char * output_path) {
    if (!g_runtime || !text || !output_path) { return false; }

    VoxCPM2GenerateParams params;
    params.cfg_value           = cfg_value;
    params.inference_timesteps = timesteps;

    std::vector<float> pcm;

    bool has_ref = (ref_wav_path != nullptr && std::strlen(ref_wav_path) > 0);
    if (has_ref) {
        // Read reference audio
        std::vector<float> ref_pcm = readWavF32(ref_wav_path);
        if (ref_pcm.empty()) {
            // Fall back to voice-design mode if ref audio can't be loaded
            pcm = g_runtime->generate(text, params);
        } else {
            pcm = g_runtime->generate_with_clone(text, ref_pcm, params);
        }
    } else {
        pcm = g_runtime->generate(text, params);
    }

    if (pcm.empty()) { return false; }

    return writeWavI16(output_path, pcm, 48000);
}

void tts_free(void) {
    delete g_runtime;
    g_runtime = nullptr;
}
