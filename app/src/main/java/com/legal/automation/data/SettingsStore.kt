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

    fun setUseShizuku(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SHIZUKU, value).apply()
        _useShizuku.value = value
    }

    private companion object {
        const val KEY_USE_SHIZUKU = "use_shizuku"
    }
}
