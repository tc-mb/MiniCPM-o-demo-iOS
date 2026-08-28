package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGuardReplyPolicyTest {

    @Test
    fun allowedPromptIsDispatchedToModelContext() {
        val plan = LocalGuardReplyPolicy.plan(VisualPromptDecision.ALLOW)

        assertEquals(PromptDestination.MODEL, plan.destination)
        assertTrue(plan.includeInModelContext)
        assertNull(plan.localReplyKind)
    }

    @Test
    fun blockedPromptsAreDispatchedToDistinctLocalOnlyReplies() {
        val missingVisual = LocalGuardReplyPolicy.plan(
            VisualPromptDecision.BLOCK_NEEDS_VISUAL
        )
        val uncertain = LocalGuardReplyPolicy.plan(
            VisualPromptDecision.BLOCK_UNCERTAIN
        )

        assertEquals(PromptDestination.LOCAL_ONLY, missingVisual.destination)
        assertFalse(missingVisual.includeInModelContext)
        assertEquals(LocalGuardReplyKind.NO_VISUAL_CONTEXT, missingVisual.localReplyKind)

        assertEquals(PromptDestination.LOCAL_ONLY, uncertain.destination)
        assertFalse(uncertain.includeInModelContext)
        assertEquals(LocalGuardReplyKind.UNCERTAIN_VISUAL_REQUEST, uncertain.localReplyKind)
    }

    @Test
    fun streamingFramesNeverExposeHalfOfAUnicodeCodePoint() {
        assertEquals(
            listOf("好", "好🙂"),
            LocalResponseStreamer.frames("好🙂").toList()
        )
    }
}
