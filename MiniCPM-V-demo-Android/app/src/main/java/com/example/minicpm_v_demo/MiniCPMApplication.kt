package com.example.minicpm_v_demo

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import com.example.minicpm_v_demo.rag.crypto.RagKeyManager
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import com.example.minicpm_v_demo.rag.retrieval.CascadedEvidenceAcceptancePolicy
import com.example.minicpm_v_demo.rag.retrieval.CurrentAnswerabilityCalibration
import com.example.minicpm_v_demo.rag.retrieval.CurrentRetrievalCalibration
import com.example.minicpm_v_demo.rag.retrieval.LazyAnswerabilityClassifier
import com.example.minicpm_v_demo.rag.DatabaseRagTurnStateSource
import com.example.minicpm_v_demo.rag.RagCoordinator
import com.example.minicpm_v_demo.rag.RagPromptBuilder
import com.example.minicpm_v_demo.rag.RagRunIdFactory
import com.example.minicpm_v_demo.rag.RagRetrievalMode
import com.example.minicpm_v_demo.rag.RoomRagStateQueries
import com.example.minicpm_v_demo.rag.LowLatencyRagRuntimeGate
import com.example.minicpm_v_demo.rag.prompt.RagContextBudgeter
import com.example.minicpm_v_demo.rag.db.RagDatabaseFactory
import com.example.minicpm_v_demo.rag.embed.EmbeddingModelManager
import com.example.minicpm_v_demo.rag.embed.EmbeddingSessionReleasePolicy
import com.example.minicpm_v_demo.rag.guard.RagGuardModelManager
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.index.ExactVectorSearchBackend
import com.example.minicpm_v_demo.rag.index.HnswIndexPublisher
import com.example.minicpm_v_demo.rag.index.HnswVectorSearchBackend
import com.example.minicpm_v_demo.rag.retrieval.RoomDenseEvidenceRetriever
import com.example.minicpm_v_demo.rag.retrieval.HybridRetriever
import com.example.minicpm_v_demo.rag.retrieval.RagPromptAssembler
import com.example.minicpm_v_demo.rag.retrieval.SentenceWindowEvidenceReducer
import com.example.minicpm_v_demo.rag.retrieval.RoomLexicalEvidenceRetriever
import com.example.minicpm_v_demo.rag.route.DefaultRagQueryRouter
import com.example.minicpm_v_demo.rag.work.RagWorkRecovery
import com.example.minicpm_v_demo.rag.work.WorkManagerRagWorkCoordinator
import com.example.minicpm_v_demo.rag.work.WorkManagerHnswRebuildScheduler
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.UUID
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MiniCPMApplication : Application() {
    private val processStartedAtMs = System.currentTimeMillis()
    @Volatile private var backgroundSinceElapsedMs: Long? = null
    private var startedActivityCount: Int = 0
    val embeddingModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EmbeddingModelManager(this) }
    val ragGuardModelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RagGuardModelManager(this, embeddingModelManager)
    }
    val ragKeyManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RagKeyManager(this)
    }

    val ragDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RagDatabaseFactory(this, ragKeyManager).open()
    }
    val lowLatencyRagRuntimeGate = LowLatencyRagRuntimeGate()
    internal val hnswIndexDirectory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(noBackupFilesDir, "rag/index").apply {
            check((isDirectory || mkdirs()) && isDirectory)
        }
    }
    internal val hnswIndexPublisher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HnswIndexPublisher(
            hnswIndexDirectory,
            EncryptedFileStore(ragKeyManager::getOrCreateMasterKey),
        )
    }
    private val vectorSearchBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val rebuildScheduler = WorkManagerHnswRebuildScheduler(WorkManager.getInstance(this))
        HnswVectorSearchBackend(
            indexDirectory = hnswIndexDirectory,
            publisher = hnswIndexPublisher,
            appMemoryBudgetBytes = { Runtime.getRuntime().maxMemory() },
            exactFallback = ExactVectorSearchBackend(),
            onRebuildRequired = rebuildScheduler::enqueue,
        )
    }
    private val denseRagRetriever by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomDenseEvidenceRetriever(
            ragDatabase,
            embeddingModelManager,
            vectorSearchBackend = vectorSearchBackend,
        )
    }
    private val hybridRagRetriever by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HybridRetriever(
            denseRetriever = denseRagRetriever,
            lexicalRetriever = RoomLexicalEvidenceRetriever(
                ragDatabase,
                CurrentRetrievalCalibration.key,
            ),
            calibrationKey = CurrentRetrievalCalibration.key,
        )
    }
    val ragCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RagCoordinator(
            stateSource = DatabaseRagTurnStateSource(
                RoomRagStateQueries(ragDatabase.conversationRagDao()),
            ),
            router = DefaultRagQueryRouter(),
            retriever = hybridRagRetriever,
            acceptancePolicy = CascadedEvidenceAcceptancePolicy(
                retrievalKey = CurrentRetrievalCalibration.key,
                classifier = LazyAnswerabilityClassifier(ragGuardModelManager::openInstalled),
                profile = CurrentAnswerabilityCalibration.profile,
            ),
            reducer = SentenceWindowEvidenceReducer,
            budgeter = RagContextBudgeter(),
            promptBuilder = RagPromptBuilder(RagPromptAssembler::assemble),
            runIdFactory = RagRunIdFactory { UUID.randomUUID().toString() },
            retrievalMode = RagRetrievalMode.ALL_QUERIES,
            runtimeEnabled = lowLatencyRagRuntimeGate::isEnabled,
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                backgroundSinceElapsedMs = null
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    backgroundSinceElapsedMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        PDFBoxResourceLoader.init(this)
        LocaleManager.applyOnAppStart(this)
        ragMaintenanceExecutor.execute {
            RagTempFileCleaner.cleanup(RagTempFileCleaner.stagingDirectory(noBackupFilesDir))
            RagTempFileCleaner.cleanupHnswPlaintext(
                hnswIndexDirectory,
                createdBeforeOrAtMs = processStartedAtMs,
            )
            runBlocking {
                val installedModel = embeddingModelManager.installedIdentity()
                installedModel?.let { model ->
                    ragDatabase.knowledgeBaseDao().updateInstalledModelHash(
                        model.modelId, model.modelSha256, System.currentTimeMillis(),
                    )
                }
                RagWorkRecovery(
                    ragDatabase.documentDao(),
                    WorkManagerRagWorkCoordinator(WorkManager.getInstance(this@MiniCPMApplication)),
                ).rescheduleInterruptedImports(retryModelBindingFailures = installedModel != null)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (EmbeddingSessionReleasePolicy.shouldRelease(
                backgroundSinceMs = backgroundSinceElapsedMs,
                nowMs = SystemClock.elapsedRealtime(),
                trimLevel = level,
            )
        ) {
            embeddingModelManager.close()
        }
    }

    private val ragMaintenanceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rag-maintenance").apply { isDaemon = true }
    }
}
