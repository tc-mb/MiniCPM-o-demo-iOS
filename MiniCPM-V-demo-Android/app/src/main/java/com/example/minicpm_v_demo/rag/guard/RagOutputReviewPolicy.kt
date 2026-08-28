package com.example.minicpm_v_demo.rag.guard

enum class RagOutputReviewAction {
    ACCEPT,
    REGENERATE,
    REPLACE_WITH_KNOWLEDGE_BASE,
    FALLBACK_TO_NORMAL_GENERATION,
}

object RagOutputReviewPolicy {
    private const val MAX_REGENERATIONS = 1

    fun decide(
        label: GroundednessLabel,
        regenerationCount: Int,
    ): RagOutputReviewAction {
        require(regenerationCount >= 0)
        return when (label) {
            GroundednessLabel.GROUNDED -> RagOutputReviewAction.ACCEPT
            GroundednessLabel.UNSUPPORTED -> RagOutputReviewAction.FALLBACK_TO_NORMAL_GENERATION
            GroundednessLabel.CONTRADICTED -> RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE
            GroundednessLabel.PARTIAL -> if (regenerationCount < MAX_REGENERATIONS) {
                RagOutputReviewAction.REGENERATE
            } else {
                RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE
            }
        }
    }
}
