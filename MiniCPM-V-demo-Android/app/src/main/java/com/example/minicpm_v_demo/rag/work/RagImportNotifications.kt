package com.example.minicpm_v_demo.rag.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.example.minicpm_v_demo.KnowledgeBaseActivity
import com.example.minicpm_v_demo.R
import com.example.minicpm_v_demo.rag.db.DocumentStatus

object RagImportNotifications {
    private const val CHANNEL_ID = "rag_document_import"

    fun foregroundInfo(
        context: Context,
        documentId: String,
        status: DocumentStatus,
    ): ForegroundInfo {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            documentId.hashCode(),
            Intent(context, KnowledgeBaseActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getBroadcast(
            context,
            documentId.hashCode(),
            Intent(context, RagImportCancelReceiver::class.java)
                .putExtra(RagWorkContract.KEY_DOCUMENT_ID, documentId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.rag_import_notification_title))
            .setContentText(context.getString(RagDocumentStageResources.bodyFor(status)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.cancel), cancelIntent)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(documentId.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(documentId.hashCode(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.rag_import_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }
}
