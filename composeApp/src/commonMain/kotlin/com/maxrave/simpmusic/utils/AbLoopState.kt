package com.maxrave.simpmusic.utils

import kotlin.math.max

data class AbLoopState(
    val pointAMs: Long? = null,
    val pointBMs: Long? = null,
) {
    val isActive: Boolean
        get() = pointAMs != null && pointBMs != null && pointBMs > pointAMs

    fun toggleAt(
        positionMs: Long,
        durationMs: Long,
    ): AbLoopState {
        val safePos =
            if (durationMs > 0L) {
                positionMs.coerceIn(0L, durationMs)
            } else {
                max(0L, positionMs)
            }
        return when {
            pointAMs == null -> AbLoopState(pointAMs = safePos, pointBMs = null)
            pointBMs == null ->
                if (safePos >= pointAMs + MIN_LOOP_GAP_MS) {
                    AbLoopState(pointAMs = pointAMs, pointBMs = safePos)
                } else {
                    // Tapping B too close to or before A resets the loop cleanly.
                    AbLoopState()
                }
            else -> AbLoopState()
        }
    }

    fun clear(): AbLoopState = AbLoopState()

    fun shouldLoopBack(positionMs: Long): Boolean {
        val b = pointBMs ?: return false
        val a = pointAMs ?: return false
        if (b <= a) return false
        return positionMs >= (b - LOOP_TRIGGER_LEAD_MS)
    }

    /**
     * `UIEvent.UpdateProgress` expects a `0f..100f` percentage of track duration.
     */
    fun seekPercentForPointA(durationMs: Long): Float? {
        val a = pointAMs ?: return null
        if (!isActive || durationMs <= 0L) return null
        return ((a.toDouble() * 100.0) / durationMs.toDouble()).toFloat().coerceIn(0f, 100f)
    }

    fun startFraction(durationMs: Long): Float? {
        val a = pointAMs ?: return null
        if (durationMs <= 0L) return null
        return (a.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun endFraction(durationMs: Long): Float? {
        val b = pointBMs ?: return null
        if (durationMs <= 0L) return null
        return (b.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_LOOP_GAP_MS = 500L
        const val LOOP_TRIGGER_LEAD_MS = 120L

        fun formatLoopTime(ms: Long): String {
            val totalSeconds = max(0L, ms / 1000L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            val secStr = seconds.toString().padStart(2, '0')
            return if (hours > 0L) {
                val minStr = minutes.toString().padStart(2, '0')
                "$hours:$minStr:$secStr"
            } else {
                "$minutes:$secStr"
            }
        }
    }
}
