package com.example.minicpm_v_demo.rag.storage

import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RagDocumentArtifactCleanerTest {
    @Test
    fun `delete removes only the expected encrypted source and parsed blocks`() {
        val staging = Files.createTempDirectory("rag-document-delete").toFile()
        try {
            val document = document("doc-safe")
            val source = staging.resolve(document.privateFileName).apply { writeText("encrypted source") }
            val blocks = staging.resolve("${document.id}.blocks.enc").apply { writeText("encrypted blocks") }
            val unrelated = staging.resolve("other.src.enc").apply { writeText("keep") }

            RagDocumentArtifactCleaner.delete(staging, document)

            assertFalse(source.exists())
            assertFalse(blocks.exists())
            assertTrue(unrelated.exists())
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `delete rejects a private name that can escape staging`() {
        val root = Files.createTempDirectory("rag-document-boundary").toFile()
        val staging = root.resolve("staging").apply { mkdirs() }
        val outside = root.resolve("outside.src.enc").apply { writeText("keep") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RagDocumentArtifactCleaner.delete(
                    staging,
                    document("doc-safe").copy(privateFileName = "../outside.src.enc"),
                )
            }
            assertTrue(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete rejects an unsafe document id`() {
        val staging = Files.createTempDirectory("rag-document-id-boundary").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RagDocumentArtifactCleaner.delete(staging, document("../escape"))
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun document(id: String) = DocumentEntity(
        id = id,
        knowledgeBaseId = "kb-1",
        displayName = "policy.txt",
        sourceUri = "content://provider/policy.txt",
        privateFileName = "$id.src.enc",
        mimeType = "text/plain",
        detectedType = "TXT",
        sha256 = "a".repeat(64),
        sizeBytes = 10,
        status = DocumentStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )
}
