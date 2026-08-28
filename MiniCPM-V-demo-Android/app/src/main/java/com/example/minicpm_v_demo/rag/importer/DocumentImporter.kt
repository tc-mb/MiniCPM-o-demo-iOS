package com.example.minicpm_v_demo.rag.importer

import com.example.minicpm_v_demo.rag.config.RagLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class DocumentImportSource(
    val displayName: String,
    val declaredMimeType: String?,
    val declaredSizeBytes: Long?,
    val persistPermission: () -> Boolean,
    val open: () -> InputStream,
)

data class DocumentImportRequest(
    val documentId: String,
    val knowledgeBaseId: String,
    val source: DocumentImportSource,
)

data class ImportedDocument(
    val privateFileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val detectedType: DetectedFileType,
)

fun interface EncryptedDocumentWriter {
    fun write(plaintext: InputStream, target: File, shouldContinue: () -> Boolean)
}

enum class DocumentImportError {
    PERSIST_PERMISSION_DENIED,
    SOURCE_TOO_LARGE,
    CANCELLED,
    EMPTY_SOURCE,
    UNSUPPORTED_TYPE,
    DECLARATION_MISMATCH,
    DUPLICATE_CONTENT,
}

class DocumentImportException(val error: DocumentImportError) : Exception(error.name)

class DocumentImporter(
    private val stagingDirectory: File,
    private val encryptedDocumentWriter: EncryptedDocumentWriter,
    private val duplicateShaExists: (knowledgeBaseId: String, sha256: String) -> Boolean,
    private val maxSourceBytes: Long = RagLimits.MAX_SOURCE_BYTES,
) {
    fun copy(
        request: DocumentImportRequest,
        shouldContinue: () -> Boolean = { true },
    ): ImportedDocument {
        require(SAFE_DOCUMENT_ID.matches(request.documentId)) { "Invalid document ID" }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive" }
        request.source.declaredSizeBytes?.takeIf { it >= 0 }?.let { declaredSize ->
            if (declaredSize > maxSourceBytes) fail(DocumentImportError.SOURCE_TOO_LARGE)
        }
        val permissionGranted = runCatching(request.source.persistPermission).getOrDefault(false)
        if (!permissionGranted) fail(DocumentImportError.PERSIST_PERMISSION_DENIED)
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) {
            "Unable to create RAG staging directory"
        }

        val partFile = stagingDirectory.resolve("${request.documentId}.part")
        val privateFileName = "${request.documentId}.src.enc"
        val encryptedTarget = stagingDirectory.resolve(privateFileName)
        var completed = false
        try {
            val copied = copyAndDigest(request.source, partFile, shouldContinue)
            val detection = FileTypeDetector.detect(
                copied.header,
                request.source.declaredMimeType,
                request.source.displayName,
                sampleIsComplete = copied.sizeBytes == copied.header.size.toLong(),
            )
            when {
                detection.type == DetectedFileType.EMPTY -> fail(DocumentImportError.EMPTY_SOURCE)
                detection.type == DetectedFileType.UNSUPPORTED_BINARY -> fail(DocumentImportError.UNSUPPORTED_TYPE)
                detection.declarationMismatch -> fail(DocumentImportError.DECLARATION_MISMATCH)
                duplicateShaExists(request.knowledgeBaseId, copied.sha256) -> fail(DocumentImportError.DUPLICATE_CONTENT)
            }
            if (!shouldContinue()) fail(DocumentImportError.CANCELLED)
            partFile.inputStream().use { plaintext ->
                encryptedDocumentWriter.write(plaintext, encryptedTarget) {
                    if (!shouldContinue()) fail(DocumentImportError.CANCELLED)
                    true
                }
            }
            completed = true
            return ImportedDocument(privateFileName, copied.sha256, copied.sizeBytes, detection.type)
        } finally {
            partFile.delete()
            if (!completed) {
                encryptedTarget.delete()
                File(encryptedTarget.parentFile, encryptedTarget.name + ".new").delete()
            }
        }
    }

    private data class CopiedSource(val sha256: String, val sizeBytes: Long, val header: ByteArray)

    private fun copyAndDigest(
        source: DocumentImportSource,
        partFile: File,
        shouldContinue: () -> Boolean,
    ): CopiedSource {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArrayOutputStream(HEADER_BYTES)
        var total = 0L
        source.open().use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    if (!shouldContinue()) fail(DocumentImportError.CANCELLED)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maxSourceBytes) fail(DocumentImportError.SOURCE_TOO_LARGE)
                    digest.update(buffer, 0, count)
                    val headerBytes = minOf(count, HEADER_BYTES - header.size())
                    if (headerBytes > 0) header.write(buffer, 0, headerBytes)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
        return CopiedSource(digest.digest().toHex(), total, header.toByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun fail(error: DocumentImportError): Nothing = throw DocumentImportException(error)

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val HEADER_BYTES = 64 * 1024
        private val SAFE_DOCUMENT_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
