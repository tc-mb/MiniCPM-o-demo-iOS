# Local Streaming Guard Reply Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace blocked-input Toasts with assistant-style local streaming messages that are visible in chat but never enter the MiniCPM model context.

**Architecture:** A pure dispatch policy maps visual prompt decisions either to real model inference or to a local-only guard reply. A Unicode-safe frame generator produces cumulative text frames for the existing AI message renderer. `MainActivity` handles local replies in a dedicated coroutine and never calls `LlamaEngine.sendUserPrompt` on that path.

**Tech Stack:** Kotlin, Android lifecycle coroutines, RecyclerView chat messages, JUnit 4, Gradle.

---

### Task 1: Specify local-only dispatch and streaming frames

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/LocalGuardReplyPolicyTest.kt`
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LocalGuardReplyPolicy.kt`

- [ ] **Step 1: Write a failing dispatch test**

Assert that `ALLOW` selects `MODEL`, while both blocked decisions select `LOCAL_ONLY` with distinct reply kinds and `includeInModelContext=false`.

- [ ] **Step 2: Write a failing Unicode frame test**

Assert that `LocalResponseStreamer.frames("好🙂")` yields `"好"` and `"好🙂"` without exposing half of a surrogate pair.

- [ ] **Step 3: Run the focused tests and verify RED**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.minicpm_v_demo.LocalGuardReplyPolicyTest`

Expected: compilation fails because the new policy and streamer do not exist.

- [ ] **Step 4: Implement the minimal pure Kotlin policy**

Define `PromptDestination`, `LocalGuardReplyKind`, `PromptDispatchPlan`, `LocalGuardReplyPolicy`, and a code-point-safe `LocalResponseStreamer`.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the focused command again and require all new tests to pass.

### Task 2: Replace blocked-input Toasts with local chat messages

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Route the visual prompt decision**

Use `LocalGuardReplyPolicy.plan` in `handleUserInput`; only the `MODEL` destination continues to attachment consumption and `sendUserPrompt`.

- [ ] **Step 2: Add the local streaming chat path**

Append the user message and an AI generating cell, emit cumulative local frames with a short delay, and finish through the same adapter state transitions as real generation.

- [ ] **Step 3: Keep model context isolated**

The local path must return before attachment consumption and before every call to `sendUserPrompt`; cancellation must affect only the local coroutine.

- [ ] **Step 4: Localize reply text**

Add separate assistant messages for missing visual context and uncertain visual intent; remove the no-longer-used blocked-input Toast resources.

### Task 3: Verify and document

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Document UI-only context isolation**

Explain that blocked user messages and simulated assistant replies remain in the RecyclerView transcript only and are never written into native model context.

- [ ] **Step 2: Run all checks**

Run: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Expected: all unit tests pass, lint has zero errors, and the debug APK builds.

- [ ] **Step 3: Install and launch on the connected phone**

Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`, start `MainActivity`, and confirm the installed process is alive.
