package com.example.minicpm_v_demo.rag.work

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minicpm_v_demo.rag.index.EmbeddingCorpusKey
import java.util.concurrent.TimeUnit

class WorkManagerHnswRebuildScheduler(
    private val workManager: WorkManager,
) {
    fun enqueue(corpusKey: EmbeddingCorpusKey) {
        val input = HnswRebuildContract.inputValues(corpusKey)
        val request = OneTimeWorkRequestBuilder<VectorIndexWorker>()
            .setInputData(
                Data.Builder()
                    .putStringArray(
                        HnswRebuildContract.KEY_KNOWLEDGE_BASE_IDS,
                        input.knowledgeBaseIds.toTypedArray(),
                    )
                    .putString(HnswRebuildContract.KEY_MODEL_SHA256, input.modelSha256)
                    .putInt(HnswRebuildContract.KEY_CORPUS_VERSION, input.corpusVersion)
                    .build(),
            )
            .setInitialDelay(HnswRebuildContract.INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .addTag(HnswRebuildContract.uniqueWorkName(corpusKey))
            .build()
        workManager.enqueueUniqueWork(
            HnswRebuildContract.uniqueWorkName(corpusKey),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
