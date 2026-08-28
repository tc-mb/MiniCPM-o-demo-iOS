package com.example.minicpm_v_demo.rag.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.minicpm_v_demo.rag.naming.KnowledgeBaseNamePolicy
import com.example.minicpm_v_demo.rag.naming.KnowledgeBaseNameValidationException

object RagMigrations {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chunk_embeddings (
                    chunkId INTEGER NOT NULL, modelSha256 TEXT NOT NULL,
                    dimension INTEGER NOT NULL, vector BLOB NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(chunkId),
                    FOREIGN KEY(chunkId) REFERENCES chunks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chunk_embeddings_modelSha256 ON chunk_embeddings(modelSha256)")
        }
    }

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Some Android SQLite builds retain legacy ALTER TABLE behavior unless
            // this is explicit, leaving child foreign keys pointed at *_v2 names.
            db.execSQL("PRAGMA legacy_alter_table=OFF")
            val database = db
            val names = migratedNames(database)
            val conversationIds = validatedConversationIds(database)

            createV2Tables(database)
            copyKnowledgeBases(database, names)
            copyDependentContent(database)
            copyConversationState(database, conversationIds)
            replaceV1Tables(database)
            createV2IndicesAndFts(database)
        }
    }

    private data class MigratedName(
        val id: String,
        val displayName: String,
        val normalizedName: String,
    )

    private fun migratedNames(database: SupportSQLiteDatabase): List<MigratedName> {
        val usedNames = mutableSetOf<String>()
        return database.query("SELECT id, name FROM knowledge_bases ORDER BY createdAt ASC, id ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val rawName = cursor.getString(1)
                    val base = try {
                        KnowledgeBaseNamePolicy.validateAndNormalize(rawName).displayName
                    } catch (_: KnowledgeBaseNameValidationException) {
                        "知识库"
                    }
                    var suffixNumber = 1
                    var validated = KnowledgeBaseNamePolicy.validateAndNormalize(base)
                    while (!usedNames.add(validated.normalizedName)) {
                        suffixNumber += 1
                        val suffix = " ($suffixNumber)"
                        val maxBaseCodePoints = KnowledgeBaseNamePolicy.MAX_CODE_POINTS - suffix.codePointCount(0, suffix.length)
                        val candidate = base.takeCodePoints(maxBaseCodePoints) + suffix
                        validated = KnowledgeBaseNamePolicy.validateAndNormalize(candidate)
                    }
                    add(MigratedName(id, validated.displayName, validated.normalizedName))
                }
            }
        }
    }

    private fun validatedConversationIds(database: SupportSQLiteDatabase): Map<String, Long> =
        database.query("SELECT DISTINCT conversationId FROM conversation_knowledge_bases ORDER BY conversationId").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val stored = cursor.getString(0)
                    val parsed = stored.toLongOrNull()
                    if (parsed == null || parsed < 0 || parsed.toString() != stored) {
                        throw IllegalStateException("Invalid conversationId in RAG binding: $stored")
                    }
                    put(stored, parsed)
                }
            }
        }

    private fun createV2Tables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE knowledge_bases_v2 (
                id TEXT NOT NULL, name TEXT NOT NULL, normalizedName TEXT NOT NULL,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, enabled INTEGER NOT NULL,
                strictGrounding INTEGER NOT NULL, embeddingModelId TEXT NOT NULL,
                embeddingModelSha256 TEXT NOT NULL, indexVersion INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE documents_v2 (
                id TEXT NOT NULL, knowledgeBaseId TEXT NOT NULL, displayName TEXT NOT NULL,
                sourceUri TEXT, privateFileName TEXT NOT NULL, mimeType TEXT NOT NULL,
                detectedType TEXT NOT NULL, sha256 TEXT NOT NULL, sizeBytes INTEGER NOT NULL,
                status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                progressDone INTEGER NOT NULL, progressTotal INTEGER NOT NULL,
                parserVersion INTEGER NOT NULL, chunkerVersion INTEGER NOT NULL,
                lastErrorCode TEXT, lastErrorDetail TEXT, PRIMARY KEY(id),
                FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases_v2(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE chunks_v2 (
                id INTEGER NOT NULL, documentId TEXT NOT NULL, knowledgeBaseId TEXT NOT NULL,
                ordinal INTEGER NOT NULL, text TEXT NOT NULL, searchText TEXT NOT NULL,
                displayName TEXT NOT NULL, titlePath TEXT, locatorType TEXT NOT NULL,
                locatorValue TEXT NOT NULL, tokenCount INTEGER NOT NULL,
                contentSha256 TEXT NOT NULL, embeddingState INTEGER NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(documentId) REFERENCES documents_v2(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE citations_v2 (
                messageId TEXT NOT NULL, sourceId TEXT NOT NULL, chunkId INTEGER NOT NULL,
                documentId TEXT NOT NULL, locator TEXT NOT NULL, quotedText TEXT NOT NULL,
                retrievalScore REAL NOT NULL, retrievalVersion INTEGER NOT NULL,
                PRIMARY KEY(messageId, sourceId),
                FOREIGN KEY(chunkId) REFERENCES chunks_v2(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE conversation_knowledge_bases_v2 (
                conversationId INTEGER NOT NULL, knowledgeBaseId TEXT NOT NULL,
                PRIMARY KEY(conversationId, knowledgeBaseId),
                FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases_v2(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE conversation_rag_state (
                conversationId INTEGER NOT NULL, ragEnabled INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL, PRIMARY KEY(conversationId)
            )
            """.trimIndent(),
        )
    }

    private fun copyKnowledgeBases(database: SupportSQLiteDatabase, names: List<MigratedName>) {
        names.forEach { migrated ->
            database.execSQL(
                """
                INSERT INTO knowledge_bases_v2
                (id, name, normalizedName, createdAt, updatedAt, enabled, strictGrounding,
                 embeddingModelId, embeddingModelSha256, indexVersion)
                SELECT id, ?, ?, createdAt, updatedAt, enabled, strictGrounding,
                       embeddingModelId, embeddingModelSha256, indexVersion
                FROM knowledge_bases WHERE id = ?
                """.trimIndent(),
                arrayOf(migrated.displayName, migrated.normalizedName, migrated.id),
            )
        }
    }

    private fun copyDependentContent(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO documents_v2 SELECT * FROM documents")
        database.execSQL("INSERT INTO chunks_v2 SELECT * FROM chunks")
        database.execSQL("INSERT INTO citations_v2 SELECT * FROM citations")
    }

    private fun copyConversationState(database: SupportSQLiteDatabase, ids: Map<String, Long>) {
        ids.forEach { (stored, parsed) ->
            database.execSQL(
                "INSERT INTO conversation_rag_state (conversationId, ragEnabled, updatedAt) VALUES (?, 1, 0)",
                arrayOf(parsed),
            )
            database.execSQL(
                """
                INSERT INTO conversation_knowledge_bases_v2 (conversationId, knowledgeBaseId)
                SELECT ?, knowledgeBaseId FROM conversation_knowledge_bases
                WHERE conversationId = ? AND enabled = 1
                """.trimIndent(),
                arrayOf<Any>(parsed, stored),
            )
        }
    }

    private fun replaceV1Tables(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE citations")
        database.execSQL("DROP TABLE chunk_fts")
        database.execSQL("DROP TABLE chunks")
        database.execSQL("DROP TABLE documents")
        database.execSQL("DROP TABLE conversation_knowledge_bases")
        database.execSQL("DROP TABLE knowledge_bases")

        database.execSQL("ALTER TABLE knowledge_bases_v2 RENAME TO knowledge_bases")
        database.execSQL("ALTER TABLE documents_v2 RENAME TO documents")
        database.execSQL("ALTER TABLE chunks_v2 RENAME TO chunks")
        database.execSQL("ALTER TABLE citations_v2 RENAME TO citations")
        database.execSQL("ALTER TABLE conversation_knowledge_bases_v2 RENAME TO conversation_knowledge_bases")
    }

    private fun createV2IndicesAndFts(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE UNIQUE INDEX index_knowledge_bases_normalizedName ON knowledge_bases(normalizedName)")
        database.execSQL("CREATE INDEX index_documents_knowledgeBaseId ON documents(knowledgeBaseId)")
        database.execSQL("CREATE INDEX index_documents_knowledgeBaseId_status ON documents(knowledgeBaseId, status)")
        database.execSQL("CREATE UNIQUE INDEX index_documents_knowledgeBaseId_sha256 ON documents(knowledgeBaseId, sha256)")
        database.execSQL("CREATE INDEX index_chunks_documentId ON chunks(documentId)")
        database.execSQL("CREATE INDEX index_chunks_knowledgeBaseId ON chunks(knowledgeBaseId)")
        database.execSQL("CREATE UNIQUE INDEX index_chunks_documentId_ordinal ON chunks(documentId, ordinal)")
        database.execSQL("CREATE INDEX index_conversation_knowledge_bases_knowledgeBaseId ON conversation_knowledge_bases(knowledgeBaseId)")
        database.execSQL("CREATE INDEX index_citations_chunkId ON citations(chunkId)")
        database.execSQL("CREATE INDEX index_citations_documentId ON citations(documentId)")
        database.execSQL("CREATE VIRTUAL TABLE chunk_fts USING FTS4(searchText TEXT NOT NULL, titlePath TEXT, displayName TEXT NOT NULL, content=`chunks`)")
        database.execSQL("CREATE TRIGGER room_fts_content_sync_chunk_fts_BEFORE_UPDATE BEFORE UPDATE ON chunks BEGIN DELETE FROM chunk_fts WHERE docid=OLD.rowid; END")
        database.execSQL("CREATE TRIGGER room_fts_content_sync_chunk_fts_BEFORE_DELETE BEFORE DELETE ON chunks BEGIN DELETE FROM chunk_fts WHERE docid=OLD.rowid; END")
        database.execSQL("CREATE TRIGGER room_fts_content_sync_chunk_fts_AFTER_UPDATE AFTER UPDATE ON chunks BEGIN INSERT INTO chunk_fts(docid, searchText, titlePath, displayName) VALUES (NEW.rowid, NEW.searchText, NEW.titlePath, NEW.displayName); END")
        database.execSQL("CREATE TRIGGER room_fts_content_sync_chunk_fts_AFTER_INSERT AFTER INSERT ON chunks BEGIN INSERT INTO chunk_fts(docid, searchText, titlePath, displayName) VALUES (NEW.rowid, NEW.searchText, NEW.titlePath, NEW.displayName); END")
        database.execSQL("INSERT INTO chunk_fts(chunk_fts) VALUES('rebuild')")
    }

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        if (codePointCount(0, length) <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }
}
