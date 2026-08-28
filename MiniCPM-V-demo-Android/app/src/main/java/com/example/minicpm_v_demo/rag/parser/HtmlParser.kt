package com.example.minicpm_v_demo.rag.parser

class HtmlParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> = sequence {
        val source = StrictTextSource(input)
        val output = StringBuilder()
        var skippedTag: String? = null
        for (line in source.lines()) {
            var index = 0
            while (index < line.text.length) {
                source.ensureActive()
                val open = line.text.indexOf('<', index)
                if (open < 0) {
                    if (skippedTag == null) output.append(line.text.substring(index)).append(' ')
                    break
                }
                if (skippedTag == null) output.append(line.text.substring(index, open)).append(' ')
                val close = line.text.indexOf('>', open + 1)
                if (close < 0) fail(ParserError.MALFORMED_DOCUMENT)
                val rawTag = line.text.substring(open + 1, close).trim()
                val tagName = rawTag.removePrefix("/").substringBefore(' ').lowercase()
                if (!rawTag.startsWith("/") && tagName in SKIPPED_TAGS) skippedTag = tagName
                if (rawTag.startsWith("/") && tagName == skippedTag) skippedTag = null
                if (skippedTag == null && tagName in BLOCK_TAGS) output.append('\n')
                index = close + 1
            }
        }
        val normalized = decodeEntities(output.toString())
            .lineSequence()
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
        var ordinal = 0
        for (text in normalized) {
            ordinal++
            yield(ParsedBlock(text, BlockStructure.PARAGRAPH, locatorType = "block", locatorValue = ordinal.toString()))
        }
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private companion object {
        val SKIPPED_TAGS = setOf("script", "style", "noscript", "iframe", "object")
        val BLOCK_TAGS = setOf("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr")
        val WHITESPACE = Regex("[\\t\\x0B\\f ]+")
    }
}
