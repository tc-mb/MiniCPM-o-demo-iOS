package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.RagEvidenceAcceptancePolicy
import kotlinx.coroutines.CancellationException

data class AnswerabilityCalibrationProfile(
    val classifierSha256: String,
    val minimumDenseForClassification: Float,
    val supportedProbabilityThreshold: Float,
    val maxCandidates: Int = 3,
) {
    init {
        require(
            classifierSha256.length == 64 &&
                classifierSha256.all { it in '0'..'9' || it in 'a'..'f' },
        )
        require(
            minimumDenseForClassification.isFinite() &&
                minimumDenseForClassification in -1f..1f,
        )
        require(
            supportedProbabilityThreshold.isFinite() &&
                supportedProbabilityThreshold in 0f..1f,
        )
        require(maxCandidates in 1..3)
    }
}

object CurrentAnswerabilityCalibration {
    val profile = AnswerabilityCalibrationProfile(
        classifierSha256 =
            "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
        minimumDenseForClassification = -1f,
        supportedProbabilityThreshold = 0.95f,
        maxCandidates = 3,
    )
}

object ExperimentalAnswerabilityCalibration {
    val profile = CurrentAnswerabilityCalibration.profile
}

class CascadedEvidenceAcceptancePolicy(
    private val retrievalKey: RetrievalCalibrationKey,
    private val classifier: AnswerabilityClassifier?,
    private val profile: AnswerabilityCalibrationProfile?,
) : RagEvidenceAcceptancePolicy {
    override suspend fun accept(
        question: String,
        sources: List<RetrievedChunk>,
    ): List<RetrievedChunk> {
        if (question.isBlank()) return emptyList()
        val valid = sources.asSequence()
            .filter { it.isStructurallyValid() && it.calibrationKey == retrievalKey }
            .distinctBy(RetrievedChunk::chunkId)
            .toList()
        val maxCandidates = profile?.maxCandidates ?: DEFAULT_MAX_CANDIDATES
        val anchored = valid.filter(RetrievedChunk::exactAnchor).take(maxCandidates)
        if (anchored.isNotEmpty()) return anchored

        val activeProfile = profile ?: return emptyList()
        val activeClassifier = classifier ?: return emptyList()
        val candidates = valid.filter { source ->
            source.denseScore?.let { it >= activeProfile.minimumDenseForClassification } == true ||
                source.lexicalCoverage?.let { it > 0.0 } == true
        }.take(activeProfile.maxCandidates)
        if (candidates.isEmpty()) return emptyList()

        val verdict = try {
            activeClassifier.classify(question, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return emptyList()
        }
        if (verdict.modelSha256 != activeProfile.classifierSha256) return emptyList()
        return if (
            verdict.label == AnswerabilityLabel.SUPPORTED &&
            verdict.supportedProbability >= activeProfile.supportedProbabilityThreshold
        ) {
            candidates
        } else {
            emptyList()
        }
    }

    private fun RetrievedChunk.isStructurallyValid(): Boolean =
        chunkId > 0 &&
            documentId.isNotBlank() &&
            text.isNotBlank() &&
            score.isFinite() &&
            tokenCount >= 0 &&
            denseScore?.isFinite() != false &&
            lexicalScore?.isFinite() != false &&
            lexicalCoverage?.let { it.isFinite() && it in 0.0..1.0 } != false

    private companion object {
        const val DEFAULT_MAX_CANDIDATES = 3
    }
}
