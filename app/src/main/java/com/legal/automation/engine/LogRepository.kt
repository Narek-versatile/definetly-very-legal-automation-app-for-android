package com.legal.automation.engine

import android.content.Context
import com.legal.automation.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Stores run logs (newest first) and exposes them as a [StateFlow] for the UI.
 * Screenshots live alongside as PNGs referenced by [RunLog.failureScreenshotPath].
 */
class LogRepository(context: Context) {

    private val logsFile = File(context.filesDir, "logs/runs.json").apply {
        parentFile?.mkdirs()
    }
    val screenshotDir: File = File(context.filesDir, "logs/screenshots").apply { mkdirs() }

    private val _logs = MutableStateFlow<List<RunLog>>(emptyList())
    val logs: StateFlow<List<RunLog>> = _logs.asStateFlow()

    private val maxLogs = 100

    suspend fun load() = withContext(Dispatchers.IO) {
        if (logsFile.exists()) {
            _logs.value = runCatching {
                AppJson.decodeFromString<List<RunLog>>(logsFile.readText())
            }.getOrDefault(emptyList())
        }
    }

    suspend fun add(log: RunLog) = withContext(Dispatchers.IO) {
        val updated = (listOf(log) + _logs.value).take(maxLogs)
        _logs.value = updated
        runCatching { logsFile.writeText(AppJson.encodeToString(updated)) }
    }
}
