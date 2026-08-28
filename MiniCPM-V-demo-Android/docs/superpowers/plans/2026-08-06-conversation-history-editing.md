# Conversation History Editing Implementation Plan

> **Archived 2026-08-18:** 本计划已完成，统一状态见 [MiniCPM Android 统一进度与后续实施计划](2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史实现细节。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add multiple in-app conversations, safe message edit/delete rollback, conversation management under Settings, and removal of an image while it is being prepared.

**Architecture:** Keep conversation timelines in a lifecycle-aware store and explicitly distinguish model-context turns from local safety UI turns. Any switch, edit, delete, or pending-image removal runs through one serialized context rebuild: cancel active work, reset the native context, replay retained image/user/assistant turns, then re-enable input. Image source tokens remain app-private opaque cache identifiers and are deleted only when no conversation references them.

**Tech Stack:** Kotlin, AndroidX Lifecycle/RecyclerView, Material dialogs, coroutines, JNI/C++ llama.cpp bridge, JUnit4 and Android instrumentation tests.

---

### Task 1: Define and test conversation timeline semantics

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/ConversationStore.kt`
- Create: `app/src/test/java/com/example/minicpm_v_demo/ConversationStoreTest.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatMessage.kt`

1. Write failing tests for creating/switching/deleting conversations, editing a user turn with tail truncation, editing an assistant turn with tail truncation, deleting one message without regeneration, and excluding local-only messages from replay.
2. Add explicit model-context metadata to chat messages.
3. Implement deterministic IDs, titles, active-session selection, edit/truncate, single-message delete, and referenced image-token queries.
4. Run the focused unit tests.

### Task 2: Add native history replay primitives

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `app/src/main/cpp/llama_jni.cpp`

1. Add serialized engine APIs for replaying retained user and assistant turns without generation.
2. Preserve MiniCPM-V ChatML boundaries and visual-context state during replay.
3. Return failures to Kotlin instead of silently leaving a partially rebuilt context.

### Task 3: Reuse secure cached images during rebuild

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/PendingImageViewModel.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/ImageSourceCacheTest.kt`

1. Add a replay method that resolves only opaque app-generated cache tokens, decodes within existing size limits, and prefills the engine.
2. Keep cancellation joined before engine reset and do not expose filesystem paths.
3. Verify invalid/traversal-like tokens remain rejected.

### Task 4: Add message actions and conversation management UI

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/res/layout/dialog_chat_settings.xml`
- Create: `app/src/main/res/layout/dialog_edit_message.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

1. Long-press user or assistant messages to show Edit/Delete actions.
2. Editing either role replaces that turn and truncates later turns; editing a user turn submits it again, while editing an assistant turn only rebuilds through the edited answer.
3. Deleting removes only the selected bubble, performs no automatic generation, and rebuilds the remaining visible model-context turns.
4. Add Settings > Conversation management with new, switch, rename-by-first-prompt title, and delete controls.
5. Disable all destructive/timeline actions while generation, video processing, image preprocessing, or another rebuild is active.

### Task 5: Add pending-image removal and context recovery

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/drawable/ic_close.xml`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

1. Add an accessible remove button to the pending image panel.
2. On removal, cancel and join preprocessing, reset the engine, replay the current conversation, delete the no-longer-referenced source, and restore controls.
3. Keep open-original behavior on the thumbnail.

### Task 6: Regression verification and device install

**Files:**
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/MainActivityUiTest.kt`
- Modify: `README_MODIFIED.md` if present

1. Run focused unit tests, then the full unit-test suite.
2. Build the debug APK and run available instrumentation checks.
3. Inspect git diff for cache ownership, local-only context exclusion, and cancellation races.
4. Install the debug APK over the connected device and report the exact tested behavior and any device-only checks left to the user.
