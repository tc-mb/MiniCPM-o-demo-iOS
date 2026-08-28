package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeBaseDocumentPresentationTest {
    @Test
    fun `failed documents remain visible with a safe reason`() {
        assertEquals(
            KnowledgeBaseDocumentPresentation.Failure("加密失败"),
            KnowledgeBaseDocumentPresentation.from(DocumentStatus.FAILED, "ENCRYPTION_FAILED"),
        )
        assertEquals(
            KnowledgeBaseDocumentPresentation.Failure("导入失败"),
            KnowledgeBaseDocumentPresentation.from(DocumentStatus.FAILED, "unexpected-private-detail"),
        )
        assertEquals(
            KnowledgeBaseDocumentPresentation.Failure("知识库模型版本未同步，请重试导入"),
            KnowledgeBaseDocumentPresentation.from(DocumentStatus.FAILED, "TOKENIZER_MISMATCH"),
        )
        assertEquals(
            KnowledgeBaseDocumentPresentation.Failure("文档切块失败"),
            KnowledgeBaseDocumentPresentation.from(DocumentStatus.FAILED, "CHUNK_FAILED"),
        )
    }

    @Test
    fun `every active stage remains processing and only ready is completed`() {
        val active = listOf(
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.PARSING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING,
        )
        active.forEach { status ->
            assertEquals(
                KnowledgeBaseDocumentPresentation.Processing(status),
                KnowledgeBaseDocumentPresentation.from(status, null),
            )
        }
        assertEquals(
            KnowledgeBaseDocumentPresentation.Uploaded,
            KnowledgeBaseDocumentPresentation.from(DocumentStatus.READY, null),
        )
    }

    @Test
    fun `terminal non-failure documents do not remain in the status list`() {
        assertNull(KnowledgeBaseDocumentPresentation.from(DocumentStatus.CANCELLED, "CANCELLED"))
    }
}
