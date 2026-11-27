package com.maxrave.simpmusic.autoflow

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoFlowQueueSorterTest {
    private fun track(
        id: String,
        title: String,
        durationSeconds: Int = 200,
        explicit: Boolean = false,
    ): Track =
        Track(
            album = Album(id = "", name = "Test Album"),
            artists = listOf(Artist(id = "a1", name = "Artist")),
            duration = "3:20",
            durationSeconds = durationSeconds,
            isAvailable = true,
            isExplicit = explicit,
            likeStatus = null,
            thumbnails = null,
            title = title,
            videoId = id,
            videoType = "SONG",
            category = null,
            feedbackTokens = null,
            resultType = null,
            year = null,
        )

    private val sampleTracks =
        listOf(
            track("1", "Midnight Ambient Rain", durationSeconds = 360),
            track("2", "Club Phonk Remix", durationSeconds = 150, explicit = true),
            track("3", "Acoustic Ballad", durationSeconds = 260),
            track("4", "Workout Rave Drill", durationSeconds = 175, explicit = true),
            track("5", "Midtempo Groove", durationSeconds = 210),
            track("6", "Lo-Fi Sleep Chill", durationSeconds = 330),
        )

    @Test
    fun workoutRampAscendsAndChillWindDownDescends() {
        val ramp = AutoFlowQueueSorter.previewEnergyCurve(sampleTracks, AutoFlowArc.WORKOUT_RAMP)
        assertEquals(sampleTracks.size, ramp.size)
        for (i in 0 until ramp.lastIndex) {
            assertTrue(ramp[i].energyScore <= ramp[i + 1].energyScore)
        }

        val chill = AutoFlowQueueSorter.previewEnergyCurve(sampleTracks, AutoFlowArc.CHILL_WINDDOWN)
        assertEquals(sampleTracks.size, chill.size)
        for (i in 0 until chill.lastIndex) {
            assertTrue(chill[i].energyScore >= chill[i + 1].energyScore)
        }
    }

    @Test
    fun warmupPeakCooldownPeaksNearMiddleAndPreservesMultiset() {
        val curve = AutoFlowQueueSorter.previewEnergyCurve(sampleTracks, AutoFlowArc.WARMUP_PEAK_COOLDOWN)
        assertEquals(sampleTracks.map { it.videoId }.sorted(), curve.map { it.videoId }.sorted())
        val maxIdx = curve.indices.maxByOrNull { curve[it].energyScore } ?: 0
        assertTrue(maxIdx in 1 until curve.lastIndex, "Peak should sit in the middle, not at index $maxIdx")
    }

    @Test
    fun keepFirstTrackPinnedNeverMovesIndexZero() {
        val pinned = track("pinned", "Pinned Intro", durationSeconds = 220)
        val input = listOf(pinned) + sampleTracks
        val sorted = AutoFlowQueueSorter.sortForArc(input, AutoFlowArc.PEAK_FIRST, keepFirstTrackPinned = true)
        assertEquals("pinned", sorted.first().videoId)
        assertEquals(input.size, sorted.size)
    }
}
