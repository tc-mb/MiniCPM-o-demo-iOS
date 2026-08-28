package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityLabel
import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityVerdict
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RagGuardContractTest {
    @Test
    fun `shared classifier exposes independent answerability and groundedness heads`() = runBlocking {
        val source = RetrievedChunk(
            chunkId = 1,
            displayName = "policy.txt",
            locator = "line 1",
            text = "Annual leave is ten days.",
            score = 1f,
            documentId = "doc-1",
            tokenCount = 6,
        )
        val classifier = object : RagGuardClassifier {
            override suspend fun classifyAnswerability(
                question: String,
                sources: List<RetrievedChunk>,
            ) = AnswerabilityVerdict(AnswerabilityLabel.SUPPORTED, 0.91f, SHA)

            override suspend fun classifyGroundedness(
                question: String,
                sources: List<RetrievedChunk>,
                answer: String,
            ) = GroundednessVerdict(GroundednessLabel.GROUNDED, 0.93f, SHA)
        }

        assertEquals(
            AnswerabilityLabel.SUPPORTED,
            classifier.classifyAnswerability("How much leave?", listOf(source)).label,
        )
        assertEquals(
            GroundednessLabel.GROUNDED,
            classifier.classifyGroundedness(
                "How much leave?",
                listOf(source),
                "Annual leave is ten days.",
            ).label,
        )
    }

    @Test
    fun `groundedness verdict rejects invalid probability and digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            GroundednessVerdict(GroundednessLabel.PARTIAL, Float.NaN, SHA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroundednessVerdict(GroundednessLabel.UNSUPPORTED, 0.4f, "A".repeat(64))
        }
    }

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
