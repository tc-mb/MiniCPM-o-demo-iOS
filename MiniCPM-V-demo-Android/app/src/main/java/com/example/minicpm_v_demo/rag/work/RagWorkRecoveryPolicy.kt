package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentStatus

object RagWorkRecoveryPolicy {
    fun shouldReschedule(status: DocumentStatus): Boolean =
        status in setOf(
            DocumentStatus.QUEUED,
            DocumentStatus.COPYING,
            DocumentStatus.PARSING,
            DocumentStatus.OCR,
            DocumentStatus.CHUNKING,
        )

    fun <T> selectObservable(
        items: List<T>,
        isActive: (T) -> Boolean,
        isFailed: (T) -> Boolean,
    ): T? = items.firstOrNull(isActive) ?: items.firstOrNull(isFailed) ?: items.lastOrNull()
}
