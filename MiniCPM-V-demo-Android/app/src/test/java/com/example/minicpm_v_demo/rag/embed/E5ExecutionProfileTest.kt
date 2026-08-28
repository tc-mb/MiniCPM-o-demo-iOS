package com.example.minicpm_v_demo.rag.embed

import ai.onnxruntime.providers.NNAPIFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E5ExecutionProfileTest {
    @Test
    fun `NNAPI profiles prohibit silent CPU fallback and only FP16 profile enables FP16`() {
        assertEquals(E5ExecutionProfile.CPU, E5ExecutionSelection.SELECTED)
        assertTrue(E5ExecutionProfile.CPU.nnapiFlags.isEmpty())
        assertEquals(
            setOf(NNAPIFlags.CPU_DISABLED),
            E5ExecutionProfile.NNAPI.nnapiFlags,
        )
        assertEquals(
            setOf(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16),
            E5ExecutionProfile.NNAPI_FP16.nnapiFlags,
        )
    }
}
