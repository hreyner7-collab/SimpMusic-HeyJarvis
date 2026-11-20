package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object JarvisUiEvents {
    private val _navEvents = MutableSharedFlow<JerryNavTarget>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navEvents: SharedFlow<JerryNavTarget> = _navEvents.asSharedFlow()

    fun emitNav(target: JerryNavTarget): Boolean = _navEvents.tryEmit(target)
}
