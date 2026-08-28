package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.rag.RagEvidenceRetriever
import com.example.minicpm_v_demo.rag.RagRetrievalOutcome
import com.example.minicpm_v_demo.rag.RagRetrievalRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class LexicalRetrievedChunk(
    val source: RetrievedChunk,
    val score: Double,
)

fun interface LexicalEvidenceRetriever {
    suspend fun retrieve(
        knowledgeBaseIds: List<String>,
        question: String,
        limit: Int,
    ): List<LexicalRetrievedChunk>
}

class HybridRetrievalUnavailableException : IllegalStateException("Hybrid retrieval is unavailable")

class HybridRetriever(
    private val denseRetriever: RagEvidenceRetriever,
    private val lexicalRetriever: LexicalEvidenceRetriever,
    private val calibrationKey: RetrievalCalibrationKey? = null,
) : RagEvidenceRetriever {
    override suspend fun retrieve(request: RagRetrievalRequest): RagRetrievalOutcome = coroutineScope {
        require(request.knowledgeBaseIds.isNotEmpty())
        require(request.question.isNotBlank() && request.limit in 1..MAX_FUSED_CANDIDATES)

        val denseDeferred = async {
            attempt { denseRetriever.retrieve(request.copy(limit = ROUTE_CANDIDATE_LIMIT)) }
        }
        val lexicalDeferred = async {
            attempt {
                lexicalRetriever.retrieve(
                    request.knowledgeBaseIds,
                    request.question,
                    ROUTE_CANDIDATE_LIMIT,
                )
            }
        }
        val denseAttempt = denseDeferred.await()
        val lexicalAttempt = lexicalDeferred.await()
        if (denseAttempt is Attempt.Failure && lexicalAttempt is Attempt.Failure) {
            throw HybridRetrievalUnavailableException()
        }

        val denseOutcome = (denseAttempt as? Attempt.Success)?.value
        val denseSources = (denseOutcome as? RagRetrievalOutcome.Evidence)?.sources.orEmpty()
            .take(ROUTE_CANDIDATE_LIMIT)
        val lexicalSources = (lexicalAttempt as? Attempt.Success)?.value.orEmpty()
            .filter { it.source.chunkId > 0 && it.score.isFinite() }
            .take(ROUTE_CANDIDATE_LIMIT)
        if (denseOutcome == RagRetrievalOutcome.ModelRequired && lexicalSources.isEmpty()) {
            return@coroutineScope RagRetrievalOutcome.ModelRequired
        }

        val denseById = denseSources.distinctBy(RetrievedChunk::chunkId).associateBy(RetrievedChunk::chunkId)
        val lexicalById = lexicalSources.distinctBy { it.source.chunkId }.associateBy { it.source.chunkId }
        val fused = ReciprocalRankFusion.fuse(
            dense = denseSources.map { DenseRankedHit(it.chunkId, it.score) },
            lexical = lexicalSources.map { LexicalRankedHit(it.source.chunkId, it.score) },
            limit = MAX_ROUTE_UNION_CANDIDATES,
        )
        val documentContributions = HashMap<String, Int>()
        val sources = buildList {
            for (hit in fused) {
                val source = denseById[hit.chunkId] ?: lexicalById[hit.chunkId]?.source ?: continue
                if (source.documentId.isBlank()) continue
                val contribution = documentContributions[source.documentId] ?: 0
                if (contribution >= MAX_CANDIDATES_PER_DOCUMENT) continue
                documentContributions[source.documentId] = contribution + 1
                add(
                    source.copy(
                        score = hit.rrfScore.toFloat(),
                        denseScore = hit.denseScore,
                        lexicalScore = hit.lexicalScore,
                        lexicalCoverage = lexicalById[hit.chunkId]?.source?.lexicalCoverage,
                        exactAnchor = source.exactAnchor || ExactAnchorMatcher.matches(request.question, source),
                        calibrationKey = calibrationKey,
                    ),
                )
                if (size >= request.limit) break
            }
        }
        RagRetrievalOutcome.Evidence(sources)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> = try {
        Attempt.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Attempt.Failure
    }

    private sealed interface Attempt<out T> {
        data class Success<T>(val value: T) : Attempt<T>
        data object Failure : Attempt<Nothing>
    }

    private companion object {
        const val ROUTE_CANDIDATE_LIMIT = 40
        const val MAX_ROUTE_UNION_CANDIDATES = ROUTE_CANDIDATE_LIMIT * 2
        const val MAX_FUSED_CANDIDATES = 12
        const val MAX_CANDIDATES_PER_DOCUMENT = 3
    }
}
