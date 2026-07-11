package com.legal.automation.data

import android.content.Context
import com.legal.automation.model.Automation
import com.legal.automation.model.RetryPolicy
import com.legal.automation.model.ScrollDirection
import com.legal.automation.model.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persists automations as individual pretty-printed JSON files in
 * `filesDir/automations`. Exposes the current set as a [StateFlow] the UI
 * observes. Seeds one example automation on first launch so there is
 * something to run and to read as a template.
 */
class AutomationStore(context: Context) {

    private val dir = File(context.filesDir, "automations").apply { mkdirs() }

    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()
        if (files.isEmpty()) {
            save(sampleAutomation())
        }
        refresh()
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        val loaded = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { AppJson.decodeFromString<Automation>(f.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        _automations.value = loaded
    }

    suspend fun save(automation: Automation) = withContext(Dispatchers.IO) {
        File(dir, "${automation.id}.json").writeText(AppJson.encodeToString(automation))
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        refresh()
    }

    fun get(id: String): Automation? = _automations.value.firstOrNull { it.id == id }

    private fun sampleAutomation() = Automation(
        name = "Example: search in Chrome",
        description = "Opens Chrome, types a query, and verifies results appear. " +
            "Edit or delete freely — this is just a template.",
        steps = listOf(
            Step.LaunchApp(packageName = "com.android.chrome", appLabel = "Chrome"),
            Step.Sleep(ms = 2500),
            Step.TapId(viewId = "com.android.chrome:id/search_box_text"),
            Step.InputText(text = "hello world", retry = RetryPolicy(attempts = 2)),
            Step.Sleep(ms = 500),
            Step.WaitFor(text = "hello", timeoutMs = 6000),
            Step.Scroll(direction = ScrollDirection.DOWN),
            Step.Verify(text = "hello", expectPresent = true),
        ),
    )
}
