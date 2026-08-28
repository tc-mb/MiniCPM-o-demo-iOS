package com.example.minicpm_v_demo.rag.retrieval

data class VectorCandidate(val chunkId: Long, val vector: FloatArray)
data class RankedChunkId(val chunkId: Long, val score: Float)

object ExactVectorRanker {
    fun rank(query: FloatArray, candidates: List<VectorCandidate>, limit: Int): List<RankedChunkId> {
        require(query.isNotEmpty() && limit > 0)
        return candidates.asSequence()
            .filter { it.vector.size == query.size }
            .map { candidate ->
                RankedChunkId(candidate.chunkId, query.indices.sumOf {
                    (query[it] * candidate.vector[it]).toDouble()
                }.toFloat())
            }
            .sortedWith(compareByDescending<RankedChunkId> { it.score }.thenBy { it.chunkId })
            .take(limit)
            .toList()
    }
}
