package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.index.EmbeddingCorpusKey
import com.example.minicpm_v_demo.rag.index.HnswIndexMetadata
import com.example.minicpm_v_demo.rag.index.stableDigest

data class HnswRebuildInput(
    val knowledgeBaseIds: List<String>,
    val modelSha256: String,
    val corpusVersion: Int,
) {
    init {
        require(knowledgeBaseIds.isNotEmpty() && knowledgeBaseIds.size <= MAX_KNOWLEDGE_BASES)
        require(knowledgeBaseIds == knowledgeBaseIds.sorted())
        require(knowledgeBaseIds.distinct().size == knowledgeBaseIds.size)
        require(
            knowledgeBaseIds.all {
                it.isNotBlank() &&
                    it.none(Char::isISOControl) &&
                    it.toByteArray(Charsets.UTF_8).size <=
                    HnswIndexMetadata.MAX_KNOWLEDGE_BASE_ID_BYTES
            },
        )
        require(knowledgeBaseIds.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MAX_TOTAL_ID_BYTES)
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(corpusVersion > 0)
    }

    companion object {
        const val MAX_KNOWLEDGE_BASES = 64
        const val MAX_TOTAL_ID_BYTES = 4 * 1024
    }
}

object HnswRebuildContract {
    const val INITIAL_DELAY_SECONDS = 30L
    const val KEY_KNOWLEDGE_BASE_IDS = "hnswKnowledgeBaseIds"
    const val KEY_MODEL_SHA256 = "hnswModelSha256"
    const val KEY_CORPUS_VERSION = "hnswCorpusVersion"
    private const val UNIQUE_PREFIX = "rag-hnsw-rebuild-"

    fun inputValues(corpusKey: EmbeddingCorpusKey): HnswRebuildInput = HnswRebuildInput(
        knowledgeBaseIds = corpusKey.knowledgeBaseIds,
        modelSha256 = corpusKey.modelSha256,
        corpusVersion = corpusKey.corpusVersion,
    )

    fun uniqueWorkName(corpusKey: EmbeddingCorpusKey): String =
        UNIQUE_PREFIX + corpusKey.stableDigest()
}
