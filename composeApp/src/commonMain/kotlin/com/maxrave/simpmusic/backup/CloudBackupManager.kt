package com.maxrave.simpmusic.backup

import com.maxrave.data.listentogether.ListenTogetherPrefs
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheEnabledFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.rollingOfflineCacheLimitMbFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setRollingOfflineCacheEnabled
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setRollingOfflineCacheLimitMb
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.setWifiAutoSyncEnabled
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.toggleWifiAutoSyncPlaylist
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.wifiAutoSyncEnabledFlow
import com.maxrave.domain.data.model.offline.OfflineCacheAndSyncSettings.wifiAutoSyncPlaylistsFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.deviceAudioProfilesFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.perDeviceAudioProfilesEnabledFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.saveDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.setPerDeviceAudioProfilesEnabled
import com.maxrave.domain.data.player.EightDDirection
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.backup.CloudBackupProtocol.cloudBackupConfigFlow
import com.maxrave.simpmusic.backup.CloudBackupProtocol.readCloudBackupConfig
import com.maxrave.simpmusic.backup.CloudBackupProtocol.writeCloudBackupConfig
import com.maxrave.simpmusic.expect.isOnUnmeteredNetwork
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "CloudBackupManager"
private const val MAX_EVENTS_TO_BACKUP = 500
private const val MAX_LIKED_TO_BACKUP = 1_000
private const val MAX_TOP_SONGS_TO_BACKUP = 50

/**
 * Automates cloud backup and restore of listening statistics, preferences, per-device audio
 * profiles, Wi-Fi auto-sync selections, and local playlists via private GitHub Gist or WebDAV/HTTP.
 *
 * Security boundary: session cookies, OAuth tokens, and API keys are NEVER included in snapshots.
 */
@OptIn(ExperimentalTime::class)
class CloudBackupManager(
    private val dataStoreManager: DataStoreManager,
    private val analyticsRepository: AnalyticsRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val httpClient: CloudBackupHttpClient = KtorCloudBackupHttpClient(),
    private val unmeteredCheck: () -> Boolean = { getPlatform() == Platform.Desktop || isOnUnmeteredNetwork() },
    private val clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deviceNameProvider: () -> String = { if (getPlatform() == Platform.Android) "SimpMusic Android" else "SimpMusic Desktop" },
    private val appVersionProvider: () -> String = { runCatching { VersionManager.getVersionName() }.getOrDefault("2.6.43") },
) {
    private val mutex = Mutex()

    val configFlow: Flow<CloudBackupConfig> = dataStoreManager.cloudBackupConfigFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    suspend fun getConfig(): CloudBackupConfig = dataStoreManager.readCloudBackupConfig()

    suspend fun updateConfig(transform: (CloudBackupConfig) -> CloudBackupConfig): CloudBackupConfig {
        val updated = transform(dataStoreManager.readCloudBackupConfig())
        dataStoreManager.writeCloudBackupConfig(updated)
        return updated
    }

    /**
     * Runs a scheduled cloud backup on startup if enabled, configured, due by schedule,
     * and allowed under the Wi-Fi-only preference.
     */
    suspend fun runScheduledBackupIfDue(): Result<CloudBackupResult> {
        val cfg = getConfig()
        val now = clockNowMs()
        if (!CloudBackupProtocol.isBackupDue(cfg, now)) {
            return Result.success(
                CloudBackupResult(
                    summary = "Not due",
                    uploadedBytes = 0,
                    skippedReason = "not_due",
                ),
            )
        }
        if (cfg.wifiOnly && !unmeteredCheck()) {
            return Result.success(
                CloudBackupResult(
                    summary = "Waiting for Wi-Fi",
                    uploadedBytes = 0,
                    skippedReason = "metered_network",
                ),
            )
        }
        return backupNow(respectWifiOnly = true)
    }

    suspend fun backupNow(respectWifiOnly: Boolean = false): Result<CloudBackupResult> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val cfg = getConfig()
                if (!cfg.isConfigured) {
                    return@withContext Result.failure(IllegalStateException("Configure GitHub Gist token or WebDAV/HTTP URL first."))
                }
                if (respectWifiOnly && cfg.wifiOnly && !unmeteredCheck()) {
                    return@withContext Result.success(
                        CloudBackupResult(
                            summary = "Waiting for Wi-Fi",
                            uploadedBytes = 0,
                            skippedReason = "metered_network",
                        ),
                    )
                }

                _isRunning.value = true
                try {
                    val snapshot = exportSnapshot()
                    val encoded = CloudBackupProtocol.encodeSnapshot(snapshot)
                    val byteCount = encoded.encodeToByteArray().size
                    val now = clockNowMs()
                    val summary =
                        "${snapshot.listeningStats.totalPlayCount} plays · " +
                            "${snapshot.listeningStats.localPlaylists.size} playlists · " +
                            "${snapshot.preferences.deviceAudioProfiles.size} audio profiles"

                    when (cfg.provider) {
                        CloudBackupProvider.GITHUB_GIST -> {
                            val gistId =
                                httpClient
                                    .uploadGitHubGist(
                                        token = cfg.githubToken,
                                        existingGistId = cfg.githubGistId.takeIf { it.isNotBlank() },
                                        filename = CloudBackupProtocol.DEFAULT_GIST_FILENAME,
                                        jsonContent = encoded,
                                    ).getOrThrow()
                            updateConfig {
                                it.copy(
                                    githubGistId = gistId,
                                    lastBackupEpochMs = now,
                                    lastBackupSummary = summary,
                                    lastError = null,
                                )
                            }
                            Logger.i(TAG, "Cloud backup uploaded to GitHub Gist $gistId ($byteCount bytes)")
                            Result.success(CloudBackupResult(gistId = gistId, summary = summary, uploadedBytes = byteCount))
                        }

                        CloudBackupProvider.WEBDAV_OR_HTTP -> {
                            httpClient
                                .uploadCustomEndpoint(
                                    url = cfg.customEndpointUrl,
                                    authHeader = cfg.customAuthHeader,
                                    jsonContent = encoded,
                                ).getOrThrow()
                            updateConfig {
                                it.copy(
                                    lastBackupEpochMs = now,
                                    lastBackupSummary = summary,
                                    lastError = null,
                                )
                            }
                            Logger.i(TAG, "Cloud backup uploaded to custom endpoint ($byteCount bytes)")
                            Result.success(CloudBackupResult(summary = summary, uploadedBytes = byteCount))
                        }
                    }
                } catch (e: Exception) {
                    val err = e.message ?: "Cloud backup failed"
                    Logger.e(TAG, "Cloud backup failed: $err", e)
                    runCatching { updateConfig { it.copy(lastError = err) } }
                    Result.failure(e)
                } finally {
                    _isRunning.value = false
                }
            }
        }

    suspend fun restoreLatest(): Result<CloudRestoreResult> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val cfg = getConfig()
                if (!cfg.isConfigured) {
                    return@withContext Result.failure(IllegalStateException("Configure GitHub Gist or WebDAV/HTTP URL first."))
                }
                _isRunning.value = true
                try {
                    val rawJson =
                        when (cfg.provider) {
                            CloudBackupProvider.GITHUB_GIST -> {
                                if (cfg.githubGistId.isBlank()) {
                                    error("No Gist ID saved yet — run a backup or paste your Gist ID first.")
                                }
                                httpClient
                                    .downloadGitHubGist(
                                        token = cfg.githubToken,
                                        gistId = cfg.githubGistId,
                                        filename = CloudBackupProtocol.DEFAULT_GIST_FILENAME,
                                    ).getOrThrow()
                            }

                            CloudBackupProvider.WEBDAV_OR_HTTP -> {
                                httpClient
                                    .downloadCustomEndpoint(
                                        url = cfg.customEndpointUrl,
                                        authHeader = cfg.customAuthHeader,
                                    ).getOrThrow()
                            }
                        }
                    val snapshot =
                        CloudBackupProtocol.decodeSnapshot(rawJson)
                            ?: error("Downloaded cloud backup is not a valid SimpMusic backup JSON.")
                    val result = applySnapshot(snapshot)
                    updateConfig { it.copy(lastError = null) }
                    Logger.i(TAG, "Restored cloud backup from ${result.sourceDeviceName}: +${result.restoredEventsCount} plays")
                    Result.success(result)
                } catch (e: Exception) {
                    val err = e.message ?: "Cloud restore failed"
                    Logger.e(TAG, "Cloud restore failed: $err", e)
                    runCatching { updateConfig { it.copy(lastError = err) } }
                    Result.failure(e)
                } finally {
                    _isRunning.value = false
                }
            }
        }

    suspend fun exportSnapshot(): CloudBackupSnapshot =
        withContext(Dispatchers.IO) {
            val bandsRaw = safeFlowFirst(dataStoreManager.equalizerBands, "")
            val parsedBands =
                bandsRaw
                    .split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }

            val prefs =
                CloudPreferencesSnapshot(
                    quality = safeFlowFirst(dataStoreManager.quality, ""),
                    downloadQuality = safeFlowFirst(dataStoreManager.downloadQuality, ""),
                    equalizerEnabled = safeFlowFirst(dataStoreManager.equalizerEnabled, "") == TRUE,
                    equalizerBands = parsedBands,
                    equalizerPreamp = safeFlowFirst(dataStoreManager.equalizerPreamp, 0f),
                    equalizerAutoEqProfile = safeFlowFirst(dataStoreManager.equalizerAutoEqProfile, ""),
                    stereoWidenEnabled = safeFlowFirst(dataStoreManager.stereoWidenEnabled, "") == TRUE,
                    stereoWidenAmount = safeFlowFirst(dataStoreManager.stereoWidenAmount, 0.5f),
                    delayEnabled = safeFlowFirst(dataStoreManager.delayEnabled, "") == TRUE,
                    delayTimeMs = safeFlowFirst(dataStoreManager.delayTimeMs, 250),
                    delayFeedback = safeFlowFirst(dataStoreManager.delayFeedback, 0.3f),
                    delayMix = safeFlowFirst(dataStoreManager.delayMix, 0.25f),
                    reverbEnabled = safeFlowFirst(dataStoreManager.reverbEnabled, "") == TRUE,
                    reverbPreset = safeFlowFirst(dataStoreManager.reverbPreset, ""),
                    reverbMix = safeFlowFirst(dataStoreManager.reverbMix, 0.25f),
                    eightDEnabled = safeFlowFirst(dataStoreManager.eightDEnabled, "") == TRUE,
                    eightDDirection = safeFlowFirst(dataStoreManager.eightDDirection, ""),
                    eightDPeriodSeconds = safeFlowFirst(dataStoreManager.eightDPeriodSeconds, 10f),
                    normalizeVolume = safeFlowFirst(dataStoreManager.normalizeVolume, "") == TRUE,
                    skipSilent = safeFlowFirst(dataStoreManager.skipSilent, "") == TRUE,
                    crossfadeEnabled = safeFlowFirst(dataStoreManager.crossfadeEnabled, "") == TRUE,
                    crossfadeDuration = safeFlowFirst(dataStoreManager.crossfadeDuration, 0),
                    crossfadeDjMode = safeFlowFirst(dataStoreManager.crossfadeDjMode, "") == TRUE,
                    crossfadeSkipAlbum = safeFlowFirst(dataStoreManager.crossfadeSkipAlbum, "") == TRUE,
                    saveStateOfPlayback = safeFlowFirst(dataStoreManager.saveStateOfPlayback, TRUE) == TRUE,
                    endlessQueue = safeFlowFirst(dataStoreManager.endlessQueue, TRUE) == TRUE,
                    radioAudioOnly = safeFlowFirst(dataStoreManager.radioAudioOnly, "") == TRUE,
                    explicitContentEnabled = safeFlowFirst(dataStoreManager.explicitContentEnabled, TRUE) == TRUE,
                    playbackSpeed = safeFlowFirst(dataStoreManager.playbackSpeed, 1f),
                    pitch = safeFlowFirst(dataStoreManager.pitch, 0),
                    themeMode = safeFlowFirst(dataStoreManager.themeMode, DataStoreManager.THEME_MODE_DARK),
                    themeColorSource = safeFlowFirst(dataStoreManager.themeColorSource, DataStoreManager.THEME_COLOR_DEFAULT),
                    customThemeColor = safeFlowFirst(dataStoreManager.customThemeColor, DataStoreManager.DEFAULT_THEME_COLOR_HEX),
                    nowPlayingStyle = safeFlowFirst(dataStoreManager.nowPlayingStyle, DataStoreManager.NOW_PLAYING_STYLE_APPLE_MUSIC),
                    lyricsStyle = safeFlowFirst(dataStoreManager.lyricsStyle, DataStoreManager.LYRICS_STYLE_APPLE_MUSIC),
                    lyricsProvider = safeFlowFirst(dataStoreManager.lyricsProvider, DataStoreManager.SIMPMUSIC),
                    lyricsOffsetMs = safeFlowFirst(dataStoreManager.lyricsOffsetMs, 0),
                    romanizationLanguages = safeFlowFirst(dataStoreManager.romanizationLanguages, ""),
                    enableLiquidGlass = safeFlowFirst(dataStoreManager.enableLiquidGlass, TRUE) == TRUE,
                    localTrackingEnabled = safeFlowFirst(dataStoreManager.localTrackingEnabled, "") == TRUE,
                    perDeviceAudioProfilesEnabled = safeFlowFirst(dataStoreManager.perDeviceAudioProfilesEnabledFlow(), false),
                    deviceAudioProfiles = safeFlowFirst(dataStoreManager.deviceAudioProfilesFlow(), emptyList()),
                    rollingOfflineCacheEnabled = safeFlowFirst(dataStoreManager.rollingOfflineCacheEnabledFlow(), true),
                    rollingOfflineCacheLimitMb =
                        safeFlowFirst(
                            dataStoreManager.rollingOfflineCacheLimitMbFlow(),
                            OfflineCacheAndSyncSettings.DEFAULT_ROLLING_CACHE_MB,
                        ),
                    wifiAutoSyncEnabled = safeFlowFirst(dataStoreManager.wifiAutoSyncEnabledFlow(), true),
                    wifiAutoSyncPlaylists = safeFlowFirst(dataStoreManager.wifiAutoSyncPlaylistsFlow(), emptyList()),
                    listenTogetherBlocklist = safeFlowFirst(dataStoreManager.getString(ListenTogetherPrefs.BLOCKLIST), "") ?: "",
                )

            val totalPlays = runCatching { analyticsRepository.getTotalPlaybackEventCount().first() }.getOrDefault(0L)
            val totalSeconds = runCatching { analyticsRepository.getTotalListeningTimeInSeconds().first() }.getOrDefault(0L)
            val totalArtists = runCatching { analyticsRepository.getTotalEventArtistCount().first() }.getOrDefault(0L)
            val recentEvents =
                runCatching {
                    analyticsRepository
                        .getPlaybackEventsByOffset(0, MAX_EVENTS_TO_BACKUP)
                        .first()
                        .map { ev ->
                            CloudPlaybackEvent(
                                videoId = ev.videoId,
                                albumBrowseId = ev.albumBrowseId,
                                durationSecond = ev.durationSecond,
                                listenedSecond = ev.listenedSecond,
                                timestampIso = ev.timestamp.toString(),
                                syncId = ev.syncId,
                            )
                        }
                }.getOrDefault(emptyList())

            val topSongs =
                runCatching {
                    songRepository
                        .getMostPlayedSongs()
                        .first()
                        .take(MAX_TOP_SONGS_TO_BACKUP)
                        .map { song ->
                            CloudStatSong(
                                videoId = song.videoId,
                                title = song.title,
                                artists = song.artistName.orEmpty(),
                                totalPlayTimeMs = song.totalPlayTime,
                                liked = song.liked,
                            )
                        }
                }.getOrDefault(emptyList())

            val likedVideoIds =
                runCatching {
                    songRepository
                        .getLikedSongs()
                        .first()
                        .take(MAX_LIKED_TO_BACKUP)
                        .map { it.videoId }
                }.getOrDefault(emptyList())

            val playlists =
                runCatching {
                    localPlaylistRepository
                        .getAllLocalPlaylists()
                        .first()
                        .map { pl ->
                            val trackIds =
                                runCatching { localPlaylistRepository.getListTrackVideoId(pl.id) }
                                    .getOrDefault(pl.tracks.orEmpty())
                            CloudLocalPlaylistSummary(
                                title = pl.title,
                                thumbnail = pl.thumbnail,
                                trackVideoIds = trackIds,
                            )
                        }
                }.getOrDefault(emptyList())

            CloudBackupSnapshot(
                createdAtEpochMs = clockNowMs(),
                deviceName = deviceNameProvider(),
                appVersion = appVersionProvider(),
                preferences = prefs,
                listeningStats =
                    CloudListeningStatsSnapshot(
                        totalPlayCount = totalPlays,
                        totalListeningSeconds = totalSeconds,
                        totalUniqueArtists = totalArtists,
                        topSongs = topSongs,
                        recentEvents = recentEvents,
                        likedVideoIds = likedVideoIds,
                        localPlaylists = playlists,
                    ),
            )
        }

    suspend fun applySnapshot(snapshot: CloudBackupSnapshot): CloudRestoreResult =
        withContext(Dispatchers.IO) {
            val p = snapshot.preferences
            runCatching { if (p.quality.isNotBlank()) dataStoreManager.setQuality(p.quality) }
            runCatching { if (p.downloadQuality.isNotBlank()) dataStoreManager.setDownloadQuality(p.downloadQuality) }
            runCatching { dataStoreManager.setEqualizerEnabled(p.equalizerEnabled) }
            runCatching { if (p.equalizerBands.isNotEmpty()) dataStoreManager.setEqualizerBands(p.equalizerBands) }
            runCatching { dataStoreManager.setEqualizerPreamp(p.equalizerPreamp) }
            runCatching {
                if (p.equalizerAutoEqProfile.contains("\n")) {
                    val label = p.equalizerAutoEqProfile.substringBefore("\n")
                    val gains = p.equalizerAutoEqProfile.substringAfter("\n").split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (label.isNotBlank() && gains.isNotEmpty()) {
                        dataStoreManager.setEqualizerAutoEqProfile(label, gains)
                    }
                }
            }
            runCatching { dataStoreManager.setStereoWidenEnabled(p.stereoWidenEnabled) }
            runCatching { dataStoreManager.setStereoWidenAmount(p.stereoWidenAmount) }
            runCatching { dataStoreManager.setDelayEnabled(p.delayEnabled) }
            runCatching { dataStoreManager.setDelayTimeMs(p.delayTimeMs) }
            runCatching { dataStoreManager.setDelayFeedback(p.delayFeedback) }
            runCatching { dataStoreManager.setDelayMix(p.delayMix) }
            runCatching { dataStoreManager.setReverbEnabled(p.reverbEnabled) }
            runCatching {
                if (p.reverbPreset.isNotBlank()) {
                    ReverbPreset.entries.firstOrNull { it.name == p.reverbPreset }?.let {
                        dataStoreManager.setReverbPreset(it)
                    }
                }
            }
            runCatching { dataStoreManager.setReverbMix(p.reverbMix) }
            runCatching { dataStoreManager.setEightDEnabled(p.eightDEnabled) }
            runCatching {
                if (p.eightDDirection.isNotBlank()) {
                    EightDDirection.entries.firstOrNull { it.name == p.eightDDirection }?.let {
                        dataStoreManager.setEightDDirection(it)
                    }
                }
            }
            runCatching { dataStoreManager.setEightDPeriodSeconds(p.eightDPeriodSeconds) }
            runCatching { dataStoreManager.setNormalizeVolume(p.normalizeVolume) }
            runCatching { dataStoreManager.setSkipSilent(p.skipSilent) }
            runCatching { dataStoreManager.setCrossfadeEnabled(p.crossfadeEnabled) }
            runCatching { dataStoreManager.setCrossfadeDuration(p.crossfadeDuration) }
            runCatching { dataStoreManager.setCrossfadeDjMode(p.crossfadeDjMode) }
            runCatching { dataStoreManager.setCrossfadeSkipAlbum(p.crossfadeSkipAlbum) }
            runCatching { dataStoreManager.setSaveStateOfPlayback(p.saveStateOfPlayback) }
            runCatching { dataStoreManager.setEndlessQueue(p.endlessQueue) }
            runCatching { dataStoreManager.setRadioAudioOnly(p.radioAudioOnly) }
            runCatching { dataStoreManager.setExplicitContentEnabled(p.explicitContentEnabled) }
            runCatching { dataStoreManager.setPlaybackSpeed(p.playbackSpeed) }
            runCatching { dataStoreManager.setPitch(p.pitch) }
            runCatching { if (p.themeMode.isNotBlank()) dataStoreManager.setThemeMode(p.themeMode) }
            runCatching { if (p.themeColorSource.isNotBlank()) dataStoreManager.setThemeColorSource(p.themeColorSource) }
            runCatching { if (p.customThemeColor.isNotBlank()) dataStoreManager.setCustomThemeColor(p.customThemeColor) }
            runCatching { if (p.nowPlayingStyle.isNotBlank()) dataStoreManager.setNowPlayingStyle(p.nowPlayingStyle) }
            runCatching { if (p.lyricsStyle.isNotBlank()) dataStoreManager.setLyricsStyle(p.lyricsStyle) }
            runCatching { if (p.lyricsProvider.isNotBlank()) dataStoreManager.setLyricsProvider(p.lyricsProvider) }
            runCatching { dataStoreManager.setLyricsOffsetMs(p.lyricsOffsetMs) }
            runCatching { dataStoreManager.setRomanizationLanguages(p.romanizationLanguages) }
            runCatching { dataStoreManager.setEnableLiquidGlass(p.enableLiquidGlass) }
            runCatching { dataStoreManager.setLocalTrackingEnabled(p.localTrackingEnabled) }
            runCatching { dataStoreManager.setPerDeviceAudioProfilesEnabled(p.perDeviceAudioProfilesEnabled) }
            p.deviceAudioProfiles.forEach { profile ->
                runCatching { dataStoreManager.saveDeviceAudioProfile(profile) }
            }
            runCatching { dataStoreManager.setRollingOfflineCacheEnabled(p.rollingOfflineCacheEnabled) }
            runCatching { dataStoreManager.setRollingOfflineCacheLimitMb(p.rollingOfflineCacheLimitMb) }
            runCatching { dataStoreManager.setWifiAutoSyncEnabled(p.wifiAutoSyncEnabled) }
            val existingAutoSync = safeFlowFirst(dataStoreManager.wifiAutoSyncPlaylistsFlow(), emptyList()).map { it.id to it.kind }.toSet()
            p.wifiAutoSyncPlaylists.forEach { ref ->
                if ((ref.id to ref.kind) !in existingAutoSync) {
                    runCatching { dataStoreManager.toggleWifiAutoSyncPlaylist(ref) }
                }
            }
            if (p.listenTogetherBlocklist.isNotBlank()) {
                runCatching { dataStoreManager.putString(ListenTogetherPrefs.BLOCKLIST, p.listenTogetherBlocklist) }
            }

            // Merge missing playback events cleanly without double-counting duplicates.
            val existingEvents =
                runCatching { analyticsRepository.getPlaybackEventsByOffset(0, MAX_EVENTS_TO_BACKUP).first() }
                    .getOrDefault(emptyList())
            val existingSyncIds = existingEvents.mapNotNull { it.syncId }.toHashSet()
            val existingKeys = existingEvents.map { "${it.videoId}|${it.timestamp}|${it.listenedSecond}" }.toHashSet()
            var restoredEvents = 0
            for (ev in snapshot.listeningStats.recentEvents) {
                if (ev.videoId.isBlank()) continue
                val key = "${ev.videoId}|${ev.timestampIso}|${ev.listenedSecond}"
                if ((ev.syncId != null && ev.syncId in existingSyncIds) || key in existingKeys) continue
                runCatching {
                    analyticsRepository
                        .insertPlaybackEvent(
                            videoId = ev.videoId,
                            channelIds = emptyList(),
                            albumBrowseId = ev.albumBrowseId,
                            durationSecond = ev.durationSecond,
                            listenedSecond = ev.listenedSecond,
                        ).firstOrNull()
                    restoredEvents++
                    existingKeys += key
                    ev.syncId?.let { existingSyncIds += it }
                }
            }

            // Re-apply liked flags for tracks already known locally.
            var restoredLikes = 0
            for (vid in snapshot.listeningStats.likedVideoIds) {
                if (vid.isBlank()) continue
                runCatching {
                    val existing = songRepository.getSongById(vid).firstOrNull()
                    if (existing != null && !existing.liked) {
                        songRepository.updateLikeStatus(vid, 1)
                        restoredLikes++
                    }
                }
            }

            // Restore any missing local playlists by title.
            val existingPlaylistTitles =
                runCatching { localPlaylistRepository.getAllLocalPlaylists().first().map { it.title.lowercase() }.toSet() }
                    .getOrDefault(emptySet())
            var restoredPlaylists = 0
            for (pl in snapshot.listeningStats.localPlaylists) {
                if (pl.title.isBlank() || pl.title.lowercase() in existingPlaylistTitles) continue
                runCatching {
                    localPlaylistRepository
                        .insertLocalPlaylist(
                            localPlaylist =
                                LocalPlaylistEntity(
                                    title = pl.title,
                                    thumbnail = pl.thumbnail,
                                    tracks = pl.trackVideoIds,
                                ),
                            successMessage = "Restored ${pl.title}",
                        ).firstOrNull()
                    restoredPlaylists++
                }
            }

            CloudRestoreResult(
                restoredPreferences = true,
                restoredEventsCount = restoredEvents,
                restoredLikedCount = restoredLikes,
                restoredPlaylistsCount = restoredPlaylists,
                snapshotCreatedAtEpochMs = snapshot.createdAtEpochMs,
                sourceDeviceName = snapshot.deviceName,
            )
        }

    private suspend fun <T> safeFlowFirst(
        flow: Flow<T>,
        default: T,
    ): T = runCatching { flow.first() }.getOrDefault(default)
}
