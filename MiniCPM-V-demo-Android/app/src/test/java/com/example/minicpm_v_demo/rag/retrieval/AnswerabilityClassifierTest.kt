package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerabilityClassifierTest {
    @Test
    fun `verdict preserves a valid three class result`() {
        val verdict = AnswerabilityVerdict(
            label = AnswerabilityLabel.PARTIAL,
            supportedProbability = 0.4f,
            modelSha256 = SHA,
        )

        assertEquals(AnswerabilityLabel.PARTIAL, verdict.label)
        assertEquals(0.4f, verdict.supportedProbability)
        assertEquals(SHA, verdict.modelSha256)
    }

    @Test
    fun `verdict rejects invalid probabilities`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach { probability ->
            assertThrows(IllegalArgumentException::class.java) {
                AnswerabilityVerdict(
                    label = AnswerabilityLabel.SUPPORTED,
                    supportedProbability = probability,
                    modelSha256 = SHA,
                )
            }
        }
    }

    @Test
    fun `verdict rejects a non canonical model digest`() {
        listOf("", "A".repeat(64), "g".repeat(64), "a".repeat(63)).forEach { digest ->
            assertThrows(IllegalArgumentException::class.java) {
                AnswerabilityVerdict(
                    label = AnswerabilityLabel.UNSUPPORTED,
                    supportedProbability = 0f,
                    modelSha256 = digest,
                )
            }
        }
    }

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
