package com.example.minicpm_v_demo.rag.work

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabase
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.index.HnswIndex
import com.example.minicpm_v_demo.rag.index.HnswIndexBuildOutcome
import com.example.minicpm_v_demo.rag.index.HnswIndexPublisher
import com.example.minicpm_v_demo.rag.index.EmbeddingCorpusKey
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswRebuildRunnerInstrumentedTest {
    @Test
    fun repeatedEnqueueConvergesToOneCorpusGenerationWorkRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManager = WorkManager.getInstance(context)
        val corpusKey = EmbeddingCorpusKey(
            knowledgeBaseIds = listOf("kb-enqueue-${UUID.randomUUID()}"),
            modelSha256 = "0".repeat(64),
            corpusVersion = 1,
            embeddingCount = 5_001,
            maximumUpdatedAt = 42,
            chunkIdSum = 12_507_501,
        )
        val uniqueName = HnswRebuildContract.uniqueWorkName(corpusKey)
        try {
            val scheduler = WorkManagerHnswRebuildScheduler(workManager)
            repeat(20) { scheduler.enqueue(corpusKey) }

            val work = workManager.getWorkInfosForUniqueWork(uniqueName)
                .get(10, TimeUnit.SECONDS)

            assertEquals(1, work.size)
        } finally {
            workManager.cancelUniqueWork(uniqueName).result.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun legacyDeviceFixtureRowsAreRemovedWithoutTouchingUserKnowledgeBases() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MiniCPMApplication
        val writable = app.ragDatabase.openHelper.writableDatabase
        val pattern = "$LEGACY_DEVICE_FIXTURE_PREFIX%"
        writable.beginTransaction()
        try {
            writable.execSQL(
                "DELETE FROM citations WHERE chunkId IN " +
                    "(SELECT id FROM chunks WHERE knowledgeBaseId LIKE ?)",
                arrayOf<Any>(pattern),
            )
            writable.execSQL(
                "DELETE FROM chunk_embeddings WHERE chunkId IN " +
                    "(SELECT id FROM chunks WHERE knowledgeBaseId LIKE ?)",
                arrayOf<Any>(pattern),
            )
            writable.execSQL("DELETE FROM chunks WHERE knowledgeBaseId LIKE ?", arrayOf<Any>(pattern))
            writable.execSQL("DELETE FROM documents WHERE knowledgeBaseId LIKE ?", arrayOf<Any>(pattern))
            writable.execSQL(
                "DELETE FROM conversation_knowledge_bases WHERE knowledgeBaseId LIKE ?",
                arrayOf<Any>(pattern),
            )
            writable.execSQL("DELETE FROM knowledge_bases WHERE id LIKE ?", arrayOf<Any>(pattern))
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        runBlocking {
            assertTrue(
                app.ragDatabase.knowledgeBaseDao().findAll()
                    .none { it.id.startsWith(LEGACY_DEVICE_FIXTURE_PREFIX) },
            )
        }
    }

    @Test
    fun runnerBuildsOneEncryptedIndexAcrossTwoKnowledgeBasesAtProductionThreshold() {
        Log.i(TAG, "stage=test-start")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RagDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        Log.i(TAG, "stage=database-ready")
        val root = File(context.noBackupFilesDir, "rag/runner-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniCPM:HnswRebuildRunnerTest")
            .apply { acquire(TEST_WAKE_LOCK_TIMEOUT_MILLIS) }
        try {
            val modelSha = E5ModelSpec.PINNED.files.getValue("model.int8.onnx")
            val knowledgeBaseIds = listOf("kb-runner-a", "kb-runner-b")
            val now = System.currentTimeMillis()
            runBlocking {
                knowledgeBaseIds.forEachIndexed { index, id ->
                    database.knowledgeBaseDao().insert(
                        KnowledgeBaseEntity(
                            id = id,
                            name = "Runner ${index + 1}",
                            normalizedName = "runner-${index + 1}",
                            createdAt = now,
                            updatedAt = now,
                            embeddingModelSha256 = modelSha,
                        ),
                    )
                    database.documentDao().upsert(document(index, id, now))
                }
            }
            Log.i(TAG, "stage=metadata-ready")
            seedCorpus(database, knowledgeBaseIds, modelSha, now)
            Log.i(TAG, "stage=corpus-ready")

            val encryptionKey = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { encryptionKey })
            Log.i(TAG, "stage=runner-start")
            val outcome = runBlocking {
                HnswRebuildRunner(database.chunkDao(), root, publisher).rebuild(
                    HnswRebuildInput(
                        knowledgeBaseIds = knowledgeBaseIds,
                        modelSha256 = modelSha,
                        corpusVersion = 1,
                    ),
                    onStage = { stage -> Log.i(TAG, "stage=runner-$stage") },
                )
            }
            Log.i(TAG, "stage=runner-finished")

            assertTrue(outcome is HnswIndexBuildOutcome.Published)
            val published = outcome as HnswIndexBuildOutcome.Published
            assertEquals(EMBEDDING_COUNT, published.metadata.corpusKey.embeddingCount)
            assertEquals(knowledgeBaseIds, published.metadata.corpusKey.knowledgeBaseIds)
            assertTrue(published.paths.encryptedIndex.isFile)
            assertTrue(published.paths.metadata.isFile)
            publisher.withVerifiedPlaintext(published.metadata.corpusKey) { plaintext ->
                HnswIndex.load(
                    indexDirectory = root,
                    indexFile = plaintext,
                    dimension = E5ModelSpec.PINNED.dimension,
                    maximumElements = EMBEDDING_COUNT,
                ).use { index ->
                    val result = index.search(unitVector(0), topK = 10, efSearch = 48)
                    assertTrue(result.isNotEmpty())
                    assertTrue(result.all { it.chunkId in 1L..EMBEDDING_COUNT.toLong() })
                }
            }
            Log.i(TAG, "stage=verification-finished")
        } finally {
            database.close()
            root.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "stage=cleanup-finished")
        }
    }

    private fun seedCorpus(
        database: RagDatabase,
        knowledgeBaseIds: List<String>,
        modelSha: String,
        now: Long,
    ) {
        val writable = database.openHelper.writableDatabase
        val chunkStatement = writable.compileStatement(
            "INSERT INTO chunks " +
                "(id, documentId, knowledgeBaseId, ordinal, text, searchText, displayName, " +
                "titlePath, locatorType, locatorValue, tokenCount, contentSha256, embeddingState) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        val embeddingStatement = writable.compileStatement(
            "INSERT INTO chunk_embeddings (chunkId, modelSha256, dimension, vector, updatedAt) " +
                "VALUES (?, ?, ?, ?, ?)",
        )
        writable.beginTransaction()
        try {
            repeat(EMBEDDING_COUNT) { ordinal ->
                val knowledgeBaseIndex = ordinal and 1
                val documentId = "doc-runner-${knowledgeBaseIndex + 1}"
                val chunkId = ordinal + 1L
                val text = "runner vector $ordinal"
                chunkStatement.clearBindings()
                chunkStatement.bindLong(1, chunkId)
                chunkStatement.bindString(2, documentId)
                chunkStatement.bindString(3, knowledgeBaseIds[knowledgeBaseIndex])
                chunkStatement.bindLong(4, (ordinal / 2).toLong())
                chunkStatement.bindString(5, text)
                chunkStatement.bindString(6, text)
                chunkStatement.bindString(7, "$documentId.txt")
                chunkStatement.bindNull(8)
                chunkStatement.bindString(9, "none")
                chunkStatement.bindString(10, "")
                chunkStatement.bindLong(11, 3)
                chunkStatement.bindString(12, ordinal.toString().padStart(64, '0'))
                chunkStatement.bindLong(13, ChunkEntity.EMBEDDING_READY.toLong())
                chunkStatement.executeInsert()

                embeddingStatement.clearBindings()
                embeddingStatement.bindLong(1, chunkId)
                embeddingStatement.bindString(2, modelSha)
                embeddingStatement.bindLong(3, E5ModelSpec.PINNED.dimension.toLong())
                embeddingStatement.bindBlob(4, FloatVectorCodec.encode(unitVector(ordinal)))
                embeddingStatement.bindLong(5, now + ordinal)
                embeddingStatement.executeInsert()
                if ((ordinal + 1) % 1_000 == 0 || ordinal == EMBEDDING_COUNT - 1) {
                    Log.i(TAG, "stage=seed-${ordinal + 1}")
                }
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
            chunkStatement.close()
            embeddingStatement.close()
        }
    }

    private fun document(index: Int, knowledgeBaseId: String, now: Long) = DocumentEntity(
        id = "doc-runner-${index + 1}",
        knowledgeBaseId = knowledgeBaseId,
        displayName = "runner-${index + 1}.txt",
        sourceUri = null,
        privateFileName = "runner-${index + 1}.source.enc",
        mimeType = "text/plain",
        detectedType = "text/plain",
        sha256 = (index + 1).toString().padStart(64, '0'),
        sizeBytes = 1,
        status = DocumentStatus.READY,
        createdAt = now,
        updatedAt = now,
    )

    private fun unitVector(index: Int): FloatArray {
        val random = Random(index xor 0x5f3759df)
        val values = FloatArray(E5ModelSpec.PINNED.dimension) { random.nextFloat() * 2f - 1f }
        val norm = sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() }).toFloat()
        return FloatArray(values.size) { dimension -> values[dimension] / norm }
    }

    private fun generatedKey() = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private companion object {
        const val TAG = "HnswRebuildRunnerTest"
        const val LEGACY_DEVICE_FIXTURE_PREFIX = "instrumented-hnsw-"
        const val EMBEDDING_COUNT = 5_001
        const val TEST_WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    }
}
