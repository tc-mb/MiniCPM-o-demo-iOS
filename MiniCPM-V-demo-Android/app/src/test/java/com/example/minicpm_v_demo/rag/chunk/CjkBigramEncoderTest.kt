package com.example.minicpm_v_demo.rag.chunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkBigramEncoderTest {
    @Test
    fun `adds CJK bigrams while preserving words numbers and original text`() {
        val original = "项目验收编号 AB-2026-0810"

        val encoded = CjkBigramEncoder.encode(original)

        assertEquals("项目 目验 验收 收编 编号 AB-2026-0810", encoded)
        assertEquals("项目验收编号 AB-2026-0810", original)
    }

    @Test
    fun `does not bridge punctuation whitespace or emoji`() {
        val encoded = CjkBigramEncoder.encode("甲乙，丙丁 😀 戊己")

        assertTrue(encoded.contains("甲乙"))
        assertTrue(encoded.contains("丙丁"))
        assertTrue(encoded.contains("戊己"))
        assertFalse(encoded.contains("乙丙"))
        assertFalse(encoded.contains("丁戊"))
    }
}
