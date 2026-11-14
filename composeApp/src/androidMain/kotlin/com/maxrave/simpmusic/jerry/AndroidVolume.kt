package com.maxrave.simpmusic.jerry

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

class AndroidVolume(context: Context) : JerryVolume {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun adjust(up: Boolean) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
    }

    override fun setLevel(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val clamped = percent.coerceIn(0, 100)
        val target =
            if (clamped == 0) {
                0
            } else {
                (clamped * max / 100f).roundToInt().coerceIn(1, max)
            }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
}
