package com.maxrave.simpmusic.bookmarks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackBookmarkManagerTest {
    private class FakeKeyValueStore : BookmarkKeyValueStore {
        private val mapFlow = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun getString(key: String): Flow<String?> = mapFlow.map { it[key] }

        override suspend fun putString(
            key: String,
            value: String,
        ) {
            mapFlow.value = mapFlow.value + (key to value)
        }
    }

    @Test
    fun addsSortsDeduplicatesUpdatesDeletesAndSeeksBookmarks() =
        runBlocking {
            val store = FakeKeyValueStore()
            val seekedPositions = mutableListOf<Long>()
            var fakeNow = 10_000L
            var fakeProgress = 42_000L

            val manager =
                TrackBookmarkManager(
                    store = store,
                    seekAction = { seekedPositions += it },
                    currentProgressProvider = { fakeProgress },
                    clockNowMs = { fakeNow.also { fakeNow += 1_000L } },
                )

            // Add bookmarks out of chronological order (including default currentProgress)
            val bmLate = manager.addBookmark("vid_1", "Comfortably Numb", timestampMs = 285_000L, label = "Second solo")
            val bmEarly = manager.addBookmark("vid_1", "Comfortably Numb", label = "First verse") // uses fakeProgress = 42_000L
            val bmMid = manager.addBookmark("vid_1", "Comfortably Numb", timestampMs = 135_000L, label = "Chorus", note = "Strings swell")
            manager.addBookmark("vid_2", "Other Song", timestampMs = 15_000L, label = "Intro")

            val sortedForVid1 = manager.getBookmarksForTrack("vid_1")
            assertEquals(listOf(42_000L, 135_000L, 285_000L), sortedForVid1.map { it.timestampMs })

            // Dedupe within 1s window updates existing bookmark rather than duplicating
            val deduped =
                manager.addBookmark(
                    videoId = "vid_1",
                    songTitle = "Comfortably Numb",
                    timestampMs = 42_500L,
                    label = "Opening vocal",
                    note = "Updated note",
                )
            assertEquals(bmEarly.id, deduped.id)
            assertEquals(3, manager.getBookmarksForTrack("vid_1").size)
            assertEquals("Opening vocal", manager.getBookmarksForTrack("vid_1").first().label)

            // Jump seeks player to bookmark timestamp
            manager.jumpToBookmark(bmMid)
            assertEquals(listOf(135_000L), seekedPositions)

            // Update bookmark metadata
            val updated = manager.updateBookmark(bmLate.id, label = "Epic Outro Solo", note = "Best 2 minutes in rock")
            assertEquals("Epic Outro Solo", updated?.label)
            assertEquals("Best 2 minutes in rock", updated?.note)

            // Delete bookmark
            assertTrue(manager.deleteBookmark(bmMid.id))
            assertFalse(manager.deleteBookmark("non_existent"))
            assertEquals(2, manager.getBookmarksForTrack("vid_1").size)

            // Other track bookmarks remain intact
            assertEquals(1, manager.getBookmarksForTrack("vid_2").size)
        }

    @Test
    fun formatsTimestampsAndShareableSummary() {
        assertEquals("0:00", formatTimestamp(-500L))
        assertEquals("0:09", formatTimestamp(9_400L))
        assertEquals("2:15", formatTimestamp(135_000L))
        assertEquals("1:05:03", formatTimestamp(3_903_000L))

        val summary =
            formatShareSummary(
                songTitle = "Bohemian Rhapsody",
                bookmarks =
                    listOf(
                        TrackBookmark("2", "v1", "Bohemian Rhapsody", 248_000L, "Rock section", "Headbanging"),
                        TrackBookmark("1", "v1", "Bohemian Rhapsody", 55_000L, "Piano ballad", ""),
                        TrackBookmark("3", "v1", "Bohemian Rhapsody", 300_000L, "", ""),
                    ),
            )

        val expected =
            """
            Bohemian Rhapsody — Bookmarks
            0:55 · Piano ballad
            4:08 · Rock section — Headbanging
            5:00 · Bookmark
            """.trimIndent()
        assertEquals(expected, summary)
    }
}
