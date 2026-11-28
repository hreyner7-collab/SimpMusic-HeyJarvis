package com.maxrave.simpmusic.handoff

import com.maxrave.common.Config
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.effectiveHandoffDeviceName
import com.maxrave.simpmusic.handoff.PlaybackHandoffSettings.playbackHandoffEnabledFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "PlaybackHandoff"
private const val PEER_STALE_MS = 18_000L

expect class PlaybackHandoffTransport() {
    fun defaultDeviceName(): String

    fun platformLabel(): String

    fun startServer(
        preferredPort: Int,
        beaconProvider: () -> HandoffBeacon,
        onGetState: (pauseLocal: Boolean) -> HandoffPlaybackSnapshot?,
        onReceiveTransfer: (HandoffPlaybackSnapshot) -> Unit,
        onPeerDiscovered: (HandoffPeer) -> Unit,
        onReceiveCustomPost: (path: String, body: String) -> Boolean = { _, _ -> false },
    ): Int

    fun broadcastDiscoverPing()

    suspend fun sendSnapshot(
        peer: HandoffPeer,
        snapshot: HandoffPlaybackSnapshot,
    ): Boolean

    suspend fun sendCustomPost(
        peer: HandoffPeer,
        path: String,
        body: String,
    ): Boolean

    suspend fun pullSnapshot(
        peer: HandoffPeer,
        pauseRemote: Boolean = true,
    ): HandoffPlaybackSnapshot?

    fun stopServer()
}

/**
 * Coordinates one-tap Phone <-> PC Playback Handoff over the local Wi-Fi network.
 */
@OptIn(ExperimentalTime::class)
class PlaybackHandoffManager(
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val dataStoreManager: DataStoreManager,
    private val transport: PlaybackHandoffTransport = PlaybackHandoffTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val localDeviceId: String = "sm-${Random.nextLong().toULong().toString(16)}"

    private val _peers = MutableStateFlow<List<HandoffPeer>>(emptyList())
    val peers: StateFlow<List<HandoffPeer>> = _peers.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow<String?>(null)
    val lastStatusMessage: StateFlow<String?> = _lastStatusMessage.asStateFlow()

    @Volatile
    private var cachedDeviceName: String = transport.defaultDeviceName()

    @Volatile
    private var activeRoomCodeProvider: () -> String? = { null }

    @Volatile
    private var wholeHomeGroupIdProvider: () -> String? = { null }

    @Volatile
    private var customPostHandlers: Map<String, (String) -> Boolean> = emptyMap()

    fun currentDeviceName(): String = cachedDeviceName

    fun registerBeaconProviders(
        roomCodeProvider: (() -> String?)? = null,
        groupIdProvider: (() -> String?)? = null,
    ) {
        if (roomCodeProvider != null) activeRoomCodeProvider = roomCodeProvider
        if (groupIdProvider != null) wholeHomeGroupIdProvider = groupIdProvider
    }

    fun registerCustomPostHandler(
        pathPrefix: String,
        handler: (String) -> Boolean,
    ) {
        customPostHandlers = customPostHandlers + (pathPrefix to handler)
    }

    suspend fun sendCustomPostToPeer(
        peer: HandoffPeer,
        path: String,
        body: String,
    ): Boolean = runCatching { transport.sendCustomPost(peer, path, body) }.getOrDefault(false)

    suspend fun sendRemoteSpeakerCommand(
        peer: HandoffPeer,
        command: RemoteSpeakerCommand,
    ): Boolean {
        val body = PlaybackHandoffWire.json.encodeToString(RemoteSpeakerCommand.serializer(), command)
        val ok = sendCustomPostToPeer(peer, PlaybackHandoffWire.REMOTE_SPEAKER_PATH, body)
        if (ok) {
            delay(250L)
            runCatching { transport.broadcastDiscoverPing() }
        }
        return ok
    }

    private var lifecycleJob: Job? = null
    private var pruneJob: Job? = null

    fun start() {
        if (lifecycleJob?.isActive == true) return
        lifecycleJob =
            scope.launch {
                cachedDeviceName = runCatching {
                    dataStoreManager.effectiveHandoffDeviceName(transport.defaultDeviceName())
                }.getOrDefault(transport.defaultDeviceName())

                dataStoreManager
                    .playbackHandoffEnabledFlow()
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (enabled) {
                            startTransportInternal()
                        } else {
                            stopTransportInternal()
                        }
                    }
            }
    }

    fun refreshPeers() {
        scope.launch {
            cachedDeviceName = runCatching {
                dataStoreManager.effectiveHandoffDeviceName(transport.defaultDeviceName())
            }.getOrDefault(transport.defaultDeviceName())
            if (_boundPort.value <= 0) {
                startTransportInternal()
            }
            pruneStalePeers()
            runCatching { transport.broadcastDiscoverPing() }
        }
    }

    fun buildCurrentSnapshot(): HandoffPlaybackSnapshot? {
        val queue = mediaPlayerHandler.queueData.value?.data
        val tracks = queue?.listTracks.orEmpty()
        val nowTrack = mediaPlayerHandler.nowPlayingState.value.track
        val resolvedTracks =
            if (tracks.isNotEmpty()) {
                tracks.map { it.toHandoffTrack() }
            } else if (nowTrack != null) {
                listOf(nowTrack.toHandoffTrack())
            } else {
                return null
            }
        val idx = mediaPlayerHandler.currentOrderIndex().coerceIn(0, resolvedTracks.lastIndex)
        val control = mediaPlayerHandler.controlState.value
        val posMs = mediaPlayerHandler.player.currentPosition.coerceAtLeast(0L)
        return HandoffPlaybackSnapshot(
            senderDeviceId = localDeviceId,
            senderDeviceName = cachedDeviceName,
            tracks = resolvedTracks,
            currentIndex = idx,
            positionMs = posMs,
            isPlaying = control.isPlaying,
            isShuffle = control.isShuffle,
            repeatMode = mediaPlayerHandler.player.repeatMode,
            playlistId = queue?.playlistId,
            playlistName = queue?.playlistName,
            playlistTypeName = queue?.playlistType?.name,
        )
    }

    suspend fun sendPlaybackTo(peer: HandoffPeer): Result<String> {
        val snapshot = buildCurrentSnapshot()
            ?: return Result.failure(IllegalStateException("Nothing is playing to hand off."))
        val sent = runCatching { transport.sendSnapshot(peer, snapshot) }.getOrDefault(false)
        if (!sent) {
            return Result.failure(IllegalStateException("Could not reach ${peer.deviceName} on Wi-Fi."))
        }
        // Pause locally after confirming the target accepted playback.
        if (mediaPlayerHandler.controlState.value.isPlaying) {
            runCatching { mediaPlayerHandler.player.pause() }
        }
        val msg = "Handed off playback to ${peer.deviceName}"
        _lastStatusMessage.value = msg
        return Result.success(msg)
    }

    suspend fun pullPlaybackFrom(peer: HandoffPeer): Result<String> {
        val snapshot = runCatching { transport.pullSnapshot(peer, pauseRemote = true) }.getOrNull()
            ?: return Result.failure(IllegalStateException("${peer.deviceName} has nothing playing or is unreachable."))
        applyIncomingSnapshot(snapshot)
        val msg = "Continued playback from ${peer.deviceName}"
        _lastStatusMessage.value = msg
        return Result.success(msg)
    }

    fun applyIncomingSnapshot(snapshot: HandoffPlaybackSnapshot) {
        if (snapshot.tracks.isEmpty()) return
        scope.launch {
            try {
                val tracks = snapshot.tracks.map { it.toTrack() }
                val safeIndex = snapshot.currentIndex.coerceIn(0, tracks.lastIndex)
                val targetTrack = tracks[safeIndex]
                val playlistType =
                    snapshot.playlistTypeName?.let { name ->
                        runCatching { PlaylistType.valueOf(name) }.getOrNull()
                    } ?: PlaylistType.PLAYLIST

                mediaPlayerHandler.reset()
                mediaPlayerHandler.setShuffle(snapshot.isShuffle)
                mediaPlayerHandler.player.repeatMode = snapshot.repeatMode
                mediaPlayerHandler.setQueueData(
                    QueueData.Data(
                        listTracks = tracks.toCollection(ArrayList()),
                        firstPlayedTrack = targetTrack,
                        playlistId = snapshot.playlistId ?: "HANDOFF_${snapshot.senderDeviceId}",
                        playlistName = snapshot.playlistName ?: "From ${snapshot.senderDeviceName}",
                        playlistType = playlistType,
                        continuation = null,
                    ),
                )
                mediaPlayerHandler.loadMediaItem(
                    anyTrack = targetTrack,
                    type = Config.PLAYLIST_CLICK,
                    index = safeIndex,
                )
                // Wait briefly for the player to load the track, then seek to exact handoff timestamp.
                if (snapshot.positionMs > 1_000L) {
                    delay(450L)
                    mediaPlayerHandler.player.seekTo(snapshot.positionMs)
                }
                if (!snapshot.isPlaying) {
                    delay(150L)
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                }
                _lastStatusMessage.value = "Playback received from ${snapshot.senderDeviceName}"
                Logger.i(TAG, "Applied handoff from ${snapshot.senderDeviceName}: ${targetTrack.title} @ ${snapshot.positionMs}ms")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply incoming handoff: ${e.message}", e)
            }
        }
    }

    private fun startTransportInternal() {
        val port =
            runCatching {
                transport.startServer(
                    preferredPort = PlaybackHandoffWire.DEFAULT_HTTP_PORT,
                    beaconProvider = { buildLocalBeacon() },
                    onGetState = { pauseLocal ->
                        val snap = buildCurrentSnapshot()
                        if (pauseLocal && snap != null && mediaPlayerHandler.controlState.value.isPlaying) {
                            runCatching { mediaPlayerHandler.player.pause() }
                        }
                        snap
                    },
                    onReceiveTransfer = { snapshot ->
                        applyIncomingSnapshot(snapshot)
                    },
                    onPeerDiscovered = { peer ->
                        if (peer.deviceId == localDeviceId) return@startServer
                        val stamped = peer.copy(lastSeenMs = Clock.System.now().toEpochMilliseconds())
                        _peers.update { current ->
                            (current.filterNot { it.deviceId == stamped.deviceId } + stamped)
                                .sortedBy { it.deviceName.lowercase() }
                        }
                    },
                    onReceiveCustomPost = { path, body ->
                        if (path.startsWith(PlaybackHandoffWire.REMOTE_SPEAKER_PATH)) {
                            handleRemoteSpeakerPost(body)
                        } else {
                            val handler =
                                customPostHandlers.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.value
                            handler?.invoke(body) ?: false
                        }
                    },
                )
            }.getOrDefault(0)
        _boundPort.value = port
        if (port > 0) {
            pruneJob?.cancel()
            pruneJob =
                scope.launch {
                    while (true) {
                        delay(5_000L)
                        pruneStalePeers()
                    }
                }
            runCatching { transport.broadcastDiscoverPing() }
        }
    }

    private fun stopTransportInternal() {
        pruneJob?.cancel()
        pruneJob = null
        runCatching { transport.stopServer() }
        _boundPort.value = 0
        _peers.value = emptyList()
    }

    private fun handleRemoteSpeakerPost(rawBody: String): Boolean {
        val cmd =
            runCatching {
                PlaybackHandoffWire.json.decodeFromString(RemoteSpeakerCommand.serializer(), rawBody)
            }.getOrNull() ?: return false
        val player = mediaPlayerHandler.player
        if (!player.multiOutputSupported) return false
        when (cmd.action) {
            "toggle" -> player.toggleSecondaryAudioOutput(cmd.id)
            "select" -> player.selectAudioOutput(cmd.id)
            "delay" -> player.setAudioOutputDelayMs(cmd.id, cmd.delayMs)
            else -> return false
        }
        scope.launch {
            delay(200L)
            runCatching { transport.broadcastDiscoverPing() }
        }
        return true
    }

    private fun buildLocalBeacon(): HandoffBeacon {
        val np = mediaPlayerHandler.nowPlayingState.value
        val title = np.songEntity?.title ?: np.track?.title ?: ""
        val artist = np.songEntity?.artistName?.joinToString(", ") ?: np.track?.artists?.joinToString(", ") { it.name } ?: ""
        val player = mediaPlayerHandler.player
        val remoteSpeakers =
            if (player.multiOutputSupported) {
                val secIds = player.secondaryAudioOutputIds.value
                val delays = player.audioOutputDelaysMs.value
                player.audioOutputs.value
                    .filter { it.id != "auto" && it.name.isNotBlank() }
                    .take(10)
                    .map { out ->
                        RemoteSpeakerOutput(
                            id = out.id,
                            name = out.name,
                            isActive = out.isActive,
                            isSecondary = out.id in secIds,
                            delayMs = delays[out.id] ?: 0,
                        )
                    }
            } else {
                emptyList()
            }
        return HandoffBeacon(
            deviceId = localDeviceId,
            deviceName = cachedDeviceName,
            platform = transport.platformLabel(),
            httpPort = _boundPort.value,
            nowPlayingTitle = title,
            nowPlayingArtist = artist,
            isPlaying = mediaPlayerHandler.controlState.value.isPlaying,
            activeRoomCode = runCatching { activeRoomCodeProvider() }.getOrNull(),
            wholeHomeGroupId = runCatching { wholeHomeGroupIdProvider() }.getOrNull(),
            speakers = remoteSpeakers,
        )
    }

    private fun pruneStalePeers() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - PEER_STALE_MS
        _peers.update { list -> list.filter { it.lastSeenMs >= cutoff } }
    }

    fun stop() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        stopTransportInternal()
    }
}
