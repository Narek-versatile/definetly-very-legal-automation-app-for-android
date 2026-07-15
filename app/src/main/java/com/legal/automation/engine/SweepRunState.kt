package com.legal.automation.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live progress of the currently running vote sweep, observed by the UI. */
object SweepRunState {

    data class State(
        val running: Boolean = false,
        val nicknameIndex: Int = 0,
        val totalNicknames: Int = 0,
        val currentNickname: String = "",
        val voteIndex: Int = 0,
        val totalVotes: Int = 0,
        val phase: String = "",
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
