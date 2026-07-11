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
}
