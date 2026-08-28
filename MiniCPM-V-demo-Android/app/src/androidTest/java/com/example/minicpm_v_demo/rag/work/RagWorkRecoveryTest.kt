package com.example.minicpm_v_demo.rag.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.db.KnowledgeBaseEntity
import com.example.minicpm_v_demo.rag.db.RagDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagWorkRecoveryTest {
    private lateinit var database: RagDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RagDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun restartRecoverySelectsOnlyInterruptedImports() = runBlocking {
        val now = System.currentTimeMillis()
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-recovery", "Recovery", "recovery", now, now))
        listOf(
            document("queued", DocumentStatus.QUEUED, now),
            document("copying", DocumentStatus.COPYING, now + 1),
            document("parsing", DocumentStatus.PARSING, now + 2),
            document("ocr", DocumentStatus.OCR, now + 3),
            document("chunking", DocumentStatus.CHUNKING, now + 4),
            document("cancelled", DocumentStatus.CANCELLED, now + 5),
            document("failed", DocumentStatus.FAILED, now + 6),
        ).forEach { database.documentDao().upsert(it) }

        // Use the database query itself as the persistence boundary. The coordinator contract
        // is covered by JVM tests; this connected test verifies Room reconstruction semantics.
        val recoverable = database.documentDao().findRecoverableImports().map { it.id }
        assertEquals(listOf("queued", "copying", "parsing", "ocr", "chunking"), recoverable)
    }

    @Test
    fun modelBindingRecoverySelectsOnlyTokenizerMismatchFailures() = runBlocking {
        val now = System.currentTimeMillis()
        database.knowledgeBaseDao().insert(KnowledgeBaseEntity("kb-recovery", "Recovery", "recovery", now, now))
        database.documentDao().upsert(
            document("model-mismatch", DocumentStatus.FAILED, now).copy(lastErrorCode = "TOKENIZER_MISMATCH"),
        )
        database.documentDao().upsert(
            document("other-failure", DocumentStatus.FAILED, now + 1).copy(lastErrorCode = "CHUNK_FAILED"),
        )

        assertEquals(
            listOf("model-mismatch"),
            database.documentDao().findRetryableModelBindingFailures().map { it.id },
        )
    }

    private fun document(id: String, status: DocumentStatus, now: Long) = DocumentEntity(
        id = id,
        knowledgeBaseId = "kb-recovery",
        displayName = "$id.txt",
        sourceUri = "content://test/$id",
        privateFileName = "$id.src.enc",
        mimeType = "text/plain",
        detectedType = "",
        sha256 = "pending:$id",
        sizeBytes = 1,
        status = status,
        createdAt = now,
        updatedAt = now,
    )
}
