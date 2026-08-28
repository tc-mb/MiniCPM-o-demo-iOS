package com.example.minicpm_v_demo.rag.parser

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class CsvParser : DocumentParser {
    override fun parse(input: ParserInput): Sequence<ParsedBlock> = sequence {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var totalChars = 0
        try {
            BufferedReader(InputStreamReader(input.input, decoder)).use { reader ->
                val fields = mutableListOf<String>()
                val field = StringBuilder()
                var inQuotes = false
                var recordNumber = 1
                var firstChar = true
                while (true) {
                    if (!input.shouldContinue()) fail(ParserError.CANCELLED)
                    val value = reader.read()
                    if (value < 0) {
                        if (inQuotes) fail(ParserError.MALFORMED_DOCUMENT)
                        if (field.isNotEmpty() || fields.isNotEmpty()) {
                            fields.add(field.toString())
                            yield(record(fields, recordNumber))
                        }
                        break
                    }
                    var char = value.toChar()
                    if (firstChar) {
                        firstChar = false
                        if (char == '\uFEFF') continue
                    }
                    totalChars++
                    if (totalChars > input.maxChars) fail(ParserError.TEXT_LIMIT_EXCEEDED)
                    when {
                        char == '"' && inQuotes -> {
                            reader.mark(1)
                            if (reader.read() == '"'.code) {
                                field.append('"')
                                totalChars++
                            } else {
                                reader.reset()
                                inQuotes = false
                            }
                        }
                        char == '"' && field.isEmpty() -> inQuotes = true
                        char == ',' && !inQuotes -> {
                            fields.add(field.toString())
                            field.clear()
                        }
                        (char == '\n' || char == '\r') && !inQuotes -> {
                            if (char == '\r') {
                                reader.mark(1)
                                if (reader.read() != '\n'.code) reader.reset() else totalChars++
                            }
                            fields.add(field.toString())
                            field.clear()
                            yield(record(fields, recordNumber++))
                            fields.clear()
                        }
                        else -> {
                            field.append(char)
                            if (field.length > MAX_FIELD_CHARS) fail(ParserError.RECORD_TOO_LARGE)
                        }
                    }
                }
            }
        } catch (error: java.nio.charset.CharacterCodingException) {
            fail(ParserError.INVALID_ENCODING)
        }
    }

    private fun record(fields: List<String>, number: Int) = ParsedBlock(
        fields.joinToString(" | "),
        BlockStructure.TABLE_ROW,
        locatorType = "row",
        locatorValue = number.toString(),
    )

    private companion object {
        const val MAX_FIELD_CHARS = 1_000_000
    }
}
