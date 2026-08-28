package com.example.minicpm_v_demo.rag.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagDatabaseMigrationTest {
    private val databaseName = "rag-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RagDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrateEmptyDatabaseFrom1To2() {
        helper.createDatabase(databaseName, 1).close()

        helper.runMigrationsAndValidate(databaseName, 2, true, RagMigrations.MIGRATION_1_2).close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateEmptyDatabaseFrom2To3AddsEmbeddingStorage() {
        helper.createDatabase(databaseName, 2).close()

        helper.runMigrationsAndValidate(databaseName, 3, true, RagMigrations.MIGRATION_2_3).close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2PreservesContentResolvesNamesAndConvertsConversationId() {
        helper.createDatabase(databaseName, 1).apply {
            insertKnowledgeBase("kb-1", "Office", 1L)
            insertKnowledgeBase("kb-2", "ＯＦＦＩＣＥ", 2L)
            execSQL(
                """
                INSERT INTO documents
                (id, knowledgeBaseId, displayName, sourceUri, privateFileName, mimeType,
                 detectedType, sha256, sizeBytes, status, createdAt, updatedAt, progressDone,
                 progressTotal, parserVersion, chunkerVersion, lastErrorCode, lastErrorDetail)
                VALUES ('doc-1', 'kb-1', 'office.txt', NULL, 'doc-1.source', 'text/plain',
                        'text/plain', 'hash-1', 12, 'READY', 1, 1, 1, 1, 1, 1, NULL, NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chunks
                (id, documentId, knowledgeBaseId, ordinal, text, searchText, displayName,
                 titlePath, locatorType, locatorValue, tokenCount, contentSha256, embeddingState)
                VALUES (1, 'doc-1', 'kb-1', 0, 'office policy', 'office policy', 'office.txt',
                        NULL, 'line', '1', 2, 'chunk-hash-1', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO citations
                (messageId, sourceId, chunkId, documentId, locator, quotedText,
                 retrievalScore, retrievalVersion)
                VALUES ('message-1', 'source-1', 1, 'doc-1', 'line 1', 'office policy', 0.9, 1)
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO conversation_knowledge_bases (conversationId, knowledgeBaseId, enabled) VALUES (?, ?, 1)",
                arrayOf(Long.MAX_VALUE.toString(), "kb-1"),
            )
            execSQL(
                "INSERT INTO conversation_knowledge_bases (conversationId, knowledgeBaseId, enabled) VALUES ('7', 'kb-2', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            RagMigrations.MIGRATION_1_2,
        )

        assertEquals(
            listOf("Office" to "office", "OFFICE (2)" to "office (2)"),
            migrated.queryPairs("SELECT name, normalizedName FROM knowledge_bases ORDER BY createdAt, id"),
        )
        assertEquals(1, migrated.queryCount("documents"))
        assertEquals(1, migrated.queryCount("chunks"))
        assertEquals(1, migrated.queryCount("chunk_fts"))
        assertEquals(1, migrated.queryCount("citations"))
        assertEquals(
            Long.MAX_VALUE,
            migrated.query("SELECT conversationId FROM conversation_knowledge_bases").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            },
        )
        assertEquals(1, migrated.queryCount("conversation_knowledge_bases"))
        assertEquals(2, migrated.queryCount("conversation_rag_state"))
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun invalidConversationIdAbortsMigration() {
        helper.createDatabase(databaseName, 1).apply {
            insertKnowledgeBase("kb-1", "Office", 1L)
            execSQL(
                "INSERT INTO conversation_knowledge_bases (conversationId, knowledgeBaseId, enabled) VALUES ('01', 'kb-1', 1)",
            )
            close()
        }

        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(databaseName, 2, true, RagMigrations.MIGRATION_1_2)
        }
    }

    private fun SupportSQLiteDatabase.insertKnowledgeBase(id: String, name: String, createdAt: Long) {
        execSQL(
            """
            INSERT INTO knowledge_bases
            (id, name, createdAt, updatedAt, enabled, strictGrounding, embeddingModelId,
             embeddingModelSha256, indexVersion)
            VALUES (?, ?, ?, ?, 1, 1, 'intfloat/multilingual-e5-small', '', 1)
            """.trimIndent(),
            arrayOf<Any>(id, name, createdAt, createdAt),
        )
    }

    private fun SupportSQLiteDatabase.queryCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.queryPairs(query: String): List<Pair<String, String>> =
        query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
}
