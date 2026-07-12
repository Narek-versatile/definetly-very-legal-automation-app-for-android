package com.legal.automation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.legal.automation.App
import com.legal.automation.data.AppJson
import com.legal.automation.model.Automation
import com.legal.automation.shizuku.ShizukuManager
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class MainViewModel(private val app: App) : ViewModel() {

    val automations = app.store.automations
    val logs = app.logs.logs
    val scans = app.scans.scans
    val shizukuStatus = ShizukuManager.status
    val useShizuku = app.settings.useShizuku
    val username = app.settings.username

    init {
        viewModelScope.launch { app.store.load() }
        viewModelScope.launch { app.logs.load() }
        viewModelScope.launch { app.scans.load() }
    }

    fun refreshShizuku() = ShizukuManager.refreshStatus()

    fun setUseShizuku(value: Boolean) = app.settings.setUseShizuku(value)

    fun setUsername(value: String) = app.settings.setUsername(value)

    fun requestShizuku() {
        viewModelScope.launch {
            ShizukuManager.requestPermission()
            ShizukuManager.refreshStatus()
        }
    }

    fun run(automation: Automation) {
        com.legal.automation.engine.RunnerService.start(app, automation.id)
    }

    fun save(automation: Automation) {
        viewModelScope.launch { app.store.save(automation) }
    }

    fun delete(id: String) {
        viewModelScope.launch { app.store.delete(id) }
    }

    fun reloadBuiltIns() {
        viewModelScope.launch { app.store.reseedBuiltIns() }
    }

    /** Parses edited JSON back into an [Automation]; null if malformed. */
    fun parse(json: String): Automation? =
        runCatching { AppJson.decodeFromString<Automation>(json) }.getOrNull()

    fun toJson(automation: Automation): String = AppJson.encodeToString(automation)
}
