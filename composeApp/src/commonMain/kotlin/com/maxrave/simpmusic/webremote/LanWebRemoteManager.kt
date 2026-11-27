package com.maxrave.simpmusic.webremote

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

private const val TAG = "LanWebRemoteManager"
private const val MAX_QUEUE_ITEMS = 60

/**
 * Coordinates the PIN-protected LAN Web Remote HTTP server and dispatches remote commands safely
 * onto [MediaPlayerHandler] without blocking HTTP worker threads.
 */
class LanWebRemoteManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val server: PlatformWebRemoteServer = PlatformWebRemoteServer(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    val isSupported: Boolean = server.isSupportedOnCurrentPlatform

    private val _config = MutableStateFlow(LanWebRemoteConfig())
    val config: StateFlow<LanWebRemoteConfig> = _config.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Mirror playback timeline from StateFlows so HTTP workers never call ExoPlayer off the main thread.
    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = 0L

    private var started = false
    private var stateObserverJob: Job? = null

    internal val router: LanWebRemoteRouter =
        LanWebRemoteRouter(
            pinProvider = { _config.value.pin },
            htmlProvider = { LanWebRemoteHtml.renderPage("SimpMusic") },
            snapshotProvider = { buildPlaybackSnapshot() },
            onCommand = { cmd -> executeCommand(cmd) },
        )

    fun start() {
        if (started) return
        started = true
        observePlaybackTimeline()
        scope.launch {
            val raw =
                runCatching { dataStoreManager.getString(LanWebRemoteStoreCodec.DATASTORE_KEY).firstOrNull() }
                    .getOrNull()
            val loaded = LanWebRemoteStoreCodec.decode(raw)
            _config.value = loaded
            // Persist auto-generated PIN on first run so it stays stable across launches.
            if (raw.isNullOrBlank()) {
                persistConfig(loaded)
            }
            applyServerState(loaded)
        }
    }

    fun stop() {
        started = false
        stateObserverJob?.cancel()
        stateObserverJob = null
        stopServerInternal()
    }

    fun setEnabled(enabled: Boolean) {
        updateConfig(_config.value.copy(enabled = enabled))
    }

    fun regeneratePin() {
        val currentPin = _config.value.pin
        var freshPin = LanWebRemoteSecurity.generatePin()
        if (freshPin == currentPin) {
            freshPin = LanWebRemoteSecurity.generatePin()
        }
        updateConfig(_config.value.copy(pin = freshPin))
    }

    fun setCustomPin(pin: String): Boolean {
        val trimmed = pin.trim()
        if (!LanWebRemoteSecurity.isValidPinFormat(trimmed)) return false
        updateConfig(_config.value.copy(pin = trimmed))
        return true
    }

    fun setPort(port: Int) {
        val clamped = port.coerceIn(LanWebRemoteConfig.MIN_PORT, LanWebRemoteConfig.MAX_PORT)
        if (clamped == _config.value.port) return
        updateConfig(_config.value.copy(port = clamped))
    }

    fun buildPlaybackSnapshot(): RemotePlaybackSnapshot {
        val np = mediaPlayerHandler.nowPlayingState.value
        val control = mediaPlayerHandler.controlState.value
        val queueTracks = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        val currentIdx = mediaPlayerHandler.currentSongIndex.value

        val title =
            np.songEntity?.title?.takeIf { it.isNotBlank() }
                ?: np.track?.title?.takeIf { it.isNotBlank() }
                ?: np.mediaItem.metadata.title.orEmpty()
        val artist =
            np.songEntity?.artistName?.joinToString(", ")?.takeIf { it.isNotBlank() }
                ?: np.track?.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }
                ?: np.mediaItem.metadata.artist.orEmpty()
        val thumbnailUrl =
            np.songEntity?.thumbnails?.takeIf { it.isNotBlank() }
                ?: np.track?.thumbnails?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
                ?: np.mediaItem.metadata.artworkUri.orEmpty()

        val fallbackDurMs =
            ((np.songEntity?.durationSeconds ?: np.track?.durationSeconds ?: 0).coerceAtLeast(0)) * 1_000L
        val durationMs = cachedDurationMs.takeIf { it > 0L } ?: fallbackDurMs
        val positionMs =
            if (durationMs > 0L) {
                cachedPositionMs.coerceIn(0L, durationMs)
            } else {
                cachedPositionMs.coerceAtLeast(0L)
            }

        val repeatStr =
            when (control.repeatState) {
                RepeatState.None -> RemotePlaybackSnapshot.REPEAT_OFF
                RepeatState.All -> RemotePlaybackSnapshot.REPEAT_ALL
                RepeatState.One -> RemotePlaybackSnapshot.REPEAT_ONE
            }

        val queueItems =
            queueTracks
                .take(MAX_QUEUE_ITEMS)
                .mapIndexed { idx, track ->
                    RemoteQueueItem(
                        index = idx,
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artists.orEmpty().joinToString(", ") { it.name },
                        thumbnailUrl = track.thumbnails?.lastOrNull()?.url.orEmpty(),
                        durationSeconds = track.durationSeconds ?: 0,
                        isCurrent = idx == currentIdx,
                    )
                }

        return RemotePlaybackSnapshot(
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            isPlaying = control.isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            shuffle = control.isShuffle,
            repeat = repeatStr,
            volume = control.volume.coerceIn(0f, 1f),
            isLiked = control.isLiked,
            queue = queueItems,
        )
    }

    internal fun executeCommand(command: ParsedRemoteCommand): Result<String> {
        if (command is ParsedRemoteCommand.JumpToQueueIndex) {
            val queueSize = mediaPlayerHandler.queueData.value?.data?.listTracks?.size ?: 0
            if (queueSize > 0 && command.index !in 0 until queueSize) {
                return Result.failure(IllegalArgumentException("Queue index ${command.index} out of bounds"))
            }
        }

        if (command is ParsedRemoteCommand.SeekToMs) {
            cachedPositionMs = command.positionMs.coerceAtLeast(0L)
        }

        scope.launch(mainDispatcher) {
            runCatching {
                when (command) {
                    ParsedRemoteCommand.PlayPause ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)

                    ParsedRemoteCommand.Play -> {
                        if (!mediaPlayerHandler.controlState.value.isPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        }
                    }

                    ParsedRemoteCommand.Pause -> {
                        if (mediaPlayerHandler.controlState.value.isPlaying) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        }
                    }

                    ParsedRemoteCommand.Next ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)

                    ParsedRemoteCommand.Previous ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)

                    ParsedRemoteCommand.ToggleShuffle ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)

                    ParsedRemoteCommand.CycleRepeat ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.Repeat)

                    ParsedRemoteCommand.ToggleLike ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.ToggleLike)

                    is ParsedRemoteCommand.SeekToMs ->
                        mediaPlayerHandler.player.seekTo(command.positionMs.coerceAtLeast(0L))

                    is ParsedRemoteCommand.SetVolume ->
                        mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateVolume(command.volume.coerceIn(0f, 1f)))

                    is ParsedRemoteCommand.JumpToQueueIndex ->
                        mediaPlayerHandler.playMediaItemInMediaSource(command.index)
                }
            }.onFailure {
                Logger.w(TAG, "Remote command $command failed: ${it.message}")
            }
        }
        return Result.success("Dispatched ${command::class.simpleName}")
    }

    private fun observePlaybackTimeline() {
        stateObserverJob?.cancel()
        stateObserverJob =
            scope.launch {
                mediaPlayerHandler.simpleMediaState.collect { state ->
                    when (state) {
                        is SimpleMediaState.Ready -> {
                            if (state.duration > 0L) cachedDurationMs = state.duration
                        }

                        is SimpleMediaState.Loading -> {
                            if (state.duration > 0L) cachedDurationMs = state.duration
                        }

                        is SimpleMediaState.Progress -> {
                            cachedPositionMs = state.progress.coerceAtLeast(0L)
                        }

                        is SimpleMediaState.Buffering -> {
                            cachedPositionMs = state.position.coerceAtLeast(0L)
                        }

                        SimpleMediaState.Ended -> {
                            cachedPositionMs = cachedDurationMs
                        }

                        SimpleMediaState.Initial -> {
                            cachedPositionMs = 0L
                            cachedDurationMs = 0L
                        }
                    }
                }
            }
    }

    private fun updateConfig(next: LanWebRemoteConfig) {
        val normalized = next.normalized()
        val restartNeeded =
            normalized.enabled != _config.value.enabled ||
                normalized.port != _config.value.port ||
                (normalized.enabled && !_isRunning.value)
        _config.value = normalized
        scope.launch {
            persistConfig(normalized)
            if (restartNeeded) {
                applyServerState(normalized)
            }
        }
    }

    private suspend fun persistConfig(cfg: LanWebRemoteConfig) {
        runCatching {
            dataStoreManager.putString(LanWebRemoteStoreCodec.DATASTORE_KEY, LanWebRemoteStoreCodec.encode(cfg))
        }.onFailure {
            Logger.w(TAG, "Failed to persist LAN Web Remote config: ${it.message}")
        }
    }

    private fun applyServerState(cfg: LanWebRemoteConfig) {
        if (!isSupported || !cfg.enabled) {
            stopServerInternal()
            return
        }
        val handle =
            runCatching {
                server.start(cfg.port) { method, path, headers, body ->
                    router.route(method, path, headers, body)
                }
            }.onFailure {
                Logger.e(TAG, "Failed to start LAN Web Remote server: ${it.message}", it)
                _lastError.value = it.message ?: "Failed to start server"
            }.getOrNull()

        if (handle != null) {
            _isRunning.value = true
            _boundPort.value = handle.boundPort
            _serverUrl.value = handle.displayUrl
            _lastError.value = null
        } else {
            _isRunning.value = false
            _boundPort.value = 0
            _serverUrl.value = null
            if (_lastError.value == null) {
                _lastError.value = "Could not bind LAN HTTP server on port ${cfg.port}"
            }
        }
    }

    private fun stopServerInternal() {
        runCatching { server.stop() }
        _isRunning.value = false
        _boundPort.value = 0
        _serverUrl.value = null
    }
}
