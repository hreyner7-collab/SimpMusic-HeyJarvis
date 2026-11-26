package com.maxrave.simpmusic

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.offline.AutoSyncPlaylistKind
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.toggleWifiAutoSyncPlaylist
import com.maxrave.domain.data.model.offline.WifiAutoSyncPlaylistRef
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.AudioOutputKind
import com.maxrave.domain.data.player.DeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles
import com.maxrave.domain.data.player.DeviceAudioProfiles.saveDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.setPerDeviceAudioProfilesEnabled
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.LIVE_RESULT_TYPE
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.handoff.HandoffPeer
import com.maxrave.simpmusic.handoff.HandoffPlaybackSnapshot
import com.maxrave.simpmusic.handoff.HandoffTrack
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
import com.maxrave.simpmusic.handoff.PlaybackHandoffTransport
import com.maxrave.simpmusic.utils.DeviceAudioProfileController
import com.maxrave.simpmusic.utils.PlaylistHealthManager
import com.maxrave.simpmusic.utils.StreamPrewarmer
import com.maxrave.simpmusic.utils.WifiPlaylistAutoSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NewEnhancementsTest {
    private class InMemoryDataStore {
        val strings = MutableStateFlow<Map<String, String>>(emptyMap())
        var eqEnabled = false
        var eqBands: List<Float> = emptyList()
        var eqPreamp = 0f
        var stereoEnabled = false
        var stereoAmount = 0.5f
        var normalize = false
        var crossfadeEnabled = false
        var crossfadeDuration = 5000

        val proxy: DataStoreManager =
            Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> {
                        val k = args[0] as String
                        strings.map { it[k] }
                    }
                    "putString" -> {
                        val k = args[0] as String
                        val v = args[1] as String
                        strings.value = strings.value + (k to v)
                        Unit
                    }
                    "getEqualizerEnabled" -> flowOf(if (eqEnabled) DataStoreManager.TRUE else DataStoreManager.FALSE)
                    "setEqualizerEnabled" -> {
                        eqEnabled = args[0] as Boolean
                        Unit
                    }
                    "getEqualizerBands" -> flowOf(eqBands.joinToString(","))
                    "setEqualizerBands" -> {
                        @Suppress("UNCHECKED_CAST")
                        eqBands = args[0] as List<Float>
                        Unit
                    }
                    "getEqualizerPreamp" -> flowOf(eqPreamp)
                    "setEqualizerPreamp" -> {
                        eqPreamp = args[0] as Float
                        Unit
                    }
                    "getEqualizerAutoEqProfile" -> flowOf("")
                    "getStereoWidenEnabled" -> flowOf(if (stereoEnabled) DataStoreManager.TRUE else DataStoreManager.FALSE)
                    "setStereoWidenEnabled" -> {
                        stereoEnabled = args[0] as Boolean
                        Unit
                    }
                    "getStereoWidenAmount" -> flowOf(stereoAmount)
                    "setStereoWidenAmount" -> {
                        stereoAmount = args[0] as Float
                        Unit
                    }
                    "getNormalizeVolume" -> flowOf(if (normalize) DataStoreManager.TRUE else DataStoreManager.FALSE)
                    "setNormalizeVolume" -> {
                        normalize = args[0] as Boolean
                        Unit
                    }
                    "getCrossfadeEnabled" -> flowOf(if (crossfadeEnabled) DataStoreManager.TRUE else DataStoreManager.FALSE)
                    "setCrossfadeEnabled" -> {
                        crossfadeEnabled = args[0] as Boolean
                        Unit
                    }
                    "getCrossfadeDuration" -> flowOf(crossfadeDuration)
                    "setCrossfadeDuration" -> {
                        crossfadeDuration = args[0] as Int
                        Unit
                    }
                    else -> defaultReturn(method.returnType)
                }
            } as DataStoreManager
    }

    private fun songEntity(
        videoId: String,
        title: String,
        artist: String = "Skepta",
        durationSec: Int = 190,
        available: Boolean = true,
        downloadState: Int = DownloadState.STATE_NOT_DOWNLOADED,
        resultType: String? = "Song",
    ): SongEntity =
        SongEntity(
            videoId = videoId,
            albumId = null,
            albumName = "Konnichiwa",
            artistId = listOf("art_1"),
            artistName = listOf(artist),
            duration = "3:10",
            durationSeconds = durationSec,
            isAvailable = available,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = "https://img.example/$videoId.jpg",
            title = title,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            resultType = resultType,
            liked = false,
            totalPlayTime = 0L,
            downloadState = downloadState,
        )

    private fun makeTrack(
        videoId: String,
        title: String,
        resultType: String? = "Song",
    ): Track =
        Track(
            album = Album(id = "alb_1", name = "Test Album"),
            artists = listOf(Artist(id = "art_1", name = "Skepta")),
            duration = "3:10",
            durationSeconds = 190,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            thumbnails = emptyList(),
            title = title,
            videoId = videoId,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            feedbackTokens = null,
            resultType = resultType,
        )

    @Test
    fun streamPrewarmerResolvesTopTracksDeduplicatesAndSkipsLiveAndDownloaded() =
        runBlocking {
            val ds = InMemoryDataStore()
            val resolvedIds = CopyOnWriteArrayList<String>()
            val streamRepo =
                Proxy.newProxyInstance(
                    StreamRepository::class.java.classLoader,
                    arrayOf(StreamRepository::class.java),
                ) { _, method, args ->
                    if (method.name == "getStream") {
                        val id = args[1] as String
                        resolvedIds += id
                        flowOf("https://stream.example/$id.webm")
                    } else {
                        defaultReturn(method.returnType)
                    }
                } as StreamRepository

            val songRepo =
                Proxy.newProxyInstance(
                    SongRepository::class.java.classLoader,
                    arrayOf(SongRepository::class.java),
                ) { _, method, args ->
                    if (method.name == "getSongById") {
                        val id = args[0] as String
                        if (id == "downloaded_1") {
                            flowOf(songEntity(id, "Offline Song", downloadState = DownloadState.STATE_DOWNLOADED))
                        } else {
                            flowOf(null)
                        }
                    } else {
                        defaultReturn(method.returnType)
                    }
                } as SongRepository

            val prewarmer =
                StreamPrewarmer(
                    streamRepository = streamRepo,
                    dataStoreManager = ds.proxy,
                    songRepository = songRepo,
                    dispatcher = Dispatchers.Unconfined,
                    cooldown = 60.seconds,
                )

            val tracks =
                listOf(
                    makeTrack("live_stream", "Live Radio", resultType = LIVE_RESULT_TYPE),
                    makeTrack("downloaded_1", "Offline Song"),
                    makeTrack("fresh_1", "Shutdown"),
                    makeTrack("fresh_2", "That's Not Me"),
                )
            val jobs = prewarmer.prewarmTracks(tracks, maxTracks = 3)
            jobs.forEach { it.join() }

            // Live stream skipped upfront, downloaded_1 skipped inside permit, fresh_1 & fresh_2 pre-warmed
            assertEquals(listOf("fresh_1", "fresh_2"), resolvedIds.toList())

            // Re-calling within cooldown must not re-fetch fresh_1
            val dupJobs = prewarmer.prewarmTracks(listOf(makeTrack("fresh_1", "Shutdown")), maxTracks = 1)
            assertTrue(dupJobs.isEmpty(), "Cooldown must prevent duplicate network prewarm calls")
        }

    @Test
    fun playlistHealthManagerDetectsNormalizedDuplicatesAndScoresCleanReplacements() =
        runBlocking {
            val songs =
                listOf(
                    songEntity("vid_a", "Shutdown (Official Video)", "Skepta", downloadState = DownloadState.STATE_NOT_DOWNLOADED),
                    songEntity("vid_b", "Shutdown", "Skepta", downloadState = DownloadState.STATE_DOWNLOADED),
                    songEntity("vid_c", "Greaze Mode", "Skepta"),
                    songEntity("vid_c", "Greaze Mode", "Skepta"),
                )
            val dupScan = PlaylistHealthManager.findDuplicates(songs)
            assertEquals(2, dupScan.keptSongs.size)
            assertEquals(2, dupScan.duplicatesToRemove.size)
            assertTrue(
                dupScan.keptSongs.any { it.videoId == "vid_b" },
                "When deduplicating normalized matches, must keep the downloaded copy (vid_b)",
            )
            assertTrue(dupScan.duplicatesToRemove.any { it.videoId == "vid_a" })

            // Dead-track replacement scoring must pick the real track and reject slowed/reverb variants
            val broken = songEntity("dead_id", "Shutdown", "Skepta", durationSec = 188, available = false)
            assertTrue(PlaylistHealthManager.isLikelyUnavailable(broken))

            val slowedCandidate =
                SongsResult(
                    album = null,
                    artists = listOf(Artist(null, "Skepta")),
                    category = "Song",
                    duration = "3:30",
                    durationSeconds = 210,
                    feedbackTokens = null,
                    isExplicit = false,
                    resultType = "Song",
                    thumbnails = emptyList(),
                    title = "Shutdown (Slowed + Reverb)",
                    videoId = "bad_slowed",
                    videoType = "MUSIC_VIDEO_TYPE_ATV",
                    year = "2024",
                )
            val realCandidate =
                SongsResult(
                    album = null,
                    artists = listOf(Artist(null, "Skepta")),
                    category = "Song",
                    duration = "3:08",
                    durationSeconds = 188,
                    feedbackTokens = null,
                    isExplicit = true,
                    resultType = "Song",
                    thumbnails = emptyList(),
                    title = "Shutdown",
                    videoId = "good_real",
                    videoType = "MUSIC_VIDEO_TYPE_ATV",
                    year = "2016",
                )
            val searchRepo =
                Proxy.newProxyInstance(
                    SearchRepository::class.java.classLoader,
                    arrayOf(SearchRepository::class.java),
                ) { _, method, _ ->
                    if (method.name == "getSearchDataSong") {
                        flowOf(Resource.Success(arrayListOf(slowedCandidate, realCandidate)))
                    } else {
                        defaultReturn(method.returnType)
                    }
                } as SearchRepository

            val proposal = PlaylistHealthManager.findBestReplacement(broken, searchRepo)
            assertNotNull(proposal)
            assertEquals("good_real", proposal.replacement.videoId)
            assertTrue(
                PlaylistHealthManager.scoreReplacementCandidate("Shutdown", "Skepta", 188, realCandidate) >
                    PlaylistHealthManager.scoreReplacementCandidate("Shutdown", "Skepta", 188, slowedCandidate),
            )
        }

    @Test
    fun deviceAudioProfilesNormalizeDeviceKeyAndAutoApplyOnOutputChange() =
        runBlocking {
            val bt1 = AudioOutput(id = "42", name = "AirPods Pro", kind = AudioOutputKind.BLUETOOTH, isActive = true)
            val btReconnected = AudioOutput(id = "99", name = "  airpods   pro ", kind = AudioOutputKind.BLUETOOTH, isActive = true)
            assertEquals(DeviceAudioProfiles.deviceKeyFor(bt1), DeviceAudioProfiles.deviceKeyFor(btReconnected))

            val ds = InMemoryDataStore()
            ds.proxy.setPerDeviceAudioProfilesEnabled(true)
            ds.proxy.saveDeviceAudioProfile(
                DeviceAudioProfile(
                    deviceKey = DeviceAudioProfiles.deviceKeyFor(bt1),
                    displayName = "AirPods Pro",
                    kind = AudioOutputKind.BLUETOOTH.name,
                    equalizerEnabled = true,
                    equalizerBands = listOf(2f, 1f, 0f, -1f, 0f, 1f, 2f, 2f, 1f, 0f),
                    equalizerPreamp = -2.5f,
                    stereoWidenEnabled = true,
                    stereoWidenAmount = 0.75f,
                    normalizeVolume = true,
                    crossfadeEnabled = true,
                    crossfadeDurationMs = 4000,
                ),
            )

            val outputsFlow =
                MutableStateFlow(
                    listOf(AudioOutput(id = "1", name = "Phone Speaker", kind = AudioOutputKind.DEVICE_SPEAKER, isActive = true)),
                )
            val fakeHandler = makeFakeHandler(outputsFlow)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val ctrl = DeviceAudioProfileController(ds.proxy, fakeHandler.proxy, scope)
            try {
                ctrl.start()
                assertFalse(ds.eqEnabled)

                // User connects AirPods Pro -> saved Bluetooth profile must apply automatically!
                outputsFlow.value = listOf(btReconnected)
                delay(50L)
                assertTrue(ds.eqEnabled)
                assertEquals(-2.5f, ds.eqPreamp)
                assertTrue(ds.stereoEnabled)
                assertEquals(0.75f, ds.stereoAmount)
                assertTrue(ds.normalize)
                assertTrue(ds.crossfadeEnabled)
                assertEquals(4000, ds.crossfadeDuration)
                assertEquals("AirPods Pro", ctrl.lastAppliedProfile.value?.displayName)
            } finally {
                ctrl.stop()
                scope.cancel()
            }
        }

    @Test
    fun wifiPlaylistAutoSyncRespectsUnmeteredGateAndDownloadsOnlyMissingTracks() =
        runBlocking {
            val ds = InMemoryDataStore()
            val ref = WifiAutoSyncPlaylistRef(id = "7", title = "Gym Mix", kind = AutoSyncPlaylistKind.LOCAL_PLAYLIST)
            assertTrue(ds.proxy.toggleWifiAutoSyncPlaylist(ref))

            val songs =
                listOf(
                    songEntity("s_done", "Already Offline", downloadState = DownloadState.STATE_DOWNLOADED),
                    songEntity("s_live", "Live Stream", resultType = LIVE_RESULT_TYPE),
                    songEntity("s_need", "Needs Download", downloadState = DownloadState.STATE_NOT_DOWNLOADED),
                )
            val downloadedIds = CopyOnWriteArrayList<String>()

            val localPlaylistRepo =
                Proxy.newProxyInstance(
                    LocalPlaylistRepository::class.java.classLoader,
                    arrayOf(LocalPlaylistRepository::class.java),
                ) { _, method, _ ->
                    if (method.name == "getFullPlaylistTracks") songs else defaultReturn(method.returnType)
                } as LocalPlaylistRepository

            val playlistRepo =
                Proxy.newProxyInstance(
                    PlaylistRepository::class.java.classLoader,
                    arrayOf(PlaylistRepository::class.java),
                ) { _, method, _ -> defaultReturn(method.returnType) } as PlaylistRepository

            val songRepo =
                Proxy.newProxyInstance(
                    SongRepository::class.java.classLoader,
                    arrayOf(SongRepository::class.java),
                ) { _, method, _ ->
                    if (method.name == "getSongsByListVideoId") flowOf(songs) else defaultReturn(method.returnType)
                } as SongRepository

            val downloadHandler =
                Proxy.newProxyInstance(
                    DownloadHandler::class.java.classLoader,
                    arrayOf(DownloadHandler::class.java),
                ) { _, method, args ->
                    if (method.name == "downloadTrack") {
                        downloadedIds += args[0] as String
                        Unit
                    } else {
                        defaultReturn(method.returnType)
                    }
                } as DownloadHandler

            var onWifi = false
            val manager =
                WifiPlaylistAutoSyncManager(
                    dataStoreManager = ds.proxy,
                    localPlaylistRepository = localPlaylistRepo,
                    playlistRepository = playlistRepo,
                    songRepository = songRepo,
                    downloadHandler = downloadHandler,
                    unmeteredCheck = { onWifi },
                )

            // On cellular data -> must skip completely
            val cellularResult = manager.syncEnabledPlaylistsNow()
            assertEquals("metered_network", cellularResult.skippedReason)
            assertTrue(downloadedIds.isEmpty())

            // On Wi-Fi -> must download ONLY s_need (skipping s_done & s_live)
            onWifi = true
            val wifiResult = manager.syncEnabledPlaylistsNow()
            assertNull(wifiResult.skippedReason)
            assertEquals(1, wifiResult.tracksQueued)
            assertEquals(listOf("s_need"), downloadedIds.toList())
        }

    @Test
    fun playbackHandoffTransfersQueuePositionAndShuffleOverLoopbackHttp() =
        runBlocking {
            val dsSender = InMemoryDataStore()
            val dsReceiver = InMemoryDataStore()
            val senderFake = makeFakeHandler(MutableStateFlow(emptyList()))
            val receiverFake = makeFakeHandler(MutableStateFlow(emptyList()))

            val queueTracks = listOf(makeTrack("h1", "Track One"), makeTrack("h2", "Track Two"))
            senderFake.queueData.value =
                QueueData(
                    data =
                        QueueData.Data(
                            listTracks = queueTracks,
                            firstPlayedTrack = queueTracks[1],
                            playlistId = "PL_TEST",
                            playlistName = "Test Queue",
                            playlistType = PlaylistType.PLAYLIST,
                        ),
                )
            senderFake.currentIndex = 1
            senderFake.positionMs = 45_250L
            senderFake.controlState.value = senderFake.controlState.value.copy(isPlaying = true, isShuffle = true)
            senderFake.shuffleEnabled = true

            val senderTransport = PlaybackHandoffTransport()
            val receiverTransport = PlaybackHandoffTransport()
            val receivedSnapshots = CopyOnWriteArrayList<HandoffPlaybackSnapshot>()
            val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            val senderMgr = PlaybackHandoffManager(senderFake.proxy, dsSender.proxy, senderTransport, senderScope)
            val receiverMgr = PlaybackHandoffManager(receiverFake.proxy, dsReceiver.proxy, receiverTransport, receiverScope)
            try {
                val receiverPort =
                    receiverTransport.startServer(
                        preferredPort = 0,
                        beaconProvider = {
                            com.maxrave.simpmusic.handoff.HandoffBeacon(
                                deviceId = receiverMgr.localDeviceId,
                                deviceName = "Receiver PC",
                                platform = "DESKTOP",
                                httpPort = 0,
                            )
                        },
                        onGetState = { receiverMgr.buildCurrentSnapshot() },
                        onReceiveTransfer = { snap ->
                            receivedSnapshots += snap
                            receiverMgr.applyIncomingSnapshot(snap)
                        },
                        onPeerDiscovered = {},
                    )
                assertTrue(receiverPort > 0)

                val peer =
                    HandoffPeer(
                        deviceId = receiverMgr.localDeviceId,
                        deviceName = "Receiver PC",
                        platform = "DESKTOP",
                        host = "127.0.0.1",
                        httpPort = receiverPort,
                    )

                // 1. Push playback from Sender -> Receiver
                val sendRes = senderMgr.sendPlaybackTo(peer)
                assertTrue(sendRes.isSuccess, "Push handoff must succeed: ${sendRes.exceptionOrNull()}")
                assertEquals(1, senderFake.pauseCalls, "Sender must pause after handing off")
                assertEquals(1, receivedSnapshots.size)
                val snap = receivedSnapshots.single()
                assertEquals(listOf("h1", "h2"), snap.tracks.map { it.videoId })
                assertEquals(1, snap.currentIndex)
                assertEquals(45_250L, snap.positionMs)
                assertTrue(snap.isShuffle)

                // Wait for receiverMgr.applyIncomingSnapshot to load + seek
                delay(650L)
                assertTrue(receiverFake.shuffleEnabled)
                assertEquals(listOf("h1", "h2"), receiverFake.queueData.value?.data?.listTracks?.map { it.videoId })
                assertTrue(receiverFake.seekTargets.contains(45_250L))

                // 2. Pull playback back from Receiver -> Sender via GET /handoff/state?pause=1
                val pulled = senderTransport.pullSnapshot(peer, pauseRemote = true)
                assertNotNull(pulled)
                assertEquals(listOf("h1", "h2"), pulled.tracks.map { it.videoId })
            } finally {
                senderMgr.stop()
                receiverMgr.stop()
                senderScope.cancel()
                receiverScope.cancel()
            }
        }

    private class FakeHandlerBox(
        val audioOutputsFlow: MutableStateFlow<List<AudioOutput>>,
    ) {
        var shuffleEnabled = false
        var repeatMode = 0
        var positionMs = 0L
        var currentIndex = 0
        var pauseCalls = 0
        val seekTargets = CopyOnWriteArrayList<Long>()
        val queueData = MutableStateFlow<QueueData?>(null)
        val controlState =
            MutableStateFlow(
                ControlState(
                    isPlaying = false,
                    isShuffle = false,
                    repeatState = RepeatState.None,
                    isLiked = false,
                    isNextAvailable = true,
                    isPreviousAvailable = true,
                    isCrossfading = false,
                    volume = 1f,
                ),
            )
        val nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
        val simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
        val nowPlaying = MutableStateFlow<GenericMediaItem?>(null)
        val sleepTimerState = MutableStateFlow(SleepTimerState(false, 0))
        val castState = MutableStateFlow(GenericCastState.NOT_CASTING)

        val playerProxy: MediaPlayerInterface =
            Proxy.newProxyInstance(
                MediaPlayerInterface::class.java.classLoader,
                arrayOf(MediaPlayerInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAudioOutputs" -> audioOutputsFlow
                    "getShuffleModeEnabled" -> shuffleEnabled
                    "setShuffleModeEnabled" -> {
                        shuffleEnabled = args[0] as Boolean
                        Unit
                    }
                    "getRepeatMode" -> repeatMode
                    "setRepeatMode" -> {
                        repeatMode = args[0] as Int
                        Unit
                    }
                    "getCurrentPosition" -> positionMs
                    "pause" -> {
                        pauseCalls++
                        controlState.value = controlState.value.copy(isPlaying = false)
                        Unit
                    }
                    "seekTo" -> {
                        val pos = if (args.size == 1) args[0] as Long else args[1] as Long
                        positionMs = pos
                        seekTargets += pos
                        Unit
                    }
                    "getPlaybackParameters" -> GenericPlaybackParameters.DEFAULT
                    else -> defaultReturn(method.returnType)
                }
            } as MediaPlayerInterface

        val proxy: MediaPlayerHandler =
            Proxy.newProxyInstance(
                MediaPlayerHandler::class.java.classLoader,
                arrayOf(MediaPlayerHandler::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getPlayer" -> playerProxy
                    "getQueueData" -> queueData
                    "getControlState" -> controlState
                    "getNowPlayingState" -> nowPlayingState
                    "getSimpleMediaState" -> simpleMediaState
                    "getNowPlaying" -> nowPlaying
                    "getSleepTimerState" -> sleepTimerState
                    "getCastState" -> castState
                    "currentOrderIndex" -> currentIndex
                    "setShuffle" -> {
                        shuffleEnabled = args[0] as Boolean
                        controlState.value = controlState.value.copy(isShuffle = shuffleEnabled)
                        Unit
                    }
                    "setQueueData" -> {
                        queueData.value = QueueData(data = args[0] as QueueData.Data)
                        Unit
                    }
                    "loadMediaItem" -> {
                        currentIndex = (args[2] as? Int) ?: 0
                        Unit
                    }
                    else -> defaultReturn(method.returnType)
                }
            } as MediaPlayerHandler
    }

    private fun makeFakeHandler(outputs: MutableStateFlow<List<AudioOutput>>) = FakeHandlerBox(outputs)

    private companion object {
        fun defaultReturn(returnType: Class<*>): Any? =
            when {
                Flow::class.java.isAssignableFrom(returnType) -> emptyFlow<Any?>()
                returnType == java.lang.Boolean.TYPE -> false
                returnType == java.lang.Integer.TYPE -> 0
                returnType == java.lang.Long.TYPE -> 0L
                returnType == java.lang.Float.TYPE -> 0f
                returnType == java.lang.Double.TYPE -> 0.0
                returnType == Unit::class.java || returnType == Void.TYPE -> Unit
                List::class.java.isAssignableFrom(returnType) -> emptyList<Any?>()
                Set::class.java.isAssignableFrom(returnType) -> emptySet<Any?>()
                else -> null
            }
    }
}
