package com.maxrave.data.mediaservice

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.DiscordRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

actual fun createMediaServiceHandler(
    dataStoreManager: DataStoreManager,
    songRepository: SongRepository,
    streamRepository: StreamRepository,
    localPlaylistRepository: LocalPlaylistRepository,
    analyticsRepository: AnalyticsRepository,
    discordRepository: DiscordRepository,
    coroutineScope: CoroutineScope,
): MediaPlayerHandler =
    IosMediaPlayerHandlerImpl(
        dataStoreManager = dataStoreManager,
        songRepository = songRepository,
        streamRepository = streamRepository,
        localPlaylistRepository = localPlaylistRepository,
        analyticsRepository = analyticsRepository,
        discordRepository = discordRepository,
        coroutineScope = coroutineScope,
    )

@OptIn(ExperimentalForeignApi::class)
private class IosAvMediaPlayer(
    private val onTrackEnded: () -> Unit,
    private val onResolveAndPlay: (GenericMediaItem, Boolean) -> Unit,
) : MediaPlayerInterface {
    val avPlayer: AVPlayer = AVPlayer()
    private val items: MutableList<GenericMediaItem> = mutableListOf()
    private val listeners: MutableList<MediaPlayerListener> = mutableListOf()
    private var endObserver: Any? = null

    private var _currentIndex: Int = 0
    private var _playbackState: Int = PlayerConstants.STATE_IDLE
    private var _isPlaying: Boolean = false

    override var shuffleModeEnabled: Boolean = false
    override var repeatMode: Int = PlayerConstants.REPEAT_MODE_OFF
    override var playWhenReady: Boolean = true
    override var playbackParameters: GenericPlaybackParameters = GenericPlaybackParameters.DEFAULT
    override val audioSessionId: Int = PlayerConstants.AUDIO_SESSION_ID_UNSET
    override var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            avPlayer.volume = field * sleepFadeFactor
        }
    override var sleepFadeFactor: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            avPlayer.volume = volume * field
        }
    override var crossfadeSuppressed: Boolean = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled: Boolean = false

    init {
        configureAudioSession()
        endObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = null,
                queue = null,
            ) { _ ->
                _isPlaying = false
                _playbackState = PlayerConstants.STATE_ENDED
                onTrackEnded()
            }
    }

    private fun configureAudioSession() {
        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
    }

    fun loadUrlAndPlay(
        url: String,
        autoPlay: Boolean,
    ) {
        configureAudioSession()
        val nsUrl = NSURL.URLWithString(url) ?: return
        val item = AVPlayerItem.playerItemWithURL(nsUrl)
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        avPlayer.volume = volume * sleepFadeFactor
        _playbackState = PlayerConstants.STATE_READY
        if (autoPlay) {
            avPlayer.play()
            _isPlaying = true
        } else {
            avPlayer.pause()
            _isPlaying = false
        }
    }

    override fun play() {
        configureAudioSession()
        playWhenReady = true
        avPlayer.play()
        _isPlaying = true
        _playbackState = PlayerConstants.STATE_READY
    }

    override fun pause() {
        playWhenReady = false
        avPlayer.pause()
        _isPlaying = false
    }

    override fun stop() {
        playWhenReady = false
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        _isPlaying = false
        _playbackState = PlayerConstants.STATE_IDLE
    }

    override fun seekTo(positionMs: Long) {
        val target = CMTimeMakeWithSeconds(positionMs.coerceAtLeast(0L) / 1000.0, preferredTimescale = 1000)
        avPlayer.seekToTime(target)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex in items.indices) {
            _currentIndex = mediaItemIndex
            onResolveAndPlay(items[mediaItemIndex], playWhenReady)
            if (positionMs > 0L) {
                seekTo(positionMs)
            }
        }
    }

    override fun seekBack() {
        seekTo((currentPosition - 10_000L).coerceAtLeast(0L))
    }

    override fun seekForward() {
        val dur = duration
        val target = currentPosition + 10_000L
        seekTo(if (dur > 0L) target.coerceAtMost(dur) else target)
    }

    override fun seekToNext() {
        if (hasNextMediaItem()) {
            _currentIndex = (_currentIndex + 1).coerceAtMost(items.lastIndex)
            onResolveAndPlay(items[_currentIndex], true)
        } else if (repeatMode == PlayerConstants.REPEAT_MODE_ALL && items.isNotEmpty()) {
            _currentIndex = 0
            onResolveAndPlay(items[0], true)
        }
    }

    override fun seekToPrevious() {
        if (currentPosition > 3_000L) {
            seekTo(0L)
        } else {
            seekToPreviousMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) {
            _currentIndex = (_currentIndex - 1).coerceAtLeast(0)
            onResolveAndPlay(items[_currentIndex], true)
        } else if (items.isNotEmpty()) {
            seekTo(0L)
        }
    }

    override fun prepare() {
        if (_playbackState == PlayerConstants.STATE_IDLE) {
            _playbackState = PlayerConstants.STATE_BUFFERING
        }
    }

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        items.clear()
        items.add(mediaItem)
        _currentIndex = 0
        onResolveAndPlay(mediaItem, playWhenReady)
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        items.add(mediaItem)
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        items.add(index.coerceIn(0, items.size), mediaItem)
    }

    override fun removeMediaItem(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            if (_currentIndex >= items.size) {
                _currentIndex = (items.size - 1).coerceAtLeast(0)
            }
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex in items.indices && toIndex in items.indices) {
            val item = items.removeAt(fromIndex)
            items.add(toIndex, item)
        }
    }

    override fun clearMediaItems() {
        items.clear()
        _currentIndex = 0
        stop()
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index in items.indices) {
            items[index] = mediaItem
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = items.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = items.toList()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex

    override val isPlaying: Boolean
        get() = _isPlaying || avPlayer.rate > 0f

    override val currentPosition: Long
        get() {
            val sec = CMTimeGetSeconds(avPlayer.currentTime())
            return if (!sec.isNaN() && !sec.isInfinite() && sec >= 0.0) (sec * 1000.0).toLong() else 0L
        }

    override val duration: Long
        get() {
            val item = avPlayer.currentItem ?: return 0L
            val sec = CMTimeGetSeconds(item.duration)
            return if (!sec.isNaN() && !sec.isInfinite() && sec > 0.0) (sec * 1000.0).toLong() else 0L
        }

    override val bufferedPosition: Long
        get() = currentPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            return if (dur > 0L) ((currentPosition * 100L) / dur).toInt().coerceIn(0, 100) else 0
        }

    override val currentMediaItem: GenericMediaItem?
        get() = items.getOrNull(_currentIndex)

    override val currentMediaItemIndex: Int
        get() = _currentIndex

    override val mediaItemCount: Int
        get() = items.size

    override val contentPosition: Long
        get() = currentPosition

    override val playbackState: Int
        get() = _playbackState

    override fun hasNextMediaItem(): Boolean = _currentIndex < items.lastIndex

    override fun hasPreviousMediaItem(): Boolean = _currentIndex > 0

    override fun addListener(listener: MediaPlayerListener) {
        if (listener !in listeners) listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        stop()
        listeners.clear()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosMediaPlayerHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val songRepository: SongRepository,
    private val streamRepository: StreamRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val discordRepository: DiscordRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler {
    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(null)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData = MutableStateFlow<QueueData?>(null)
    override val queueData: StateFlow<QueueData?> = _queueData.asStateFlow()

    private val _controlState =
        MutableStateFlow(
            ControlState(
                isPlaying = false,
                isShuffle = false,
                repeatState = RepeatState.None,
                isLiked = false,
                isNextAvailable = false,
                isPreviousAvailable = false,
                isCrossfading = false,
                volume = 1f,
            ),
        )
    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState(isDone = false, timeRemaining = 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _skipSegments = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex = MutableStateFlow(0)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState.NOT_CASTING)
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}

    private var progressJob: Job? = null
    private var resolveJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val iosPlayer: IosAvMediaPlayer =
        IosAvMediaPlayer(
            onTrackEnded = { handleTrackEnded() },
            onResolveAndPlay = { item, autoPlay -> resolveAndPlay(item, autoPlay) },
        )

    override val player: MediaPlayerInterface = iosPlayer

    init {
        setupRemoteCommandCenter()
    }

    private fun setupRemoteCommandCenter() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.setEnabled(true)
        center.playCommand.addTargetWithHandler { _ ->
            iosPlayer.play()
            syncControlState()
            updateNowPlayingInfo()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.setEnabled(true)
        center.pauseCommand.addTargetWithHandler { _ ->
            iosPlayer.pause()
            syncControlState()
            updateNowPlayingInfo()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.setEnabled(true)
        center.togglePlayPauseCommand.addTargetWithHandler { _ ->
            if (iosPlayer.isPlaying) iosPlayer.pause() else iosPlayer.play()
            syncControlState()
            updateNowPlayingInfo()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.nextTrackCommand.setEnabled(true)
        center.nextTrackCommand.addTargetWithHandler { _ ->
            iosPlayer.seekToNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.previousTrackCommand.setEnabled(true)
        center.previousTrackCommand.addTargetWithHandler { _ ->
            iosPlayer.seekToPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun handleTrackEnded() {
        when (_controlState.value.repeatState) {
            RepeatState.One -> {
                iosPlayer.seekTo(0L)
                iosPlayer.play()
                syncControlState()
            }
            RepeatState.All -> {
                iosPlayer.seekToNext()
            }
            RepeatState.None -> {
                if (iosPlayer.hasNextMediaItem()) {
                    iosPlayer.seekToNext()
                } else {
                    _simpleMediaState.value = SimpleMediaState.Ended
                    syncControlState()
                    updateNowPlayingInfo()
                }
            }
        }
    }

    private fun resolveAndPlay(
        mediaItem: GenericMediaItem,
        autoPlay: Boolean,
    ) {
        _nowPlaying.value = mediaItem
        _currentSongIndex.value = iosPlayer.currentMediaItemIndex
        val matchingTrack =
            _queueData.value?.data?.listTracks?.firstOrNull { it.videoId == mediaItem.mediaId }
        _nowPlayingState.value =
            NowPlayingTrackState(
                mediaItem = mediaItem,
                track = matchingTrack,
                songEntity = null,
            )
        _simpleMediaState.value = SimpleMediaState.Buffering(0L)
        syncControlState()
        updateNowPlayingInfo()

        resolveJob?.cancel()
        resolveJob =
            coroutineScope.launch(Dispatchers.IO) {
                val directUrl =
                    mediaItem.uri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val streamUrl =
                    directUrl ?: runCatching {
                        streamRepository
                            .getStream(
                                dataStoreManager = dataStoreManager,
                                videoId = mediaItem.mediaId,
                                isDownloading = false,
                                isVideo = false,
                                muxed = false,
                            ).firstOrNull { !it.isNullOrBlank() }
                    }.getOrNull()

                withContext(Dispatchers.Main) {
                    if (!streamUrl.isNullOrBlank()) {
                        iosPlayer.loadUrlAndPlay(streamUrl, autoPlay)
                        val dur = iosPlayer.duration
                        _simpleMediaState.value = SimpleMediaState.Ready(dur)
                        startProgressUpdate()
                    } else {
                        _simpleMediaState.value = SimpleMediaState.Initial
                        showToast(ToastType.PlayerError("Unable to resolve stream URL"))
                    }
                    syncControlState()
                    updateNowPlayingInfo()
                }
            }
    }

    private fun syncControlState() {
        val current = _controlState.value
        _controlState.value =
            current.copy(
                isPlaying = iosPlayer.isPlaying,
                isShuffle = iosPlayer.shuffleModeEnabled,
                isNextAvailable = iosPlayer.hasNextMediaItem() || current.repeatState == RepeatState.All,
                isPreviousAvailable = iosPlayer.hasPreviousMediaItem(),
                volume = iosPlayer.volume,
            )
    }

    private fun updateNowPlayingInfo() {
        val item = _nowPlaying.value ?: return
        val info = mutableMapOf<Any?, Any?>()
        item.metadata.title?.let { info[MPMediaItemPropertyTitle] = it }
        item.metadata.artist?.let { info[MPMediaItemPropertyArtist] = it }
        item.metadata.albumTitle?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        val durSec = iosPlayer.duration / 1000.0
        if (durSec > 0.0) {
            info[MPMediaItemPropertyPlaybackDuration] = durSec
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = iosPlayer.currentPosition / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (iosPlayer.isPlaying) 1.0 else 0.0
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            PlayerEvent.PlayPause -> {
                if (iosPlayer.isPlaying) {
                    iosPlayer.pause()
                    stopProgressUpdate()
                } else {
                    iosPlayer.play()
                    startProgressUpdate()
                }
                syncControlState()
                updateNowPlayingInfo()
            }
            PlayerEvent.Backward -> {
                iosPlayer.seekBack()
                updateNowPlayingInfo()
            }
            PlayerEvent.Forward -> {
                iosPlayer.seekForward()
                updateNowPlayingInfo()
            }
            PlayerEvent.Stop -> {
                iosPlayer.stop()
                stopProgressUpdate()
                _simpleMediaState.value = SimpleMediaState.Initial
                syncControlState()
                updateNowPlayingInfo()
            }
            PlayerEvent.Next -> {
                iosPlayer.seekToNext()
            }
            PlayerEvent.Previous -> {
                iosPlayer.seekToPrevious()
            }
            PlayerEvent.SkipToPrevious -> {
                iosPlayer.seekToPreviousMediaItem()
            }
            PlayerEvent.Shuffle -> {
                iosPlayer.shuffleModeEnabled = !iosPlayer.shuffleModeEnabled
                syncControlState()
            }
            PlayerEvent.Repeat -> {
                val nextRepeat =
                    when (_controlState.value.repeatState) {
                        RepeatState.None -> {
                            iosPlayer.repeatMode = PlayerConstants.REPEAT_MODE_ALL
                            RepeatState.All
                        }
                        RepeatState.All -> {
                            iosPlayer.repeatMode = PlayerConstants.REPEAT_MODE_ONE
                            RepeatState.One
                        }
                        RepeatState.One -> {
                            iosPlayer.repeatMode = PlayerConstants.REPEAT_MODE_OFF
                            RepeatState.None
                        }
                    }
                _controlState.update { it.copy(repeatState = nextRepeat) }
                syncControlState()
            }
            is PlayerEvent.UpdateProgress -> {
                val dur = iosPlayer.duration
                val targetMs =
                    if (dur > 0L) {
                        ((playerEvent.newProgress.coerceIn(0f, 100f) / 100f) * dur).toLong()
                    } else {
                        playerEvent.newProgress.toLong().coerceAtLeast(0L)
                    }
                iosPlayer.seekTo(targetMs)
                _simpleMediaState.value = SimpleMediaState.Progress(targetMs)
                updateNowPlayingInfo()
            }
            PlayerEvent.ToggleLike -> {
                toggleLike()
            }
            is PlayerEvent.UpdateVolume -> {
                iosPlayer.volume = playerEvent.newVolume
                syncControlState()
            }
        }
    }

    override fun toggleRadio() = Unit

    override fun toggleLike() {
        _controlState.update { it.copy(isLiked = !it.isLiked) }
    }

    override fun like(liked: Boolean) {
        _controlState.update { it.copy(isLiked = liked) }
    }

    override fun resetSongAndQueue() {
        resolveJob?.cancel()
        stopProgressUpdate()
        iosPlayer.clearMediaItems()
        _nowPlaying.value = null
        _nowPlayingState.value = NowPlayingTrackState.initial()
        _queueData.value = null
        _currentSongIndex.value = 0
        _simpleMediaState.value = SimpleMediaState.Initial
        syncControlState()
    }

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) return
        sleepTimerJob =
            coroutineScope.launch {
                var remaining = minutes
                while (remaining > 0 && isActive) {
                    _sleepTimerState.value = SleepTimerState(isDone = false, timeRemaining = remaining)
                    delay(60_000L)
                    remaining--
                }
                _sleepTimerState.value = SleepTimerState(isDone = true, timeRemaining = 0)
                iosPlayer.pause()
                syncControlState()
            }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(isDone = false, timeRemaining = 0)
    }

    override fun removeMediaItem(position: Int) {
        iosPlayer.removeMediaItem(position)
        _queueData.update { current ->
            if (current != null && position in current.data.listTracks.indices) {
                current.removeTrackWithIndex(position)
            } else {
                current
            }
        }
        syncControlState()
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        iosPlayer.playWhenReady = playWhenReady
        iosPlayer.setMediaItem(mediaItem)
    }

    override fun clearMediaItems() {
        iosPlayer.clearMediaItems()
        syncControlState()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        mediaItemList.forEach { iosPlayer.addMediaItem(it) }
        syncControlState()
    }

    override fun playMediaItemInMediaSource(index: Int) {
        iosPlayer.playWhenReady = true
        iosPlayer.seekTo(index, 0L)
    }

    override fun currentSongIndex(): Int = iosPlayer.currentMediaItemIndex

    override fun currentOrderIndex(): Int = iosPlayer.currentMediaItemIndex

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
        iosPlayer.moveMediaItem(from, to)
    }

    override fun resetCrossfade() = Unit

    override fun shufflePlaylist(randomTrackIndex: Int) {
        iosPlayer.shuffleModeEnabled = true
        syncControlState()
    }

    override fun loadMore() = Unit

    override fun getRelated(videoId: String) = Unit

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.value =
            QueueData(
                queueState = QueueData.StateSource.STATE_INITIALIZED,
                data = queueData,
            )
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = iosPlayer.currentMediaItem

    override suspend fun moveItemUp(position: Int) {
        if (position > 0) swap(position, position - 1)
    }

    override suspend fun moveItemDown(position: Int) {
        if (position < iosPlayer.mediaItemCount - 1) swap(position, position + 1)
    }

    override suspend fun moveItemToPlayNext(position: Int) {
        val target = (iosPlayer.currentMediaItemIndex + 1).coerceAtMost((iosPlayer.mediaItemCount - 1).coerceAtLeast(0))
        swap(position, target)
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (mediaItem != null) {
            iosPlayer.addMediaItem(index, mediaItem)
            syncControlState()
        }
    }

    override fun reset() {
        resetSongAndQueue()
    }

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {
        val tracks = _queueData.value?.data?.listTracks.orEmpty()
        if (tracks.isEmpty()) return
        iosPlayer.clearMediaItems()
        tracks.forEach { iosPlayer.addMediaItem(it.toGenericMediaItem()) }
        val startIndex = (index ?: 0).coerceIn(0, tracks.lastIndex)
        iosPlayer.playWhenReady = true
        iosPlayer.seekTo(startIndex, 0L)
    }

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {
        _queueData.update { current ->
            (current ?: QueueData()).addTrackList(listTrack)
        }
        listTrack.forEach { iosPlayer.addMediaItem(it.toGenericMediaItem()) }
        syncControlState()
    }

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean {
        load(downloaded = downloaded, index = index)
        return true
    }

    override fun addQueueToPlayer() {
        val tracks = _queueData.value?.data?.listTracks.orEmpty()
        iosPlayer.clearMediaItems()
        tracks.forEach { iosPlayer.addMediaItem(it.toGenericMediaItem()) }
        syncControlState()
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        coroutineScope.launch {
            load(index = index)
        }
    }

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
    }

    override suspend fun playNext(track: Track) {
        val nextIndex = (iosPlayer.currentMediaItemIndex + 1).coerceAtMost(iosPlayer.mediaItemCount)
        iosPlayer.addMediaItem(nextIndex, track.toGenericMediaItem())
        _queueData.update { current ->
            (current ?: QueueData()).addToIndex(track, nextIndex)
        }
        syncControlState()
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        val mediaItem =
            when (anyTrack) {
                is Track -> anyTrack.toGenericMediaItem()
                is SongEntity -> anyTrack.toGenericMediaItem()
                is GenericMediaItem -> anyTrack
                else -> return
            }
        val tracks = _queueData.value?.data?.listTracks
        if (tracks.isNullOrEmpty() && anyTrack is Track) {
            _queueData.value =
                QueueData(
                    queueState = QueueData.StateSource.STATE_INITIALIZED,
                    data = QueueData.Data(listTracks = listOf(anyTrack), firstPlayedTrack = anyTrack),
                )
        }
        iosPlayer.playWhenReady = true
        iosPlayer.setMediaItem(mediaItem)
    }

    override fun getPlayerDuration(): Long = iosPlayer.duration

    override fun getProgress(): Long = iosPlayer.currentPosition

    override fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob =
            coroutineScope.launch {
                while (isActive && iosPlayer.isPlaying) {
                    _simpleMediaState.value = SimpleMediaState.Progress(iosPlayer.currentPosition)
                    updateNowPlayingInfo()
                    delay(500L)
                }
            }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    override fun startBufferedUpdate() = Unit

    override fun stopBufferedUpdate() = Unit

    override fun mayBeNormalizeVolume() = Unit

    override fun mayBeSaveRecentSong(runBlocking: Boolean) = Unit

    override fun mayBeSavePlaybackState() = Unit

    override fun mayBeRestoreQueue() = Unit

    override suspend fun restoreQueueAndPlay(): Boolean = false

    override fun shouldReleaseOnTaskRemoved(): Boolean = false

    override fun release() {
        resolveJob?.cancel()
        sleepTimerJob?.cancel()
        stopProgressUpdate()
        iosPlayer.release()
    }
}

private fun Track.toGenericMediaItem(): GenericMediaItem =
    GenericMediaItem(
        mediaId = videoId,
        uri = videoId,
        metadata =
            GenericMediaMetadata(
                title = title,
                artist = artists?.joinToString(", ") { it.name }.orEmpty(),
                albumTitle = album?.name,
                artworkUri = thumbnails?.lastOrNull()?.url,
                description = duration,
            ),
    )

private fun SongEntity.toGenericMediaItem(): GenericMediaItem =
    GenericMediaItem(
        mediaId = videoId,
        uri = videoId,
        metadata =
            GenericMediaMetadata(
                title = title,
                artist = artistName?.joinToString(", ").orEmpty(),
                albumTitle = albumName,
                artworkUri = thumbnails,
                description = duration,
            ),
    )
