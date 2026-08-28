package com.example.minicpm_v_demo.rag.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class ChunkFtsMatchInfoRow(
    val chunkId: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val matchInfo: ByteArray,
)

data class EmbeddingCorpusStamp(
    val embeddingCount: Int,
    val maximumUpdatedAt: Long,
    val chunkIdSum: Long,
)

@Dao
interface KnowledgeBaseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KnowledgeBaseEntity)

    @Query(
        """
        UPDATE knowledge_bases
        SET name = :name, normalizedName = :normalizedName, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateName(id: String, name: String, normalizedName: String, updatedAt: Long): Int

    @Query("SELECT * FROM knowledge_bases WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_bases WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): KnowledgeBaseEntity?

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM knowledge_bases ORDER BY updatedAt DESC")
    suspend fun findAll(): List<KnowledgeBaseEntity>

    @Query("UPDATE knowledge_bases SET embeddingModelSha256 = :sha256, updatedAt = :updatedAt WHERE embeddingModelId = :modelId AND embeddingModelSha256 != :sha256")
    suspend fun updateInstalledModelHash(modelId: String, sha256: String, updatedAt: Long): Int
}

@Dao
interface ConversationRagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: ConversationRagStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBindings(bindings: List<ConversationKnowledgeBaseCrossRef>)

    @Query("SELECT * FROM conversation_rag_state WHERE conversationId = :conversationId")
    suspend fun findState(conversationId: Long): ConversationRagStateEntity?

    @Query(
        """
        SELECT knowledge_bases.id
        FROM conversation_knowledge_bases
        JOIN conversation_rag_state
          ON conversation_rag_state.conversationId = conversation_knowledge_bases.conversationId
        JOIN knowledge_bases
          ON knowledge_bases.id = conversation_knowledge_bases.knowledgeBaseId
        WHERE conversation_knowledge_bases.conversationId = :conversationId
          AND conversation_rag_state.ragEnabled = 1
          AND knowledge_bases.enabled = 1
        ORDER BY knowledge_bases.id
        """,
    )
    suspend fun findSelectedEnabledKnowledgeBaseIds(conversationId: Long): List<String>

    @Query(
        """
        SELECT knowledgeBaseId FROM conversation_knowledge_bases
        WHERE conversationId = :conversationId
        ORDER BY knowledgeBaseId
        """,
    )
    suspend fun findBoundKnowledgeBaseIds(conversationId: Long): List<String>

    @Query(
        """
        SELECT DISTINCT documents.displayName
        FROM conversation_knowledge_bases
        JOIN documents
          ON documents.knowledgeBaseId = conversation_knowledge_bases.knowledgeBaseId
        WHERE conversation_knowledge_bases.conversationId = :conversationId
        ORDER BY documents.displayName
        """,
    )
    suspend fun findBoundDocumentNames(conversationId: Long): List<String>

    @Query(
        """
        SELECT COUNT(*)
        FROM conversation_knowledge_bases
        JOIN conversation_rag_state
          ON conversation_rag_state.conversationId = conversation_knowledge_bases.conversationId
        JOIN knowledge_bases
          ON knowledge_bases.id = conversation_knowledge_bases.knowledgeBaseId
        JOIN documents
          ON documents.knowledgeBaseId = conversation_knowledge_bases.knowledgeBaseId
        WHERE conversation_knowledge_bases.conversationId = :conversationId
          AND conversation_rag_state.ragEnabled = 1
          AND knowledge_bases.enabled = 1
          AND documents.status = 'READY'
        """,
    )
    suspend fun countReadyDocuments(conversationId: Long): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM conversation_knowledge_bases
        JOIN conversation_rag_state
          ON conversation_rag_state.conversationId = conversation_knowledge_bases.conversationId
        JOIN knowledge_bases
          ON knowledge_bases.id = conversation_knowledge_bases.knowledgeBaseId
        JOIN documents
          ON documents.knowledgeBaseId = conversation_knowledge_bases.knowledgeBaseId
        WHERE conversation_knowledge_bases.conversationId = :conversationId
          AND conversation_rag_state.ragEnabled = 1
          AND knowledge_bases.enabled = 1
          AND documents.status IN (
              'QUEUED', 'COPYING', 'PARSING', 'OCR', 'CHUNKING',
              'EMBEDDING', 'INDEXING', 'STALE'
          )
        """,
    )
    suspend fun countIndexingDocuments(conversationId: Long): Int

    @Query("DELETE FROM conversation_knowledge_bases WHERE conversationId = :conversationId")
    suspend fun deleteBindings(conversationId: Long): Int

    @Query("DELETE FROM conversation_rag_state WHERE conversationId = :conversationId")
    suspend fun deleteState(conversationId: Long): Int

    @Transaction
    suspend fun replaceSelection(
        conversationId: Long,
        knowledgeBaseIds: List<String>,
        enabled: Boolean,
        updatedAt: Long,
    ) {
        require(conversationId > 0 && updatedAt >= 0)
        val uniqueIds = knowledgeBaseIds.distinct()
        require(uniqueIds.size == knowledgeBaseIds.size && uniqueIds.all { it.isNotBlank() })
        deleteBindings(conversationId)
        if (uniqueIds.isNotEmpty()) {
            insertBindings(uniqueIds.map { ConversationKnowledgeBaseCrossRef(conversationId, it) })
        }
        upsertState(ConversationRagStateEntity(conversationId, enabled && uniqueIds.isNotEmpty(), updatedAt))
    }

    @Transaction
    suspend fun setEnabled(conversationId: Long, enabled: Boolean, updatedAt: Long) {
        require(conversationId > 0 && updatedAt >= 0)
        val hasSelection = findBoundKnowledgeBaseIds(conversationId).isNotEmpty()
        upsertState(ConversationRagStateEntity(conversationId, enabled && hasSelection, updatedAt))
    }

    @Transaction
    suspend fun deleteConversation(conversationId: Long) {
        deleteBindings(conversationId)
        deleteState(conversationId)
    }
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: String): DocumentEntity?

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM documents WHERE knowledgeBaseId = :knowledgeBaseId ORDER BY createdAt")
    suspend fun findByKnowledgeBase(knowledgeBaseId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE status IN ('QUEUED', 'COPYING', 'PARSING', 'OCR', 'CHUNKING', 'EMBEDDING', 'INDEXING') ORDER BY createdAt")
    suspend fun findRecoverableImports(): List<DocumentEntity>

    @Query(
        "SELECT * FROM documents " +
            "WHERE status = 'FAILED' AND lastErrorCode = 'TOKENIZER_MISMATCH' ORDER BY createdAt",
    )
    suspend fun findRetryableModelBindingFailures(): List<DocumentEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM documents
            WHERE knowledgeBaseId = :knowledgeBaseId
              AND sha256 = :sha256
              AND id != :excludingDocumentId
        )
        """,
    )
    suspend fun contentHashExists(
        knowledgeBaseId: String,
        sha256: String,
        excludingDocumentId: String,
    ): Boolean

    @Query(
        """
        UPDATE documents
        SET privateFileName = :privateFileName,
            detectedType = :detectedType,
            sha256 = :sha256,
            sizeBytes = :sizeBytes,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateImportedMetadata(
        id: String,
        privateFileName: String,
        detectedType: String,
        sha256: String,
        sizeBytes: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE documents
        SET status = :status,
            progressDone = :progressDone,
            progressTotal = :progressTotal,
            updatedAt = :updatedAt,
            lastErrorCode = :lastErrorCode,
            lastErrorDetail = :lastErrorDetail
        WHERE id = :id
        """,
    )
    suspend fun updateStatusAndProgress(
        id: String,
        status: DocumentStatus,
        progressDone: Int,
        progressTotal: Int,
        updatedAt: Long,
        lastErrorCode: String?,
        lastErrorDetail: String?,
    ): Int

    @Transaction
    suspend fun transition(
        id: String,
        to: DocumentStatus,
        progressDone: Int,
        progressTotal: Int,
        updatedAt: Long,
        lastErrorCode: String? = null,
        lastErrorDetail: String? = null,
    ) {
        require(progressDone >= 0 && progressTotal >= 0 && progressDone <= progressTotal) {
            "Invalid progress $progressDone/$progressTotal"
        }
        val current = requireNotNull(findById(id)) { "Unknown document $id" }
        require(DocumentStatusTransitionPolicy.canTransition(current.status, to)) {
            "Invalid document transition ${current.status} -> $to"
        }
        check(
            updateStatusAndProgress(
                id = id,
                status = to,
                progressDone = progressDone,
                progressTotal = progressTotal,
                updatedAt = updatedAt,
                lastErrorCode = lastErrorCode,
                lastErrorDetail = lastErrorDetail,
            ) == 1,
        )
    }
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("DELETE FROM chunks WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: String): Int

    @Transaction
    suspend fun replaceForDocument(documentId: String, chunks: List<ChunkEntity>) {
        require(chunks.all { it.documentId == documentId }) { "Chunk document mismatch" }
        require(chunks.map { it.ordinal }.distinct().size == chunks.size) { "Duplicate chunk ordinal" }
        deleteByDocument(documentId)
        insertAll(chunks)
    }

    @Transaction
    suspend fun replaceForDocumentBatched(
        documentId: String,
        chunks: Sequence<ChunkEntity>,
        batchSize: Int = 64,
    ): Int {
        require(batchSize in 1..256) { "Invalid chunk batch size" }
        deleteByDocument(documentId)
        val iterator = chunks.iterator()
        var count = 0
        var expectedOrdinal = 0
        while (iterator.hasNext()) {
            val batch = ArrayList<ChunkEntity>(batchSize)
            while (iterator.hasNext() && batch.size < batchSize) {
                val chunk = iterator.next()
                require(chunk.documentId == documentId) { "Chunk document mismatch" }
                require(chunk.ordinal == expectedOrdinal++) { "Chunk ordinals must be contiguous" }
                batch += chunk
            }
            insertAll(batch)
            count += batch.size
        }
        return count
    }

    @Query("SELECT * FROM chunks WHERE documentId = :documentId ORDER BY ordinal")
    suspend fun findByDocument(documentId: String): List<ChunkEntity>

    @Query("UPDATE chunks SET embeddingState = :state WHERE id IN (:chunkIds)")
    suspend fun updateEmbeddingState(chunkIds: List<Long>, state: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbeddings(embeddings: List<ChunkEmbeddingEntity>)

    @Query("SELECT * FROM chunk_embeddings WHERE chunkId IN (:chunkIds)")
    suspend fun findEmbeddings(chunkIds: List<Long>): List<ChunkEmbeddingEntity>

    @Query(
        """
        SELECT chunk_embeddings.* FROM chunk_embeddings
        JOIN chunks ON chunks.id = chunk_embeddings.chunkId
        WHERE chunks.documentId = :documentId
        ORDER BY chunks.ordinal
        """,
    )
    suspend fun findEmbeddingsByDocument(documentId: String): List<ChunkEmbeddingEntity>

    @Query(
        """
        SELECT chunks.* FROM chunks
        LEFT JOIN chunk_embeddings ON chunk_embeddings.chunkId = chunks.id
        WHERE chunks.documentId = :documentId
          AND (chunks.embeddingState != :readyState OR chunk_embeddings.chunkId IS NULL
               OR chunk_embeddings.modelSha256 != :modelSha256)
        ORDER BY chunks.ordinal
        """,
    )
    suspend fun findChunksNeedingEmbedding(
        documentId: String,
        modelSha256: String,
        readyState: Int = ChunkEntity.EMBEDDING_READY,
    ): List<ChunkEntity>

    @Query(
        """
        SELECT chunk_embeddings.* FROM chunk_embeddings
        JOIN chunks ON chunks.id = chunk_embeddings.chunkId
        JOIN documents ON documents.id = chunks.documentId
        JOIN knowledge_bases ON knowledge_bases.id = chunks.knowledgeBaseId
        WHERE chunks.knowledgeBaseId IN (:knowledgeBaseIds)
          AND documents.status = 'READY' AND knowledge_bases.enabled = 1
          AND documents.chunkerVersion = :corpusVersion
          AND chunk_embeddings.modelSha256 = :modelSha256
        """,
    )
    suspend fun findReadyEmbeddings(
        knowledgeBaseIds: List<String>,
        modelSha256: String,
        corpusVersion: Int,
    ): List<ChunkEmbeddingEntity>

    @Query(
        """
        SELECT COUNT(*) AS embeddingCount,
               COALESCE(MAX(chunk_embeddings.updatedAt), 0) AS maximumUpdatedAt,
               COALESCE(SUM(chunk_embeddings.chunkId), 0) AS chunkIdSum
        FROM chunk_embeddings
        JOIN chunks ON chunks.id = chunk_embeddings.chunkId
        JOIN documents ON documents.id = chunks.documentId
        JOIN knowledge_bases ON knowledge_bases.id = chunks.knowledgeBaseId
        WHERE chunks.knowledgeBaseId IN (:knowledgeBaseIds)
          AND documents.status = 'READY' AND knowledge_bases.enabled = 1
          AND documents.chunkerVersion = :corpusVersion
          AND chunk_embeddings.modelSha256 = :modelSha256
        """,
    )
    suspend fun findReadyEmbeddingStamp(
        knowledgeBaseIds: List<String>,
        modelSha256: String,
        corpusVersion: Int,
    ): EmbeddingCorpusStamp

    @Query(
        """
        SELECT chunk_embeddings.* FROM chunk_embeddings
        JOIN chunks ON chunks.id = chunk_embeddings.chunkId
        JOIN documents ON documents.id = chunks.documentId
        JOIN knowledge_bases ON knowledge_bases.id = chunks.knowledgeBaseId
        WHERE chunks.knowledgeBaseId IN (:knowledgeBaseIds)
          AND documents.status = 'READY' AND knowledge_bases.enabled = 1
          AND documents.chunkerVersion = :corpusVersion
          AND chunk_embeddings.modelSha256 = :modelSha256
        ORDER BY chunk_embeddings.chunkId
        LIMIT :pageSize OFFSET :offset
        """,
    )
    suspend fun findReadyEmbeddingsPage(
        knowledgeBaseIds: List<String>,
        modelSha256: String,
        corpusVersion: Int,
        pageSize: Int,
        offset: Int,
    ): List<ChunkEmbeddingEntity>

    @Query("SELECT * FROM chunks WHERE id IN (:chunkIds)")
    suspend fun findByIds(chunkIds: List<Long>): List<ChunkEntity>

    @Transaction
    suspend fun storeEmbeddingBatch(embeddings: List<ChunkEmbeddingEntity>) {
        require(embeddings.isNotEmpty())
        require(embeddings.map { it.chunkId }.distinct().size == embeddings.size)
        require(embeddings.all { it.dimension > 0 && it.vector.size == it.dimension * Float.SIZE_BYTES })
        upsertEmbeddings(embeddings)
        check(updateEmbeddingState(embeddings.map { it.chunkId }, ChunkEntity.EMBEDDING_READY) == embeddings.size)
    }

    @Query(
        """
        SELECT chunks.*
        FROM chunks
        JOIN chunk_fts ON chunk_fts.rowid = chunks.id
        JOIN documents ON documents.id = chunks.documentId
        JOIN knowledge_bases ON knowledge_bases.id = chunks.knowledgeBaseId
        WHERE chunk_fts MATCH :matchQuery
          AND chunks.knowledgeBaseId = :knowledgeBaseId
          AND documents.status = 'READY'
          AND knowledge_bases.enabled = 1
        ORDER BY chunks.id
        LIMIT :limit
        """,
    )
    suspend fun searchReadyChunks(
        matchQuery: String,
        knowledgeBaseId: String,
        limit: Int,
    ): List<ChunkEntity>

    @Query(
        """
        SELECT chunks.id AS chunkId,
               matchinfo(chunk_fts, 'pcnalx') AS matchInfo
        FROM chunks
        JOIN chunk_fts ON chunk_fts.rowid = chunks.id
        JOIN documents ON documents.id = chunks.documentId
        JOIN knowledge_bases ON knowledge_bases.id = chunks.knowledgeBaseId
        WHERE chunk_fts MATCH :matchQuery
          AND chunks.knowledgeBaseId IN (:knowledgeBaseIds)
          AND documents.status = 'READY'
          AND knowledge_bases.enabled = 1
          AND documents.chunkerVersion = :corpusVersion
        ORDER BY chunks.id
        LIMIT :scanLimit
        """,
    )
    suspend fun searchReadyChunkMatchInfo(
        matchQuery: String,
        knowledgeBaseIds: List<String>,
        corpusVersion: Int,
        scanLimit: Int,
    ): List<ChunkFtsMatchInfoRow>
}
