# RAG Lifecycle Pressure Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make native checkpoint ownership observable and prove that successful, cancelled, backgrounded, edited, and conversation-switched RAG turns leave no active checkpoint or stale generating message.

**Architecture:** Extend the existing privacy-preserving native debug snapshot with a read-only `activeCheckpointCount` value derived from the single native checkpoint pointer. Keep `RagTurnTransaction` as the sole checkpoint owner and exercise its idempotent close paths before running real Activity lifecycle scenarios on device. Production behavior changes are allowed only when a failing matrix test proves a gap.

**Tech Stack:** Kotlin, Coroutines, JNI/C++, AndroidX Test, ActivityScenario, JUnit 4, Gradle.

> **Implementation status 2026-08-20:** Tasks 1-3 are complete. The vivo V2359A matrix passed 100 restore cycles, 50 cancellation-release cycles, and 20 production `MainActivity.onStop()` cancellation cycles with a final active checkpoint count of `0`. The native checkpoint was `20,546,716` bytes; save P50/P95 were `10.869/19.036 ms`, restore P50/P95 were `8.357/16.599385 ms`, the 100/50 instrumentation test completed in `5.495 s`, and the 20-cycle Activity test completed in `25.96 s`. The Activity test binds a real `RagTurnTransaction` to the production `generationJob` cancellation path; it does not fabricate a successful retrieval answer. The edit/switch regression also proves that editing a user message removes its generating RAG tail without modifying another conversation. No production `MainActivity` change was required.

---

### Task 1: Expose checkpoint ownership safely

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `app/src/main/cpp/llama_jni.cpp`
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/LlamaCheckpointInstrumentedTest.kt`

- [x] **Step 1: Write the failing diagnostic assertions**

Add assertions that `nativeContextDebugSnapshot().activeCheckpointCount` changes from `0` to `1` after `beginEphemeralTurn()` and returns to `0` after restore or release.

- [x] **Step 2: Run RED**

Run `./gradlew :app:compileDebugAndroidTestKotlin -x buildGgmlCpu_v86`. Expected: compilation fails because `activeCheckpointCount` does not exist.

- [x] **Step 3: Implement the minimal read-only JNI diagnostic**

Append `activeCheckpointCount: Int` to `NativeContextDebugSnapshot`, add `currentActiveCheckpointCountNative()`, and return `1` only when `g_active_checkpoint != nullptr`. Execute the JNI call on the existing `llamaDispatcher`; do not expose the pointer or handle.

- [x] **Step 4: Run GREEN**

Run the focused Android-test compilation and JVM tests. Expected: PASS.

### Task 2: Add deterministic success/cancellation pressure

**Files:**
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/LlamaCheckpointInstrumentedTest.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/RagTurnTransactionTest.kt`

- [x] **Step 1: Exercise 100 successful closes**

Create and restore 100 checkpoints, asserting the active count is `1` while owned and `0` after every restore. Verify the final native context snapshot equals the stable baseline except for the diagnostic count.

- [x] **Step 2: Exercise 50 cancellation closes**

Create and release 50 checkpoints to model cancellation before evidence can be committed. Assert the active count returns to `0` after every release.

- [x] **Step 3: Exercise transaction idempotency under pressure**

Run 100 commit transactions and 50 double-rollback transactions against the fake engine. Assert one native close per transaction and no duplicate stable-history writes.

- [x] **Step 4: Run the focused matrix**

Run `RagTurnTransactionTest` locally and `LlamaCheckpointInstrumentedTest` on the connected device. Expected: PASS with final active checkpoint count `0`.

### Task 3: Run real Activity lifecycle conflicts

**Files:**
- Create: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/RagTurnLifecycleInstrumentedTest.kt`
- Modify only if RED proves a defect: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [x] **Step 1: Add 20 foreground/background cycles**

Start an actual RAG turn, move `MainActivity` to the background during retrieval or generation, return to foreground, and assert the checkpoint count reaches `0`, the input controls recover, and no blank generating AI message remains.

- [x] **Step 2: Add edit and conversation-switch conflicts**

Cancel an active turn through the same production path used before timeline editing, then edit a user message and switch conversations. Assert the old timeline is truncated correctly, the active conversation owns the visible messages, and the native context contains no old RAG evidence.

- [x] **Step 3: Apply only proven production fixes**

If a RED test fails, preserve `CancellationException`, join the active generation before rebuilding context, and keep rollback in `NonCancellable`. Do not persist transient RAG stages or local safety replies into model context.

- [x] **Step 4: Run GREEN on device**

Run the focused Activity instrumentation test and inspect logcat for checkpoint, cancellation, and stale-delivery errors.

### Task 4: Close the phase

**Files:**
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- Modify: `docs/superpowers/plans/2026-08-20-rag-lifecycle-pressure.md`
- Update: `graphify-out/`

- [ ] **Step 1: Run full verification**

Run JVM tests, Android-test compilation, Debug APK assembly, and installation-signature verification.

- [ ] **Step 2: Update persistent architecture evidence**

Record measured matrix results in both plans and run `graphify update .`.

- [ ] **Step 3: Hand off to large-library indexing**

Proceed to `VectorSearchBackend` and HNSW/partitioned indexing only after the lifecycle matrix is green.
