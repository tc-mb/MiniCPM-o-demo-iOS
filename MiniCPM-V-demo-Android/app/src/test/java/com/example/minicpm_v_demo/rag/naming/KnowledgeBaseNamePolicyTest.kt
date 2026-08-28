package com.example.minicpm_v_demo.rag.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeBaseNamePolicyTest {
    @Test
    fun `normalization folds width trims and collapses unicode whitespace`() {
        val result = KnowledgeBaseNamePolicy.validateAndNormalize("  项目　资料   库  ")

        assertEquals("项目 资料 库", result.displayName)
        assertEquals("项目 资料 库", result.normalizedName)
    }

    @Test
    fun `normalization preserves display case and uses locale independent lowercase key`() {
        val result = KnowledgeBaseNamePolicy.validateAndNormalize("ＡＢＣ Knowledge")

        assertEquals("ABC Knowledge", result.displayName)
        assertEquals("abc knowledge", result.normalizedName)
    }

    @Test
    fun `normalization composes canonically equivalent unicode`() {
        val result = KnowledgeBaseNamePolicy.validateAndNormalize("Cafe\u0301")

        assertEquals("Café", result.displayName)
        assertEquals("café", result.normalizedName)
    }

    @Test
    fun `validation rejects blank control newline and overlong names`() {
        listOf(
            "   ",
            "项目\n资料",
            "项目\u0000资料",
            "😀".repeat(KnowledgeBaseNamePolicy.MAX_CODE_POINTS + 1),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `validation counts unicode code points instead of utf16 code units`() {
        val name = "😀".repeat(KnowledgeBaseNamePolicy.MAX_CODE_POINTS)

        val result = KnowledgeBaseNamePolicy.validateAndNormalize(name)

        assertEquals(KnowledgeBaseNamePolicy.MAX_CODE_POINTS, result.displayName.codePointCount(0, result.displayName.length))
        assertTrue(result.normalizedName.isNotBlank())
    }

    private fun assertInvalid(raw: String) {
        try {
            KnowledgeBaseNamePolicy.validateAndNormalize(raw)
            fail("Expected invalid knowledge base name")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
