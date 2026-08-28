package com.example.minicpm_v_demo.rag.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OoxmlSecurityTest {
    @Test
    fun `registry selects PDF and OOXML parsers`() {
        assertTrue(ParserRegistry.forDocument("report.pdf", "application/pdf") is PdfDocumentParser)
        assertTrue(ParserRegistry.forDocument("contract.docx", DOCX_MIME) is DocxParser)
        assertTrue(ParserRegistry.forDocument("budget.xlsx", XLSX_MIME) is XlsxParser)
        assertTrue(ParserRegistry.forDocument("briefing.pptx", PPTX_MIME) is PptxParser)
    }

    @Test
    fun `reader rejects zip slip before parsing content`() {
        val error = assertThrows(ParserException::class.java) {
            DocxParser().parse(input(zip("../word/document.xml" to "<document/>"))).toList()
        }

        assertEquals(ParserError.ZIP_SLIP, error.error)
    }

    @Test
    fun `reader rejects highly compressed OOXML entries`() {
        val repetitive = "A".repeat(200_000)
        val error = assertThrows(ParserException::class.java) {
            XlsxParser().parse(input(zip("xl/worksheets/sheet1.xml" to repetitive))).toList()
        }

        assertEquals(ParserError.ZIP_BOMB_RISK, error.error)
    }

    @Test
    fun `reader counts compressed payloads even when the entry is not parsed`() {
        val error = assertThrows(ParserException::class.java) {
            DocxParser().parse(input(zip(
                "customXml/hidden.bin" to "Z".repeat(200_000),
                "word/document.xml" to "<w:document xmlns:w=\"urn:w\"><w:body/></w:document>",
            ))).toList()
        }

        assertEquals(ParserError.ZIP_BOMB_RISK, error.error)
    }

    @Test
    fun `reader rejects DTD and external entities without exposing payload`() {
        val xxe = """
            <!DOCTYPE document [<!ENTITY secret SYSTEM "file:///private/secret.txt">]>
            <w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>
        """.trimIndent()
        val error = assertThrows(ParserException::class.java) {
            DocxParser().parse(input(zip("word/document.xml" to xxe))).toList()
        }

        assertEquals(ParserError.UNSAFE_XML, error.error)
        assertFalse(error.message.orEmpty().contains("secret.txt"))
    }

    @Test
    fun `reader rejects XML deeper than the configured ceiling`() {
        val deep = buildString {
            repeat(130) { append("<a>") }
            append("text")
            repeat(130) { append("</a>") }
        }
        val error = assertThrows(ParserException::class.java) {
            PptxParser().parse(input(zip("ppt/slides/slide1.xml" to deep))).toList()
        }

        assertEquals(ParserError.XML_DEPTH_LIMIT, error.error)
    }

    @Test
    fun `DOCX parser preserves paragraphs tables and heading path`() {
        val xml = """
            <w:document xmlns:w="urn:w"><w:body>
              <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Policy</w:t></w:r></w:p>
              <w:p><w:r><w:t>Applies locally.</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>
        """.trimIndent()

        val blocks = DocxParser().parse(input(zip("word/document.xml" to xml))).toList()

        assertEquals(BlockStructure.HEADING, blocks[0].structure)
        assertEquals("Policy", blocks[1].titlePath)
        assertTrue(blocks.any { it.structure == BlockStructure.TABLE_ROW && it.text == "Name | Value" })
    }

    @Test
    fun `XLSX parser resolves shared strings and keeps a cell range locator`() {
        val shared = """
            <sst xmlns="urn:x"><si><t>Item</t></si><si><t>Tea</t></si></sst>
        """.trimIndent()
        val sheet = """
            <worksheet xmlns="urn:x"><sheetData><row r="2">
              <c r="A2" t="s"><v>0</v></c><c r="B2" t="s"><v>1</v></c>
            </row></sheetData></worksheet>
        """.trimIndent()

        val blocks = XlsxParser().parse(input(zip(
            "xl/sharedStrings.xml" to shared,
            "xl/worksheets/sheet1.xml" to sheet,
        ))).toList()

        assertEquals("Item | Tea", blocks.single().text)
        assertEquals("cell-range", blocks.single().locatorType)
        assertEquals("sheet1!A2:B2", blocks.single().locatorValue)
    }

    @Test
    fun `PPTX parser emits one ordered block per slide`() {
        val slide = """
            <p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:cSld><p:spTree>
              <p:sp><p:txBody><a:p><a:r><a:t>Quarterly</a:t></a:r></a:p></p:txBody></p:sp>
              <p:sp><p:txBody><a:p><a:r><a:t>Revenue grew</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>
        """.trimIndent()

        val block = PptxParser().parse(input(zip("ppt/slides/slide2.xml" to slide))).single()

        assertEquals("Quarterly\nRevenue grew", block.text)
        assertEquals("slide", block.locatorType)
        assertEquals("2", block.locatorValue)
    }

    private fun input(bytes: ByteArray) = ParserInput(ByteArrayInputStream(bytes))

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    companion object {
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
