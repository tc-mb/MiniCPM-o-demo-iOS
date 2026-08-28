package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.RagEvidenceRetriever
import com.example.minicpm_v_demo.rag.RagRetrievalOutcome
import com.example.minicpm_v_demo.rag.RagRetrievalRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRetrieverTest {
    @Test
    fun `fuses both routes and requests only top forty candidates`() = runBlocking {
        val dense = FakeDense(RagRetrievalOutcome.Evidence(listOf(source(1, "dense", 0.8f))))
        val lexical = FakeLexical(listOf(LexicalRetrievedChunk(source(2, "lexical", 0f), 4.0)))
        val retriever = HybridRetriever(dense, lexical)

        val result = retriever.retrieve(request(limit = 6))

        assertTrue(result is RagRetrievalOutcome.Evidence)
        result as RagRetrievalOutcome.Evidence
        assertEquals(listOf(1L, 2L), result.sources.map(RetrievedChunk::chunkId))
        assertEquals(40, dense.requestedLimit)
        assertEquals(40, lexical.requestedLimit)
        assertEquals(0.8f, result.sources.first().denseScore)
        assertEquals(4.0, result.sources.last().lexicalScore)
    }

    @Test
    fun `degrades to either healthy route and fails only when both routes fail`() = runBlocking {
        val lexicalOnly = HybridRetriever(
            FakeDense(failure = IllegalStateException("dense detail")),
            FakeLexical(listOf(LexicalRetrievedChunk(source(2, "lexical", 0f), 4.0))),
        ).retrieve(request()) as RagRetrievalOutcome.Evidence
        val denseOnly = HybridRetriever(
            FakeDense(RagRetrievalOutcome.Evidence(listOf(source(1, "dense", 0.8f)))),
            FakeLexical(failure = IllegalStateException("fts detail")),
        ).retrieve(request()) as RagRetrievalOutcome.Evidence

        assertEquals(listOf(2L), lexicalOnly.sources.map(RetrievedChunk::chunkId))
        assertEquals(listOf(1L), denseOnly.sources.map(RetrievedChunk::chunkId))
        assertThrows(HybridRetrievalUnavailableException::class.java) {
            runBlocking {
                HybridRetriever(
                    FakeDense(failure = IllegalStateException("dense secret")),
                    FakeLexical(failure = IllegalStateException("fts secret")),
                ).retrieve(request())
            }
        }
        Unit
    }

    @Test
    fun `uses lexical evidence when embedding model is missing`() = runBlocking {
        val withLexicalHit = HybridRetriever(
            FakeDense(RagRetrievalOutcome.ModelRequired),
            FakeLexical(listOf(LexicalRetrievedChunk(source(2, "lexical", 0f), 4.0))),
        ).retrieve(request())
        val withoutAnyHit = HybridRetriever(
            FakeDense(RagRetrievalOutcome.ModelRequired),
            FakeLexical(emptyList()),
        ).retrieve(request())

        assertTrue(withLexicalHit is RagRetrievalOutcome.Evidence)
        assertEquals(RagRetrievalOutcome.ModelRequired, withoutAnyHit)
    }

    @Test
    fun `limits fusion output and each document contribution`() = runBlocking {
        val denseSources = (1L..20L).map { chunkId ->
            source(chunkId, documentId = if (chunkId <= 8) "same-document" else "doc-$chunkId", score = 1f)
        }
        val result = HybridRetriever(
            FakeDense(RagRetrievalOutcome.Evidence(denseSources)),
            FakeLexical(emptyList()),
        ).retrieve(request(limit = 12)) as RagRetrievalOutcome.Evidence

        assertEquals(12, result.sources.size)
        assertEquals(3, result.sources.count { it.documentId == "same-document" })
    }

    @Test
    fun `propagates cancellation from either route`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                HybridRetriever(
                    FakeDense(failure = CancellationException("cancelled")),
                    FakeLexical(emptyList()),
                ).retrieve(request())
            }
        }
    }

    private class FakeDense(
        private val result: RagRetrievalOutcome = RagRetrievalOutcome.Evidence(emptyList()),
        private val failure: Exception? = null,
    ) : RagEvidenceRetriever {
        var requestedLimit: Int? = null

        override suspend fun retrieve(request: RagRetrievalRequest): RagRetrievalOutcome {
            requestedLimit = request.limit
            failure?.let { throw it }
            return result
        }
    }

    private class FakeLexical(
        private val result: List<LexicalRetrievedChunk> = emptyList(),
        private val failure: Exception? = null,
    ) : LexicalEvidenceRetriever {
        var requestedLimit: Int? = null

        override suspend fun retrieve(
            knowledgeBaseIds: List<String>,
            question: String,
            limit: Int,
        ): List<LexicalRetrievedChunk> {
            requestedLimit = limit
            failure?.let { throw it }
            return result
        }
    }

    private companion object {
        fun request(limit: Int = 6) = RagRetrievalRequest(listOf("kb-1"), "policy", limit)

        fun source(
            chunkId: Long,
            documentId: String,
            score: Float,
        ) = RetrievedChunk(
            chunkId = chunkId,
            displayName = "$documentId.txt",
            locator = "line $chunkId",
            text = "evidence $chunkId",
            score = score,
            documentId = documentId,
            tokenCount = 3,
        )
    }
}
