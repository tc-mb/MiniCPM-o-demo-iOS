# RAG Stage UI And Review Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 显示真实的知识库检索、依据整理和回答生成阶段，并为 Groundedness 分类增加独立超时降级，同时保证阶段状态不进入模型上下文或会话归档。

**Architecture:** `RagCoordinator` 在确认需要检索后通过可选 suspend callback 报告 `RETRIEVING/ORGANIZING`；`MainActivity` 将其映射到 `AiMessage` 的内存态 `RagGenerationStage`，发送模型前切到 `GENERATING`。归档编解码器继续忽略该字段。分类 watchdog 只包装 `GroundednessClassifier`，超时转为普通异常，让现有 reviewed generation 安全降级普通回答；外层取消仍保持 `CancellationException` 语义。

**Tech Stack:** Kotlin、Coroutines、Room/RAG Coordinator、Android RecyclerView、JUnit 4。

> **Implementation status 2026-08-20:** 全部任务已完成；聚焦回归、全量 JVM、Debug APK 和安装签名校验通过。阶段字段未进入归档，规划与 Groundedness 分类均有 15 秒上限，模型生成本身不受该 watchdog 限制。

---

### Task 1: Add deterministic planning stages

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/RagCoordinator.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/RagCoordinatorTest.kt`

- [x] **Step 1: Write RED tests**

Assert Ready emits exactly `RETRIEVING, ORGANIZING`; Disabled and NoRetrieval emit none.

- [x] **Step 2: Run RED**

Run focused coordinator tests; expect missing stage API.

- [x] **Step 3: Implement minimal callbacks**

Emit `RETRIEVING` only after route/selection is eligible and immediately before retriever access. Emit `ORGANIZING` only after accepted evidence is non-empty and before reducer/budget/prompt construction.

- [x] **Step 4: Run GREEN**

Run focused coordinator tests; expect PASS.

### Task 2: Render stages without persistence

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatMessage.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/ConversationArchiveTest.kt`

- [x] **Step 1: Write archive RED test**

Create an in-memory generating AI message with a RAG stage, round-trip archive v2, and assert the restored message has no stage.

- [x] **Step 2: Add the transient field and UI mapping**

Append `ragGenerationStage` to `AiMessage` so existing positional constructors remain source compatible. Display localized stage text only while `text` is blank and `isGenerating=true`.

- [x] **Step 3: Wire MainActivity**

Coordinator callbacks update the active AI placeholder on Main. Ready switches to `GENERATING` before model collection. Pass-through and final messages clear the stage.

- [x] **Step 4: Run GREEN**

Run archive and adapter-related JVM tests; expect PASS.

### Task 3: Bound Groundedness classification

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerator.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/guard/RagReviewedGenerationTest.kt`

- [x] **Step 1: Write timeout RED test**

Wrap a classifier that suspends longer than the configured bound and assert reviewed generation returns `FallbackToNormalGeneration` without exposing the candidate.

- [x] **Step 2: Implement classifier-only watchdog**

Use `withTimeoutOrNull`; convert watchdog expiry to a private non-cancellation exception. Do not catch caller cancellation and do not time-limit LLM generation or correction generation.

- [x] **Step 3: Wire the production bound**

Wrap the installed Groundedness classifier with a 15-second watchdog before constructing `RagReviewedGenerator`.

- [x] **Step 4: Verify full build**

Run full JVM tests, Debug APK build and installation-signature verification.
