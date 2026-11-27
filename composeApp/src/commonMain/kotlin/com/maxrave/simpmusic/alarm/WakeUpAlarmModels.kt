package com.maxrave.simpmusic.alarm

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val ACTION_WAKE_UP_ALARM = "com.maxrave.simpmusic.ACTION_WAKE_UP_ALARM"
const val EXTRA_ALARM_ID = "extra_alarm_id"

@Serializable
enum class AlarmTargetType {
    LIKED_SONGS,
    LOCAL_PLAYLIST,
    SPECIFIC_SONG,
}

@Serializable
data class WakeUpAlarm(
    val id: String,
    val label: String = "Morning Music",
    val hour: Int = 7,
    val minute: Int = 0,
    /** 1 = Monday .. 7 = Sunday; empty set means one-shot alarm. */
    val daysOfWeek: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val targetType: AlarmTargetType = AlarmTargetType.LIKED_SONGS,
    val targetId: String? = null,
    val targetTitle: String = "Liked Songs",
    val fadeInSeconds: Int = 45,
    val targetVolume: Float = 0.8f,
    val snoozeMinutes: Int = 9,
) {
    val isRepeating: Boolean
        get() = daysOfWeek.isNotEmpty()

    fun normalized(): WakeUpAlarm =
        copy(
            label = label.trim().ifBlank { "Wake-Up Alarm" },
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            daysOfWeek = daysOfWeek.filter { it in 1..7 }.toSet(),
            targetTitle =
                targetTitle.trim().ifBlank {
                    when (targetType) {
                        AlarmTargetType.LIKED_SONGS -> "Liked Songs"
                        AlarmTargetType.LOCAL_PLAYLIST -> "Local Playlist"
                        AlarmTargetType.SPECIFIC_SONG -> "Selected Song"
                    }
                },
            fadeInSeconds = fadeInSeconds.coerceIn(0, 300),
            targetVolume = targetVolume.coerceIn(WakeUpAlarmPlanner.MIN_FADE_START_VOLUME, 1f),
            snoozeMinutes = snoozeMinutes.coerceIn(1, 60),
        )

    fun formattedTime(): String {
        val h = hour.coerceIn(0, 23).toString().padStart(2, '0')
        val m = minute.coerceIn(0, 59).toString().padStart(2, '0')
        return "$h:$m"
    }

    fun daysSummary(): String {
        val clean = daysOfWeek.filter { it in 1..7 }.toSet()
        return when {
            clean.isEmpty() -> "One-shot"
            clean.size == 7 -> "Every day"
            clean == WEEKDAYS -> "Weekdays"
            clean == WEEKENDS -> "Weekends"
            else -> clean.sorted().joinToString(", ") { dayShortLabel(it) }
        }
    }

    companion object {
        val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)
        val WEEKENDS: Set<Int> = setOf(6, 7)
        val EVERY_DAY: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)

        fun dayShortLabel(isoDay: Int): String =
            when (isoDay) {
                1 -> "Mon"
                2 -> "Tue"
                3 -> "Wed"
                4 -> "Thu"
                5 -> "Fri"
                6 -> "Sat"
                7 -> "Sun"
                else -> ""
            }

        fun dayInitial(isoDay: Int): String =
            when (isoDay) {
                1 -> "M"
                2 -> "T"
                3 -> "W"
                4 -> "T"
                5 -> "F"
                6 -> "S"
                7 -> "S"
                else -> ""
            }
    }
}

@OptIn(ExperimentalTime::class)
object WakeUpAlarmPlanner {
    const val MIN_FADE_START_VOLUME = 0.05f

    /**
     * Computes the next strictly-future trigger epoch milliseconds for [alarm] relative to [nowEpochMs]
     * in [timeZone]. Returns null if the alarm is disabled.
     *
     * Handles same-day future triggers, past-time rollover to tomorrow, arbitrary ISO weekday masks
     * (1=Mon..7=Sun), midnight rollovers, and DST transitions via [TimeZone].
     */
    fun computeNextTriggerEpochMs(
        alarm: WakeUpAlarm,
        nowEpochMs: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Long? {
        if (!alarm.enabled) return null
        val normalized = alarm.normalized()
        val nowLocal = Instant.fromEpochMilliseconds(nowEpochMs).toLocalDateTime(timeZone)
        val baseDate = nowLocal.date
        val targetTime = LocalTime(normalized.hour, normalized.minute, 0, 0)

        for (dayOffset in 0..7) {
            val candidateDate = baseDate.plus(dayOffset, DateTimeUnit.DAY)
            val matchesDay =
                normalized.daysOfWeek.isEmpty() ||
                    candidateDate.dayOfWeek.isoDayNumber in normalized.daysOfWeek
            if (!matchesDay) continue

            val candidateEpochMs =
                LocalDateTime(candidateDate, targetTime)
                    .toInstant(timeZone)
                    .toEpochMilliseconds()
            if (candidateEpochMs > nowEpochMs) {
                return candidateEpochMs
            }
        }
        return null
    }

    /**
     * Computes the one-shot trigger timestamp for snoozing an active alarm by [snoozeMinutes].
     */
    fun computeSnoozeTriggerEpochMs(
        nowEpochMs: Long,
        snoozeMinutes: Int,
    ): Long = nowEpochMs + snoozeMinutes.coerceIn(1, 60) * 60_000L

    /**
     * Picks the earliest upcoming enabled alarm from [alarms], or null if none are enabled.
     */
    fun selectNextAlarm(
        alarms: List<WakeUpAlarm>,
        nowEpochMs: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Pair<WakeUpAlarm, Long>? =
        alarms
            .mapNotNull { alarm ->
                computeNextTriggerEpochMs(alarm, nowEpochMs, timeZone)?.let { alarm to it }
            }.minByOrNull { it.second }

    /**
     * Smooth exponential fade-in volume curve from [startVolume] (default 5%) up to [targetVolume].
     *
     * Using `start * (target / start)^t` matches human logarithmic loudness perception so the
     * wake-up transition begins softly and swells smoothly without abrupt jumps.
     */
    fun fadeVolumeAt(
        elapsedMs: Long,
        totalFadeMs: Long,
        targetVolume: Float,
        startVolume: Float = MIN_FADE_START_VOLUME,
    ): Float {
        val safeTarget =
            if (targetVolume.isFinite()) {
                targetVolume.coerceIn(MIN_FADE_START_VOLUME, 1.0f)
            } else {
                0.8f
            }
        val safeStart =
            if (startVolume.isFinite()) {
                startVolume.coerceIn(0.01f, safeTarget)
            } else {
                MIN_FADE_START_VOLUME.coerceAtMost(safeTarget)
            }
        if (totalFadeMs <= 0L || elapsedMs >= totalFadeMs) return safeTarget
        if (elapsedMs <= 0L) return safeStart

        val progress = (elapsedMs.toDouble() / totalFadeMs.toDouble()).coerceIn(0.0, 1.0)
        val ratio = safeTarget.toDouble() / safeStart.toDouble()
        val curved = safeStart.toDouble() * ratio.pow(progress)
        return curved.toFloat().coerceIn(safeStart, safeTarget)
    }
}

object WakeUpAlarmStoreCodec {
    const val DATASTORE_KEY = "wake_up_alarms_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encode(alarms: List<WakeUpAlarm>): String = json.encodeToString(alarms)

    fun decode(raw: String?): List<WakeUpAlarm> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<WakeUpAlarm>>(raw).map { it.normalized() }
        }.getOrDefault(emptyList())
    }
}
