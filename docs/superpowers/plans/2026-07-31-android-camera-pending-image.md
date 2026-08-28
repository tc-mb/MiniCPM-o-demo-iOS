# Android Immersive Camera and Pending Image Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide the Android status bar, add full-resolution camera capture beside Send, and preprocess a selected image in a darkened input-area preview before it can be sent to chat.

**Architecture:** Keep native image prefill exactly once per image, but move it from the chat list into a pending-input state. A pure Kotlin state machine guards stale callbacks and button availability; `MainActivity` owns the pending bitmap and renders an indeterminate circular indicator until native prefill returns, then displays a real 100% state. Full-resolution camera capture uses a narrowly scoped cache `FileProvider`.

**Tech Stack:** Kotlin, Android Views/XML, Activity Result APIs, AndroidX `FileProvider`, Kotlin coroutines, Material circular progress indicator, JUnit 4, Android instrumentation tests.

---

### Task 1: Establish the pending-image state contract

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/PendingImageStateMachineTest.kt`
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/PendingImageStateMachine.kt`

- [ ] **Step 1: Write failing state-machine tests**

Cover these behaviors with real state transitions:

```kotlin
@Test fun completion_is_the_only_transition_that_exposes_100_percent()
@Test fun stale_callbacks_cannot_replace_the_current_request()
@Test fun preprocessing_blocks_send_and_media_selection()
@Test fun ready_image_allows_text_send_but_not_replacement()
@Test fun consuming_or_failing_a_request_returns_to_empty()
```

The state API must expose `Empty`, `Preprocessing(requestId)`, and
`Ready(requestId, progressPercent = 100)`. Preprocessing has no numeric percent because
the native JNI call exposes no intermediate progress.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
$env:ANDROID_HOME='D:\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests '*PendingImageStateMachineTest'
```

Expected: compilation failure because `PendingImageStateMachine` does not exist.

- [ ] **Step 3: Implement the minimal state machine**

Implement request-id validation, `start`, `complete`, `fail`, `consumeReady`, and pure
control predicates. Never allow a preprocessing state to report 100%.

- [ ] **Step 4: Run the focused and complete unit suites**

Expected: the focused tests and existing unit suite pass.

### Task 2: Bound camera/gallery image decoding

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/ImageDecodePolicyTest.kt`
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ImageDecodePolicy.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Write failing decode-policy tests**

Test that valid images remain at sample size 1, very large images choose a power-of-two
sample, invalid dimensions are rejected, and the decoded maximum dimension is bounded.

- [ ] **Step 2: Verify RED**

Run the focused unit test and confirm the missing policy is the failure reason.

- [ ] **Step 3: Implement and integrate bounded decoding**

Read URI metadata without constructing a filesystem path from provider-controlled names.
Open the `content://` URI through `ContentResolver`, decode bounds, reopen the stream, and
decode with the policy’s sample size. Encode opaque images as high-quality JPEG and images
with alpha as PNG. All decoding and encoding remains off the main thread.

- [ ] **Step 4: Verify GREEN**

Run both focused policy tests and the full unit suite.

### Task 3: Add secure full-resolution camera capture

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/AndroidManifest.xml`
- Create: `MiniCPM-V-demo-Android/app/src/main/res/xml/camera_file_paths.xml`
- Create: `MiniCPM-V-demo-Android/app/src/main/res/drawable/ic_camera.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/layout/activity_main.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Create: `MiniCPM-V-demo-Android/app/src/androidTest/java/com/example/minicpm_v_demo/CameraFileProviderTest.kt`

- [ ] **Step 1: Write an instrumentation test for provider scope**

The test creates a file under `cacheDir/camera`, obtains a `content://` URI from
`<package>.fileprovider`, opens it through `ContentResolver`, and asserts the provider is
not exported and grants URI permissions.

- [ ] **Step 2: Add the provider and UI resources**

The provider XML exposes only `cacheDir/camera/`; it does not expose all cache, files, or
external storage. Add the camera button directly before Send. Do not declare
`android.permission.CAMERA`, because capture is delegated to the system camera app.

- [ ] **Step 3: Register `ActivityResultContracts.TakePicture`**

Create the output with `File.createTempFile` inside the private camera cache, use
`FileProvider.getUriForFile`, preserve the URI/file name in instance state, handle
cancel/failure, and delete the temporary camera file after decoding.

- [ ] **Step 4: Build and run provider instrumentation**

Expected: provider test passes on the connected Android device.

### Task 4: Render and enforce pending-image preprocessing

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/layout/activity_main.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Add the pending preview above the text row**

Add a hidden panel containing the image thumbnail, a semi-transparent scrim, a Material
`CircularProgressIndicator`, an in-ring percentage label, and image metadata. The preview
is dark only while `Preprocessing`.

- [ ] **Step 2: Move image prefill out of the chat list**

When an image is chosen, transition to `Preprocessing`, decode it, show the dark preview,
and invoke `engine.prefillImage(imageBytes)` once. Do not add a `ChatMessage` at this time.
While JNI is running, use an indeterminate circle and hide the percentage label.

- [ ] **Step 3: Complete only on native success**

After `prefillImage` returns successfully, transition to `Ready`, restore image brightness,
switch the circle to determinate 100, show `100%`, and enable Send. On failure, remove the
pending preview, restore controls, clean camera cache, and show a localized error.

- [ ] **Step 4: Centralize button-state calculation**

`refreshInputControls()` must consider engine state, video processing, submission, and the
pending-image state. Preprocessing disables Send/gallery/camera. Ready permits Send but
continues to disable replacement so an already-prefilled image cannot be orphaned in the
native KV context.

- [ ] **Step 5: Send the cached image exactly once**

On Send, require nonblank text and a Ready state when a pending image exists. Consume the
pending state, add one `UserMessage` containing both text and bitmap, clear the input
preview, and call only `engine.sendUserPrompt(text)`. Never call `prefillImage` from Send.

- [ ] **Step 6: Handle lifecycle and reset paths**

Clear pending UI after full `engine.clearContext`, model reload, or error. Preserve camera
capture URI across activity recreation. Prevent orientation recreation during a running
prefill by handling `orientation|screenSize` configuration changes in `MainActivity`.

- [ ] **Step 7: Run unit tests**

Expected: all state-machine and decode-policy tests pass.

### Task 5: Hide the status bar without hiding navigation or IME

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/StatusBarHidingActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ModelManagerActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/TtsActivity.kt`
- Create: `MiniCPM-V-demo-Android/app/src/androidTest/java/com/example/minicpm_v_demo/StatusBarVisibilityTest.kt`

- [ ] **Step 1: Add a failing device assertion**

Launch each activity and assert the root insets report `statusBars()` as hidden. Do not
assert navigation bars are hidden.

- [ ] **Step 2: Implement the shared base activity**

Use `WindowInsetsControllerCompat.hide(WindowInsetsCompat.Type.statusBars())` with
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, applying it on resume and when window focus
returns. Preserve each activity’s existing `decorFitsSystemWindows` and IME handling.

- [ ] **Step 3: Run device assertions**

Expected: the top status bar is hidden in chat, model management, and TTS screens; keyboard
and bottom navigation insets remain usable.

### Task 6: Package, install, and perform true-device regression

**Files:**
- Build output only (ignored): `MiniCPM-V-demo-Android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run clean verification**

```powershell
$env:ANDROID_HOME='D:\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected: all tasks succeed without new warnings attributable to this feature.

- [ ] **Step 2: Verify APK metadata and signature**

Use `aapt dump badging` to confirm package/version/arm64 metadata and `apksigner verify`
to confirm the debug APK is signed and installable.

- [ ] **Step 3: Resolve the official/debug signature mismatch**

Confirm the exact installed package and data size. Because the official v2.3 certificate
differs from the local debug certificate, uninstall only
`com.example.minicpm_v_demo`, then install the newly built APK. This deletes the official
package’s app data; no other package or storage path is touched.

- [ ] **Step 4: Run instrumentation and manual ADB/UI checks**

Verify cold launch, hidden status bar, gallery selection, dark pending preview, disabled
Send during prefill, 100% only after completion, one combined image/text message on Send,
camera launch and return, no duplicate prefill, and no `AndroidRuntime` fatal exception.

### Task 7: Write the modified-build README

**Files:**
- Create: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Document user-visible behavior**

Describe status-bar auto-hide, camera capture, pending image preprocessing, exact 100%
semantics, send gating, and the one-image-at-a-time limitation imposed by native KV state.

- [ ] **Step 2: Document build and installation**

List Java/SDK/NDK/CMake requirements using the actual CMake `4.1.2`, exact Gradle commands,
APK location, signature mismatch/uninstall note, and ADB installation commands.

- [ ] **Step 3: Document verification evidence**

Record unit/instrumentation/build results, tested phone/Android/ABI, APK SHA-256 and signing
certificate digest, package/version, and known limitations.

- [ ] **Step 4: Review repository cleanliness**

Confirm generated APK/native/Gradle outputs remain ignored and only source, tests, the plan,
and modified README appear in `git status`.
