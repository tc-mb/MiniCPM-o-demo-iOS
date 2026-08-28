package com.example.minicpm_v_demo.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLatencyRagRuntimeGateTest {
    @Test
    fun `checkpoint failure disables only the current process until restart`() {
        val gate = LowLatencyRagRuntimeGate()

        assertTrue(gate.isEnabled())
        gate.disable()
        assertFalse(gate.isEnabled())
        assertTrue(LowLatencyRagRuntimeGate().isEnabled())
    }
}
