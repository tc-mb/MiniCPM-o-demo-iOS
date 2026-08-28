package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.retrieval.CurrentRetrievalCalibration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RagWorkStagePlan {
    val workerClasses: List<Class<out ListenableWorker>> = listOf(
        ImportCopyWorker::class.java,
        ParseWorker::class.java,
        OcrWorker::class.java,
        ChunkWorker::class.java,
        EmbedWorker::class.java,
        FinalizeIndexWorker::class.java,
        VectorIndexWorker::class.java,
    )
}

/** Builds an optional per-knowledge-base HNSW acceleration sidecar after the document is READY. */
class VectorIndexWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? MiniCPMApplication ?: return@withContext Result.failure()
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
        val rebuildInput = if (documentId == null) {
            runCatching {
                HnswRebuildInput(
                    knowledgeBaseIds = inputData
                        .getStringArray(HnswRebuildContract.KEY_KNOWLEDGE_BASE_IDS)
                        ?.toList()
                        ?: error("Missing HNSW knowledge bases"),
                    modelSha256 = inputData.getString(HnswRebuildContract.KEY_MODEL_SHA256)
                        ?: error("Missing HNSW model hash"),
                    corpusVersion = inputData.getInt(HnswRebuildContract.KEY_CORPUS_VERSION, 0),
                )
            }.getOrElse { return@withContext Result.failure() }
        } else {
            runCatching { RagWorkContract.requireValidDocumentId(documentId) }
                .getOrElse { return@withContext Result.failure() }
            val document = app.ragDatabase.documentDao().findById(documentId)
                ?: return@withContext Result.failure()
            if (document.status != DocumentStatus.READY) return@withContext Result.failure()
            HnswRebuildInput(
                knowledgeBaseIds = listOf(document.knowledgeBaseId),
                modelSha256 = E5ModelSpec.PINNED.files.getValue("model.int8.onnx"),
                corpusVersion = CurrentRetrievalCalibration.key.corpusVersion,
            )
        }

        try {
            HnswRebuildRunner(
                chunkDao = app.ragDatabase.chunkDao(),
                indexDirectory = app.hnswIndexDirectory,
                publisher = app.hnswIndexPublisher,
            ).rebuild(
                input = rebuildInput,
                shouldContinue = { !isStopped },
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isStopped) throw CancellationException("HNSW index build cancelled")
            // HNSW is an optional acceleration layer. Room vectors remain the source of truth.
            Result.success()
        }
    }
}
