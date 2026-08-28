package com.example.minicpm_v_demo.rag.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        KnowledgeBaseEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkEmbeddingEntity::class,
        ChunkFtsEntity::class,
        ConversationKnowledgeBaseCrossRef::class,
        ConversationRagStateEntity::class,
        CitationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(RagDatabaseConverters::class)
abstract class RagDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun conversationRagDao(): ConversationRagDao

    companion object {
        const val DATABASE_NAME = "local-rag.db"
    }
}

class RagDatabaseConverters {
    @TypeConverter
    fun documentStatusToString(status: DocumentStatus): String = status.name

    @TypeConverter
    fun stringToDocumentStatus(value: String): DocumentStatus = DocumentStatus.valueOf(value)
}
