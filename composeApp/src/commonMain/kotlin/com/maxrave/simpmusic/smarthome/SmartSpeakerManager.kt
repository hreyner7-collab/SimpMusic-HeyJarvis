package com.maxrave.simpmusic.smarthome

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SmartSpeakerManager"

expect class SmartSpeakerTransport() {
    suspend fun discoverSpeakers(timeoutMs: Long = 1_800L): List<SmartSpeakerDevice>

    suspend fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        action: String,
        envelopeXml: String,
    ): Result<String>
}

/**
 * Discovers and controls smart home speakers & AV receivers over standard DLNA / UPnP
 * (Sonos, Bose SoundTouch, Denon/Marantz HEOS, Yamaha MusicCast, WiiM, Smart TVs, AV receivers).
 */
class SmartSpeakerManager(
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val transport: SmartSpeakerTransport = SmartSpeakerTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _speakers = MutableStateFlow<List<SmartSpeakerDevice>>(emptyList())
    val speakers: StateFlow<List<SmartSpeakerDevice>> = _speakers.asStateFlow()

    private val _state = MutableStateFlow(SmartSpeakerPlaybackState())
    val state: StateFlow<SmartSpeakerPlaybackState> = _state.asStateFlow()

    fun scanForSpeakers() {
        if (_state.value.isScanning) return
        scope.launch {
            _state.update { it.copy(isScanning = true, statusMessage = "Scanning Wi-Fi for smart speakers…") }
            val found =
                runCatching { transport.discoverSpeakers() }
                    .onFailure { Logger.w(TAG, "Smart speaker scan failed: ${it.message}") }
                    .getOrDefault(emptyList())
                    .distinctBy { it.udn }
                    .sortedBy { it.friendlyName.lowercase() }
            _speakers.value = found
            _state.update {
                it.copy(
                    isScanning = false,
                    statusMessage =
                        if (found.isEmpty()) {
                            "No DLNA/UPnP smart speakers found on Wi-Fi"
                        } else {
                            "Found ${found.size} smart speaker(s)"
                        },
                )
            }
        }
    }

    suspend fun castCurrentTrackTo(speaker: SmartSpeakerDevice): Result<String> =
        withContext(Dispatchers.IO) {
            val np = mediaPlayerHandler.nowPlayingState.value
            val song = np.songEntity
            val track = np.track
            val videoId =
                song?.videoId?.takeIf { it.isNotBlank() }
                    ?: track?.videoId?.takeIf { it.isNotBlank() }
                    ?: np.mediaItem.mediaId.takeIf { it.isNotBlank() }
                    ?: return@withContext Result.failure(IllegalStateException("Play a song first to cast to ${speaker.friendlyName}."))
            val title = song?.title ?: track?.title ?: np.mediaItem.metadata.title ?: "SimpMusic"
            val artist =
                song?.artistName?.joinToString(", ")
                    ?: track?.artists?.joinToString(", ") { it.name }
                    ?: np.mediaItem.metadata.artist.orEmpty()
            val album = song?.albumName ?: track?.album?.name ?: np.mediaItem.metadata.albumTitle.orEmpty()
            val artwork = song?.thumbnails ?: track?.thumbnails?.lastOrNull()?.url ?: np.mediaItem.metadata.artworkUri.orEmpty()

            _state.update { it.copy(isBusy = true, statusMessage = "Connecting to ${speaker.friendlyName}…") }
            try {
                val streamUrl =
                    streamRepository
                        .getStream(
                            dataStoreManager = dataStoreManager,
                            videoId = videoId,
                            isDownloading = false,
                            isVideo = false,
                            muxed = false,
                        ).firstOrNull()
                        ?.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: error("Could not resolve audio stream URL for $title.")

                val setUriEnvelope =
                    UpnpSoapBuilder.buildSetAvTransportUriEnvelope(
                        streamUrl = streamUrl,
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUrl = artwork,
                    )
                transport
                    .sendSoapAction(
                        controlUrl = speaker.avTransportControlUrl,
                        serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                        action = "SetAVTransportURI",
                        envelopeXml = setUriEnvelope,
                    ).getOrThrow()

                transport
                    .sendSoapAction(
                        controlUrl = speaker.avTransportControlUrl,
                        serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                        action = "Play",
                        envelopeXml = UpnpSoapBuilder.buildPlayEnvelope(),
                    ).getOrThrow()

                val resumePos = mediaPlayerHandler.player.currentPosition
                if (resumePos > 2_000L) {
                    runCatching {
                        transport.sendSoapAction(
                            controlUrl = speaker.avTransportControlUrl,
                            serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                            action = "Seek",
                            envelopeXml = UpnpSoapBuilder.buildSeekEnvelope(resumePos),
                        )
                    }
                }

                // Pause local playback once speaker confirms Play.
                if (mediaPlayerHandler.controlState.value.isPlaying) {
                    withContext(Dispatchers.Main) {
                        runCatching { mediaPlayerHandler.player.pause() }
                    }
                }

                val currentVolume =
                    speaker.renderingControlUrl?.let { rcUrl ->
                        transport
                            .sendSoapAction(
                                controlUrl = rcUrl,
                                serviceType = UpnpSoapBuilder.RENDERING_CONTROL_SERVICE,
                                action = "GetVolume",
                                envelopeXml = UpnpSoapBuilder.buildGetVolumeEnvelope(),
                            ).getOrNull()
                            ?.let { UpnpSoapBuilder.parseGetVolumeResponse(it) }
                    } ?: _state.value.volumePercent

                val msg = "Playing on ${speaker.friendlyName}"
                _state.value =
                    SmartSpeakerPlaybackState(
                        activeSpeaker = speaker,
                        isPlaying = true,
                        trackTitle = title,
                        trackArtist = artist,
                        volumePercent = currentVolume,
                        isScanning = false,
                        isBusy = false,
                        statusMessage = msg,
                    )
                Result.success(msg)
            } catch (e: Exception) {
                Logger.w(TAG, "Cast to ${speaker.friendlyName} failed: ${e.message}")
                _state.update { it.copy(isBusy = false, statusMessage = e.message ?: "Speaker cast failed") }
                Result.failure(e)
            }
        }

    suspend fun togglePlayPause(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val speaker = _state.value.activeSpeaker ?: return@withContext Result.failure(IllegalStateException("No speaker connected."))
            val targetPlay = !_state.value.isPlaying
            val res =
                transport.sendSoapAction(
                    controlUrl = speaker.avTransportControlUrl,
                    serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                    action = if (targetPlay) "Play" else "Pause",
                    envelopeXml = if (targetPlay) UpnpSoapBuilder.buildPlayEnvelope() else UpnpSoapBuilder.buildPauseEnvelope(),
                )
            res.map {
                _state.update { it.copy(isPlaying = targetPlay) }
                targetPlay
            }
        }

    suspend fun setVolume(volumePercent: Int): Result<Int> =
        withContext(Dispatchers.IO) {
            val speaker = _state.value.activeSpeaker ?: return@withContext Result.failure(IllegalStateException("No speaker connected."))
            val rcUrl = speaker.renderingControlUrl ?: return@withContext Result.failure(IllegalStateException("Speaker does not expose volume control."))
            val clamped = volumePercent.coerceIn(0, 100)
            transport
                .sendSoapAction(
                    controlUrl = rcUrl,
                    serviceType = UpnpSoapBuilder.RENDERING_CONTROL_SERVICE,
                    action = "SetVolume",
                    envelopeXml = UpnpSoapBuilder.buildSetVolumeEnvelope(clamped),
                ).map {
                    _state.update { it.copy(volumePercent = clamped) }
                    clamped
                }
        }

    suspend fun seekTo(positionMs: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            val speaker = _state.value.activeSpeaker ?: return@withContext Result.failure(IllegalStateException("No speaker connected."))
            transport
                .sendSoapAction(
                    controlUrl = speaker.avTransportControlUrl,
                    serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                    action = "Seek",
                    envelopeXml = UpnpSoapBuilder.buildSeekEnvelope(positionMs),
                ).map { Unit }
        }

    suspend fun stopCasting(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val speaker = _state.value.activeSpeaker
            if (speaker != null) {
                runCatching {
                    transport.sendSoapAction(
                        controlUrl = speaker.avTransportControlUrl,
                        serviceType = UpnpSoapBuilder.AV_TRANSPORT_SERVICE,
                        action = "Stop",
                        envelopeXml = UpnpSoapBuilder.buildStopEnvelope(),
                    )
                }
            }
            _state.value = SmartSpeakerPlaybackState(statusMessage = "Disconnected from smart speaker")
            Result.success(Unit)
        }
}
