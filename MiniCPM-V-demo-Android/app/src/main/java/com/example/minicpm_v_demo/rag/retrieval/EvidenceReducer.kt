package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.RagEvidenceReducer
import com.example.minicpm_v_demo.rag.chunk.CjkBigramEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Selects a query-relevant sentence/row and one adjacent unit on each side.
 * This is deliberately model-free so it adds no extra inference to a RAG turn.
 */
object SentenceWindowEvidenceReducer : RagEvidenceReducer {
    override fun reduce(question: String, sources: List<RetrievedChunk>): List<RetrievedChunk> {
        if (question.isBlank()) return emptyList()
        val questionTerms = terms(question)
        val questionAnchors = anchors(question)
        val seen = HashSet<String>()
        return sources.mapNotNull { source ->
            val units = splitUnits(source.text)
            if (units.isEmpty()) return@mapNotNull null
            val bestIndex = units.indices.maxWithOrNull(
                compareBy<Int> { score(units[it], questionTerms, questionAnchors) }.thenBy { it },
            ) ?: return@mapNotNull null
            val start = (bestIndex - ADJACENT_UNITS).coerceAtLeast(0)
            val end = (bestIndex + ADJACENT_UNITS).coerceAtMost(units.lastIndex)
            val reducedText = units.subList(start, end + 1).joinToString("\n").trim()
            val normalized = normalize(reducedText)
            if (normalized.isEmpty() || !seen.add(normalized)) return@mapNotNull null
            val fraction = reducedText.length.toDouble() / source.text.length.coerceAtLeast(1)
            source.copy(
                text = reducedText,
                tokenCount = (source.tokenCount * fraction).roundToInt().coerceAtLeast(1),
            )
        }
    }

    internal fun splitUnits(text: String): List<String> {
        val units = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            current.appendCodePoint(codePoint)
            if (codePoint in BOUNDARIES) {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(units::add)
                current.setLength(0)
                if (codePoint == '\r'.code && index + 1 < text.length && text[index + 1] == '\n') {
                    index++
                }
            }
            index += Character.charCount(codePoint)
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(units::add)
        return units
    }

    private fun score(unit: String, questionTerms: Set<String>, questionAnchors: Set<String>): Int {
        val unitTerms = terms(unit)
        val lexical = unitTerms.count(questionTerms::contains) * LEXICAL_WEIGHT
        val anchorReward = anchors(unit).count(questionAnchors::contains) * ANCHOR_WEIGHT
        return lexical + anchorReward
    }

    private fun terms(value: String): Set<String> =
        CjkBigramEncoder.encode(value.lowercase(Locale.ROOT))
            .split(' ')
            .filterTo(LinkedHashSet(), String::isNotBlank)

    private fun anchors(value: String): Set<String> =
        ANCHOR.findAll(value).map { it.value.lowercase(Locale.ROOT) }.toSet()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private val BOUNDARIES = setOf(
        '.'.code, '!'.code, '?'.code,
        '。'.code, '！'.code, '？'.code,
        ';'.code, '；'.code, '\n'.code, '\r'.code,
    )
    private val ANCHOR = Regex(
        "(?i)(?:\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b|" +
            "(?:[$¥￥€£]\\s*)?\\b\\d+(?:[.,]\\d+)?\\b|" +
            "第[〇零一二三四五六七八九十百千万两0-9]+[条章款])",
    )
    private val WHITESPACE = Regex("\\s+")
    private const val ADJACENT_UNITS = 1
    private const val LEXICAL_WEIGHT = 2
    private const val ANCHOR_WEIGHT = 5
}
