package com.example.minicpm_v_demo.rag

import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import com.example.minicpm_v_demo.rag.route.RagQueryRoute
import com.example.minicpm_v_demo.rag.route.RagQueryRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RagCoordinatorTest {
    @Test
    fun defaultEvidenceStagesRejectMalformedSourcesAndEnforceSourceLimit() = runBlocking {
        val valid = source()
        val invalid = source().copy(chunkId = 0L, score = Float.NaN, text = "")
        val accepted = BasicRagEvidenceAcceptancePolicy.accept("question", listOf(valid, invalid))

        assertEquals(listOf(valid), accepted)
        assertEquals(listOf(valid), IdentityRagEvidenceReducer.reduce("question", accepted))
        assertEquals(
            RagEvidenceBudget(List(4) { valid.copy(chunkId = it + 1L) }, 12),
            SourceCountRagEvidenceBudgeter(maxSources = 4).budget(
                "question",
                List(6) { valid.copy(chunkId = it + 1L) },
                null,
            ),
        )
    }

    @Test
    fun databaseStateSourceAvoidsDocumentQueriesWhenDisabled() = runBlocking {
        val queries = FakeStateQueries(enabled = false)
        val source = DatabaseRagTurnStateSource(queries)

        assertEquals(RagRouteState(false, emptyList()), source.routeState(CONVERSATION_ID))
        assertEquals(listOf("enabled"), queries.calls)
    }

    @Test
    fun databaseStateSourceDistinguishesSelectionIndexingAndReady() = runBlocking {
        val missing = DatabaseRagTurnStateSource(FakeStateQueries(selectedIds = emptyList()))
        val indexing = DatabaseRagTurnStateSource(
            FakeStateQueries(selectedIds = listOf("kb-1"), readyCount = 0, indexingCount = 1),
        )
        val ready = DatabaseRagTurnStateSource(
            FakeStateQueries(selectedIds = listOf("kb-1"), readyCount = 1, indexingCount = 2),
        )

        assertEquals(RagSelectionState.NoSelection, missing.selectionState(CONVERSATION_ID))
        assertEquals(RagSelectionState.Indexing, indexing.selectionState(CONVERSATION_ID))
        assertEquals(
            RagSelectionState.Ready(listOf("kb-1")),
            ready.selectionState(CONVERSATION_ID),
        )
    }

    @Test
    fun disabledReturnsBeforeRoutingSelectionOrRetrieval() = runBlocking {
        val fixture = Fixture(enabled = false, route = RagQueryRoute.SINGLE_RETRIEVAL)

        val result = fixture.coordinator.plan(CONVERSATION_ID, "hello")

        assertEquals(RagTurnPlan.Disabled, result)
        assertEquals(listOf("route-state"), fixture.calls)
        assertFalse(result.requiresCheckpoint)
    }

    @Test
    fun runtimeGateFailureDisablesRagBeforeDatabaseRouting() = runBlocking {
        val fixture = Fixture(runtimeEnabled = false)

        val result = fixture.coordinator.plan(CONVERSATION_ID, "policy")

        assertEquals(RagTurnPlan.Disabled, result)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun noRetrievalReturnsBeforeSelectionEmbeddingChunksOrPromptBuild() = runBlocking {
        val fixture = Fixture(enabled = true, route = RagQueryRoute.NO_RETRIEVAL)

        val result = fixture.coordinator.plan(CONVERSATION_ID, "hello")

        assertEquals(RagTurnPlan.NoRetrieval, result)
        assertEquals(listOf("route-state", "route"), fixture.calls)
        assertFalse(result.requiresCheckpoint)
    }

    @Test
    fun readyTurnReportsRetrievalThenEvidenceOrganization() = runBlocking {
        val fixture = Fixture()
        val stages = mutableListOf<RagPlanningStage>()

        val result = fixture.coordinator.plan(CONVERSATION_ID, "policy", onStage = stages::add)

        assertTrue(result is RagTurnPlan.Ready)
        assertEquals(listOf(RagPlanningStage.RETRIEVING, RagPlanningStage.ORGANIZING), stages)
    }

    @Test
    fun noRetrievalTurnDoesNotReportRagStages() = runBlocking {
        val fixture = Fixture(route = RagQueryRoute.NO_RETRIEVAL)
        val stages = mutableListOf<RagPlanningStage>()

        fixture.coordinator.plan(CONVERSATION_ID, "hello", onStage = stages::add)

        assertTrue(stages.isEmpty())
    }

    @Test
    fun allQueriesModeBypassesRouterAndRetrievesEvenForOrdinaryChat() = runBlocking {
        val fixture = Fixture(
            enabled = true,
            route = RagQueryRoute.NO_RETRIEVAL,
            retrievalMode = RagRetrievalMode.ALL_QUERIES,
        )

        val result = fixture.coordinator.plan(CONVERSATION_ID, "你好")

        assertTrue(result is RagTurnPlan.Ready)
        assertEquals(
            listOf(
                "route-state",
                "selection-state",
                "retrieve",
                "accept",
                "reduce",
                "budget",
                "prompt",
                "run-id",
            ),
            fixture.calls,
        )
        assertEquals("你好", fixture.retrievalRequest?.question)
    }

    @Test
    fun missingSelectionAndIndexingStopBeforeRetrieval() = runBlocking {
        val missing = Fixture(selection = RagSelectionState.NoSelection)
        val indexing = Fixture(selection = RagSelectionState.Indexing)

        assertEquals(RagTurnPlan.NoSelection, missing.coordinator.plan(CONVERSATION_ID, "policy"))
        assertEquals(RagTurnPlan.Indexing, indexing.coordinator.plan(CONVERSATION_ID, "policy"))
        assertEquals(listOf("route-state", "route", "selection-state"), missing.calls)
        assertEquals(listOf("route-state", "route", "selection-state"), indexing.calls)
    }

    @Test
    fun missingModelStopsBeforeEvidenceProcessing() = runBlocking {
        val fixture = Fixture(retrieval = RagRetrievalOutcome.ModelRequired)

        val result = fixture.coordinator.plan(CONVERSATION_ID, "policy")

        assertEquals(RagTurnPlan.ModelRequired, result)
        assertEquals(
            listOf("route-state", "route", "selection-state", "retrieve"),
            fixture.calls,
        )
    }

    @Test
    fun rejectedOrEmptyEvidenceReturnsNoEvidenceBeforePromptBuild() = runBlocking {
        val empty = Fixture(retrieval = RagRetrievalOutcome.Evidence(emptyList()))
        val rejected = Fixture(acceptedEvidence = emptyList())

        assertEquals(RagTurnPlan.NoEvidence, empty.coordinator.plan(CONVERSATION_ID, "policy"))
        assertEquals(RagTurnPlan.NoEvidence, rejected.coordinator.plan(CONVERSATION_ID, "policy"))
        assertEquals(
            listOf("route-state", "route", "selection-state", "retrieve", "accept"),
            empty.calls,
        )
        assertEquals(
            listOf("route-state", "route", "selection-state", "retrieve", "accept"),
            rejected.calls,
        )
    }

    @Test
    fun readyPlanUsesStrictStageOrderAndCarriesOnlyBudgetedEvidence() = runBlocking {
        val budgeted = source().copy(chunkId = 2L, text = "budgeted", tokenCount = 4)
        val fixture = Fixture(
            reducedEvidence = listOf(source(), budgeted),
            budget = RagEvidenceBudget(listOf(budgeted), 4),
        )

        val result = fixture.coordinator.plan(CONVERSATION_ID, "policy", limit = 5)

        assertEquals(
            listOf(
                "route-state",
                "route",
                "selection-state",
                "retrieve",
                "accept",
                "reduce",
                "budget",
                "prompt",
                "run-id",
            ),
            fixture.calls,
        )
        assertEquals(
            RagTurnPlan.Ready(
                runId = "run-1",
                prompt = "prepared prompt",
                citations = listOf(budgeted),
                evidenceTokenCount = 4,
            ),
            result,
        )
        assertTrue(result.requiresCheckpoint)
        assertEquals(5, fixture.retrievalRequest?.limit)
        assertEquals(listOf("kb-1"), fixture.retrievalRequest?.knowledgeBaseIds)
    }

    @Test
    fun failuresAreAnonymousAndNeverFallBackToOrdinaryPrompt() = runBlocking {
        val stateFailure = Fixture(throwAt = "route-state")
        val retrievalFailure = Fixture(throwAt = "retrieve")
        val acceptanceFailure = Fixture(throwAt = "accept")
        val promptFailure = Fixture(throwAt = "prompt")

        assertEquals(
            RagTurnPlan.Failed(RagTurnFailure.STATE_UNAVAILABLE),
            stateFailure.coordinator.plan(CONVERSATION_ID, "policy"),
        )
        assertEquals(
            RagTurnPlan.Failed(RagTurnFailure.RETRIEVAL_UNAVAILABLE),
            retrievalFailure.coordinator.plan(CONVERSATION_ID, "policy"),
        )
        assertEquals(
            RagTurnPlan.Failed(RagTurnFailure.EVIDENCE_PROCESSING_FAILED),
            acceptanceFailure.coordinator.plan(CONVERSATION_ID, "policy"),
        )
        assertEquals(
            RagTurnPlan.Failed(RagTurnFailure.PROMPT_BUILD_FAILED),
            promptFailure.coordinator.plan(CONVERSATION_ID, "policy"),
        )
    }

    @Test
    fun retrievalAndPromptStagesReceiveABoundedUserQuestion() = runBlocking {
        val fixture = Fixture()

        fixture.coordinator.plan(CONVERSATION_ID, "x".repeat(5_000))

        assertEquals(4_096, fixture.retrievalRequest?.question?.length)
        assertEquals(4_096, fixture.acceptanceQuestion?.length)
        assertEquals(4_096, fixture.promptQuestion?.length)
    }

    @Test
    fun finalNativePromptCheckFallsBackWhenAnswerReserveWouldBeConsumed() = runBlocking {
        val fixture = Fixture()
        val counter = object : RagPromptTokenCounter {
            override suspend fun count(text: String): Int = 400
            override suspend fun remainingContextTokens(): Int = 1_000
        }

        val result = fixture.coordinator.plan(CONVERSATION_ID, "policy", tokenCounter = counter)

        assertEquals(RagTurnPlan.NoEvidence, result)
        assertFalse(fixture.calls.contains("run-id"))
    }

    @Test
    fun cancellationIsPropagatedInsteadOfConvertedToAFailurePlan() {
        listOf("retrieve", "accept").forEach { stage ->
            val fixture = Fixture(cancellationAt = stage)

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.coordinator.plan(CONVERSATION_ID, "policy")
                }
            }
        }
    }

    private class Fixture(
        enabled: Boolean = true,
        route: RagQueryRoute = RagQueryRoute.SINGLE_RETRIEVAL,
        private val selection: RagSelectionState = RagSelectionState.Ready(listOf("kb-1")),
        private val retrieval: RagRetrievalOutcome = RagRetrievalOutcome.Evidence(listOf(source())),
        private val acceptedEvidence: List<RetrievedChunk>? = null,
        private val reducedEvidence: List<RetrievedChunk>? = null,
        private val budget: RagEvidenceBudget? = null,
        private val throwAt: String? = null,
        private val cancellationAt: String? = null,
        private val retrievalMode: RagRetrievalMode = RagRetrievalMode.ADAPTIVE,
        private val runtimeEnabled: Boolean = true,
    ) {
        val calls = mutableListOf<String>()
        var retrievalRequest: RagRetrievalRequest? = null
        var acceptanceQuestion: String? = null
        var promptQuestion: String? = null

        private val stateSource = object : RagTurnStateSource {
            override suspend fun routeState(conversationId: Long): RagRouteState {
                calls += "route-state"
                failIfRequested("route-state")
                return RagRouteState(enabled, emptyList())
            }

            override suspend fun selectionState(conversationId: Long): RagSelectionState {
                calls += "selection-state"
                failIfRequested("selection-state")
                return selection
            }
        }
        private val router = RagQueryRouter {
            calls += "route"
            route
        }
        private val retriever = RagEvidenceRetriever {
            calls += "retrieve"
            retrievalRequest = it
            failIfRequested("retrieve")
            retrieval
        }
        private val acceptancePolicy = RagEvidenceAcceptancePolicy { question, evidence ->
            calls += "accept"
            acceptanceQuestion = question
            failIfRequested("accept")
            acceptedEvidence ?: evidence
        }
        private val reducer = RagEvidenceReducer { _, evidence ->
            calls += "reduce"
            failIfRequested("reduce")
            reducedEvidence ?: evidence
        }
        private val budgeter = RagEvidenceBudgeter { _, evidence, _ ->
            calls += "budget"
            failIfRequested("budget")
            budget ?: RagEvidenceBudget(evidence, evidence.sumOf(RetrievedChunk::tokenCount))
        }
        private val promptBuilder = RagPromptBuilder { question, _ ->
            calls += "prompt"
            promptQuestion = question
            failIfRequested("prompt")
            "prepared prompt"
        }

        val coordinator = RagCoordinator(
            stateSource = stateSource,
            router = router,
            retriever = retriever,
            acceptancePolicy = acceptancePolicy,
            reducer = reducer,
            budgeter = budgeter,
            promptBuilder = promptBuilder,
            runIdFactory = RagRunIdFactory {
                calls += "run-id"
                "run-1"
            },
            retrievalMode = retrievalMode,
            runtimeEnabled = { runtimeEnabled },
        )

        private fun failIfRequested(stage: String) {
            if (cancellationAt == stage) throw CancellationException("cancelled")
            if (throwAt == stage) error("sensitive internal detail from $stage")
        }
    }

    private class FakeStateQueries(
        private val enabled: Boolean = true,
        private val selectedIds: List<String> = listOf("kb-1"),
        private val readyCount: Int = 1,
        private val indexingCount: Int = 0,
    ) : RagStateQueries {
        val calls = mutableListOf<String>()

        override suspend fun isEnabled(conversationId: Long): Boolean {
            calls += "enabled"
            return enabled
        }

        override suspend fun knownDocumentNames(conversationId: Long): List<String> {
            calls += "names"
            return listOf("handbook.txt")
        }

        override suspend fun selectedKnowledgeBaseIds(conversationId: Long): List<String> {
            calls += "selection"
            return selectedIds
        }

        override suspend fun readyDocumentCount(conversationId: Long): Int {
            calls += "ready"
            return readyCount
        }

        override suspend fun indexingDocumentCount(conversationId: Long): Int {
            calls += "indexing"
            return indexingCount
        }
    }

    private companion object {
        const val CONVERSATION_ID = 7L

        fun source() = RetrievedChunk(
            chunkId = 1L,
            displayName = "handbook.txt",
            locator = "line 1",
            text = "evidence",
            score = 0.9f,
            documentId = "doc-1",
            tokenCount = 3,
        )
    }
}
