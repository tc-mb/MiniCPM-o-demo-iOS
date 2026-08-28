package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.embed.E5Tokenizer

object KnowledgeBaseEntityFactory {
    fun create(
        id: String,
        displayName: String,
        normalizedName: String,
        timestamp: Long,
        verifiedTokenizer: E5Tokenizer?,
    ): KnowledgeBaseEntity {
        val base = KnowledgeBaseEntity(id, displayName, normalizedName, timestamp, timestamp)
        if (verifiedTokenizer == null) return base
        require(
            verifiedTokenizer.modelId.isNotBlank() &&
                SHA256.matches(verifiedTokenizer.modelSha256) &&
                SHA256.matches(verifiedTokenizer.tokenizerSha256)
        ) { "Invalid verified embedding model identity" }
        return base.copy(
            embeddingModelId = verifiedTokenizer.modelId,
            embeddingModelSha256 = verifiedTokenizer.modelSha256,
        )
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
