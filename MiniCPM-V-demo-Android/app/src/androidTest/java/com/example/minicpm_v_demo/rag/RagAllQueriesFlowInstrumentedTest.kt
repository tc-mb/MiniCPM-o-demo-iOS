package com.example.minicpm_v_demo.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.ConversationRagStateEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabase
import com.example.minicpm_v_demo.rag.embed.E5InputKind
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.retrieval.CurrentRetrievalCalibration
import com.example.minicpm_v_demo.rag.retrieval.HybridRetriever
import com.example.minicpm_v_demo.rag.retrieval.RagPromptAssembler
import com.example.minicpm_v_demo.rag.retrieval.RoomDenseEvidenceRetriever
import com.example.minicpm_v_demo.rag.retrieval.RoomLexicalEvidenceRetriever
import com.example.minicpm_v_demo.rag.route.DefaultRagQueryRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagAllQueriesFlowInstrumentedTest {
    @Test
    fun selectedKnowledgeBaseAlwaysRetrievesAndOnlyAcceptedEvidenceAugments() = runBlocking {
        keepDebugTargetForeground()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MiniCPMApplication
        val database = Room.inMemoryDatabaseBuilder(context, RagDatabase::class.java).build()
        try {
            val now = 1_800_000_000_000L
            val conversationId = 908L
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntity("kb-all-queries", "All Queries", "all queries", now, now),
            )
            database.documentDao().upsert(
                DocumentEntity(
                    id = "doc-all-queries",
                    knowledgeBaseId = "kb-all-queries",
                    displayName = "synthetic-policy.txt",
                    sourceUri = null,
                    privateFileName = "synthetic-policy.src.enc",
                    mimeType = "text/plain",
                    detectedType = "text/plain",
                    sha256 = "a".repeat(64),
                    sizeBytes = 64,
                    status = DocumentStatus.READY,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val evidenceText = "The approved travel reimbursement limit is 200 yuan."
            val chunk = ChunkEntity(
                id = 9_081,
                documentId = "doc-all-queries",
                knowledgeBaseId = "kb-all-queries",
                ordinal = 0,
                text = evidenceText,
                searchText = "approved travel reimbursement limit 200 yuan",
                displayName = "synthetic-policy.txt",
                locatorType = "line",
                locatorValue = "1",
                tokenCount = 9,
                contentSha256 = "b".repeat(64),
            )
            database.chunkDao().insertAll(listOf(chunk))
            val embedder = requireNotNull(app.embeddingModelManager.openInstalled())
            val vector = embedder.embed(listOf(evidenceText), E5InputKind.PASSAGE).single()
            database.chunkDao().storeEmbeddingBatch(
                listOf(
                    ChunkEmbeddingEntity(
                        chunkId = chunk.id,
                        modelSha256 = embedder.modelSha256,
                        dimension = vector.size,
                        vector = FloatVectorCodec.encode(vector),
                        updatedAt = now,
                    ),
                ),
            )
            database.conversationRagDao().replaceSelection(
                conversationId,
                listOf("kb-all-queries"),
                true,
                now,
            )
            val retriever = HybridRetriever(
                denseRetriever = RoomDenseEvidenceRetriever(database, app.embeddingModelManager),
                lexicalRetriever = RoomLexicalEvidenceRetriever(database, CurrentRetrievalCalibration.key),
                calibrationKey = CurrentRetrievalCalibration.key,
            )
            val readyCoordinator = coordinator(
                database,
                retriever,
                BasicRagEvidenceAcceptancePolicy,
            )
            val question = "What is the approved travel reimbursement limit?"
            val ready = readyCoordinator.plan(conversationId, question)

            assertTrue(ready is RagTurnPlan.Ready)
            ready as RagTurnPlan.Ready
            assertEquals(listOf(chunk.id), ready.citations.map { it.chunkId })
            assertTrue(ready.prompt.contains("200 yuan"))

            val rejectingCoordinator = coordinator(
                database,
                retriever,
                RagEvidenceAcceptancePolicy { _, _ -> emptyList() },
            )
            val noEvidence = rejectingCoordinator.plan(conversationId, "你好")
            assertEquals(RagTurnPlan.NoEvidence, noEvidence)
            assertEquals("你好", noEvidence.plainModelPromptOrNull("你好"))

            database.conversationRagDao().upsertState(
                ConversationRagStateEntity(conversationId + 1, ragEnabled = true, updatedAt = now),
            )
            val noSelection = readyCoordinator.plan(conversationId + 1, "普通问题")
            assertEquals(RagTurnPlan.NoSelection, noSelection)
            assertEquals("普通问题", noSelection.plainModelPromptOrNull("普通问题"))
        } finally {
            database.close()
        }
    }

    private fun coordinator(
        database: RagDatabase,
        retriever: RagEvidenceRetriever,
        acceptancePolicy: RagEvidenceAcceptancePolicy,
    ) = RagCoordinator(
        stateSource = DatabaseRagTurnStateSource(RoomRagStateQueries(database.conversationRagDao())),
        router = DefaultRagQueryRouter(),
        retriever = retriever,
        acceptancePolicy = acceptancePolicy,
        reducer = IdentityRagEvidenceReducer,
        budgeter = SourceCountRagEvidenceBudgeter(),
        promptBuilder = RagPromptBuilder(RagPromptAssembler::assemble),
        runIdFactory = RagRunIdFactory { "all-queries-flow" },
        retrievalMode = RagRetrievalMode.ALL_QUERIES,
    )

    private fun keepDebugTargetForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
        ).close()
    }
}
