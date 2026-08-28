package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAnchorMatcherTest {
    @Test
    fun `matches an explicitly named file case insensitively`() {
        assertTrue(ExactAnchorMatcher.matches("请总结 POLICY.TXT", source(displayName = "policy.txt")))
    }

    @Test
    fun `matches exact identifiers and Chinese clause anchors`() {
        assertTrue(
            ExactAnchorMatcher.matches(
                "编号 AB-2026-0810 的金额是多少",
                source(text = "项目编号 AB-2026-0810，金额 200 元"),
            ),
        )
        assertTrue(
            ExactAnchorMatcher.matches(
                "第十二条规定了什么",
                source(text = "第十二条 差旅报销不得超过 200 元"),
            ),
        )
    }

    @Test
    fun `does not treat ordinary shared words as exact anchors`() {
        assertFalse(
            ExactAnchorMatcher.matches(
                "请介绍报销政策",
                source(text = "其他项目的报销政策说明"),
            ),
        )
    }

    private fun source(
        displayName: String = "handbook.txt",
        text: String = "content",
    ) = RetrievedChunk(
        chunkId = 1,
        displayName = displayName,
        locator = "line 1",
        text = text,
        score = 1f,
        documentId = "doc-1",
        tokenCount = 3,
    )
}
