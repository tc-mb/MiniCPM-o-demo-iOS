package com.example.minicpm_v_demo.rag.parser

import org.xml.sax.Attributes

class PptxParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> {
        val entries = SafeOoxmlReader.read(input) { it.startsWith(SLIDE_PREFIX) && it.endsWith(".xml") }
        if (entries.isEmpty()) fail(ParserError.MALFORMED_DOCUMENT)
        val blocks = mutableListOf<ParsedBlock>()
        var totalChars = 0
        entries.entries.sortedBy { slideNumber(it.key) }.forEach { (name, bytes) ->
            val handler = SlideHandler()
            SafeOoxmlReader.parseXml(bytes, handler)
            val text = handler.paragraphs.filter { it.isNotBlank() }.joinToString("\n")
            if (text.isNotEmpty()) {
                totalChars += text.length
                if (totalChars > input.maxChars) fail(ParserError.TEXT_LIMIT_EXCEEDED)
                blocks += ParsedBlock(text, BlockStructure.PARAGRAPH, handler.paragraphs.firstOrNull(), "slide", slideNumber(name).toString())
            }
        }
        return blocks.asSequence()
    }

    private class SlideHandler : BoundedXmlHandler() {
        val paragraphs = mutableListOf<String>()
        private val paragraph = StringBuilder()
        private var inText = false
        override fun onStart(name: String, attributes: Attributes) {
            if (name == "p") paragraph.setLength(0)
            if (name == "t") inText = true
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) paragraph.append(ch, start, length)
        }
        override fun onEnd(name: String) {
            if (name == "t") inText = false
            if (name == "p" && paragraph.isNotBlank()) paragraphs += paragraph.toString().trim()
        }
    }

    companion object {
        private const val SLIDE_PREFIX = "ppt/slides/slide"
        private fun slideNumber(name: String): Int = name.substringAfter(SLIDE_PREFIX).substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
    }
}
