package com.legal.automation.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.legal.automation.App
import com.legal.automation.model.Automation
import com.legal.automation.model.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the full vote sweep as a foreground service: for each nickname, the
 * vote-1..7 chain in order (waiting [com.legal.automation.data.SettingsStore.betweenVotesMs]
 * between votes), then — if a post-cycle app is configured — launches that
 * app, taps the recorded location, waits, and returns to this app before
 * moving to the next nickname. Reports once at the end.
 */
class SweepRunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = App.instance
        startAsForeground()
        acquireWakeLock()
        scope.launch {
            try {
                runSweep(app)
            } finally {
                releaseWakeLock()
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Keeps the screen on (bright) for the whole sweep so the accessibility
     * service can keep tapping and reading — Chrome won't render vote pages
     * with the screen off. Capped so a stuck run can't hold it forever.
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "LegalAutomation:sweep",
        ).also { runCatching { it.acquire(MAX_WAKE_MS) } }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private suspend fun runSweep(app: App) {
        val nicknames = app.settings.nicknames.value
        val automations = VOTE_IDS.mapNotNull { app.store.get(it) }
        val originalUsername = app.settings.username.value
        var successCount = 0
        var failCount = 0

        if (nicknames.isEmpty() || automations.isEmpty()) {
            return
        }

        SweepRunState.update {
            it.copy(running = true, totalNicknames = nicknames.size, totalVotes = automations.size)
        }

        for ((nicknameIndex, nickname) in nicknames.withIndex()) {
            SweepRunState.update {
                it.copy(nicknameIndex = nicknameIndex, currentNickname = nickname, phase = "Launching app")
            }
            app.settings.setUsername(nickname)

            // Pre-cycle: before this nickname's votes, optionally launch the
            // chosen app, tap the recorded spot, wait, then return here. Runs
            // once at the start of every nickname (e.g. to rotate IP first).
            val cyclePackage = app.settings.postCycleAppPackage.value
            if (cyclePackage != null) {
                app.engine.run(cycleAutomation(app, cyclePackage))
            }

            SweepRunState.update { it.copy(phase = "Voting") }
            for ((voteIndex, automation) in automations.withIndex()) {
                SweepRunState.update { it.copy(voteIndex = voteIndex) }
                val log = app.engine.run(automation)
                if (log.success) successCount++ else failCount++
                if (voteIndex < automations.size - 1) {
                    delay(app.settings.betweenVotesMs.value)
                }
            }

            // If there's no app cycle to space nicknames apart, still pause
            // between one nickname's chain and the next.
            if (cyclePackage == null && nicknameIndex < nicknames.size - 1) {
                delay(app.settings.betweenVotesMs.value)
            }
        }

        app.settings.setUsername(originalUsername)
        SweepRunState.reset()
        app.alerts.alertSweepComplete(nicknames.size, successCount, failCount)
    }

    private fun cycleAutomation(app: App, packageName: String): Automation {
        val x = app.settings.postCycleTapX.value
        val y = app.settings.postCycleTapY.value
        return Automation(
            name = "Sweep: app cycle",
            steps = listOfNotNull(
                Step.LaunchApp(packageName = packageName, appLabel = app.settings.postCycleAppLabel.value),
                Step.Sleep(ms = 1500),
                if (x != null && y != null) Step.TapXy(x = x, y = y) else null,
                Step.Sleep(ms = app.settings.postCycleWaitMs.value),
                Step.ReturnToApp(),
            ),
        )
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, AlertManager.CHANNEL_RUNNER)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Running vote sweep")
            .setContentText("Sweeping nicknames…")
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
        private const val NOTIF_ID = 43
        private const val MAX_WAKE_MS = 30 * 60 * 1000L // safety cap on the wakelock
        private val VOTE_IDS = listOf(
            "seed-vote-1", "seed-vote-2", "seed-vote-3", "seed-vote-4",
            "seed-vote-5", "seed-vote-6", "seed-vote-7",
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SweepRunnerService::class.java))
        }
    }
}
