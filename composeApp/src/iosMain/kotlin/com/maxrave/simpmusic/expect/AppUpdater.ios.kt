package com.maxrave.simpmusic.expect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object AppUpdater {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    actual val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    actual fun startUpdate(tagName: String?) {
        _state.value = AppUpdateState.Error("In-app updates are supported on Windows and Android.")
    }

    actual fun resetState() {
        _state.value = AppUpdateState.Idle
    }
}
