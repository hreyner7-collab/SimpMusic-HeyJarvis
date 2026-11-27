package com.maxrave.simpmusic.alarm

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class WakeUpAlarmPlannerTest {
    private val utc = TimeZone.UTC

    private fun epochUtc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long = LocalDateTime(year, month, day, hour, minute, second).toInstant(utc).toEpochMilliseconds()

    @Test
    fun disabledAlarmReturnsNull() {
        val alarm = WakeUpAlarm(id = "a1", hour = 7, minute = 30, enabled = false)
        val now = epochUtc(2026, 11, 26, 6, 0)
        assertNull(WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, now, utc))
    }

    @Test
    fun oneShotAlarmLaterTodayFiresSameDay() {
        val alarm = WakeUpAlarm(id = "a1", hour = 7, minute = 30, daysOfWeek = emptySet(), enabled = true)
        // Thursday 2026-11-26 06:45 UTC
        val now = epochUtc(2026, 11, 26, 6, 45)
        val expected = epochUtc(2026, 11, 26, 7, 30)
        assertEquals(expected, WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, now, utc))
    }

    @Test
    fun oneShotAlarmEarlierTodayRollsOverToTomorrow() {
        val alarm = WakeUpAlarm(id = "a1", hour = 7, minute = 0, daysOfWeek = emptySet(), enabled = true)
        // Thursday 2026-11-26 07:00:00 exact (not strictly future) -> Friday 2026-11-27 07:00
        val now = epochUtc(2026, 11, 26, 7, 0, 0)
        val expected = epochUtc(2026, 11, 27, 7, 0)
        assertEquals(expected, WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, now, utc))
    }

    @Test
    fun midnightRolloverComputesNextCalendarDayAccurately() {
        val alarm = WakeUpAlarm(id = "mid", hour = 0, minute = 5, daysOfWeek = emptySet(), enabled = true)
        // 2026-11-30 23:55 UTC -> 2026-12-01 00:05 UTC (month boundary rollover!)
        val now = epochUtc(2026, 11, 30, 23, 55)
        val expected = epochUtc(2026, 12, 1, 0, 5)
        assertEquals(expected, WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, now, utc))
    }

    @Test
    fun weekdayMaskSkipsWeekendToMonday() {
        val alarm = WakeUpAlarm(id = "work", hour = 7, minute = 0, daysOfWeek = WakeUpAlarm.WEEKDAYS, enabled = true)
        // Friday 2026-11-27 08:00 UTC -> Monday 2026-11-30 07:00 UTC
        val fridayAfter = epochUtc(2026, 11, 27, 8, 0)
        val monday = epochUtc(2026, 11, 30, 7, 0)
        assertEquals(monday, WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, fridayAfter, utc))
    }

    @Test
    fun singleDayRepeatingAlarmAtExactTimeRollsFullWeek() {
        // 2026-11-26 is Thursday (ISO day 4)
        val alarm = WakeUpAlarm(id = "thu", hour = 9, minute = 15, daysOfWeek = setOf(4), enabled = true)
        val thursdayAtAlarm = epochUtc(2026, 11, 26, 9, 15, 0)
        val nextThursday = epochUtc(2026, 12, 3, 9, 15, 0)
        assertEquals(nextThursday, WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, thursdayAtAlarm, utc))
    }

    @Test
    fun respectsNonUtcTimeZoneWallClock() {
        val tokyo = TimeZone.of("UTC+9")
        val alarm = WakeUpAlarm(id = "tz", hour = 6, minute = 30, daysOfWeek = emptySet(), enabled = true)
        // 2026-11-25 21:00 UTC == 2026-11-26 06:00 JST -> should fire 30 minutes later at 06:30 JST (21:30 UTC)
        val nowUtc = epochUtc(2026, 11, 25, 21, 0)
        val triggerMs = WakeUpAlarmPlanner.computeNextTriggerEpochMs(alarm, nowUtc, tokyo)
        assertNotNull(triggerMs)
        assertEquals(nowUtc + 30 * 60_000L, triggerMs)
        val local = Instant.fromEpochMilliseconds(triggerMs).toLocalDateTime(tokyo)
        assertEquals(6, local.hour)
        assertEquals(30, local.minute)
    }

    @Test
    fun selectNextAlarmPicksEarliestEnabled() {
        val now = epochUtc(2026, 11, 26, 6, 0)
        val alarms =
            listOf(
                WakeUpAlarm(id = "disabled", hour = 6, minute = 5, enabled = false),
                WakeUpAlarm(id = "late", hour = 8, minute = 0, enabled = true),
                WakeUpAlarm(id = "soon", hour = 6, minute = 30, enabled = true),
            )
        val next = WakeUpAlarmPlanner.selectNextAlarm(alarms, now, utc)
        assertNotNull(next)
        assertEquals("soon", next.first.id)
        assertEquals(epochUtc(2026, 11, 26, 6, 30), next.second)
    }

    @Test
    fun exponentialFadeCurveBoundsAndMonotonicity() {
        val totalMs = 45_000L
        val target = 0.8f

        // Start & end bounds
        assertEquals(0.05f, WakeUpAlarmPlanner.fadeVolumeAt(0L, totalMs, target), 0.0001f)
        assertEquals(0.05f, WakeUpAlarmPlanner.fadeVolumeAt(-500L, totalMs, target), 0.0001f)
        assertEquals(target, WakeUpAlarmPlanner.fadeVolumeAt(totalMs, totalMs, target), 0.0001f)
        assertEquals(target, WakeUpAlarmPlanner.fadeVolumeAt(totalMs + 10_000L, totalMs, target), 0.0001f)

        // Zero fade jumps straight to target
        assertEquals(target, WakeUpAlarmPlanner.fadeVolumeAt(0L, 0L, target), 0.0001f)

        // Midpoint of exponential 0.05 -> 0.80 is 0.05 * sqrt(16) = 0.20 (gentler than linear 0.425!)
        val mid = WakeUpAlarmPlanner.fadeVolumeAt(totalMs / 2, totalMs, target)
        assertTrue(abs(mid - 0.20f) < 0.002f, "Expected ~0.20 at midpoint, got $mid")
        assertTrue(mid < 0.425f, "Exponential fade should stay gentler than linear midpoint")

        // Strictly monotonic across entire ramp
        var prev = WakeUpAlarmPlanner.fadeVolumeAt(0L, totalMs, target)
        for (step in 1..45) {
            val current = WakeUpAlarmPlanner.fadeVolumeAt(step * 1_000L, totalMs, target)
            assertTrue(current > prev, "Expected strictly increasing volume at ${step}s: prev=$prev, current=$current")
            assertTrue(current in 0.05f..target)
            prev = current
        }
    }

    @Test
    fun snoozeTriggerAddsClampedMinutes() {
        val now = 1_700_000_000_000L
        assertEquals(now + 9 * 60_000L, WakeUpAlarmPlanner.computeSnoozeTriggerEpochMs(now, 9))
        assertEquals(now + 1 * 60_000L, WakeUpAlarmPlanner.computeSnoozeTriggerEpochMs(now, 0))
        assertEquals(now + 60 * 60_000L, WakeUpAlarmPlanner.computeSnoozeTriggerEpochMs(now, 180))
    }

    @Test
    fun jsonRoundTripPreservesAlarmFields() {
        val original =
            listOf(
                WakeUpAlarm(
                    id = "w1",
                    label = "Chill Morning",
                    hour = 6,
                    minute = 45,
                    daysOfWeek = setOf(1, 3, 5),
                    enabled = true,
                    targetType = AlarmTargetType.LOCAL_PLAYLIST,
                    targetId = "42",
                    targetTitle = "Lo-Fi Sunrise",
                    fadeInSeconds = 60,
                    targetVolume = 0.75f,
                    snoozeMinutes = 10,
                ),
            )
        val encoded = WakeUpAlarmStoreCodec.encode(original)
        val decoded = WakeUpAlarmStoreCodec.decode(encoded)
        assertEquals(original, decoded)
        assertTrue(WakeUpAlarmStoreCodec.decode("not-valid-json").isEmpty())
    }
}
