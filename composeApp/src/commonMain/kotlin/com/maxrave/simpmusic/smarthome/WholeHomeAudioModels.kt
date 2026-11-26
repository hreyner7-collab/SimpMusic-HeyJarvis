package com.maxrave.simpmusic.smarthome

import com.maxrave.simpmusic.handoff.HandoffPlaybackSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
enum class WholeHomeCommandType {
    SYNC_PLAY_AT,
    PAUSE,
    SEEK,
    SET_VOLUME,
    LEAVE_GROUP,
}

@Serializable
data class WholeHomeGroupCommand(
    val type: WholeHomeCommandType,
    val groupId: String,
    val groupName: String = "Whole Home",
    val leaderDeviceId: String,
    val leaderDeviceName: String,
    val snapshot: HandoffPlaybackSnapshot? = null,
    val targetStartEpochMs: Long = 0L,
    val positionMs: Long = 0L,
    val volume: Float? = null,
)

enum class WholeHomeRole {
    IDLE,
    LEADER,
    FOLLOWER,
}

data class WholeHomeAudioState(
    val role: WholeHomeRole = WholeHomeRole.IDLE,
    val groupId: String? = null,
    val groupName: String = "Whole Home",
    val leaderDeviceName: String? = null,
    val groupedPeerIds: Set<String> = emptySet(),
    val masterVolume: Float = 1f,
    val statusMessage: String? = null,
) {
    val isActive: Boolean get() = role != WholeHomeRole.IDLE
}

data class ScheduledStartPlan(
    val waitDelayMs: Long,
    val targetPositionMs: Long,
)

object WholeHomeSyncMath {
    const val HTTP_PATH = "/group/command"
    const val DEFAULT_LEAD_TIME_MS = 650L
    const val MAX_WAIT_DELAY_MS = 3_000L
    const val DRIFT_TOLERANCE_MS = 450L

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encodeCommand(command: WholeHomeGroupCommand): String = json.encodeToString(command)

    fun decodeCommand(raw: String): WholeHomeGroupCommand? =
        runCatching { json.decodeFromString<WholeHomeGroupCommand>(raw) }.getOrNull()

    /**
     * Computes how long a device should wait before starting playback (when ahead of target epoch)
     * or how far forward to seek to catch up (when the command arrived after target epoch).
     */
    fun computeStartPlan(
        snapshotPositionMs: Long,
        targetStartEpochMs: Long,
        nowEpochMs: Long,
        isPlaying: Boolean,
    ): ScheduledStartPlan {
        val safePos = snapshotPositionMs.coerceAtLeast(0L)
        if (!isPlaying || targetStartEpochMs <= 0L) {
            return ScheduledStartPlan(waitDelayMs = 0L, targetPositionMs = safePos)
        }
        val delta = targetStartEpochMs - nowEpochMs
        return if (delta > 0L) {
            ScheduledStartPlan(
                waitDelayMs = delta.coerceAtMost(MAX_WAIT_DELAY_MS),
                targetPositionMs = safePos,
            )
        } else {
            val lateMs = (-delta).coerceAtMost(30_000L)
            ScheduledStartPlan(
                waitDelayMs = 0L,
                targetPositionMs = safePos + lateMs,
            )
        }
    }

    fun shouldCorrectDrift(
        localPositionMs: Long,
        expectedPositionMs: Long,
        toleranceMs: Long = DRIFT_TOLERANCE_MS,
    ): Boolean = abs(localPositionMs - expectedPositionMs) > toleranceMs
}
