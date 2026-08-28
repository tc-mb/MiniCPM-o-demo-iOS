package com.example.minicpm_v_demo.rag.retrieval

enum class AnswerabilityLabel {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
}

data class AnswerabilityVerdict(
    val label: AnswerabilityLabel,
    val supportedProbability: Float,
    val modelSha256: String,
) {
    init {
        require(supportedProbability.isFinite() && supportedProbability in 0f..1f)
        require(
            modelSha256.length == 64 &&
                modelSha256.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }
}

fun interface AnswerabilityClassifier {
    suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict
}
