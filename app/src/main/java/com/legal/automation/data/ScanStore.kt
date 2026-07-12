package com.legal.automation.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** One "Scan screen" capture: everything readable on screen at that moment. */
@Serializable
data class Scan(
    val id: String,
    val createdAt: Long,
    val appLabel: String,
    val items: List<String>,
)

/**
 * Persists screen scans so they can be viewed in full inside the app (the
 * notification only shows a snippet) and re-read later. Also mirrors each scan
 * to a plain-text file under the app's external files dir for power users.
 */
class ScanStore(private val context: Context) {

    private val dir = File(context.filesDir, "scans").apply { mkdirs() }
    private val externalDir =
        context.getExternalFilesDir(null)?.let { File(it, "scans").apply { mkdirs() } }

    private val _scans = MutableStateFlow<List<Scan>>(emptyList())
    val scans: StateFlow<List<Scan>> = _scans.asStateFlow()

    private val maxScans = 40

    suspend fun load() = withContext(Dispatchers.IO) { refresh() }

    suspend fun add(appLabel: String, items: List<String>): Scan = withContext(Dispatchers.IO) {
        val scan = Scan(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            appLabel = appLabel,
            items = items,
        )
        runCatching { File(dir, "${scan.createdAt}_${scan.id}.json").writeText(AppJson.encodeToString(scan)) }
        externalDir?.let { ext ->
            runCatching { File(ext, "scan_${scan.createdAt}.txt").writeText(scan.asText()) }
        }
        trimAndRefresh()
        scan
    }

    private fun refresh() {
        val loaded = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { AppJson.decodeFromString<Scan>(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()
        _scans.value = loaded
    }

    private fun trimAndRefresh() {
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.name }
            ?.drop(maxScans)
            ?.forEach { it.delete() }
        refresh()
    }
}

private fun Scan.asText(): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(createdAt))
    return buildString {
        append("Screen scan — ").append(appLabel).append(" — ").append(time).append('\n')
        append(items.size).append(" items\n\n")
        items.forEach { append(it).append('\n') }
    }
}
