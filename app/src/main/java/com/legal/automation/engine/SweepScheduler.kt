package com.legal.automation.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.legal.automation.data.SweepSchedule

/**
 * Arms/cancels the 3-hourly vote-sweep alarms via [AlarmManager]. For
 * [SweepSchedule.REMINDER] a single reminder alarm re-arms itself each time it
 * fires; for [SweepSchedule.AUTOMATIC] each cycle sets a run alarm plus two
 * warning alarms (5 min and 1 min before).
 *
 * Alarms are best-effort exact (`setExactAndAllowWhileIdle`) when the OS allows
 * it, so they still fire in Doze overnight; otherwise they fall back to an
 * inexact allow-while-idle alarm.
 */
object SweepScheduler {

    const val INTERVAL_MS = 3 * 60 * 60 * 1000L

    // Distinct request codes so each pending alarm can be targeted/cancelled.
    private const val CODE_REMINDER = 1
    private const val CODE_WARN_5 = 2
    private const val CODE_WARN_1 = 3
    private const val CODE_RUN = 4

    fun apply(context: Context, schedule: SweepSchedule) {
        cancelAll(context)
        when (schedule) {
            SweepSchedule.OFF -> Unit
            SweepSchedule.REMINDER -> scheduleReminder(context, System.currentTimeMillis() + INTERVAL_MS)
            SweepSchedule.AUTOMATIC -> scheduleAutomatic(context, System.currentTimeMillis() + INTERVAL_MS)
        }
    }

    /** Called from the receiver after a reminder fires, to arm the next one. */
    fun scheduleReminder(context: Context, triggerAt: Long) {
        setExact(context, triggerAt, CODE_REMINDER, SweepAlarmReceiver.ACTION_REMINDER)
    }

    /** Called from the receiver after a run fires, to arm the next cycle. */
    fun scheduleAutomatic(context: Context, runAt: Long) {
        setExact(context, runAt - 5 * 60_000, CODE_WARN_5, SweepAlarmReceiver.ACTION_WARN, minutes = 5)
        setExact(context, runAt - 60_000, CODE_WARN_1, SweepAlarmReceiver.ACTION_WARN, minutes = 1)
        setExact(context, runAt, CODE_RUN, SweepAlarmReceiver.ACTION_RUN)
    }

    private fun setExact(context: Context, triggerAt: Long, code: Int, action: String, minutes: Int = 0) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SweepAlarmReceiver::class.java).setAction(action)
        if (minutes > 0) intent.putExtra(SweepAlarmReceiver.EXTRA_MINUTES, minutes)
        val pi = PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(
            CODE_REMINDER to SweepAlarmReceiver.ACTION_REMINDER,
            CODE_WARN_5 to SweepAlarmReceiver.ACTION_WARN,
            CODE_WARN_1 to SweepAlarmReceiver.ACTION_WARN,
            CODE_RUN to SweepAlarmReceiver.ACTION_RUN,
        ).forEach { (code, action) ->
            val intent = Intent(context, SweepAlarmReceiver::class.java).setAction(action)
            val pi = PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pi?.let { am.cancel(it); it.cancel() }
        }
    }
}
