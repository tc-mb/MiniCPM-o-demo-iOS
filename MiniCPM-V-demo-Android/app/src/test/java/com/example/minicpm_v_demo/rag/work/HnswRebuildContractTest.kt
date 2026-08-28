package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.index.EmbeddingCorpusKey
import com.example.minicpm_v_demo.rag.index.HnswFallbackReason
import com.example.minicpm_v_demo.rag.index.HnswRebuildPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HnswRebuildContractTest {
    @Test
    fun `unique work name is stable for one exact corpus generation`() {
        val key = key(listOf("kb-a", "kb-b"), updatedAt = 10)

        assertEquals(
            HnswRebuildContract.uniqueWorkName(key),
            HnswRebuildContract.uniqueWorkName(key.copy()),
        )
        assertNotEquals(
            HnswRebuildContract.uniqueWorkName(key),
            HnswRebuildContract.uniqueWorkName(key.copy(maximumUpdatedAt = 11)),
        )
    }

    @Test
    fun `worker input preserves sorted knowledge bases and embedding contract`() {
        val key = key(listOf("kb-a", "kb-b"), updatedAt = 10)

        val input = HnswRebuildContract.inputValues(key)

        assertEquals(listOf("kb-a", "kb-b"), input.knowledgeBaseIds)
        assertEquals(key.modelSha256, input.modelSha256)
        assertEquals(key.corpusVersion, input.corpusVersion)
    }

    @Test
    fun `worker input rejects unsorted duplicates and oversized selections`() {
        assertThrows(IllegalArgumentException::class.java) {
            HnswRebuildInput(listOf("kb-b", "kb-a"), "0".repeat(64), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnswRebuildInput(listOf("kb-a", "kb-a"), "0".repeat(64), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnswRebuildInput((1..65).map { "kb-$it" }.sorted(), "0".repeat(64), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnswRebuildInput(listOf("k".repeat(257)), "0".repeat(64), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnswRebuildInput((1..17).map { it.toString().padStart(3, '0') + "k".repeat(253) }, "0".repeat(64), 1)
        }
    }

    @Test
    fun `rebuild waits until the current answer has left the latency critical path`() {
        assertTrue(HnswRebuildContract.INITIAL_DELAY_SECONDS >= 30)
    }

    @Test
    fun `only recoverable sidecar failures schedule a rebuild`() {
        assertTrue(HnswRebuildPolicy.shouldSchedule(HnswFallbackReason.MISSING_OR_CORRUPT))
        assertTrue(HnswRebuildPolicy.shouldSchedule(HnswFallbackReason.CORPUS_MISMATCH))
        assertFalse(HnswRebuildPolicy.shouldSchedule(HnswFallbackReason.RSS_BUDGET_EXCEEDED))
        assertFalse(HnswRebuildPolicy.shouldSchedule(HnswFallbackReason.BELOW_THRESHOLD))
    }

    private fun key(ids: List<String>, updatedAt: Long) = EmbeddingCorpusKey(
        knowledgeBaseIds = ids,
        modelSha256 = "0".repeat(64),
        corpusVersion = 1,
        embeddingCount = 6_000,
        maximumUpdatedAt = updatedAt,
        chunkIdSum = 18_003_000,
    )
}
