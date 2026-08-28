package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.AnswerabilityVerdict
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk

enum class GroundednessLabel {
    GROUNDED,
    PARTIAL,
    UNSUPPORTED,
    CONTRADICTED,
}

data class GroundednessVerdict(
    val label: GroundednessLabel,
    val groundedProbability: Float,
    val modelSha256: String,
) {
    init {
        require(groundedProbability.isFinite() && groundedProbability in 0f..1f)
        require(
            modelSha256.length == SHA256_HEX_LENGTH &&
                modelSha256.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * Contract for one shared encoder with independent answerability and groundedness heads.
 * Implementations must keep raw questions, evidence, and answers out of logs.
 */
interface RagGuardClassifier {
    suspend fun classifyAnswerability(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict

    suspend fun classifyGroundedness(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String,
    ): GroundednessVerdict
}
