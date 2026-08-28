package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalThresholdCalibratorTest {
    @Test
    fun `rejects calibration sets smaller than three hundred cases`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalThresholdCalibrator.select(
                key = KEY,
                observations = List(299) { noEvidenceObservation(it.toLong()) },
                highDenseCandidates = listOf(0.90f),
                standardDenseCandidates = listOf(0.80f),
                lexicalCoverageCandidates = listOf(0.5),
            )
        }
    }

    @Test
    fun `selects a deterministic conservative profile that clears both quality gates`() {
        val evidence = List(180) { index ->
            evidenceObservation(index.toLong(), denseScore = 0.92f, lexicalCoverage = null)
        } + List(20) { index ->
            evidenceObservation((1_000 + index).toLong(), denseScore = 0.82f, lexicalCoverage = 0.75)
        }
        val noEvidence = List(100) { index -> noEvidenceObservation((2_000 + index).toLong()) }

        val result = RetrievalThresholdCalibrator.select(
            key = KEY,
            observations = evidence + noEvidence,
            highDenseCandidates = listOf(0.88f, 0.90f),
            standardDenseCandidates = listOf(0.78f, 0.80f),
            lexicalCoverageCandidates = listOf(0.5, 0.75),
        )

        assertEquals(0.90f, result.profile.highDenseThreshold)
        assertEquals(0.80f, result.profile.standardDenseThreshold)
        assertEquals(0.75, result.profile.minimumLexicalCoverage, 0.0)
        assertEquals(1.0, result.metrics.recallAt4, 0.0)
        assertEquals(1.0, result.metrics.noEvidencePrecision, 0.0)
        assertEquals(1.0, result.metrics.noEvidenceRecall, 0.0)
        assertEquals(300, result.metrics.totalCases)
    }

    @Test
    fun `fails closed when no profile satisfies recall and abstention precision`() {
        val observations = List(200) { index ->
            evidenceObservation(index.toLong(), denseScore = 0.70f, lexicalCoverage = null)
        } + List(100) { index ->
            noEvidenceObservation((10_000 + index).toLong()).copy(
                candidates = listOf(source(50_000L + index, denseScore = 0.95f, lexicalCoverage = null)),
            )
        }

        val result = RetrievalThresholdCalibrator.selectOrNull(
            key = KEY,
            observations = observations,
            highDenseCandidates = listOf(0.80f, 0.90f),
            standardDenseCandidates = listOf(0.70f),
            lexicalCoverageCandidates = listOf(0.5),
        )

        assertEquals(null, result)
    }

    @Test
    fun `validates finite candidate scores`() {
        val invalid = evidenceObservation(1, denseScore = 0.90f, lexicalCoverage = null).copy(
            candidates = listOf(source(1, denseScore = Float.NaN, lexicalCoverage = null)),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            RetrievalThresholdCalibrator.evaluate(
                RetrievalCalibrationProfile(KEY, 0.90f, 0.80f, 0.5),
                listOf(invalid),
            )
        }
        assertTrue(failure.message.orEmpty().contains("finite"))
    }

    private fun evidenceObservation(
        chunkId: Long,
        denseScore: Float,
        lexicalCoverage: Double?,
    ) = RetrievalCalibrationObservation(
        caseId = "evidence-$chunkId",
        relevantChunkIds = setOf(chunkId + 1),
        candidates = listOf(source(chunkId + 1, denseScore, lexicalCoverage)),
    )

    private fun noEvidenceObservation(seed: Long) = RetrievalCalibrationObservation(
        caseId = "none-$seed",
        relevantChunkIds = emptySet(),
        candidates = listOf(source(seed + 100_000, denseScore = 0.79f, lexicalCoverage = 0.25)),
    )

    private fun source(
        chunkId: Long,
        denseScore: Float,
        lexicalCoverage: Double?,
    ) = RetrievedChunk(
        chunkId = chunkId,
        displayName = "synthetic-$chunkId.txt",
        locator = "line 1",
        text = "synthetic calibration evidence",
        score = 0.01f,
        documentId = "doc-$chunkId",
        tokenCount = 4,
        denseScore = denseScore,
        lexicalScore = lexicalCoverage?.times(10.0),
        lexicalCoverage = lexicalCoverage,
        calibrationKey = KEY,
    )

    private companion object {
        val KEY = RetrievalCalibrationKey("a".repeat(64), corpusVersion = 1)
    }
}
