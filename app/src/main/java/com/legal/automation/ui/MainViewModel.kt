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
    val realTaps = app.settings.realTaps
    val nicknames = app.settings.nicknames
    val betweenVotesMs = app.settings.betweenVotesMs
    val postCycleAppPackage = app.settings.postCycleAppPackage
    val postCycleAppLabel = app.settings.postCycleAppLabel
    val postCycleTapX = app.settings.postCycleTapX
    val postCycleTapY = app.settings.postCycleTapY
    val postCycleWaitMs = app.settings.postCycleWaitMs
    val sweepSchedule = app.settings.sweepSchedule
    val keepAwakeWhileCharging = app.settings.keepAwakeWhileCharging
    val sweepState = com.legal.automation.engine.SweepRunState.state
    val runPaused = com.legal.automation.engine.RunControl.paused

    fun toggleRunPause() = com.legal.automation.engine.RunControl.togglePause()

    init {
        viewModelScope.launch { app.store.load() }
        viewModelScope.launch { app.logs.load() }
        viewModelScope.launch { app.scans.load() }
        // Re-assert stay-awake on launch (it resets on reboot) if the user wants it.
        if (app.settings.keepAwakeWhileCharging.value) {
            viewModelScope.launch { ShizukuManager.setStayAwakeWhileCharging(true) }
        }
    }

    fun refreshShizuku() = ShizukuManager.refreshStatus()

    fun setUseShizuku(value: Boolean) = app.settings.setUseShizuku(value)

    fun setUsername(value: String) = app.settings.setUsername(value)

    fun setRealTaps(value: Boolean) = app.settings.setRealTaps(value)

    fun requestShizuku() {
        viewModelScope.launch {
            ShizukuManager.requestPermission()
            ShizukuManager.refreshStatus()
        }
    }

    fun run(automation: Automation) {
        com.legal.automation.engine.RunnerService.start(app, automation.id)
    }

    fun setNicknames(value: List<String>) = app.settings.setNicknames(value)

    fun setBetweenVotesMs(value: Long) = app.settings.setBetweenVotesMs(value)

    fun setPostCycleApp(packageName: String?, label: String) = app.settings.setPostCycleApp(packageName, label)

    fun setPostCycleTap(x: Int?, y: Int?) = app.settings.setPostCycleTap(x, y)

    fun setPostCycleWaitMs(value: Long) = app.settings.setPostCycleWaitMs(value)

    fun startSweep() {
        com.legal.automation.engine.SweepRunnerService.start(app)
    }

    fun setSweepSchedule(value: com.legal.automation.data.SweepSchedule) {
        app.settings.setSweepSchedule(value)
        com.legal.automation.engine.SweepScheduler.apply(app, value)
    }

    fun setKeepAwakeWhileCharging(value: Boolean) {
        app.settings.setKeepAwakeWhileCharging(value)
        viewModelScope.launch { ShizukuManager.setStayAwakeWhileCharging(value) }
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
