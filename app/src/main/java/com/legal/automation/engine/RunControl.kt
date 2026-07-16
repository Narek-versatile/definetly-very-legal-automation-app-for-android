package com.legal.automation.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Global pause switch for whatever automation/sweep is currently running,
 * toggled from the floating overlay bubble ([FloatingControlService]) or the
 * in-app running banners. [AutomationEngine] checks it between every step, so
 * pausing takes effect at the next step boundary and resuming picks the run
 * back up exactly where it left off — nothing is cancelled or restarted.
 */
object RunControl {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun togglePause() {
        _paused.value = !_paused.value
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    /** Call when a fresh run starts so a stale pause doesn't carry over. */
    fun reset() {
        _paused.value = false
    }

    /** Suspends until unpaused; returns immediately if not paused. */
    suspend fun awaitResume() {
        paused.first { !it }
    }
}
