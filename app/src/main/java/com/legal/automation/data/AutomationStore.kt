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

    /** topminecraftservers.org: input id="username", button id="voteButton", invisible reCAPTCHA. */
    private fun topMinecraftServersVote() = Automation(
        id = "seed-vote-1",
        name = "Vote 1: TopMinecraftServers",
        description = "Fills your {username} and taps Vote. Set your username in Settings.",
        steps = listOf(
            Step.OpenUrl(url = "https://topminecraftservers.org/vote/18687", target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "username", retry = RetryPolicy(attempts = 6, backoffMs = 2500)),
            Step.HideKeyboard(),
            Step.TapId(viewId = "voteButton"),
        ),
    )

    /** best-minecraft-servers.co: input name="username", button "Vote!". */
    private fun bestMinecraftServersVote() = Automation(
        id = "seed-vote-2",
        name = "Vote 2: Best-Minecraft-Servers",
        description = "Fills your {username} and taps Vote!.",
        steps = listOf(
            Step.OpenUrl(url = "https://best-minecraft-servers.co/server-jartexnetwork.4402/vote", target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoText = "Minecraft Username", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            Step.TapText(text = "Vote!"),
        ),
    )

    /** minerank.com: input id="mc_username", submit text "Send Vote", Cloudflare Turnstile. */
    private fun minerankVote() = Automation(
        id = "seed-vote-3",
        name = "Vote 3: MineRank",
        description = "Waits for the page, fills your {username} (case-sensitive!), waits for the " +
            "Cloudflare check, then taps Send Vote.",
        steps = listOf(
            Step.OpenUrl(url = "https://www.minerank.com/jartexnetwork/vote", target = UrlTarget.GOOGLE_APP),
            Step.Sleep(ms = 3000), // let the site start loading before we work
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "mc_username", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            // Don't submit until Cloudflare Turnstile reports success.
            Step.WaitFor(text = "Success", timeoutMs = 40000),
            // Tap the visible "Send Vote" button; re-tap until it takes.
            Step.TapAndConfirm(text = "Send Vote", attempts = 3, gapMs = 4000),
        ),
    )

    /** minecraft-server-list.com: input id="ignnn", button id="voteButton" ("Click to Vote"). */
    private fun minecraftServerListVote() = Automation(
        id = "seed-vote-4",
        name = "Vote 4: Minecraft-Server-List",
        description = "Fills your {username}, waits for the button, then taps Click to Vote.",
        steps = listOf(
            Step.OpenUrl(url = "https://minecraft-server-list.com/server/288369/vote/", target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "Vote for JartexNetwork", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "ignnn", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            // Wait for the vote button to finish loading before tapping.
            Step.WaitFor(text = "Click to Vote", timeoutMs = 20000),
            Step.Sleep(ms = 1000), // extra settle time before the tap
            Step.TapAndConfirm(viewId = "voteButton", attempts = 3, gapMs = 4000),
        ),
    )

    /** minecraft-mp.com: Cloudflare + Turnstile, input id="nickname", id="accept" box, button "Vote".
     *  The captcha fails ("Captcha data missing") when the site's third-party
     *  cookies get into a bad state, so we clear this site's stored data via
     *  Chrome's page-info sheet and reload before voting. */
    private fun minecraftMpVote() = Automation(
        id = "seed-vote-5",
        name = "Vote 5: Minecraft-MP",
        description = "Clears the site's stored data (fixes the captcha), reloads, fills your " +
            "{username}, ticks the agree box only if needed, waits for the Cloudflare check, then " +
            "votes. Chrome-specific — if a settings step fails, scan that screen and tell me.",
        steps = listOf(
            Step.OpenUrl(url = "https://minecraft-mp.com/server/52462/vote/", target = UrlTarget.GOOGLE_APP),
            // Extra settle time before touching the page info sheet — a
            // Cloudflare interstitial sometimes pops up right after load and
            // steals the "Connection is secure" tap, which ruins the cookie
            // clear below.
            Step.Sleep(ms = 9500),

            // --- Clear this site's cookies/data via Chrome's page-info sheet ---
            Step.TapText(text = "Connection is secure"), // the padlock in the address bar
            Step.Sleep(ms = 1500),
            Step.TapText(text = "Cookies and site data"),
            Step.Sleep(ms = 1500),
            Step.TapText(text = "Delete"), // the trash / delete-data control
            Step.Sleep(ms = 1200),
            Step.TapText(text = "Delete"), // confirm the "Delete cookies?" dialog
            Step.Sleep(ms = 1500),
            // Reload the page now that data is cleared.
            Step.OpenUrl(url = "https://minecraft-mp.com/server/52462/vote/", target = UrlTarget.GOOGLE_APP),

            // --- Vote ---
            Step.InputText(text = "{username}", intoId = "nickname", retry = RetryPolicy(attempts = 8, backoffMs = 3000)),
            Step.HideKeyboard(),
            Step.EnsureChecked(viewId = "accept", checked = true),
            Step.WaitFor(text = "Success", timeoutMs = 40000), // Turnstile gate
            Step.Sleep(ms = 2000), // extra settle time before the single tap
            Step.TapAndConfirm(text = "Vote", attempts = 1, gapMs = 4000),
        ),
    )

    /** minecraftkrant.nl (Dutch): input id="minecraft_name", submit "Stem op deze server", no captcha. */
    private fun minecraftKrantVote() = Automation(
        id = "seed-vote-6",
        name = "Vote 6: MinecraftKrant",
        description = "Dutch site, no captcha. Fills your {username} and taps “Stem op deze server”.",
        steps = listOf(
            Step.OpenUrl(url = "https://minecraftkrant.nl/server/jartexnetwork/vote", target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "MINECRAFT NAAM", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "minecraft_name", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            Step.TapText(text = "Stem op deze server"),
        ),
    )

    /** minecraft.buzz: /vote/24 — input id="username-input", submit id="submitter".
     *  Tapping submit opens a Cloudflare Turnstile "Almost there!" modal with its
     *  own Submit button (resource id "captcha_submit" — it has no accessible
     *  "Submit" text of its own, so targeting by text kept hitting the page's
     *  original submit button underneath instead); that modal button is the one
     *  that actually finalises the vote, so we wait for the challenge to pass
     *  then tap it by id. */
    private fun minecraftBuzzVote() = Automation(
        id = "seed-vote-7",
        name = "Vote 7: Minecraft.buzz",
        description = "Fills your {username}, taps the vote button, waits for the Cloudflare " +
            "check, then taps the modal's Submit.",
        steps = listOf(
            Step.OpenUrl(url = "https://minecraft.buzz/vote/24", target = UrlTarget.GOOGLE_APP),
            Step.WaitFor(text = "Minecraft Username", timeoutMs = 30000),
            Step.InputText(text = "{username}", intoId = "username-input", retry = RetryPolicy(attempts = 6, backoffMs = 2000)),
            Step.HideKeyboard(),
            // Tap the vote/submit button — this opens the "Almost there!" modal.
            Step.TapId(viewId = "submitter"),
            // Wait for the Cloudflare Turnstile to finish and show "Success!".
            Step.Sleep(ms = 22000),
            // Tap the modal's own Submit button (id captcha_submit) to finalise the vote.
            Step.TapAndConfirm(viewId = "captcha_submit", attempts = 3, gapMs = 2000),
            Step.Sleep(ms = 3000), // considered done
        ),
    )
}
