# Semantic Visual Output Guard Implementation Plan

> **Archived 2026-08-18:** 本计划的视觉保护部分已完成；RAG Groundedness 输出审查继续由 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md) 跟踪。本文不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three-way input intent and output assertion classification, block unsupported visual claims when no visual context exists, and preserve discovered bypasses as repeatable regression cases.

**Architecture:** Keep `VisualContextPolicy` as the authoritative conversation state and add pure Kotlin classifiers for prompt intent and generated-answer assertions. `LlamaEngine` retains a second input gate, while `MainActivity` buffers answers generated without visual context and only displays them after the output policy accepts them. A TSV corpus under test resources records bypasses independently of implementation lists.

**Tech Stack:** Kotlin, Android coroutines/Flow, JUnit 4, Gradle.

---

### Task 1: Define classification behavior with failing tests

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/VisualContextPolicyTest.kt`
- Create: `MiniCPM-V-demo-Android/app/src/test/resources/visual_guard_regression_cases.tsv`

- [ ] **Step 1: Add tests for input labels**

Add assertions for `NEED_VISUAL`, `TEXT_ONLY`, and `UNCERTAIN`, including indirect Chinese and English references.

- [ ] **Step 2: Add tests for output labels**

Add assertions for `VISUAL_ASSERTION`, `NON_VISUAL_RESPONSE`, and `UNCERTAIN_VISUAL_ASSERTION`.

- [ ] **Step 3: Add a data-driven regression test**

Read `visual_guard_regression_cases.tsv` from the test classpath and verify every case against its expected label.

- [ ] **Step 4: Run the focused test and verify RED**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.minicpm_v_demo.VisualContextPolicyTest`

Expected: compilation fails because the new classifier types and APIs do not exist.

### Task 2: Implement pure Kotlin classification and decisions

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/VisualContextPolicy.kt`

- [ ] **Step 1: Add bounded normalization**

Normalize Unicode, case, whitespace, and punctuation with a hard input length cap and without dynamic or backtracking-heavy regular expressions.

- [ ] **Step 2: Implement input intent classification**

Return `NEED_VISUAL`, `TEXT_ONLY`, or `UNCERTAIN` based on explicit visual references, indirect references plus perception actions, and safe text-only counterexamples.

- [ ] **Step 3: Implement output assertion classification**

Return `VISUAL_ASSERTION`, `NON_VISUAL_RESPONSE`, or `UNCERTAIN_VISUAL_ASSERTION`; recognize safe inability/upload messages before visual-claim patterns.

- [ ] **Step 4: Implement policy decisions**

Block `NEED_VISUAL` and `UNCERTAIN` prompts without visual context. Block visual and uncertain assertions when the response was generated without visual context.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.minicpm_v_demo.VisualContextPolicyTest`

Expected: all focused tests pass.

### Task 3: Integrate the output gate before UI disclosure

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Expose typed decisions from the engine**

Replace boolean-only prompt gating with a typed prompt decision while retaining a second check inside `sendUserPrompt`; expose response evaluation using the visual-context snapshot taken before generation.

- [ ] **Step 2: Buffer no-visual responses**

When generation starts without visual context, keep the candidate response out of the chat bubble until generation completes.

- [ ] **Step 3: Apply the response decision**

Display accepted text; replace rejected visual assertions with a localized fixed no-image response. Keep normal streaming behavior when visual context exists.

- [ ] **Step 4: Compile tests**

Run: `gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`

Expected: Kotlin production and test sources compile.

### Task 4: Verify and document

**Files:**
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Run all unit tests**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: all unit tests pass.

- [ ] **Step 2: Run Android lint**

Run: `gradlew.bat :app:lintDebug`

Expected: zero lint errors.

- [ ] **Step 3: Build the debug APK**

Run: `gradlew.bat :app:assembleDebug`

Expected: debug APK is produced successfully.

- [ ] **Step 4: Document behavior and limitations**

Explain the double input/output guard, regression corpus location, conservative uncertain handling, and that the initial local classifier is replaceable by a trained TFLite semantic classifier.
