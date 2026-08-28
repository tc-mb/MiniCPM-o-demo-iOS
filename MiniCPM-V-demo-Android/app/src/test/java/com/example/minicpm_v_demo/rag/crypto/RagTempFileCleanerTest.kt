package com.example.minicpm_v_demo.rag.crypto

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagTempFileCleanerTest {
    @Test
    fun `HNSW cleanup removes only plaintext candidates left by an earlier process`() {
        val indexDirectory = Files.createTempDirectory("rag-hnsw-cleanup-test").toFile()
        try {
            val processStartedAt = 10_000L
            val staleBuild = indexDirectory.resolve("hnsw-build-123.hnsw").apply {
                writeText("plaintext build")
                setLastModified(1_000L)
            }
            val staleDecryption = indexDirectory.resolve("hnsw-456.plain").apply {
                writeText("plaintext read")
                setLastModified(1_000L)
            }
            val currentProcessBuild = indexDirectory.resolve("hnsw-build-789.hnsw").apply {
                writeText("active build")
                setLastModified(11_000L)
            }
            val encryptedIndex = indexDirectory.resolve("corpus.hnsw.enc").apply {
                writeText("encrypted")
                setLastModified(1_000L)
            }
            val previousGeneration = indexDirectory.resolve("corpus.hnsw.enc.previous").apply {
                writeText("encrypted previous")
                setLastModified(1_000L)
            }
            val unrelated = indexDirectory.resolve("notes.hnsw").apply {
                writeText("unrelated")
                setLastModified(1_000L)
            }

            val deleted = RagTempFileCleaner.cleanupHnswPlaintext(
                indexDirectory,
                createdBeforeOrAtMs = processStartedAt,
            )

            assertTrue(deleted)
            assertFalse(staleBuild.exists())
            assertFalse(staleDecryption.exists())
            assertTrue(currentProcessBuild.exists())
            assertTrue(encryptedIndex.exists())
            assertTrue(previousGeneration.exists())
            assertTrue(unrelated.exists())
        } finally {
            indexDirectory.deleteRecursively()
        }
    }

    @Test
    fun `cleanup removes only stale part files inside staging directory`() {
        val staging = Files.createTempDirectory("rag-staging-test").toFile()
        try {
            val now = 10_000L
            val stalePart = staging.resolve("old.part").apply {
                writeText("plaintext")
                setLastModified(1_000L)
            }
            val freshPart = staging.resolve("active.part").apply {
                writeText("active")
                setLastModified(9_500L)
            }
            val encrypted = staging.resolve("document.src.enc").apply {
                writeText("encrypted")
                setLastModified(1_000L)
            }
            val unrelated = staging.resolve("notes.txt").apply {
                writeText("keep")
                setLastModified(1_000L)
            }

            val deleted = RagTempFileCleaner.cleanup(staging, now, staleAfterMs = 1_000L)

            assertTrue(deleted)
            assertFalse(stalePart.exists())
            assertTrue(freshPart.exists())
            assertTrue(encrypted.exists())
            assertTrue(unrelated.exists())
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `cleanup does not follow symbolic links`() {
        val staging = Files.createTempDirectory("rag-staging-link-test").toFile()
        val outside = Files.createTempFile("rag-outside", ".part")
        try {
            val link = staging.toPath().resolve("linked.part")
            runCatching { Files.createSymbolicLink(link, outside) }
                .getOrElse { return }
            outside.toFile().setLastModified(1_000L)

            RagTempFileCleaner.cleanup(staging, nowMs = 10_000L, staleAfterMs = 1_000L)

            assertTrue(Files.exists(outside))
        } finally {
            staging.deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }
}
