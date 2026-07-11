package com.legal.automation.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live progress of the currently running automation, observed by the UI. */
object AutomationRunState {

    data class State(
        val running: Boolean = false,
        val automationName: String = "",
        val stepIndex: Int = -1,
        val totalSteps: Int = 0,
        val lastMessage: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = State()
    }
}
