package com.example.minicpm_v_demo.rag.prompt

import com.example.minicpm_v_demo.rag.RagEvidenceBudget
import com.example.minicpm_v_demo.rag.RagEvidenceBudgeter
import com.example.minicpm_v_demo.rag.RagPromptTokenCounter
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk

class RagContextBudgeter(
    private val preferredEvidenceTokens: Int = 768,
    private val hardEvidenceTokens: Int = 900,
    private val maxTokensPerSource: Int = 320,
    private val minimumUsableBudget: Int = 128,
    private val answerReserveTokens: Int = 768,
    private val protocolAndQuestionReserveTokens: Int = 256,
) : RagEvidenceBudgeter {
    init {
        require(preferredEvidenceTokens in 1..hardEvidenceTokens)
        require(hardEvidenceTokens in 1..4_096)
        require(maxTokensPerSource in 1..hardEvidenceTokens)
        require(minimumUsableBudget in 1..preferredEvidenceTokens)
        require(answerReserveTokens >= 0 && protocolAndQuestionReserveTokens >= 0)
    }

    override suspend fun budget(
        question: String,
        sources: List<RetrievedChunk>,
        tokenCounter: RagPromptTokenCounter?,
    ): RagEvidenceBudget {
        if (question.isBlank() || sources.isEmpty() || tokenCounter == null) {
            return RagEvidenceBudget(emptyList(), 0)
        }
        val availableForEvidence = (
            tokenCounter.remainingContextTokens() -
                answerReserveTokens -
                protocolAndQuestionReserveTokens
            ).coerceAtMost(preferredEvidenceTokens)
            .coerceAtMost(hardEvidenceTokens)
        if (availableForEvidence < minimumUsableBudget) {
            return RagEvidenceBudget(emptyList(), 0)
        }

        val selected = ArrayList<RetrievedChunk>()
        var used = 0
        for (source in sources) {
            val sourceBudget = minOf(maxTokensPerSource, availableForEvidence - used)
            if (sourceBudget <= 0) break
            val boundedText = truncateToTokens(source.text, sourceBudget, tokenCounter)
            if (boundedText.isBlank()) continue
            val count = tokenCounter.count(boundedText)
            check(count in 1..sourceBudget) { "Token counter returned an invalid bounded result" }
            selected += source.copy(text = boundedText, tokenCount = count)
            used += count
        }
        return RagEvidenceBudget(selected, used)
    }

    private suspend fun truncateToTokens(
        text: String,
        maxTokens: Int,
        tokenCounter: RagPromptTokenCounter,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val fullCount = tokenCounter.count(trimmed)
        require(fullCount >= 0) { "Token count must not be negative" }
        if (fullCount <= maxTokens) return trimmed

        val codePoints = trimmed.codePoints().toArray()
        var low = 0
        var high = codePoints.size
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val candidate = String(codePoints, 0, middle).trimEnd()
            if (candidate.isNotEmpty() && tokenCounter.count(candidate) <= maxTokens) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return if (low == 0) "" else String(codePoints, 0, low).trimEnd()
    }
}
