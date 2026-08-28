package com.example.minicpm_v_demo.rag.embed

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8TokenOffsetsTest {
    @Test
    fun `converts UTF-8 byte offsets to Kotlin character boundaries`() {
        val text = "a\u6d4b\ud83d\ude00z"

        assertEquals(listOf(0, 1, 2, 4, 5), Utf8TokenOffsets.toUtf16Boundaries(text, intArrayOf(0, 1, 4, 8, 9)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects offset inside a UTF-8 code point`() {
        Utf8TokenOffsets.toUtf16Boundaries("\u6d4b", intArrayOf(1))
    }
}
