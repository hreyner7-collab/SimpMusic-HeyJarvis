package com.maxrave.simpmusic.smarthome

import com.maxrave.common.Config
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.handoff.HandoffPeer
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "WholeHomeAudio"

/**
 * Coordinates synchronized Whole-Home Multi-Room Audio across SimpMusic devices on the same Wi-Fi
 * network (phones, tablets, and desktop PCs) using scheduled epoch-aligned starts and live transport
 * mirroring over the shared LAN peer transport.
 */
@OptIn(ExperimentalTime::class)
class WholeHomeAudioManager(
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val handoffManager: PlaybackHandoffManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(WholeHomeAudioState())
    val state: StateFlow<WholeHomeAudioState> = _state.asStateFlow()

    val availableRooms: StateFlow<List<HandoffPeer>> = handoffManager.peers

    private var lifecycleJob: Job? = null
    private var leaderMirrorJob: Job? = null

    fun start() {
        if (lifecycleJob?.isActive == true) return
        handoffManager.registerBeaconProviders(
            groupIdProvider = { _state.value.groupId },
        )
        handoffManager.registerCustomPostHandler(WholeHomeSyncMath.HTTP_PATH) { rawBody ->
            val command = WholeHomeSyncMath.decodeCommand(rawBody) ?: return@registerCustomPostHandler false
            handleIncomingCommand(command)
            true
        }
        lifecycleJob =
            scope.launch {
                // Keep groupedPeerIds trimmed to valid peers only when disbanding manually;
                // transient UDP misses do not drop rooms from an active group.
            }
    }

    fun togglePeerInGroup(peer: HandoffPeer) {
        _state.update { current ->
            val nextPeers =
                if (peer.deviceId in current.groupedPeerIds) {
                    current.groupedPeerIds - peer.deviceId
                } else {
                    current.groupedPeerIds + peer.deviceId
                }
            current.copy(groupedPeerIds = nextPeers)
        }
    }

    /**
     * Starts or re-syncs Whole-Home Multi-Room Audio across this device and all selected Wi-Fi peers
     * (or all currently discovered Wi-Fi peers if none were individually toggled yet).
     */
    suspend fun startWholeHomePlayback(selectedPeerIds: Set<String> = _state.value.groupedPeerIds): Result<String> {
        val peers = handoffManager.peers.value
        val targetPeers =
            if (selectedPeerIds.isNotEmpty()) {
                peers.filter { it.deviceId in selectedPeerIds }
            } else {
                peers
            }
        if (targetPeers.isEmpty()) {
            return Result.failure(IllegalStateException("No other SimpMusic devices found on Wi-Fi to group."))
        }
        val snapshot =
            handoffManager.buildCurrentSnapshot()
                ?: return Result.failure(IllegalStateException("Start playing a song first to launch Whole-Home Audio."))

        val groupId = _state.value.groupId ?: "wh-${Random.nextInt(100000, 999999)}"
        val startEpoch = clockNowMs() + WholeHomeSyncMath.DEFAULT_LEAD_TIME_MS
        val cmd =
            WholeHomeGroupCommand(
                type = WholeHomeCommandType.SYNC_PLAY_AT,
                groupId = groupId,
                groupName = "Whole Home (${targetPeers.size + 1} rooms)",
                leaderDeviceId = handoffManager.localDeviceId,
                leaderDeviceName = handoffManager.currentDeviceName(),
                snapshot = snapshot.copy(isPlaying = true),
                targetStartEpochMs = startEpoch,
                positionMs = snapshot.positionMs,
                volume = _state.value.masterVolume,
            )

        val body = WholeHomeSyncMath.encodeCommand(cmd)
        var reached = 0
        targetPeers.forEach { peer ->
            if (handoffManager.sendCustomPostToPeer(peer, WholeHomeSyncMath.HTTP_PATH, body)) {
                reached++
            }
        }
        if (reached == 0) {
            return Result.failure(IllegalStateException("Could not reach any selected Wi-Fi room."))
        }

        // Suppress crossfade on leader while multi-room sync is active so track transitions stay locked.
        withContext(Dispatchers.Main) {
            runCatching {
                mediaPlayerHandler.player.crossfadeSuppressed = true
                if (!mediaPlayerHandler.player.playWhenReady) {
                    mediaPlayerHandler.player.play()
                }
            }
        }

        val msg = "Whole-Home Audio active on ${reached + 1} rooms"
        _state.value =
            WholeHomeAudioState(
                role = WholeHomeRole.LEADER,
                groupId = groupId,
                groupName = "Whole Home (${reached + 1} rooms)",
                leaderDeviceName = handoffManager.currentDeviceName(),
                groupedPeerIds = targetPeers.map { it.deviceId }.toSet(),
                masterVolume = mediaPlayerHandler.controlState.value.volume.coerceIn(0f, 1f),
                statusMessage = msg,
            )
        startLeaderMirroring()
        return Result.success(msg)
    }

    suspend fun setMasterVolume(volume: Float): Result<Float> {
        val clamped = volume.coerceIn(0f, 1f)
        val current = _state.value
        withContext(Dispatchers.Main) {
            runCatching { mediaPlayerHandler.player.volume = clamped }
        }
        _state.update { it.copy(masterVolume = clamped) }
        if (current.role == WholeHomeRole.LEADER && current.groupId != null) {
            broadcastToGroupPeers(
                WholeHomeGroupCommand(
                    type = WholeHomeCommandType.SET_VOLUME,
                    groupId = current.groupId,
                    leaderDeviceId = handoffManager.localDeviceId,
                    leaderDeviceName = handoffManager.currentDeviceName(),
                    volume = clamped,
                ),
            )
        }
        return Result.success(clamped)
    }

    suspend fun disbandOrLeaveGroup() {
        val current = _state.value
        leaderMirrorJob?.cancel()
        leaderMirrorJob = null
        if (current.role == WholeHomeRole.LEADER && current.groupId != null) {
            broadcastToGroupPeers(
                WholeHomeGroupCommand(
                    type = WholeHomeCommandType.LEAVE_GROUP,
                    groupId = current.groupId,
                    leaderDeviceId = handoffManager.localDeviceId,
                    leaderDeviceName = handoffManager.currentDeviceName(),
                ),
            )
        }
        withContext(Dispatchers.Main) {
            runCatching { mediaPlayerHandler.player.crossfadeSuppressed = false }
        }
        _state.value = WholeHomeAudioState(statusMessage = "Whole-Home Audio stopped")
    }

    internal fun handleIncomingCommand(command: WholeHomeGroupCommand) {
        if (command.leaderDeviceId == handoffManager.localDeviceId) return
        scope.launch {
            try {
                when (command.type) {
                    WholeHomeCommandType.SYNC_PLAY_AT -> {
                        val snap = command.snapshot ?: return@launch
                        if (snap.tracks.isEmpty()) return@launch
                        _state.value =
                            WholeHomeAudioState(
                                role = WholeHomeRole.FOLLOWER,
                                groupId = command.groupId,
                                groupName = command.groupName,
                                leaderDeviceName = command.leaderDeviceName,
                                masterVolume = command.volume ?: _state.value.masterVolume,
                                statusMessage = "Synced with ${command.leaderDeviceName}",
                            )
                        val plan =
                            WholeHomeSyncMath.computeStartPlan(
                                snapshotPositionMs = snap.positionMs,
                                targetStartEpochMs = command.targetStartEpochMs,
                                nowEpochMs = clockNowMs(),
                                isPlaying = snap.isPlaying,
                            )
                        val tracks = snap.tracks.map { it.toTrack() }
                        val safeIdx = snap.currentIndex.coerceIn(0, tracks.lastIndex)
                        val targetTrack = tracks[safeIdx]
                        val playlistType =
                            snap.playlistTypeName?.let { runCatching { PlaylistType.valueOf(it) }.getOrNull() }
                                ?: PlaylistType.PLAYLIST

                        withContext(Dispatchers.Main) {
                            mediaPlayerHandler.player.crossfadeSuppressed = true
                            command.volume?.let { mediaPlayerHandler.player.volume = it.coerceIn(0f, 1f) }
                        }
                        mediaPlayerHandler.reset()
                        mediaPlayerHandler.setShuffle(snap.isShuffle)
                        mediaPlayerHandler.setQueueData(
                            QueueData.Data(
                                listTracks = tracks.toCollection(ArrayList()),
                                firstPlayedTrack = targetTrack,
                                playlistId = snap.playlistId ?: "WHOLEHOME_${command.groupId}",
                                playlistName = command.groupName,
                                playlistType = playlistType,
                                continuation = null,
                            ),
                        )
                        if (plan.waitDelayMs > 0L) {
                            delay(plan.waitDelayMs)
                        }
                        mediaPlayerHandler.loadMediaItem(
                            anyTrack = targetTrack,
                            type = Config.PLAYLIST_CLICK,
                            index = safeIdx,
                        )
                        if (plan.targetPositionMs > 500L) {
                            delay(350L)
                            withContext(Dispatchers.Main) {
                                mediaPlayerHandler.player.seekTo(plan.targetPositionMs)
                            }
                        }
                        Logger.i(TAG, "Whole-Home follower synced to ${command.leaderDeviceName}: ${targetTrack.title}")
                    }

                    WholeHomeCommandType.PAUSE -> {
                        if (_state.value.groupId != command.groupId) return@launch
                        withContext(Dispatchers.Main) {
                            runCatching { mediaPlayerHandler.player.pause() }
                        }
                    }

                    WholeHomeCommandType.SEEK -> {
                        if (_state.value.groupId != command.groupId) return@launch
                        val plan =
                            WholeHomeSyncMath.computeStartPlan(
                                snapshotPositionMs = command.positionMs,
                                targetStartEpochMs = command.targetStartEpochMs,
                                nowEpochMs = clockNowMs(),
                                isPlaying = true,
                            )
                        withContext(Dispatchers.Main) {
                            runCatching { mediaPlayerHandler.player.seekTo(plan.targetPositionMs) }
                        }
                    }

                    WholeHomeCommandType.SET_VOLUME -> {
                        if (_state.value.groupId != command.groupId) return@launch
                        val vol = command.volume?.coerceIn(0f, 1f) ?: return@launch
                        _state.update { it.copy(masterVolume = vol) }
                        withContext(Dispatchers.Main) {
                            runCatching { mediaPlayerHandler.player.volume = vol }
                        }
                    }

                    WholeHomeCommandType.LEAVE_GROUP -> {
                        if (_state.value.groupId != command.groupId) return@launch
                        withContext(Dispatchers.Main) {
                            runCatching {
                                mediaPlayerHandler.player.crossfadeSuppressed = false
                                mediaPlayerHandler.player.pause()
                            }
                        }
                        _state.value = WholeHomeAudioState(statusMessage = "${command.leaderDeviceName} ended Whole-Home Audio")
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Whole-Home command error: ${e.message}")
            }
        }
    }

    private fun startLeaderMirroring() {
        leaderMirrorJob?.cancel()
        leaderMirrorJob =
            scope.launch {
                launch {
                    mediaPlayerHandler.controlState
                        .map { it.isPlaying }
                        .distinctUntilChanged()
                        .collect { playing ->
                            val st = _state.value
                            if (st.role != WholeHomeRole.LEADER || st.groupId == null) return@collect
                            if (!playing) {
                                broadcastToGroupPeers(
                                    WholeHomeGroupCommand(
                                        type = WholeHomeCommandType.PAUSE,
                                        groupId = st.groupId,
                                        leaderDeviceId = handoffManager.localDeviceId,
                                        leaderDeviceName = handoffManager.currentDeviceName(),
                                    ),
                                )
                            } else {
                                val snap = handoffManager.buildCurrentSnapshot() ?: return@collect
                                broadcastToGroupPeers(
                                    WholeHomeGroupCommand(
                                        type = WholeHomeCommandType.SYNC_PLAY_AT,
                                        groupId = st.groupId,
                                        groupName = st.groupName,
                                        leaderDeviceId = handoffManager.localDeviceId,
                                        leaderDeviceName = handoffManager.currentDeviceName(),
                                        snapshot = snap.copy(isPlaying = true),
                                        targetStartEpochMs = clockNowMs() + 400L,
                                        positionMs = snap.positionMs,
                                    ),
                                )
                            }
                        }
                }
                launch {
                    mediaPlayerHandler.nowPlayingState
                        .distinctUntilChangedBy { it.songEntity?.videoId ?: it.track?.videoId }
                        .collect {
                            val st = _state.value
                            if (st.role != WholeHomeRole.LEADER || st.groupId == null) return@collect
                            val snap = handoffManager.buildCurrentSnapshot() ?: return@collect
                            broadcastToGroupPeers(
                                WholeHomeGroupCommand(
                                    type = WholeHomeCommandType.SYNC_PLAY_AT,
                                    groupId = st.groupId,
                                    groupName = st.groupName,
                                    leaderDeviceId = handoffManager.localDeviceId,
                                    leaderDeviceName = handoffManager.currentDeviceName(),
                                    snapshot = snap,
                                    targetStartEpochMs = clockNowMs() + 500L,
                                    positionMs = snap.positionMs,
                                ),
                            )
                        }
                }
            }
    }

    private suspend fun broadcastToGroupPeers(command: WholeHomeGroupCommand) {
        val ids = _state.value.groupedPeerIds
        if (ids.isEmpty()) return
        val targets = handoffManager.peers.value.filter { it.deviceId in ids }
        if (targets.isEmpty()) return
        val body = WholeHomeSyncMath.encodeCommand(command)
        targets.forEach { peer ->
            handoffManager.sendCustomPostToPeer(peer, WholeHomeSyncMath.HTTP_PATH, body)
        }
    }

    fun stop() {
        leaderMirrorJob?.cancel()
        leaderMirrorJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
    }
}
