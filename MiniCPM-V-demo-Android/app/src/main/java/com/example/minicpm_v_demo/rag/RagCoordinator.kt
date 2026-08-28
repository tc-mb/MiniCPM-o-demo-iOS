package com.example.minicpm_v_demo.rag

import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import com.example.minicpm_v_demo.rag.db.ConversationRagDao
import com.example.minicpm_v_demo.rag.route.RagQueryRoute
import com.example.minicpm_v_demo.rag.route.RagQueryRouter
import com.example.minicpm_v_demo.rag.route.RagRouteInput
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class RagRouteState(
    val enabled: Boolean,
    val knownDocumentNames: List<String>,
)

sealed interface RagSelectionState {
    data object NoSelection : RagSelectionState
    data object Indexing : RagSelectionState
    data class Ready(val knowledgeBaseIds: List<String>) : RagSelectionState {
        init {
            require(knowledgeBaseIds.isNotEmpty())
            require(knowledgeBaseIds.all(String::isNotBlank))
            require(knowledgeBaseIds.distinct().size == knowledgeBaseIds.size)
        }
    }
}

interface RagTurnStateSource {
    suspend fun routeState(conversationId: Long): RagRouteState
    suspend fun selectionState(conversationId: Long): RagSelectionState
}

interface RagStateQueries {
    suspend fun isEnabled(conversationId: Long): Boolean
    suspend fun knownDocumentNames(conversationId: Long): List<String>
    suspend fun selectedKnowledgeBaseIds(conversationId: Long): List<String>
    suspend fun readyDocumentCount(conversationId: Long): Int
    suspend fun indexingDocumentCount(conversationId: Long): Int
}

class RoomRagStateQueries(
    private val dao: ConversationRagDao,
) : RagStateQueries {
    override suspend fun isEnabled(conversationId: Long): Boolean =
        dao.findState(conversationId)?.ragEnabled == true

    override suspend fun knownDocumentNames(conversationId: Long): List<String> =
        dao.findBoundDocumentNames(conversationId)

    override suspend fun selectedKnowledgeBaseIds(conversationId: Long): List<String> =
        dao.findSelectedEnabledKnowledgeBaseIds(conversationId)

    override suspend fun readyDocumentCount(conversationId: Long): Int =
        dao.countReadyDocuments(conversationId)

    override suspend fun indexingDocumentCount(conversationId: Long): Int =
        dao.countIndexingDocuments(conversationId)
}

class DatabaseRagTurnStateSource(
    private val queries: RagStateQueries,
) : RagTurnStateSource {
    override suspend fun routeState(conversationId: Long): RagRouteState {
        require(conversationId > 0)
        val enabled = queries.isEnabled(conversationId)
        return RagRouteState(
            enabled = enabled,
            knownDocumentNames = if (enabled) queries.knownDocumentNames(conversationId) else emptyList(),
        )
    }

    override suspend fun selectionState(conversationId: Long): RagSelectionState {
        require(conversationId > 0)
        val selectedIds = queries.selectedKnowledgeBaseIds(conversationId).distinct()
        if (selectedIds.isEmpty()) return RagSelectionState.NoSelection
        if (queries.readyDocumentCount(conversationId) > 0) {
            return RagSelectionState.Ready(selectedIds)
        }
        return if (queries.indexingDocumentCount(conversationId) > 0) {
            RagSelectionState.Indexing
        } else {
            RagSelectionState.Ready(selectedIds)
        }
    }
}

data class RagRetrievalRequest(
    val knowledgeBaseIds: List<String>,
    val question: String,
    val limit: Int,
)

sealed interface RagRetrievalOutcome {
    data object ModelRequired : RagRetrievalOutcome
    data class Evidence(val sources: List<RetrievedChunk>) : RagRetrievalOutcome
}

fun interface RagEvidenceRetriever {
    suspend fun retrieve(request: RagRetrievalRequest): RagRetrievalOutcome
}

fun interface RagEvidenceAcceptancePolicy {
    suspend fun accept(question: String, sources: List<RetrievedChunk>): List<RetrievedChunk>
}

object BasicRagEvidenceAcceptancePolicy : RagEvidenceAcceptancePolicy {
    override suspend fun accept(
        question: String,
        sources: List<RetrievedChunk>,
    ): List<RetrievedChunk> = sources.filter { source ->
        source.chunkId > 0 &&
            source.documentId.isNotBlank() &&
            source.text.isNotBlank() &&
            source.score.isFinite() &&
            source.tokenCount >= 0
    }
}

fun interface RagEvidenceReducer {
    fun reduce(question: String, sources: List<RetrievedChunk>): List<RetrievedChunk>
}

object IdentityRagEvidenceReducer : RagEvidenceReducer {
    override fun reduce(question: String, sources: List<RetrievedChunk>): List<RetrievedChunk> =
        sources.toList()
}

data class RagEvidenceBudget(
    val sources: List<RetrievedChunk>,
    val tokenCount: Int,
) {
    init {
        require(tokenCount >= 0)
    }
}

fun interface RagEvidenceBudgeter {
    suspend fun budget(
        question: String,
        sources: List<RetrievedChunk>,
        tokenCounter: RagPromptTokenCounter?,
    ): RagEvidenceBudget
}

interface RagPromptTokenCounter {
    suspend fun count(text: String): Int
    suspend fun remainingContextTokens(): Int
}

class SourceCountRagEvidenceBudgeter(
    private val maxSources: Int = 4,
) : RagEvidenceBudgeter {
    init {
        require(maxSources in 1..20)
    }

    override suspend fun budget(
        question: String,
        sources: List<RetrievedChunk>,
        tokenCounter: RagPromptTokenCounter?,
    ): RagEvidenceBudget {
        val bounded = sources.take(maxSources).toList()
        val tokenCount = bounded.fold(0L) { total, source -> total + source.tokenCount }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return RagEvidenceBudget(bounded, tokenCount)
    }
}

fun interface RagPromptBuilder {
    fun build(question: String, sources: List<RetrievedChunk>): String
}

fun interface RagRunIdFactory {
    fun create(): String
}

enum class RagTurnFailure {
    STATE_UNAVAILABLE,
    ROUTING_UNAVAILABLE,
    RETRIEVAL_UNAVAILABLE,
    EVIDENCE_PROCESSING_FAILED,
    PROMPT_BUILD_FAILED,
}

sealed interface RagTurnPlan {
    data object Disabled : RagTurnPlan
    data object NoRetrieval : RagTurnPlan
    data object NoSelection : RagTurnPlan
    data object Indexing : RagTurnPlan
    data object ModelRequired : RagTurnPlan
    data object NoEvidence : RagTurnPlan
    data class Ready(
        val runId: String,
        val prompt: String,
        val citations: List<RetrievedChunk>,
        val evidenceTokenCount: Int,
    ) : RagTurnPlan
    data class Failed(val kind: RagTurnFailure) : RagTurnPlan
}

val RagTurnPlan.requiresCheckpoint: Boolean
    get() = this is RagTurnPlan.Ready

enum class RagRetrievalMode {
    ADAPTIVE,
    ALL_QUERIES,
}

class LowLatencyRagRuntimeGate {
    private val enabled = AtomicBoolean(true)

    fun isEnabled(): Boolean = enabled.get()

    fun disable() {
        enabled.set(false)
    }
}

enum class RagPlanningStage {
    RETRIEVING,
    ORGANIZING,
}

class RagCoordinator(
    private val stateSource: RagTurnStateSource,
    private val router: RagQueryRouter,
    private val retriever: RagEvidenceRetriever,
    private val acceptancePolicy: RagEvidenceAcceptancePolicy,
    private val reducer: RagEvidenceReducer,
    private val budgeter: RagEvidenceBudgeter,
    private val promptBuilder: RagPromptBuilder,
    private val runIdFactory: RagRunIdFactory,
    private val retrievalMode: RagRetrievalMode = RagRetrievalMode.ADAPTIVE,
    private val runtimeEnabled: () -> Boolean = { true },
) {
    suspend fun plan(
        conversationId: Long,
        question: String,
        limit: Int = 6,
        tokenCounter: RagPromptTokenCounter? = null,
        onStage: suspend (RagPlanningStage) -> Unit = {},
    ): RagTurnPlan {
        require(conversationId > 0 && question.isNotBlank() && limit in 1..20)
        if (!runtimeEnabled()) return RagTurnPlan.Disabled
        val boundedQuestion = question.takeCodePoints(MAX_QUERY_CODE_POINTS)
        val routeState = try {
            stateSource.routeState(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.STATE_UNAVAILABLE)
        }
        if (!routeState.enabled) return RagTurnPlan.Disabled
        if (retrievalMode == RagRetrievalMode.ADAPTIVE) {
            val route = try {
                router.route(
                    RagRouteInput(
                        ragEnabled = true,
                        query = boundedQuestion,
                        knownDocumentNames = routeState.knownDocumentNames,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return RagTurnPlan.Failed(RagTurnFailure.ROUTING_UNAVAILABLE)
            }
            if (route == RagQueryRoute.NO_RETRIEVAL) return RagTurnPlan.NoRetrieval
        }
        val selection = try {
            stateSource.selectionState(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.STATE_UNAVAILABLE)
        }
        val knowledgeBaseIds = when (selection) {
            RagSelectionState.NoSelection -> return RagTurnPlan.NoSelection
            RagSelectionState.Indexing -> return RagTurnPlan.Indexing
            is RagSelectionState.Ready -> selection.knowledgeBaseIds
        }
        reportStage(RagPlanningStage.RETRIEVING, onStage)
        val retrieval = try {
            retriever.retrieve(RagRetrievalRequest(knowledgeBaseIds, boundedQuestion, limit))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.RETRIEVAL_UNAVAILABLE)
        }
        val retrieved = when (retrieval) {
            RagRetrievalOutcome.ModelRequired -> return RagTurnPlan.ModelRequired
            is RagRetrievalOutcome.Evidence -> retrieval.sources
        }
        val accepted = try {
            acceptancePolicy.accept(boundedQuestion, retrieved).toList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.EVIDENCE_PROCESSING_FAILED)
        }
        if (accepted.isEmpty()) return RagTurnPlan.NoEvidence
        reportStage(RagPlanningStage.ORGANIZING, onStage)
        val reduced = try {
            reducer.reduce(boundedQuestion, accepted).toList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.EVIDENCE_PROCESSING_FAILED)
        }
        if (reduced.isEmpty()) return RagTurnPlan.NoEvidence
        val budget = try {
            budgeter.budget(boundedQuestion, reduced, tokenCounter)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.EVIDENCE_PROCESSING_FAILED)
        }
        if (budget.sources.isEmpty()) return RagTurnPlan.NoEvidence
        val prompt = try {
            val built = promptBuilder.build(boundedQuestion, budget.sources).takeIf(String::isNotBlank)
                ?: return RagTurnPlan.Failed(RagTurnFailure.PROMPT_BUILD_FAILED)
            if (
                tokenCounter != null &&
                tokenCounter.count(built) >
                (tokenCounter.remainingContextTokens() - RESPONSE_RESERVE_TOKENS).coerceAtLeast(0)
            ) {
                return RagTurnPlan.NoEvidence
            }
            built
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.PROMPT_BUILD_FAILED)
        }
        val runId = try {
            runIdFactory.create().takeIf(String::isNotBlank)
                ?: return RagTurnPlan.Failed(RagTurnFailure.PROMPT_BUILD_FAILED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RagTurnPlan.Failed(RagTurnFailure.PROMPT_BUILD_FAILED)
        }
        return RagTurnPlan.Ready(
            runId = runId,
            prompt = prompt,
            citations = budget.sources.toList(),
            evidenceTokenCount = budget.tokenCount,
        )
    }

    private suspend fun reportStage(
        stage: RagPlanningStage,
        callback: suspend (RagPlanningStage) -> Unit,
    ) {
        try {
            callback(stage)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // UI/telemetry observers must not change the retrieval decision.
        }
    }

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        val count = codePointCount(0, length)
        return if (count <= maxCodePoints) this else substring(0, offsetByCodePoints(0, maxCodePoints))
    }

    private companion object {
        const val MAX_QUERY_CODE_POINTS = 4_096
        const val RESPONSE_RESERVE_TOKENS = 768
    }
}
