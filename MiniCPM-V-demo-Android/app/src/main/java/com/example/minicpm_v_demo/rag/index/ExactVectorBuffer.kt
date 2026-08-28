package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.retrieval.RankedChunkId
import java.security.MessageDigest

data class EmbeddingCorpusKey(
    val knowledgeBaseIds: List<String>,
    val modelSha256: String,
    val corpusVersion: Int,
    val embeddingCount: Int,
    val maximumUpdatedAt: Long,
    val chunkIdSum: Long,
) {
    init {
        require(knowledgeBaseIds.isNotEmpty() && knowledgeBaseIds == knowledgeBaseIds.sorted())
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(corpusVersion > 0 && embeddingCount >= 0 && maximumUpdatedAt >= 0 && chunkIdSum >= 0)
    }
}

fun EmbeddingCorpusKey.stableDigest(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(bytes: ByteArray) {
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    knowledgeBaseIds.forEach { update(it.toByteArray(Charsets.UTF_8)) }
    update(modelSha256.toByteArray(Charsets.US_ASCII))
    update(corpusVersion.toString().toByteArray(Charsets.US_ASCII))
    update(embeddingCount.toString().toByteArray(Charsets.US_ASCII))
    update(maximumUpdatedAt.toString().toByteArray(Charsets.US_ASCII))
    update(chunkIdSum.toString().toByteArray(Charsets.US_ASCII))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

class ExactVectorBuffer private constructor(
    private val chunkIds: LongArray,
    private val values: FloatArray,
    val dimension: Int,
) {
    val size: Int get() = chunkIds.size

    fun rank(query: FloatArray, limit: Int): List<RankedChunkId> {
        require(query.size == dimension && limit > 0)
        return chunkIds.indices.asSequence()
            .map { row ->
                val offset = row * dimension
                var score = 0.0f
                for (column in 0 until dimension) {
                    score += query[column] * values[offset + column]
                }
                RankedChunkId(chunkIds[row], score)
            }
            .sortedWith(compareByDescending<RankedChunkId> { it.score }.thenBy { it.chunkId })
            .take(limit)
            .toList()
    }

    companion object {
        fun from(embeddings: List<ChunkEmbeddingEntity>): ExactVectorBuffer {
            require(embeddings.isNotEmpty())
            val dimension = embeddings.first().dimension
            require(dimension > 0 && embeddings.all { it.dimension == dimension })
            val ids = LongArray(embeddings.size)
            val values = FloatArray(Math.multiplyExact(embeddings.size, dimension))
            embeddings.forEachIndexed { index, embedding ->
                ids[index] = embedding.chunkId
                val decoded = FloatVectorCodec.decode(embedding.vector, dimension)
                decoded.copyInto(values, destinationOffset = index * dimension)
            }
            return ExactVectorBuffer(ids, values, dimension)
        }
    }
}

class ExactVectorBufferCache(
    private val maximumCachedChunks: Int = 5_000,
) {
    init {
        require(maximumCachedChunks > 0)
    }

    private var cachedKey: EmbeddingCorpusKey? = null
    private var cachedBuffer: ExactVectorBuffer? = null

    @Synchronized
    fun get(key: EmbeddingCorpusKey): ExactVectorBuffer? =
        cachedBuffer?.takeIf { cachedKey == key }

    @Synchronized
    fun put(key: EmbeddingCorpusKey, buffer: ExactVectorBuffer): ExactVectorBuffer {
        if (key.embeddingCount <= maximumCachedChunks && buffer.size == key.embeddingCount) {
            cachedKey = key
            cachedBuffer = buffer
        } else {
            cachedKey = null
            cachedBuffer = null
        }
        return buffer
    }
}

object PartitionedExactVectorRanker {
    fun merge(
        accumulated: List<RankedChunkId>,
        partition: List<RankedChunkId>,
        limit: Int,
    ): List<RankedChunkId> {
        require(limit > 0)
        return (accumulated.asSequence() + partition.asSequence())
            .sortedWith(compareByDescending<RankedChunkId> { it.score }.thenBy { it.chunkId })
            .take(limit)
            .toList()
    }
}
