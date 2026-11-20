package com.maxrave.simpmusic.jerry

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

class AndroidVolume(context: Context) : JerryVolume {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var preMuteVolume: Int? = null

    override fun adjust(up: Boolean) {
        if (up && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
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
        if (target > 0) {
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            preMuteVolume = target
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    override suspend fun mute() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current > 0) preMuteVolume = current
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    override suspend fun unmute() {
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                val restore = preMuteVolume?.takeIf { it in 1..max } ?: (max / 2).coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
            }
        }
    }
}
