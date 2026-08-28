package com.example.minicpm_v_demo.rag.guard

import org.junit.Assert.assertEquals
import org.junit.Test

class RagOutputReviewPolicyTest {
    @Test
    fun `grounded output is accepted immediately`() {
        assertEquals(
            RagOutputReviewAction.ACCEPT,
            RagOutputReviewPolicy.decide(GroundednessLabel.GROUNDED, regenerationCount = 0),
        )
    }

    @Test
    fun `partial output regenerates only once`() {
        assertEquals(
            RagOutputReviewAction.REGENERATE,
            RagOutputReviewPolicy.decide(GroundednessLabel.PARTIAL, regenerationCount = 0),
        )
        assertEquals(
            RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE,
            RagOutputReviewPolicy.decide(GroundednessLabel.PARTIAL, regenerationCount = 1),
        )
    }

    @Test
    fun `unsupported output falls back to normal chat`() {
        assertEquals(
            RagOutputReviewAction.FALLBACK_TO_NORMAL_GENERATION,
            RagOutputReviewPolicy.decide(GroundednessLabel.UNSUPPORTED, regenerationCount = 0),
        )
    }

    @Test
    fun `contradicted output immediately uses knowledge base evidence`() {
        assertEquals(
            RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE,
            RagOutputReviewPolicy.decide(GroundednessLabel.CONTRADICTED, regenerationCount = 0),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative regeneration count is rejected`() {
        RagOutputReviewPolicy.decide(GroundednessLabel.GROUNDED, regenerationCount = -1)
    }
}
