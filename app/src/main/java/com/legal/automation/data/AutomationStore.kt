package com.legal.automation.data

import android.content.Context
import com.legal.automation.model.Automation
import com.legal.automation.model.RetryPolicy
import com.legal.automation.model.Step
import com.legal.automation.model.UrlTarget
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
            sampleAutomations().forEach { save(it) }
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

    /**
     * Seeds one ready automation per JartexNetwork vote site. Each opens the
     * page **in the Google app** (not Chrome), types your `{username}` (set it
     * once in Settings), pauses so you can solve any captcha, then taps Vote
     * and verifies. The field label ("Username") and button text ("Vote") are
     * best-effort defaults — tweak per site in the editor after seeing the page.
     */
    private fun sampleAutomations(): List<Automation> {
        // Site 1 is tuned to the real page (label "Minecraft Username", button
        // "Vote!", invisible reCAPTCHA). The rest use a generic template until
        // each is dialed in the same way.
        val rest = listOf(
            "Best-Minecraft-Servers" to "https://best-minecraft-servers.co/server-jartexnetwork.4402/vote",
            "MineRank" to "https://www.minerank.com/jartexnetwork/vote",
            "Minecraft-Server-List" to "https://minecraft-server-list.com/server/288369/vote/",
            "Minecraft-MP" to "https://minecraft-mp.com/server/52462/vote/",
            "MinecraftKrant" to "https://minecraftkrant.nl/server/jartexnetwork/vote",
            "Minecraft.buzz" to "https://minecraft.buzz/server/24&tab=vote",
        )
        return listOf(topMinecraftServersVote()) +
            rest.mapIndexed { index, (label, url) -> voteAutomation(index + 2, label, url) }
    }

    /** Perfected flow for topminecraftservers.org/vote/18687. */
    private fun topMinecraftServersVote() = Automation(
        name = "Vote 1: TopMinecraftServers",
        description = "Opens the vote page, types your {username} in the Minecraft Username " +
            "box, taps Vote!, then pauses in case the (usually invisible) reCAPTCHA shows a " +
            "challenge. Set your username in Settings.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://topminecraftservers.org/vote/18687",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 15000),
            Step.TapText(text = "Minecraft Username"), // focuses the username field
            Step.InputText(text = "{username}", retry = RetryPolicy(attempts = 2)),
            Step.TapText(text = "Vote!"),
            Step.ManualStep(
                message = "If a reCAPTCHA image challenge appears, solve it then wait. " +
                    "It's usually invisible, so often nothing to do.",
                timeoutMs = 15000,
            ),
            Step.Sleep(ms = 4000),
            Step.Verify(text = "hank", expectPresent = true), // "Thank you for voting"
        ),
    )

    private fun voteAutomation(number: Int, siteLabel: String, url: String) = Automation(
        name = "Vote $number: $siteLabel",
        description = "Opens $url in the Google app, fills your {username}, waits for you " +
            "to solve any captcha, then taps Vote. Set your username in Settings. " +
            "If the field/button aren't found, edit this step's target text to match the page.",
        steps = listOf(
            Step.OpenUrl(url = url, target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "ote", timeoutMs = 12000), // matches "Vote"/"vote"
            Step.InputText(
                text = "{username}",
                intoText = "Username",
                retry = RetryPolicy(attempts = 2),
            ),
            Step.ManualStep(
                message = "Solve the captcha / 'I'm not a robot' if shown, then wait…",
                timeoutMs = 25000,
            ),
            Step.TapText(text = "Vote"),
            Step.Sleep(ms = 3000),
            Step.Verify(text = "hank", expectPresent = true), // "Thank you for voting"
        ),
    )
}
