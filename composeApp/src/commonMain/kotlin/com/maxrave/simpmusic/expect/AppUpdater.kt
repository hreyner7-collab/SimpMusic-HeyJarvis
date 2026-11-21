package com.maxrave.simpmusic.expect

import kotlinx.coroutines.flow.StateFlow

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Downloading(val progressPercent: Int, val statusText: String) : AppUpdateState
    data class Installing(val statusText: String) : AppUpdateState
    data class UpToDate(val version: String) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

const val UPDATE_REPO_LATEST_API =
    "https://api.github.com/repos/hreyner7-collab/SimpMusic-HeyJarvis/releases/latest"

expect object AppUpdater {
    val state: StateFlow<AppUpdateState>
    fun startUpdate(tagName: String? = null)
    fun resetState()
}
