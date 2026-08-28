package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactVectorRankerTest {
    @Test
    fun `ranks normalized vectors by cosine and applies limit`() {
        val ranked = ExactVectorRanker.rank(
            floatArrayOf(1f, 0f),
            listOf(
                VectorCandidate(1, floatArrayOf(0f, 1f)),
                VectorCandidate(2, floatArrayOf(0.8f, 0.6f)),
                VectorCandidate(3, floatArrayOf(1f, 0f)),
            ),
            limit = 2,
        )

        assertEquals(listOf(3L, 2L), ranked.map { it.chunkId })
    }
}
