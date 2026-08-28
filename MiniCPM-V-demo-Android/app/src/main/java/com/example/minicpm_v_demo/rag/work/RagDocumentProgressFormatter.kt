package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentStatus

object RagDocumentProgressFormatter {
    fun format(status: DocumentStatus, done: Int, total: Int): String =
        if (total > 0) "${status.name} · $done/$total" else status.name
}
