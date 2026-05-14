<div align="center">

# 下载安装 MiniCPM-V Apps

<strong><a href="./DOWNLOAD.md">English</a> |
中文</strong>

</div>

我们在 **iOS**、**安卓**、**鸿蒙** 三大平台均提供了预编译的应用安装包。所有应用均通过 `llama.cpp` 在**端侧**本地运行 MiniCPM-V 多模态模型 —— 无需远程服务器，数据不会离开你的手机。

> 如果希望从源码自行编译，请参考仓库首页 [README](./README.md)。


## 总览

| 平台 | 最新版本 | 安装包 | 大小 | 系统要求 | 推荐内存 |
| --- | --- | --- | --- | --- | --- |
| 🍎 **iOS / iPadOS** | [TestFlight（滚动更新）](https://testflight.apple.com/join/yNKyFZwW) | — | — | iOS / iPadOS 16+ | ≥ 6 GB |
| 🤖 **安卓** | [v2.0](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/android-v2.0) | APK | ~17 MB | 安卓 8.0 (API 26)+ | ≥ 6 GB |
| 📱 **鸿蒙** | [v2.0](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/harmonyos-v2.0) | HAP | ~39 MB | 鸿蒙 API 12+ | ≥ 6 GB |

所有安装包均面向 **64 位 ARM** 架构（`arm64-v8a`）。


## 🍎 iOS — TestFlight

**TestFlight 公开链接：** [https://testflight.apple.com/join/yNKyFZwW](https://testflight.apple.com/join/yNKyFZwW)

> **注意：** iOS 发版会比安卓 / 鸿蒙稍慢。每个 iOS 版本都需先通过 Apple TestFlight 的审核才能交付给用户，因此 iOS 上的新功能和缺陷修复通常会**比安卓 APK 和鸿蒙 HAP 版本晚数天到一两周**。如需立即体验最新修复，请使用本页面的安卓或鸿蒙安装包。

### 系统要求

- 运行 **iOS / iPadOS 16 及以上**的 iPhone 或 iPad
- 推荐内存 **≥ 6 GB**（如 iPhone 15 Pro，或搭载 M 系列芯片的 iPad），以获得流畅的端侧推理体验
- 在 App Store 安装好 [TestFlight](https://apps.apple.com/app/testflight/id899247664) 应用

### 安装步骤

1. 在 iPhone 或 iPad 的 App Store 安装 **TestFlight**。
2. 在同一台设备上打开上方公开链接，依次点击 **Accept** → **Install**。
3. 在主屏幕启动 **MiniCPM-V Demo**，按应用内提示下载模型文件。

> 每个 TestFlight 版本最长有效期为 90 天，新版本发布时 TestFlight 会自动通知你。


## 🤖 安卓 — APK

**最新版本：** [**v2.0**](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/android-v2.0) &nbsp;|&nbsp; [下载 APK](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/download/android-v2.0/MiniCPM-V-demo-Android-v2.0.apk) &nbsp;|&nbsp; [全部安卓版本](https://github.com/OpenBMB/MiniCPM-V-Apps/releases?q=tag%3Aandroid)

| 文件 | 大小 | MD5 |
| --- | --- | --- |
| `MiniCPM-V-demo-Android-v2.0.apk` | ~17 MB | `43576791bfe8c24ed6909f33d8e94b63` |

### 系统要求

- 采用 **64 位 ARM SoC**（`arm64-v8a`）的安卓设备
- **安卓 8.0 (API 26) 及以上**
- 推荐内存 **≥ 6 GB**

### 安装步骤

1. 将上方最新版 `.apk` 文件下载到手机。
2. 如出现安全提示，请在 **设置 → 应用 → 安装未知应用** 中允许该来源安装。
3. 打开已安装的 **MiniCPM-V Demo** 应用，在内置的 **模型管理** 中点击 **下载** 获取 GGUF 模型文件。


## 📱 鸿蒙 — HAP

**最新版本：** [**v2.0**](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/harmonyos-v2.0) &nbsp;|&nbsp; [下载 HAP](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/download/harmonyos-v2.0/MiniCPM-V-demo-HarmonyOS-v2.0.hap) &nbsp;|&nbsp; [全部鸿蒙版本](https://github.com/OpenBMB/MiniCPM-V-Apps/releases?q=tag%3Aharmonyos)

| 文件 | 大小 | MD5 |
| --- | --- | --- |
| `MiniCPM-V-demo-HarmonyOS-v2.0.hap` | ~39 MB | `9aff126a47b651bce1d33890a289f319` |

### 系统要求

- 运行 **鸿蒙 API 12 及以上**的设备（如 nova 14 Vitality、Mate 60、Pura 70）
- **64 位 ARM**（`arm64-v8a`）
- 推荐内存 **≥ 6 GB**

### 安装步骤

1. 在设备上开启 **开发者模式** 和 **USB 调试**。
2. 在已安装 `hdc` 的电脑上连接设备，运行：

   ```bash
   hdc install MiniCPM-V-demo-HarmonyOS-v2.0.hap
   ```

3. 打开已安装的应用，在内置的 **模型管理** 中点击 **下载** 获取模型文件。

> **注意：** 我们正在准备将应用上架到华为应用市场，目前请先使用上方的 HAP 安装包。


## 备注

- 应用首次启动时会下载 GGUF 模型文件（数 GB），建议在 Wi-Fi 网络下完成首次下载。
- 这些 Demo 仅用于**研究 / 预览**用途，并非经过完整优化的成品应用。
- 发现问题或有改进建议？欢迎提交 [issue](https://github.com/OpenBMB/MiniCPM-V-Apps/issues)，或通过 TestFlight（iOS）反馈。
