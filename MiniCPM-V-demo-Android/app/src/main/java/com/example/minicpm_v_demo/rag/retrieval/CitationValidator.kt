package com.example.minicpm_v_demo.rag.retrieval

data class ValidatedCitation(
    val sourceId: String,
    val source: RetrievedChunk,
)

object CitationValidator {
    private val citationPattern = Regex("(?<![\\p{L}\\p{N}_])\\[S([1-9]\\d*)](?![\\p{L}\\p{N}_])")

    fun validate(answer: String, candidates: List<RetrievedChunk>): List<ValidatedCitation> {
        if (answer.isBlank() || candidates.isEmpty()) return emptyList()
        val seen = HashSet<Int>()
        return citationPattern.findAll(answer).mapNotNull { match ->
            val ordinal = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (ordinal !in 1..candidates.size || !seen.add(ordinal)) return@mapNotNull null
            ValidatedCitation("S$ordinal", candidates[ordinal - 1])
        }.toList()
    }
}
