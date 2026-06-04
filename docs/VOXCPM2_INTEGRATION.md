# VoxCPM2 跨平台集成设计文档

> Android 端已完整实现。本文档供 iOS / HarmonyOS agent 参考复用，无需重复探索。

---

## 1. 共享子模块：llama.cpp-omni

三个平台共享同一个 `llama.cpp` 子模块，**以下修改只需做一次**（Android 已完成）。

### 1.1 仓库与分支

```
url: https://github.com/tc-mb/llama.cpp-omni.git
branch: feat/voxcpm
```

当前 `.gitmodules` 内容与 `AGENTS.md` 免责一致，无需改动。

### 1.2 llama.cpp 自身的架构兼容性修改（已完成）

**文件**: `llama.cpp/src/llama-arch.cpp`

**问题**: MiniCPM-V-4.6 的 GGUF 文件使用 `general.architecture = "qwen35"` 作为架构标识，但 `llama.cpp-omni` 内部用 `LLM_ARCH_MINICPM4 -> "minicpm4"` 映射。GGUF KV metadata 也使用 `qwen35.` 前缀（如 `qwen35.context_length`），而 llama.cpp 期望 `minicpm4.` 前缀。

**解决方案**: 在 `llm_arch_from_string()` 之前增加别名解析层，将 `"qwen35"` 映射为 `"minicpm4"`，使架构识别和 KV key 前缀均能得到正确解析。

```cpp
// 1. 顶部添加 include
#include <algorithm>

// 2. LLM_ARCH_NAMES 中保持 minicpm4
{ LLM_ARCH_MINICPM4, "minicpm4" },

// 3. 在 LLM_ARCH_NAMES 之后添加别名表
static const std::map<const char *, const char *> LLM_ARCH_ALIASES = {
    { "qwen35", "minicpm4" },
};

// 4. llm_arch_from_string 修改为先解析别名
llm_arch llm_arch_from_string(const std::string & name) {
    auto alias_it = std::find_if(LLM_ARCH_ALIASES.begin(), LLM_ARCH_ALIASES.end(),
        [&](const auto & p) { return p.first == name; });
    const std::string & resolved = (alias_it != LLM_ARCH_ALIASES.end())
        ? alias_it->second : name;
    for (const auto & kv : LLM_ARCH_NAMES) {
        if (kv.second == resolved) return kv.first;
    }
    return LLM_ARCH_UNKNOWN;
}
```

### 1.3 VoxCPM2 代码位置（已存在于子模块中）

| 路径 | 内容 |
|---|---|
| `tools/omni/voxcpm2/voxcpm2_runtime.h` | C++ 运行时头文件，暴露 `VoxCPM2Runtime` 类 |
| `tools/omni/voxcpm2/voxcpm2_runtime.cpp` | C++ 运行时实现 |
| `tools/omni/voxcpm2/` | 包含 `convert_voxcpm2_to_gguf.py`（GGUF 转换脚本，无需集成） |

**VoxCPM2Runtime C++ API**（`voxcpm2_runtime.h`）：

```cpp
class VoxCPM2Runtime {
public:
    bool init(const std::string & base_lm_path, const std::string & acoustic_path);
    std::string generate(const std::string & text, float cfg_value, int timesteps);
    std::string generate_with_clone(
        const std::string & text,
        const std::string & ref_wav_path,
        float cfg_value, int timesteps
    );
    // 返回 PCM float samples (48kHz mono), 需自行写入 WAV 文件
};
```

---

## 2. 各平台接口层

每个平台需要在其 C++ 桥梁代码中暴露三个函数。以下是各平台对应的实现模板。

### 2.1 Android JNI (`omni_jni.cpp`)

**文件**: `MiniCPM-V-demo-Android/app/src/main/cpp/omni_jni.cpp`

**核心函数**:

```cpp
// 1. 初始化：加载 BaseLM + Acoustic 两个 GGUF 文件
JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeInitOmni(
    JNIEnv * env, jclass, jstring baseLmPath, jstring acousticPath)
{
    auto bp = jstringToStdString(env, baseLmPath);
    auto ap = jstringToStdString(env, acousticPath);
    g_runtime = new VoxCPM2Runtime();
    return g_runtime->init(bp, ap) ? JNI_TRUE : JNI_FALSE;
}

// 2. 生成语音
JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeTtsGenerate(
    JNIEnv * env, jclass,
    jstring text, jfloat cfgValue, jint timesteps,
    jstring refWavPath, jstring outputPath)
{
    auto txt = jstringToStdString(env, text);
    auto out = jstringToStdString(env, outputPath);
    auto ref = jstringToStdString(env, refWavPath);

    std::vector<float> pcm;
    if (ref.empty() || !std::ifstream(ref).good()) {
        pcm = g_runtime->generate(txt, cfgValue, timesteps);
    } else {
        pcm = g_runtime->generate_with_clone(txt, ref, cfgValue, timesteps);
    }

    if (pcm.empty()) return JNI_FALSE;
    return writeWavI16(out, pcm, 48000) ? JNI_TRUE : JNI_FALSE;
}

// 3. 释放
JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_TtsEngine_nativeOmniFree(JNIEnv *, jclass)
{
    delete g_runtime; g_runtime = nullptr;
}
```

**关键细节**：
- `VoxCPM2Runtime::generate()` 返回 `std::vector<float>` PCM（48kHz mono float32）
- 需要转换为 16-bit PCM 并写入 WAV 文件
- iOS / HarmonyOS 需改为平台对应的 FFI 机制（见 2.2 / 2.3）

### 2.2 iOS 对应（Swift → C/C++）

**关键点**：
- iOS 不通过 JNI，直接在 Objective-C++ 桥接
- 在 `MiniCPM-V-demo/Sources/` 下建立类似 `TtsEngine.swift` + `TtsBridge.mm`
- `.mm` 文件引用 `voxcpm2_runtime.h`，通过 C 函数暴露给 Swift

```objc
// TtsBridge.h
bool tts_init(const char * baseLm, const char * acoustic);
bool tts_generate(const char * text, float cfg, int steps,
                  const char * refWav, const char * output);
void tts_free();
```

编译时链接 `voxcpm2_runtime` 和 `omni` 两个静态库 target（iOS 的 CMake 或 Xcode target 需将 `tools/omni` 下源码纳入编译）。

### 2.3 HarmonyOS 对应（ArkTS → C++）

**关键点**：
- HarmonyOS 使用 NAPI（类似 JNI 的 C 接口）
- 在 `MiniCPM-V-demo-HarmonyOS/entry/src/main/cpp/` 下建立 `omni_napi.cpp`

```cpp
// napi_value InitOmni(napi_env env, napi_callback_info info)
//   获取 baseLmPath, acousticPath → new VoxCPM2Runtime() → init()
// napi_value TtsGenerate(...)
//   → generate() / generate_with_clone() → writeWavI16()
// napi_value OmniFree(...)
//   → delete runtime
```

编译时确保 `tools/omni` 源码被 CMake 纳入。

---

## 3. 构建系统

### 3.1 Android（已完成，供参考）

**`app/build.gradle.kts`** — `externalNativeBuild` 块添加 CMake 参数：

```kotlin
arguments += "-DLLAMA_BUILD_TOOLS=ON"   // 启用 tools/omni 构建
arguments += "-DLLAMA_CURL=OFF"          // 禁用 CURL（NDK 缺失）
```

**`app/src/main/cpp/CMakeLists.txt`** — 关键修改：

```cmake
# 添加 include 路径
include_directories(${LLAMA_SRC}/tools/omni)
include_directories(${LLAMA_SRC}/tools/omni/voxcpm2)

# 源文件列表加入 omni_jni.cpp
add_library(minicpm_v_demo SHARED
    llama_jni.cpp
    omni_jni.cpp    # <-- 新增
    ...
)

# 链接 omni 和 voxcpm2_runtime 库
target_link_libraries(minicpm_v_demo
    common              # 注意: 此 fork 中库名是 common，不是 llama-common
    omni                # tools/omni 构建产物
    voxcpm2_runtime     # tools/omni/voxcpm2 构建产物
    ...
)
```

### 3.2 iOS

iOS 的 `scripts/build_xcframework.sh` 需确保 `LLAMA_BUILD_TOOLS=ON`（默认 sminimal mode 可能关闭）。需要将 `tools/omni` 和 `tools/omni/voxcpm2` 的 `.cpp` 文件链接进 XCFramework 或 App target。

### 3.3 HarmonyOS

在 `entry/src/main/cpp/CMakeLists.txt` 中参照 Android 的修改，添加 include 路径、源文件、链接依赖。

---

## 4. 模型定义（各平台共享逻辑）

### 4.1 Android: `ModelInfo.kt`

```kotlin
data class ModelInfo(
    val id: String,
    val displayName: String,
    val descriptionRes: Int,
    val ggufFileName: String,
    val mmprojFileName: String? = null,
    // === VoxCPM2 新增字段 ===
    val acousticFileName: String? = null,
    val acousticRemoteName: String? = null,
    val directAcousticUrl: String? = null,
    val acousticMd5: String? = null,
    // ... 其他字段
) {
    val isTts: Boolean get() = acousticFileName != null
    val isTextOnly: Boolean get() = mmprojFileName == null && acousticFileName == null
}

// VoxCPM2 模型条目
ModelInfo(
    id = "voxcpm2",
    displayName = "VoxCPM2 (TTS)",
    ggufFileName = "VoxCPM2-BaseLM-Q4_K_M.gguf",
    acousticFileName = "VoxCPM2-Acoustic-F16.gguf",
    directGgufUrl = "<OBS URL>",
    directAcousticUrl = "<OBS URL>",
    ggufMd5 = "d8cd571526464d225187d326caa289be",
    acousticMd5 = "0f16229cfffe935102d21433f6969f8b",
    // ...
)
```

**iOS / HarmonyOS 对应**：在各平台的 ModelConst 或模型定义文件中增加同样字段。

### 4.2 模型下载

VoxCPM2 需要下载 **两个** GGUF 文件：
- `VoxCPM2-BaseLM-Q4_K_M.gguf`（BaseLM，约 1.0 GB，Q4_K_M 量化）
- `VoxCPM2-Acoustic-F16.gguf`（Acoustic，约 1.8 GB，F16 全精度）
- 合计约 **2.8 GB**

> **量化说明**：BaseLM 从 F16（~3.1 GB）量化到 Q4_K_M（~1.0 GB），体积减少约 65%，推理速度提升。Acoustic 保持 F16 精度以确保音质。

两个文件放在同一个模型子目录下（如 `files/models/voxcpm2/`）。

**下载来源**：
- HuggingFace: `https://huggingface.co/openbmb/VoxCPM2`
- ModelScope: `https://www.modelscope.cn/models/OpenBMB/VoxCPM2`

---

## 5. 引擎层（Kotlin → Swift / ArkTS 对应）

### 5.1 Android 实现: `TtsEngine.kt`

**状态机**:

```kotlin
sealed class TtsState {
    object Uninitialized : TtsState()
    object Initializing : TtsState()    // native 库加载中
    object LoadingModel : TtsState()    // BaseLM + Acoustic 加载中
    object Ready : TtsState()           // 就绪，可生成
    object Generating : TtsState()      // 生成进行中
    data class Error(val e: Exception) : TtsState()
}
```

**核心逻辑**:

```
init() → loadModel(baseLmPath, acousticPath) → Ready
                                                     ↓
                                                generate(text, cfg, steps, refPath?, outputPath)
                                                     ↓
                                                Ready (or Error)
```

**关键细节**：
- 单例模式（`@Volatile private var instance`）
- 单线程调度器（`Dispatchers.IO.limitedParallelism(1)`），避免并发生成
- 通过 `MutableStateFlow<TtsState>` 驱动 UI 状态

### 5.2 iOS 对应

```swift
enum TtsState {
    case uninitialized, initializing, loadingModel, ready, generating
    case error(Error)
}

class TtsEngine {
    static let shared = TtsEngine()
    @Published var state: TtsState = .uninitialized
    // ...

    func loadModel() async throws {
        state = .loadingModel
        guard tts_init(baseLm, acoustic) else { throw ... }
        state = .ready
    }

    func generate(text: String, cfg: Float, steps: Int,
                  refWav: String?, output: String) async throws {
        state = .generating
        guard tts_generate(text, cfg, steps, refWav ?? "", output) else { throw ... }
        state = .ready
    }
}
```

### 5.3 HarmonyOS 对应

参考 Android 的 Kotlin 实现，使用 `@State` 管理 UI 状态，NAPI 异步调用。

---

## 6. UI 层（TtsActivity 功能清单）

### 6.1 功能列表

| 功能 | 说明 |
|---|---|
| 文本输入 | 多行文本输入，Voice Design 模式用 `(描述)文本` 格式 |
| CFG 滑块 | 0.5–5.0，默认 2.0 |
| 推理步数滑块 | 0–20，默认 5。>8 时显示警告提示 |
| 录音 | 16kHz mono 16-bit WAV，作为 Voice Cloning 参考音频 |
| 预设参考音频 | 内置默认女声/默认男声（需 resources/assets 中嵌入 WAV） |
| 试听/清除参考音频 | 播放和删除当前选中的参考音频 |
| 生成/取消 | 生成中按钮变为"取消生成" |
| 播放 | 生成的语音播放，进度条显示 |
| 模型切换跳转 | onResume 检测非 TTS 模型时自动跳回聊天界面 |

### 6.2 音频播放注意事项

**录音采样率 16kHz** 和 **VoxCPM2 输出 48kHz** 不同，播放时必须从 WAV 头部读取真实采样率，不能硬编码。

```kotlin
// 从 WAV header 读取采样率
val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                 ((header[26].toInt() and 0xFF) shl 16) or
                 ((header[25].toInt() and 0xFF) shl 8) or
                 (header[24].toInt() and 0xFF)

// 使用 MODE_STREAM 而非 MODE_STATIC
AudioTrack(..., AudioTrack.MODE_STREAM, 0)
// Static 要求 buffer >= 全部数据，48kHz 语音可达数 MB
// Stream 分块写入，buffer 只需几 KB
```

### 6.3 预设参考音频

需将 `默认女声.wav` 和 `默认男声.wav` 放入平台资源目录：
- **Android**: `app/src/main/assets/ref_audios/`
- **iOS**: Bundle Resources 或 Assets.xcassets
- **HarmonyOS**: `entry/src/main/resources/rawfile/`

---

## 7. 主页面集成

### 7.1 入口跳转

当用户选择 TTS 模型时，主页面（聊天界面）应自动跳转到 TTS 界面。

**Android**: `MainActivity.onCreate()` / `onResume()` 检测 `getSelectedModel().isTts` → 跳转 `TtsActivity`。

### 7.2 返回跳转

当用户在 TTS 界面通过设置切换到非 TTS 模型时，`TtsActivity.onResume()` 检测后自动 `finish()` 并跳回主页面。

---

## 8. 常见问题

| 问题 | 原因 | 解决 |
|---|---|---|
| `unknown model architecture: 'qwen35'` | MiniCPM-V GGUF 用 qwen35 架构名 | 已在 llama-arch.cpp 添加别名映射 |
| `failed to initialize BaseLM` | Acoustic GGUF 的 `minicpm4.embedding_scale` key 不匹配 | 确认 arch 名保持 `minicpm4`，不要改成 `qwen35` |
| `AudioTrack init failed` | MODE_STATIC + buffer 不足 | 改用 MODE_STREAM |
| 生成特别慢（几分钟） | 手机端跑 VoxCPM2 克隆模式极慢 | 默认步数设为 5，>8 显示警告 |
| "试听"按钮不出声 | 硬编码 48kHz 播放 16kHz 音频 | 从 WAV 头读取真实采样率 |

---

## 9. 文件变更总览

### llama.cpp 子模块（共享，已完成）

| 文件 | 变更 |
|---|---|
| `src/llama-arch.cpp` | 添加 `qwen35` → `minicpm4` 别名映射 |

### Android 应用层（各平台需对应实现）

| 文件 | 变更 |
|---|---|
| `app/build.gradle.kts` | CMake 添加 `LLAMA_BUILD_TOOLS=ON`, `LLAMA_CURL=OFF` |
| `app/src/main/cpp/CMakeLists.txt` | include omni/voxcpm2 路径，链接 omni + voxcpm2_runtime 库 |
| `app/src/main/cpp/omni_jni.cpp` | **新增**: JNI 桥接（init/generate/free） |
| `app/src/main/cpp/llama_jni.cpp` | 适配 mtmd API 变更 |
| `ModelInfo.kt` | 添加 `acousticFileName` / `isTts` / VoxCPM2 条目 |
| `LlamaEngine.kt` | 添加 `acousticPath()`, `modelsExist()` 兼容 TTS |
| `TtsEngine.kt` | **新增**: StateFlow 驱动的 TTS 引擎 |
| `AudioRecorder.kt` | **新增**: 16kHz 录音工具类 |
| `TtsActivity.kt` | **新增**: 完整 TTS 界面 |
| `activity_tts.xml` | **新增**: TTS 布局 |
| `strings.xml` | 添加 TTS 相关字符串 |
| `AndroidManifest.xml` | 添加 `RECORD_AUDIO` 权限 + `TtsActivity` 声明 |
| `MainActivity.kt` | 添加 `shouldRedirectToTts()` 跳转逻辑 |
| `ModelManagerActivity.kt` | 添加 TTS 模型选择后的标记逻辑 |

---

*文档最后更新: 2026-06-02*
