package com.legal.automation.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.legal.automation.App
import com.legal.automation.data.SweepSchedule
import com.legal.automation.ui.WakeActivity

/**
 * Receives the scheduled sweep alarms (and the "Start sweep now" button on the
 * reminder notification). REMINDER posts the actionable reminder and re-arms;
 * WARN posts a heads-up; RUN wakes the device and launches the sweep, then
 * arms the next cycle; START_NOW just launches the sweep.
 */
class SweepAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        when (intent.action) {
            ACTION_REMINDER -> {
                app.alerts.alertSweepReminder()
                // Only keep re-arming while the user still wants reminders.
                if (app.settings.sweepSchedule.value == SweepSchedule.REMINDER) {
                    SweepScheduler.scheduleReminder(context, System.currentTimeMillis() + SweepScheduler.INTERVAL_MS)
                }
            }

            ACTION_WARN -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                app.alerts.alertSweepWarning(minutes)
            }

            ACTION_RUN -> {
                if (app.settings.sweepSchedule.value == SweepSchedule.AUTOMATIC) {
                    startSweepWaking(context)
                    SweepScheduler.scheduleAutomatic(context, System.currentTimeMillis() + SweepScheduler.INTERVAL_MS)
                }
            }

            ACTION_START_NOW -> startSweep(context)
        }
    }

    /** Wake + dismiss (non-secure) keyguard first, since an auto run may fire
     *  while the phone is locked/asleep. */
    private fun startSweepWaking(context: Context) {
        val wake = WakeActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(wake) }
            .onFailure { startSweep(context) } // fall back to a direct start
    }

    private fun startSweep(context: Context) {
        runCatching { SweepRunnerService.start(context) }
    }

    companion object {
        const val ACTION_REMINDER = "com.legal.automation.SWEEP_REMINDER"
        const val ACTION_WARN = "com.legal.automation.SWEEP_WARN"
        const val ACTION_RUN = "com.legal.automation.SWEEP_RUN"
        const val ACTION_START_NOW = "com.legal.automation.SWEEP_START_NOW"
        const val EXTRA_MINUTES = "minutes"
    }
}
