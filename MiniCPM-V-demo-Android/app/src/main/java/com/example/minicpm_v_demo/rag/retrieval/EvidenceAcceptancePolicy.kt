package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.RagEvidenceAcceptancePolicy
import com.example.minicpm_v_demo.rag.chunk.CjkBigramEncoder
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import java.util.Locale

data class RetrievalCalibrationKey(
    val embeddingModelSha256: String,
    val corpusVersion: Int,
) {
    init {
        require(
            embeddingModelSha256.length == 64 &&
                embeddingModelSha256.all { it in '0'..'9' || it in 'a'..'f' },
        )
        require(corpusVersion > 0)
    }
}

data class RetrievalCalibrationProfile(
    val key: RetrievalCalibrationKey,
    val highDenseThreshold: Float,
    val standardDenseThreshold: Float,
    val minimumLexicalCoverage: Double,
) {
    init {
        require(highDenseThreshold.isFinite() && highDenseThreshold in -1f..1f)
        require(standardDenseThreshold.isFinite() && standardDenseThreshold in -1f..1f)
        require(highDenseThreshold >= standardDenseThreshold)
        require(minimumLexicalCoverage.isFinite() && minimumLexicalCoverage in 0.0..1.0)
    }
}

object CurrentRetrievalCalibration {
    val key = RetrievalCalibrationKey(
        embeddingModelSha256 = E5ModelSpec.PINNED.files.getValue("model.int8.onnx"),
        corpusVersion = 1,
    )

    // Fail closed until the corpus-independent lexical-coverage profile has been
    // calibrated and verified on the real E5 + Room FTS device path.
    val profile: RetrievalCalibrationProfile? = null
}

class CalibratedEvidenceAcceptancePolicy(
    private val profile: RetrievalCalibrationProfile?,
) : RagEvidenceAcceptancePolicy {
    override suspend fun accept(
        question: String,
        sources: List<RetrievedChunk>,
    ): List<RetrievedChunk> = accept(sources)

    fun accept(sources: List<RetrievedChunk>): List<RetrievedChunk> = sources.filter { source ->
        if (!source.isStructurallyValid()) return@filter false
        if (source.exactAnchor) return@filter true
        val activeProfile = profile ?: return@filter false
        if (source.calibrationKey != activeProfile.key) return@filter false
        val denseScore = source.denseScore ?: return@filter false
        denseScore >= activeProfile.highDenseThreshold ||
            (
                denseScore >= activeProfile.standardDenseThreshold &&
                    source.lexicalCoverage?.let { it >= activeProfile.minimumLexicalCoverage } == true
            )
    }

    private fun RetrievedChunk.isStructurallyValid(): Boolean =
        chunkId > 0 &&
            documentId.isNotBlank() &&
            text.isNotBlank() &&
            score.isFinite() &&
            tokenCount >= 0 &&
            denseScore?.isFinite() != false &&
            lexicalScore?.isFinite() != false &&
            lexicalCoverage?.let { it.isFinite() && it in 0.0..1.0 } != false
}

object ExactAnchorMatcher {
    fun matches(question: String, source: RetrievedChunk): Boolean {
        if (question.isBlank()) return false
        val normalizedQuestion = question.lowercase(Locale.ROOT)
        val normalizedFileName = source.displayName.trim().lowercase(Locale.ROOT)
        if (normalizedFileName.length >= 3 && normalizedQuestion.contains(normalizedFileName)) return true

        val sourceText = listOf(source.text, source.locator, source.displayName).joinToString(" ")
        val sourceTerms = encodedTerms(sourceText).toHashSet()
        if (encodedTerms(question).any { it.isStrongIdentifier() && it in sourceTerms }) return true

        val sourceClauses = clauseAnchors(sourceText)
        return clauseAnchors(question).any(sourceClauses::contains)
    }

    private fun encodedTerms(value: String): List<String> =
        CjkBigramEncoder.encode(value).split(' ').filter(String::isNotBlank)

    private fun String.isStrongIdentifier(): Boolean {
        val hasDigit = any(Char::isDigit)
        val hasLetterOrSeparator = any(Char::isLetter) || '-' in this || '_' in this
        return hasDigit && length >= 4 && hasLetterOrSeparator
    }

    private fun clauseAnchors(value: String): Set<String> {
        val points = value.codePoints().toArray()
        val anchors = LinkedHashSet<String>()
        for (start in points.indices) {
            if (points[start] != '第'.code) continue
            val endLimit = minOf(points.lastIndex, start + MAX_CLAUSE_CODE_POINTS - 1)
            for (end in start + 2..endLimit) {
                if (points[end] !in CLAUSE_SUFFIXES) continue
                if ((start + 1 until end).all { points[it].isClauseOrdinal() }) {
                    anchors += String(points, start, end - start + 1)
                }
                break
            }
        }
        return anchors
    }

    private fun Int.isClauseOrdinal(): Boolean =
        Character.isDigit(this) || this in CHINESE_NUMERAL_CODE_POINTS

    private val CLAUSE_SUFFIXES = setOf('条'.code, '章'.code, '款'.code)
    private val CHINESE_NUMERAL_CODE_POINTS = "〇零一二三四五六七八九十百千万两".codePoints().toArray().toSet()
    private const val MAX_CLAUSE_CODE_POINTS = 16
}
