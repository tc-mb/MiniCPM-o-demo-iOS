package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.retrieval.RankedChunkId
import java.io.File

enum class HnswFallbackReason {
    BELOW_THRESHOLD,
    MISSING_OR_CORRUPT,
    CORPUS_MISMATCH,
    RSS_BUDGET_EXCEEDED,
}

object HnswRebuildPolicy {
    fun shouldSchedule(reason: HnswFallbackReason): Boolean = when (reason) {
        HnswFallbackReason.MISSING_OR_CORRUPT,
        HnswFallbackReason.CORPUS_MISMATCH,
        -> true
        HnswFallbackReason.BELOW_THRESHOLD,
        HnswFallbackReason.RSS_BUDGET_EXCEEDED,
        -> false
    }
}

object HnswSearchPolicy {
    const val DEFAULT_EF_SEARCH = 256
}

class HnswVectorSearchBackend(
    indexDirectory: File,
    private val publisher: HnswIndexPublisher,
    appMemoryBudgetBytes: () -> Long,
    private val exactFallback: VectorSearchBackend = ExactVectorSearchBackend(),
    private val minimumEmbeddingCount: Int = 5_001,
    private val efSearch: Int = HnswSearchPolicy.DEFAULT_EF_SEARCH,
    private val onRebuildRequired: (EmbeddingCorpusKey) -> Unit = {},
) : VectorSearchBackend {
    private val directory = indexDirectory.canonicalFile.also { root ->
        require(root.isDirectory) { "HNSW index directory is unavailable" }
    }
    private val manager = HnswIndexManager(directory, appMemoryBudgetBytes)

    init {
        require(minimumEmbeddingCount > 0)
        require(efSearch > 0)
    }

    override suspend fun search(
        request: VectorSearchRequest,
        source: VectorEmbeddingSource,
    ): List<RankedChunkId> {
        if (request.corpusKey.embeddingCount < minimumEmbeddingCount) {
            return exactFallback.search(request, source)
        }
        val metadata = try {
            publisher.readMetadata(request.corpusKey)
        } catch (_: Exception) {
            scheduleIfRequired(HnswFallbackReason.MISSING_OR_CORRUPT, request.corpusKey)
            return exactFallback.search(request, source)
        }
        val admission = manager.assess(request.corpusKey, metadata)
        if (!admission.allowed) {
            val reason = when (admission.rejection) {
                HnswIndexRejection.CORPUS_MISMATCH -> HnswFallbackReason.CORPUS_MISMATCH
                HnswIndexRejection.RSS_BUDGET_EXCEEDED -> HnswFallbackReason.RSS_BUDGET_EXCEEDED
                null -> HnswFallbackReason.MISSING_OR_CORRUPT
            }
            scheduleIfRequired(reason, request.corpusKey)
            return exactFallback.search(request, source)
        }
        return try {
            publisher.withVerifiedPlaintext(request.corpusKey) { plaintext ->
                HnswIndex.load(
                    indexDirectory = directory,
                    indexFile = plaintext,
                    dimension = metadata.dimension,
                    maximumElements = metadata.corpusKey.embeddingCount,
                ).use { index ->
                    index.search(
                        query = request.query,
                        topK = request.limit,
                        efSearch = maxOf(efSearch, request.limit),
                    )
                }
            }
        } catch (_: Exception) {
            scheduleIfRequired(HnswFallbackReason.MISSING_OR_CORRUPT, request.corpusKey)
            exactFallback.search(request, source)
        }
    }

    private fun scheduleIfRequired(reason: HnswFallbackReason, corpusKey: EmbeddingCorpusKey) {
        if (HnswRebuildPolicy.shouldSchedule(reason)) runCatching { onRebuildRequired(corpusKey) }
    }
}
