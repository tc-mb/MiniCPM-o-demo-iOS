# MiniCPM-V Demo — iOS, Android & HarmonyOS

**English** | [中文](README_zh.md)

This demo runs the MiniCPM-V family of multimodal models fully on-device on iOS, Android, and HarmonyOS NEXT. Three model versions are currently supported:

* **MiniCPM-V 2.6**
* **MiniCPM-V 4.0**
* **MiniCPM-V 4.6**

This repository contains three on-device demos for MiniCPM-V (multimodal LLM) running fully locally via `llama.cpp`:

* `MiniCPM-V-demo/` — iOS demo (Xcode project)
* `MiniCPM-V-demo-Android/` — Android demo (Gradle / Kotlin)
* `MiniCPM-V-demo-HarmonyOS/` — HarmonyOS NEXT demo (DevEco Studio / ArkTS)

All three demos share the same `llama.cpp` submodule (branch `MiniCPM-V`) at the repo root.

> **NOTE**: This project bundles `llama.cpp` as a git submodule. The upstream fork `tc-mb/llama.cpp` carries a dozen unrelated branches and a full clone weighs ~350 MB, so `shallow = true` is set in `.gitmodules` by default. The recommended **shallow + single-branch** clone is:
>
> ```bash
> # one-shot (parent repo + submodules, all shallow)
> git clone --recurse-submodules --shallow-submodules \
>     https://github.com/OpenBMB/MiniCPM-V-Apps.git
> cd MiniCPM-V-Apps
> ```
>
> Or, if you've already cloned the parent repo and want to init the submodule afterwards:
>
> ```bash
> git submodule update --init --recursive --depth 1 --single-branch
> ```
>
> This only pulls a single commit of the `MiniCPM-V` branch (~tens of MB) instead of the full llama.cpp fork history. Developers who need to push to `tc-mb/llama.cpp:MiniCPM-V` can run `git fetch --unshallow` inside the submodule to lift the shallow restriction.

The README is organised in two parts:

* **Part 1 — Platform setup**: how to build and run the demo on iOS, Android and HarmonyOS.
* **Part 2 — GGUF model files**: where to get the model weights for each MiniCPM-V version, and the minimum on-device hardware needed to run them.

---

> **Just want to install the app?** Pre-built TestFlight (iOS) / APK (Android) / HAP (HarmonyOS) packages and step-by-step install instructions are in **[DOWNLOAD.md](DOWNLOAD.md)**. The rest of this README is only needed if you want to build from source.

---

## Part 1. Platform Setup

### 1.1 iOS Demo

**NOTE: To deploy and test the app on an iOS device, you may need an Apple Developer account.**

Install Xcode:

* Download Xcode from the App Store
* Install the Command Line Tools:

  ```bash
  xcode-select --install
  ```
* Agree to the software license agreement:

  ```bash
  sudo xcodebuild -license
  ```

Open `MiniCPM-V-demo/MiniCPM-V-demo.xcodeproj` with Xcode. It may take a moment for Xcode to automatically download the required dependencies.

In Xcode, select the target device at the top of the window, then click the "Run" (triangle) button to launch the demo.

**REQUIRED on first build:** The prebuilt `llama.xcframework` binary (~189 MB) is no longer tracked in this repo. Build it once from the `llama.cpp` submodule and copy it back to `MiniCPM-V-demo/thirdparty/` using the steps below.

#### Building the llama.xcframework

Build directly inside the submodule (no extra clone needed):

```bash
cd llama.cpp
./build-xcframework.sh
cp -r ./build-apple/llama.xcframework ../MiniCPM-V-demo/thirdparty
```

### 1.2 Android Demo

Requirements:

* Android Studio (Giraffe or newer)
* Android SDK + NDK (the project pins NDK `28.2.13676358` and CMake `3.22.1`)
* A physical device with a 64-bit ARM SoC (`arm64-v8a`)
* Device RAM: see the per-model requirements in [Part 2](#part-2-gguf-model-files)

Build & run:

```bash
cd MiniCPM-V-demo-Android
./gradlew assembleDebug
```

Or open `MiniCPM-V-demo-Android/` directly in Android Studio and click Run.

The first launch will download the GGUF model files into the app's external storage. You can also sideload model files manually via `adb push` — see in-app **Model Manager** for the expected directory layout.

### 1.3 HarmonyOS Demo

Requirements:

* DevEco Studio 5.0 or newer (with the HarmonyOS Native SDK / NDK)
* A real device or emulator running HarmonyOS API 12+ (e.g. nova 14 vitality / Mate 60 / Pura 70)
* 64-bit ARM architecture (`arm64-v8a`)
* Device RAM: see the per-model requirements in [Part 2](#part-2-gguf-model-files)

Build & run:

1. Open `MiniCPM-V-demo-HarmonyOS/` in DevEco Studio.
2. `File` → `Project Structure` → `Signing Configs` and tick **Automatically generate signature** (requires a Huawei developer account; this only needs to be done once).
3. Connect the device with USB debugging enabled, then click Run (the green triangle).

After the first launch, open the in-app **Model Manager** and tap **Download**. You can also sideload model files via `hdc file send`; see `MiniCPM-V-demo-HarmonyOS/README_zh.md` for the expected directory layout.

> The HarmonyOS port shares the exact same `llama.cpp` submodule, model catalogue, OBS direct-link URLs and MD5 hashes with the iOS / Android demos.

---

## Part 2. GGUF Model Files

### Hardware requirements

The on-device memory needed to run a model is roughly *(model file size) + KV cache + a few hundred MB of working memory for the vision encoder and llama.cpp internals*. The recommended values below leave enough headroom for the OS and the demo app itself.

| Model | LLM params | Recommended quant | LLM file (Q4) | mmproj (f16) | Total download | Recommended device RAM |
| --- | --- | --- | --- | --- | --- | --- |
| MiniCPM-V 2.6 | 8B | Q4_K_M | ~4.4 GB | ~1.0 GB | ~5.4 GB | **≥ 8 GB** |
| MiniCPM-V 4.0 | 4.1B | Q4_K_M | ~2.0 GB | ~0.9 GB | ~2.9 GB | **≥ 6 GB** |
| MiniCPM-V 4.6 | 1.3B | Q4_K_M | ~0.5 GB | ~1.1 GB | ~1.6 GB | **≥ 6 GB** |

Notes:

* `mmproj` is the vision projector + ViT weights; it is shipped in **f16** because quantising the visual tower hurts perception quality noticeably more than quantising the LLM.
* All three demos default to a context window of 4K tokens. Larger contexts will increase the KV-cache footprint roughly linearly, so on a borderline device you may need to lower it.
* On Android / HarmonyOS, devices with 8 GB+ RAM are strongly recommended for V 2.6. On iOS, V 2.6 has been validated on iPhone 15 Pro / 16 series and recent iPads with M-series chips; older 6 GB devices may swap heavily.

### 2.1 MiniCPM-V 2.6 GGUF Files

#### Download Official GGUF Files

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf](https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-2_6-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-2_6-gguf)

Download the language model file (e.g., `ggml-model-Q4_0.gguf`) and the vision model file (`mmproj-model-f16.gguf`) from the repository.

### 2.2 MiniCPM-V 4.0 GGUF Files

#### Download Official GGUF Files

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-4-gguf](https://huggingface.co/openbmb/MiniCPM-V-4-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-4-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-4-gguf)

Download the language model file (e.g., `ggml-model-Q4_K_M.gguf`) and the vision model file (`mmproj-model-f16.gguf`) from the repository.

### 2.3 MiniCPM-V 4.6 GGUF Files

#### Download Official GGUF Files

* HuggingFace: [https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf](https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf)
* ModelScope: [https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf](https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf)

Download the language model file (e.g., `MiniCPM-V-4_6-Q4_K_M.gguf`) and the vision model file (`mmproj-model-f16.gguf`) from the repository.
