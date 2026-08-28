package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.chunk.ChunkConfig
import com.example.minicpm_v_demo.rag.chunk.ChunkIdentity
import com.example.minicpm_v_demo.rag.chunk.DocumentChunker
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.embed.E5TokenizerRegistry
import com.example.minicpm_v_demo.rag.parser.ParsedBlockCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ChunkWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
            ?: return@withContext Result.failure()
        runCatching { RagWorkContract.requireValidDocumentId(documentId) }
            .getOrElse { return@withContext Result.failure() }
        val app = applicationContext as? MiniCPMApplication ?: return@withContext Result.failure()
        val documentDao = app.ragDatabase.documentDao()
        val document = documentDao.findById(documentId) ?: return@withContext Result.failure()
        if (document.status == DocumentStatus.EMBEDDING) return@withContext Result.success()
        if (document.status != DocumentStatus.CHUNKING) return@withContext Result.failure()
        val knowledgeBase = app.ragDatabase.knowledgeBaseDao().findById(document.knowledgeBaseId)
            ?: return@withContext fail(documentId, "KNOWLEDGE_BASE_MISSING")
        val tokenizer = E5TokenizerRegistry.current()
        val decision = ChunkWorkPolicy.decide(
            tokenizer?.let { TokenizerIdentity(it.modelId, it.modelSha256, it.tokenizerSha256) },
            knowledgeBase.embeddingModelId,
            knowledgeBase.embeddingModelSha256,
        )
        if (decision == ChunkPrerequisiteDecision.MODEL_REQUIRED) {
            documentDao.updateStatusAndProgress(
                documentId, DocumentStatus.CHUNKING, 0, 1, System.currentTimeMillis(), "MODEL_REQUIRED", null,
            )
            return@withContext Result.failure()
        }
        if (decision != ChunkPrerequisiteDecision.READY || tokenizer == null) {
            return@withContext fail(documentId, "TOKENIZER_MISMATCH")
        }
        val parsedFile = RagTempFileCleaner.parsedBlockFile(
            RagTempFileCleaner.stagingDirectory(applicationContext.noBackupFilesDir),
            documentId,
        )
        if (!parsedFile.isFile) return@withContext fail(documentId, "PARSED_BLOCKS_UNAVAILABLE")
        try {
            setForeground(
                RagImportNotifications.foregroundInfo(
                    applicationContext,
                    documentId,
                    DocumentStatus.CHUNKING,
                ),
            )
            val store = EncryptedFileStore(app.ragKeyManager::getOrCreateMasterKey)
            var chunkCount = 0
            store.withDecryptedInput(parsedFile) { plaintext ->
                val blocks = ParsedBlockCodec.read(plaintext)
                val entities = DocumentChunker(tokenizer).chunk(
                    blocks,
                    ChunkConfig(version = document.chunkerVersion),
                ).onEach {
                    if (isStopped) throw CancellationException("Chunking cancelled")
                }.map { draft ->
                    ChunkEntity(
                        id = ChunkIdentity.id(documentId, draft.ordinal, draft.contentSha256),
                        documentId = documentId,
                        knowledgeBaseId = document.knowledgeBaseId,
                        ordinal = draft.ordinal,
                        text = draft.text,
                        searchText = draft.searchText,
                        displayName = document.displayName,
                        titlePath = draft.titlePath,
                        locatorType = draft.locatorType,
                        locatorValue = draft.locatorValue,
                        tokenCount = draft.tokenCount,
                        contentSha256 = draft.contentSha256,
                    )
                }
                chunkCount = runBlocking {
                    app.ragDatabase.chunkDao().replaceForDocumentBatched(documentId, entities)
                }
            }
            if (chunkCount == 0) return@withContext fail(documentId, "EMPTY_DOCUMENT")
            documentDao.transition(
                documentId, DocumentStatus.EMBEDDING, chunkCount, chunkCount, System.currentTimeMillis(),
            )
            setProgress(workDataOf(
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_DONE to chunkCount,
                WorkManagerRagWorkCoordinator.KEY_PROGRESS_TOTAL to chunkCount,
            ))
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { terminal(documentId, DocumentStatus.CANCELLED, "CANCELLED") }
            throw cancelled
        } catch (_: Exception) {
            fail(documentId, "CHUNK_FAILED")
        }
    }

    private suspend fun fail(documentId: String, code: String): Result {
        return RagImportFailureHandler.fail(applicationContext, documentId, code)
    }

    private suspend fun terminal(documentId: String, status: DocumentStatus, code: String) {
        val app = applicationContext as? MiniCPMApplication ?: return
        val dao = app.ragDatabase.documentDao()
        val current = dao.findById(documentId) ?: return
        if (current.status in DocumentStatus.activeWorkStates) {
            dao.transition(documentId, status, 0, 1, System.currentTimeMillis(), code)
        }
    }
}
