package com.example.minicpm_v_demo.rag.parser

import org.xml.sax.Attributes

class DocxParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> {
        val entries = SafeOoxmlReader.read(input) { it == DOCUMENT_XML }
        val xml = entries[DOCUMENT_XML] ?: fail(ParserError.MALFORMED_DOCUMENT)
        val handler = Handler(input.maxChars)
        SafeOoxmlReader.parseXml(xml, handler)
        return handler.blocks.asSequence()
    }

    private class Handler(private val maxChars: Int) : BoundedXmlHandler() {
        val blocks = mutableListOf<ParsedBlock>()
        private val text = StringBuilder()
        private val rowCells = mutableListOf<String>()
        private var paragraph = 0
        private var inText = false
        private var inTable = false
        private var inCell = false
        private var headingLevel = 0
        private var headingPath: String? = null
        private var totalChars = 0

        override fun onStart(name: String, attributes: Attributes) {
            when (name) {
                "tbl" -> inTable = true
                "tc" -> { inCell = true; text.setLength(0) }
                "p" -> { paragraph++; text.setLength(0); headingLevel = 0 }
                "pStyle" -> headingLevel = attributes.value("val")
                    ?.removePrefix("Heading")?.toIntOrNull()?.coerceIn(1, 9) ?: 0
                "t" -> inText = true
                "tab" -> text.append('\t')
                "br" -> text.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) text.append(ch, start, length)
        }

        override fun onEnd(name: String) {
            when (name) {
                "t" -> inText = false
                "tc" -> { rowCells += text.toString().trim(); text.setLength(0); inCell = false }
                "tr" -> emit(rowCells.joinToString(" | "), BlockStructure.TABLE_ROW).also { rowCells.clear() }
                "p" -> if (!inTable && !inCell) {
                    val value = text.toString().trim()
                    if (headingLevel > 0 && value.isNotEmpty()) {
                        headingPath = value
                        emit(value, BlockStructure.HEADING, value)
                    } else emit(value, BlockStructure.PARAGRAPH)
                }
                "tbl" -> inTable = false
            }
        }

        private fun emit(value: String, structure: BlockStructure, title: String? = headingPath) {
            if (value.isEmpty()) return
            totalChars += value.length
            if (totalChars > maxChars) fail(ParserError.TEXT_LIMIT_EXCEEDED)
            blocks += ParsedBlock(value, structure, title, "paragraph", paragraph.toString())
        }
    }

    companion object { private const val DOCUMENT_XML = "word/document.xml" }
}
