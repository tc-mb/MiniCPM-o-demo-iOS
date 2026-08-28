package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentDao
import com.example.minicpm_v_demo.rag.db.DocumentStatus

class RagWorkRecovery(
    private val documentDao: DocumentDao,
    private val coordinator: RagWorkCoordinator,
) {
    suspend fun rescheduleInterruptedImports(retryModelBindingFailures: Boolean = false): Int {
        if (retryModelBindingFailures) {
            documentDao.findRetryableModelBindingFailures().forEach { document ->
                documentDao.transition(
                    id = document.id,
                    to = DocumentStatus.QUEUED,
                    progressDone = 0,
                    progressTotal = 1,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        val documents = documentDao.findRecoverableImports()
            .filter { RagWorkRecoveryPolicy.shouldReschedule(it.status) }
        documents.forEach { coordinator.enqueue(it.id) }
        return documents.size
    }
}
