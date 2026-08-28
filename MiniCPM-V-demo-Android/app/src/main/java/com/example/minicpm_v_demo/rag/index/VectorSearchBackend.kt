package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.retrieval.RankedChunkId

data class VectorSearchRequest(
    val corpusKey: EmbeddingCorpusKey,
    val query: FloatArray,
    val limit: Int,
) {
    init {
        require(query.isNotEmpty() && query.all(Float::isFinite))
        require(limit > 0)
    }
}

interface VectorEmbeddingSource {
    suspend fun loadAll(): List<ChunkEmbeddingEntity>

    suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity>
}

interface VectorSearchBackend {
    suspend fun search(
        request: VectorSearchRequest,
        source: VectorEmbeddingSource,
    ): List<RankedChunkId>
}

class ExactVectorSearchBackend(
    maximumCachedChunks: Int = 5_000,
    private val partitionChunks: Int = 1_000,
    private val bufferCache: ExactVectorBufferCache = ExactVectorBufferCache(maximumCachedChunks),
) : VectorSearchBackend {
    private val maximumCachedChunks = maximumCachedChunks.also { require(it > 0) }

    init {
        require(partitionChunks > 0)
    }

    override suspend fun search(
        request: VectorSearchRequest,
        source: VectorEmbeddingSource,
    ): List<RankedChunkId> {
        if (request.corpusKey.embeddingCount == 0) return emptyList()
        if (request.corpusKey.embeddingCount <= maximumCachedChunks) {
            val buffer = bufferCache.get(request.corpusKey) ?: ExactVectorBuffer.from(
                source.loadAll(),
            ).also { bufferCache.put(request.corpusKey, it) }
            return buffer.rank(request.query, request.limit)
        }

        var offset = 0
        var ranked = emptyList<RankedChunkId>()
        while (true) {
            val page = source.loadPage(offset, partitionChunks)
            if (page.isEmpty()) break
            ranked = PartitionedExactVectorRanker.merge(
                accumulated = ranked,
                partition = ExactVectorBuffer.from(page).rank(request.query, request.limit),
                limit = request.limit,
            )
            offset += page.size
            if (page.size < partitionChunks) break
        }
        return ranked
    }
}
