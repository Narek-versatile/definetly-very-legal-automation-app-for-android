package com.legal.automation.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.legal.automation.service.AutomationAccessibilityService

/** Helpers for checking/opening the OS-level permissions the app depends on. */
object SystemAccess {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutomationAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // --- Overnight/auto setup deep-links -------------------------------------

    private fun pkgUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun start(context: Context, intent: Intent, fallback: (() -> Intent)? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { fallback?.let { fb -> runCatching { context.startActivity(fb().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }

    /** The app's own details page — where Samsung's Battery → Unrestricted lives. */
    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(context)))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Prompts to exempt the app from battery optimization (Doze). */
    fun openBatteryOptimization(context: Context) {
        @Suppress("BatteryLife")
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(context))
        start(context, request) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** The per-app "Alarms & reminders" screen (Android 12+). */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(context))) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(context))
            }
        } else {
            openAppDetails(context)
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** "Display over other apps" — helps background launches on Samsung. */
    fun openOverlaySettings(context: Context) {
        start(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(context))) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        }
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        start(context, intent) { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(context)) }
    }
}
