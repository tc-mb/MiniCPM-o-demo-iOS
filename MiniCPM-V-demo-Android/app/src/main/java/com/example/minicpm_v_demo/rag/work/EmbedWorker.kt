package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.embed.E5InputKind
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbedWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
            ?: return@withContext Result.failure()
        runCatching { RagWorkContract.requireValidDocumentId(documentId) }
            .getOrElse { return@withContext Result.failure() }
        val app = applicationContext as? MiniCPMApplication ?: return@withContext Result.failure()
        val documentDao = app.ragDatabase.documentDao()
        val chunkDao = app.ragDatabase.chunkDao()
        val document = documentDao.findById(documentId) ?: return@withContext Result.failure()
        if (document.status == DocumentStatus.INDEXING) return@withContext Result.success()
        if (document.status != DocumentStatus.EMBEDDING) return@withContext Result.failure()
        val embedder = app.embeddingModelManager.openInstalled()
        if (embedder == null) {
            documentDao.updateStatusAndProgress(documentId, DocumentStatus.EMBEDDING, 0, 1,
                System.currentTimeMillis(), "MODEL_REQUIRED", null)
            return@withContext Result.failure()
        }
        try {
            setForeground(
                RagImportNotifications.foregroundInfo(
                    applicationContext,
                    documentId,
                    DocumentStatus.EMBEDDING,
                ),
            )
            val allChunks = chunkDao.findByDocument(documentId)
            val chunks = chunkDao.findChunksNeedingEmbedding(documentId, embedder.modelSha256)
            val alreadyDone = allChunks.size - chunks.size
            if (allChunks.isEmpty()) return@withContext fail(documentId, "EMPTY_DOCUMENT")
            var done = alreadyDone
            chunks.chunked(BATCH_SIZE).forEach { batch ->
                if (isStopped) throw CancellationException("Embedding cancelled")
                val vectors = embedder.embed(batch.map(ChunkEntity::text), E5InputKind.PASSAGE)
                val now = System.currentTimeMillis()
                chunkDao.storeEmbeddingBatch(batch.zip(vectors) { chunk, vector ->
                    ChunkEmbeddingEntity(
                        chunkId = chunk.id,
                        modelSha256 = embedder.modelSha256,
                        dimension = vector.size,
                        vector = FloatVectorCodec.encode(vector),
                        updatedAt = now,
                    )
                })
                done += batch.size
                documentDao.updateStatusAndProgress(documentId, DocumentStatus.EMBEDDING, done, allChunks.size,
                    System.currentTimeMillis(), null, null)
                setProgress(workDataOf(
                    WorkManagerRagWorkCoordinator.KEY_PROGRESS_DONE to done,
                    WorkManagerRagWorkCoordinator.KEY_PROGRESS_TOTAL to allChunks.size,
                ))
            }
            documentDao.transition(documentId, DocumentStatus.INDEXING, done, allChunks.size, System.currentTimeMillis())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(documentId, "EMBED_FAILED")
        }
    }

    private suspend fun fail(documentId: String, code: String): Result =
        RagImportFailureHandler.fail(applicationContext, documentId, code)

    companion object { private const val BATCH_SIZE = 4 }
}
