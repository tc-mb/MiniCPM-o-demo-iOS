package com.example.minicpm_v_demo.rag.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_bases",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean = true,
    val strictGrounding: Boolean = true,
    val embeddingModelId: String = "intfloat/multilingual-e5-small",
    val embeddingModelSha256: String = "",
    val indexVersion: Int = 1,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("knowledgeBaseId"),
        Index(value = ["knowledgeBaseId", "status"]),
        Index(value = ["knowledgeBaseId", "sha256"], unique = true),
    ],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val displayName: String,
    val sourceUri: String?,
    val privateFileName: String,
    val mimeType: String,
    val detectedType: String,
    val sha256: String,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val parserVersion: Int = 1,
    val chunkerVersion: Int = 1,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("documentId"),
        Index("knowledgeBaseId"),
        Index(value = ["documentId", "ordinal"], unique = true),
    ],
)
data class ChunkEntity(
    @PrimaryKey val id: Long,
    val documentId: String,
    val knowledgeBaseId: String,
    val ordinal: Int,
    val text: String,
    val searchText: String,
    /** Denormalized solely so the external-content FTS table can index the source name. */
    val displayName: String,
    val titlePath: String? = null,
    val locatorType: String = "none",
    val locatorValue: String = "",
    val tokenCount: Int,
    val contentSha256: String,
    val embeddingState: Int = EMBEDDING_PENDING,
) {
    companion object {
        const val EMBEDDING_PENDING = 0
        const val EMBEDDING_READY = 1
        const val EMBEDDING_FAILED = 2
    }
}

@Entity(
    tableName = "chunk_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("modelSha256")],
)
data class ChunkEmbeddingEntity(
    @PrimaryKey val chunkId: Long,
    val modelSha256: String,
    val dimension: Int,
    val vector: ByteArray,
    val updatedAt: Long,
)

@Fts4(contentEntity = ChunkEntity::class)
@Entity(tableName = "chunk_fts")
data class ChunkFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val searchText: String,
    val titlePath: String?,
    val displayName: String,
)

@Entity(
    tableName = "conversation_knowledge_bases",
    primaryKeys = ["conversationId", "knowledgeBaseId"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("knowledgeBaseId")],
)
data class ConversationKnowledgeBaseCrossRef(
    val conversationId: Long,
    val knowledgeBaseId: String,
)

@Entity(tableName = "conversation_rag_state")
data class ConversationRagStateEntity(
    @PrimaryKey val conversationId: Long,
    val ragEnabled: Boolean = false,
    val updatedAt: Long,
)

@Entity(
    tableName = "citations",
    primaryKeys = ["messageId", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chunkId"), Index("documentId")],
)
data class CitationEntity(
    val messageId: String,
    val sourceId: String,
    val chunkId: Long,
    val documentId: String,
    val locator: String,
    val quotedText: String,
    val retrievalScore: Double,
    val retrievalVersion: Int,
)
