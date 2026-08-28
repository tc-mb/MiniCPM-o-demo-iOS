package com.example.minicpm_v_demo.rag.work

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.work.ListenableWorker

class RagWorkStagePlanTest {
    @Test
    fun `optional vector index runs only after document finalization`() {
        assertEquals(
            listOf<Class<out ListenableWorker>>(
                ImportCopyWorker::class.java,
                ParseWorker::class.java,
                OcrWorker::class.java,
                ChunkWorker::class.java,
                EmbedWorker::class.java,
                FinalizeIndexWorker::class.java,
                VectorIndexWorker::class.java,
            ),
            RagWorkStagePlan.workerClasses,
        )
    }
}
