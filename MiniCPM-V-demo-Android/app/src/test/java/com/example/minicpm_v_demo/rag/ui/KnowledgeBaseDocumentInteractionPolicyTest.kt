package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseDocumentInteractionPolicyTest {
    @Test
    fun `only successfully imported documents can be deleted by long press`() {
        assertTrue(KnowledgeBaseDocumentInteractionPolicy.canDeleteByLongPress(DocumentStatus.READY))
        assertFalse(KnowledgeBaseDocumentInteractionPolicy.canDeleteByLongPress(DocumentStatus.COPYING))
        assertFalse(KnowledgeBaseDocumentInteractionPolicy.canDeleteByLongPress(DocumentStatus.FAILED))
    }
}
