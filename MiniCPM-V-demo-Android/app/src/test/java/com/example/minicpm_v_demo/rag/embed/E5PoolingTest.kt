package com.example.minicpm_v_demo.rag.embed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E5PoolingTest {
    @Test
    fun `masked mean pooling excludes padding and normalizes`() {
        val hidden = arrayOf(
            floatArrayOf(3f, 0f),
            floatArrayOf(0f, 4f),
            floatArrayOf(100f, 100f),
        )

        val result = E5Pooling.maskedMeanAndNormalize(hidden, longArrayOf(1, 1, 0))

        assertArrayEquals(floatArrayOf(0.6f, 0.8f), result, 1e-6f)
        assertEquals(1f, E5Pooling.l2Norm(result), 1e-6f)
    }

    @Test
    fun `pooling rejects empty attention mask`() {
        val error = runCatching {
            E5Pooling.maskedMeanAndNormalize(arrayOf(floatArrayOf(1f)), longArrayOf(0))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
