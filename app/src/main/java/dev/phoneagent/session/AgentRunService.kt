package dev.phoneagent.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions by lazy { AgentSessions.get(this) }
    private val notifications by lazy { requireNotNull(getSystemService(NotificationManager::class.java)) }
    private var runningId: String? = null
    private var latestStartId = 0
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> if (id == null) stopSelf(startId) else startRun(id)
            ACTION_CANCEL -> {
                if (id != null && id == runningId) sessions.cancel(id)
                if (runningId == null) stopSelf(startId)
            }
            else -> if (runningId == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRun(id: String) {
        val current = sessions.current.value?.takeIf { it.id == id && it.status.active }
        try {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent execution", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = notification(current)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            sessions.foregroundFailed(id)
            stopSelf(latestStartId)
            return
        }
        if (current == null) {
            // The request reached Android before a rapid cancellation. Fulfill the foreground
            // startup contract, then stop without running a command.
            stopSelf(latestStartId)
            return
        }
        observer?.cancel()
        runningId = id
        sessions.foregroundReady(id)
        observer = scope.launch {
            sessions.current.collect { snapshot ->
                if (snapshot == null || snapshot.id != id || !snapshot.status.active) {
                    stopSelf(latestStartId)
                    cancel()
                } else {
                    try {
                        notifications.notify(NOTIFICATION_ID, notification(snapshot))
                    } catch (_: SecurityException) {
                        // Android permits active foreground services with notifications disabled.
                    }
                }
            }
        }
    }

    private fun notification(snapshot: SessionSnapshot?): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PHONE AGENT")
            .setContentText(snapshot?.statusMessage ?: "Starting agent")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        if (open != null) builder.setContentIntent(open)
        if (snapshot != null) {
            val cancel = PendingIntent.getService(
                this,
                0,
                Intent(this, AgentRunService::class.java)
                    .setAction(ACTION_CANCEL)
                    .setIdentifier(snapshot.id)
                    .putExtra(EXTRA_ID, snapshot.id),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "Stop", cancel).build())
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runningId?.let(sessions::interrupt)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        runningId?.let(sessions::interrupt)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "dev.phoneagent.session.START"
        internal const val ACTION_CANCEL = "dev.phoneagent.session.CANCEL"
        internal const val EXTRA_ID = "session_id"
        private const val NOTIFICATION_ID = 410
        private const val CHANNEL = "agent_execution"
    }
}
