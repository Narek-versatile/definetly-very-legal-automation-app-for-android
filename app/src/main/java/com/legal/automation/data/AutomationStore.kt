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
    private fun sampleAutomations(): List<Automation> = listOf(
        topMinecraftServersVote(),
        bestMinecraftServersVote(),
        minerankVote(),
        minecraftServerListVote(),
        minecraftMpVote(),
        minecraftKrantVote(),
        minecraftBuzzVote(),
    )

    /** minecraft-server-list.com: input id="ignnn", button id="voteButton", invisible reCAPTCHA. */
    private fun minecraftServerListVote() = Automation(
        id = "seed-vote-4",
        name = "Vote 4: Minecraft-Server-List",
        description = "Fills your {username}, taps Click to Vote, then pauses for any captcha.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://minecraft-server-list.com/server/288369/vote/",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "Vote for JartexNetwork", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "ignnn", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapId(viewId = "voteButton"),
            Step.ManualStep(
                message = "If a reCAPTCHA challenge appears, solve it then wait. Usually invisible.",
                timeoutMs = 15000,
            ),
            Step.Sleep(ms = 3000),
        ),
    )

    /** minecraft-mp.com: Cloudflare interstitial + Turnstile, input id="nickname",
     *  required id="accept" checkbox, submit button "Vote". The trickiest one. */
    private fun minecraftMpVote() = Automation(
        id = "seed-vote-5",
        name = "Vote 5: Minecraft-MP",
        description = "Rides out the Cloudflare check, fills your {username}, ticks the agree " +
            "box, waits for the Cloudflare verification, then votes. This site is the fussiest — " +
            "if a step fails, just re-run it.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://minecraft-mp.com/server/52462/vote/",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.ManualStep(
                message = "If a Cloudflare “verify you are human” page shows, wait for it to pass.",
                timeoutMs = 5000,
            ),
            // High retry to survive the Cloudflare interstitial before the form loads.
            Step.InputText(text = "{username}", intoId = "nickname", retry = RetryPolicy(attempts = 8, backoffMs = 3000)),
            Step.HideKeyboard(),
            Step.TapId(viewId = "accept"), // tick "I agree"
            Step.SwipeSmall(down = true, pixels = 150),
            Step.ManualStep(
                message = "Complete the Cloudflare “I'm not a robot” check if shown. " +
                    "Voting waits until it's verified.",
                timeoutMs = 3000,
            ),
            Step.WaitFor(text = "Success", timeoutMs = 40000), // Turnstile gate
            // Submit button reads "Vote"; clickable-preference avoids the
            // "Vote for…" heading. Turn on "Use real taps" if it doesn't fire.
            Step.TapText(text = "Vote"),
            Step.Sleep(ms = 3000),
        ),
    )

    /** minecraftkrant.nl (Dutch): input id="minecraft_name", submit "Stem op deze server", no captcha. */
    private fun minecraftKrantVote() = Automation(
        id = "seed-vote-6",
        name = "Vote 6: MinecraftKrant",
        description = "Dutch site, no captcha. Fills your {username} and taps “Stem op deze server”.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://minecraftkrant.nl/server/jartexnetwork/vote",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "MINECRAFT NAAM", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "minecraft_name", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapText(text = "Stem op deze server"),
            // This site runs a countdown before the vote counts — wait it out
            // before the run reports success.
            Step.Sleep(ms = 10000),
        ),
    )

    /** minecraft.buzz: the real vote form is at /vote/24 — input id="username-input",
     *  submit id="submitter", no captcha. */
    private fun minecraftBuzzVote() = Automation(
        id = "seed-vote-7",
        name = "Vote 7: Minecraft.buzz",
        description = "Fills your {username} and taps Submit. No captcha.",
        steps = listOf(
            Step.OpenUrl(
                url = "https://minecraft.buzz/vote/24",
                target = UrlTarget.GOOGLE_APP,
            ),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "username-input", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.TapId(viewId = "submitter"),
            Step.Sleep(ms = 3000),
        ),
    )

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
            Step.InputText(
                text = "{username}",
                intoText = "Minecraft Username",
                retry = RetryPolicy(attempts = 6, backoffMs = 2000),
            ),
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
            Step.InputText(
                text = "{username}",
                intoId = "mc_username",
                retry = RetryPolicy(attempts = 6, backoffMs = 2000),
            ),
            Step.HideKeyboard(),
            Step.SwipeSmall(down = true, pixels = 150),
            Step.ManualStep(
                message = "Complete the Cloudflare “I'm not a robot” check if it's shown. " +
                    "Voting waits until it's verified.",
                timeoutMs = 3000,
            ),
            // Gate: don't submit until Cloudflare Turnstile reports success.
            Step.WaitFor(text = "Success", timeoutMs = 40000),
            Step.TapId(viewId = "vote-now"), // the submit button
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

}
