package com.example.minicpm_v_demo.rag.chunk

import com.example.minicpm_v_demo.rag.embed.E5Tokenizer
import com.example.minicpm_v_demo.rag.embed.TokenSpan
import com.example.minicpm_v_demo.rag.parser.BlockStructure
import com.example.minicpm_v_demo.rag.parser.ParsedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {
    private val tokenizer = CodePointTokenizer("test-e5", "sha256:test-tokenizer")

    @Test
    fun `same input and version produce stable ordered chunks`() {
        val blocks = sequenceOf(
            heading("章一"),
            paragraph("甲".repeat(8), "line", "2"),
            paragraph("乙".repeat(8), "line", "3"),
            paragraph("丙".repeat(8), "line", "4"),
        )
        val config = ChunkConfig(targetTokens = 12, minTokens = 4, maxTokens = 16, overlapTokens = 3, version = 7)

        val first = DocumentChunker(tokenizer).chunk(blocks, config).toList()
        val second = DocumentChunker(tokenizer).chunk(sequenceOf(
            heading("章一"), paragraph("甲".repeat(8), "line", "2"),
            paragraph("乙".repeat(8), "line", "3"), paragraph("丙".repeat(8), "line", "4"),
        ), config).toList()

        assertEquals(first, second)
        assertEquals(first.indices.toList(), first.map { it.ordinal })
        assertTrue(first.all { it.titlePath == "章一" && it.tokenCount <= config.maxTokens })
        assertTrue(first.zipWithNext().all { (left, right) ->
            tokenizer.tokenTexts(left.text).takeLast(3) == tokenizer.tokenTexts(right.text).take(3)
        })
    }

    @Test
    fun `chunker version changes hashes without changing visible text`() {
        val block = sequenceOf(paragraph("版本稳定文本".repeat(3)))
        val first = DocumentChunker(tokenizer).chunk(block, config(version = 1)).single()
        val second = DocumentChunker(tokenizer).chunk(
            sequenceOf(paragraph("版本稳定文本".repeat(3))),
            config(version = 2),
        ).single()

        assertEquals(first.text, second.text)
        assertNotEquals(first.contentSha256, second.contentSha256)
    }

    @Test
    fun `long content splits only at tokenizer boundaries and keeps emoji intact`() {
        val text = "开😀始。" + "很长句子；".repeat(8) + "结束👍🏽"
        val chunks = DocumentChunker(tokenizer).chunk(
            sequenceOf(paragraph(text)),
            config(target = 12, min = 4, max = 16, overlap = 2),
        ).toList()

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.tokenCount <= 16 })
        assertFalse(chunks.any { it.text.contains('\uFFFD') })
        assertTrue(chunks.joinToString("").contains("😀"))
    }

    @Test
    fun `page boundaries are never merged`() {
        val chunks = DocumentChunker(tokenizer).chunk(
            sequenceOf(
                paragraph("第一页内容", "page", "1"),
                paragraph("第二页内容", "page", "2"),
            ),
            config(target = 30, min = 1, max = 40, overlap = 0),
        ).toList()

        assertEquals(listOf("1", "2"), chunks.map { it.locatorValue })
        assertFalse(chunks.any { it.text.contains("第一页") && it.text.contains("第二页") })
    }

    @Test
    fun `table header is repeated when rows span multiple chunks`() {
        val blocks = sequenceOf(
            table("项目 | 金额", "1"),
            table("设备 | 100", "2"),
            table("服务 | 200", "3"),
            table("合计 | 300", "4"),
        )

        val chunks = DocumentChunker(tokenizer).chunk(
            blocks,
            config(target = 12, min = 1, max = 18, overlap = 0),
        ).toList()

        assertTrue(chunks.size > 1)
        assertTrue(chunks.drop(1).all { it.text.startsWith("项目 | 金额") })
    }

    @Test
    fun `taking first chunk does not consume the complete document`() {
        var consumed = 0
        val blocks = sequence {
            repeat(10_000) { index ->
                consumed++
                yield(paragraph("段落$index".repeat(8), "line", index.toString()))
            }
        }

        DocumentChunker(tokenizer).chunk(
            blocks,
            config(target = 16, min = 2, max = 20, overlap = 2),
        ).first()

        assertTrue("consumed=$consumed", consumed < 100)
    }

    @Test
    fun `taking first table chunk does not consume the complete table`() {
        var consumed = 0
        val blocks = sequence {
            repeat(10_000) { index ->
                consumed++
                yield(table("row-$index | ${"value".repeat(5)}", index.toString()))
            }
        }

        DocumentChunker(tokenizer).chunk(
            blocks,
            config(target = 30, min = 4, max = 36, overlap = 0),
        ).first()

        assertTrue("consumed=$consumed", consumed < 100)
    }

    @Test
    fun `split avoids a final chunk smaller than configured minimum`() {
        val chunks = DocumentChunker(tokenizer).chunk(
            sequenceOf(paragraph("abcdefghijklm")),
            config(target = 10, min = 4, max = 12, overlap = 0),
        ).toList()

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.tokenCount in 4..12 })
        assertEquals("abcdefghijklm", chunks.joinToString("") { it.text })
    }

    private fun config(
        target: Int = 40,
        min: Int = 1,
        max: Int = 48,
        overlap: Int = 4,
        version: Int = 1,
    ) = ChunkConfig(target, min, max, overlap, titleMaxTokens = 12, version = version)

    private fun heading(text: String) = ParsedBlock(text, BlockStructure.HEADING, text, "line", "1")
    private fun paragraph(text: String, locatorType: String = "line", locator: String = "1") =
        ParsedBlock(text, BlockStructure.PARAGRAPH, null, locatorType, locator)
    private fun table(text: String, locator: String) =
        ParsedBlock(text, BlockStructure.TABLE_ROW, null, "row", locator)

    private class CodePointTokenizer(
        override val modelId: String,
        override val tokenizerSha256: String,
    ) : E5Tokenizer {
        override val modelSha256: String = "c".repeat(64)
        override fun tokenSpans(text: String): List<TokenSpan> {
            val spans = mutableListOf<TokenSpan>()
            var index = 0
            while (index < text.length) {
                val end = index + Character.charCount(text.codePointAt(index))
                spans += TokenSpan(index, end)
                index = end
            }
            return spans
        }

        fun tokenTexts(text: String) = tokenSpans(text).map { text.substring(it.start, it.endExclusive) }
    }
}
