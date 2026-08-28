# RAG Source Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让历史回答的来源 Chip 在原文仍存在时展示当前索引原文并定位引用块，在原文删除后继续展示不可变归档摘录和明确的删除状态。

**Architecture:** `CitationRef` 继续作为归档快照，不修改会话文件格式。点击来源时，Activity 在 IO 线程按 `documentId + chunkId` 查询 Room，并交给纯 Kotlin resolver 校验文档、切块和引用之间的关系；只有完全匹配才显示当前索引原文，否则一律降级为归档摘录，不读取任意文件路径。

**Tech Stack:** Kotlin、Room、Android Material Dialog、JUnit 4、Graphify。

> **Implementation status 2026-08-20:** Task 1、Task 2 和 Task 3 的代码/计划同步已完成；全量 JVM、Debug APK 和签名校验通过。Graphify AST 图已更新到 2,648 节点、5,277 边；因当前没有 Gemini 后端且本轮未获特定子代理授权，新增/修改计划文档的语义增量仍明确待处理。

---

### Task 1: Resolve current and deleted sources

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/ui/CitationSourceResolver.kt`
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/ui/CitationSourceResolverTest.kt`

- [x] **Step 1: Write failing tests**

Add tests proving a matching document/chunk resolves to current indexed text, while a missing document, missing chunk, or cross-document chunk resolves only to the archived snapshot.

- [x] **Step 2: Run RED**

Run `:app:testDebugUnitTest --tests '*CitationSourceResolverTest'`; expect compilation failure because the resolver does not exist.

- [x] **Step 3: Implement minimal resolver**

Return only `Available` or `Deleted`. `Available` requires matching document ID, chunk ID and chunk document ID; all other states fail closed to `Deleted`.

- [x] **Step 4: Run GREEN**

Run the focused test; expect PASS.

### Task 2: Connect source chips to Room lifecycle state

**Files:**
- Modify: `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [x] **Step 1: Query on IO dispatcher**

Load the exact document and chunk IDs from Room. Never construct a path from citation data and never query another knowledge base by display name.

- [x] **Step 2: Render available source**

Show file snapshot, locator, current indexed block and a clear “source available” status.

- [x] **Step 3: Render deleted source**

Keep the archived filename, locator and quoted excerpt, and show “source deleted; archived excerpt retained.”

- [x] **Step 4: Verify build and full JVM suite**

Run `:app:testDebugUnitTest :app:assembleDebug :app:verifyInstallationSigning -x buildGgmlCpu_v86`; expect BUILD SUCCESSFUL.

### Task 3: Synchronize active progress and Graphify

**Files:**
- Modify: `docs/superpowers/plans/2026-08-18-minicpm-android-unified-progress-plan.md`
- Modify: `docs/superpowers/plans/2026-08-19-rag-document-delete-and-failure-dismiss.md`
- Modify: `graphify-out/*`

- [x] **Step 1: Record completed gesture acceptance**

Mark successful long-press deletion and failed-notice swipe dismissal as manually accepted.

- [x] **Step 2: Record source lifecycle status**

Mark indexed-block positioning and deleted-source state complete; keep external binary page/cell deep-linking as a later enhancement.

- [ ] **Step 3: Refresh the persistent graph**

Run the required semantic/document update followed by `graphify update .`, then inspect warnings and persistent outputs.

AST/code graph refresh is complete. Semantic extraction for the three changed plan documents remains pending for the reason recorded above.
