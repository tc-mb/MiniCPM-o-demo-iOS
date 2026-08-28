package com.example.minicpm_v_demo.rag.crypto

import java.io.File
import java.nio.file.Files

object RagTempFileCleaner {
    const val DEFAULT_STALE_AFTER_MS = 24L * 60 * 60 * 1_000

    fun cleanupHnswPlaintext(
        indexDirectory: File,
        createdBeforeOrAtMs: Long,
    ): Boolean {
        require(createdBeforeOrAtMs >= 0) { "createdBeforeOrAtMs must be non-negative" }
        if (!indexDirectory.isDirectory || Files.isSymbolicLink(indexDirectory.toPath())) return false
        val canonicalDirectory = runCatching { indexDirectory.canonicalFile }.getOrElse { return false }
        var deletedAny = false
        indexDirectory.listFiles().orEmpty().forEach { candidate ->
            val isManagedPlaintext = runCatching {
                candidate.isFile &&
                    !Files.isSymbolicLink(candidate.toPath()) &&
                    candidate.canonicalFile.parentFile == canonicalDirectory &&
                    HNSW_PLAINTEXT_NAME.matches(candidate.name) &&
                    candidate.lastModified() <= createdBeforeOrAtMs
            }.getOrDefault(false)
            if (isManagedPlaintext && candidate.delete()) deletedAny = true
        }
        return deletedAny
    }

    /** Returns true when at least one stale plaintext staging file was removed. */
    fun cleanup(
        stagingDirectory: File,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    ): Boolean {
        require(staleAfterMs >= 0) { "staleAfterMs must be non-negative" }
        if (!stagingDirectory.isDirectory || Files.isSymbolicLink(stagingDirectory.toPath())) return false
        val oldestAllowedModifiedAt = nowMs - staleAfterMs
        var deletedAny = false
        stagingDirectory.listFiles().orEmpty().forEach { candidate ->
            val isPlaintextPart = candidate.isFile &&
                !Files.isSymbolicLink(candidate.toPath()) &&
                candidate.name.endsWith(PART_SUFFIX) &&
                candidate.lastModified() <= oldestAllowedModifiedAt
            if (isPlaintextPart && candidate.delete()) deletedAny = true
        }
        return deletedAny
    }

    fun stagingDirectory(noBackupFilesDirectory: File): File =
        noBackupFilesDirectory.resolve("rag").resolve("source")

    fun parsedBlockFile(stagingDirectory: File, documentId: String): File {
        require(SAFE_DOCUMENT_ID.matches(documentId)) { "Invalid document ID" }
        return stagingDirectory.resolve("$documentId.blocks.enc")
    }

    private const val PART_SUFFIX = ".part"
    private val SAFE_DOCUMENT_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val HNSW_PLAINTEXT_NAME = Regex(
        "(?:hnsw-build-[A-Za-z0-9_-]+\\.hnsw|hnsw-[A-Za-z0-9_-]+\\.plain)",
    )
}
