package com.example.minicpm_v_demo.rag.index

import org.junit.Assert.assertEquals
import org.junit.Test

class HnswSearchPolicyTest {
    @Test
    fun `production query width matches the measured twenty thousand vector release gate`() {
        assertEquals(256, HnswSearchPolicy.DEFAULT_EF_SEARCH)
    }
}
