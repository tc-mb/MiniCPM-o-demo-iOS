package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityLabel
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGuardInferenceContractTest {
    @Test
    fun `input pair exactly matches the v4 training contract`() {
        val sources = listOf(source("first"), source("second", id = 2))

        assertEquals(
            RagGuardTextPair(
                protectedText = "query: question",
                evidenceText = "evidence [S1]: first\nevidence [S2]: second",
            ),
            RagGuardInput.answerabilityPair(" question ", sources),
        )
        assertEquals(
            RagGuardTextPair(
                protectedText = "query: question\nanswer: response",
                evidenceText = "evidence [S1]: first\nevidence [S2]: second",
            ),
            RagGuardInput.groundednessPair(" question ", sources, " response "),
        )
    }

    @Test
    fun `xlmr pair assembly preserves protected tokens and truncates only evidence`() {
        assertArrayEquals(
            longArrayOf(0, 10, 2, 2, 20, 21, 2),
            RagGuardInput.assembleXlmrPair(
                protectedIds = longArrayOf(0, 10, 2),
                evidenceIds = longArrayOf(0, 20, 21, 22, 2),
                maxTokens = 7,
            ),
        )
    }

    @Test
    fun `shared runner selects the requested head and decodes softmax probabilities`() = runBlocking {
        val calls = mutableListOf<Int>()
        val classifier = OnnxRagGuardClassifier.forTest(
            manifest = CurrentRagGuardModel.PINNED,
            encode = { text ->
                when {
                    text.startsWith("query:") -> longArrayOf(0, 7, 2)
                    text.startsWith("evidence [S1]:") -> longArrayOf(0, 8, 2)
                    else -> error("unexpected tokenizer input")
                }
            },
            infer = { ids, attention, taskId ->
                assertArrayEquals(longArrayOf(0, 7, 2, 2, 8, 2), ids)
                assertArrayEquals(longArrayOf(1, 1, 1, 1, 1, 1), attention)
                calls += taskId
                if (taskId == 0) {
                    floatArrayOf(4f, 1f, -1f, -10000f)
                } else {
                    floatArrayOf(-2f, 0f, 1f, 3f)
                }
            },
        )

        val answerability = classifier.classifyAnswerability("question", listOf(source("evidence")))
        val groundedness = classifier.classifyGroundedness(
            "question",
            listOf(source("evidence")),
            "answer",
        )

        assertEquals(listOf(0, 1), calls)
        assertEquals(AnswerabilityLabel.SUPPORTED, answerability.label)
        assertEquals(GroundednessLabel.CONTRADICTED, groundedness.label)
        assertTrue(answerability.supportedProbability > 0.94f)
        assertTrue(groundedness.groundedProbability < 0.01f)
        assertEquals(CurrentRagGuardModel.PINNED.model.sha256, answerability.modelSha256)
    }

    private fun source(text: String, id: Long = 1) = RetrievedChunk(
        chunkId = id,
        displayName = "policy.txt",
        locator = "line $id",
        text = text,
        score = 1f,
        documentId = "doc-$id",
        tokenCount = 1,
    )
}
