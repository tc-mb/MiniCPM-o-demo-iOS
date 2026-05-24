package com.example.minicpm_v_demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that runs [LlamaEngine.downloadModels] outside the
 * Activity's lifecycle. Survives:
 *   - user switching to another app
 *   - the screen turning off (Doze still throttles network somewhat, but the
 *     PARTIAL_WAKE_LOCK keeps the CPU awake long enough to keep the socket
 *     alive; combined with the HTTP Range resume in LlamaEngine that's
 *     enough to make multi-GB downloads reliable)
 *   - Activity being destroyed (we're a service, not bound to it)
 *
 * Coordinates with the UI through [ModelDownloadController] (a simple
 * StateFlow singleton) - we deliberately don't expose a binder so observing
 * the service requires no IPC plumbing in the Activity.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_CANCEL) {
            Log.i(TAG, "Cancel requested")
            downloadJob?.cancel()
            ModelDownloadController.markCancelled()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Only one download at a time. If a job is already running, just
        // re-attach the foreground notification and bail.
        if (downloadJob?.isActive == true) {
            startForegroundWithNotification(buildNotification(getString(R.string.status_downloading)))
            return START_NOT_STICKY
        }

        startForegroundWithNotification(buildNotification(getString(R.string.download_preparing)))
        acquireWakeLock()
        ModelDownloadController.markStarted()

        downloadJob = scope.launch {
            try {
                LlamaEngine.downloadModels(applicationContext) { message ->
                    ModelDownloadController.publishProgress(message)
                    updateNotification(message)
                }
                ModelDownloadController.markCompleted()
                updateNotification(getString(R.string.download_complete_short))
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                ModelDownloadController.markFailed(e.message ?: e::class.java.simpleName)
                updateNotification(getString(R.string.download_failed, e.message))
            } finally {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        releaseWakeLock()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun updateNotification(message: String) {
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, ModelManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelPi = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_download_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPi
            )
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        // PARTIAL_WAKE_LOCK keeps the CPU running with the screen off, but
        // *not* the radio - that's fine, OkHttp/HttpURLConnection only need
        // the CPU awake to drive the socket; the modem stays in low power.
        // 30 minute hard timeout as a safety belt against leaks: a typical
        // download is well under that.
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MiniCPMV:ModelDownload"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 0xD0_C1

        const val ACTION_START = "com.example.minicpm_v_demo.action.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.example.minicpm_v_demo.action.DOWNLOAD_CANCEL"

        fun start(context: Context) {
            ensureNotificationChannel(context)
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            // Use startService instead of startForegroundService for the
            // cancel signal to avoid a stray foreground transition.
            context.startService(intent)
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

/**
 * Singleton that decouples the service from any specific Activity. The
 * Activity (or other UI) collects [status] to render progress; the service
 * publishes updates via the package-private [publishProgress] / [markFoo]
 * functions.
 *
 * This lives outside the service so that observers don't need to bind to the
 * service or worry about IPC - the JVM-local StateFlow is enough for our
 * single-process app.
 */
object ModelDownloadController {

    sealed class Status {
        object Idle : Status()
        data class Running(val message: String) : Status()
        object Completed : Status()
        object Cancelled : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isRunning: Boolean
        get() = _status.value is Status.Running

    internal fun markStarted() {
        _status.value = Status.Running("")
    }

    internal fun publishProgress(message: String) {
        // Only overwrite when we're actually in the running phase - we don't
        // want a late tail-progress callback to clobber a Completed/Failed
        // state.
        if (_status.value is Status.Running) {
            _status.value = Status.Running(message)
        }
    }

    internal fun markCompleted() {
        _status.value = Status.Completed
    }

    internal fun markFailed(reason: String) {
        _status.value = Status.Failed(reason)
    }

    internal fun markCancelled() {
        _status.value = Status.Cancelled
    }

    /**
     * Drop a terminal state (Completed/Cancelled/Failed) back to Idle once
     * the UI has acknowledged it. Calling this while a download is running
     * is a no-op.
     */
    fun acknowledge() {
        val cur = _status.value
        if (cur is Status.Completed || cur is Status.Cancelled || cur is Status.Failed) {
            _status.value = Status.Idle
        }
    }
}
