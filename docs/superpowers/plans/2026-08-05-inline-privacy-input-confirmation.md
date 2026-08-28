# Inline Privacy Input Confirmation Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the model-style privacy input warning with an inline Yes/No choice below the sensitive user message, submitting only on Yes and deleting the message on No, while preserving output review behavior.

**Architecture:** A pending privacy input remains memory-only and is tied to the exact user-message ID. `ChatMessage.UserMessage` exposes a pending-confirmation flag; `ChatAdapter` renders a compact action row and reports the selected message ID. MainActivity validates that ID before either submitting the cached prompt or atomically removing the unsubmitted UI message.

**Tech Stack:** Kotlin, Android RecyclerView/ListAdapter, Material Components, JUnit 4, XML layouts.

---

### Task 1: Confirmation decision policy

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ContentSafetyPolicy.kt`
- Test: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/ContentSafetyPolicyTest.kt`

- [ ] Add a failing test proving that Yes maps to `SUBMIT`, No maps to `DELETE`, and a stale message ID maps to `IGNORE`.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests com.example.minicpm_v_demo.ContentSafetyPolicyTest` and confirm unresolved policy references.
- [ ] Add `PrivacyInputChoiceAction` and `PrivacyInputConfirmationPolicy.resolve(pendingId, selectedId, approved)` with ID equality checked before the choice.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Inline confirmation message UI

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatMessage.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/layout/item_user_message.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`

- [ ] Add `requiresPrivacyConfirmation` to `UserMessage` and include it in DiffUtil content equality.
- [ ] Add an adapter callback carrying message ID and approved/rejected state.
- [ ] Add a right-aligned confirmation panel directly below the user bubble with explanatory text and `删除` / `是，继续发送` buttons; hide it for normal messages.
- [ ] Bind listeners on every bind so recycled rows cannot retain stale actions.

### Task 3: Input-only workflow integration

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] When input classification returns `WARNING`, add only the pending user message and inline choice; do not create an AI message and do not call `sendUserPrompt`.
- [ ] On Yes, validate the message ID, clear the pending flag on the displayed message, and submit the cached original prompt without adding a duplicate bubble.
- [ ] On No, validate the message ID, clear the memory-only cached prompt, remove the pending message from `messages`, and re-enable controls.
- [ ] Keep `RevealResponse` and its existing typed confirmation behavior unchanged.
- [ ] Clear pending input on chat reset/model reset.

### Task 4: Documentation and verification

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] Document the inline input buttons, Yes-only submission, No deletion, and unchanged output flow.
- [ ] Run all unit tests, lint, and assemble Debug APK.
- [ ] Cover-install the APK on the connected vivo device, launch it, and verify the foreground activity.
