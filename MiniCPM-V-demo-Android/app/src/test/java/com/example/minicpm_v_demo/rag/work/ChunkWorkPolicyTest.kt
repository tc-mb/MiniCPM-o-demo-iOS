package com.example.minicpm_v_demo.rag.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkWorkPolicyTest {
    @Test
    fun `missing exact tokenizer is recoverable without fabricating counts`() {
        val hash = "a".repeat(64)
        assertEquals(ChunkPrerequisiteDecision.MODEL_REQUIRED, ChunkWorkPolicy.decide(null, "model", hash))
        assertTrue(ChunkWorkPolicy.decide(null, "model", hash).recoverable)
    }

    @Test
    fun `tokenizer must match both configured model and hash`() {
        assertEquals(
            ChunkPrerequisiteDecision.READY,
            ChunkWorkPolicy.decide(
                TokenizerIdentity("model", "a".repeat(64), "b".repeat(64)),
                "model",
                "a".repeat(64),
            ),
        )
        assertEquals(
            ChunkPrerequisiteDecision.TOKENIZER_MISMATCH,
            ChunkWorkPolicy.decide(
                TokenizerIdentity("other", "a".repeat(64), "b".repeat(64)),
                "model",
                "a".repeat(64),
            ),
        )
        assertFalse(ChunkPrerequisiteDecision.TOKENIZER_MISMATCH.recoverable)
    }
}
