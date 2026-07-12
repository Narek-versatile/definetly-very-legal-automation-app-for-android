package com.legal.automation.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private companion object {
        const val KEY_USE_SHIZUKU = "use_shizuku"
        const val KEY_USERNAME = "username"
        const val KEY_REAL_TAPS = "real_taps"
    }
}
