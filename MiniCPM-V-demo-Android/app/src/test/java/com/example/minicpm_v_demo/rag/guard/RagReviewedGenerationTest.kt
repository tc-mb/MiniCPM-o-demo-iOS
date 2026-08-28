package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.RetrievalCalibrationKey
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RagReviewedGenerationTest {
    @Test
    fun `production groundedness profile is pinned to the approved override model`() {
        assertEquals(
            "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
            CurrentGroundednessCalibration.profile.classifierSha256,
        )
        assertEquals(0.95f, CurrentGroundednessCalibration.profile.groundedProbabilityThreshold)
    }

    @Test
    fun `grounded first candidate is accepted without regeneration`() = runBlocking {
        val reviewer = reviewer(GroundednessVerdict(GroundednessLabel.GROUNDED, 0.96f, SHA))
        var regenerations = 0

        val result = reviewer.review("问题", SOURCES, "有依据的回答") {
            regenerations++
            "unused"
        }

        assertEquals(ReviewedRagGeneration.Accepted("根据数据库中内容，有依据的回答", 0), result)
        assertEquals(0, regenerations)
    }

    @Test
    fun `partial first candidate regenerates once and accepts corrected answer`() = runBlocking {
        val reviewer = reviewer(
            GroundednessVerdict(GroundednessLabel.PARTIAL, 0.31f, SHA),
            GroundednessVerdict(GroundednessLabel.GROUNDED, 0.94f, SHA),
        )
        var receivedPrompt = ""

        val result = reviewer.review("question", SOURCES, "invented candidate") { prompt ->
            receivedPrompt = prompt
            "corrected answer"
        }

        assertEquals(ReviewedRagGeneration.Accepted("According to the knowledge base, corrected answer", 1), result)
        assertFalse(receivedPrompt.contains("invented candidate"))
        assertTrue(receivedPrompt.contains("question"))
        assertTrue(receivedPrompt.contains("evidence text"))
    }

    @Test
    fun `second rejection replaces candidates with the knowledge base evidence`() = runBlocking {
        val reviewer = reviewer(
            GroundednessVerdict(GroundednessLabel.PARTIAL, 0.05f, SHA),
            GroundednessVerdict(GroundednessLabel.PARTIAL, 0.40f, SHA),
        )

        val result = reviewer.review("question", SOURCES, "secret first candidate") {
            "secret second candidate"
        }

        assertEquals(
            ReviewedRagGeneration.Accepted(
                "According to the knowledge base:\n[S1] evidence text",
                regenerationCount = 1,
            ),
            result,
        )
        assertFalse(result.toString().contains("secret"))
    }

    @Test
    fun `unsupported candidate falls back to normal generation without regeneration`() = runBlocking {
        val reviewer = reviewer(
            GroundednessVerdict(GroundednessLabel.UNSUPPORTED, 0.05f, SHA),
        )
        var regenerations = 0

        val result = reviewer.review("question", SOURCES, "candidate") {
            regenerations++
            "unused"
        }

        assertEquals(ReviewedRagGeneration.FallbackToNormalGeneration, result)
        assertEquals(0, regenerations)
    }

    @Test
    fun `contradicted candidate immediately uses knowledge base evidence`() = runBlocking {
        val reviewer = reviewer(
            GroundednessVerdict(GroundednessLabel.CONTRADICTED, 0.01f, SHA),
        )

        val result = reviewer.review("question", SOURCES, "wrong candidate") { "unused" }

        assertEquals(
            ReviewedRagGeneration.Accepted(
                "According to the knowledge base:\n[S1] evidence text",
                regenerationCount = 0,
            ),
            result,
        )
    }

    @Test
    fun `classifier mismatch falls back to normal generation without exposing candidate`() = runBlocking {
        val reviewer = reviewer(
            GroundednessVerdict(GroundednessLabel.GROUNDED, 0.99f, "a".repeat(64)),
        )

        val result = reviewer.review("question", SOURCES, "candidate") { "unused" }

        assertEquals(ReviewedRagGeneration.FallbackToNormalGeneration, result)
    }

    @Test
    fun `groundedness watchdog falls back without exposing a timed out candidate`() = runBlocking {
        val reviewer = RagReviewedGenerator(
            classifier = WatchdogGroundednessClassifier(
                delegate = GroundednessClassifier { _, _, _ ->
                    delay(100)
                    GroundednessVerdict(GroundednessLabel.GROUNDED, 0.99f, SHA)
                },
                timeoutMs = 1,
            ),
            profile = GroundednessCalibrationProfile(SHA, 0.80f),
        )

        val result = reviewer.review("question", SOURCES, "private timed out candidate") { "unused" }

        assertEquals(ReviewedRagGeneration.FallbackToNormalGeneration, result)
        assertFalse(result.toString().contains("private timed out candidate"))
    }

    @Test
    fun `knowledge attribution is inserted after a completed thinking block`() = runBlocking {
        val reviewer = reviewer(GroundednessVerdict(GroundednessLabel.GROUNDED, 0.96f, SHA))

        val result = reviewer.review("问题", SOURCES, "<think>内部推理</think>最终回答") { "unused" }

        assertEquals(
            ReviewedRagGeneration.Accepted(
                "<think>内部推理</think>\n根据数据库中内容，最终回答",
                0,
            ),
            result,
        )
    }

    @Test
    fun `classifier reviews visible answer instead of private thinking text`() = runBlocking {
        var reviewedAnswer = ""
        val reviewer = RagReviewedGenerator(
            classifier = GroundednessClassifier { _, _, answer ->
                reviewedAnswer = answer
                GroundednessVerdict(GroundednessLabel.GROUNDED, 0.96f, SHA)
            },
            profile = GroundednessCalibrationProfile(SHA, 0.80f),
        )

        reviewer.review("问题", SOURCES, "<think>未核验的推理</think>可见回答") { "unused" }

        assertEquals("可见回答", reviewedAnswer)
    }

    @Test
    fun `cancellation from classifier propagates`() {
        try {
            runBlocking {
                val reviewer = RagReviewedGenerator(
                    classifier = object : GroundednessClassifier {
                        override suspend fun classify(
                            question: String,
                            sources: List<RetrievedChunk>,
                            answer: String,
                        ): GroundednessVerdict = throw CancellationException("cancel")
                    },
                    profile = GroundednessCalibrationProfile(SHA, 0.80f),
                )

                reviewer.review("question", SOURCES, "candidate") { "unused" }
            }
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            Unit
        }
    }

    private fun reviewer(vararg verdicts: GroundednessVerdict): RagReviewedGenerator {
        val queue = ArrayDeque(verdicts.toList())
        return RagReviewedGenerator(
            classifier = object : GroundednessClassifier {
                override suspend fun classify(
                    question: String,
                    sources: List<RetrievedChunk>,
                    answer: String,
                ): GroundednessVerdict = queue.removeFirst()
            },
            profile = GroundednessCalibrationProfile(SHA, 0.80f),
        )
    }

    private companion object {
        const val SHA = "45d42125648c169a19697ce8b64f6883e63c2d8a45fd666c73bf163a3c59e097"
        val SOURCES = listOf(
            RetrievedChunk(
                chunkId = 1,
                documentId = "doc-1",
                displayName = "policy.txt",
                text = "evidence text",
                score = 0.9f,
                tokenCount = 4,
                locator = "line 1",
                calibrationKey = RetrievalCalibrationKey("0".repeat(64), 1),
            ),
        )
    }
}
