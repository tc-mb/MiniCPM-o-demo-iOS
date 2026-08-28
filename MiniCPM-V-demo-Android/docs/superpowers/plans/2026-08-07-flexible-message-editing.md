# Flexible Message Editing Implementation Plan

> **Archived 2026-08-18:** 本计划已完成，统一状态见 [MiniCPM Android 统一进度与后续实施计划](2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史实现细节。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow assistant text-only edits, user-message edit-and-regenerate at any time, and immediate removal of an image while preprocessing is still running.

**Architecture:** Split assistant replacement from user rollback in `ConversationStore`. Let edit actions bypass generation/image busy gates, then serialize each confirmed edit by cancelling active jobs before rebuilding the native history. Add an explicit user-removal cancellation mode that hides the pending image immediately while cleanup safely completes in the background.

**Tech Stack:** Kotlin, Android lifecycle coroutines, JUnit 4, Gradle.

---

### Task 1: Separate assistant and user edit semantics

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ConversationStore.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/ConversationStoreTest.kt`

- [x] Add a failing test proving an assistant edit preserves all later turns.
- [x] Add a failing test proving a user edit still replaces that message and truncates every later turn.
- [x] Implement `editAssistantText` and `editUserAndTruncate` with role validation.
- [x] Run `:app:testDebugUnitTest --tests '*ConversationStoreTest'` and confirm both paths pass.

### Task 2: Make edits safe during active work

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [x] Make edit available while text generation, local streaming, video preprocessing, or pending-image preprocessing is active; keep destructive delete behind the existing idle gate.
- [x] Before applying an edit, cancel and join active jobs so their `finally` blocks cannot overwrite the edited timeline.
- [x] For assistant messages, replace only the selected text, persist it, and rebuild model history without generating.
- [x] For user messages, truncate from the edited turn, rebuild history through the edited turn, and call the existing safety-aware generation path for a new answer.

### Task 3: Remove preprocessing images immediately

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/PendingImageStateMachine.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/PendingImageViewModel.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/PendingImageStateMachineTest.kt`

- [x] Add a failing policy test proving user removal displays `Empty` even while a processing job exists.
- [x] Add an explicit cancellation display mode; normal context resets retain `Clearing`, user removal transitions UI to `Empty` before awaiting the job.
- [x] Keep source-file cleanup and native context reconstruction ordered after cancellation completes.

### Task 4: Verify and package

**Files:**
- Modify: `docs/superpowers/plans/2026-08-07-flexible-message-editing.md`

- [x] Run all JVM unit tests.
- [x] Build `:app:assembleDebug` with the configured Android/native dependency paths.
- [x] Run `git diff --check` and inspect edit/cancellation paths for stale-job races.
