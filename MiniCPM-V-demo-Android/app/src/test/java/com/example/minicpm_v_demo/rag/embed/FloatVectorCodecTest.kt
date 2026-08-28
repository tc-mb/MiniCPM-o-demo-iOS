package com.example.minicpm_v_demo.rag.embed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FloatVectorCodecTest {
    @Test
    fun `round trips finite vector in canonical little endian format`() {
        val vector = floatArrayOf(-1.25f, 0f, 3.5f)

        assertArrayEquals(vector, FloatVectorCodec.decode(FloatVectorCodec.encode(vector), 3), 0f)
    }

    @Test
    fun `rejects non finite values and invalid byte lengths`() {
        assertThrows(IllegalArgumentException::class.java) { FloatVectorCodec.encode(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { FloatVectorCodec.decode(ByteArray(3), 1) }
    }
}
