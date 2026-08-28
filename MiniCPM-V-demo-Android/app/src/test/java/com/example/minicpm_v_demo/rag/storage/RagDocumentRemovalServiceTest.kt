package com.example.minicpm_v_demo.rag.storage

import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RagDocumentRemovalServiceTest {
    @Test
    fun `remove deletes artifacts before deleting the document record`() = runBlocking {
        val staging = Files.createTempDirectory("rag-document-removal").toFile()
        try {
            val document = document()
            val source = staging.resolve(document.privateFileName).apply { writeText("source") }
            val blocks = staging.resolve("${document.id}.blocks.enc").apply { writeText("blocks") }
            var deletedId: String? = null
            val service = RagDocumentRemovalService(staging) { id ->
                assertFalse(source.exists())
                assertFalse(blocks.exists())
                deletedId = id
                1
            }

            service.remove(document)

            assertEquals(document.id, deletedId)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `remove fails closed when the database record was not deleted`() = runBlocking {
        val staging = Files.createTempDirectory("rag-document-removal-missing").toFile()
        try {
            RagDocumentRemovalService(staging) { 0 }.remove(document())
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun document() = DocumentEntity(
        id = "doc-1",
        knowledgeBaseId = "kb-1",
        displayName = "policy.txt",
        sourceUri = null,
        privateFileName = "doc-1.src.enc",
        mimeType = "text/plain",
        detectedType = "TXT",
        sha256 = "d".repeat(64),
        sizeBytes = 10,
        status = DocumentStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )
}
