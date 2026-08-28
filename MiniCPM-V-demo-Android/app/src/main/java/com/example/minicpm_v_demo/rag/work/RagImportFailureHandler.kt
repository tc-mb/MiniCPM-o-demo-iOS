package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.work.ListenableWorker
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.storage.RagDocumentRemovalService

object RagImportFailureHandler {
    suspend fun fail(context: Context, documentId: String, errorCode: String): ListenableWorker.Result {
        val app = context.applicationContext as? MiniCPMApplication ?: return ListenableWorker.Result.failure()
        val dao = app.ragDatabase.documentDao()
        val document = dao.findById(documentId) ?: return ListenableWorker.Result.failure()
        val publicData = RagImportFailureData.encode(document, errorCode)
        runCatching {
            RagDocumentRemovalService(
                stagingDirectory = RagTempFileCleaner.stagingDirectory(context.noBackupFilesDir),
                deleteRecord = dao::deleteById,
            ).remove(document)
        }.onFailure {
            val current = dao.findById(documentId)
            if (current != null && current.status in DocumentStatus.activeWorkStates) {
                dao.transition(
                    id = documentId,
                    to = DocumentStatus.FAILED,
                    progressDone = 0,
                    progressTotal = current.progressTotal.coerceAtLeast(1),
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = errorCode,
                )
            }
        }
        return ListenableWorker.Result.failure(publicData)
    }
}
