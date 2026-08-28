package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk

data class RagGuardTextPair(
    val protectedText: String,
    val evidenceText: String,
)

object RagGuardInput {
    fun answerabilityPair(question: String, sources: List<RetrievedChunk>): RagGuardTextPair =
        buildPair(question, sources, answer = null)

    fun groundednessPair(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String,
    ): RagGuardTextPair = buildPair(question, sources, answer)

    fun assembleXlmrPair(
        protectedIds: LongArray,
        evidenceIds: LongArray,
        maxTokens: Int,
    ): LongArray {
        require(protectedIds.size >= 2 && evidenceIds.size >= 2)
        require(protectedIds.first() == evidenceIds.first())
        require(protectedIds.last() == evidenceIds.last())
        require(maxTokens >= protectedIds.size + 2) { "protected input exceeds token budget" }
        val availableEvidenceTail = maxTokens - protectedIds.size - 1
        val evidenceTail = evidenceIds.copyOfRange(1, evidenceIds.size).let { tail ->
            if (tail.size <= availableEvidenceTail) {
                tail
            } else {
                tail.copyOf(availableEvidenceTail).also { it[it.lastIndex] = evidenceIds.last() }
            }
        }
        return protectedIds + longArrayOf(protectedIds.last()) + evidenceTail
    }

    private fun buildPair(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String?,
    ): RagGuardTextPair {
        val cleanQuestion = question.trim()
        require(cleanQuestion.isNotEmpty())
        require(sources.size in 1..3)
        val evidence = sources.mapIndexed { index, source ->
            "evidence [S${index + 1}]: ${source.text.trim()}"
        }.joinToString("\n")
        require(evidence.isNotEmpty() && sources.all { it.text.isNotBlank() })
        val protectedParts = mutableListOf("query: $cleanQuestion")
        if (answer != null) {
            val cleanAnswer = answer.trim()
            require(cleanAnswer.isNotEmpty())
            protectedParts += "answer: $cleanAnswer"
        }
        return RagGuardTextPair(
            protectedText = protectedParts.joinToString("\n"),
            evidenceText = evidence,
        )
    }
}
