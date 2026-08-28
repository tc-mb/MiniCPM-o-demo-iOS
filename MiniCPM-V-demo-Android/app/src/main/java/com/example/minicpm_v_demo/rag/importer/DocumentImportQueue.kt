package com.example.minicpm_v_demo.rag.importer

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.minicpm_v_demo.rag.db.DocumentDao
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import com.example.minicpm_v_demo.rag.work.RagWorkCoordinator
import java.util.UUID

class DocumentImportQueue(
    private val contentResolver: ContentResolver,
    private val documentDao: DocumentDao,
    private val workCoordinator: RagWorkCoordinator,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun enqueue(uri: Uri, knowledgeBaseId: String): String {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs are accepted" }
        require(knowledgeBaseId.isNotBlank()) { "Knowledge base ID is required" }
        takeReadPermission(uri)
        val metadata = queryMetadata(uri)
        val documentId = newId()
        val timestamp = now()
        documentDao.upsert(
            DocumentEntity(
                id = documentId,
                knowledgeBaseId = knowledgeBaseId,
                displayName = metadata.displayName,
                sourceUri = uri.toString(),
                privateFileName = "$documentId.src.enc",
                mimeType = contentResolver.getType(uri).orEmpty(),
                detectedType = "",
                // A per-row pending value avoids colliding with the unique (KB, SHA) index.
                sha256 = "pending:$documentId",
                sizeBytes = metadata.sizeBytes ?: 0,
                status = DocumentStatus.QUEUED,
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
        workCoordinator.enqueue(documentId)
        return documentId
    }

    private fun takeReadPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.optionalString(OpenableColumns.DISPLAY_NAME)
                sizeBytes = cursor.optionalLong(OpenableColumns.SIZE)
            }
        }
        return SourceMetadata(
            displayName = displayName?.takeIf(String::isNotBlank) ?: "document",
            sizeBytes = sizeBytes?.takeIf { it >= 0 },
        )
    }

    private fun Cursor.optionalString(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.optionalLong(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private data class SourceMetadata(val displayName: String, val sizeBytes: Long?)
}
