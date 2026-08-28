package com.example.minicpm_v_demo.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RagTurnDeliveryPolicyTest {
    @Test
    fun noEvidenceFallsBackToUnmodifiedPlainModelPrompt() {
        val originalUserText = "你能做什么"

        assertEquals(
            originalUserText,
            RagTurnPlan.NoEvidence.plainModelPromptOrNull(originalUserText),
        )
    }

    @Test
    fun everyNonReadyRagStateFallsBackToTheUnmodifiedPlainModelPrompt() {
        val originalUserText = "问题"

        val nonReadyStates = listOf(
            RagTurnPlan.Disabled,
            RagTurnPlan.NoRetrieval,
            RagTurnPlan.NoSelection,
            RagTurnPlan.Indexing,
            RagTurnPlan.ModelRequired,
            RagTurnPlan.NoEvidence,
            RagTurnPlan.Failed(RagTurnFailure.RETRIEVAL_UNAVAILABLE),
        )

        nonReadyStates.forEach { state ->
            assertEquals(originalUserText, state.plainModelPromptOrNull(originalUserText))
        }
    }

    @Test
    fun readyRagStateCannotBeDeliveredAsAnUnaugmentedPrompt() {
        val ready = RagTurnPlan.Ready(
            runId = "run-1",
            prompt = "prepared prompt",
            citations = emptyList(),
            evidenceTokenCount = 0,
        )

        assertNull(ready.plainModelPromptOrNull("问题"))
    }
}
