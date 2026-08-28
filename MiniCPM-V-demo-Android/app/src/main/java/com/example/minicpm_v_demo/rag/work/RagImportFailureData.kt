package com.example.minicpm_v_demo.rag.work

import androidx.work.Data
import com.example.minicpm_v_demo.rag.db.DocumentEntity

object RagImportFailureData {
    const val KEY_DOCUMENT_ID = "failureDocumentId"
    const val KEY_KNOWLEDGE_BASE_ID = "failureKnowledgeBaseId"
    const val KEY_DISPLAY_NAME = "failureDisplayName"
    const val KEY_ERROR_CODE = "failureErrorCode"

    private val PUBLIC_ERROR_CODES = setOf(
        "SOURCE_PERMISSION_LOST",
        "SOURCE_UNAVAILABLE",
        "SOURCE_TOO_LARGE",
        "EMPTY_SOURCE",
        "EMPTY_DOCUMENT",
        "UNSUPPORTED_TYPE",
        "DECLARATION_MISMATCH",
        "DUPLICATE_CONTENT",
        "ENCRYPTION_FAILED",
        "IO_FAILED",
        "IMPORT_COPY_FAILED",
        "TOKENIZER_MISMATCH",
        "CHUNK_FAILED",
        "PARSE_INVALID_ENCODING",
        "PARSE_TEXT_LIMIT_EXCEEDED",
        "PARSE_RECORD_TOO_LARGE",
        "PARSE_MALFORMED_DOCUMENT",
        "PARSE_UNSUPPORTED_FORMAT",
        "PARSE_FAILED",
        "OCR_FAILED",
        "EMBED_FAILED",
        "INDEX_FINALIZATION_FAILED",
    )

    fun encode(document: DocumentEntity, errorCode: String): Data = Data.Builder()
        .putString(KEY_DOCUMENT_ID, document.id)
        .putString(KEY_KNOWLEDGE_BASE_ID, document.knowledgeBaseId)
        .putString(KEY_DISPLAY_NAME, document.displayName.take(MAX_DISPLAY_NAME_CHARS))
        .putString(KEY_ERROR_CODE, errorCode.takeIf(PUBLIC_ERROR_CODES::contains) ?: "IMPORT_FAILED")
        .build()

    private const val MAX_DISPLAY_NAME_CHARS = 160
}
