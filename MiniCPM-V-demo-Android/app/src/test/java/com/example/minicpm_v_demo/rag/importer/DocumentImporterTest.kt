package com.example.minicpm_v_demo.rag.importer

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImporterTest {
    @Test
    fun `rejects declared oversize before opening source`() = withImporter(maxBytes = 4) { importer, staging ->
        var opened = false
        val source = source("large.txt", "text/plain", 5) {
            opened = true
            ByteArrayInputStream("large".toByteArray())
        }

        val error = assertThrows(DocumentImportException::class.java) {
            importer.copy(request("doc-1", source))
        }

        assertEquals(DocumentImportError.SOURCE_TOO_LARGE, error.error)
        assertFalse(opened)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `permission failure and cancellation leave no part file`() = withImporter { importer, staging ->
        val denied = source("denied.txt", "text/plain", 1, persistPermission = { false }) {
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertEquals(
            DocumentImportError.PERSIST_PERMISSION_DENIED,
            assertThrows(DocumentImportException::class.java) { importer.copy(request("denied", denied)) }.error,
        )

        var checks = 0
        val cancellable = source("cancel.txt", "text/plain", null) {
            ByteArrayInputStream(ByteArray(128 * 1024) { 'a'.code.toByte() })
        }
        assertEquals(
            DocumentImportError.CANCELLED,
            assertThrows(DocumentImportException::class.java) {
                importer.copy(request("cancelled", cancellable)) { checks++ == 0 }
            }.error,
        )
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `duplicate hash and misleading declaration are rejected`() = withImporter(
        duplicateSha = { _, _ -> true },
    ) { duplicateImporter, staging ->
        val duplicate = source("copy.txt", "text/plain", null) {
            ByteArrayInputStream("same content".toByteArray())
        }
        assertEquals(
            DocumentImportError.DUPLICATE_CONTENT,
            assertThrows(DocumentImportException::class.java) {
                duplicateImporter.copy(request("duplicate", duplicate))
            }.error,
        )
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `magic bytes reject a fake extension`() = withImporter { importer, staging ->
        val fakePdf = source("invoice.pdf", "application/pdf", null) {
            ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        }

        assertEquals(
            DocumentImportError.DECLARATION_MISMATCH,
            assertThrows(DocumentImportException::class.java) {
                importer.copy(request("fake", fakePdf))
            }.error,
        )
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `same display name from different sources gets unique private files`() = withImporter { importer, staging ->
        val first = importer.copy(request("doc-a", source("report.txt", "text/plain", null) {
            ByteArrayInputStream("first report".toByteArray())
        }))
        val second = importer.copy(request("doc-b", source("report.txt", "text/plain", null) {
            ByteArrayInputStream("second report".toByteArray())
        }))

        assertNotEquals(first.privateFileName, second.privateFileName)
        assertTrue(staging.resolve(first.privateFileName).isFile)
        assertTrue(staging.resolve(second.privateFileName).isFile)
        assertFalse(staging.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun `cancellation remains active while encrypted output is written`() {
        val staging = Files.createTempDirectory("document-importer-encryption-cancel").toFile()
        try {
            val importer = DocumentImporter(
                stagingDirectory = staging,
                encryptedDocumentWriter = EncryptedDocumentWriter { plaintext, target, shouldContinue ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(4)
                        while (true) {
                            if (!shouldContinue()) throw DocumentImportException(DocumentImportError.CANCELLED)
                            val count = plaintext.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                },
                duplicateShaExists = { _, _ -> false },
            )
            var checks = 0
            val error = assertThrows(DocumentImportException::class.java) {
                importer.copy(
                    request("encrypt-cancel", source("cancel.txt", "text/plain", null) {
                        ByteArrayInputStream("content that reaches encryption".toByteArray())
                    }),
                ) { checks++ < 3 }
            }

            assertEquals(DocumentImportError.CANCELLED, error.error)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun request(id: String, source: DocumentImportSource) = DocumentImportRequest(
        documentId = id,
        knowledgeBaseId = "kb-1",
        source = source,
    )

    private fun source(
        displayName: String,
        mimeType: String,
        declaredSize: Long?,
        persistPermission: () -> Boolean = { true },
        open: () -> ByteArrayInputStream,
    ) = DocumentImportSource(displayName, mimeType, declaredSize, persistPermission, open)

    private fun withImporter(
        maxBytes: Long = 1024 * 1024,
        duplicateSha: (String, String) -> Boolean = { _, _ -> false },
        block: (DocumentImporter, java.io.File) -> Unit,
    ) {
        val staging = Files.createTempDirectory("document-importer-test").toFile()
        try {
            block(
                DocumentImporter(
                    stagingDirectory = staging,
                    encryptedDocumentWriter = EncryptedDocumentWriter { plaintext, target, _ ->
                        target.outputStream().use { output -> plaintext.copyTo(output) }
                    },
                    duplicateShaExists = duplicateSha,
                    maxSourceBytes = maxBytes,
                ),
                staging,
            )
        } finally {
            staging.deleteRecursively()
        }
    }
}
