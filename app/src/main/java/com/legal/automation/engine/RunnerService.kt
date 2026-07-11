package com.legal.automation.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.legal.automation.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs an automation as a foreground service so it survives the screen turning
 * off and isn't killed mid-run. Started with the automation id to execute.
 */
class RunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val automationId = intent?.getStringExtra(EXTRA_AUTOMATION_ID)
        val app = App.instance
        val automation = automationId?.let { app.store.get(it) }
        if (automation == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(automation.name)
        scope.launch {
            try {
                app.engine.run(automation)
            } finally {
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(name: String) {
        val notification = NotificationCompat.Builder(this, AlertManager.CHANNEL_RUNNER)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Running “$name”")
            .setContentText("Automation in progress…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val EXTRA_AUTOMATION_ID = "automation_id"

        fun start(context: Context, automationId: String) {
            val intent = Intent(context, RunnerService::class.java)
                .putExtra(EXTRA_AUTOMATION_ID, automationId)
            context.startForegroundService(intent)
        }
    }
}
