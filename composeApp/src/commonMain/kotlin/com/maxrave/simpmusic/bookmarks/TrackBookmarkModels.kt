package com.maxrave.simpmusic.bookmarks

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackBookmark(
    val id: String,
    val videoId: String,
    val songTitle: String,
    val timestampMs: Long,
    val label: String = "",
    val note: String = "",
    val createdAtMs: Long = 0L,
)

fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatShareSummary(
    songTitle: String,
    bookmarks: List<TrackBookmark>,
): String {
    val cleanTitle = songTitle.trim().ifBlank { "Track" }
    val sorted = bookmarks.sortedWith(compareBy<TrackBookmark> { it.timestampMs }.thenBy { it.createdAtMs })
    if (sorted.isEmpty()) return "$cleanTitle — No bookmarks"
    return buildString {
        append(cleanTitle).append(" — Bookmarks\n")
        sorted.forEachIndexed { index, bm ->
            val time = formatTimestamp(bm.timestampMs)
            val label = bm.label.trim()
            val note = bm.note.trim()
            val detail =
                when {
                    label.isNotEmpty() && note.isNotEmpty() -> "$label — $note"
                    label.isNotEmpty() -> label
                    note.isNotEmpty() -> note
                    else -> "Bookmark"
                }
            append(time).append(" · ").append(detail)
            if (index < sorted.lastIndex) append('\n')
        }
    }
}

object TrackBookmarkCodec {
    const val DATASTORE_KEY = "track_bookmarks_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encode(bookmarks: List<TrackBookmark>): String = json.encodeToString(bookmarks)

    fun decode(raw: String?): List<TrackBookmark> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<TrackBookmark>>(raw)
        }.getOrDefault(emptyList())
    }
}
