package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RagDocumentStageResourcesTest {
    @Test
    fun `every active import status has its own shared page and notification text`() {
        val stages = listOf(
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.PARSING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING,
        )

        val resources = stages.map(RagDocumentStageResources::bodyFor)

        assertEquals(stages.size, resources.distinct().size)
        assertNotEquals(
            RagDocumentStageResources.bodyFor(DocumentStatus.COPYING),
            RagDocumentStageResources.bodyFor(DocumentStatus.EMBEDDING),
        )
    }

    @Test
    fun `ready has a completion label and terminal failures are not foreground stages`() {
        assertEquals(
            com.example.minicpm_v_demo.R.string.rag_document_stage_ready,
            RagDocumentStageResources.bodyFor(DocumentStatus.READY),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RagDocumentStageResources.bodyFor(DocumentStatus.FAILED)
        }
    }
}
