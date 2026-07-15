package com.legal.automation.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.legal.automation.App
import com.legal.automation.data.SweepSchedule

/** Re-arms the sweep alarms after a reboot (alarms don't survive one). */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? App ?: return
        val schedule = app.settings.sweepSchedule.value
        if (schedule != SweepSchedule.OFF) {
            SweepScheduler.apply(context, schedule)
        }
    }
}
