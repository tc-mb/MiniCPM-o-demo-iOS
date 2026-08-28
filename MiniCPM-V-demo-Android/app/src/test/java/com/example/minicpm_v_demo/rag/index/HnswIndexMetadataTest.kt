package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HnswIndexMetadataTest {
    @Test
    fun `metadata round trip preserves the complete corpus generation`() {
        val metadata = metadata()

        val restored = HnswIndexMetadataCodec.decode(
            ByteArrayInputStream(HnswIndexMetadataCodec.encode(metadata)),
        )

        assertEquals(metadata, restored)
        assertTrue(restored.matches(metadata.corpusKey))
    }

    @Test
    fun `metadata rejects truncation trailing bytes and non canonical digests`() {
        val encoded = HnswIndexMetadataCodec.encode(metadata())

        assertThrows(IOException::class.java) {
            HnswIndexMetadataCodec.decode(ByteArrayInputStream(encoded.copyOf(encoded.size - 1)))
        }
        assertThrows(IOException::class.java) {
            HnswIndexMetadataCodec.decode(ByteArrayInputStream(encoded + 0x01))
        }
        val invalidUtf8 = encoded.copyOf()
        val knowledgeBaseOffset = invalidUtf8.indexOfSubsequence("kb-1".toByteArray())
        invalidUtf8[knowledgeBaseOffset] = 0xc3.toByte()
        invalidUtf8[knowledgeBaseOffset + 1] = 0x28
        assertThrows(IOException::class.java) {
            HnswIndexMetadataCodec.decode(ByteArrayInputStream(invalidUtf8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata().copy(plaintextSha256 = "A".repeat(64))
        }
    }

    @Test
    fun `corpus mismatch fails admission before opening an index`() {
        val expected = key(updatedAt = 11)
        val admission = HnswIndexAdmissionPolicy.assess(
            expectedCorpus = expected,
            metadata = metadata(corpusKey = key(updatedAt = 10)),
            appMemoryBudgetBytes = 512L * 1024L * 1024L,
        )

        assertEquals(HnswIndexRejection.CORPUS_MISMATCH, admission.rejection)
        assertFalse(admission.allowed)
    }

    @Test
    fun `managed paths hash untrusted ids and reject traversal`() {
        val root = Files.createTempDirectory("hnsw-paths-").toFile()
        try {
            val policy = HnswIndexPathPolicy(root)
            val hostileKey = key(knowledgeBaseIds = listOf("../outside", "normal"))

            val paths = policy.pathsFor(hostileKey)

            assertEquals(root.canonicalFile, paths.encryptedIndex.parentFile)
            assertEquals(root.canonicalFile, paths.metadata.parentFile)
            assertTrue(paths.encryptedIndex.name.matches(Regex("[0-9a-f]{64}\\.hnsw\\.enc")))
            assertTrue(paths.metadata.name.matches(Regex("[0-9a-f]{64}\\.hnsw\\.meta")))
            assertThrows(IllegalArgumentException::class.java) {
                policy.requireManaged(File(root, "../escape.hnsw.enc"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `plaintext length and sha must match before native load`() {
        val root = Files.createTempDirectory("hnsw-plaintext-").toFile()
        val plaintext = File(root, "candidate.hnsw").apply { writeBytes("index bytes".toByteArray()) }
        try {
            val valid = metadata(
                plaintextLength = plaintext.length(),
                plaintextSha256 = HnswIndexIntegrity.sha256(plaintext),
            )

            assertTrue(HnswIndexIntegrity.verify(plaintext, valid))
            assertFalse(HnswIndexIntegrity.verify(plaintext, valid.copy(plaintextLength = plaintext.length() + 1)))
            plaintext.appendText("tampered")
            assertFalse(HnswIndexIntegrity.verify(plaintext, valid))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rss admission is bounded to ten percent of app memory`() {
        val metadata = metadata(corpusKey = key(count = 20_000))
        val estimate = HnswIndexRssPolicy.estimateBytes(metadata)

        assertTrue(
            HnswIndexAdmissionPolicy.assess(
                expectedCorpus = metadata.corpusKey,
                metadata = metadata,
                appMemoryBudgetBytes = Math.multiplyExact(estimate, 10L),
            ).allowed,
        )
        assertEquals(
            HnswIndexRejection.RSS_BUDGET_EXCEEDED,
            HnswIndexAdmissionPolicy.assess(
                expectedCorpus = metadata.corpusKey,
                metadata = metadata,
                appMemoryBudgetBytes = Math.multiplyExact(estimate, 10L) - 1L,
            ).rejection,
        )
    }

    private fun metadata(
        corpusKey: EmbeddingCorpusKey = key(),
        plaintextLength: Long = 4096,
        plaintextSha256: String = "1".repeat(64),
    ) = HnswIndexMetadata(
        corpusKey = corpusKey,
        dimension = E5ModelSpec.PINNED.dimension,
        indexGeneration = 7,
        maximumChunkId = 99,
        plaintextLength = plaintextLength,
        plaintextSha256 = plaintextSha256,
        builtAt = 1234,
    )

    private fun key(
        knowledgeBaseIds: List<String> = listOf("kb-1"),
        count: Int = 6_000,
        updatedAt: Long = 10,
    ) = EmbeddingCorpusKey(
        knowledgeBaseIds = knowledgeBaseIds.sorted(),
        modelSha256 = "0".repeat(64),
        corpusVersion = 1,
        embeddingCount = count,
        maximumUpdatedAt = updatedAt,
        chunkIdSum = count.toLong() * (count + 1L) / 2L,
    )

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        val index = indices.firstOrNull { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
        return requireNotNull(index) { "Test fixture marker is missing" }
    }
}
