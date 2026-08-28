package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.db.RagDatabase

class RoomLexicalEvidenceRetriever(
    private val database: RagDatabase,
    private val calibrationKey: RetrievalCalibrationKey,
) : LexicalEvidenceRetriever {
    override suspend fun retrieve(
        knowledgeBaseIds: List<String>,
        question: String,
        limit: Int,
    ): List<LexicalRetrievedChunk> {
        require(knowledgeBaseIds.isNotEmpty() && knowledgeBaseIds.all(String::isNotBlank))
        require(question.isNotBlank() && limit in 1..MAX_LEXICAL_RESULTS)
        val matchQuery = SafeFtsQuery.build(question) ?: return emptyList()
        val rows = database.chunkDao().searchReadyChunkMatchInfo(
            matchQuery = matchQuery,
            knowledgeBaseIds = knowledgeBaseIds.distinct(),
            corpusVersion = calibrationKey.corpusVersion,
            scanLimit = MAX_MATCHINFO_SCAN_RESULTS,
        )
        val ranked = rows.asSequence().map { row ->
            val matchInfo = FtsMatchInfo.parse(row.matchInfo)
            LexicalScore(row.chunkId, matchInfo.bm25(), matchInfo.matchedPhraseRatio())
        }.filter { it.score.isFinite() && it.score > 0.0 }
            .sortedWith(compareByDescending<LexicalScore>(LexicalScore::score).thenBy(LexicalScore::chunkId))
            .take(limit)
            .toList()
        if (ranked.isEmpty()) return emptyList()
        val chunks = database.chunkDao().findByIds(ranked.map(LexicalScore::chunkId)).associateBy { it.id }
        return ranked.mapNotNull { result ->
            val chunk = chunks[result.chunkId] ?: return@mapNotNull null
            val source = RetrievedChunk(
                chunkId = chunk.id,
                displayName = chunk.displayName,
                locator = listOf(chunk.locatorType, chunk.locatorValue)
                    .filter(String::isNotBlank)
                    .joinToString(" "),
                text = chunk.text,
                score = result.score.toFloat(),
                documentId = chunk.documentId,
                tokenCount = chunk.tokenCount,
                lexicalScore = result.score,
                lexicalCoverage = result.coverage,
                calibrationKey = calibrationKey,
            )
            LexicalRetrievedChunk(
                source = source.copy(exactAnchor = ExactAnchorMatcher.matches(question, source)),
                score = result.score,
            )
        }
    }

    private data class LexicalScore(
        val chunkId: Long,
        val score: Double,
        val coverage: Double,
    )

    private companion object {
        const val MAX_LEXICAL_RESULTS = 40
        const val MAX_MATCHINFO_SCAN_RESULTS = 2_000
    }
}
