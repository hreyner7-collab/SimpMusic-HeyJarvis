package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.simpmusic.utils.cache.HomeDiskCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceUpgradesTest {
    private fun sampleSnapshot(): FastPageCache.HomeSnapshot {
        val watchEndpoint =
            YouTubeWatchEndpoint(
                videoId = "vid-1",
                playlistId = "RDAMVMvid-1",
                playlistSetVideoId = "set-99",
                params = "wAEB",
                index = 2,
                watchEndpointMusicSupportedConfigs =
                    YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs(
                        watchEndpointMusicConfig =
                            YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(
                                musicVideoType =
                                    YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_ATV,
                            ),
                    ),
            )
        val quickPicksItem =
            HomeItem(
                title = "Quick picks",
                subtitle = "Start radio from a song",
                thumbnail = listOf(Thumbnail(height = 120, url = "https://img.example/header.jpg", width = 120)),
                channelId = "UC12345",
                moreEndpoint =
                    HomeItem.MoreEndpoint(
                        browseId = "FEmusic_quick_picks",
                        params = "more-params",
                        pageType = HomeItem.MoreEndpoint.PAGE_TYPE_PLAYLIST,
                    ),
                itemsPerColumn = 4,
                playAllEndpoint = watchEndpoint,
                contents =
                    listOf(
                        Content(
                            album = Album(id = "MPREb_abc", name = "Midnight Signals"),
                            artists = listOf(Artist(id = "UCart1", name = "The Synthwave")),
                            description = "Top pick for you",
                            isExplicit = true,
                            playlistId = "PL123",
                            browseId = "VLPL123",
                            thumbnails = listOf(Thumbnail(height = 544, url = "https://img.example/song1.jpg", width = 544)),
                            title = "Neon Drive",
                            videoId = "vid-1",
                            views = "1.2M plays",
                            durationSeconds = 214,
                            radio = "RDAMVMvid-1",
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            isLive = false,
                            playEndpoint = watchEndpoint,
                        ),
                        null,
                        Content(
                            album = null,
                            artists = listOf(Artist(id = null, name = "Live Streamer")),
                            description = null,
                            isExplicit = false,
                            playlistId = null,
                            browseId = null,
                            thumbnails = listOf(Thumbnail(height = 360, url = "https://img.example/live.jpg", width = 640)),
                            title = "Lofi Radio 24/7",
                            videoId = "vid-live",
                            views = "4.5K watching",
                            durationSeconds = null,
                            radio = null,
                            videoType = "MUSIC_VIDEO_TYPE_UGC",
                            isLive = true,
                            playEndpoint = null,
                        ),
                    ),
            )
        val newReleaseItem =
            HomeItem(
                title = "New releases",
                contents =
                    listOf(
                        Content(
                            album = Album(id = "MPREb_new", name = "Fresh Drop"),
                            artists = listOf(Artist(id = "UCart2", name = "Nova")),
                            description = "Single",
                            isExplicit = false,
                            playlistId = null,
                            browseId = "MPREb_new",
                            thumbnails = listOf(Thumbnail(height = 400, url = "https://img.example/new.jpg", width = 400)),
                            title = "Fresh Drop",
                            videoId = "vid-new",
                            views = null,
                            durationSeconds = 185,
                            radio = null,
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            isLive = false,
                            playEndpoint = null,
                        ),
                    ),
            )
        return FastPageCache.HomeSnapshot(
            homeItems = listOf(quickPicksItem),
            continuation = "4qmFsgI_continuation_token",
            homeBackground = "https://img.example/home-bg.jpg",
            mood = null,
            chart = null,
            newRelease = listOf(newReleaseItem),
        )
    }

    @Test
    fun `HomeDiskCache JSON codec round-trips full HomeSnapshot without data loss`() {
        val original = sampleSnapshot()
        val cacheKey = "|US|en-US|123456"
        val savedAt = 1_700_000_000_000L

        val rawJson = HomeDiskCache.encode(cacheKey = cacheKey, snapshot = original, savedAtEpochMs = savedAt)
        val decoded =
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = cacheKey,
                nowMs = savedAt + 10_000L,
            )

        assertNotNull(decoded)
        assertEquals(savedAt, decoded.savedAtEpochMs)
        assertTrue(decoded.isFresh)
        assertEquals(original, decoded.snapshot)
    }

    @Test
    fun `HomeDiskCache enforces fresh window and 24h stale-while-revalidate TTL`() {
        val snapshot = sampleSnapshot()
        val cacheKey = "|US|en-US|0"
        val savedAt = 1_700_000_000_000L
        val rawJson = HomeDiskCache.encode(cacheKey = cacheKey, snapshot = snapshot, savedAtEpochMs = savedAt)

        // Fresh within 5 minutes
        val fresh =
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = cacheKey,
                nowMs = savedAt + HomeDiskCache.HOME_DISK_FRESH_MS,
            )
        assertNotNull(fresh)
        assertTrue(fresh.isFresh)

        // Stale-while-revalidate valid after 5 min and up to 24 hours
        val staleMid =
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = cacheKey,
                nowMs = savedAt + 6L * 60L * 60L * 1000L,
            )
        assertNotNull(staleMid)
        assertFalse(staleMid.isFresh)
        assertEquals(snapshot.homeItems.size, staleMid.snapshot.homeItems.size)

        // Exact 24-hour boundary is still valid (stale)
        val atBoundary =
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = cacheKey,
                nowMs = savedAt + HomeDiskCache.HOME_DISK_TTL_MS,
            )
        assertNotNull(atBoundary)
        assertFalse(atBoundary.isFresh)

        // Past 24 hours is expired
        val expired =
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = cacheKey,
                nowMs = savedAt + HomeDiskCache.HOME_DISK_TTL_MS + 1L,
            )
        assertNull(expired)
    }

    @Test
    fun `HomeDiskCache rejects mismatched keys, corrupt JSON, empty items, and future timestamps`() {
        val snapshot = sampleSnapshot()
        val savedAt = 1_700_000_000_000L
        val rawJson = HomeDiskCache.encode(cacheKey = "|US|en-US|1", snapshot = snapshot, savedAtEpochMs = savedAt)

        // Key mismatch
        assertNull(
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = "|GB|en-GB|1",
                nowMs = savedAt + 1_000L,
            ),
        )

        // Corrupt JSON
        assertNull(
            HomeDiskCache.decode(
                rawJson = "{not valid json",
                expectedCacheKey = "|US|en-US|1",
                nowMs = savedAt + 1_000L,
            ),
        )

        // Future timestamp (negative age)
        assertNull(
            HomeDiskCache.decode(
                rawJson = rawJson,
                expectedCacheKey = "|US|en-US|1",
                nowMs = savedAt - 1_000L,
            ),
        )

        // Empty homeItems rejected
        val emptySnapshot = snapshot.copy(homeItems = emptyList())
        val emptyJson = HomeDiskCache.encode(cacheKey = "|US|en-US|1", snapshot = emptySnapshot, savedAtEpochMs = savedAt)
        assertNull(
            HomeDiskCache.decode(
                rawJson = emptyJson,
                expectedCacheKey = "|US|en-US|1",
                nowMs = savedAt + 1_000L,
            ),
        )
    }

    @Test
    fun `selectTwoCoreMask picks 2 available logical cores across standard, sparse, single-core, and 64-bit masks`() {
        // Fallback when system mask is 0
        assertEquals(DEFAULT_TWO_CORE_AFFINITY_MASK, selectTwoCoreMask(0L))

        // Standard multi-core Windows masks select CPU 0 + CPU 1 (0x3L)
        assertEquals(0x3L, selectTwoCoreMask(0x3L))
        assertEquals(0x3L, selectTwoCoreMask(0xFL))
        assertEquals(0x3L, selectTwoCoreMask(0xFFL))
        assertEquals(0x3L, selectTwoCoreMask(0xFFFFL))
        assertEquals(0x3L, selectTwoCoreMask(-1L))

        // Single-core CPU/VM keeps only that 1 available core (never sets unavailable bit)
        assertEquals(0x1L, selectTwoCoreMask(0x1L))
        assertEquals(0x8L, selectTwoCoreMask(0x8L))

        // Restricted or sparse system masks pick the first 2 set bits
        assertEquals(0x0CL, selectTwoCoreMask(0x0CL))
        assertEquals(0x30L, selectTwoCoreMask(0xF0L))
        assertEquals(0x28L, selectTwoCoreMask(0xA8L)) // bits 3, 5, 7 -> bits 3 & 5

        // High 64-bit masks including sign bit (bit 63 = Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, selectTwoCoreMask(Long.MIN_VALUE))
        val topTwo = (1L shl 62) or Long.MIN_VALUE
        assertEquals(topTwo, selectTwoCoreMask(topTwo))
        val sparse64 = (1L shl 10) or (1L shl 40) or Long.MIN_VALUE
        assertEquals((1L shl 10) or (1L shl 40), selectTwoCoreMask(sparse64))

        // Invariant check: selected mask is always a non-empty subset of systemMask with <= 2 bits set
        val samples = listOf(0x1L, 0x2L, 0x3L, 0x5L, 0xF0L, 0xFFFF0000L, -1L, Long.MIN_VALUE, topTwo, sparse64)
        for (mask in samples) {
            val selected = selectTwoCoreMask(mask)
            assertTrue(selected != 0L, "Selected mask must be non-zero for $mask")
            assertTrue(selected.countOneBits() in 1..2, "Expected 1..2 cores for $mask, got ${selected.countOneBits()}")
            assertEquals(selected, selected and mask, "Selected mask $selected must be a subset of $mask")
        }
    }
}
