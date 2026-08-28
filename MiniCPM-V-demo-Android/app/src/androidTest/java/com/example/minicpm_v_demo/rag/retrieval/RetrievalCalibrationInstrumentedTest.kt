package com.example.minicpm_v_demo.rag.retrieval

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.RagRetrievalOutcome
import com.example.minicpm_v_demo.rag.RagRetrievalRequest
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabase
import com.example.minicpm_v_demo.rag.embed.E5InputKind
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetrievalCalibrationInstrumentedTest {
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
    fun syntheticOfficeSuiteProducesVersionedThresholdsOnRealE5AndFts() = runBlocking {
        val corpus = SyntheticOfficeCalibrationCorpus.build()
        assertEquals(320, corpus.cases.size)
        assertEquals(
            CalibrationCategory.entries.associateWith { 40 },
            corpus.cases.groupingBy { it.category }.eachCount(),
        )
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntity(CORPUS_ID, "Synthetic calibration", "synthetic calibration", now, now),
        )
        corpus.documents.forEachIndexed { index, document ->
            database.documentDao().upsert(
                DocumentEntity(
                    id = document.documentId,
                    knowledgeBaseId = CORPUS_ID,
                    displayName = document.displayName,
                    sourceUri = null,
                    privateFileName = "${document.documentId}.src.enc",
                    mimeType = "text/plain",
                    detectedType = "text/plain",
                    sha256 = sha(index + 1),
                    sizeBytes = document.text.toByteArray(Charsets.UTF_8).size.toLong(),
                    status = DocumentStatus.READY,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        database.chunkDao().insertAll(corpus.documents.map { it.chunk })

        val embedder = requireNotNull(app.embeddingModelManager.openInstalled()) {
            "The pinned E5 model must be installed before calibration"
        }
        val passageVectors = embedder.embed(corpus.documents.map { it.text }, E5InputKind.PASSAGE)
        database.chunkDao().storeEmbeddingBatch(
            corpus.documents.zip(passageVectors).map { (document, vector) ->
                ChunkEmbeddingEntity(
                    chunkId = document.chunkId,
                    modelSha256 = embedder.modelSha256,
                    dimension = vector.size,
                    vector = FloatVectorCodec.encode(vector),
                    updatedAt = now,
                )
            },
        )

        val retriever = HybridRetriever(
            denseRetriever = RoomDenseEvidenceRetriever(database, app.embeddingModelManager),
            lexicalRetriever = RoomLexicalEvidenceRetriever(database, CurrentRetrievalCalibration.key),
            calibrationKey = CurrentRetrievalCalibration.key,
        )
        val observations = corpus.cases.mapIndexed { index, case ->
            val outcome = retriever.retrieve(
                RagRetrievalRequest(listOf(CORPUS_ID), case.question, limit = 12),
            )
            check(outcome is RagRetrievalOutcome.Evidence) {
                "Calibration retrieval failed for anonymous case ${case.caseId}"
            }
            if ((index + 1) % 40 == 0) sendProgress(index + 1)
            RetrievalCalibrationObservation(
                caseId = case.caseId,
                relevantChunkIds = case.relevantChunkIds,
                candidates = outcome.sources.map { it.copy(exactAnchor = false) },
            )
        }
        val denseCandidates = quantiles(
            observations.flatMap { observation -> observation.candidates.mapNotNull { it.denseScore } },
        )
        val lexicalCoverageCandidates = quantiles(
            observations.flatMap { observation -> observation.candidates.mapNotNull { it.lexicalCoverage } },
        )
        val result = RetrievalThresholdCalibrator.selectOrNull(
            key = CurrentRetrievalCalibration.key,
            observations = observations,
            highDenseCandidates = denseCandidates,
            standardDenseCandidates = denseCandidates,
            lexicalCoverageCandidates = lexicalCoverageCandidates,
        )
        if (result == null) {
            sendDiagnostic(
                calibrationBoundaryDiagnostic(
                    corpus.cases,
                    observations,
                    denseCandidates,
                    lexicalCoverageCandidates,
                ),
            )
            error("No calibration profile satisfies the quality gates")
        }

        assertTrue(result.metrics.recallAt4 >= RetrievalThresholdCalibrator.MINIMUM_RECALL_AT_4)
        assertTrue(
            result.metrics.noEvidencePrecision >=
                RetrievalThresholdCalibrator.MINIMUM_NO_EVIDENCE_PRECISION,
        )
        sendResult(result)
    }

    private fun sendProgress(completed: Int) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_PROGRESS,
            Bundle().apply { putString("calibration_progress", "$completed/320") },
        )
    }

    private fun sendResult(result: RetrievalCalibrationResult) {
        val profile = result.profile
        val metrics = result.metrics
        val summary = String.format(
            Locale.ROOT,
            "model=%s corpus=%d high=%.9f standard=%.9f lexical=%.9f recallAt4=%.6f " +
                "noEvidencePrecision=%.6f noEvidenceRecall=%.6f cases=%d",
            profile.key.embeddingModelSha256,
            profile.key.corpusVersion,
            profile.highDenseThreshold,
            profile.standardDenseThreshold,
            profile.minimumLexicalCoverage,
            metrics.recallAt4,
            metrics.noEvidencePrecision,
            metrics.noEvidenceRecall,
            metrics.totalCases,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_RESULT,
            Bundle().apply { putString("calibration_result", summary) },
        )
    }

    private fun sendDiagnostic(summary: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_RESULT,
            Bundle().apply { putString("calibration_diagnostic", summary) },
        )
    }

    private fun calibrationBoundaryDiagnostic(
        cases: List<SyntheticCalibrationCase>,
        observations: List<RetrievalCalibrationObservation>,
        denseCandidates: List<Float>,
        lexicalCoverageCandidates: List<Double>,
    ): String {
        var bestPrecisionAtRecall: RetrievalCalibrationResult? = null
        var bestRecallAtPrecision: RetrievalCalibrationResult? = null
        var bestRecallOverall: RetrievalCalibrationResult? = null
        var bestPrecisionOverall: RetrievalCalibrationResult? = null
        denseCandidates.forEach { high ->
            denseCandidates.filter { it <= high }.forEach { standard ->
                lexicalCoverageCandidates.forEach { coverage ->
                    val profile = RetrievalCalibrationProfile(
                        CurrentRetrievalCalibration.key,
                        high,
                        standard,
                        coverage,
                    )
                    val result = RetrievalCalibrationResult(
                        profile,
                        RetrievalThresholdCalibrator.evaluate(profile, observations),
                    )
                    if (result.metrics.recallAt4 >= RetrievalThresholdCalibrator.MINIMUM_RECALL_AT_4 &&
                        (bestPrecisionAtRecall == null ||
                            result.metrics.noEvidencePrecision > bestPrecisionAtRecall!!.metrics.noEvidencePrecision)
                    ) {
                        bestPrecisionAtRecall = result
                    }
                    if (result.metrics.noEvidencePrecision >=
                        RetrievalThresholdCalibrator.MINIMUM_NO_EVIDENCE_PRECISION &&
                        (bestRecallAtPrecision == null ||
                            result.metrics.recallAt4 > bestRecallAtPrecision!!.metrics.recallAt4)
                    ) {
                        bestRecallAtPrecision = result
                    }
                    if (bestRecallOverall == null ||
                        result.metrics.recallAt4 > bestRecallOverall!!.metrics.recallAt4 ||
                        (result.metrics.recallAt4 == bestRecallOverall!!.metrics.recallAt4 &&
                            result.metrics.noEvidencePrecision > bestRecallOverall!!.metrics.noEvidencePrecision)
                    ) {
                        bestRecallOverall = result
                    }
                    if (bestPrecisionOverall == null ||
                        result.metrics.noEvidencePrecision > bestPrecisionOverall!!.metrics.noEvidencePrecision ||
                        (result.metrics.noEvidencePrecision == bestPrecisionOverall!!.metrics.noEvidencePrecision &&
                            result.metrics.recallAt4 > bestPrecisionOverall!!.metrics.recallAt4)
                    ) {
                        bestPrecisionOverall = result
                    }
                }
            }
        }
        fun RetrievalCalibrationResult?.compact(): String = this?.let {
            String.format(
                Locale.ROOT,
                "h=%.6f,s=%.6f,c=%.6f,r=%.6f,p=%.6f,nr=%.6f",
                profile.highDenseThreshold,
                profile.standardDenseThreshold,
                profile.minimumLexicalCoverage,
                metrics.recallAt4,
                metrics.noEvidencePrecision,
                metrics.noEvidenceRecall,
            )
        } ?: "none"
        fun errors(result: RetrievalCalibrationResult?): String {
            if (result == null) return "none"
            val policy = CalibratedEvidenceAcceptancePolicy(result.profile)
            val missed = mutableListOf<CalibrationCategory>()
            val falseEvidence = mutableListOf<CalibrationCategory>()
            cases.zip(observations).forEach { (case, observation) ->
                val accepted = policy.accept(observation.candidates)
                if (case.relevantChunkIds.isNotEmpty() &&
                    accepted.take(4).none { it.chunkId in case.relevantChunkIds }
                ) {
                    missed += case.category
                }
                if (case.relevantChunkIds.isEmpty() && accepted.isNotEmpty()) falseEvidence += case.category
            }
            return "miss=" + missed.groupingBy { it }.eachCount() +
                ",false=" + falseEvidence.groupingBy { it }.eachCount()
        }
        return "precisionAtRecall=${bestPrecisionAtRecall.compact()} " +
            "recallAtPrecision=${bestRecallAtPrecision.compact()} " +
            "bestRecall=${bestRecallOverall.compact()} ${errors(bestRecallOverall)} " +
            "bestPrecision=${bestPrecisionOverall.compact()} ${errors(bestPrecisionOverall)} " +
            "denseCandidates=${denseCandidates.size} coverageCandidates=${lexicalCoverageCandidates.size}"
    }

    private fun <T : Comparable<T>> quantiles(values: List<T>): List<T> {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        return (0..100 step 5).map { percentile ->
            sorted[((sorted.lastIndex.toLong() * percentile) / 100L).toInt()]
        }.distinct()
    }

    private fun sha(seed: Int): String = seed.toString(16).padStart(64, '0')

    private companion object {
        const val CORPUS_ID = "kb-calibration-v1"
        const val STATUS_PROGRESS = 2
        const val STATUS_RESULT = 3
    }
}
