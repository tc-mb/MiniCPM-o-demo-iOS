package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ExactVectorBufferTest {
    @Test
    fun `ranks contiguous vectors and breaks ties by chunk id`() {
        val buffer = ExactVectorBuffer.from(
            listOf(embedding(2, floatArrayOf(1f, 0f)), embedding(1, floatArrayOf(1f, 0f))),
        )

        assertEquals(listOf(1L, 2L), buffer.rank(floatArrayOf(1f, 0f), 2).map { it.chunkId })
    }

    @Test
    fun `cache invalidates when corpus stamp changes and skips oversized corpus`() {
        val cache = ExactVectorBufferCache(maximumCachedChunks = 2)
        val buffer = ExactVectorBuffer.from(listOf(embedding(1, floatArrayOf(1f))))
        val key = key(count = 1, updatedAt = 10)

        cache.put(key, buffer)
        assertSame(buffer, cache.get(key))
        assertNull(cache.get(key(count = 1, updatedAt = 11)))

        cache.put(key(count = 3, updatedAt = 12), buffer)
        assertNull(cache.get(key(count = 3, updatedAt = 12)))
    }

    @Test
    fun `partition merge preserves global top k with stable ties`() {
        val merged = PartitionedExactVectorRanker.merge(
            accumulated = listOf(
                com.example.minicpm_v_demo.rag.retrieval.RankedChunkId(4, 0.8f),
                com.example.minicpm_v_demo.rag.retrieval.RankedChunkId(2, 0.7f),
            ),
            partition = listOf(
                com.example.minicpm_v_demo.rag.retrieval.RankedChunkId(3, 0.9f),
                com.example.minicpm_v_demo.rag.retrieval.RankedChunkId(1, 0.8f),
            ),
            limit = 3,
        )

        assertEquals(listOf(3L, 1L, 4L), merged.map { it.chunkId })
    }

    private fun embedding(id: Long, vector: FloatArray) = ChunkEmbeddingEntity(
        chunkId = id,
        modelSha256 = "0".repeat(64),
        dimension = vector.size,
        vector = FloatVectorCodec.encode(vector),
        updatedAt = 1,
    )

    private fun key(count: Int, updatedAt: Long) = EmbeddingCorpusKey(
        knowledgeBaseIds = listOf("kb-1"),
        modelSha256 = "0".repeat(64),
        corpusVersion = 1,
        embeddingCount = count,
        maximumUpdatedAt = updatedAt,
        chunkIdSum = count.toLong(),
    )
}
