# Visual Context Guard Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent MiniCPM-V from inventing image contents when the current conversation has no successfully prefetched image or video.

**Architecture:** `LlamaEngine` owns a conversation-level visual-context state that becomes available only after native image/video prefill succeeds and resets with model/context lifecycle operations. A pure Kotlin policy performs bounded, high-precision visual-request detection, `MainActivity` blocks unsupported requests before inference, and the welcome card changes from visual questions to image/camera acquisition actions until visual context exists. A grounding system prompt provides defense in depth after each model load or context reset.

**Tech Stack:** Kotlin, Android ViewModel/StateFlow, llama.cpp JNI, JUnit 4, Android instrumentation tests.

---

### Task 1: Visual request and context policy

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/VisualContextPolicy.kt`
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/VisualContextPolicyTest.kt`

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test fun blocksExplicitImageQuestionWithoutContext() {
    val policy = VisualContextPolicy()
    assertTrue(policy.shouldBlock("这张图说了什么？"))
}

@Test fun allowsNormalTextQuestionWithoutContext() {
    val policy = VisualContextPolicy()
    assertFalse(policy.shouldBlock("介绍一下图像识别技术"))
}

@Test fun allowsFollowUpAfterSuccessfulVisualPrefill() {
    val policy = VisualContextPolicy()
    policy.markVisualContextAvailable()
    assertFalse(policy.shouldBlock("这张图说了什么？"))
}

@Test fun resetBlocksVisualQuestionAgain() {
    val policy = VisualContextPolicy()
    policy.markVisualContextAvailable()
    policy.reset()
    assertTrue(policy.shouldBlock("Describe this image"))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*VisualContextPolicyTest"`

Expected: compilation fails because `VisualContextPolicy` does not exist.

- [ ] **Step 3: Implement the bounded policy**

```kotlin
class VisualContextPolicy {
    private val _hasVisualContext = MutableStateFlow(false)
    val hasVisualContext: StateFlow<Boolean> = _hasVisualContext.asStateFlow()

    fun markVisualContextAvailable() { _hasVisualContext.value = true }
    fun reset() { _hasVisualContext.value = false }
    fun shouldBlock(message: String): Boolean =
        !_hasVisualContext.value && VisualRequestDetector.requiresVisualContext(message)
}
```

The detector scans at most 4096 characters using fixed substring groups, not user-controlled regular expressions. It matches concrete references such as `这张图`, `图中`, `this image`, and `in the photo`, while allowing general questions such as `什么是图像识别`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*VisualContextPolicyTest"`

Expected: all `VisualContextPolicyTest` cases pass.

### Task 2: Engine lifecycle integration and defense in depth

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp`

- [ ] **Step 1: Add failing lifecycle expectations to the policy test**

Verify that initial/load/reset states are false, successful visual prefill is true, and an engine-level preflight rejects explicit visual requests only while false.

- [ ] **Step 2: Wire the policy into engine boundaries**

Mark context available only after `prefillImage` or all video frames complete. Reset it before/after model load, successful `clearContext`, unload, and `resetToInitialized`. Expose `hasVisualContext` and `shouldBlockVisualRequest` to the activity, and guard `sendUserPrompt` as a second application-layer boundary.

- [ ] **Step 3: Add the static grounding system instruction**

After model load and after `clearContext`, install a bilingual-neutral instruction stating that visual claims must use visual content actually provided in this conversation; if none exists, the assistant must say it cannot inspect an image and request upload/capture. Preserve the user's original message in the visible chat UI.

- [ ] **Step 4: Run unit tests**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: all unit tests pass.

### Task 3: Deterministic UI guard and dynamic welcome actions

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatMessage.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/androidTest/java/com/example/minicpm_v_demo/MainActivityUiTest.kt`

- [ ] **Step 1: Write the failing welcome-action and guard tests**

Assert that a vision welcome card without context exposes gallery and camera actions, a card with context exposes visual prompt actions, and a blocked request does not add user/assistant messages.

- [ ] **Step 2: Verify RED**

Run: `gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: compilation fails because visual-context welcome actions and the no-image message do not exist.

- [ ] **Step 3: Implement UI routing**

Observe engine visual state, refresh the welcome card when it changes, route no-context actions to the existing picker/camera launchers, and check `engine.shouldBlockVisualRequest(userMsg)` before consuming pending input or adding chat messages. Show `当前对话没有图片，请先上传或拍照` on rejection.

- [ ] **Step 4: Verify GREEN**

Run: `gradlew.bat :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`

Expected: tests compile and unit tests pass.

### Task 4: Documentation, build, and device verification

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Document visual-context protection**

Describe deterministic blocking, dynamic gallery/camera actions, conversation-level follow-ups, lifecycle reset rules, and the grounding prompt.

- [ ] **Step 2: Run the complete verification build**

Run: `gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug`

Expected: build succeeds, unit tests have zero failures, and Lint has zero errors.

- [ ] **Step 3: Install and verify on the connected phone**

Install `app/build/outputs/apk/debug/app-debug.apk`, confirm an explicit image question without visual context is rejected without a model turn, confirm gallery/camera actions appear before visual input, then provide an image and confirm a follow-up visual question is accepted. Clear the chat and confirm it is rejected again.
