package com.example.minicpm_v_demo.rag.db

enum class DocumentStatus {
    QUEUED,
    COPYING,
    PARSING,
    OCR,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    READY,
    PAUSED,
    FAILED,
    CANCELLED,
    STALE,
    DELETING;

    companion object {
        val activeWorkStates: Set<DocumentStatus> = setOf(
            COPYING,
            PARSING,
            OCR,
            CHUNKING,
            EMBEDDING,
            INDEXING,
        )
    }
}

/**
 * Central transition policy used by workers and database transactions.
 * Persistence code must validate with this policy before updating status and progress together.
 */
object DocumentStatusTransitionPolicy {
    private val forwardTransitions = mapOf(
        DocumentStatus.QUEUED to setOf(DocumentStatus.COPYING, DocumentStatus.CANCELLED),
        DocumentStatus.COPYING to setOf(DocumentStatus.PARSING),
        DocumentStatus.PARSING to setOf(DocumentStatus.OCR, DocumentStatus.CHUNKING),
        DocumentStatus.OCR to setOf(DocumentStatus.CHUNKING),
        DocumentStatus.CHUNKING to setOf(DocumentStatus.EMBEDDING),
        DocumentStatus.EMBEDDING to setOf(DocumentStatus.INDEXING),
        DocumentStatus.INDEXING to setOf(DocumentStatus.READY),
        DocumentStatus.READY to setOf(DocumentStatus.STALE, DocumentStatus.DELETING),
        DocumentStatus.STALE to setOf(DocumentStatus.EMBEDDING, DocumentStatus.INDEXING, DocumentStatus.DELETING),
        DocumentStatus.PAUSED to setOf(DocumentStatus.QUEUED, DocumentStatus.CANCELLED),
        DocumentStatus.FAILED to setOf(DocumentStatus.QUEUED, DocumentStatus.DELETING),
        DocumentStatus.CANCELLED to setOf(DocumentStatus.QUEUED, DocumentStatus.DELETING),
        DocumentStatus.DELETING to emptySet(),
    )

    fun canTransition(from: DocumentStatus, to: DocumentStatus): Boolean {
        if (from == to) return false
        if (from in DocumentStatus.activeWorkStates &&
            to in setOf(DocumentStatus.PAUSED, DocumentStatus.FAILED, DocumentStatus.CANCELLED)
        ) {
            return true
        }
        return to in forwardTransitions.getValue(from)
    }
}
