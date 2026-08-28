package com.example.minicpm_v_demo.rag.retrieval

data class DenseRankedHit(val chunkId: Long, val score: Float)
data class LexicalRankedHit(val chunkId: Long, val score: Double)

data class FusedRankedHit(
    val chunkId: Long,
    val rrfScore: Double,
    val denseScore: Float?,
    val lexicalScore: Double?,
)

object ReciprocalRankFusion {
    fun fuse(
        dense: List<DenseRankedHit>,
        lexical: List<LexicalRankedHit>,
        limit: Int = 12,
        rankConstant: Int = 60,
    ): List<FusedRankedHit> {
        require(limit in 1..100 && rankConstant > 0)
        val accumulators = LinkedHashMap<Long, Accumulator>()
        dense.asSequence()
            .filter { it.chunkId > 0 && it.score.isFinite() }
            .distinctBy(DenseRankedHit::chunkId)
            .forEachIndexed { index, hit ->
                val accumulator = accumulators.getOrPut(hit.chunkId, ::Accumulator)
                accumulator.rrfScore += reciprocalRank(rankConstant, index)
                accumulator.denseScore = hit.score
            }
        lexical.asSequence()
            .filter { it.chunkId > 0 && it.score.isFinite() }
            .distinctBy(LexicalRankedHit::chunkId)
            .forEachIndexed { index, hit ->
                val accumulator = accumulators.getOrPut(hit.chunkId, ::Accumulator)
                accumulator.rrfScore += reciprocalRank(rankConstant, index)
                accumulator.lexicalScore = hit.score
            }
        return accumulators.map { (chunkId, accumulator) ->
            FusedRankedHit(
                chunkId = chunkId,
                rrfScore = accumulator.rrfScore,
                denseScore = accumulator.denseScore,
                lexicalScore = accumulator.lexicalScore,
            )
        }.sortedWith(
            compareByDescending<FusedRankedHit>(FusedRankedHit::rrfScore)
                .thenByDescending { it.denseScore ?: Float.NEGATIVE_INFINITY }
                .thenByDescending { it.lexicalScore ?: Double.NEGATIVE_INFINITY }
                .thenBy(FusedRankedHit::chunkId),
        ).take(limit)
    }

    private fun reciprocalRank(rankConstant: Int, zeroBasedIndex: Int): Double =
        1.0 / (rankConstant + zeroBasedIndex + 1).toDouble()

    private class Accumulator(
        var rrfScore: Double = 0.0,
        var denseScore: Float? = null,
        var lexicalScore: Double? = null,
    )
}
