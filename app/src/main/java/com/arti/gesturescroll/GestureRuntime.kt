package com.arti.gesturescroll

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GestureUiState(
    val running: Boolean = false,
    val lastGesture: String = "—",
    val error: String? = null,
)

object GestureRuntime {
    private val mutableState = MutableStateFlow(GestureUiState())
    val state: StateFlow<GestureUiState> = mutableState.asStateFlow()

    fun setRunning(running: Boolean) {
        mutableState.value = mutableState.value.copy(running = running, error = null)
    }

    fun setLastGesture(value: String) {
        mutableState.value = mutableState.value.copy(lastGesture = value, error = null)
    }

    fun setError(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }
}
