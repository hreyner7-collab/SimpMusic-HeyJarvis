package com.maxrave.simpmusic

import com.maxrave.simpmusic.expect.platformOsName

sealed class Platform {
    object Android : Platform()
    object iOS : Platform()
    object Desktop : Platform()

    fun osName(): String = when (this) {
        Android -> "android"
        iOS -> "iOS"
        Desktop -> platformOsName()
    }
}

expect fun getPlatform(): Platform