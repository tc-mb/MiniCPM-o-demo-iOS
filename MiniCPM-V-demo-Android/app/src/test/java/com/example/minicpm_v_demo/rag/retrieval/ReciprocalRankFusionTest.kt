package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class ReciprocalRankFusionTest {
    @Test
    fun `rewards candidates returned by both routes`() {
        val result = ReciprocalRankFusion.fuse(
            dense = listOf(
                DenseRankedHit(chunkId = 1, score = 0.91f),
                DenseRankedHit(chunkId = 2, score = 0.90f),
            ),
            lexical = listOf(
                LexicalRankedHit(chunkId = 2, score = 4.2),
                LexicalRankedHit(chunkId = 3, score = 4.0),
            ),
        )

        assertEquals(listOf(2L, 1L, 3L), result.map(FusedRankedHit::chunkId))
        assertEquals(1.0 / 62.0 + 1.0 / 61.0, result.first().rrfScore, 1e-12)
    }

    @Test
    fun `uses route score before dense tie breaker`() {
        val result = ReciprocalRankFusion.fuse(
            dense = listOf(
                DenseRankedHit(chunkId = 8, score = 0.8f),
                DenseRankedHit(chunkId = 7, score = 0.8f),
            ),
            lexical = listOf(
                LexicalRankedHit(chunkId = 4, score = 3.0),
                LexicalRankedHit(chunkId = 5, score = 2.0),
            ),
        )

        assertEquals(listOf(8L, 4L, 7L, 5L), result.map(FusedRankedHit::chunkId))
    }

    @Test
    fun `uses chunk id when fusion dense and lexical scores all tie`() {
        val result = ReciprocalRankFusion.fuse(
            dense = listOf(
                DenseRankedHit(chunkId = 8, score = 0.8f),
                DenseRankedHit(chunkId = 7, score = 0.8f),
            ),
            lexical = listOf(
                LexicalRankedHit(chunkId = 7, score = 3.0),
                LexicalRankedHit(chunkId = 8, score = 3.0),
            ),
        )

        assertEquals(listOf(7L, 8L), result.map(FusedRankedHit::chunkId))
    }

    @Test
    fun `deduplicates route input and enforces output limit`() {
        val result = ReciprocalRankFusion.fuse(
            dense = listOf(
                DenseRankedHit(chunkId = 1, score = 0.9f),
                DenseRankedHit(chunkId = 1, score = 0.1f),
                DenseRankedHit(chunkId = 2, score = 0.8f),
            ),
            lexical = emptyList(),
            limit = 1,
        )

        assertEquals(listOf(1L), result.map(FusedRankedHit::chunkId))
        assertEquals(0.9f, result.single().denseScore)
    }
}
