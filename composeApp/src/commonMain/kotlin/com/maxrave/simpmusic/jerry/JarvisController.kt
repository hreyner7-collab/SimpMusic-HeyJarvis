package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.StateFlow

/** Platform bridge for the settings toggle. Android uses the permission sheet instead. */
expect object JarvisController {
    val running: StateFlow<Boolean>
    fun start()
    fun stop()
}
