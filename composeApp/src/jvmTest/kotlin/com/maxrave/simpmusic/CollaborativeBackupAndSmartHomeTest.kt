package com.maxrave.simpmusic

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.listentogether.ListenTogetherRoom
import com.maxrave.domain.data.model.listentogether.RoomConnection
import com.maxrave.domain.data.model.listentogether.RoomMember
import com.maxrave.domain.data.model.listentogether.RoomTrack
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.DeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.saveDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.setPerDeviceAudioProfilesEnabled
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlaybackTimelinePrecision
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.ListenTogetherRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.backup.CloudBackupConfig
import com.maxrave.simpmusic.backup.CloudBackupManager
import com.maxrave.simpmusic.backup.CloudBackupProtocol
import com.maxrave.simpmusic.backup.CloudBackupProvider
import com.maxrave.simpmusic.backup.CloudBackupSchedule
import com.maxrave.simpmusic.backup.KtorCloudBackupHttpClient
import com.maxrave.simpmusic.collab.CollaborativeRoomManager
import com.maxrave.simpmusic.collab.CollaborativeRoomProtocol
import com.maxrave.simpmusic.handoff.HandoffBeacon
import com.maxrave.simpmusic.handoff.HandoffPeer
import com.maxrave.simpmusic.handoff.PlaybackHandoffManager
import com.maxrave.simpmusic.handoff.PlaybackHandoffTransport
import com.maxrave.simpmusic.smarthome.SmartSpeakerManager
import com.maxrave.simpmusic.smarthome.SmartSpeakerTransport
import com.maxrave.simpmusic.smarthome.SsdpProtocol
import com.maxrave.simpmusic.smarthome.UpnpDeviceParser
import com.maxrave.simpmusic.smarthome.UpnpSoapBuilder
import com.maxrave.simpmusic.smarthome.WholeHomeAudioManager
import com.maxrave.simpmusic.smarthome.WholeHomeRole
import com.maxrave.simpmusic.smarthome.WholeHomeSyncMath
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
import kotlinx.datetime.LocalDateTime
import java.io.BufferedInputStream
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollaborativeBackupAndSmartHomeTest {
    @Test
    fun playbackTimelinePrecisionBalancesBrowseSmoothnessAndFullScreenCadence() {
        PlaybackTimelinePrecision.resetForTest()
        assertFalse(PlaybackTimelinePrecision.isHighFrequencyActive)
        assertEquals(PlaybackTimelinePrecision.BACKGROUND_BROWSE_TICK_MS, PlaybackTimelinePrecision.currentTickIntervalMs())

        PlaybackTimelinePrecision.acquireHighFrequency()
        assertTrue(PlaybackTimelinePrecision.isHighFrequencyActive)
        assertEquals(PlaybackTimelinePrecision.HIGH_FREQUENCY_TICK_MS, PlaybackTimelinePrecision.currentTickIntervalMs())

        // Nested views (e.g. Now Playing + Fullscreen Lyrics) ref-count cleanly
        PlaybackTimelinePrecision.acquireHighFrequency()
        PlaybackTimelinePrecision.releaseHighFrequency()
        assertTrue(PlaybackTimelinePrecision.isHighFrequencyActive)

        PlaybackTimelinePrecision.releaseHighFrequency()
        assertFalse(PlaybackTimelinePrecision.isHighFrequencyActive)

        // Extra release must never underflow
        PlaybackTimelinePrecision.releaseHighFrequency()
        assertFalse(PlaybackTimelinePrecision.isHighFrequencyActive)
    }

    @Test
    fun collaborativeRoomParsesInviteCodesRanksVotedQueueAndRelaysEventsOverLoopback() =
        runBlocking {
            // Invite code extraction
            assertEquals("ABCD1234", CollaborativeRoomProtocol.extractRoomCode("Join my SimpMusic room with code ABCD1234"))
            assertEquals("ZX98CV12", CollaborativeRoomProtocol.extractRoomCode("simpmusic://room/zx98cv12?src=share"))
            assertEquals("QWER5678", CollaborativeRoomProtocol.extractRoomCode("qwer-5678"))

            // Deterministic ranking by votes descending, preserving original order on ties
            val q =
                listOf(
                    RoomTrack(id = "t1", title = "First", artist = "A"),
                    RoomTrack(id = "t2", title = "Second", artist = "B"),
                    RoomTrack(id = "t3", title = "Third", artist = "C"),
                )
            val ranked =
                CollaborativeRoomProtocol.rankUpcomingQueue(
                    q,
                    mapOf("t3" to setOf("u1", "u2"), "t2" to setOf("u1")),
                )
            assertEquals(listOf("t3", "t2", "t1"), ranked.map { it.id })

            // Loopback LAN event relay between two peers in the same room
            val dsA = InMemoryDataStore()
            val dsB = InMemoryDataStore()
            val handlerA = FakeHandlerBox()
            val handlerB = FakeHandlerBox()
            val roomFlowA =
                MutableStateFlow(
                    ListenTogetherRoom(
                        connection = RoomConnection.Connected("1.0"),
                        roomCode = "ROOM2026",
                        selfUserId = "host_1",
                        isHost = true,
                        members = listOf(RoomMember("host_1", "Alex", isHost = true, isConnected = true)),
                    ),
                )
            val roomFlowB =
                MutableStateFlow(
                    ListenTogetherRoom(
                        connection = RoomConnection.Connected("1.0"),
                        roomCode = "ROOM2026",
                        selfUserId = "guest_2",
                        isHost = false,
                        members = listOf(RoomMember("guest_2", "Sam", isHost = false, isConnected = true)),
                    ),
                )
            val repoA = fakeListenTogetherRepo(roomFlowA)
            val repoB = fakeListenTogetherRepo(roomFlowB)
            val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val transportA = PlaybackHandoffTransport()
            val transportB = PlaybackHandoffTransport()
            val handoffA = PlaybackHandoffManager(handlerA.proxy, dsA.proxy, transportA, scopeA)
            val handoffB = PlaybackHandoffManager(handlerB.proxy, dsB.proxy, transportB, scopeB)
            val collabA = CollaborativeRoomManager(repoA, handoffA, handlerA.proxy, scopeA)
            val collabB = CollaborativeRoomManager(repoB, handoffB, handlerB.proxy, scopeB)

            try {
                collabA.start()
                collabB.start()
                val portB =
                    transportB.startServer(
                        preferredPort = 0,
                        beaconProvider = { HandoffBeacon(handoffB.localDeviceId, "Peer B", "DESKTOP", 0, activeRoomCode = "ROOM2026") },
                        onGetState = { null },
                        onReceiveTransfer = {},
                        onPeerDiscovered = {},
                        onReceiveCustomPost = { path, body ->
                            if (path.startsWith(CollaborativeRoomProtocol.HTTP_PATH)) {
                                CollaborativeRoomProtocol.decode(body)?.let { collabB.applyIncomingEvent(it) } != null
                            } else {
                                false
                            }
                        },
                    )
                assertTrue(portB > 0)
                val peerB =
                    HandoffPeer(
                        deviceId = handoffB.localDeviceId,
                        deviceName = "Peer B",
                        platform = "DESKTOP",
                        host = "127.0.0.1",
                        httpPort = portB,
                        activeRoomCode = "ROOM2026",
                    )

                // Inject peer B into handoffA via direct custom post helper
                collabA.toggleTrackVote("t3", "Third", displayNameOverride = "Alex")
                assertEquals(1, collabA.uiState.value.voteCount("t3"))

                // Relay a chat + reaction + vote over HTTP loopback to collabB
                val voteEvent =
                    com.maxrave.simpmusic.collab.CollaborativeRoomEvent(
                        eventId = "ev-loopback-1",
                        roomCode = "ROOM2026",
                        type = com.maxrave.simpmusic.collab.CollaborativeEventType.VOTE_TRACK,
                        senderId = "host_1",
                        senderName = "Alex",
                        timestampMs = 1_000L,
                        trackId = "t3",
                        trackTitle = "Third",
                    )
                val delivered = handoffA.sendCustomPostToPeer(peerB, CollaborativeRoomProtocol.HTTP_PATH, CollaborativeRoomProtocol.encode(voteEvent))
                assertTrue(delivered)
                assertEquals(1, collabB.uiState.value.voteCount("t3"))

                // Sending duplicate eventId must not double-count
                collabB.applyIncomingEvent(voteEvent)
                assertEquals(1, collabB.uiState.value.voteCount("t3"))
            } finally {
                collabA.stop()
                collabB.stop()
                handoffA.stop()
                handoffB.stop()
                scopeA.cancel()
                scopeB.cancel()
            }
        }

    @Test
    fun automatedCloudBackupUploadsRestoresAndDeduplicatesOverLoopbackHttp() =
        runBlocking {
            // Schedule & Wi-Fi due checks
            val dailyCfg =
                CloudBackupConfig(
                    enabled = true,
                    provider = CloudBackupProvider.GITHUB_GIST,
                    schedule = CloudBackupSchedule.DAILY,
                    wifiOnly = true,
                    githubToken = "ghp_secret_test_token_999",
                    lastBackupEpochMs = 10_000L,
                )
            assertFalse(CloudBackupProtocol.isBackupDue(dailyCfg, nowEpochMs = 10_000L + 1_000L))
            assertTrue(CloudBackupProtocol.isBackupDue(dailyCfg, nowEpochMs = 10_000L + CloudBackupSchedule.DAILY.intervalMs))

            // Start a real loopback HTTP server simulating GitHub Gist API + WebDAV PUT/GET
            val server = MiniMockHttpServer()
            val storedGistContent = mutableMapOf<String, String>()
            var lastAuthHeader: String? = null
            var lastMethod: String? = null
            server.start { method, path, headers, body ->
                lastMethod = method
                lastAuthHeader = headers["authorization"]
                when {
                    method == "POST" && path == "/gists" -> {
                        val content =
                            CloudBackupProtocol.json
                                .parseToJsonElement(body)
                                .let { el ->
                                    (el as kotlinx.serialization.json.JsonObject)["files"]
                                        ?.let { f -> (f as kotlinx.serialization.json.JsonObject)[CloudBackupProtocol.DEFAULT_GIST_FILENAME] }
                                        ?.let { obj -> (obj as kotlinx.serialization.json.JsonObject)["content"] }
                                        ?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content }
                                }.orEmpty()
                        storedGistContent["gist_abc123"] = content
                        201 to """{"id":"gist_abc123","files":{"${CloudBackupProtocol.DEFAULT_GIST_FILENAME}":{"content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}}}"""
                    }

                    method == "GET" && path == "/gists/gist_abc123" -> {
                        val content = storedGistContent["gist_abc123"].orEmpty()
                        200 to """{"id":"gist_abc123","files":{"${CloudBackupProtocol.DEFAULT_GIST_FILENAME}":{"truncated":false,"content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}}}"""
                    }

                    method == "PUT" && path == "/webdav/backup.json" -> {
                        storedGistContent["webdav"] = body
                        200 to """{"ok":true}"""
                    }

                    method == "GET" && path == "/webdav/backup.json" -> {
                        200 to storedGistContent["webdav"].orEmpty()
                    }

                    else -> 404 to "not found"
                }
            }

            try {
                val dsSource = InMemoryDataStore()
                dsSource.eqEnabled = true
                dsSource.eqPreamp = -3.0f
                dsSource.crossfadeEnabled = true
                dsSource.crossfadeDuration = 6000
                dsSource.proxy.setPerDeviceAudioProfilesEnabled(true)
                dsSource.proxy.saveDeviceAudioProfile(
                    DeviceAudioProfile(
                        deviceKey = "BLUETOOTH:sony_xm5",
                        displayName = "Sony WH-1000XM5",
                        kind = "BLUETOOTH",
                        equalizerEnabled = true,
                    ),
                )

                val sourceEvents =
                    mutableListOf(
                        PlaybackEventEntity(
                            eventId = 1L,
                            timestamp = LocalDateTime(2026, 11, 26, 12, 0, 0),
                            videoId = "vid_skepta_1",
                            durationSecond = 190L,
                            listenedSecond = 185L,
                            syncId = "sync_evt_1",
                        ),
                    )
                val sourcePlaylists =
                    mutableListOf(
                        LocalPlaylistEntity(id = 1L, title = "Late Night Drive", tracks = listOf("vid_skepta_1", "vid_2")),
                    )

                val httpClient = KtorCloudBackupHttpClient(githubApiBaseUrl = "http://127.0.0.1:${server.port}")
                var onWifi = false
                val sourceMgr =
                    CloudBackupManager(
                        dataStoreManager = dsSource.proxy,
                        analyticsRepository = fakeAnalyticsRepo(sourceEvents),
                        songRepository = fakeSongRepo(listOf(sampleSong("vid_skepta_1", "Shutdown", liked = true))),
                        localPlaylistRepository = fakeLocalPlaylistRepo(sourcePlaylists),
                        httpClient = httpClient,
                        unmeteredCheck = { onWifi },
                        clockNowMs = { 50_000L },
                        deviceNameProvider = { "Henry's PC" },
                        appVersionProvider = { "2.6.43" },
                    )
                sourceMgr.updateConfig { dailyCfg.copy(lastBackupEpochMs = 0L) }

                // Scheduled backup on cellular must be skipped due to wifiOnly=true
                val cellularRes = sourceMgr.runScheduledBackupIfDue().getOrThrow()
                assertEquals("metered_network", cellularRes.skippedReason)

                // On Wi-Fi -> scheduled backup uploads to GitHub Gist!
                onWifi = true
                val backupRes = sourceMgr.runScheduledBackupIfDue().getOrThrow()
                assertNull(backupRes.skippedReason)
                assertEquals("gist_abc123", backupRes.gistId)
                assertEquals("POST", lastMethod)
                assertEquals("Bearer ghp_secret_test_token_999", lastAuthHeader)

                // Security invariant: secret token must NEVER appear inside uploaded JSON snapshot!
                val uploadedJson = storedGistContent["gist_abc123"].orEmpty()
                assertTrue(uploadedJson.contains("Sony WH-1000XM5"))
                assertTrue(uploadedJson.contains("Late Night Drive"))
                assertFalse(uploadedJson.contains("ghp_secret_test_token_999"), "Credentials must NEVER be serialized into cloud backup snapshots")

                // Restore onto a brand-new clean device DataStore + DB
                val dsTarget = InMemoryDataStore()
                val targetEvents = mutableListOf<PlaybackEventEntity>()
                val targetPlaylists = mutableListOf<LocalPlaylistEntity>()
                val targetMgr =
                    CloudBackupManager(
                        dataStoreManager = dsTarget.proxy,
                        analyticsRepository = fakeAnalyticsRepo(targetEvents),
                        songRepository = fakeSongRepo(listOf(sampleSong("vid_skepta_1", "Shutdown", liked = false))),
                        localPlaylistRepository = fakeLocalPlaylistRepo(targetPlaylists),
                        httpClient = httpClient,
                        unmeteredCheck = { true },
                        clockNowMs = { 60_000L },
                    )
                targetMgr.updateConfig {
                    CloudBackupConfig(
                        enabled = true,
                        provider = CloudBackupProvider.GITHUB_GIST,
                        githubToken = "ghp_secret_test_token_999",
                        githubGistId = "gist_abc123",
                    )
                }

                val restore1 = targetMgr.restoreLatest().getOrThrow()
                assertTrue(restore1.restoredPreferences)
                assertEquals(1, restore1.restoredEventsCount)
                assertEquals(1, restore1.restoredPlaylistsCount)
                assertEquals("Henry's PC", restore1.sourceDeviceName)
                assertTrue(dsTarget.eqEnabled)
                assertEquals(-3.0f, dsTarget.eqPreamp)
                assertTrue(dsTarget.crossfadeEnabled)
                assertEquals(6000, dsTarget.crossfadeDuration)

                // Restoring a second time must NOT duplicate playback events or playlists!
                val restore2 = targetMgr.restoreLatest().getOrThrow()
                assertEquals(0, restore2.restoredEventsCount)
                assertEquals(0, restore2.restoredPlaylistsCount)
            } finally {
                server.stop()
            }
        }

    @Test
    fun dlnaSmartSpeakerParsesSsdpUpnpXmlAndControlsMockSpeakerOverHttp() =
        runBlocking {
            // 1. SSDP response parsing
            val rawSsdp =
                "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://192.168.1.44:1400/xml/device_description.xml\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "USN: uuid:RINCON_12345::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            val parsedSsdp = SsdpProtocol.parseSsdpResponse(rawSsdp)
            assertNotNull(parsedSsdp)
            assertEquals("http://192.168.1.44:1400/xml/device_description.xml", parsedSsdp.location)

            // 2. UPnP Device Description XML parsing (Sonos-style relative controlURLs)
            val sonosXml =
                """
                <?xml version="1.0"?>
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <friendlyName>Living Room Sonos</friendlyName>
                    <manufacturer>Sonos, Inc.</manufacturer>
                    <modelName>Sonos Era 300</modelName>
                    <UDN>uuid:RINCON_998877</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </root>
                """.trimIndent()
            val server = MiniMockHttpServer()
            val receivedActions = CopyOnWriteArrayList<String>()
            var lastSoapBody = ""
            server.start { _, path, headers, body ->
                val soapAction = headers["soapaction"].orEmpty().trim('"')
                receivedActions += soapAction
                lastSoapBody = body
                when {
                    path.endsWith("/RenderingControl/Control") && soapAction.endsWith("#GetVolume") ->
                        200 to "<s:Envelope><s:Body><u:GetVolumeResponse><CurrentVolume>68</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"
                    else -> 200 to "<s:Envelope><s:Body><ok/></s:Body></s:Envelope>"
                }
            }

            try {
                val baseLocation = "http://127.0.0.1:${server.port}/xml/device_description.xml"
                val speaker = UpnpDeviceParser.parseDeviceDescriptionXml(baseLocation, sonosXml)
                assertNotNull(speaker)
                assertEquals("Living Room Sonos", speaker.friendlyName)
                assertEquals("Sonos", speaker.ecosystemLabel)
                assertEquals("http://127.0.0.1:${server.port}/MediaRenderer/AVTransport/Control", speaker.avTransportControlUrl)
                assertEquals("http://127.0.0.1:${server.port}/MediaRenderer/RenderingControl/Control", speaker.renderingControlUrl)

                // Verify XML escaping & time formatting
                assertEquals("01:02:05", UpnpSoapBuilder.formatUpnpTime(3_725_000L))
                val didl = UpnpSoapBuilder.buildDidlLiteMetadata("https://cdn.example/a?x=1&y=2", "Rock & Roll <Live>", "AC/DC")
                assertTrue(didl.contains("Rock &amp; Roll &lt;Live&gt;"))
                assertTrue(didl.contains("x=1&amp;y=2"))

                // 3. End-to-end cast + play + seek + getVolume + setVolume + pause + stop against loopback mock UPnP speaker
                val ds = InMemoryDataStore()
                val fakeHandler = FakeHandlerBox()
                fakeHandler.nowPlayingState.value =
                    fakeHandler.nowPlayingState.value.copy(
                        songEntity = sampleSong("vid_sonos", "Shutdown", liked = false),
                    )
                fakeHandler.positionMs = 15_000L
                fakeHandler.controlState.value = fakeHandler.controlState.value.copy(isPlaying = true)

                val streamRepo =
                    Proxy.newProxyInstance(
                        StreamRepository::class.java.classLoader,
                        arrayOf(StreamRepository::class.java),
                    ) { _, method, _ ->
                        if (method.name == "getStream") flowOf("https://audio.example/shutdown.m4a") else defaultReturn(method.returnType)
                    } as StreamRepository

                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val manager = SmartSpeakerManager(fakeHandler.proxy, streamRepo, ds.proxy, SmartSpeakerTransport(), scope)
                try {
                    val castRes = manager.castCurrentTrackTo(speaker)
                    assertTrue(castRes.isSuccess, "Cast to mock UPnP speaker must succeed: ${castRes.exceptionOrNull()}")
                    assertEquals(1, fakeHandler.pauseCalls, "Local player must pause once smart speaker starts")
                    assertEquals(68, manager.state.value.volumePercent)
                    assertTrue(manager.state.value.isPlaying)
                    assertTrue(receivedActions.any { it.endsWith("#SetAVTransportURI") })
                    assertTrue(receivedActions.any { it.endsWith("#Play") })
                    assertTrue(receivedActions.any { it.endsWith("#Seek") })
                    assertTrue(receivedActions.any { it.endsWith("#GetVolume") })

                    val volRes = manager.setVolume(80)
                    assertEquals(80, volRes.getOrThrow())
                    assertTrue(lastSoapBody.contains("<DesiredVolume>80</DesiredVolume>"))

                    val pauseRes = manager.togglePlayPause()
                    assertFalse(pauseRes.getOrThrow())
                    assertTrue(receivedActions.any { it.endsWith("#Pause") })

                    manager.stopCasting()
                    assertNull(manager.state.value.activeSpeaker)
                    assertTrue(receivedActions.any { it.endsWith("#Stop") })
                } finally {
                    scope.cancel()
                }
            } finally {
                server.stop()
            }
        }

    @Test
    fun wholeHomeAudioComputesLockstepSyncPlanAndSyncsFollowerRoomOverLoopback() =
        runBlocking {
            // Ahead of schedule -> waits cleanly until targetStartEpochMs
            val ahead =
                WholeHomeSyncMath.computeStartPlan(
                    snapshotPositionMs = 30_000L,
                    targetStartEpochMs = 10_650L,
                    nowEpochMs = 10_000L,
                    isPlaying = true,
                )
            assertEquals(650L, ahead.waitDelayMs)
            assertEquals(30_000L, ahead.targetPositionMs)

            // Late packet arrival -> catches up position immediately with 0 wait
            val late =
                WholeHomeSyncMath.computeStartPlan(
                    snapshotPositionMs = 30_000L,
                    targetStartEpochMs = 10_000L,
                    nowEpochMs = 10_320L,
                    isPlaying = true,
                )
            assertEquals(0L, late.waitDelayMs)
            assertEquals(30_320L, late.targetPositionMs)
            assertTrue(WholeHomeSyncMath.shouldCorrectDrift(30_000L, 30_600L))
            assertFalse(WholeHomeSyncMath.shouldCorrectDrift(30_000L, 30_200L))

            // Real loopback multi-room command transfer Leader -> Follower
            val dsLeader = InMemoryDataStore()
            val dsFollower = InMemoryDataStore()
            val leaderHandler = FakeHandlerBox()
            val followerHandler = FakeHandlerBox()
            val tracks = listOf(makeTrack("wh_1", "Living Room Jam"))
            leaderHandler.queueData.value =
                QueueData(
                    data =
                        QueueData.Data(
                            listTracks = tracks,
                            firstPlayedTrack = tracks.first(),
                            playlistId = "PL_WH",
                            playlistName = "House Party",
                            playlistType = PlaylistType.PLAYLIST,
                        ),
                )
            leaderHandler.positionMs = 12_000L
            leaderHandler.controlState.value = leaderHandler.controlState.value.copy(isPlaying = true)

            val scopeLeader = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val scopeFollower = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val transportLeader = PlaybackHandoffTransport()
            val transportFollower = PlaybackHandoffTransport()
            val handoffLeader = PlaybackHandoffManager(leaderHandler.proxy, dsLeader.proxy, transportLeader, scopeLeader)
            val handoffFollower = PlaybackHandoffManager(followerHandler.proxy, dsFollower.proxy, transportFollower, scopeFollower)
            val wholeHomeLeader = WholeHomeAudioManager(leaderHandler.proxy, handoffLeader, scopeLeader, clockNowMs = { 1_000L })
            val wholeHomeFollower = WholeHomeAudioManager(followerHandler.proxy, handoffFollower, scopeFollower, clockNowMs = { 1_650L })

            try {
                wholeHomeLeader.start()
                wholeHomeFollower.start()
                val followerPort =
                    transportFollower.startServer(
                        preferredPort = 0,
                        beaconProvider = { HandoffBeacon(handoffFollower.localDeviceId, "Kitchen Tablet", "ANDROID", 0) },
                        onGetState = { null },
                        onReceiveTransfer = {},
                        onPeerDiscovered = {},
                        onReceiveCustomPost = { path, body ->
                            if (path.startsWith(WholeHomeSyncMath.HTTP_PATH)) {
                                WholeHomeSyncMath.decodeCommand(body)?.let { wholeHomeFollower.handleIncomingCommand(it) } != null
                            } else {
                                false
                            }
                        },
                    )
                assertTrue(followerPort > 0)
                val followerPeer =
                    HandoffPeer(
                        deviceId = handoffFollower.localDeviceId,
                        deviceName = "Kitchen Tablet",
                        platform = "ANDROID",
                        host = "127.0.0.1",
                        httpPort = followerPort,
                    )

                // Send SYNC_PLAY_AT directly over HTTP loopback and verify follower locks onto group & queue
                val cmd =
                    com.maxrave.simpmusic.smarthome.WholeHomeGroupCommand(
                        type = com.maxrave.simpmusic.smarthome.WholeHomeCommandType.SYNC_PLAY_AT,
                        groupId = "wh-test-1",
                        groupName = "Whole Home (2 rooms)",
                        leaderDeviceId = handoffLeader.localDeviceId,
                        leaderDeviceName = "Living Room PC",
                        snapshot = handoffLeader.buildCurrentSnapshot(),
                        targetStartEpochMs = 1_650L,
                        positionMs = 12_000L,
                        volume = 0.75f,
                    )
                val sent = handoffLeader.sendCustomPostToPeer(followerPeer, WholeHomeSyncMath.HTTP_PATH, WholeHomeSyncMath.encodeCommand(cmd))
                assertTrue(sent)
                delay(500L)
                assertEquals(WholeHomeRole.FOLLOWER, wholeHomeFollower.state.value.role)
                assertEquals("wh-test-1", wholeHomeFollower.state.value.groupId)
                assertEquals(listOf("wh_1"), followerHandler.queueData.value?.data?.listTracks?.map { it.videoId })
                assertTrue(followerHandler.seekTargets.contains(12_000L))
            } finally {
                wholeHomeLeader.stop()
                wholeHomeFollower.stop()
                handoffLeader.stop()
                handoffFollower.stop()
                scopeLeader.cancel()
                scopeFollower.cancel()
            }
        }

    // ─────────────────────────── Test Helpers ───────────────────────────

    private class MiniMockHttpServer {
        private var serverSocket: ServerSocket? = null
        private val running = AtomicBoolean(false)
        val port: Int get() = serverSocket?.localPort ?: 0

        fun start(handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>) {
            val ss = ServerSocket(0)
            serverSocket = ss
            running.set(true)
            thread(isDaemon = true) {
                while (running.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        thread(isDaemon = true) {
                            client.use { s ->
                                s.soTimeout = 4_000
                                val input = BufferedInputStream(s.getInputStream())
                                val lines = mutableListOf<String>()
                                val sb = StringBuilder()
                                while (lines.size < 64) {
                                    val b = input.read()
                                    if (b == -1) break
                                    if (b == '\n'.code) {
                                        val line = sb.toString().trimEnd('\r')
                                        sb.setLength(0)
                                        if (line.isEmpty()) break
                                        lines += line
                                    } else {
                                        sb.append(b.toChar())
                                    }
                                }
                                val req = lines.firstOrNull()?.split(" ") ?: return@use
                                val method = req.getOrNull(0) ?: return@use
                                val path = req.getOrNull(1) ?: "/"
                                val headers =
                                    lines.drop(1).associate { line ->
                                        line.substringBefore(':').trim().lowercase() to line.substringAfter(':', "").trim()
                                    }
                                val len = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                val bodyBytes = ByteArray(len)
                                var off = 0
                                while (off < len) {
                                    val r = input.read(bodyBytes, off, len - off)
                                    if (r <= 0) break
                                    off += r
                                }
                                val body = String(bodyBytes, 0, off, StandardCharsets.UTF_8)
                                val (status, respBody) = handler(method, path, headers, body)
                                val respBytes = respBody.toByteArray(StandardCharsets.UTF_8)
                                val out = s.getOutputStream()
                                val respHeaders =
                                    "HTTP/1.1 $status OK\r\n" +
                                        "Content-Type: application/json; charset=utf-8\r\n" +
                                        "Content-Length: ${respBytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                out.write(respHeaders.toByteArray(StandardCharsets.US_ASCII))
                                out.write(respBytes)
                                out.flush()
                            }
                        }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { serverSocket?.close() }
        }
    }

    private class InMemoryDataStore {
        val strings = MutableStateFlow<Map<String, String>>(emptyMap())
        var eqEnabled = false
        var eqBands: List<Float> = emptyList()
        var eqPreamp = 0f
        var crossfadeEnabled = false
        var crossfadeDuration = 0

        val proxy: DataStoreManager =
            Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> strings.map { it[args[0] as String] }
                    "putString" -> {
                        strings.value = strings.value + ((args[0] as String) to (args[1] as String))
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

    private class FakeHandlerBox {
        var shuffleEnabled = false
        var repeatMode = 0
        var positionMs = 0L
        var currentIndex = 0
        var pauseCalls = 0
        var volume = 1f
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
        val outputsFlow = MutableStateFlow<List<AudioOutput>>(emptyList())

        val playerProxy: MediaPlayerInterface =
            Proxy.newProxyInstance(
                MediaPlayerInterface::class.java.classLoader,
                arrayOf(MediaPlayerInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAudioOutputs" -> outputsFlow
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
                    "getVolume" -> volume
                    "setVolume" -> {
                        volume = args[0] as Float
                        Unit
                    }
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

    private fun fakeListenTogetherRepo(roomFlow: MutableStateFlow<ListenTogetherRoom>): ListenTogetherRepository =
        Proxy.newProxyInstance(
            ListenTogetherRepository::class.java.classLoader,
            arrayOf(ListenTogetherRepository::class.java),
        ) { _, method, _ ->
            if (method.name == "getRoom") roomFlow else defaultReturn(method.returnType)
        } as ListenTogetherRepository

    private fun fakeAnalyticsRepo(events: MutableList<PlaybackEventEntity>): AnalyticsRepository =
        Proxy.newProxyInstance(
            AnalyticsRepository::class.java.classLoader,
            arrayOf(AnalyticsRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTotalPlaybackEventCount" -> flowOf(events.size.toLong())
                "getTotalListeningTimeInSeconds" -> flowOf(events.sumOf { it.listenedSecond })
                "getTotalEventArtistCount" -> flowOf(1L)
                "getPlaybackEventsByOffset" -> flowOf(events.toList())
                "insertPlaybackEvent" -> {
                    events +=
                        PlaybackEventEntity(
                            eventId = (events.size + 1).toLong(),
                            timestamp = LocalDateTime(2026, 11, 26, 12, 0, 0),
                            videoId = args[0] as String,
                            albumBrowseId = args[2] as? String,
                            durationSecond = args[3] as Long,
                            listenedSecond = args[4] as Long,
                            syncId = "sync_evt_${events.size + 1}",
                        )
                    flowOf(events.size.toLong())
                }
                else -> defaultReturn(method.returnType)
            }
        } as AnalyticsRepository

    private fun fakeSongRepo(songs: List<SongEntity>): SongRepository =
        Proxy.newProxyInstance(
            SongRepository::class.java.classLoader,
            arrayOf(SongRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMostPlayedSongs" -> flowOf(songs)
                "getLikedSongs" -> flowOf(songs.filter { it.liked })
                "getSongById" -> flowOf(songs.firstOrNull { it.videoId == args[0] })
                else -> defaultReturn(method.returnType)
            }
        } as SongRepository

    private fun fakeLocalPlaylistRepo(playlists: MutableList<LocalPlaylistEntity>): LocalPlaylistRepository =
        Proxy.newProxyInstance(
            LocalPlaylistRepository::class.java.classLoader,
            arrayOf(LocalPlaylistRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAllLocalPlaylists" -> flowOf(playlists.toList())
                "getListTrackVideoId" -> playlists.firstOrNull { it.id == args[0] }?.tracks.orEmpty()
                "insertLocalPlaylist" -> {
                    playlists += args[0] as LocalPlaylistEntity
                    flowOf(LocalResource.Success("OK"))
                }
                else -> defaultReturn(method.returnType)
            }
        } as LocalPlaylistRepository

    private fun sampleSong(
        videoId: String,
        title: String,
        liked: Boolean,
    ): SongEntity =
        SongEntity(
            videoId = videoId,
            albumId = null,
            albumName = "Konnichiwa",
            artistId = listOf("a1"),
            artistName = listOf("Skepta"),
            duration = "3:10",
            durationSeconds = 190,
            isAvailable = true,
            isExplicit = false,
            likeStatus = if (liked) "LIKE" else "INDIFFERENT",
            thumbnails = "https://img.example/$videoId.jpg",
            title = title,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = "Song",
            resultType = "Song",
            liked = liked,
            totalPlayTime = 185_000L,
        )

    private fun makeTrack(
        videoId: String,
        title: String,
    ): Track =
        Track(
            album = Album(id = "alb1", name = "Test"),
            artists = listOf(Artist(id = "a1", name = "Skepta")),
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
            resultType = "Song",
        )

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
