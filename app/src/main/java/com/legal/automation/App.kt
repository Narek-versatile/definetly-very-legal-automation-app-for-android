package com.legal.automation

import android.app.Application
import com.legal.automation.data.AutomationStore
import com.legal.automation.data.ScanStore
import com.legal.automation.data.SettingsStore
import com.legal.automation.engine.AlertManager
import com.legal.automation.engine.AutomationEngine
import com.legal.automation.engine.LogRepository
import com.legal.automation.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide container. Deliberately tiny — a hand-rolled service locator is
 * enough for a single-module app and avoids pulling in a DI framework.
 */
class App : Application() {

    lateinit var store: AutomationStore
        private set
    lateinit var logs: LogRepository
        private set
    lateinit var alerts: AlertManager
        private set
    lateinit var engine: AutomationEngine
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var scans: ScanStore
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        alerts = AlertManager(this).also { it.ensureChannels() }
        store = AutomationStore(this)
        logs = LogRepository(this)
        settings = SettingsStore(this)
        scans = ScanStore(this)
        engine = AutomationEngine(this, ShizukuManager, alerts, logs, settings)
        ShizukuManager.init()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
