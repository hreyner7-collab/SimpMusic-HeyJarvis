package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.MutableStateFlow

enum class JarvisPhase { Off, Listening, Awake, Thinking, Result, Error }

data class JarvisUiState(val phase: JarvisPhase = JarvisPhase.Off, val message: String = "")

/** Shared live state driving the Siri-style indicator on every platform. */
object JarvisStatus {
    val state = MutableStateFlow(JarvisUiState())
    fun set(phase: JarvisPhase, message: String = "") {
        state.value = JarvisUiState(phase, message)
    }
}
