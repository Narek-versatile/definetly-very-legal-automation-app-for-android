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

    /** Re-writes the built-in vote automations to their latest tuned versions,
     *  replacing older copies (matched by name or stable id) so there are no
     *  duplicates. User-created automations are left untouched. */
    suspend fun reseedBuiltIns() = withContext(Dispatchers.IO) {
        val seeds = sampleAutomations()
        val seedNames = seeds.map { it.name }.toSet()
        val seedIds = seeds.map { it.id }.toSet()
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            val existing = runCatching { AppJson.decodeFromString<Automation>(f.readText()) }.getOrNull()
            if (existing != null && existing.name in seedNames && existing.id !in seedIds) {
                f.delete()
            }
        }
        seeds.forEach { File(dir, "${it.id}.json").writeText(AppJson.encodeToString(it)) }
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
        // Sites 1-3 are tuned to their real pages (confirmed via screen scans).
        // The rest use a generic template until each is dialed in the same way.
        val rest = listOf(
            "Minecraft-Server-List" to "https://minecraft-server-list.com/server/288369/vote/",
            "Minecraft-MP" to "https://minecraft-mp.com/server/52462/vote/",
            "MinecraftKrant" to "https://minecraftkrant.nl/server/jartexnetwork/vote",
            "Minecraft.buzz" to "https://minecraft.buzz/server/24&tab=vote",
        )
        return listOf(topMinecraftServersVote(), bestMinecraftServersVote(), minerankVote()) +
            rest.mapIndexed { index, (label, url) -> voteAutomation(index + 4, label, url) }
    }

    /** best-minecraft-servers.co: input name="username", button "Vote!". */
    private fun bestMinecraftServersVote() = Automation(
        id = "seed-vote-2",
        name = "Vote 2: Best-Minecraft-Servers",
        description = "Opens the vote page, fills your {username}, taps Vote!, then pauses " +
            "for any captcha. Set your username in Settings.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://best-minecraft-servers.co/server-jartexnetwork.4402/vote",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoText = "Minecraft Username", retry = RetryPolicy(attempts = 3)),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapText(text = "Vote!"),
            Step.ManualStep(
                message = "If a reCAPTCHA challenge appears, solve it then wait. Usually invisible.",
                timeoutMs = 15000,
            ),
            Step.Sleep(ms = 3000),
        ),
    )

    /** minerank.com: input id="mc_username", submit "Send Vote", Cloudflare Turnstile. */
    private fun minerankVote() = Automation(
        id = "seed-vote-3",
        name = "Vote 3: MineRank",
        description = "Opens the vote page, fills your {username} (case-sensitive!), taps " +
            "Send Vote, then pauses for the Cloudflare check. Set your username in Settings.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://www.minerank.com/jartexnetwork/vote",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "mc_username", retry = RetryPolicy(attempts = 3)),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapId(viewId = "vote-now"), // the submit button
            Step.ManualStep(
                message = "If the Cloudflare check needs you, complete it then wait. " +
                    "Usually it passes on its own.",
                timeoutMs = 15000,
            ),
            Step.Sleep(ms = 3000),
        ),
    )

    /** Perfected flow for topminecraftservers.org/vote/18687. */
    private fun topMinecraftServersVote() = Automation(
        id = "seed-vote-1",
        name = "Vote 1: TopMinecraftServers",
        description = "Opens the vote page, types your {username} in the Minecraft Username " +
            "box, taps Vote!, then pauses in case the (usually invisible) reCAPTCHA shows a " +
            "challenge. Set your username in Settings.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://topminecraftservers.org/vote/18687",
                target = UrlTarget.GOOGLE_APP,
            ),
            // Ad-heavy page + browser cold start: give the accessibility tree
            // time to populate (it does — confirmed via a scan).
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            // Retry through an intermittent Cloudflare "verify you are human"
            // screen: keep trying until the field comes back.
            Step.InputText(
                text = "{username}",
                intoId = "username",
                retry = RetryPolicy(attempts = 6, backoffMs = 2500),
            ),
            Step.HideKeyboard(), // otherwise the keyboard eats the Vote tap
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapId(viewId = "voteButton"),
            Step.ManualStep(
                message = "If a reCAPTCHA / Cloudflare challenge appears, solve it then wait. " +
                    "It's usually invisible, so often nothing to do.",
                timeoutMs = 15000,
            ),
            Step.Sleep(ms = 3000),
        ),
    )

    private fun voteAutomation(number: Int, siteLabel: String, url: String) = Automation(
        id = "seed-vote-$number",
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
