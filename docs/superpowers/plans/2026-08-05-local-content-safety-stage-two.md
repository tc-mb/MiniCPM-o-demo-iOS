# Local Content Safety Stage Two Implementation Plan

> **Archived 2026-08-18:** 本计划已完成并归入 [MiniCPM Android 统一进度与后续实施计划](../../../MiniCPM-V-demo-Android/docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md)。本文仅保留历史设计与测试细节，不再单独更新进度。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-device privacy and illegal-content detection, an `ALLOW/WARNING/BLOCK/REVIEW` policy engine, explicit privacy confirmation, and fixed locally streamed safety replies.

**Architecture:** A bounded pure-Kotlin classifier detects actual identity numbers, phone numbers, addresses, actionable illegal instructions, and ambiguous evasion intent. A deterministic policy engine converts signals into one of four decisions. `MainActivity` checks inputs before native inference, buffers all model output until post-generation review, and keeps privacy confirmations and fixed safety replies outside model context.

**Tech Stack:** Kotlin, Android lifecycle coroutines, JUnit 4, Gradle, existing RecyclerView chat renderer.

---

### Task 1: Define classifier and policy behavior

**Files:**
- Create: `MiniCPM-V-demo-Android/app/src/test/java/com/example/minicpm_v_demo/ContentSafetyPolicyTest.kt`
- Create: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/ContentSafetyPolicy.kt`

- [ ] **Step 1: Write privacy detection tests**

Require real Chinese identity-number, mobile-number, and structured-address samples to produce `WARNING`; require generic questions about identity-card formats and image-recognition technology to remain `ALLOW`.

- [ ] **Step 2: Write illegal-content tests**

Require actionable fraud, credential theft, explosive construction, and forged-document instructions to produce `BLOCK`; require anti-fraud and legal-risk education to remain `ALLOW`.

- [ ] **Step 3: Write review tests**

Require ambiguous evasion phrases such as asking how to avoid being discovered without a concrete benign context to produce `REVIEW`.

- [ ] **Step 4: Write explicit confirmation tests**

Accept exact affirmative forms such as `是` and `确认显示`, exact negative forms such as `否` and `取消`, and reject substring tricks such as `不是` or unrelated sentences.

- [ ] **Step 5: Run focused tests and verify RED**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.minicpm_v_demo.ContentSafetyPolicyTest`

Expected: compilation fails because the classifier, policy engine, and confirmation parser do not exist.

- [ ] **Step 6: Implement bounded deterministic classification**

Normalize at most 8,192 characters, use only fixed safe regular expressions for formatted phone/identity sequences, detect addresses with bounded markers, and apply safe educational counterexamples before illegal-action rules.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the focused command again and require all tests to pass.

### Task 2: Add input safety routing and privacy confirmation

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LocalGuardReplyPolicy.kt`

- [ ] **Step 1: Add pending privacy actions**

Represent a pending private prompt and a pending private response as mutually exclusive in-memory values; clear them on chat reset and model switch.

- [ ] **Step 2: Route input decisions before visual routing**

`ALLOW` continues, `WARNING` stores the prompt and streams a fixed confirmation request, `BLOCK` streams a fixed refusal, and `REVIEW` streams a fixed unable-to-review message. All non-allow branches return before attachment consumption and `sendUserPrompt`.

- [ ] **Step 3: Handle exact yes/no replies locally**

Exact yes submits the stored original prompt without adding a duplicate user bubble; exact no discards it. The yes/no text and confirmation messages remain UI-only and are never passed to the native model.

### Task 3: Review every generated response before display

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`

- [ ] **Step 1: Buffer all generated tokens**

Keep the candidate response out of the visible chat cell for both text and visual conversations until generation completes.

- [ ] **Step 2: Compose visual and content decisions**

Visual grounding rejection has highest priority, followed by content `BLOCK`, `REVIEW`, privacy `WARNING`, then `ALLOW`.

- [ ] **Step 3: Stream only the selected display text**

Locally stream an allowed candidate or a fixed safety reply into the existing assistant bubble. For privacy warning, retain the candidate only in memory and reveal it only after an explicit local affirmative reply.

### Task 4: Localize, document, and verify

**Files:**
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values/strings.xml`
- Modify: `MiniCPM-V-demo-Android/app/src/main/res/values-en/strings.xml`
- Modify: `MiniCPM-V-demo-Android/README_MODIFIED_zh.md`

- [ ] **Step 1: Add fixed safety text**

Add separate localized messages for privacy confirmation, privacy cancellation, invalid confirmation, illegal-content refusal, and manual-review fallback.

- [ ] **Step 2: Document privacy behavior**

State that pending private prompts/responses live only in memory, require exact confirmation, are cleared on reset, and are never written to logs.

- [ ] **Step 3: Run all checks**

Run: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Expected: all tests pass, lint reports zero errors, and the debug APK builds.

- [ ] **Step 4: Install and launch on the connected phone**

Use `adb install -r`, start `MainActivity`, and verify the package process remains alive.
