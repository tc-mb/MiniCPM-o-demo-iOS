package com.example.minicpm_v_demo.rag.work

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RagWorkUiState(
    val state: WorkInfo.State,
    val progressDone: Int,
    val progressTotal: Int,
    val failureDocumentId: String? = null,
    val failureKnowledgeBaseId: String? = null,
    val failureDisplayName: String? = null,
    val failureErrorCode: String? = null,
)

interface RagWorkCoordinator {
    fun enqueue(documentId: String): Operation
    fun cancel(documentId: String): Operation
    fun observe(documentId: String): Flow<RagWorkUiState?>
}

class WorkManagerRagWorkCoordinator(
    private val workManager: WorkManager,
) : RagWorkCoordinator {
    override fun enqueue(documentId: String): Operation {
        val input = RagWorkContract.inputValues(documentId)
        val workName = RagWorkContract.uniqueWorkName(documentId)
        val requests = RagWorkStagePlan.workerClasses.map { workerClass ->
            androidx.work.OneTimeWorkRequest.Builder(workerClass)
                .setInputData(Data.Builder().apply { input.forEach(::putString) }.build())
                .addTag(workName)
                .build()
        }
        var continuation = workManager.beginUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { request -> continuation = continuation.then(request) }
        return continuation.enqueue()
    }

    override fun cancel(documentId: String): Operation {
        RagWorkContract.requireValidDocumentId(documentId)
        val request = OneTimeWorkRequestBuilder<CancelImportWorker>()
            .setInputData(Data.Builder().putString(RagWorkContract.KEY_DOCUMENT_ID, documentId).build())
            .build()
        return workManager.beginUniqueWork(
            RagWorkContract.uniqueWorkName(documentId),
            ExistingWorkPolicy.REPLACE,
            request,
        ).enqueue()
    }

    override fun observe(documentId: String): Flow<RagWorkUiState?> =
        workManager.getWorkInfosForUniqueWorkFlow(RagWorkContract.uniqueWorkName(documentId))
            .map { workInfos ->
                RagWorkRecoveryPolicy.selectObservable(
                    workInfos,
                    isActive = { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
                    isFailed = { it.state == WorkInfo.State.FAILED },
                )
                    ?.let { info ->
                    RagWorkUiState(
                        state = info.state,
                        progressDone = info.progress.getInt(KEY_PROGRESS_DONE, 0),
                        progressTotal = info.progress.getInt(KEY_PROGRESS_TOTAL, 0),
                        failureDocumentId = info.outputData.getString(RagImportFailureData.KEY_DOCUMENT_ID),
                        failureKnowledgeBaseId = info.outputData.getString(RagImportFailureData.KEY_KNOWLEDGE_BASE_ID),
                        failureDisplayName = info.outputData.getString(RagImportFailureData.KEY_DISPLAY_NAME),
                        failureErrorCode = info.outputData.getString(RagImportFailureData.KEY_ERROR_CODE),
                    )
                }
            }

    companion object {
        const val KEY_PROGRESS_DONE = "progressDone"
        const val KEY_PROGRESS_TOTAL = "progressTotal"
    }

}
