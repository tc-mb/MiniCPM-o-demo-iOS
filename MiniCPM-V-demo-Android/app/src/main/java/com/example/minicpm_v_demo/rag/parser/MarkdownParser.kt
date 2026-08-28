package com.example.minicpm_v_demo.rag.parser

class MarkdownParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> = sequence {
        val headings = mutableListOf<String>()
        var codeStart = 0
        var codeFence: String? = null
        val code = StringBuilder()
        for (line in StrictTextSource(input).lines()) {
            val trimmed = line.text.trimStart()
            val fence = trimmed.takeWhile { it == '`' || it == '~' }
            if (codeFence != null) {
                if (fence.length >= 3 && fence.firstOrNull() == codeFence!!.first()) {
                    yield(ParsedBlock(code.toString().trimEnd(), BlockStructure.CODE, headings.path(), "line", "$codeStart-${line.number}"))
                    code.clear()
                    codeFence = null
                } else {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(line.text)
                }
                continue
            }
            if (fence.length >= 3) {
                codeFence = fence
                codeStart = line.number
                continue
            }
            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                val title = trimmed.drop(hashes + 1).trim()
                if (title.isNotEmpty()) {
                    while (headings.size >= hashes) headings.removeAt(headings.lastIndex)
                    while (headings.size < hashes - 1) headings.add("")
                    headings.add(title)
                    yield(ParsedBlock(title, BlockStructure.HEADING, headings.path(), "line", line.number.toString()))
                }
            } else if (line.text.isNotBlank()) {
                yield(ParsedBlock(line.text, BlockStructure.PARAGRAPH, headings.path(), "line", line.number.toString()))
            }
        }
        if (codeFence != null) fail(ParserError.MALFORMED_DOCUMENT)
    }

    private fun List<String>.path(): String? = filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" > ")
}
