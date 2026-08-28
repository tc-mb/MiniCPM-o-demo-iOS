package com.example.minicpm_v_demo.rag.retrieval

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.retrieval.CalibratedEvidenceAcceptancePolicy
import com.example.minicpm_v_demo.rag.retrieval.CurrentRetrievalCalibration
import com.example.minicpm_v_demo.rag.DatabaseRagTurnStateSource
import com.example.minicpm_v_demo.rag.IdentityRagEvidenceReducer
import com.example.minicpm_v_demo.rag.RagCoordinator
import com.example.minicpm_v_demo.rag.RagPromptBuilder
import com.example.minicpm_v_demo.rag.RagRetrievalOutcome
import com.example.minicpm_v_demo.rag.RagRetrievalRequest
import com.example.minicpm_v_demo.rag.RagRunIdFactory
import com.example.minicpm_v_demo.rag.RagTurnPlan
import com.example.minicpm_v_demo.rag.RoomRagStateQueries
import com.example.minicpm_v_demo.rag.SourceCountRagEvidenceBudgeter
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabase
import com.example.minicpm_v_demo.rag.embed.E5InputKind
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.route.DefaultRagQueryRouter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridRetrieverInstrumentedTest {
    private lateinit var database: RagDatabase
    private lateinit var app: MiniCPMApplication

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = context.applicationContext as MiniCPMApplication
        database = Room.inMemoryDatabaseBuilder(context, RagDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun selectedReadyKnowledgeBaseProducesAugmentedPromptFromRealE5Vectors() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-office", "Office", "office", now, now))
        database.documentDao().upsert(DocumentEntity(
            id = "doc-office", knowledgeBaseId = "kb-office", displayName = "policy.txt",
            sourceUri = null, privateFileName = "doc-office.src.enc", mimeType = "text/plain",
            detectedType = "text/plain", sha256 = "a".repeat(64), sizeBytes = 32,
            status = DocumentStatus.READY, createdAt = now, updatedAt = now,
        ))
        val chunk = ChunkEntity(
            id = 901, documentId = "doc-office", knowledgeBaseId = "kb-office", ordinal = 0,
            text = "The travel reimbursement limit is 200 yuan.",
            searchText = "travel reimbursement limit 200 yuan", displayName = "policy.txt",
            locatorType = "line", locatorValue = "12", tokenCount = 9,
            contentSha256 = "b".repeat(64),
        )
        database.chunkDao().insertAll(listOf(chunk))
        val embedder = requireNotNull(app.embeddingModelManager.openInstalled())
        val vector = embedder.embed(listOf(chunk.text), E5InputKind.PASSAGE).single()
        database.chunkDao().storeEmbeddingBatch(listOf(ChunkEmbeddingEntity(
            chunkId = chunk.id, modelSha256 = embedder.modelSha256, dimension = vector.size,
            vector = FloatVectorCodec.encode(vector), updatedAt = now,
        )))
        database.conversationRagDao().replaceSelection(77, listOf("kb-office"), true, now)

        val raw = hybridRetriever().retrieve(
            RagRetrievalRequest(listOf("kb-office"), "What is the travel reimbursement limit?", limit = 12),
        ) as RagRetrievalOutcome.Evidence
        val accepted = CalibratedEvidenceAcceptancePolicy(CurrentRetrievalCalibration.profile).accept(raw.sources)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "semantic_gate_diagnostic",
                    raw.sources.joinToString(separator = ";") { source ->
                        "dense=${source.denseScore},lexical=${source.lexicalScore}," +
                            "exact=${source.exactAnchor},accepted=${source in accepted}"
                    },
                )
            },
        )
        val result = coordinator().plan(77, "What is the travel reimbursement limit?")

        assertTrue(result is RagTurnPlan.Ready)
        result as RagTurnPlan.Ready
        assertEquals(listOf(901L), result.citations.map { it.chunkId })
        assertTrue(result.prompt.contains("200 yuan"))
        assertTrue(result.prompt.contains("[S1]"))
    }

    @Test
    fun greetingPassesThroughBeforeOpeningTheEmbeddingModelOrLoadingChunks() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntity("kb-greeting", "Greeting Test", "greeting test", now, now)
        )
        database.conversationRagDao().replaceSelection(
            conversationId = 78,
            knowledgeBaseIds = listOf("kb-greeting"),
            enabled = true,
            updatedAt = now,
        )

        val result = coordinator().plan(78, "你好")

        assertEquals(RagTurnPlan.NoRetrieval, result)
    }

    private fun coordinator() = RagCoordinator(
        stateSource = DatabaseRagTurnStateSource(
            RoomRagStateQueries(database.conversationRagDao()),
        ),
        router = DefaultRagQueryRouter(),
        retriever = hybridRetriever(),
        acceptancePolicy = CalibratedEvidenceAcceptancePolicy(CurrentRetrievalCalibration.profile),
        reducer = IdentityRagEvidenceReducer,
        budgeter = SourceCountRagEvidenceBudgeter(),
        promptBuilder = RagPromptBuilder(RagPromptAssembler::assemble),
        runIdFactory = RagRunIdFactory { "instrumented-run" },
    )

    private fun hybridRetriever() = HybridRetriever(
        denseRetriever = RoomDenseEvidenceRetriever(database, app.embeddingModelManager),
        lexicalRetriever = RoomLexicalEvidenceRetriever(
            database,
            CurrentRetrievalCalibration.key,
        ),
        calibrationKey = CurrentRetrievalCalibration.key,
    )
}
