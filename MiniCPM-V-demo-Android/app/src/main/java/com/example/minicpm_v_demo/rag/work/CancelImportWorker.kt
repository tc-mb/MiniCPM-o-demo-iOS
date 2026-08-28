package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.db.DocumentStatus

class CancelImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val documentId = inputData.getString(RagWorkContract.KEY_DOCUMENT_ID)
            ?: return Result.failure()
        runCatching { RagWorkContract.requireValidDocumentId(documentId) }
            .getOrElse { return Result.failure() }
        val app = applicationContext as? MiniCPMApplication ?: return Result.failure()
        val dao = app.ragDatabase.documentDao()
        val current = dao.findById(documentId) ?: return Result.success()
        if (current.status == DocumentStatus.CANCELLED) return Result.success()
        if (current.status == DocumentStatus.QUEUED || current.status in DocumentStatus.activeWorkStates) {
            dao.transition(
                id = documentId,
                to = DocumentStatus.CANCELLED,
                progressDone = 0,
                progressTotal = current.progressTotal.coerceAtLeast(1),
                updatedAt = System.currentTimeMillis(),
                lastErrorCode = "CANCELLED",
            )
        }
        return Result.success()
    }
}
