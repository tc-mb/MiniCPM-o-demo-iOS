package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.embed.E5Tokenizer
import com.example.minicpm_v_demo.rag.embed.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeBaseEntityFactoryTest {
    @Test
    fun `new knowledge base binds the currently verified embedding model`() {
        val model = object : E5Tokenizer {
            override val modelId = "verified-model"
            override val modelSha256 = "a".repeat(64)
            override val tokenizerSha256 = "b".repeat(64)
            override fun tokenSpans(text: String): List<TokenSpan> = emptyList()
        }

        val entity = KnowledgeBaseEntityFactory.create(
            id = "kb",
            displayName = "Office",
            normalizedName = "office",
            timestamp = 123L,
            verifiedTokenizer = model,
        )

        assertEquals("verified-model", entity.embeddingModelId)
        assertEquals("a".repeat(64), entity.embeddingModelSha256)
    }
}
