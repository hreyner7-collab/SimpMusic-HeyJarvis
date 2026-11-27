package com.maxrave.simpmusic.bookmarks

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal interface BookmarkKeyValueStore {
    fun getString(key: String): Flow<String?>

    suspend fun putString(
        key: String,
        value: String,
    )
}

@OptIn(ExperimentalTime::class)
class TrackBookmarkManager internal constructor(
    private val store: BookmarkKeyValueStore,
    private val seekAction: (Long) -> Unit,
    private val currentProgressProvider: () -> Long,
    private val clockNowMs: () -> Long,
) {
    constructor(
        dataStoreManager: DataStoreManager,
        mediaPlayerHandler: MediaPlayerHandler,
        clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        store =
            object : BookmarkKeyValueStore {
                override fun getString(key: String): Flow<String?> = dataStoreManager.getString(key)

                override suspend fun putString(
                    key: String,
                    value: String,
                ) = dataStoreManager.putString(key, value)
            },
        seekAction = { ms -> mediaPlayerHandler.player.seekTo(ms) },
        currentProgressProvider = { mediaPlayerHandler.getProgress() },
        clockNowMs = clockNowMs,
    )

    private val mutex = Mutex()

    val allBookmarksFlow: Flow<List<TrackBookmark>> =
        store
            .getString(TrackBookmarkCodec.DATASTORE_KEY)
            .map { raw -> sortBookmarks(TrackBookmarkCodec.decode(raw)) }
            .distinctUntilChanged()

    fun bookmarksForTrackFlow(videoId: String): Flow<List<TrackBookmark>> =
        allBookmarksFlow
            .map { all -> all.filter { it.videoId == videoId }.sortedWith(BOOKMARK_COMPARATOR) }
            .distinctUntilChanged()

    fun bookmarkedTracksFlow(): Flow<Map<String, List<TrackBookmark>>> =
        allBookmarksFlow
            .map { all ->
                all
                    .groupBy { it.videoId }
                    .mapValues { (_, list) -> list.sortedWith(BOOKMARK_COMPARATOR) }
            }.distinctUntilChanged()

    suspend fun getAllBookmarks(): List<TrackBookmark> = allBookmarksFlow.first()

    suspend fun getBookmarksForTrack(videoId: String): List<TrackBookmark> =
        getAllBookmarks().filter { it.videoId == videoId }.sortedWith(BOOKMARK_COMPARATOR)

    /**
     * Adds a bookmark for [videoId] at [timestampMs] (defaults to current playback position).
     * If another bookmark on the same track already exists within [DEDUPE_WINDOW_MS] (1s),
     * updates that bookmark in-place instead of creating a duplicate entry.
     */
    suspend fun addBookmark(
        videoId: String,
        songTitle: String,
        timestampMs: Long = currentProgressProvider(),
        label: String = "",
        note: String = "",
    ): TrackBookmark {
        require(videoId.isNotBlank()) { "videoId cannot be blank" }
        val safeTimestampMs = timestampMs.coerceAtLeast(0L)
        val cleanLabel = label.trim()
        val cleanNote = note.trim()
        val cleanTitle = songTitle.trim().ifBlank { videoId }

        return mutex.withLock {
            val current = readStoredBookmarks().toMutableList()
            val now = clockNowMs()

            val existingIndex =
                current.indexOfFirst {
                    it.videoId == videoId && abs(it.timestampMs - safeTimestampMs) <= DEDUPE_WINDOW_MS
                }

            val saved =
                if (existingIndex >= 0) {
                    val prev = current[existingIndex]
                    val updated =
                        prev.copy(
                            songTitle = cleanTitle,
                            timestampMs = safeTimestampMs,
                            label = cleanLabel.ifEmpty { prev.label },
                            note = cleanNote.ifEmpty { prev.note },
                        )
                    current[existingIndex] = updated
                    updated
                } else {
                    val id = buildBookmarkId(videoId, safeTimestampMs, now, current.size)
                    val created =
                        TrackBookmark(
                            id = id,
                            videoId = videoId,
                            songTitle = cleanTitle,
                            timestampMs = safeTimestampMs,
                            label = cleanLabel,
                            note = cleanNote,
                            createdAtMs = now,
                        )
                    current += created
                    created
                }

            writeStoredBookmarks(sortBookmarks(current))
            saved
        }
    }

    suspend fun updateBookmark(
        id: String,
        label: String,
        note: String,
        timestampMs: Long? = null,
    ): TrackBookmark? =
        mutex.withLock {
            val current = readStoredBookmarks().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock null
            val existing = current[idx]
            val updated =
                existing.copy(
                    label = label.trim(),
                    note = note.trim(),
                    timestampMs = (timestampMs ?: existing.timestampMs).coerceAtLeast(0L),
                )
            current[idx] = updated
            writeStoredBookmarks(sortBookmarks(current))
            updated
        }

    suspend fun deleteBookmark(id: String): Boolean =
        mutex.withLock {
            val current = readStoredBookmarks().toMutableList()
            val removed = current.removeAll { it.id == id }
            if (removed) {
                writeStoredBookmarks(sortBookmarks(current))
            }
            removed
        }

    suspend fun clearBookmarksForTrack(videoId: String): Int =
        mutex.withLock {
            val current = readStoredBookmarks()
            val remaining = current.filterNot { it.videoId == videoId }
            val removedCount = current.size - remaining.size
            if (removedCount > 0) {
                writeStoredBookmarks(sortBookmarks(remaining))
            }
            removedCount
        }

    fun jumpToBookmark(bookmark: TrackBookmark) {
        seekToTimestamp(bookmark.timestampMs)
    }

    fun seekToTimestamp(timestampMs: Long) {
        seekAction(timestampMs.coerceAtLeast(0L))
    }

    fun buildShareSummary(
        songTitle: String,
        bookmarks: List<TrackBookmark>,
    ): String = formatShareSummary(songTitle, bookmarks)

    private suspend fun readStoredBookmarks(): List<TrackBookmark> {
        val raw = runCatching { store.getString(TrackBookmarkCodec.DATASTORE_KEY).first() }.getOrNull()
        return TrackBookmarkCodec.decode(raw)
    }

    private suspend fun writeStoredBookmarks(bookmarks: List<TrackBookmark>) {
        store.putString(TrackBookmarkCodec.DATASTORE_KEY, TrackBookmarkCodec.encode(bookmarks))
    }

    private fun sortBookmarks(list: List<TrackBookmark>): List<TrackBookmark> =
        list.sortedWith(
            compareBy<TrackBookmark> { it.videoId }
                .thenBy { it.timestampMs }
                .thenBy { it.createdAtMs },
        )

    private fun buildBookmarkId(
        videoId: String,
        timestampMs: Long,
        nowMs: Long,
        salt: Int,
    ): String = "bm_${videoId.take(12)}_${timestampMs}_${nowMs}_$salt"

    companion object {
        const val DEDUPE_WINDOW_MS = 1_000L

        private val BOOKMARK_COMPARATOR =
            compareBy<TrackBookmark> { it.timestampMs }.thenBy { it.createdAtMs }
    }
}
