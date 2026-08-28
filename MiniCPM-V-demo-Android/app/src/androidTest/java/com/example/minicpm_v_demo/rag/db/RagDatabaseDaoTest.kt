package com.example.minicpm_v_demo.rag.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.retrieval.FtsMatchInfo

@RunWith(AndroidJUnit4::class)
class RagDatabaseDaoTest {
    private lateinit var database: RagDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RagDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    @Throws(IOException::class)
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun retrievalOnlyReturnsChunksFromReadyEnabledDocuments() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntity(
                id = "kb-1",
                name = "Office",
                normalizedName = "office",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.documentDao().upsert(document("ready", DocumentStatus.READY, now))
        database.documentDao().upsert(document("parsing", DocumentStatus.PARSING, now))
        database.chunkDao().insertAll(
            listOf(
                chunk(id = 1, documentId = "ready", text = "approved contract amount"),
                chunk(id = 2, documentId = "parsing", text = "draft contract amount"),
            ),
        )

        val results = database.chunkDao().searchReadyChunks("contract", "kb-1", 10)

        assertEquals(listOf(1L), results.map { it.id })
    }

    @Test
    fun ftsMatchInfoProjectionReturnsOnlyReadyEnabledSelectedChunks() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntity("kb-1", "Office", "office", now, now),
        )
        database.documentDao().upsert(document("ready", DocumentStatus.READY, now))
        database.documentDao().upsert(document("parsing", DocumentStatus.PARSING, now))
        database.documentDao().upsert(
            document("old-version", DocumentStatus.READY, now).copy(chunkerVersion = 2),
        )
        database.chunkDao().insertAll(
            listOf(
                chunk(41, "ready", "travel reimbursement policy"),
                chunk(42, "parsing", "travel reimbursement draft"),
                chunk(43, "old-version", "travel reimbursement legacy"),
            ),
        )

        val rows = database.chunkDao().searchReadyChunkMatchInfo(
            matchQuery = "\"travel\" OR \"reimbursement\"",
            knowledgeBaseIds = listOf("kb-1"),
            corpusVersion = 1,
            scanLimit = 100,
        )

        assertEquals(listOf(41L), rows.map { it.chunkId })
        assertTrue(FtsMatchInfo.parse(rows.single().matchInfo).bm25() > 0.0)
    }

    @Test
    fun deletingKnowledgeBaseCascadesDocumentsChunksAndFtsRows() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntity(
                id = "kb-delete",
                name = "Delete me",
                normalizedName = "delete me",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.documentDao().upsert(document("doc-delete", DocumentStatus.READY, now, "kb-delete"))
        database.chunkDao().insertAll(listOf(chunk(3, "doc-delete", "confidential payroll", "kb-delete")))

        database.knowledgeBaseDao().deleteById("kb-delete")

        assertTrue(database.documentDao().findByKnowledgeBase("kb-delete").isEmpty())
        assertTrue(database.chunkDao().findByDocument("doc-delete").isEmpty())
        assertTrue(database.chunkDao().searchReadyChunks("payroll", "kb-delete", 10).isEmpty())
    }

    @Test
    fun deletingDocumentReleasesContentHashForARepeatedImport() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        val first = document("first", DocumentStatus.READY, now).copy(sha256 = "c".repeat(64))
        database.documentDao().upsert(first)
        database.chunkDao().insertAll(listOf(chunk(31, "first", "first copy")))

        assertEquals(1, database.documentDao().deleteById(first.id))
        val repeated = document("second", DocumentStatus.QUEUED, now + 1).copy(sha256 = first.sha256)
        database.documentDao().upsert(repeated)

        assertNotNull(database.documentDao().findById(repeated.id))
        assertTrue(database.chunkDao().findByDocument(first.id).isEmpty())
    }

    @Test
    fun replacingDocumentChunksUpdatesFtsInTheSameTransaction() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        database.documentDao().upsert(document("doc", DocumentStatus.CHUNKING, now))
        database.chunkDao().replaceForDocument("doc", listOf(chunk(10, "doc", "旧合同内容")))

        database.chunkDao().replaceForDocument("doc", listOf(
            chunk(11, "doc", "项目验收编号"),
            chunk(12, "doc", "付款条件"),
        ))

        assertEquals(listOf(11L, 12L), database.chunkDao().findByDocument("doc").map { it.id })
        assertEquals(2L, ftsRowCount())
    }

    @Test
    fun failedChunkReplacementRollsBackDeletedRowsAndFts() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        database.documentDao().upsert(document("doc", DocumentStatus.CHUNKING, now))
        database.chunkDao().replaceForDocument("doc", listOf(chunk(20, "doc", "原始内容")))

        assertThrows(Exception::class.java) {
            runBlocking {
                database.chunkDao().replaceForDocument(
                    "doc",
                    listOf(chunk(21, "doc", "新内容一"), chunk(21, "doc", "重复主键")),
                )
            }
        }

        assertEquals(listOf(20L), database.chunkDao().findByDocument("doc").map { it.id })
        assertEquals(1L, ftsRowCount())
    }

    @Test
    fun batchedReplacementConsumesIncrementallyInsideOneTransaction() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        database.documentDao().upsert(document("doc", DocumentStatus.CHUNKING, now))
        var consumed = 0
        val chunks = sequence {
            repeat(130) { ordinal ->
                consumed++
                yield(chunk(1_000L + ordinal, "doc", "内容$ordinal").copy(ordinal = ordinal))
            }
        }

        val count = database.chunkDao().replaceForDocumentBatched("doc", chunks, batchSize = 16)

        assertEquals(130, count)
        assertEquals(130, consumed)
        assertEquals(130, database.chunkDao().findByDocument("doc").size)
        assertEquals(130L, ftsRowCount())
    }

    @Test
    fun embeddingBatchPersistsVectorsAndReadyStateAtomically() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        database.documentDao().upsert(document("doc", DocumentStatus.EMBEDDING, now))
        database.chunkDao().insertAll(listOf(chunk(31, "doc", "first"), chunk(32, "doc", "second")))

        database.chunkDao().storeEmbeddingBatch(listOf(
            ChunkEmbeddingEntity(31, "a".repeat(64), 2, FloatVectorCodec.encode(floatArrayOf(1f, 0f)), now),
            ChunkEmbeddingEntity(32, "a".repeat(64), 2, FloatVectorCodec.encode(floatArrayOf(0f, 1f)), now),
        ))

        assertEquals(2, database.chunkDao().findEmbeddingsByDocument("doc").size)
        assertTrue(database.chunkDao().findByDocument("doc").all { it.embeddingState == ChunkEntity.EMBEDDING_READY })
        assertTrue(database.chunkDao().findChunksNeedingEmbedding("doc", "a".repeat(64)).isEmpty())
    }

    @Test
    fun conversationRagSelectionsAreIsolatedAndEmptySelectionDisablesRetrieval() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-2", "Legal", "legal", now, now))

        database.conversationRagDao().replaceSelection(11, listOf("kb-1"), enabled = true, updatedAt = now)
        database.conversationRagDao().replaceSelection(22, listOf("kb-2"), enabled = true, updatedAt = now)

        assertEquals(listOf("kb-1"), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(11))
        assertEquals(listOf("kb-2"), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(22))

        database.conversationRagDao().replaceSelection(11, emptyList(), enabled = true, updatedAt = now + 1)

        assertTrue(database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(11).isEmpty())
        assertEquals(false, database.conversationRagDao().findState(11)?.ragEnabled)
        assertEquals(listOf("kb-2"), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(22))
    }

    @Test
    fun disablingConversationRagKeepsSelectionButReturnsNoKnowledgeBases() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-1", "Office", "office", now, now))
        val dao = database.conversationRagDao()
        dao.replaceSelection(33, listOf("kb-1"), enabled = true, updatedAt = now)

        dao.setEnabled(33, enabled = false, updatedAt = now + 1)

        assertTrue(dao.findSelectedEnabledKnowledgeBaseIds(33).isEmpty())
        assertEquals(listOf("kb-1"), dao.findBoundKnowledgeBaseIds(33))
    }

    private fun ftsRowCount(): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM chunk_fts")
        .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun document(
        id: String,
        status: DocumentStatus,
        now: Long,
        knowledgeBaseId: String = "kb-1",
    ) = DocumentEntity(
        id = id,
        knowledgeBaseId = knowledgeBaseId,
        displayName = "$id.txt",
        sourceUri = null,
        privateFileName = "$id.source",
        mimeType = "text/plain",
        detectedType = "text/plain",
        sha256 = id.padEnd(64, '0'),
        sizeBytes = 10,
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private fun chunk(
        id: Long,
        documentId: String,
        text: String,
        knowledgeBaseId: String = "kb-1",
    ) = ChunkEntity(
        id = id,
        documentId = documentId,
        knowledgeBaseId = knowledgeBaseId,
        ordinal = id.toInt(),
        text = text,
        searchText = text,
        displayName = "$documentId.txt",
        tokenCount = text.length,
        contentSha256 = id.toString().padEnd(64, '0'),
    )
}
