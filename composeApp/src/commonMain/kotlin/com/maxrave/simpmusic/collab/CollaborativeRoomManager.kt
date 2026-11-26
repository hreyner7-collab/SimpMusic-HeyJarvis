package com.maxrave.simpmusic.collab

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.ListenTogetherRepository
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.handoff.HandoffPeer
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "CollaborativeRoom"
private const val MAX_FEED_ITEMS = 60
private const val MAX_RECENT_REACTIONS = 8

/**
 * Enhances Listen Together rooms with real-time collaborative queue voting (upvotes), live emoji
 * reactions, room chat & activity feed, and one-tap nearby Wi-Fi room discovery over the shared
 * LAN peer transport — while staying 100% compatible with Metrolist's global sync relay.
 */
@OptIn(ExperimentalTime::class)
class CollaborativeRoomManager(
    private val listenTogetherRepository: ListenTogetherRepository,
    private val handoffManager: PlaybackHandoffManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _uiState = MutableStateFlow(CollaborativeRoomUiState())
    val uiState: StateFlow<CollaborativeRoomUiState> = _uiState.asStateFlow()

    /** Peers on the same Wi-Fi that are currently inside an active Listen Together room. */
    val nearbyRooms: StateFlow<List<HandoffPeer>> =
        handoffManager.peers
            .map { peers -> peers.filter { !it.activeRoomCode.isNullOrBlank() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val seenEventIds = LinkedHashSet<String>()
    private var lifecycleJob: Job? = null

    fun start() {
        if (lifecycleJob?.isActive == true) return
        handoffManager.registerBeaconProviders(
            roomCodeProvider = { listenTogetherRepository.room.value.roomCode },
        )
        handoffManager.registerCustomPostHandler(CollaborativeRoomProtocol.HTTP_PATH) { rawBody ->
            val event = CollaborativeRoomProtocol.decode(rawBody) ?: return@registerCustomPostHandler false
            applyIncomingEvent(event)
            true
        }

        lifecycleJob =
            scope.launch {
                launch {
                    listenTogetherRepository.room
                        .map { it.roomCode }
                        .distinctUntilChanged()
                        .collect { code ->
                            if (code != _uiState.value.roomCode) {
                                seenEventIds.clear()
                                _uiState.value =
                                    CollaborativeRoomUiState(
                                        roomCode = code,
                                        feed =
                                            if (code != null) {
                                                listOf(
                                                    CollaborativeFeedEntry(
                                                        id = "sys_join_$code",
                                                        senderName = "Room",
                                                        message = "Connected to collaborative room $code",
                                                        timestampMs = nowMs(),
                                                        isSystem = true,
                                                    ),
                                                )
                                            } else {
                                                emptyList()
                                            },
                                    )
                            }
                            handoffManager.refreshPeers()
                        }
                }
                launch {
                    listenTogetherRepository.room
                        .map { it.currentTrack?.id to it.currentTrack?.title }
                        .distinctUntilChanged()
                        .collect { (trackId, title) ->
                            val room = listenTogetherRepository.room.value
                            if (!room.inRoom || trackId.isNullOrBlank() || title.isNullOrBlank()) return@collect
                            // Clear votes on the track that just started playing and log it to the feed.
                            _uiState.update { state ->
                                state.copy(
                                    votesByTrackId = state.votesByTrackId - trackId,
                                    feed =
                                        appendFeed(
                                            state.feed,
                                            CollaborativeFeedEntry(
                                                id = "sys_track_${trackId}_${nowMs()}",
                                                senderName = "Now Playing",
                                                message = title,
                                                emoji = "🎵",
                                                timestampMs = nowMs(),
                                                isSystem = true,
                                            ),
                                        ),
                                )
                            }
                        }
                }
            }
    }

    fun sendReaction(
        emoji: String,
        displayNameOverride: String = "",
    ) {
        val room = listenTogetherRepository.room.value
        val code = room.roomCode ?: return
        val cleanEmoji = emoji.trim().take(8)
        if (cleanEmoji.isEmpty()) return
        val senderId = room.selfUserId.ifBlank { handoffManager.localDeviceId }
        val senderName = resolveSenderName(room.selfUserId, displayNameOverride)
        val event =
            CollaborativeRoomEvent(
                eventId = newEventId(),
                roomCode = code,
                type = CollaborativeEventType.REACTION,
                senderId = senderId,
                senderName = senderName,
                timestampMs = nowMs(),
                text = cleanEmoji,
            )
        applyIncomingEvent(event)
        broadcastEventToRoomPeers(code, event)
    }

    fun sendChatMessage(
        message: String,
        displayNameOverride: String = "",
    ) {
        val room = listenTogetherRepository.room.value
        val code = room.roomCode ?: return
        val cleanText = message.trim().take(240)
        if (cleanText.isEmpty()) return
        val senderId = room.selfUserId.ifBlank { handoffManager.localDeviceId }
        val senderName = resolveSenderName(room.selfUserId, displayNameOverride)
        val event =
            CollaborativeRoomEvent(
                eventId = newEventId(),
                roomCode = code,
                type = CollaborativeEventType.CHAT,
                senderId = senderId,
                senderName = senderName,
                timestampMs = nowMs(),
                text = cleanText,
            )
        applyIncomingEvent(event)
        broadcastEventToRoomPeers(code, event)
    }

    fun toggleTrackVote(
        trackId: String,
        trackTitle: String,
        displayNameOverride: String = "",
    ) {
        val room = listenTogetherRepository.room.value
        val code = room.roomCode ?: return
        if (trackId.isBlank()) return
        val voterId = room.selfUserId.ifBlank { handoffManager.localDeviceId }
        val alreadyVoted = _uiState.value.hasVoted(trackId, voterId)
        val senderName = resolveSenderName(room.selfUserId, displayNameOverride)
        val event =
            CollaborativeRoomEvent(
                eventId = newEventId(),
                roomCode = code,
                type = if (alreadyVoted) CollaborativeEventType.UNVOTE_TRACK else CollaborativeEventType.VOTE_TRACK,
                senderId = voterId,
                senderName = senderName,
                timestampMs = nowMs(),
                trackId = trackId,
                trackTitle = trackTitle,
            )
        applyIncomingEvent(event)
        broadcastEventToRoomPeers(code, event)
    }

    /**
     * Reorders upcoming tracks in the host's live player queue by collaborative vote count so the
     * room's top-voted song plays next, and publishes the updated order via ListenTogetherPlaybackBridge.
     */
    suspend fun applyTopVotedQueueOrder(): Boolean {
        val room = listenTogetherRepository.room.value
        if (!room.inRoom || !room.isHost) return false
        val votes = _uiState.value.votesByTrackId
        if (votes.isEmpty()) return false
        val queueTracks = mediaPlayerHandler.queueData.value?.data?.listTracks ?: return false
        val currentIdx = mediaPlayerHandler.currentOrderIndex().coerceIn(0, (queueTracks.size - 1).coerceAtLeast(0))
        if (currentIdx + 2 > queueTracks.size) return false

        val upcoming = queueTracks.subList(currentIdx + 1, queueTracks.size)
        val targetOrder =
            upcoming
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<com.maxrave.domain.data.model.browse.album.Track>> {
                        votes[it.value.videoId]?.size ?: 0
                    }.thenBy { it.index },
                ).map { it.value.videoId }
        val currentOrder = upcoming.map { it.videoId }
        if (targetOrder == currentOrder) return false

        // Apply minimal swaps via MediaPlayerHandler.swap(from, to) so the current playing track is untouched.
        return withContext(Dispatchers.Main) {
            try {
                val mutableIds = queueTracks.map { it.videoId }.toMutableList()
                for (offset in targetOrder.indices) {
                    val desiredId = targetOrder[offset]
                    val targetPos = currentIdx + 1 + offset
                    val foundPos = mutableIds.indexOfFirstIndexed(startIndex = targetPos) { it == desiredId }
                    if (foundPos > targetPos) {
                        mediaPlayerHandler.swap(foundPos, targetPos)
                        val moved = mutableIds.removeAt(foundPos)
                        mutableIds.add(targetPos, moved)
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        feed =
                            appendFeed(
                                state.feed,
                                CollaborativeFeedEntry(
                                    id = "sys_sort_${nowMs()}",
                                    senderName = "Queue",
                                    message = "Reordered upcoming tracks by room votes",
                                    emoji = "👍",
                                    timestampMs = nowMs(),
                                    isSystem = true,
                                ),
                            ),
                    )
                }
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to reorder queue by votes: ${e.message}")
                false
            }
        }
    }

    internal fun applyIncomingEvent(event: CollaborativeRoomEvent) {
        val currentCode = listenTogetherRepository.room.value.roomCode ?: _uiState.value.roomCode
        if (currentCode != null && !currentCode.equals(event.roomCode, ignoreCase = true)) return
        if (!seenEventIds.add(event.eventId)) return
        if (seenEventIds.size > 256) {
            seenEventIds.iterator().let { it.next(); it.remove() }
        }

        _uiState.update { state ->
            when (event.type) {
                CollaborativeEventType.REACTION -> {
                    val entry =
                        CollaborativeFeedEntry(
                            id = event.eventId,
                            senderName = event.senderName,
                            message = "reacted ${event.text}",
                            emoji = event.text,
                            timestampMs = event.timestampMs,
                        )
                    state.copy(
                        feed = appendFeed(state.feed, entry),
                        recentReactions = (state.recentReactions + entry).takeLast(MAX_RECENT_REACTIONS),
                    )
                }

                CollaborativeEventType.CHAT -> {
                    val entry =
                        CollaborativeFeedEntry(
                            id = event.eventId,
                            senderName = event.senderName,
                            message = event.text,
                            timestampMs = event.timestampMs,
                        )
                    state.copy(feed = appendFeed(state.feed, entry))
                }

                CollaborativeEventType.VOTE_TRACK -> {
                    val existing = state.votesByTrackId[event.trackId].orEmpty()
                    val updated = state.votesByTrackId + (event.trackId to (existing + event.senderId))
                    val title = event.trackTitle.ifBlank { event.trackId }
                    val entry =
                        CollaborativeFeedEntry(
                            id = event.eventId,
                            senderName = event.senderName,
                            message = "upvoted $title",
                            emoji = "👍",
                            timestampMs = event.timestampMs,
                        )
                    state.copy(
                        votesByTrackId = updated,
                        feed = appendFeed(state.feed, entry),
                    )
                }

                CollaborativeEventType.UNVOTE_TRACK -> {
                    val existing = state.votesByTrackId[event.trackId].orEmpty() - event.senderId
                    val updated =
                        if (existing.isEmpty()) {
                            state.votesByTrackId - event.trackId
                        } else {
                            state.votesByTrackId + (event.trackId to existing)
                        }
                    state.copy(votesByTrackId = updated)
                }
            }
        }
    }

    private fun broadcastEventToRoomPeers(
        roomCode: String,
        event: CollaborativeRoomEvent,
    ) {
        val body = CollaborativeRoomProtocol.encode(event)
        val targets =
            handoffManager.peers.value.filter { peer ->
                peer.activeRoomCode.equals(roomCode, ignoreCase = true)
            }
        if (targets.isEmpty()) return
        scope.launch {
            targets.forEach { peer ->
                handoffManager.sendCustomPostToPeer(peer, CollaborativeRoomProtocol.HTTP_PATH, body)
            }
        }
    }

    private fun resolveSenderName(
        selfUserId: String,
        override: String,
    ): String {
        val fromRoom =
            listenTogetherRepository.room.value.members
                .firstOrNull { it.userId == selfUserId }
                ?.username
        return fromRoom?.takeIf { it.isNotBlank() }
            ?: override.trim().takeIf { it.isNotBlank() }
            ?: handoffManager.currentDeviceName()
    }

    private fun appendFeed(
        current: List<CollaborativeFeedEntry>,
        entry: CollaborativeFeedEntry,
    ): List<CollaborativeFeedEntry> = (current + entry).takeLast(MAX_FEED_ITEMS)

    private fun newEventId(): String = "ev-${nowMs()}-${Random.nextInt(1000, 9999)}"

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun stop() {
        lifecycleJob?.cancel()
        lifecycleJob = null
    }
}

private inline fun <T> List<T>.indexOfFirstIndexed(
    startIndex: Int,
    predicate: (T) -> Boolean,
): Int {
    for (i in startIndex until size) {
        if (predicate(this[i])) return i
    }
    return -1
}
