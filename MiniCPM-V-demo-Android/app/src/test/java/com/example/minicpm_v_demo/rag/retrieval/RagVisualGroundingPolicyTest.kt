package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.VisualResponseDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class RagVisualGroundingPolicyTest {
    @Test
    fun `valid same-sentence knowledge-base citation can override only the visual guard`() {
        assertEquals(
            VisualResponseDecision.ALLOW,
            RagVisualGroundingPolicy.resolve(
                baseline = VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
                response = "根据资料，图片中显示的是设备接线图 [S1]。",
                sources = listOf(source()),
            ),
        )
    }

    @Test
    fun `missing or forged citation cannot override the visual guard`() {
        listOf(
            "根据资料，图片中显示的是设备接线图。",
            "根据资料，图片中显示的是设备接线图 [S99]。",
        ).forEach { response ->
            assertEquals(
                VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
                RagVisualGroundingPolicy.resolve(
                    baseline = VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
                    response = response,
                    sources = listOf(source()),
                ),
            )
        }
    }

    @Test
    fun `every visual assertion sentence must carry a valid citation`() {
        assertEquals(
            VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
            RagVisualGroundingPolicy.resolve(
                baseline = VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
                response = "资料中的图片显示设备接线图 [S1]。右侧看起来还有一个开关。",
                sources = listOf(source()),
            ),
        )
    }

    @Test
    fun `knowledge-base evidence never changes an already allowed visual decision`() {
        assertEquals(
            VisualResponseDecision.ALLOW,
            RagVisualGroundingPolicy.resolve(
                baseline = VisualResponseDecision.ALLOW,
                response = "这是普通文本回答。",
                sources = emptyList(),
            ),
        )
    }

    private fun source() = RetrievedChunk(
        chunkId = 7,
        displayName = "设备说明书.txt",
        locator = "第 3 节",
        text = "图片中显示设备接线图。",
        score = 0.9f,
        documentId = "doc-7",
    )
}
