# Unified Chat Settings and No-Image Hallucination Research Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all chat-page settings into one left-aligned settings entry while investigating, but not changing, the model's behavior when an image-dependent question is asked without an image.

**Architecture:** Replace the three toolbar actions with one left settings button. A custom Material dialog exposes model management, image slice count, and the destructive clear-chat action while delegating to the existing action methods. Keep inference code unchanged; diagnose it from the Kotlin/JNI data flow and compare application guards, prompting, decoding, verification, and alignment methods using primary sources.

**Tech Stack:** Android XML, Kotlin, AppCompat/Material dialogs, AndroidX instrumentation tests, Gradle, MiniCPM-V/llama.cpp JNI, primary model documentation and research papers.

---

### Task 1: Specify the toolbar behavior with a failing device test

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/androidTest/java/com/example/minicpm_v_demo/MainActivityUiTest.kt`

- [ ] **Step 1: Add a test for the new entry point**

```kotlin
val settingsButton = activity.findViewById<View>(R.id.btn_settings)
val title = activity.findViewById<View>(R.id.tv_title)
assertNotNull(settingsButton)
val settingsLocation = IntArray(2)
val titleLocation = IntArray(2)
settingsButton.getLocationOnScreen(settingsLocation)
title.getLocationOnScreen(titleLocation)
assertTrue(settingsLocation[0] < titleLocation[0])
```

- [ ] **Step 2: Compile the Android test and verify RED**

Run: `gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation fails because `R.id.btn_settings` does not exist yet.

### Task 2: Build the unified settings dialog

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/main/res/layout/dialog_chat_settings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/layout/activity_main.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Replace toolbar actions with one left settings button**

```xml
<ImageButton
    android:id="@+id/btn_settings"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:layout_gravity="start|center_vertical"
    android:src="@drawable/ic_settings"
    android:contentDescription="@string/chat_settings" />
```

- [ ] **Step 2: Add three focused setting rows**

The dialog contains `row_model_management`, `row_image_slice`, and `row_clear_chat`. The first two use normal surface colors and supporting text; the clear row uses `colorError` and retains the existing confirmation dialog.

- [ ] **Step 3: Route rows to existing behavior**

```kotlin
private fun showChatSettingsDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_chat_settings, null, false)
    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.chat_settings)
        .setView(view)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    view.findViewById<View>(R.id.row_model_management).setOnClickListener {
        dialog.dismiss()
        startActivity(Intent(this, ModelManagerActivity::class.java))
    }
    view.findViewById<View>(R.id.row_image_slice).setOnClickListener {
        dialog.dismiss()
        showImageSliceDialog()
    }
    view.findViewById<View>(R.id.row_clear_chat).setOnClickListener {
        dialog.dismiss()
        showClearChatDialog()
    }
    dialog.show()
}
```

- [ ] **Step 4: Recompile and verify GREEN**

Run: `gradlew :app:compileDebugAndroidTestKotlin`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Verify and document the Android change

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Run all local verification**

Run: `gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug`

Expected: unit tests and compilation pass; lint reports zero errors; debug APK is produced.

- [ ] **Step 2: Install without deleting app data**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: `Success`.

- [ ] **Step 3: Inspect the toolbar and settings dialog on device**

Confirm the settings icon is left of the centered title; the dialog shows all three rows; image slice opens its existing slider; clear chat still asks for confirmation.

### Task 4: Diagnose and compare no-image hallucination mitigations

**Files:**
- Read only: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Read only: `MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp`

- [ ] **Step 1: Trace modality state through the current app**

Record whether an image was prefetched, whether that fact affects prompt construction, whether prior visual embeddings remain in KV cache, and whether a system instruction exists for missing images.

- [ ] **Step 2: Consult primary sources**

Use the official MiniCPM-V model card and original papers for hallucination evaluation/mitigation. Distinguish measures that solve the exact no-image contract violation from measures that reduce hallucinations when a real image exists.

- [ ] **Step 3: Deliver a recommendation without changing inference code**

Compare deterministic application gating, modality-aware prompt metadata, post-generation verification, decoding-time methods, and alignment/fine-tuning by reliability, false-positive risk, latency, memory, and integration cost. Recommend a layered approach suitable for an offline Android app.
