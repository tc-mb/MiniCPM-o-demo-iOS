package com.example.minicpm_v_demo.rag.ui

data class FailedImportNotice(
    val id: String,
    val knowledgeBaseId: String,
    val displayName: String,
    val reason: String,
)
