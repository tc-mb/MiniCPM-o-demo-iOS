package com.example.minicpm_v_demo.rag.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicParserTest {
    @Test
    fun `text parser accepts UTF-8 BOM and rejects malformed UTF-8`() {
        val bomText = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "第一行\nsecond".toByteArray()
        assertEquals(
            listOf("第一行", "second"),
            TextParser().parse(input(bomText)).map { it.text }.toList(),
        )

        val error = assertThrows(ParserException::class.java) {
            TextParser().parse(input(byteArrayOf(0xC3.toByte(), 0x28))).toList()
        }
        assertEquals(ParserError.INVALID_ENCODING, error.error)
    }

    @Test
    fun `parser stops before document character ceiling is exceeded`() {
        val error = assertThrows(ParserException::class.java) {
            TextParser().parse(input("12345\n67890".toByteArray(), maxChars = 8)).toList()
        }
        assertEquals(ParserError.TEXT_LIMIT_EXCEEDED, error.error)
    }

    @Test
    fun `CSV parser keeps quoted newlines inside one record`() {
        val blocks = CsvParser().parse(input("name,note\nAlice,\"line one\nline two\"\n".toByteArray())).toList()

        assertEquals(2, blocks.size)
        assertEquals("name | note", blocks[0].text)
        assertEquals("Alice | line one\nline two", blocks[1].text)
        assertEquals("row", blocks[1].locatorType)
        assertEquals("2", blocks[1].locatorValue)
    }

    @Test
    fun `Markdown parser preserves heading path and fenced code boundary`() {
        val markdown = "# Guide\nIntro\n## Setup\n```kotlin\nval ok = true\n```\n"
        val blocks = MarkdownParser().parse(input(markdown.toByteArray())).toList()

        assertEquals("Guide", blocks[0].text)
        assertEquals(BlockStructure.HEADING, blocks[0].structure)
        assertEquals("Guide", blocks[1].titlePath)
        assertEquals(BlockStructure.CODE, blocks.last().structure)
        assertTrue(blocks.last().text.contains("val ok = true"))
        assertEquals("Guide > Setup", blocks.last().titlePath)
    }

    @Test
    fun `HTML parser drops executable content and never resolves external links`() {
        val html = """
            <html><head><style>.x{display:none}</style><script>steal()</script></head>
            <body><h1>Local guide</h1><p>Read &amp; use.</p>
            <a href="https://example.invalid/private">external label</a></body></html>
        """.trimIndent()
        val text = HtmlParser().parse(input(html.toByteArray())).joinToString("\n") { it.text }

        assertTrue(text.contains("Local guide"))
        assertTrue(text.contains("Read & use."))
        assertTrue(text.contains("external label"))
        assertFalse(text.contains("steal"))
        assertFalse(text.contains("display:none"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun `parser registry selects supported local document formats`() {
        assertTrue(ParserRegistry.forDocument("notes.txt", "text/plain") is TextParser)
        assertTrue(ParserRegistry.forDocument("guide.md", "text/markdown") is MarkdownParser)
        assertTrue(ParserRegistry.forDocument("table.csv", "text/csv") is CsvParser)
        assertTrue(ParserRegistry.forDocument("page.html", "text/html") is HtmlParser)
        assertTrue(ParserRegistry.forDocument("report.pdf", "application/pdf") is PdfDocumentParser)
        assertThrows(ParserException::class.java) { ParserRegistry.forDocument("archive.bin", "application/octet-stream") }
    }

    @Test
    fun `parsed block codec round trips bounded records`() {
        val expected = listOf(
            ParsedBlock("Guide", BlockStructure.HEADING, "Guide", "line", "1"),
            ParsedBlock("hello 世界", BlockStructure.PARAGRAPH, "Guide", "line", "2"),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ParsedBlockCodec.write(expected.asSequence(), output)
        }.toByteArray()

        assertEquals(expected, ParsedBlockCodec.read(ByteArrayInputStream(bytes)).toList())
    }

    private fun input(bytes: ByteArray, maxChars: Int = 10_000) = ParserInput(
        input = ByteArrayInputStream(bytes),
        maxChars = maxChars,
    )
}
