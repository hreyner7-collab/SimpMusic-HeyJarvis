package com.maxrave.simpmusic.smartplaylist

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartPlaylistEngineTest {
    private val nowMs = 1_800_000_000_000L
    private val dayMs = SmartPlaylistEngine.MILLIS_PER_DAY

    private fun song(
        id: String,
        title: String,
        artists: List<String> = listOf("Artist A"),
        durationSec: Int = 200,
        liked: Boolean = false,
        downloaded: Boolean = false,
        totalPlayTime: Long = 0L,
        inLibraryDay: Int = 1,
    ): SongEntity =
        SongEntity(
            videoId = id,
            title = title,
            artistName = artists,
            duration = "${durationSec / 60}:${(durationSec % 60).toString().padStart(2, '0')}",
            durationSeconds = durationSec,
            isAvailable = true,
            isExplicit = false,
            likeStatus = if (liked) "LIKE" else "INDIFFERENT",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            resultType = "Song",
            liked = liked,
            totalPlayTime = totalPlayTime,
            downloadState = if (downloaded) DownloadState.STATE_DOWNLOADED else DownloadState.STATE_NOT_DOWNLOADED,
            inLibrary = LocalDateTime(2026, 1, inLibraryDay.coerceIn(1, 28), 12, 0, 0),
        )

    @Test
    fun evaluatesAllFiveBuiltInPresetsAccurately() {
        val songs =
            listOf(
                song("old_fav", "Old Gem", listOf("Bonobo"), durationSec = 240, liked = true, totalPlayTime = 120, inLibraryDay = 2),
                song("never_played_fav", "Hidden Track", listOf("Four Tet"), durationSec = 150, liked = true, totalPlayTime = 0, inLibraryDay = 3),
                song("heavy_1", "Banger One", listOf("Daft Punk"), durationSec = 210, liked = true, downloaded = true, totalPlayTime = 1200, inLibraryDay = 10),
                song("heavy_2", "Banger Two", listOf("Justice"), durationSec = 170, liked = false, downloaded = false, totalPlayTime = 600, inLibraryDay = 12),
                song("short_1", "Interlude", listOf("Frank Ocean"), durationSec = 95, liked = false, downloaded = false, totalPlayTime = 50, inLibraryDay = 5),
                song("offline_fav", "Flight Mode", listOf("Khruangbin"), durationSec = 310, liked = true, downloaded = true, totalPlayTime = 90, inLibraryDay = 20),
            )
        val lastPlayed =
            mapOf(
                "old_fav" to (nowMs - 90L * dayMs),
                // never_played_fav omitted -> never played
                "heavy_1" to (nowMs - 2L * dayMs),
                "heavy_2" to (nowMs - 10L * dayMs),
                "short_1" to (nowMs - 5L * dayMs),
                "offline_fav" to (nowMs - 40L * dayMs),
            )

        // 1. Forgotten Favorites: liked & > 60 days since last played (or never played), sorted least played
        val forgotten = SmartPlaylistEngine.evaluate(SmartPlaylistPresets.forgottenFavorites, songs, lastPlayed, nowMs)
        assertEquals(listOf("never_played_fav", "old_fav"), forgotten.map { it.videoId })

        // 2. Heavy Rotation: play time > 300 & played < 30 days ago, sorted most played
        val heavy = SmartPlaylistEngine.evaluate(SmartPlaylistPresets.heavyRotation, songs, lastPlayed, nowMs)
        assertEquals(listOf("heavy_1", "heavy_2"), heavy.map { it.videoId })

        // 3. Short & Punchy: 0 < duration < 180s, sorted shortest first
        val shortOnes = SmartPlaylistEngine.evaluate(SmartPlaylistPresets.shortAndPunchy, songs, lastPlayed, nowMs)
        assertEquals(listOf("short_1", "never_played_fav", "heavy_2"), shortOnes.map { it.videoId })

        // 4. Deep Cuts: liked & low play time (< 180), sorted least played
        val deepCuts = SmartPlaylistEngine.evaluate(SmartPlaylistPresets.deepCuts, songs, lastPlayed, nowMs)
        assertEquals(listOf("never_played_fav", "offline_fav", "old_fav"), deepCuts.map { it.videoId })

        // 5. Offline Ready: downloaded & liked, sorted recently added
        val offline = SmartPlaylistEngine.evaluate(SmartPlaylistPresets.offlineReady, songs, lastPlayed, nowMs)
        assertEquals(listOf("offline_fav", "heavy_1"), offline.map { it.videoId })
    }

    @Test
    fun supportsAllVsAnyMatchModesAndCaseInsensitiveTextRules() {
        val songs =
            listOf(
                song("s1", "Midnight City", listOf("M83"), liked = true),
                song("s2", "Starlight", listOf("Muse"), liked = false),
                song("s3", "Midnight Train", listOf("Sam Smith"), liked = false),
            )

        val allRule =
            SmartPlaylistDefinition(
                id = "c1",
                name = "Liked Midnight",
                matchMode = SmartPlaylistMatchMode.ALL,
                conditions =
                    listOf(
                        SmartPlaylistCondition(SmartPlaylistRuleField.LIKED, SmartPlaylistOperator.EQUALS, "true"),
                        SmartPlaylistCondition(SmartPlaylistRuleField.TITLE_CONTAINS, SmartPlaylistOperator.CONTAINS, "midnight"),
                    ),
            )
        assertEquals(listOf("s1"), SmartPlaylistEngine.evaluate(allRule, songs, emptyMap(), nowMs).map { it.videoId })

        val anyRule =
            allRule.copy(
                matchMode = SmartPlaylistMatchMode.ANY,
                conditions =
                    listOf(
                        SmartPlaylistCondition(SmartPlaylistRuleField.ARTIST_CONTAINS, SmartPlaylistOperator.CONTAINS, "muse"),
                        SmartPlaylistCondition(SmartPlaylistRuleField.TITLE_CONTAINS, SmartPlaylistOperator.CONTAINS, "midnight"),
                    ),
                sort = SmartPlaylistSort.TITLE_ASC,
            )
        assertEquals(listOf("s1", "s3", "s2"), SmartPlaylistEngine.evaluate(anyRule, songs, emptyMap(), nowMs).map { it.videoId })
    }

    @Test
    fun respectsSortingLimitsAndEdgeCases() {
        val songs =
            listOf(
                song("a", "Zebra", durationSec = 400, totalPlayTime = 10, inLibraryDay = 1),
                song("b", "Alpha", durationSec = 100, totalPlayTime = 500, inLibraryDay = 15),
                song("c", "Middle", durationSec = 250, totalPlayTime = 200, inLibraryDay = 8),
            )

        val longestTop2 =
            SmartPlaylistDefinition(
                id = "l2",
                name = "Longest 2",
                conditions = emptyList(),
                sort = SmartPlaylistSort.LONGEST,
                limit = 2,
            )
        assertEquals(listOf("a", "c"), SmartPlaylistEngine.evaluate(longestTop2, songs, emptyMap(), nowMs).map { it.videoId })

        val recent = longestTop2.copy(sort = SmartPlaylistSort.RECENTLY_ADDED, limit = 10)
        assertEquals(listOf("b", "c", "a"), SmartPlaylistEngine.evaluate(recent, songs, emptyMap(), nowMs).map { it.videoId })

        assertTrue(SmartPlaylistEngine.evaluate(longestTop2, emptyList(), emptyMap(), nowMs).isEmpty())

        // Serialization round-trip
        val encoded = SmartPlaylistCodec.encodeCustomPlaylists(listOf(longestTop2))
        val decoded = SmartPlaylistCodec.decodeCustomPlaylists(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Longest 2", decoded.first().name)
        assertEquals(SmartPlaylistSort.LONGEST, decoded.first().sort)
    }
}
