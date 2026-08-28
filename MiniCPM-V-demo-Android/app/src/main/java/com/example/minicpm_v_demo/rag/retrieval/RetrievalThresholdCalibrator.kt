package com.example.minicpm_v_demo.rag.retrieval

data class RetrievalCalibrationObservation(
    val caseId: String,
    val relevantChunkIds: Set<Long>,
    val candidates: List<RetrievedChunk>,
)

data class RetrievalCalibrationMetrics(
    val totalCases: Int,
    val evidenceCases: Int,
    val noEvidenceCases: Int,
    val recallAt4: Double,
    val noEvidencePrecision: Double,
    val noEvidenceRecall: Double,
)

data class RetrievalCalibrationResult(
    val profile: RetrievalCalibrationProfile,
    val metrics: RetrievalCalibrationMetrics,
)

object RetrievalThresholdCalibrator {
    const val MINIMUM_CASES = 300
    const val MINIMUM_RECALL_AT_4 = 0.90
    const val MINIMUM_NO_EVIDENCE_PRECISION = 0.95

    fun select(
        key: RetrievalCalibrationKey,
        observations: List<RetrievalCalibrationObservation>,
        highDenseCandidates: List<Float>,
        standardDenseCandidates: List<Float>,
        lexicalCoverageCandidates: List<Double>,
    ): RetrievalCalibrationResult = selectOrNull(
        key,
        observations,
        highDenseCandidates,
        standardDenseCandidates,
        lexicalCoverageCandidates,
    ) ?: error("No calibration profile satisfies the quality gates")

    fun selectOrNull(
        key: RetrievalCalibrationKey,
        observations: List<RetrievalCalibrationObservation>,
        highDenseCandidates: List<Float>,
        standardDenseCandidates: List<Float>,
        lexicalCoverageCandidates: List<Double>,
    ): RetrievalCalibrationResult? {
        require(observations.size >= MINIMUM_CASES) {
            "Calibration requires at least $MINIMUM_CASES cases"
        }
        validateObservations(observations, expectedKey = key)
        val highValues = validateDenseCandidates(highDenseCandidates, "highDenseCandidates")
        val standardValues = validateDenseCandidates(standardDenseCandidates, "standardDenseCandidates")
        val lexicalValues = lexicalCoverageCandidates.distinct().onEach { value ->
            require(value.isFinite() && value in 0.0..1.0) {
                "lexicalCoverageCandidates must contain finite ratios"
            }
        }
        require(lexicalValues.isNotEmpty()) { "lexicalCoverageCandidates must not be empty" }

        var best: RetrievalCalibrationResult? = null
        for (high in highValues) {
            for (standard in standardValues) {
                if (high < standard) continue
                for (lexical in lexicalValues) {
                    val profile = RetrievalCalibrationProfile(key, high, standard, lexical)
                    val metrics = evaluateValidated(profile, observations)
                    if (metrics.recallAt4 + EPSILON < MINIMUM_RECALL_AT_4) continue
                    if (metrics.noEvidencePrecision + EPSILON < MINIMUM_NO_EVIDENCE_PRECISION) continue
                    val candidate = RetrievalCalibrationResult(profile, metrics)
                    if (best == null || RESULT_ORDER.compare(candidate, best) > 0) best = candidate
                }
            }
        }
        return best
    }

    fun evaluate(
        profile: RetrievalCalibrationProfile,
        observations: List<RetrievalCalibrationObservation>,
    ): RetrievalCalibrationMetrics {
        require(observations.isNotEmpty()) { "observations must not be empty" }
        validateObservations(observations, expectedKey = profile.key)
        return evaluateValidated(profile, observations)
    }

    private fun evaluateValidated(
        profile: RetrievalCalibrationProfile,
        observations: List<RetrievalCalibrationObservation>,
    ): RetrievalCalibrationMetrics {
        val policy = CalibratedEvidenceAcceptancePolicy(profile)
        var evidenceCases = 0
        var evidenceHits = 0
        var noEvidenceCases = 0
        var predictedNoEvidence = 0
        var correctNoEvidence = 0

        observations.forEach { observation ->
            val accepted = policy.accept(observation.candidates)
            val predictsNoEvidence = accepted.isEmpty()
            if (predictsNoEvidence) predictedNoEvidence++
            if (observation.relevantChunkIds.isEmpty()) {
                noEvidenceCases++
                if (predictsNoEvidence) correctNoEvidence++
            } else {
                evidenceCases++
                if (accepted.take(RECALL_LIMIT).any { it.chunkId in observation.relevantChunkIds }) evidenceHits++
            }
        }
        require(evidenceCases > 0 && noEvidenceCases > 0) {
            "Calibration must contain evidence and no-evidence cases"
        }
        return RetrievalCalibrationMetrics(
            totalCases = observations.size,
            evidenceCases = evidenceCases,
            noEvidenceCases = noEvidenceCases,
            recallAt4 = evidenceHits.toDouble() / evidenceCases,
            noEvidencePrecision = if (predictedNoEvidence == 0) 0.0 else {
                correctNoEvidence.toDouble() / predictedNoEvidence
            },
            noEvidenceRecall = correctNoEvidence.toDouble() / noEvidenceCases,
        )
    }

    private fun validateObservations(
        observations: List<RetrievalCalibrationObservation>,
        expectedKey: RetrievalCalibrationKey,
    ) {
        val caseIds = HashSet<String>(observations.size)
        observations.forEach { observation ->
            require(observation.caseId.isNotBlank() && caseIds.add(observation.caseId)) {
                "Calibration case IDs must be non-blank and unique"
            }
            require(observation.relevantChunkIds.all { it > 0 }) { "Relevant chunk IDs must be positive" }
            observation.candidates.forEach { source ->
                require(
                    source.denseScore?.isFinite() != false &&
                        source.lexicalScore?.isFinite() != false &&
                        source.lexicalCoverage?.let { it.isFinite() && it in 0.0..1.0 } != false,
                ) {
                    "Calibration candidate scores must be finite"
                }
                require(!source.exactAnchor) { "Threshold calibration must exclude exact-anchor candidates" }
                require(source.calibrationKey == expectedKey) { "Calibration key mismatch" }
            }
        }
    }

    private fun validateDenseCandidates(values: List<Float>, name: String): List<Float> {
        val distinct = values.distinct()
        require(distinct.isNotEmpty()) { "$name must not be empty" }
        distinct.forEach { value ->
            require(value.isFinite() && value in -1f..1f) { "$name must contain finite cosine scores" }
        }
        return distinct
    }

    private val RESULT_ORDER = compareBy<RetrievalCalibrationResult> { it.metrics.noEvidenceRecall }
        .thenBy { it.metrics.recallAt4 }
        .thenBy { it.metrics.noEvidencePrecision }
        .thenBy { it.profile.highDenseThreshold }
        .thenBy { it.profile.standardDenseThreshold }
        .thenBy { it.profile.minimumLexicalCoverage }

    private const val RECALL_LIMIT = 4
    private const val EPSILON = 1e-12
}
