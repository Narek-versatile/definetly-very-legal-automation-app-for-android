package com.legal.automation.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
