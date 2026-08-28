package com.example.minicpm_v_demo.rag.prompt

import com.example.minicpm_v_demo.rag.RagPromptTokenCounter
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagContextBudgeterTest {
    @Test
    fun `uses exact counter and enforces per-source and total budgets`() = runBlocking {
        val budget = RagContextBudgeter().budget(
            question = "question",
            sources = listOf(source(1, 500), source(2, 500), source(3, 500)),
            tokenCounter = WordCounter(remaining = 4_096),
        )

        assertEquals(768, budget.tokenCount)
        assertEquals(listOf(320, 320, 128), budget.sources.map(RetrievedChunk::tokenCount))
        assertTrue(budget.sources.all { it.tokenCount <= 320 })
    }

    @Test
    fun `returns no evidence when context cannot preserve minimum answer space`() = runBlocking {
        val budget = RagContextBudgeter().budget(
            question = "question",
            sources = listOf(source(1, 200)),
            tokenCounter = WordCounter(remaining = 1_100),
        )

        assertTrue(budget.sources.isEmpty())
        assertEquals(0, budget.tokenCount)
    }

    @Test
    fun `does not split surrogate pairs while truncating`() = runBlocking {
        val text = List(400) { "证据😀" }.joinToString(" ")
        val budget = RagContextBudgeter(maxTokensPerSource = 20).budget(
            question = "问题",
            sources = listOf(source(1, 1).copy(text = text)),
            tokenCounter = WordCounter(remaining = 4_096),
        )

        assertEquals(20, budget.sources.single().tokenCount)
        val boundedText = budget.sources.single().text
        assertFalse(boundedText.last().isHighSurrogate())
        if (boundedText.last().isLowSurrogate()) {
            assertTrue(boundedText[boundedText.lastIndex - 1].isHighSurrogate())
        }
    }

    private class WordCounter(private val remaining: Int) : RagPromptTokenCounter {
        override suspend fun count(text: String): Int = text.trim().split(Regex("\\s+")).count(String::isNotBlank)
        override suspend fun remainingContextTokens(): Int = remaining
    }

    private fun source(id: Long, words: Int) = RetrievedChunk(
        chunkId = id,
        documentId = "doc-$id",
        displayName = "source-$id.txt",
        locator = "line 1",
        text = List(words) { "word$it" }.joinToString(" "),
        score = 0.9f,
        tokenCount = words,
    )
}
