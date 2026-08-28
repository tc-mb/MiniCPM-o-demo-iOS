package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorSearchBackendTest {
    @Test
    fun `small corpus loads once and reuses contiguous exact cache`() = runBlocking {
        val embeddings = listOf(
            embedding(2, floatArrayOf(1f, 0f)),
            embedding(1, floatArrayOf(1f, 0f)),
        )
        val source = RecordingSource(embeddings)
        val backend = ExactVectorSearchBackend(
            maximumCachedChunks = 5,
            partitionChunks = 2,
        )
        val request = request(count = embeddings.size, query = floatArrayOf(1f, 0f), limit = 2)

        val first = backend.search(request, source)
        val second = backend.search(request, source)

        assertEquals(listOf(1L, 2L), first.map { it.chunkId })
        assertEquals(first, second)
        assertEquals(1, source.loadAllCalls)
        assertEquals(emptyList<Int>(), source.pageOffsets)
    }

    @Test
    fun `oversized corpus pages without loading all and matches exact oracle`() = runBlocking {
        val embeddings = listOf(
            embedding(1, floatArrayOf(0f, 1f)),
            embedding(2, floatArrayOf(0.8f, 0.6f)),
            embedding(3, floatArrayOf(1f, 0f)),
            embedding(4, floatArrayOf(0.9f, 0.1f)),
            embedding(5, floatArrayOf(-1f, 0f)),
            embedding(6, floatArrayOf(0.8f, 0.6f)),
        )
        val source = RecordingSource(embeddings, failOnLoadAll = true)
        val backend = ExactVectorSearchBackend(
            maximumCachedChunks = 2,
            partitionChunks = 2,
        )
        val query = floatArrayOf(1f, 0f)

        val ranked = backend.search(request(embeddings.size, query, 4), source)
        val oracle = ExactVectorBuffer.from(embeddings).rank(query, 4)

        assertEquals(oracle, ranked)
        assertEquals(0, source.loadAllCalls)
        assertEquals(listOf(0, 2, 4, 6), source.pageOffsets)
    }

    private class RecordingSource(
        private val embeddings: List<ChunkEmbeddingEntity>,
        private val failOnLoadAll: Boolean = false,
    ) : VectorEmbeddingSource {
        var loadAllCalls = 0
        val pageOffsets = mutableListOf<Int>()

        override suspend fun loadAll(): List<ChunkEmbeddingEntity> {
            loadAllCalls += 1
            check(!failOnLoadAll) { "Oversized search must not load every vector" }
            return embeddings
        }

        override suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity> {
            pageOffsets += offset
            return embeddings.drop(offset).take(pageSize)
        }
    }

    private fun request(count: Int, query: FloatArray, limit: Int) = VectorSearchRequest(
        corpusKey = EmbeddingCorpusKey(
            knowledgeBaseIds = listOf("kb-1"),
            modelSha256 = "0".repeat(64),
            corpusVersion = 1,
            embeddingCount = count,
            maximumUpdatedAt = 10,
            chunkIdSum = (1L..count.toLong()).sum(),
        ),
        query = query,
        limit = limit,
    )

    private fun embedding(id: Long, vector: FloatArray) = ChunkEmbeddingEntity(
        chunkId = id,
        modelSha256 = "0".repeat(64),
        dimension = vector.size,
        vector = FloatVectorCodec.encode(vector),
        updatedAt = 10,
    )
}
