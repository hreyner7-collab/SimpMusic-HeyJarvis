package com.maxrave.simpmusic.jerry

sealed interface JerryCommand {
    data object Next : JerryCommand
    data object Previous : JerryCommand
    data object Resume : JerryCommand
    data object Pause : JerryCommand
    data object Stop : JerryCommand
    data object VolumeUp : JerryCommand
    data object VolumeDown : JerryCommand
    data class SetVolume(val percent: Int) : JerryCommand
    data class PlayQuery(val query: String) : JerryCommand
}
