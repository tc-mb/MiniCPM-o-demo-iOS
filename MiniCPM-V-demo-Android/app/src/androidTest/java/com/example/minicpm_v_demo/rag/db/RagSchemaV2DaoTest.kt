package com.example.minicpm_v_demo.rag.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagSchemaV2DaoTest {
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
    fun insertingEquivalentNameAbortsWithoutDeletingExistingDocuments() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(knowledgeBase("kb-existing", "Office", "office", now))
        database.documentDao().upsert(document("doc-existing", "kb-existing", now))

        try {
            database.knowledgeBaseDao().insert(knowledgeBase("kb-new", "ＯＦＦＩＣＥ", "office", now + 1))
            fail("Expected the normalized-name unique constraint to abort")
        } catch (_: SQLiteConstraintException) {
            // Expected.
        }

        assertNotNull(database.documentDao().findById("doc-existing"))
        assertEquals(listOf("kb-existing"), database.knowledgeBaseDao().findAll().map { it.id })
    }

    @Test
    fun insertingDifferentNormalizedNamesSucceeds() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(knowledgeBase("kb-one", "Project One", "project one", now))
        database.knowledgeBaseDao().insert(knowledgeBase("kb-two", "Project Two", "project two", now + 1))

        assertEquals(setOf("kb-one", "kb-two"), database.knowledgeBaseDao().findAll().map { it.id }.toSet())
    }

    @Test
    fun selectedKnowledgeBasesRequireEnabledConversationAndEnabledKnowledgeBase() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(knowledgeBase("kb-enabled", "Enabled", "enabled", now))
        database.knowledgeBaseDao().insert(knowledgeBase("kb-disabled", "Disabled", "disabled", now, enabled = false))
        database.conversationRagDao().upsertState(ConversationRagStateEntity(42L, ragEnabled = false, updatedAt = now))
        database.conversationRagDao().insertBindings(
            listOf(
                ConversationKnowledgeBaseCrossRef(42L, "kb-enabled"),
                ConversationKnowledgeBaseCrossRef(42L, "kb-disabled"),
            ),
        )

        assertEquals(emptyList<String>(), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(42L))

        database.conversationRagDao().upsertState(ConversationRagStateEntity(42L, ragEnabled = true, updatedAt = now + 1))

        assertEquals(listOf("kb-enabled"), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(42L))
        assertEquals(emptyList<String>(), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(99L))
    }

    @Test
    fun deletingConversationRagStateAlsoDeletesBindings() = runBlocking {
        val now = 1_723_200_000_000L
        database.knowledgeBaseDao().insert(knowledgeBase("kb-one", "Project", "project", now))
        database.conversationRagDao().upsertState(ConversationRagStateEntity(Long.MAX_VALUE, true, now))
        database.conversationRagDao().insertBindings(
            listOf(ConversationKnowledgeBaseCrossRef(Long.MAX_VALUE, "kb-one")),
        )

        database.conversationRagDao().deleteConversation(Long.MAX_VALUE)

        assertEquals(null, database.conversationRagDao().findState(Long.MAX_VALUE))
        assertEquals(emptyList<String>(), database.conversationRagDao().findSelectedEnabledKnowledgeBaseIds(Long.MAX_VALUE))
    }

    private fun knowledgeBase(
        id: String,
        name: String,
        normalizedName: String,
        now: Long,
        enabled: Boolean = true,
    ) = KnowledgeBaseEntity(
        id = id,
        name = name,
        normalizedName = normalizedName,
        createdAt = now,
        updatedAt = now,
        enabled = enabled,
    )

    private fun document(id: String, knowledgeBaseId: String, now: Long) = DocumentEntity(
        id = id,
        knowledgeBaseId = knowledgeBaseId,
        displayName = "$id.txt",
        sourceUri = null,
        privateFileName = "$id.source",
        mimeType = "text/plain",
        detectedType = "text/plain",
        sha256 = id.padEnd(64, '0'),
        sizeBytes = 10,
        status = DocumentStatus.READY,
        createdAt = now,
        updatedAt = now,
    )
}
