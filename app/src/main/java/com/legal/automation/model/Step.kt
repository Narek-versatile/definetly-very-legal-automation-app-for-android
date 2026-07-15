package com.legal.automation.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How many times a single step is retried before the run is failed. */
@Serializable
data class RetryPolicy(
    val attempts: Int = 3,
    val backoffMs: Long = 600,
)

@Serializable
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** Where an [Step.OpenUrl] should open a web address. */
@Serializable
enum class UrlTarget {
    /** The Google Search app (com.google.android.googlequicksearchbox) — not Chrome. */
    GOOGLE_APP,

    /** The system default browser (whatever the user set). */
    DEFAULT_BROWSER,

    /** Show the "Open with" chooser so any non-Chrome app can be picked. */
    CHOOSER,

    /** A specific app chosen by [Step.OpenUrl.packageName]. */
    SPECIFIC_APP,
}

/**
 * A single unit of work in an automation. Steps are the JSON-serialisable
 * "source of truth"; the in-app builder and any future record-and-replay
 * feature both just produce lists of these.
 *
 * Targeting is by visible text or by resource-id (accessibility), which is
 * resolution-independent. [TapXy] exists only as a coordinate fallback.
 */
@Serializable
sealed class Step {
    abstract val retry: RetryPolicy

    /** Human-readable one-liner shown in the builder and logs. */
    abstract fun describe(): String

    /** Launch an app by package name (uses Shizuku `am` when available). */
    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(
        val packageName: String,
        val appLabel: String = packageName,
        override val retry: RetryPolicy = RetryPolicy(),
    ) : Step() {
        override fun describe() = "Launch “$appLabel”"
    }

    /**
     * Clears an app's data/cache (via Shizuku `pm clear`), e.g. to reset a
     * site's remembered form state. Heavy: for a browser it also wipes tabs
     * and can trigger the browser's first-run screen. No-op without Shizuku.
     */
    @Serializable
    @SerialName("clear_app_data")
    data class ClearAppData(
        val packageName: String,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Clear data of $packageName"
    }

    /** Tap the first element whose visible text matches. */
    @Serializable
    @SerialName("tap_text")
    data class TapText(
        val text: String,
        val exact: Boolean = false,
        override val retry: RetryPolicy = RetryPolicy(),
    ) : Step() {
        override fun describe() = "Tap text “$text”"
    }

    /** Tap the element with the given resource-id (e.g. com.app:id/button). */
    @Serializable
    @SerialName("tap_id")
    data class TapId(
        val viewId: String,
        override val retry: RetryPolicy = RetryPolicy(),
    ) : Step() {
        override fun describe() = "Tap id “$viewId”"
    }

    /**
     * Type [text] into a field. If [intoText] or [intoId] is set the field is
     * focused first. Prefers Shizuku `input text`; falls back to accessibility
     * set-text.
     */
    @Serializable
    @SerialName("input_text")
    data class InputText(
        val text: String,
        val intoText: String? = null,
        val intoId: String? = null,
        override val retry: RetryPolicy = RetryPolicy(),
    ) : Step() {
        override fun describe() = "Type “$text”"
    }

    /** Scroll/swipe the screen in a direction. */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: ScrollDirection = ScrollDirection.DOWN,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Scroll ${direction.name.lowercase()}"
    }

    /** Wait until [text] appears, up to [timeoutMs]. Fails if it never does. */
    @Serializable
    @SerialName("wait_for")
    data class WaitFor(
        val text: String,
        val timeoutMs: Long = 8000,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Wait for “$text”"
    }

    /**
     * Assert that [text] is (or is not) on screen. This is the "verify the
     * operation succeeded" check; failing it triggers the alert.
     */
    @Serializable
    @SerialName("verify")
    data class Verify(
        val text: String,
        val expectPresent: Boolean = true,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() =
            if (expectPresent) "Verify “$text” is shown"
            else "Verify “$text” is NOT shown"
    }

    /** Fixed pause. */
    @Serializable
    @SerialName("sleep")
    data class Sleep(
        val ms: Long = 1000,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Wait ${ms}ms"
    }

    /** Coordinate tap fallback (fragile; last resort). */
    @Serializable
    @SerialName("tap_xy")
    data class TapXy(
        val x: Int,
        val y: Int,
        override val retry: RetryPolicy = RetryPolicy(),
    ) : Step() {
        override fun describe() = "Tap at ($x, $y)"
    }

    /**
     * Open a web address in a chosen app — the "open in the Google app, not
     * Chrome" action. Defaults to the Google Search app.
     */
    @Serializable
    @SerialName("open_url")
    data class OpenUrl(
        val url: String,
        val target: UrlTarget = UrlTarget.GOOGLE_APP,
        val packageName: String? = null,
        override val retry: RetryPolicy = RetryPolicy(attempts = 2),
    ) : Step() {
        override fun describe() = when (target) {
            UrlTarget.GOOGLE_APP -> "Open $url in the Google app"
            UrlTarget.DEFAULT_BROWSER -> "Open $url in the default browser"
            UrlTarget.CHOOSER -> "Open $url (pick an app)"
            UrlTarget.SPECIFIC_APP -> "Open $url in ${packageName ?: "app"}"
        }
    }

    /** Run a Google/web search for a query (opens the Google app). */
    @Serializable
    @SerialName("web_search")
    data class WebSearch(
        val query: String,
        override val retry: RetryPolicy = RetryPolicy(attempts = 2),
    ) : Step() {
        override fun describe() = "Google search “$query”"
    }

    /**
     * Tick (or untick) a checkbox only if it isn't already in that state —
     * avoids toggling a box the site already remembered (e.g. a privacy-policy
     * agreement). Targets by [viewId] or visible [text].
     */
    @Serializable
    @SerialName("ensure_checked")
    data class EnsureChecked(
        val viewId: String? = null,
        val text: String? = null,
        val checked: Boolean = true,
        override val retry: RetryPolicy = RetryPolicy(attempts = 2),
    ) : Step() {
        override fun describe() =
            "Ensure ${if (checked) "checked" else "unchecked"}: ${viewId ?: text ?: "?"}"
    }

    /**
     * Tap a target and confirm it took effect. If [confirmText] is set, the tap
     * is repeated (up to [attempts], waiting [gapMs] each) until that text
     * appears; otherwise it's just tapped [attempts] times with [gapMs] gaps —
     * a robust way to drive flaky web submit buttons.
     */
    @Serializable
    @SerialName("tap_confirm")
    data class TapAndConfirm(
        val viewId: String? = null,
        val text: String? = null,
        val exact: Boolean = false,
        val confirmText: String? = null,
        val attempts: Int = 3,
        val gapMs: Long = 4000,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Tap & confirm ${viewId ?: text ?: "?"}"
    }

    /** Close the on-screen keyboard (if open) so it stops covering buttons. */
    @Serializable
    @SerialName("hide_keyboard")
    data class HideKeyboard(
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Hide keyboard"
    }

    /** A small swipe (mostly to reveal what's below the fold / for visibility). */
    @Serializable
    @SerialName("swipe")
    data class SwipeSmall(
        val down: Boolean = true,
        val pixels: Int = 200,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Swipe ${if (down) "down" else "up"} ${pixels}px"
    }

    /**
     * Pause and alert the user to do something by hand (e.g. solve a captcha),
     * then wait [timeoutMs] before continuing. Used in flows the app can't
     * fully automate.
     */
    @Serializable
    @SerialName("manual_step")
    data class ManualStep(
        val message: String = "Do the manual step, then wait…",
        val timeoutMs: Long = 20000,
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Manual: $message"
    }

    /**
     * Brings this app back to the foreground. Uses Shizuku (`am start`) when
     * available since background activity starts are otherwise restricted;
     * falls back to a normal launch intent. Not run automatically — insert it
     * explicitly wherever a flow needs to hand control back to this app (e.g.
     * after tapping into another app during a sweep).
     */
    @Serializable
    @SerialName("return_to_app")
    data class ReturnToApp(
        override val retry: RetryPolicy = RetryPolicy(attempts = 1),
    ) : Step() {
        override fun describe() = "Return to this app"
    }
}
