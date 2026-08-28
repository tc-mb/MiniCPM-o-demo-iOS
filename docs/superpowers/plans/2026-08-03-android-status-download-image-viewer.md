# Android Status Bar, Download Resume, and Original Image Viewer Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the system status bar visible, suppress missing-model dialogs while a model download is active, and retain/view the original selected image from both the pending input and sent chat message.

**Architecture:** The activities will share a visible-status-bar base while `MainActivity` continues applying explicit system/IME insets. A pure prompt policy will decide whether missing files warrant a dialog. Selected images will remain in a canonical app-private cache under an opaque basename token; the pending attachment transfers token ownership to the chat message, and a dedicated viewer validates that token before decoding the cached original safely.

**Tech Stack:** Kotlin, AndroidX Activity/Lifecycle/RecyclerView, Material Components, JUnit 4, Android instrumentation tests.

---

### Task 1: Visible status bar

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/StatusBarHidingActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/androidTest/java/com/example/minicpm_v_demo/MainActivityUiTest.kt`

- [ ] **Step 1: Change the instrumentation assertion first**

```kotlin
assertTrue(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
```

- [ ] **Step 2: Run the test compilation/test and confirm the old hidden-bar behavior fails on device**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

- [ ] **Step 3: Replace hiding with an explicit show operation**

```kotlin
WindowCompat.getInsetsController(window, window.decorView)
    .show(WindowInsetsCompat.Type.statusBars())
```

- [ ] **Step 4: Verify content top padding equals the visible status-bar inset on device**

### Task 2: Download-aware prompt policy

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ModelDownloadPromptPolicy.kt`
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/ModelDownloadPromptPolicyTest.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Write failing policy tests**

```kotlin
assertFalse(ModelDownloadPromptPolicy.shouldPrompt(true, true, downloadRunning = true))
assertTrue(ModelDownloadPromptPolicy.shouldPrompt(true, false, downloadRunning = false))
```

- [ ] **Step 2: Run the targeted tests and confirm the missing policy fails to compile**

- [ ] **Step 3: Implement the minimal policy**

```kotlin
fun shouldPrompt(ggufMissing: Boolean, mmprojMissing: Boolean, downloadRunning: Boolean) =
    (ggufMissing || mmprojMissing) && !downloadRunning
```

- [ ] **Step 4: Gate `promptDownloadModels` with `ModelDownloadController.isRunning`**

- [ ] **Step 5: Verify background/foreground during an active download produces no dialog**

### Task 3: Retained original-image cache and secure token resolution

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ImageSourceCache.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/ImageSourceCacheTest.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/PendingImageViewModel.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatMessage.kt`

- [ ] **Step 1: Write failing tests for opaque token resolution and traversal rejection**

```kotlin
assertEquals(cached.file, cache.resolve(cached.token))
assertNull(cache.resolve("../outside.img"))
```

- [ ] **Step 2: Run tests and confirm token APIs are missing**

- [ ] **Step 3: Implement canonical-parent validation**

```kotlin
if (token != File(token).name) return null
return File(directory, token).canonicalFile.takeIf { it.parentFile == directory && it.isFile }
```

- [ ] **Step 4: Include `originalImageToken` in pending and chat attachments**

- [ ] **Step 5: Retain the cached source on successful prefill; delete it on failure, cancellation, clear-chat, or final Activity finish**

### Task 4: Original image viewer and click paths

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/OriginalImageViewerActivity.kt`
- Create: `MiniCPM-V-demo-Android/app/src/main/res/layout/activity_original_image_viewer.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/AndroidManifest.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Write an instrumentation assertion that the viewer rejects a traversal token**

- [ ] **Step 2: Register an unexported viewer Activity**

```xml
<activity android:name=".OriginalImageViewerActivity" android:exported="false" />
```

- [ ] **Step 3: Decode the validated private source with bounded sampling and `fitCenter`**

- [ ] **Step 4: Add pending-image and chat-image callbacks that pass only the opaque token**

- [ ] **Step 5: Verify both click locations open the same original image**

### Task 5: Pending-image presentation

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/layout/activity_main.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Update the UI instrumentation contract to require a processing status label**

- [ ] **Step 2: Replace the centered percentage text with a two-line status/info block beside the thumbnail**

```xml
<TextView android:id="@+id/tv_pending_image_status"
    android:text="@string/image_preprocessing_wait" />
```

- [ ] **Step 3: During preprocessing show the dark scrim, indeterminate circle, and “图像预处理中，请耐心等待”**

- [ ] **Step 4: At Ready hide the scrim and circle; never render `100%`**

- [ ] **Step 5: Capture processing and ready screenshots and review spacing, contrast, and touch target**

### Task 6: Full verification and documentation

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Run unit tests, Android test compilation, Lint, and APK assembly**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
```

- [ ] **Step 2: Cover-install on the connected vivo device without deleting model data**

- [ ] **Step 3: Verify visible status bar, background download behavior, pending click, sent-message click, preprocessing copy, and completion state**

- [ ] **Step 4: Update README with behavior, cache lifetime, safety boundary, and actual validation results**
