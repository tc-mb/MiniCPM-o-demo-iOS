# MiniCPM-V Demo — iOS、Android 与 HarmonyOS

[English](README.md) | **中文**

本项目演示了 MiniCPM-V 系列多模态模型在 iOS、Android 与 HarmonyOS NEXT 设备上的端侧本地推理。当前已支持以下三个模型版本：

* **MiniCPM-V 2.6**
* **MiniCPM-V 4.0**
* **MiniCPM-V 4.6**

仓库中包含三份基于 `llama.cpp` 完整本地推理的 demo：

* `MiniCPM-V-demo/` — iOS demo（Xcode 工程）
* `MiniCPM-V-demo-Android/` — Android demo（Gradle / Kotlin）
* `MiniCPM-V-demo-HarmonyOS/` — HarmonyOS NEXT demo（DevEco Studio / ArkTS）

三端共享仓库根目录的同一份 `llama.cpp` 子模块（分支 `MiniCPM-V`）。

> **提示**：本项目通过 git submodule 引入 `llama.cpp`。子模块上游 `tc-mb/llama.cpp` 还有十几个无关分支，全量 clone 要拉 ~350 MB，所以 `.gitmodules` 里默认 `shallow = true`。推荐用如下 **shallow + single-branch** 方式 clone：
>
> ```bash
> # 一步到位（父仓库 + 子模块都浅 clone）
> git clone --recurse-submodules --shallow-submodules \
>     https://github.com/OpenBMB/MiniCPM-V-Apps.git
> cd MiniCPM-V-Apps
> ```
>
> 如果你已经 clone 了父仓库再 init 子模块：
>
> ```bash
> git submodule update --init --recursive --depth 1 --single-branch
> ```
>
> 这样只会拉 `MiniCPM-V` 分支的单 commit（约几十 MB），不会拉整个 llama.cpp fork 的全 history。需要 push 到 `tc-mb/llama.cpp:MiniCPM-V` 的开发者可在 submodule 里执行 `git fetch --unshallow` 取消浅 clone。

README 分为两大部分：

* **第一部分 — 平台安装与构建**：iOS / Android / HarmonyOS 三端如何编译运行。
* **第二部分 — GGUF 模型文件**：三个 MiniCPM-V 版本的模型权重下载方式，以及对应的最小端侧硬件要求。

---

> **只想安装 App？** TestFlight（iOS）/ APK（Android）/ HAP（HarmonyOS）的预编译安装包与详细安装步骤请见 **[DOWNLOAD_zh.md](DOWNLOAD_zh.md)**。下面的内容只在你打算从源码自行构建时才需要。

---

## 第一部分　平台安装与构建

### 1.1 iOS Demo

**注意：在 iOS 设备上部署和测试 demo，可能需要 Apple Developer 账号。**

安装 Xcode：

* 在 App Store 下载 Xcode
* 安装命令行工具：

  ```bash
  xcode-select --install
  ```
* 同意软件许可协议：

  ```bash
  sudo xcodebuild -license
  ```

用 Xcode 打开 `MiniCPM-V-demo/MiniCPM-V-demo.xcodeproj`，等待 Xcode 自动下载所需依赖。

在 Xcode 顶部选择目标设备，点击 "Run"（三角形）按钮启动 demo。

**首次构建必须执行：** 仓库不再追踪预编译的 `llama.xcframework` 二进制（~189 MB），请按下方步骤从 `llama.cpp` 子模块本地构建一份再放回 `MiniCPM-V-demo/thirdparty/`。

#### 构建 llama.xcframework

直接在子模块内构建（无需重复 clone）：

```bash
cd llama.cpp
./build-xcframework.sh
cp -r ./build-apple/llama.xcframework ../MiniCPM-V-demo/thirdparty
```

### 1.2 Android Demo

环境要求：

* Android Studio（Giraffe 或更新版本）
* Android SDK + NDK（项目固定 NDK `28.2.13676358`、CMake `3.22.1`）
* 64 位 ARM 架构（`arm64-v8a`）的真机
* 设备内存：参见[第二部分](#第二部分gguf-模型文件)中按模型给出的内存要求

构建并运行：

```bash
cd MiniCPM-V-demo-Android
./gradlew assembleDebug
```

或直接用 Android Studio 打开 `MiniCPM-V-demo-Android/` 目录，点击 Run。

首次启动时，应用会自动把 GGUF 模型文件下载到外部存储。也可以通过 `adb push` 手动侧载模型文件——具体目录结构请参考 App 内的 **模型管理** 页面。

### 1.3 HarmonyOS Demo

环境要求：

* DevEco Studio 5.0 或更新版本（含 Native SDK / NDK）
* HarmonyOS API 12 及以上的真机或模拟器（如 nova 14 活力版 / Mate 60 / Pura 70 等）
* 64 位 ARM 架构（`arm64-v8a`）
* 设备内存：参见[第二部分](#第二部分gguf-模型文件)中按模型给出的内存要求

构建并运行：

1. 在 DevEco Studio 中打开 `MiniCPM-V-demo-HarmonyOS/` 目录
2. `File` → `Project Structure` → `Signing Configs` 勾选 **Automatically generate signature**
3. 真机连接后开启开发者模式与 USB 调试，点击 Run（绿色三角）

首次启动后，进入应用内的 **模型管理** 页面点击 **下载模型**。也可以使用 `hdc file send` 旁路侧载模型文件，详见 `MiniCPM-V-demo-HarmonyOS/README_zh.md`。

> 鸿蒙端 C++ 推理层与 Android、iOS 共用仓库根目录的 `llama.cpp` 子模块，模型清单 / OBS 直链 / MD5 哈希严格同源。

---

## 第二部分　GGUF 模型文件

### 硬件要求

端侧推理所需的内存大约等于 *模型权重大小 + KV cache + 视觉编码器与 llama.cpp 的若干百兆运行时开销*。下表给出的"推荐设备内存"已经为操作系统和 demo 应用本身预留了余量。

| 模型 | LLM 参数量 | 推荐量化 | LLM 文件（Q4） | mmproj（f16） | 总下载量 | 推荐设备内存 |
| --- | --- | --- | --- | --- | --- | --- |
| MiniCPM-V 2.6 | 8B | Q4_K_M | ~4.4 GB | ~1.0 GB | ~5.4 GB | **≥ 8 GB** |
| MiniCPM-V 4.0 | 4.1B | Q4_K_M | ~2.0 GB | ~0.9 GB | ~2.9 GB | **≥ 6 GB** |
| MiniCPM-V 4.6 | 1.3B | Q4_K_M | ~0.5 GB | ~1.1 GB | ~1.6 GB | **≥ 6 GB** |

补充说明：

* `mmproj` 是视觉投影器 + ViT 权重，统一保留 **f16** 精度——视觉塔做低比特量化对感知质量的伤害比 LLM 更明显。
* 三端 demo 默认上下文长度为 4K token。上下文越长，KV cache 占用近似线性增长，临界设备上可能需要相应调小。
* Android / HarmonyOS 上跑 V 2.6 强烈建议 8 GB 及以上内存。iOS 上 V 2.6 已在 iPhone 15 Pro / 16 系列以及搭载 M 系列芯片的较新 iPad 上验证；早期 6 GB 内存设备容易出现频繁换页。

### 2.1 MiniCPM-V 2.6 GGUF 模型文件

#### 下载官方 GGUF 文件

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf](https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-2_6-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-2_6-gguf)

请从仓库下载语言模型文件（例如 `ggml-model-Q4_0.gguf`）以及视觉模型文件（`mmproj-model-f16.gguf`）。

### 2.2 MiniCPM-V 4.0 GGUF 模型文件

#### 下载官方 GGUF 文件

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-4-gguf](https://huggingface.co/openbmb/MiniCPM-V-4-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-4-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-4-gguf)

请从仓库下载语言模型文件（例如 `ggml-model-Q4_K_M.gguf`）以及视觉模型文件（`mmproj-model-f16.gguf`）。

### 2.3 MiniCPM-V 4.6 GGUF 模型文件

#### 下载官方 GGUF 文件

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf](https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf)

请从仓库下载语言模型文件（例如 `MiniCPM-V-4_6-Q4_K_M.gguf`）以及视觉模型文件（`mmproj-model-f16.gguf`）。
