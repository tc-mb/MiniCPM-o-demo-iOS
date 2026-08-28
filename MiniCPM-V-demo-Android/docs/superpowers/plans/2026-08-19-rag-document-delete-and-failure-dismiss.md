# RAG 文档删除与失败提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让已成功导入的知识库文档可通过长按确认删除，让失败导入只留下可左滑移除的临时提示，并保证失败文档不会占用文件、文档记录或同内容唯一索引。

**Architecture:** 新增一个只处理应用私有 RAG 文件的安全清理器，以及一个协调文件清理和 Room 文档删除的服务。各导入 Worker 通过统一失败处理器删除失败文档并在 WorkManager 输出中携带非敏感失败摘要；`KnowledgeBaseActivity` 只在内存中保存失败提示。适配器为成功文档状态行绑定长按，为失败提示绑定水平滑动删除，不改变知识库卡片的选择操作。

**Tech Stack:** Kotlin、Android ListView/Material Components、Room、WorkManager、JUnit4、AndroidX instrumented tests。

> **Completed 2026-08-20:** 代码、JVM 回归、真机 Room 级联/同内容重传、成功文档长按删除、失败提示左滑移除和同名重传均已验收；实现提交为 `9b229c220690123af5ec00b37742d110f9bcc18b`。

---

### Task 1: 固定安全清理和同名重传的数据行为

**Files:**
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/storage/RagDocumentArtifactCleanerTest.kt`
- Modify: `app/src/androidTest/java/com/example/minicpm_v_demo/rag/db/RagDatabaseDaoTest.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/storage/RagDocumentArtifactCleaner.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/db/RagDaos.kt`

- [ ] **Step 1: Write the failing artifact-boundary tests**

```kotlin
@Test fun `delete removes only the expected encrypted source and parsed blocks`()
@Test fun `delete rejects a document id or private name that can escape staging`()
```

- [ ] **Step 2: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*RagDocumentArtifactCleanerTest'`

Expected: FAIL because `RagDocumentArtifactCleaner` does not exist.

- [ ] **Step 3: Add the document-level Room deletion regression**

```kotlin
documentDao.deleteById(first.id)
documentDao.upsert(second.copy(sha256 = first.sha256))
assertNotNull(documentDao.findById(second.id))
```

- [ ] **Step 4: Implement bounded artifact cleanup and DAO deletion**

`RagDocumentArtifactCleaner.delete(stagingDirectory, document)` must require a safe document ID, require `privateFileName == "${document.id}.src.enc"`, reject a symbolic-link staging directory, and delete only that source plus `${document.id}.blocks.enc`. Add `DocumentDao.deleteById(id)`; Room foreign keys cascade chunks, vectors and citations.

- [ ] **Step 5: Run GREEN**

Run the focused unit test and `RagDatabaseDaoTest`; expect PASS.

### Task 2: Make failed imports self-cleaning and observable without a RAG document row

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/work/RagImportFailureData.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/work/RagImportFailureHandler.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/work/{ImportCopy,Parse,Ocr,Chunk,Embed,FinalizeIndex}Worker.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/rag/work/RagWorkCoordinator.kt`
- Modify: `app/src/test/java/com/example/minicpm_v_demo/rag/work/RagWorkRecoveryPolicyTest.kt`
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/work/RagImportFailureDataTest.kt`

- [ ] **Step 1: Write RED tests**

Assert that failure output contains only `documentId`, `knowledgeBaseId`, display name and allowlisted error code; assert observable WorkInfo selection prefers a FAILED worker over earlier SUCCEEDED or later BLOCKED workers.

- [ ] **Step 2: Run RED**

Run the two focused test classes; expect missing APIs or incorrect selection.

- [ ] **Step 3: Implement the unified failure path**

Before returning `Result.failure(data)`, capture the non-sensitive summary, delete internal artifacts, and delete the Room document row. Do not expose `lastErrorDetail`, source URI or filesystem paths. Preserve `MODEL_REQUIRED` as resumable rather than treating it as a terminal import failure.

- [ ] **Step 4: Run GREEN**

Run all `rag.work` unit tests; expect PASS.

### Task 3: Add long-press deletion and swipe-dismiss failure notices

**Files:**
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/ui/FailedImportNotice.kt`
- Create: `app/src/main/java/com/example/minicpm_v_demo/rag/ui/HorizontalSwipeDismissPolicy.kt`
- Create: `app/src/test/java/com/example/minicpm_v_demo/rag/ui/HorizontalSwipeDismissPolicyTest.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/KnowledgeBaseAdapter.kt`
- Modify: `app/src/main/java/com/example/minicpm_v_demo/KnowledgeBaseActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Write the swipe-policy RED test**

```kotlin
assertTrue(HorizontalSwipeDismissPolicy.shouldDismiss(startX = 300f, endX = 180f, density = 1f))
assertFalse(HorizontalSwipeDismissPolicy.shouldDismiss(startX = 180f, endX = 300f, density = 1f))
```

- [ ] **Step 2: Run RED**

Expected: FAIL because the policy does not exist.

- [ ] **Step 3: Implement the minimal gesture and callback APIs**

`KnowledgeBaseListItem` receives transient `failedImports`. READY document chips get a long-click callback and confirmation dialog; failed chips get a left-swipe listener with distance and vertical-drift thresholds, then animate out and remove only the in-memory notice. Knowledge-base card taps remain selection-only.

- [ ] **Step 4: Wire enqueue observation and document deletion**

Observe each returned document ID. On terminal failure, add one `FailedImportNotice` from WorkManager output; on success, rely on Room refresh. Confirmed READY deletion cancels stale work, deletes bounded artifacts and the document row, then refreshes the list.

- [ ] **Step 5: Run GREEN**

Run focused UI policy/unit tests and the full JVM test suite; expect PASS.

### Task 4: Verify build, security boundaries and persisted project graph

**Files:**
- Modify: `graphify-out/*`

- [ ] **Step 1: Run full verification**

Run: `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:verifyInstallationSigning -x buildGgmlCpu_v86`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Review deletion safety**

Confirm no filename from UI, URI or provider is passed directly to `File.delete`; only the expected ID-derived names inside `noBackupFilesDir/rag/staging` are eligible. Confirm failure output contains no URI or private path.

- [ ] **Step 3: Refresh Graphify**

Run `graphify update .`, inspect health output, and stage only the persistent graph outputs.

- [ ] **Step 4: Manual UI verification**

Import one valid document and long-press its green status row; cancel once, then confirm deletion. Import one invalid document, verify only a red reason remains, swipe it left, and upload the same filename again.
