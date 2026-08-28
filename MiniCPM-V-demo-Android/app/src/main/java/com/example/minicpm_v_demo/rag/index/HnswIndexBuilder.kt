package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import java.io.File
import java.io.IOException

interface HnswCorpusSource {
    suspend fun currentKey(): EmbeddingCorpusKey

    suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity>
}

sealed interface HnswIndexBuildOutcome {
    data class Published(
        val metadata: HnswIndexMetadata,
        val paths: HnswIndexPaths,
    ) : HnswIndexBuildOutcome

    data object BelowThreshold : HnswIndexBuildOutcome

    data object StaleCorpus : HnswIndexBuildOutcome
}

class HnswIndexBuilder(
    indexDirectory: File,
    private val publisher: HnswIndexPublisher,
    private val minimumEmbeddingCount: Int = 5_001,
    private val pageSize: Int = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = indexDirectory.canonicalFile.also { root ->
        require(root.isDirectory) { "HNSW index directory is unavailable" }
    }

    init {
        require(minimumEmbeddingCount > 0)
        require(pageSize in 1..10_000)
    }

    @Throws(IOException::class)
    suspend fun build(
        expectedCorpus: EmbeddingCorpusKey,
        source: HnswCorpusSource,
        shouldContinue: () -> Boolean = { true },
    ): HnswIndexBuildOutcome {
        if (expectedCorpus.embeddingCount < minimumEmbeddingCount) {
            return HnswIndexBuildOutcome.BelowThreshold
        }
        if (source.currentKey() != expectedCorpus) return HnswIndexBuildOutcome.StaleCorpus
        if (!shouldContinue()) throw IOException("HNSW index build cancelled")

        val candidate = File.createTempFile("hnsw-build-", ".hnsw", directory).canonicalFile
        var maximumChunkId = -1L
        try {
            HnswIndex.create(
                indexDirectory = directory,
                dimension = E5ModelSpec.PINNED.dimension,
                maximumElements = expectedCorpus.embeddingCount,
                m = 16,
                efConstruction = 100,
            ).use { index ->
                var offset = 0
                var previousChunkId = -1L
                while (offset < expectedCorpus.embeddingCount) {
                    if (!shouldContinue()) throw IOException("HNSW index build cancelled")
                    val page = source.loadPage(offset, pageSize)
                    if (page.isEmpty()) throw IOException("HNSW corpus ended before its frozen stamp")
                    if (offset + page.size > expectedCorpus.embeddingCount) {
                        throw IOException("HNSW corpus exceeds its frozen stamp")
                    }
                    page.forEach { embedding ->
                        if (embedding.chunkId <= previousChunkId) {
                            throw IOException("HNSW corpus chunk IDs must be strictly increasing")
                        }
                        if (embedding.modelSha256 != expectedCorpus.modelSha256 ||
                            embedding.dimension != E5ModelSpec.PINNED.dimension
                        ) {
                            throw IOException("HNSW corpus embedding contract mismatch")
                        }
                        val vector = runCatching {
                            FloatVectorCodec.decode(embedding.vector, embedding.dimension)
                        }.getOrElse { error ->
                            throw IOException("Invalid HNSW embedding payload", error)
                        }
                        index.add(embedding.chunkId, vector)
                        previousChunkId = embedding.chunkId
                        maximumChunkId = embedding.chunkId
                    }
                    offset += page.size
                }
                index.save(candidate)
            }

            if (!shouldContinue()) throw IOException("HNSW index build cancelled")
            if (source.currentKey() != expectedCorpus) return HnswIndexBuildOutcome.StaleCorpus
            val now = clock().coerceAtLeast(1L)
            val metadata = HnswIndexMetadata(
                corpusKey = expectedCorpus,
                dimension = E5ModelSpec.PINNED.dimension,
                indexGeneration = now,
                maximumChunkId = maximumChunkId,
                plaintextLength = candidate.length(),
                plaintextSha256 = HnswIndexIntegrity.sha256(candidate),
                builtAt = now,
            )
            val paths = publisher.publish(
                metadata = metadata,
                plaintextIndex = candidate,
                shouldContinue = shouldContinue,
            )
            return HnswIndexBuildOutcome.Published(metadata, paths)
        } finally {
            if (candidate.exists() && !candidate.delete()) {
                runCatching { candidate.writeBytes(ByteArray(0)) }
                candidate.delete()
            }
        }
    }
}
