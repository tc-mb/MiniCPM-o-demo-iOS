package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RagImportFailureDataTest {
    @Test
    fun `failure data exposes only a non-sensitive summary`() {
        val data = RagImportFailureData.encode(document(), "PARSE_FAILED")

        assertEquals("doc-1", data.getString(RagImportFailureData.KEY_DOCUMENT_ID))
        assertEquals("kb-1", data.getString(RagImportFailureData.KEY_KNOWLEDGE_BASE_ID))
        assertEquals("合同.txt", data.getString(RagImportFailureData.KEY_DISPLAY_NAME))
        assertEquals("PARSE_FAILED", data.getString(RagImportFailureData.KEY_ERROR_CODE))
        assertFalse(data.keyValueMap.values.any { it.toString().contains("content://") })
        assertFalse(data.keyValueMap.values.any { it.toString().contains(".src.enc") })
    }

    @Test
    fun `unknown internal errors are reduced to a stable public code`() {
        val data = RagImportFailureData.encode(document(), "private exception with /data/user/0/path")

        assertEquals("IMPORT_FAILED", data.getString(RagImportFailureData.KEY_ERROR_CODE))
    }

    private fun document() = DocumentEntity(
        id = "doc-1",
        knowledgeBaseId = "kb-1",
        displayName = "合同.txt",
        sourceUri = "content://provider/private",
        privateFileName = "doc-1.src.enc",
        mimeType = "text/plain",
        detectedType = "TXT",
        sha256 = "b".repeat(64),
        sizeBytes = 20,
        status = DocumentStatus.FAILED,
        createdAt = 1,
        updatedAt = 1,
        lastErrorDetail = "/data/user/0/private",
    )
}
