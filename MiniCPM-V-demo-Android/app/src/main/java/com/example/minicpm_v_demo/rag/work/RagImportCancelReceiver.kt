package com.example.minicpm_v_demo.rag.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

class RagImportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val documentId = intent.getStringExtra(RagWorkContract.KEY_DOCUMENT_ID) ?: return
        if (runCatching { RagWorkContract.requireValidDocumentId(documentId) }.isFailure) return
        WorkManagerRagWorkCoordinator(WorkManager.getInstance(context)).cancel(documentId)
    }
}
