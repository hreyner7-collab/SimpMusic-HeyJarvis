package com.maxrave.simpmusic.notch

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.simpmusic.ui.component.extractNoteTimestampsMs
import com.maxrave.simpmusic.ui.component.formatNoteTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowNotchAndTrackNotesTest {
    private val sampleLines =
        listOf(
            Line(startTimeMs = "5000", endTimeMs = "10000", words = "First line"),
            Line(startTimeMs = "10000", endTimeMs = "15000", words = "Second line"),
            Line(startTimeMs = "15000", endTimeMs = "20000", words = "Third line"),
        )

    @Test
    fun `resolveSyncedLyricLine returns null before first line or when unsynced`() {
        assertNull(
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = sampleLines,
                syncType = "UNSYNCED",
                positionMs = 11_000L,
                offsetMs = 0,
            ),
        )
        assertNull(
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = sampleLines,
                syncType = "LINE_SYNCED",
                positionMs = 2_000L,
                offsetMs = 0,
            ),
        )
    }

    @Test
    fun `resolveSyncedLyricLine matches active line and applies offset`() {
        assertEquals(
            "Second line",
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = sampleLines,
                syncType = "LINE_SYNCED",
                positionMs = 12_000L,
                offsetMs = 0,
            ),
        )
        // With +2500ms offset, ear position is 12_000 - 2_500 = 9_500ms -> still on First line
        assertEquals(
            "First line",
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = sampleLines,
                syncType = "LINE_SYNCED",
                positionMs = 12_000L,
                offsetMs = 2_500,
            ),
        )
    }

    @Test
    fun `track note timestamp parser extracts valid cues and formats positions`() {
        assertEquals("0:05", formatNoteTimestamp(5_000L))
        assertEquals("2:15", formatNoteTimestamp(135_000L))
        val parsed = extractNoteTimestampsMs("Drop starts at [1:05], outro at 3:45 (ignore 9:99)")
        assertEquals(listOf("1:05" to 65_000L, "3:45" to 225_000L), parsed)
        val defaults = NowNotchSettings()
        assertTrue(defaults.showLiveLyrics)
        assertTrue(defaults.showSideWaves)
        assertEquals(4, defaults.topOffsetDp)
        assertEquals(
            1,
            NowNotchStateHolder.resolveSyncedLyricIndex(
                lines = sampleLines,
                syncType = "LINE_SYNCED",
                positionMs = 12_000L,
                offsetMs = 0,
            ),
        )
    }

    @Test
    fun `resolveSyncedLyricLine strips rich-sync tags and supports zero-start null-syncType and long tails`() {
        val richLines =
            listOf(
                Line(startTimeMs = "0", endTimeMs = "5000", words = "<00:00.00>Intro <00:02.10>line"),
                Line(startTimeMs = "5000", endTimeMs = "12000", words = "<00:05.00>Second <00:06.50>verse"),
            )
        assertEquals("Hello world", cleanNotchLyricText("<00:10.00>Hello <00:10.50>world"))
        assertEquals(
            "Intro line",
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = richLines,
                syncType = null,
                positionMs = 0L,
                offsetMs = 0,
            ),
        )
        assertEquals(
            "Second verse",
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = richLines,
                syncType = "RICH_SYNCED",
                positionMs = 120_000L,
                offsetMs = 0,
            ),
        )
        assertEquals(
            "Second verse",
            NowNotchStateHolder.resolveNextSyncedLyricLine(
                lines = richLines,
                syncType = "RICH_SYNCED",
                positionMs = 0L,
                offsetMs = 0,
            ),
        )

        val allZeroUnsynced =
            listOf(
                Line(startTimeMs = "0", endTimeMs = "0", words = "Row 1"),
                Line(startTimeMs = "0", endTimeMs = "0", words = "Row 2"),
            )
        assertNull(
            NowNotchStateHolder.resolveSyncedLyricLine(
                lines = allZeroUnsynced,
                syncType = null,
                positionMs = 10_000L,
                offsetMs = 0,
            ),
        )
    }
}
