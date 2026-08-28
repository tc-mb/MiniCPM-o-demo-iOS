package com.example.minicpm_v_demo.rag.retrieval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LazyAnswerabilityClassifierTest {
    @Test
    fun `classifier is opened only by the first classify call and then cached`() = runBlocking {
        var opens = 0
        val expected = verdict()
        val delegate = AnswerabilityClassifier { _, _ -> expected }
        val classifier = LazyAnswerabilityClassifier {
            opens++
            delegate
        }

        assertEquals(0, opens)
        assertSame(expected, classifier.classify("question", listOf(source())))
        assertSame(expected, classifier.classify("follow-up", listOf(source())))
        assertEquals(1, opens)
    }

    @Test
    fun `missing installed model fails without caching an unavailable result`() = runBlocking {
        var opens = 0
        val classifier = LazyAnswerabilityClassifier {
            opens++
            null
        }

        repeat(2) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { classifier.classify("question", listOf(source())) }
            }
        }
        assertEquals(2, opens)
    }

    private fun verdict() = AnswerabilityVerdict(
        label = AnswerabilityLabel.SUPPORTED,
        supportedProbability = 0.9f,
        modelSha256 = "d".repeat(64),
    )

    private fun source() = RetrievedChunk(
        chunkId = 1,
        displayName = "policy.txt",
        locator = "line 1",
        text = "synthetic evidence",
        score = 0.1f,
        documentId = "doc-1",
        tokenCount = 3,
        calibrationKey = RetrievalCalibrationKey("a".repeat(64), 1),
    )
}
