package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxrave.data.listentogether.ListenTogetherPlaybackBridge
import com.maxrave.data.listentogether.ListenTogetherPrefs
import com.maxrave.domain.data.model.listentogether.ListenTogetherRoom
import com.maxrave.domain.data.model.listentogether.RoomTrack
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.ListenTogetherRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.collab.CollaborativeRoomManager
import com.maxrave.simpmusic.collab.CollaborativeRoomProtocol
import com.maxrave.simpmusic.collab.CollaborativeRoomUiState
import com.maxrave.simpmusic.handoff.HandoffPeer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Screen state for Listen Together & Real-Time Collaborative Listening.
 */
class ListenTogetherViewModel(
    private val repository: ListenTogetherRepository,
    private val dataStore: DataStoreManager,
    bridge: ListenTogetherPlaybackBridge,
    private val collaborativeRoomManager: CollaborativeRoomManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
) : ViewModel() {
    init {
        bridge.start()
        collaborativeRoomManager.start()

        viewModelScope.launch {
            dataStore.getString(ListenTogetherPrefs.AUTO_APPROVE_JOINS).collect {
                repository.autoApproveJoins = it == ListenTogetherPrefs.TRUE
            }
        }
        viewModelScope.launch {
            dataStore.getString(ListenTogetherPrefs.AUTO_APPROVE_SUGGESTIONS).collect {
                repository.autoApproveSuggestions = it == ListenTogetherPrefs.TRUE
            }
        }
    }

    val state: StateFlow<ListenTogetherRoom> =
        repository.room.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListenTogetherRoom())

    val collabState: StateFlow<CollaborativeRoomUiState> = collaborativeRoomManager.uiState

    val nearbyRooms: StateFlow<List<HandoffPeer>> = collaborativeRoomManager.nearbyRooms

    val autoApproveSuggestions: StateFlow<Boolean> =
        dataStore
            .getString(ListenTogetherPrefs.AUTO_APPROVE_SUGGESTIONS)
            .map { it == ListenTogetherPrefs.TRUE }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _roomCodeInput = MutableStateFlow("")
    val roomCodeInput: StateFlow<String> = _roomCodeInput.asStateFlow()

    private val _chatDraft = MutableStateFlow("")
    val chatDraft: StateFlow<String> = _chatDraft.asStateFlow()

    private val _suggestQuery = MutableStateFlow("")
    val suggestQuery: StateFlow<String> = _suggestQuery.asStateFlow()

    private val _suggestResults = MutableStateFlow<List<RoomTrack>>(emptyList())
    val suggestResults: StateFlow<List<RoomTrack>> = _suggestResults.asStateFlow()

    private val _isSearchingSuggestions = MutableStateFlow(false)
    val isSearchingSuggestions: StateFlow<Boolean> = _isSearchingSuggestions.asStateFlow()

    private var searchJob: Job? = null

    fun onDisplayNameChange(value: String) {
        _displayName.value = value.take(MAX_USERNAME_LENGTH)
    }

    fun onRoomCodeChange(value: String) {
        _roomCodeInput.value = CollaborativeRoomProtocol.extractRoomCode(value)
    }

    fun onChatDraftChange(value: String) {
        _chatDraft.value = value.take(240)
    }

    fun onSuggestQueryChange(value: String) {
        _suggestQuery.value = value
        if (value.isBlank()) {
            searchJob?.cancel()
            _suggestResults.value = emptyList()
            _isSearchingSuggestions.value = false
        }
    }

    fun searchTracksToSuggest() {
        val q = _suggestQuery.value.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _isSearchingSuggestions.value = true
                try {
                    searchRepository.getSearchDataSong(q).collect { resource ->
                        if (resource is Resource.Success) {
                            val mapped =
                                resource.data
                                    .orEmpty()
                                    .take(8)
                                    .mapNotNull { song ->
                                        val vid = song.videoId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                        RoomTrack(
                                            id = vid,
                                            title = song.title.orEmpty(),
                                            artist = song.artists.orEmpty().joinToString(", ") { it.name },
                                            album = song.album?.name.orEmpty(),
                                            durationMs = (song.durationSeconds ?: 0).toLong() * 1000L,
                                            thumbnail = song.thumbnails.orEmpty().lastOrNull()?.url.orEmpty(),
                                            suggestedBy = _displayName.value.trim(),
                                        )
                                    }
                            _suggestResults.value = mapped
                            _isSearchingSuggestions.value = false
                        } else if (resource is Resource.Error) {
                            _isSearchingSuggestions.value = false
                        }
                    }
                } catch (_: Exception) {
                    _isSearchingSuggestions.value = false
                }
            }
    }

    fun suggestTrack(track: RoomTrack) {
        if (state.value.isHost) {
            // Host directly queues the track via MediaPlayerHandler.playNext so everyone gets it immediately.
            viewModelScope.launch {
                val asTrack =
                    com.maxrave.domain.data.model.browse.album.Track(
                        album = track.album.takeIf { it.isNotBlank() }?.let { com.maxrave.domain.data.model.searchResult.songs.Album("", it) },
                        artists = listOf(com.maxrave.domain.data.model.searchResult.songs.Artist(null, track.artist)),
                        duration = "",
                        durationSeconds = (track.durationMs / 1000L).toInt(),
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        thumbnails =
                            track.thumbnail.takeIf { it.isNotBlank() }?.let {
                                listOf(com.maxrave.domain.data.model.searchResult.songs.Thumbnail(544, it, 544))
                            } ?: emptyList(),
                        title = track.title,
                        videoId = track.id,
                        videoType = "MUSIC_VIDEO_TYPE_ATV",
                        category = "Song",
                        feedbackTokens = null,
                        resultType = "Song",
                    )
                runCatching { mediaPlayerHandler.playNext(asTrack) }
            }
        } else {
            repository.suggestTrack(track)
        }
    }

    fun suggestCurrentPlayingTrack(): Boolean {
        val np = mediaPlayerHandler.nowPlayingState.value
        val song = np.songEntity
        val track = np.track
        val roomTrack =
            when {
                song != null && song.videoId.isNotBlank() ->
                    RoomTrack(
                        id = song.videoId,
                        title = song.title,
                        artist = song.artistName.orEmpty().joinToString(", "),
                        album = song.albumName.orEmpty(),
                        durationMs = song.durationSeconds.toLong() * 1000L,
                        thumbnail = song.thumbnails.orEmpty(),
                        suggestedBy = _displayName.value.trim(),
                    )

                track != null && track.videoId.isNotBlank() ->
                    RoomTrack(
                        id = track.videoId,
                        title = track.title,
                        artist = track.artists.orEmpty().joinToString(", ") { it.name },
                        album = track.album?.name.orEmpty(),
                        durationMs = (track.durationSeconds ?: 0).toLong() * 1000L,
                        thumbnail = track.thumbnails.orEmpty().lastOrNull()?.url.orEmpty(),
                        suggestedBy = _displayName.value.trim(),
                    )

                else -> return false
            }
        repository.suggestTrack(roomTrack)
        return true
    }

    fun sendReaction(emoji: String) {
        collaborativeRoomManager.sendReaction(emoji, _displayName.value)
    }

    fun sendChatMessage() {
        val text = _chatDraft.value.trim()
        if (text.isEmpty()) return
        collaborativeRoomManager.sendChatMessage(text, _displayName.value)
        _chatDraft.value = ""
    }

    fun toggleTrackVote(
        trackId: String,
        trackTitle: String,
    ) {
        collaborativeRoomManager.toggleTrackVote(trackId, trackTitle, _displayName.value)
    }

    fun applyTopVotedQueueOrder() {
        viewModelScope.launch {
            collaborativeRoomManager.applyTopVotedQueueOrder()
        }
    }

    fun setAutoApproveSuggestions(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.putString(
                ListenTogetherPrefs.AUTO_APPROVE_SUGGESTIONS,
                if (enabled) ListenTogetherPrefs.TRUE else ListenTogetherPrefs.FALSE,
            )
        }
    }

    fun joinNearbyRoom(roomCode: String) {
        val cleaned = CollaborativeRoomProtocol.extractRoomCode(roomCode)
        _roomCodeInput.value = cleaned
        if (_displayName.value.isBlank()) {
            _displayName.value = "Listener"
        }
        if (!state.value.isConnected) {
            repository.connect()
        } else if (cleaned.length == ROOM_CODE_LENGTH) {
            repository.joinRoom(cleaned, _displayName.value)
        }
    }

    fun requestSync() = repository.requestSync()

    fun connect() = repository.connect()

    fun disconnect() = repository.disconnect()

    fun createRoom() {
        repository.createRoom(_displayName.value)
    }

    fun joinRoom() {
        repository.joinRoom(_roomCodeInput.value, _displayName.value)
    }

    fun leaveRoom() {
        repository.leaveRoom()
        _roomCodeInput.value = ""
    }

    fun approveJoin(userId: String) {
        repository.approveJoin(userId)
    }

    fun rejectJoin(userId: String) {
        repository.rejectJoin(userId)
    }

    fun approveSuggestion(id: String) {
        repository.approveSuggestion(id)
    }

    fun approveAllSuggestions() {
        state.value.suggestions.forEach { repository.approveSuggestion(it.suggestionId) }
    }

    fun rejectSuggestion(id: String) {
        repository.rejectSuggestion(id)
    }

    fun kickUser(userId: String) {
        repository.kickUser(userId)
    }

    fun blockAndKick(
        userId: String,
        username: String,
    ) {
        viewModelScope.launch {
            val current =
                dataStore
                    .getString(ListenTogetherPrefs.BLOCKLIST)
                    .first()
                    .orEmpty()
                    .split(ListenTogetherPrefs.BLOCKLIST_SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (current.none { it.equals(username, ignoreCase = true) }) {
                dataStore.putString(
                    ListenTogetherPrefs.BLOCKLIST,
                    (current + username).joinToString(ListenTogetherPrefs.BLOCKLIST_SEPARATOR),
                )
            }
            repository.kickUser(userId)
        }
    }

    fun transferHost(userId: String) {
        repository.transferHost(userId)
    }

    fun cancelJoin() = repository.cancelJoin()

    fun clearError() = repository.clearError()

    companion object {
        const val ROOM_CODE_LENGTH = CollaborativeRoomProtocol.ROOM_CODE_LENGTH
        private const val MAX_USERNAME_LENGTH = 50
    }
}
