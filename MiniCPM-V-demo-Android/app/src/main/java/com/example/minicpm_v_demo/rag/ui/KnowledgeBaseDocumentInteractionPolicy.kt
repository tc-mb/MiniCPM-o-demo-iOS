package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.rag.db.DocumentStatus

object KnowledgeBaseDocumentInteractionPolicy {
    fun canDeleteByLongPress(status: DocumentStatus): Boolean = status == DocumentStatus.READY
}
