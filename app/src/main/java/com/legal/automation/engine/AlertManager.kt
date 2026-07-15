package com.legal.automation.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * The failure alert: a heads-up notification with alarm sound, a vibration
 * pattern, and (when available) the failure screenshot inlined.
 */
class AlertManager(private val context: Context) {

    companion object {
        const val CHANNEL_ALERTS = "automation_alerts"
        const val CHANNEL_RUNNER = "automation_runner"
        private var nextId = 1000
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Automation alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when an automation fails." },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNER,
                "Running automations",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while an automation is running." },
        )
    }

    fun alertFailure(automationName: String, stepDescription: String, screenshotPath: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("“$automationName” failed")
            .setContentText(stepDescription)
            .setStyle(NotificationCompat.BigTextStyle().bigText(stepDescription))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 300, 200, 300))

        screenshotPath?.let { path ->
            val bmp = runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
            if (bmp != null) {
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bmp)
                        .setSummaryText(stepDescription),
                )
            }
        }

        notify(builder.build().let { it })
        vibrate()
    }

    fun showScreenDump(appLabel: String, items: List<String>) {
        val body = if (items.isEmpty()) {
            "No readable text found on “$appLabel”. It hides its content from " +
                "accessibility, so text/id targeting can't work here — this page needs " +
                "a coordinate tap or a different app/browser."
        } else {
            "Saved to Scans (open the app → Scans to read all of it).\n\n" +
                items.joinToString("\n").take(4500)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Scanned “$appLabel” (${items.size} items)")
            .setContentText("Saved to Scans in the app")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        notify(builder.build())
    }

    fun alertManual(message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Action needed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200))
        notify(builder.build())
        vibrate()
    }

    /** The 3-hourly reminder (REMINDER mode): a notification with a button that
     *  starts the sweep on tap. */
    fun alertSweepReminder() {
        val startIntent = Intent(context, SweepAlarmReceiver::class.java)
            .setAction(SweepAlarmReceiver.ACTION_START_NOW)
        val pi = PendingIntent.getBroadcast(
            context, 100, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Time to vote")
            .setContentText("Tap Start to run the vote sweep for all your nicknames.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, "Start sweep", pi)
        notify(builder.build())
    }

    /** A heads-up a few minutes before an AUTOMATIC run kicks off. */
    fun alertSweepWarning(minutes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Auto vote in $minutes min")
            .setContentText("The vote sweep will start automatically in $minutes minute${if (minutes == 1) "" else "s"}.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        notify(builder.build())
    }

    /** Fires once, after the whole nickname sweep (every nickname's vote chain
     *  plus post-cycle steps) has finished. */
    fun alertSweepComplete(nicknameCount: Int, successCount: Int, failCount: Int) {
        val body = "$nicknameCount nickname${if (nicknameCount == 1) "" else "s"} swept — " +
            "$successCount vote${if (successCount == 1) "" else "s"} ok" +
            if (failCount > 0) ", $failCount failed" else ""
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Vote sweep finished")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200))
        notify(builder.build())
        vibrate()
    }

    fun alertSuccess(automationName: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("“$automationName” finished")
            .setContentText("All steps completed successfully.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        notify(builder.build())
    }

    private fun notify(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        NotificationManagerCompat.from(context).notify(nextId++, notification)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 300, 200, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }
}
