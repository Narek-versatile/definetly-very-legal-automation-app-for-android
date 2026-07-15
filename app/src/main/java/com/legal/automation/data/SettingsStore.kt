package com.legal.automation.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** How the vote sweep is triggered on a 3-hour cadence. */
enum class SweepSchedule {
    /** Only runs when you tap Start. */
    OFF,

    /** Every 3 hours, posts a notification with a "Start sweep" button. */
    REMINDER,

    /** Every 3 hours, warns 5 and 1 minutes ahead, then runs the sweep itself. */
    AUTOMATIC,
}

/**
 * Small persistent settings. The key one is [useShizuku]: when the user turns
 * it off (or Shizuku just isn't around), the engine runs in **non-Shizuku
 * mode** and relies entirely on the AccessibilityService, so the app keeps
 * working with the same automations — Shizuku is an accelerator, never a
 * requirement.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _useShizuku = MutableStateFlow(prefs.getBoolean(KEY_USE_SHIZUKU, true))
    val useShizuku: StateFlow<Boolean> = _useShizuku.asStateFlow()

    /**
     * Your Minecraft username (or any reusable value). Steps can reference it
     * as the token `{username}`, so one setting fills every vote form.
     */
    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, "").orEmpty())
    val username: StateFlow<String> = _username.asStateFlow()

    /**
     * When on, taps use a real gesture touch first (fires web buttons that
     * ignore accessibility clicks); when off, accessibility ACTION_CLICK first
     * (bypasses ad overlays). Different vote sites need different methods.
     */
    private val _realTaps = MutableStateFlow(prefs.getBoolean(KEY_REAL_TAPS, false))
    val realTaps: StateFlow<Boolean> = _realTaps.asStateFlow()

    fun setUseShizuku(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SHIZUKU, value).apply()
        _useShizuku.value = value
    }

    fun setUsername(value: String) {
        prefs.edit().putString(KEY_USERNAME, value).apply()
        _username.value = value
    }

    fun setRealTaps(value: Boolean) {
        prefs.edit().putBoolean(KEY_REAL_TAPS, value).apply()
        _realTaps.value = value
    }

    // --- Vote sweep: nicknames + the "come back and do the next one" cycle ---

    private val listSerializer = ListSerializer(String.serializer())

    /** Nicknames to sweep through, one full vote-1..7 chain per entry. */
    private val _nicknames = MutableStateFlow(loadNicknames())
    val nicknames: StateFlow<List<String>> = _nicknames.asStateFlow()

    private fun loadNicknames(): List<String> =
        prefs.getString(KEY_NICKNAMES, null)
            ?.let { runCatching { AppJson.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    fun setNicknames(value: List<String>) {
        prefs.edit().putString(KEY_NICKNAMES, AppJson.encodeToString(listSerializer, value)).apply()
        _nicknames.value = value
    }

    /** Wait between each vote in the chain (and, absent a post-cycle app, between nicknames too). */
    private val _betweenVotesMs = MutableStateFlow(prefs.getLong(KEY_BETWEEN_VOTES_MS, 10000))
    val betweenVotesMs: StateFlow<Long> = _betweenVotesMs.asStateFlow()

    fun setBetweenVotesMs(value: Long) {
        prefs.edit().putLong(KEY_BETWEEN_VOTES_MS, value).apply()
        _betweenVotesMs.value = value
    }

    /** App launched after each nickname's chain finishes (null = skip this part of the cycle). */
    private val _postCycleAppPackage =
        MutableStateFlow(prefs.getString(KEY_POST_APP_PKG, null))
    val postCycleAppPackage: StateFlow<String?> = _postCycleAppPackage.asStateFlow()

    private val _postCycleAppLabel =
        MutableStateFlow(prefs.getString(KEY_POST_APP_LABEL, "").orEmpty())
    val postCycleAppLabel: StateFlow<String> = _postCycleAppLabel.asStateFlow()

    fun setPostCycleApp(packageName: String?, label: String) {
        prefs.edit().putString(KEY_POST_APP_PKG, packageName).putString(KEY_POST_APP_LABEL, label).apply()
        _postCycleAppPackage.value = packageName
        _postCycleAppLabel.value = label
    }

    /** Recorded tap location (screen coordinates) inside the post-cycle app. */
    private val _postCycleTapX =
        MutableStateFlow(prefs.getInt(KEY_POST_TAP_X, -1).takeIf { it >= 0 })
    val postCycleTapX: StateFlow<Int?> = _postCycleTapX.asStateFlow()

    private val _postCycleTapY =
        MutableStateFlow(prefs.getInt(KEY_POST_TAP_Y, -1).takeIf { it >= 0 })
    val postCycleTapY: StateFlow<Int?> = _postCycleTapY.asStateFlow()

    fun setPostCycleTap(x: Int?, y: Int?) {
        val editor = prefs.edit()
        if (x != null) editor.putInt(KEY_POST_TAP_X, x) else editor.remove(KEY_POST_TAP_X)
        if (y != null) editor.putInt(KEY_POST_TAP_Y, y) else editor.remove(KEY_POST_TAP_Y)
        editor.apply()
        _postCycleTapX.value = x
        _postCycleTapY.value = y
    }

    /** How long to wait after the app-cycle tap before returning to this app. */
    private val _postCycleWaitMs = MutableStateFlow(prefs.getLong(KEY_POST_WAIT_MS, 15000))
    val postCycleWaitMs: StateFlow<Long> = _postCycleWaitMs.asStateFlow()

    fun setPostCycleWaitMs(value: Long) {
        prefs.edit().putLong(KEY_POST_WAIT_MS, value).apply()
        _postCycleWaitMs.value = value
    }

    /** Keep the screen awake while charging (via Shizuku) so a secure lock never
     *  re-engages during overnight sweeps. */
    private val _keepAwakeWhileCharging = MutableStateFlow(prefs.getBoolean(KEY_KEEP_AWAKE, false))
    val keepAwakeWhileCharging: StateFlow<Boolean> = _keepAwakeWhileCharging.asStateFlow()

    fun setKeepAwakeWhileCharging(value: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
        _keepAwakeWhileCharging.value = value
    }

    /** Whether/how the sweep runs automatically every 3 hours. */
    private val _sweepSchedule = MutableStateFlow(
        runCatching { SweepSchedule.valueOf(prefs.getString(KEY_SCHEDULE, null) ?: "OFF") }
            .getOrDefault(SweepSchedule.OFF),
    )
    val sweepSchedule: StateFlow<SweepSchedule> = _sweepSchedule.asStateFlow()

    fun setSweepSchedule(value: SweepSchedule) {
        prefs.edit().putString(KEY_SCHEDULE, value.name).apply()
        _sweepSchedule.value = value
    }

    private companion object {
        const val KEY_USE_SHIZUKU = "use_shizuku"
        const val KEY_USERNAME = "username"
        const val KEY_REAL_TAPS = "real_taps"
        const val KEY_NICKNAMES = "sweep_nicknames"
        const val KEY_BETWEEN_VOTES_MS = "sweep_between_votes_ms"
        const val KEY_POST_APP_PKG = "sweep_post_app_pkg"
        const val KEY_POST_APP_LABEL = "sweep_post_app_label"
        const val KEY_POST_TAP_X = "sweep_post_tap_x"
        const val KEY_POST_TAP_Y = "sweep_post_tap_y"
        const val KEY_POST_WAIT_MS = "sweep_post_wait_ms"
        const val KEY_SCHEDULE = "sweep_schedule"
        const val KEY_KEEP_AWAKE = "sweep_keep_awake"
    }
}
