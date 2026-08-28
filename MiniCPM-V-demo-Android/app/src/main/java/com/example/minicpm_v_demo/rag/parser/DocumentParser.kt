package com.example.minicpm_v_demo.rag.parser

import com.example.minicpm_v_demo.rag.config.RagLimits
import java.io.InputStream

data class ParserInput(
    val input: InputStream,
    val maxChars: Int = RagLimits.MAX_TEXT_CHARS_PER_DOCUMENT,
    val shouldContinue: () -> Boolean = { true },
)

interface DocumentParser {
    fun parse(input: ParserInput): Sequence<ParsedBlock>
}

enum class ParserError {
    INVALID_ENCODING,
    TEXT_LIMIT_EXCEEDED,
    RECORD_TOO_LARGE,
    MALFORMED_DOCUMENT,
    UNSUPPORTED_FORMAT,
    ZIP_SLIP,
    ZIP_BOMB_RISK,
    UNSAFE_XML,
    XML_DEPTH_LIMIT,
    PDF_PAGE_LIMIT,
    PDF_CORRUPT,
    OCR_FAILED,
    CANCELLED,
}

class ParserException(val error: ParserError) : Exception(error.name)

internal fun fail(error: ParserError): Nothing = throw ParserException(error)
