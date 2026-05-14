<div align="center">

# Install MiniCPM-V Apps

<strong>English |
<a href="./DOWNLOAD_zh.md">中文</a></strong>

</div>

Pre-built apps for **iOS**, **Android**, and **HarmonyOS** are listed below. All three apps run the MiniCPM-V multimodal model fully **on-device** via `llama.cpp` — no remote server, no data leaves your phone.

> Looking to build from source instead? See the main [README](./README.md).


## At a Glance

| Platform | Latest | Package | Size | Min OS | Recommended RAM |
| --- | --- | --- | --- | --- | --- |
| 🍎 **iOS / iPadOS** | [TestFlight (rolling)](https://testflight.apple.com/join/yNKyFZwW) | — | — | iOS / iPadOS 16+ | ≥ 6 GB |
| 🤖 **Android** | [v2.0](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/android-v2.0) | APK | ~17 MB | Android 8.0 (API 26)+ | ≥ 6 GB |
| 📱 **HarmonyOS** | [v2.0](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/harmonyos-v2.0) | HAP | ~39 MB | HarmonyOS API 12+ | ≥ 6 GB |

All packages target **64-bit ARM** (`arm64-v8a`).


## 🍎 iOS — TestFlight

**Public TestFlight link:** [https://testflight.apple.com/join/yNKyFZwW](https://testflight.apple.com/join/yNKyFZwW)

> **NOTE:** iOS ships slower than Android / HarmonyOS. Every iOS build must clear Apple's TestFlight review before reaching users, so new features and bug fixes on iOS typically land **several days to a couple of weeks behind** the Android APK and HarmonyOS HAP releases. If you need the latest fix immediately, please use the Android or HarmonyOS package on this page.

### Requirements

- iPhone or iPad running **iOS / iPadOS 16 or later**
- Device with **≥ 6 GB RAM** recommended (e.g. iPhone 15 Pro, or iPad with an M-series chip) for smooth on-device inference
- The [TestFlight](https://apps.apple.com/app/testflight/id899247664) app installed from the App Store

### How to install

1. Install **TestFlight** from the App Store on your iPhone or iPad.
2. Open the public link above on the same device, then tap **Accept** → **Install**.
3. Launch **MiniCPM-V Demo** from the Home Screen and follow the in-app prompts to download the model files.

> Each TestFlight build is valid for up to 90 days. When a new build is published, TestFlight will notify you automatically.


## 🤖 Android — APK

**Latest release:** [**v2.0**](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/android-v2.0) &nbsp;|&nbsp; [Download APK](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/download/android-v2.0/MiniCPM-V-demo-Android-v2.0.apk) &nbsp;|&nbsp; [All Android releases](https://github.com/OpenBMB/MiniCPM-V-Apps/releases?q=tag%3Aandroid)

| File | Size | MD5 |
| --- | --- | --- |
| `MiniCPM-V-demo-Android-v2.0.apk` | ~17 MB | `43576791bfe8c24ed6909f33d8e94b63` |

### Requirements

- Android device with a **64-bit ARM SoC** (`arm64-v8a`)
- **Android 8.0 (API 26) or later**
- **≥ 6 GB RAM** recommended

### How to install

1. Download the latest `.apk` from the link above onto your phone.
2. If you see a security prompt, allow installation from this source in **Settings → Apps → Install unknown apps**.
3. Open the installed **MiniCPM-V Demo** app and tap **Download** in the in-app **Model Manager** to fetch the GGUF model files.


## 📱 HarmonyOS — HAP

**Latest release:** [**v2.0**](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/harmonyos-v2.0) &nbsp;|&nbsp; [Download HAP](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/download/harmonyos-v2.0/MiniCPM-V-demo-HarmonyOS-v2.0.hap) &nbsp;|&nbsp; [All HarmonyOS releases](https://github.com/OpenBMB/MiniCPM-V-Apps/releases?q=tag%3Aharmonyos)

| File | Size | MD5 |
| --- | --- | --- |
| `MiniCPM-V-demo-HarmonyOS-v2.0.hap` | ~39 MB | `9aff126a47b651bce1d33890a289f319` |

### Requirements

- HarmonyOS device on **API 12 or later** (e.g. nova 14 Vitality, Mate 60, Pura 70)
- **64-bit ARM** (`arm64-v8a`)
- **≥ 6 GB RAM** recommended

### How to install

1. Enable **Developer Mode** and **USB debugging** on the device.
2. Connect to a PC with `hdc` installed, then run:

   ```bash
   hdc install MiniCPM-V-demo-HarmonyOS-v2.0.hap
   ```

3. Open the installed app and tap **Download** in the in-app **Model Manager** to fetch the model files.

> **NOTE:** The HarmonyOS distribution channel inside Huawei AppGallery is being prepared; for now please use the HAP package above.


## Notes

- On first launch, each app downloads the GGUF model files (a few GB). Use Wi-Fi for the initial download.
- These demos are intended for **research / preview** only and are not optimized products.
- Found a bug or have a suggestion? Please file an [issue](https://github.com/OpenBMB/MiniCPM-V-Apps/issues), or send feedback through TestFlight (iOS).
