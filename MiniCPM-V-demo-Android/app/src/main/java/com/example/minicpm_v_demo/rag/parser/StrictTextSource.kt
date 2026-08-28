package com.example.minicpm_v_demo.rag.parser

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class StrictTextSource(private val input: ParserInput) {
    private var emittedChars = 0

    fun lines(): Sequence<LocatedLine> = sequence {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            BufferedReader(InputStreamReader(input.input, decoder)).use { reader ->
                var lineNumber = 0
                while (true) {
                    ensureActive()
                    val raw = reader.readLine() ?: break
                    lineNumber++
                    val line = if (lineNumber == 1) raw.removePrefix("\uFEFF") else raw
                    account(line.length)
                    yield(LocatedLine(lineNumber, line))
                }
            }
        } catch (error: java.nio.charset.CharacterCodingException) {
            fail(ParserError.INVALID_ENCODING)
        }
    }

    fun account(count: Int) {
        if (count < 0 || emittedChars > input.maxChars - count) fail(ParserError.TEXT_LIMIT_EXCEEDED)
        emittedChars += count
    }

    fun ensureActive() {
        if (!input.shouldContinue()) fail(ParserError.CANCELLED)
    }
}

internal data class LocatedLine(val number: Int, val text: String)
