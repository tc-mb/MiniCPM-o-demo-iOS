// JNI bridge for VoxCPM2 TTS via llama.cpp-omni voxcpm2_runtime.
//
// Exposes a minimal API to Kotlin:
//   - init(baseLmPath, acousticPath)      -> bool
//   - generate(text, cfg, tsteps, out)    -> bool
//   - generateWithClone(text, cfg, tsteps, refWav, out) -> bool
//   - free()

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "voxcpm2_runtime.h"
#include "llama.h"

#define TAG "omni_jni"
#define LOG_I(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOG_E(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static VoxCPM2Runtime * g_runtime = nullptr;

static std::string jstringToStdString(JNIEnv * env, jstring jStr) {
    if (!jStr) return {};
    const char * chars = env->GetStringUTFChars(jStr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return result;
}

// Read a WAV file as float32 mono PCM.
static bool readWavF32(const std::string & path, std::vector<float> & pcm, int * sampleRate) {
    FILE * f = fopen(path.c_str(), "rb");
    if (!f) {
        LOG_E("readWavF32: cannot open %s", path.c_str());
        return false;
    }
    // Minimal WAV header parser
    uint8_t header[44];
    if (fread(header, 1, 44, f) != 44) {
        fclose(f);
        return false;
    }
    int16_t numChannels  = header[22] | (header[23] << 8);
    int32_t sr           = header[24] | (header[25] << 8) | (header[26] << 16) | (header[27] << 24);
    int16_t bitsPerSample = header[34] | (header[35] << 8);
    if (sampleRate) *sampleRate = sr;

    if (bitsPerSample != 16) {
        LOG_I("readWavF32: unsupported bits-per-sample %d, trying anyway", bitsPerSample);
    }

    // Read data chunk
    fseek(f, 0, SEEK_END);
    long totalSize = ftell(f) - 44;
    fseek(f, 44, SEEK_SET);
    int totalSamples = totalSize / (bitsPerSample / 8);
    if (numChannels > 1) totalSamples /= numChannels;

    pcm.resize(totalSamples);
    std::vector<int16_t> tmp(totalSamples * numChannels);
    fread(tmp.data(), sizeof(int16_t), totalSamples * numChannels, f);
    fclose(f);

    for (int i = 0; i < totalSamples; ++i) {
        float sum = 0.0f;
        for (int c = 0; c < numChannels; ++c) {
            sum += tmp[i * numChannels + c] / 32768.0f;
        }
        pcm[i] = sum / numChannels;
    }
    return true;
}

// Write float32 mono PCM as 16-bit WAV.
static bool writeWavI16(const std::string & path, const std::vector<float> & pcm, int sampleRate) {
    FILE * f = fopen(path.c_str(), "wb");
    if (!f) {
        LOG_E("writeWavI16: cannot create %s", path.c_str());
        return false;
    }
    int32_t dataSize = (int32_t)pcm.size() * 2;
    int32_t chunkSize = 36 + dataSize;

    // RIFF header
    fwrite("RIFF", 1, 4, f);
    fwrite(&chunkSize, 4, 1, f);
    fwrite("WAVE", 1, 4, f);

    // fmt chunk
    fwrite("fmt ", 1, 4, f);
    int32_t fmtSize = 16;
    fwrite(&fmtSize, 4, 1, f);
    int16_t audioFormat = 1; // PCM
    fwrite(&audioFormat, 2, 1, f);
    int16_t numChannels = 1;
    fwrite(&numChannels, 2, 1, f);
    fwrite(&sampleRate, 4, 1, f);
    int32_t byteRate = sampleRate * numChannels * 2;
    fwrite(&byteRate, 4, 1, f);
    int16_t blockAlign = numChannels * 2;
    fwrite(&blockAlign, 2, 1, f);
    int16_t bitsPerSample = 16;
    fwrite(&bitsPerSample, 2, 1, f);

    // data chunk
    fwrite("data", 1, 4, f);
    fwrite(&dataSize, 4, 1, f);
    for (float sample : pcm) {
        float clamped = std::max(-1.0f, std::min(1.0f, sample));
        int16_t val = (int16_t)(clamped * 32767.0f);
        fwrite(&val, 2, 1, f);
    }
    fclose(f);
    return true;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeInitOmni(
    JNIEnv * env, jclass /* cls */,
    jstring baseLmPath, jstring acousticPath) {

    auto baseLm = jstringToStdString(env, baseLmPath);
    auto acoustic = jstringToStdString(env, acousticPath);

    LOG_I("nativeInitOmni: baseLm=%s acoustic=%s", baseLm.c_str(), acoustic.c_str());

    if (g_runtime) {
        g_runtime->free();
        delete g_runtime;
        g_runtime = nullptr;
    }

    auto * rt = new VoxCPM2Runtime();
    if (!rt->init(baseLm, acoustic, /*n_gpu_layers=*/-1, /*use_gpu_backend=*/false)) {
        LOG_E("nativeInitOmni: init failed: %s", rt->last_error().c_str());
        delete rt;
        return JNI_FALSE;
    }

    g_runtime = rt;
    LOG_I("nativeInitOmni: success");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeTtsGenerate(
    JNIEnv * env, jclass /* cls */,
    jstring text, jfloat cfgValue, jint timesteps,
    jstring refWavPath, jstring outputPath) {

    if (!g_runtime) {
        LOG_E("nativeTtsGenerate: runtime not initialized");
        return JNI_FALSE;
    }

    auto txt = jstringToStdString(env, text);
    auto refPath = jstringToStdString(env, refWavPath);
    auto outPath = jstringToStdString(env, outputPath);

    VoxCPM2GenerateParams params;
    params.cfg_value           = cfgValue;
    params.inference_timesteps = timesteps;
    params.max_steps           = 200;

    std::vector<float> waveform;

    if (!refPath.empty()) {
        // Voice cloning mode
        std::vector<float> refPcm;
        if (!readWavF32(refPath, refPcm, nullptr)) {
            LOG_E("nativeTtsGenerate: failed to read reference WAV");
            return JNI_FALSE;
        }
        waveform = g_runtime->generate_with_clone(txt, refPcm, params);
    } else {
        waveform = g_runtime->generate(txt, params);
    }

    if (waveform.empty()) {
        LOG_E("nativeTtsGenerate: generation produced empty waveform: %s",
              g_runtime->last_error().c_str());
        return JNI_FALSE;
    }

    int sr = g_runtime->sample_rate();
    if (!writeWavI16(outPath, waveform, sr)) {
        LOG_E("nativeTtsGenerate: failed to write output WAV");
        return JNI_FALSE;
    }

    LOG_I("nativeTtsGenerate: success, %zu samples @ %d Hz -> %s",
          waveform.size(), sr, outPath.c_str());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeOmniFree(
    JNIEnv * /* env */, jclass /* cls */) {

    LOG_I("nativeOmniFree");
    if (g_runtime) {
        g_runtime->free();
        delete g_runtime;
        g_runtime = nullptr;
    }
}

} // extern "C"
