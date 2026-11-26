package com.maxrave.simpmusic.collab

import com.maxrave.domain.data.model.listentogether.RoomTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CollaborativeEventType {
    REACTION,
    CHAT,
    VOTE_TRACK,
    UNVOTE_TRACK,
}

@Serializable
data class CollaborativeRoomEvent(
    val eventId: String,
    val roomCode: String,
    val type: CollaborativeEventType,
    val senderId: String,
    val senderName: String,
    val timestampMs: Long,
    val text: String = "",
    val trackId: String = "",
    val trackTitle: String = "",
)

data class CollaborativeFeedEntry(
    val id: String,
    val senderName: String,
    val message: String,
    val emoji: String? = null,
    val timestampMs: Long,
    val isSystem: Boolean = false,
)

data class CollaborativeRoomUiState(
    val roomCode: String? = null,
    val votesByTrackId: Map<String, Set<String>> = emptyMap(),
    val feed: List<CollaborativeFeedEntry> = emptyList(),
    val recentReactions: List<CollaborativeFeedEntry> = emptyList(),
) {
    fun voteCount(trackId: String): Int = votesByTrackId[trackId]?.size ?: 0

    fun hasVoted(
        trackId: String,
        voterId: String,
    ): Boolean = votesByTrackId[trackId]?.contains(voterId) == true
}

object CollaborativeRoomProtocol {
    const val HTTP_PATH = "/collab/event"
    const val ROOM_CODE_LENGTH = 8
    val QUICK_REACTIONS = listOf("🔥", "❤️", "🎵", "🙌", "👏", "⚡")

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encode(event: CollaborativeRoomEvent): String = json.encodeToString(event)

    fun decode(raw: String): CollaborativeRoomEvent? =
        runCatching { json.decodeFromString<CollaborativeRoomEvent>(raw) }.getOrNull()

    /**
     * Deterministically orders upcoming collaborative tracks by upvote count descending,
     * preserving original queue order on ties.
     */
    fun rankUpcomingQueue(
        queue: List<RoomTrack>,
        votesByTrackId: Map<String, Set<String>>,
    ): List<RoomTrack> =
        queue
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<RoomTrack>> { votesByTrackId[it.value.id]?.size ?: 0 }
                    .thenBy { it.index },
            ).map { it.value }

    /**
     * Extracts an 8-character room code from raw input, invite text (e.g.
     * "Join my SimpMusic room with code ABCD1234"), or deep link ("simpmusic://room/ABCD1234").
     */
    fun extractRoomCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val afterSlash =
            if (trimmed.contains("simpmusic://room/", ignoreCase = true)) {
                trimmed.substringAfter("simpmusic://room/", "").substringBefore("?").substringBefore(" ")
            } else {
                null
            }
        if (!afterSlash.isNullOrBlank()) {
            return afterSlash.uppercase().filter { it.isLetterOrDigit() }.take(ROOM_CODE_LENGTH)
        }
        val tokens = trimmed.split(Regex("[\\s/:?=&]+")).filter { it.isNotBlank() }
        if (tokens.size > 1) {
            val exactCode =
                tokens.lastOrNull { token ->
                    val cleaned = token.filter { it.isLetterOrDigit() }
                    cleaned.length == ROOM_CODE_LENGTH && cleaned.any { it.isDigit() || it.isUpperCase() }
                }
            if (exactCode != null) {
                return exactCode.uppercase().filter { it.isLetterOrDigit() }.take(ROOM_CODE_LENGTH)
            }
        }
        return trimmed.uppercase().filter { it.isLetterOrDigit() }.take(ROOM_CODE_LENGTH)
    }

    fun buildShareInviteText(roomCode: String): String =
        "Join my SimpMusic room with code $roomCode (simpmusic://room/$roomCode)"
}
