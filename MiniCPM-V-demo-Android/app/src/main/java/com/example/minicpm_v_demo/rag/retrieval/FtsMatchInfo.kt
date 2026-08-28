package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.chunk.CjkBigramEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

class FtsMatchInfoFormatException : IllegalArgumentException("Invalid FTS matchinfo payload")

class FtsMatchInfo private constructor(
    private val phraseCount: Int,
    private val columnCount: Int,
    private val documentCount: Int,
    private val averageColumnLengths: IntArray,
    private val currentColumnLengths: IntArray,
    private val phraseColumnStats: IntArray,
) {
    fun bm25(k1: Double = 1.2, b: Double = 0.75): Double {
        require(k1 > 0.0 && k1.isFinite())
        require(b in 0.0..1.0 && b.isFinite())
        var score = 0.0
        for (phrase in 0 until phraseCount) {
            for (column in 0 until columnCount) {
                val statOffset = (phrase * columnCount + column) * STATS_PER_CELL
                val termFrequency = phraseColumnStats[statOffset]
                if (termFrequency == 0) continue
                val documentFrequency = phraseColumnStats[statOffset + 2]
                val averageLength = averageColumnLengths[column]
                if (documentFrequency <= 0 || averageLength <= 0) throw FtsMatchInfoFormatException()
                val idf = ln(
                    1.0 +
                        (documentCount - documentFrequency + 0.5) /
                        (documentFrequency + 0.5),
                )
                val normalizedLength = currentColumnLengths[column].toDouble() / averageLength
                val numerator = termFrequency * (k1 + 1.0)
                val denominator = termFrequency + k1 * (1.0 - b + b * normalizedLength)
                score += idf * numerator / denominator
            }
        }
        return score
    }

    fun matchedPhraseRatio(): Double {
        var matchedPhrases = 0
        for (phrase in 0 until phraseCount) {
            val present = (0 until columnCount).any { column ->
                val statOffset = (phrase * columnCount + column) * STATS_PER_CELL
                phraseColumnStats[statOffset] > 0
            }
            if (present) matchedPhrases++
        }
        return matchedPhrases.toDouble() / phraseCount
    }

    companion object {
        private const val STATS_PER_CELL = 3
        private const val MAX_PHRASES = 64
        private const val MAX_COLUMNS = 16
        private const val HEADER_INTS = 3

        fun parse(blob: ByteArray): FtsMatchInfo {
            if (blob.size < HEADER_INTS * Int.SIZE_BYTES || blob.size % Int.SIZE_BYTES != 0) {
                throw FtsMatchInfoFormatException()
            }
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val phraseCount = buffer.int
            val columnCount = buffer.int
            val documentCount = buffer.int
            if (phraseCount !in 1..MAX_PHRASES || columnCount !in 1..MAX_COLUMNS || documentCount <= 0) {
                throw FtsMatchInfoFormatException()
            }
            val expectedInts = HEADER_INTS.toLong() +
                columnCount.toLong() * 2L +
                phraseCount.toLong() * columnCount.toLong() * STATS_PER_CELL
            if (expectedInts != blob.size.toLong() / Int.SIZE_BYTES) throw FtsMatchInfoFormatException()

            val averageColumnLengths = IntArray(columnCount) { readNonNegative(buffer) }
            val currentColumnLengths = IntArray(columnCount) { readNonNegative(buffer) }
            val phraseColumnStats = IntArray(phraseCount * columnCount * STATS_PER_CELL) {
                readNonNegative(buffer)
            }
            for (cell in 0 until phraseCount * columnCount) {
                val offset = cell * STATS_PER_CELL
                val rowsContainingPhrase = phraseColumnStats[offset + 2]
                if (rowsContainingPhrase > documentCount) throw FtsMatchInfoFormatException()
            }
            return FtsMatchInfo(
                phraseCount,
                columnCount,
                documentCount,
                averageColumnLengths,
                currentColumnLengths,
                phraseColumnStats,
            )
        }

        private fun readNonNegative(buffer: ByteBuffer): Int =
            buffer.int.takeIf { it >= 0 } ?: throw FtsMatchInfoFormatException()
    }
}

object SafeFtsQuery {
    private const val MAX_QUERY_CODE_POINTS = 4_096
    private const val MAX_TERMS = 32
    private const val MAX_TERM_CODE_POINTS = 64
    private const val MAX_PHRASE_TERMS = 8

    fun build(input: String): String? {
        val bounded = input.takeCodePoints(MAX_QUERY_CODE_POINTS)
        val operands = LinkedHashSet<String>()
        var cursor = 0
        while (cursor < bounded.length && operands.size < MAX_TERMS) {
            val openingQuote = bounded.indexOf('"', cursor)
            if (openingQuote < 0) {
                addWords(bounded.substring(cursor), operands)
                break
            }
            addWords(bounded.substring(cursor, openingQuote), operands)
            val closingQuote = bounded.indexOf('"', openingQuote + 1)
            if (closingQuote < 0) {
                addWords(bounded.substring(openingQuote + 1), operands)
                break
            }
            val phrase = encodedTerms(bounded.substring(openingQuote + 1, closingQuote))
                .take(MAX_PHRASE_TERMS)
                .joinToString(" ")
            if (phrase.isNotEmpty()) operands += phrase
            cursor = closingQuote + 1
        }
        return operands.take(MAX_TERMS)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(" OR ") { operand -> "\"$operand\"" }
    }

    private fun addWords(value: String, target: LinkedHashSet<String>) {
        for (term in encodedTerms(value)) {
            if (target.size >= MAX_TERMS) return
            target += term
        }
    }

    private fun encodedTerms(value: String): List<String> = CjkBigramEncoder.encode(value)
        .split(' ')
        .asSequence()
        .filter(String::isNotBlank)
        .map { it.takeCodePoints(MAX_TERM_CODE_POINTS) }
        .filter(String::isNotEmpty)
        .toList()

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        val count = codePointCount(0, length)
        return if (count <= maxCodePoints) this else substring(0, offsetByCodePoints(0, maxCodePoints))
    }
}
