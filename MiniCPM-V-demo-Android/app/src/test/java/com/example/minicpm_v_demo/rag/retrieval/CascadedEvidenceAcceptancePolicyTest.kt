package com.example.minicpm_v_demo.rag.retrieval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CascadedEvidenceAcceptancePolicyTest {
    @Test
    fun `exact anchor bypasses classifier but mismatched retrieval key is rejected`() = runBlocking {
        var calls = 0
        val policy = policy(classifier = AnswerabilityClassifier { _, _ ->
            calls++
            verdict()
        })
        val anchored = source(1).copy(exactAnchor = true)
        val mismatched = source(2).copy(
            exactAnchor = true,
            calibrationKey = RetrievalCalibrationKey("b".repeat(64), 1),
        )

        assertEquals(listOf(anchored), policy.accept("question", listOf(anchored, mismatched)))
        assertEquals(0, calls)
    }

    @Test
    fun `low signal candidates fail closed without invoking classifier`() = runBlocking {
        var calls = 0
        val policy = policy(classifier = AnswerabilityClassifier { _, _ ->
            calls++
            verdict()
        })
        val lowSignal = source(1).copy(denseScore = 0.59f, lexicalCoverage = null)

        assertEquals(emptyList<RetrievedChunk>(), policy.accept("question", listOf(lowSignal)))
        assertEquals(0, calls)
    }

    @Test
    fun `missing production profile keeps semantic evidence closed without opening model`() = runBlocking {
        var opens = 0
        val lazyClassifier = LazyAnswerabilityClassifier {
            opens++
            AnswerabilityClassifier { _, _ -> verdict() }
        }
        val policy = CascadedEvidenceAcceptancePolicy(
            retrievalKey = RETRIEVAL_KEY,
            classifier = lazyClassifier,
            profile = null,
        )

        assertEquals(
            emptyList<RetrievedChunk>(),
            policy.accept("question", listOf(source(1).copy(denseScore = 0.9f))),
        )
        assertEquals(0, opens)
    }

    @Test
    fun `production profile is pinned to the approved override model`() {
        val profile = CurrentAnswerabilityCalibration.profile

        assertEquals(
            "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
            profile.classifierSha256,
        )
        assertEquals(0.95f, profile.supportedProbabilityThreshold)
    }

    @Test
    fun `missing classifier and empty candidates fail closed`() = runBlocking {
        val candidate = source(1).copy(denseScore = 0.8f)

        assertEquals(emptyList<RetrievedChunk>(), policy(null).accept("question", listOf(candidate)))
        assertEquals(
            emptyList<RetrievedChunk>(),
            policy(AnswerabilityClassifier { _, _ -> verdict() }).accept("question", emptyList()),
        )
    }

    @Test
    fun `duplicate chunk IDs are classified only once`() = runBlocking {
        var classifiedSources = emptyList<RetrievedChunk>()
        val classifier = AnswerabilityClassifier { _, sources ->
            classifiedSources = sources
            verdict()
        }
        val first = source(1).copy(denseScore = 0.8f)
        val duplicate = first.copy(text = "duplicate payload")
        val second = source(2).copy(denseScore = 0.8f)

        assertEquals(
            listOf(first, second),
            policy(classifier).accept("question", listOf(first, duplicate, second)),
        )
        assertEquals(listOf(first, second), classifiedSources)
    }

    @Test
    fun `supported verdict accepts only the first three candidates in one call`() = runBlocking {
        var classifiedQuestion: String? = null
        var classifiedSources = emptyList<RetrievedChunk>()
        var calls = 0
        val classifier = AnswerabilityClassifier { question, sources ->
            calls++
            classifiedQuestion = question
            classifiedSources = sources
            verdict()
        }
        val candidates = List(5) { source(it + 1L).copy(denseScore = 0.8f) }

        val accepted = policy(classifier).accept("What is the policy?", candidates)

        assertEquals(candidates.take(3), accepted)
        assertEquals("What is the policy?", classifiedQuestion)
        assertEquals(candidates.take(3), classifiedSources)
        assertEquals(1, calls)
    }

    @Test
    fun `partial low confidence and model mismatch verdicts fail closed`() = runBlocking {
        val candidate = source(1).copy(denseScore = 0.8f)
        val verdicts = listOf(
            verdict(label = AnswerabilityLabel.PARTIAL),
            verdict(probability = 0.79f),
            verdict(modelSha = "c".repeat(64)),
        )

        verdicts.forEach { result ->
            val policy = policy(AnswerabilityClassifier { _, _ -> result })
            assertEquals(emptyList<RetrievedChunk>(), policy.accept("question", listOf(candidate)))
        }
    }

    @Test
    fun `classifier failures fail closed while cancellation propagates`() = runBlocking {
        val candidate = source(1).copy(denseScore = 0.8f)
        val failed = policy(AnswerabilityClassifier { _, _ -> error("private model detail") })
        val cancelled = policy(AnswerabilityClassifier { _, _ -> throw CancellationException("stop") })

        assertEquals(emptyList<RetrievedChunk>(), failed.accept("question", listOf(candidate)))
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.accept("question", listOf(candidate)) }
        }
        Unit
    }

    private fun policy(classifier: AnswerabilityClassifier?) = CascadedEvidenceAcceptancePolicy(
        retrievalKey = RETRIEVAL_KEY,
        classifier = classifier,
        profile = AnswerabilityCalibrationProfile(
            classifierSha256 = MODEL_SHA,
            minimumDenseForClassification = 0.6f,
            supportedProbabilityThreshold = 0.8f,
            maxCandidates = 3,
        ),
    )

    private fun verdict(
        label: AnswerabilityLabel = AnswerabilityLabel.SUPPORTED,
        probability: Float = 0.9f,
        modelSha: String = MODEL_SHA,
    ) = AnswerabilityVerdict(label, probability, modelSha)

    private fun source(id: Long) = RetrievedChunk(
        chunkId = id,
        displayName = "policy.txt",
        locator = "line 1",
        text = "synthetic evidence",
        score = 0.1f,
        documentId = "doc-$id",
        tokenCount = 3,
        calibrationKey = RETRIEVAL_KEY,
    )

    private companion object {
        val RETRIEVAL_KEY = RetrievalCalibrationKey("a".repeat(64), 1)
        const val MODEL_SHA = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
