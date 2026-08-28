package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EvidenceAcceptancePolicyTest {
    @Test
    fun `current calibration is pinned to the validated model and corpus`() {
        assertNull(CurrentRetrievalCalibration.profile)
    }

    @Test
    fun `accepts exact anchors even before thresholds are calibrated`() {
        val policy = CalibratedEvidenceAcceptancePolicy(profile = null)
        val anchored = source().copy(exactAnchor = true)

        assertEquals(listOf(anchored), policy.accept(listOf(anchored)))
        assertEquals(emptyList<RetrievedChunk>(), policy.accept(listOf(source())))
    }

    @Test
    fun `accepts high dense or standard dense combined with lexical evidence`() {
        val policy = CalibratedEvidenceAcceptancePolicy(profile())
        val highDense = source().copy(denseScore = 0.90f)
        val hybrid = source().copy(
            chunkId = 2,
            denseScore = 0.82f,
            lexicalScore = 0.1,
            lexicalCoverage = 0.5,
        )
        val weakDense = source().copy(chunkId = 3, denseScore = 0.81f)
        val lexicalOnly = source().copy(chunkId = 4, lexicalScore = 10.0, lexicalCoverage = 1.0)

        assertEquals(listOf(highDense, hybrid), policy.accept(listOf(highDense, hybrid, weakDense, lexicalOnly)))
    }

    @Test
    fun `rejects high absolute BM25 when matched phrase coverage is insufficient`() {
        val policy = CalibratedEvidenceAcceptancePolicy(profile())
        val sparseMatch = source().copy(
            denseScore = 0.82f,
            lexicalScore = 100.0,
            lexicalCoverage = 0.49,
        )

        assertEquals(emptyList<RetrievedChunk>(), policy.accept(listOf(sparseMatch)))
    }

    @Test
    fun `rejects evidence produced by a different calibration key`() {
        val policy = CalibratedEvidenceAcceptancePolicy(profile())
        val mismatched = source().copy(
            denseScore = 0.99f,
            calibrationKey = RetrievalCalibrationKey("b".repeat(64), corpusVersion = 1),
        )

        assertEquals(emptyList<RetrievedChunk>(), policy.accept(listOf(mismatched)))
    }

    @Test
    fun `validates calibration thresholds`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalCalibrationProfile(
                key = KEY,
                highDenseThreshold = 0.7f,
                standardDenseThreshold = 0.8f,
                minimumLexicalCoverage = 0.5,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalCalibrationProfile(
                key = KEY,
                highDenseThreshold = 0.9f,
                standardDenseThreshold = 0.8f,
                minimumLexicalCoverage = 1.01,
            )
        }
    }

    private companion object {
        val KEY = RetrievalCalibrationKey("a".repeat(64), corpusVersion = 1)

        fun profile() = RetrievalCalibrationProfile(
            key = KEY,
            highDenseThreshold = 0.88f,
            standardDenseThreshold = 0.82f,
            minimumLexicalCoverage = 0.5,
        )

        fun source() = RetrievedChunk(
            chunkId = 1,
            displayName = "policy.txt",
            locator = "line 1",
            text = "evidence",
            score = 0.1f,
            documentId = "doc-1",
            tokenCount = 3,
            calibrationKey = KEY,
        )
    }
}
