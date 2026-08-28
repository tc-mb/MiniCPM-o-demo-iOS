package com.example.minicpm_v_demo.rag.parser

class TextParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> = sequence {
        for (line in StrictTextSource(input).lines()) {
            if (line.text.isNotBlank()) {
                yield(ParsedBlock(line.text, BlockStructure.PARAGRAPH, locatorType = "line", locatorValue = line.number.toString()))
            }
        }
    }
}
