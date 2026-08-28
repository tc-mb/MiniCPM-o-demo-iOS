package com.example.minicpm_v_demo.rag.work

import androidx.annotation.StringRes
import com.example.minicpm_v_demo.R
import com.example.minicpm_v_demo.rag.db.DocumentStatus

object RagDocumentStageResources {
    @StringRes
    fun bodyFor(status: DocumentStatus): Int = when (status) {
        DocumentStatus.QUEUED -> R.string.rag_document_stage_queued
        DocumentStatus.COPYING -> R.string.rag_document_stage_copying
        DocumentStatus.PARSING -> R.string.rag_document_stage_parsing
        DocumentStatus.OCR -> R.string.rag_document_stage_ocr
        DocumentStatus.CHUNKING -> R.string.rag_document_stage_chunking
        DocumentStatus.EMBEDDING -> R.string.rag_document_stage_embedding
        DocumentStatus.INDEXING -> R.string.rag_document_stage_indexing
        DocumentStatus.READY -> R.string.rag_document_stage_ready
        else -> throw IllegalArgumentException("Document status has no import-stage text: ${status.name}")
    }
}
