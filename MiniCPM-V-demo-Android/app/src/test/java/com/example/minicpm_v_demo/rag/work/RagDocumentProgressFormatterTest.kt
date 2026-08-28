package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RagDocumentProgressFormatterTest {
    @Test
    fun `progress is shown only when total is known`() {
        assertEquals("COPYING · 1/4", RagDocumentProgressFormatter.format(DocumentStatus.COPYING, 1, 4))
        assertEquals("QUEUED", RagDocumentProgressFormatter.format(DocumentStatus.QUEUED, 0, 0))
    }
}
