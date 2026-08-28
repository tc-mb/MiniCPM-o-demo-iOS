package com.example.minicpm_v_demo.rag.work

object RagWorkContract {
    const val KEY_DOCUMENT_ID = "documentId"
    private val SAFE_DOCUMENT_ID = Regex("[A-Za-z0-9_-]{1,128}")

    fun uniqueWorkName(documentId: String): String {
        requireValidDocumentId(documentId)
        return "rag-index-$documentId"
    }

    fun inputValues(documentId: String): Map<String, String> {
        requireValidDocumentId(documentId)
        return mapOf(KEY_DOCUMENT_ID to documentId)
    }

    fun requireValidDocumentId(documentId: String) {
        require(SAFE_DOCUMENT_ID.matches(documentId)) { "Invalid document ID" }
    }
}
