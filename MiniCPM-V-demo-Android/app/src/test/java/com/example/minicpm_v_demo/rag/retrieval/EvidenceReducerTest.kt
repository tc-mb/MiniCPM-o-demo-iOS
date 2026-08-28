package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceReducerTest {
    @Test
    fun `keeps best chinese sentence with adjacent context and exact amount`() {
        val source = source(
            "第一条适用范围。第二条报销上限为 3200 元。第三条需要主管签字。第四条是其他内容。",
        )

        val reduced = SentenceWindowEvidenceReducer.reduce("报销上限是多少钱？", listOf(source)).single()

        assertTrue(reduced.text.contains("第一条适用范围。"))
        assertTrue(reduced.text.contains("第二条报销上限为 3200 元。"))
        assertTrue(reduced.text.contains("第三条需要主管签字。"))
        assertFalse(reduced.text.contains("第四条是其他内容。"))
    }

    @Test
    fun `keeps english sentence window and preserves emoji and table row boundaries`() {
        val source = source(
            "Overview.\nStatus | Owner\nApproved | Alice ✅\nSubmit by 2026-08-31.\nUnrelated ending.",
        )

        val reduced = SentenceWindowEvidenceReducer.reduce("Who owns the approved status?", listOf(source)).single()

        assertTrue(reduced.text.contains("Status | Owner"))
        assertTrue(reduced.text.contains("Approved | Alice ✅"))
        assertTrue(reduced.text.contains("Submit by 2026-08-31."))
    }

    @Test
    fun `deduplicates equivalent evidence across sources`() {
        val repeated = "The travel limit is 500 dollars."
        val reduced = SentenceWindowEvidenceReducer.reduce(
            "travel limit",
            listOf(source(repeated, 1), source("  THE travel limit is 500 dollars.  ", 2)),
        )

        assertEquals(1, reduced.size)
    }

    private fun source(text: String, id: Long = 1) = RetrievedChunk(
        chunkId = id,
        documentId = "doc-$id",
        displayName = "policy-$id.txt",
        locator = "line 1",
        text = text,
        score = 0.9f,
        tokenCount = 100,
    )
}
