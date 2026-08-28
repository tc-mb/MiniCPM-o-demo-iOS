package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Completes the exact-vector baseline index. Task 9 may add an ANN sidecar without changing stored vectors. */
class FinalizeIndexWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
            ?: return@withContext Result.failure()
        runCatching { RagWorkContract.requireValidDocumentId(documentId) }
            .getOrElse { return@withContext Result.failure() }
        val app = applicationContext as? MiniCPMApplication ?: return@withContext Result.failure()
        val documentDao = app.ragDatabase.documentDao()
        val chunkDao = app.ragDatabase.chunkDao()
        val document = documentDao.findById(documentId) ?: return@withContext Result.failure()
        if (document.status == DocumentStatus.READY) return@withContext Result.success()
        if (document.status != DocumentStatus.INDEXING) return@withContext Result.failure()
        setForeground(
            RagImportNotifications.foregroundInfo(
                applicationContext,
                documentId,
                DocumentStatus.INDEXING,
            ),
        )
        val modelSha = E5ModelSpec.PINNED.files.getValue("model.int8.onnx")
        val chunks = chunkDao.findByDocument(documentId)
        if (chunks.isEmpty() || chunkDao.findChunksNeedingEmbedding(documentId, modelSha).isNotEmpty() ||
            chunks.any { it.embeddingState != ChunkEntity.EMBEDDING_READY }
        ) return@withContext RagImportFailureHandler.fail(
            applicationContext,
            documentId,
            "INDEX_FINALIZATION_FAILED",
        )
        documentDao.transition(documentId, DocumentStatus.READY, chunks.size, chunks.size, System.currentTimeMillis())
        Result.success()
    }
}
