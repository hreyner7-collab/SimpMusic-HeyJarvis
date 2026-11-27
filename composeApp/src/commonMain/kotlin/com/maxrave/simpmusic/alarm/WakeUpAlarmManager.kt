package com.maxrave.simpmusic.alarm

import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "WakeUpAlarmManager"
private const val FADE_STEP_MS = 250L
private const val SNOOZE_ID_PREFIX = "snooze::"

/**
 * Manages Musical Wake-Up Alarms with gentle exponential fade-in volume ramps,
 * DataStore persistence, snooze, dismiss, and test-ring support.
 */
@OptIn(ExperimentalTime::class)
class WakeUpAlarmManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val scheduler: WakeUpAlarmScheduler = WakeUpAlarmScheduler(),
    private val clockNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val _alarms = MutableStateFlow<List<WakeUpAlarm>>(emptyList())
    val alarms: StateFlow<List<WakeUpAlarm>> = _alarms.asStateFlow()

    private val _activeRingingAlarm = MutableStateFlow<WakeUpAlarm?>(null)
    val activeRingingAlarm: StateFlow<WakeUpAlarm?> = _activeRingingAlarm.asStateFlow()

    private val _currentFadeProgress = MutableStateFlow(0f)
    val currentFadeProgress: StateFlow<Float> = _currentFadeProgress.asStateFlow()

    private val _nextScheduledEpochMs = MutableStateFlow<Long?>(null)
    val nextScheduledEpochMs: StateFlow<Long?> = _nextScheduledEpochMs.asStateFlow()

    private val _availablePlaylists = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val availablePlaylists: StateFlow<List<Pair<Long, String>>> = _availablePlaylists.asStateFlow()

    private val _availableSongs = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableSongs: StateFlow<List<Pair<String, String>>> = _availableSongs.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var started = false
    private var fadeJob: Job? = null
    private var scheduledAlarmId: String? = null
    private var preAlarmVolume: Float? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            loadAlarmsFromStore()
            rescheduleNextAlarm()
        }
    }

    fun stop() {
        started = false
        fadeJob?.cancel()
        fadeJob = null
        scheduler.cancelAll()
        scheduledAlarmId = null
        _nextScheduledEpochMs.value = null
    }

    fun refreshTargets() {
        scope.launch {
            runCatching {
                val playlists = localPlaylistRepository.getAllLocalPlaylists().firstOrNull().orEmpty()
                _availablePlaylists.value = playlists.map { it.id to it.title }
            }
            runCatching {
                val liked = songRepository.getLikedSongs().firstOrNull().orEmpty()
                val recent = songRepository.getAllSongs(40).firstOrNull().orEmpty()
                val combined = (liked + recent).distinctBy { it.videoId }.take(50)
                _availableSongs.value =
                    combined.map { song ->
                        val artist = song.artistName?.joinToString(", ").orEmpty()
                        val display = if (artist.isNotBlank()) "${song.title} — $artist" else song.title
                        song.videoId to display
                    }
            }
        }
    }

    fun addAlarm(alarm: WakeUpAlarm) {
        val withId =
            alarm
                .copy(
                    id = alarm.id.ifBlank { "alarm-${clockNowMs()}-${Random.nextInt(1000, 9999)}" },
                ).normalized()
        val updated = (_alarms.value + withId).sortedWith(compareBy({ it.hour }, { it.minute }, { it.label }))
        applyAndPersist(updated)
    }

    fun updateAlarm(alarm: WakeUpAlarm) {
        val normalized = alarm.normalized()
        val updated =
            _alarms.value
                .map { if (it.id == normalized.id) normalized else it }
                .sortedWith(compareBy({ it.hour }, { it.minute }, { it.label }))
        applyAndPersist(updated)
    }

    fun toggleAlarm(
        alarmId: String,
        enabled: Boolean,
    ) {
        val updated = _alarms.value.map { if (it.id == alarmId) it.copy(enabled = enabled) else it }
        applyAndPersist(updated)
    }

    fun deleteAlarm(alarmId: String) {
        scheduler.cancel(alarmId)
        scheduler.cancel("$SNOOZE_ID_PREFIX$alarmId")
        if (_activeRingingAlarm.value?.id == alarmId) {
            dismissActiveAlarm(pausePlayback = true)
        }
        val updated = _alarms.value.filterNot { it.id == alarmId }
        applyAndPersist(updated)
    }

    fun testAlarm(alarmId: String) {
        triggerAlarm(alarmId = alarmId, isTest = true)
    }

    /**
     * Triggers playback for [alarmId] with a gentle exponential volume fade-in.
     * Safe to invoke from Android BroadcastReceiver, desktop timer thread, or UI test button.
     */
    fun triggerAlarm(
        alarmId: String,
        isTest: Boolean = false,
    ) {
        scope.launch {
            val cleanId = alarmId.removePrefix(SNOOZE_ID_PREFIX)
            val fromSnooze = alarmId.startsWith(SNOOZE_ID_PREFIX)
            if (_alarms.value.isEmpty()) {
                loadAlarmsFromStore()
            }
            val alarm = _alarms.value.firstOrNull { it.id == cleanId } ?: return@launch
            if (!isTest && !fromSnooze && !alarm.enabled) return@launch

            Logger.i(TAG, "Triggering alarm '${alarm.label}' (id=${alarm.id}, test=$isTest, snooze=$fromSnooze)")
            _activeRingingAlarm.value = alarm
            _currentFadeProgress.value = 0f
            _statusMessage.value = "Ringing: ${alarm.label}"

            // Disable one-shot alarms once fired (unless this is a manual test ring).
            if (!isTest && !fromSnooze && !alarm.isRepeating) {
                val updated = _alarms.value.map { if (it.id == alarm.id) it.copy(enabled = false) else it }
                _alarms.value = updated
                persistAlarms(updated)
            }
            rescheduleNextAlarm()

            startAlarmPlaybackAndFade(alarm)
        }
    }

    fun snoozeActiveAlarm() {
        val active = _activeRingingAlarm.value ?: return
        fadeJob?.cancel()
        fadeJob = null
        _activeRingingAlarm.value = null
        _currentFadeProgress.value = 0f

        val snoozeTriggerAt = WakeUpAlarmPlanner.computeSnoozeTriggerEpochMs(clockNowMs(), active.snoozeMinutes)
        val snoozeId = "$SNOOZE_ID_PREFIX${active.id}"
        scheduler.schedule(snoozeId, snoozeTriggerAt) { firedId ->
            triggerAlarm(firedId, isTest = false)
        }
        _statusMessage.value = "Snoozed '${active.label}' for ${active.snoozeMinutes} min"

        scope.launch {
            withContext(mainDispatcher) {
                runCatching { mediaPlayerHandler.player.pause() }
            }
        }
    }

    fun dismissActiveAlarm(pausePlayback: Boolean = true) {
        val active = _activeRingingAlarm.value
        fadeJob?.cancel()
        fadeJob = null
        if (active != null) {
            scheduler.cancel("$SNOOZE_ID_PREFIX${active.id}")
        }
        _activeRingingAlarm.value = null
        _currentFadeProgress.value = 0f
        _statusMessage.value = active?.let { "Dismissed '${it.label}'" }

        scope.launch {
            withContext(mainDispatcher) {
                if (pausePlayback) {
                    runCatching { mediaPlayerHandler.player.pause() }
                    preAlarmVolume?.let { previous ->
                        runCatching { mediaPlayerHandler.player.volume = previous.coerceIn(0f, 1f) }
                    }
                }
                preAlarmVolume = null
            }
        }
    }

    private fun applyAndPersist(updated: List<WakeUpAlarm>) {
        _alarms.value = updated
        rescheduleNextAlarm()
        scope.launch {
            persistAlarms(updated)
        }
    }

    private suspend fun loadAlarmsFromStore() {
        val raw = runCatching { dataStoreManager.getString(WakeUpAlarmStoreCodec.DATASTORE_KEY).firstOrNull() }.getOrNull()
        _alarms.value = WakeUpAlarmStoreCodec.decode(raw)
    }

    private suspend fun persistAlarms(list: List<WakeUpAlarm>) {
        runCatching {
            dataStoreManager.putString(WakeUpAlarmStoreCodec.DATASTORE_KEY, WakeUpAlarmStoreCodec.encode(list))
        }.onFailure {
            Logger.w(TAG, "Failed to persist alarms: ${it.message}")
        }
    }

    private fun rescheduleNextAlarm() {
        scheduledAlarmId?.let { scheduler.cancel(it) }
        scheduledAlarmId = null

        val next = WakeUpAlarmPlanner.selectNextAlarm(_alarms.value, clockNowMs(), timeZoneProvider())
        if (next == null) {
            _nextScheduledEpochMs.value = null
            return
        }
        val (alarm, triggerAtMs) = next
        scheduledAlarmId = alarm.id
        _nextScheduledEpochMs.value = triggerAtMs
        scheduler.schedule(alarm.id, triggerAtMs) { firedId ->
            triggerAlarm(firedId, isTest = false)
        }
    }

    private suspend fun startAlarmPlaybackAndFade(alarm: WakeUpAlarm) {
        val tracks = resolveTracksForAlarm(alarm)

        fadeJob?.cancel()
        val initialVolume =
            if (alarm.fadeInSeconds > 0) {
                WakeUpAlarmPlanner.MIN_FADE_START_VOLUME.coerceAtMost(alarm.targetVolume)
            } else {
                alarm.targetVolume
            }

        withContext(mainDispatcher) {
            runCatching {
                if (preAlarmVolume == null) {
                    preAlarmVolume = mediaPlayerHandler.player.volume
                }
                mediaPlayerHandler.player.volume = initialVolume
            }
        }

        if (tracks.isNotEmpty()) {
            try {
                val firstTrack = tracks.first()
                val clickType =
                    if (tracks.size == 1 && alarm.targetType == AlarmTargetType.SPECIFIC_SONG) {
                        Config.SONG_CLICK
                    } else {
                        Config.PLAYLIST_CLICK
                    }
                withContext(mainDispatcher) {
                    mediaPlayerHandler.reset()
                    mediaPlayerHandler.setQueueData(
                        QueueData.Data(
                            listTracks = ArrayList(tracks),
                            firstPlayedTrack = firstTrack,
                            playlistId = "WAKE_UP_ALARM_${alarm.id}",
                            playlistName = alarm.label,
                            playlistType =
                                if (alarm.targetType == AlarmTargetType.LOCAL_PLAYLIST) {
                                    PlaylistType.LOCAL_PLAYLIST
                                } else {
                                    PlaylistType.PLAYLIST
                                },
                            continuation = null,
                        ),
                    )
                    mediaPlayerHandler.loadMediaItem(
                        anyTrack = firstTrack,
                        type = clickType,
                        index = 0,
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load alarm tracks: ${e.message}", e)
            }
        } else {
            // Fallback when library has no saved songs yet: resume saved queue or current player item.
            withContext(mainDispatcher) {
                val restored = runCatching { mediaPlayerHandler.restoreQueueAndPlay() }.getOrDefault(false)
                if (!restored) {
                    runCatching { mediaPlayerHandler.player.play() }
                }
            }
        }

        val totalFadeMs = alarm.fadeInSeconds * 1_000L
        fadeJob =
            scope.launch {
                if (totalFadeMs <= 0L) {
                    withContext(mainDispatcher) {
                        runCatching { mediaPlayerHandler.player.volume = alarm.targetVolume }
                    }
                    _currentFadeProgress.value = 1f
                    return@launch
                }

                val startMs = clockNowMs()
                while (true) {
                    val elapsedMs = (clockNowMs() - startMs).coerceAtLeast(0L)
                    val volume =
                        WakeUpAlarmPlanner.fadeVolumeAt(
                            elapsedMs = elapsedMs,
                            totalFadeMs = totalFadeMs,
                            targetVolume = alarm.targetVolume,
                        )
                    withContext(mainDispatcher) {
                        runCatching { mediaPlayerHandler.player.volume = volume }
                    }
                    val progress = (elapsedMs.toFloat() / totalFadeMs.toFloat()).coerceIn(0f, 1f)
                    _currentFadeProgress.value = progress
                    if (elapsedMs >= totalFadeMs) break
                    delay(FADE_STEP_MS)
                }
            }
    }

    private suspend fun resolveTracksForAlarm(alarm: WakeUpAlarm): List<Track> {
        val primary: List<Track> =
            when (alarm.targetType) {
                AlarmTargetType.LIKED_SONGS -> {
                    runCatching { songRepository.getLikedSongs().first().map { it.toTrack() } }
                        .getOrDefault(emptyList())
                }

                AlarmTargetType.LOCAL_PLAYLIST -> {
                    val playlistId = alarm.targetId?.toLongOrNull()
                    if (playlistId != null) {
                        runCatching { localPlaylistRepository.getFullPlaylistTracks(playlistId).map { it.toTrack() } }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }

                AlarmTargetType.SPECIFIC_SONG -> {
                    val videoId = alarm.targetId?.takeIf { it.isNotBlank() }
                    if (videoId != null) {
                        runCatching { listOfNotNull(songRepository.getSongById(videoId).first()?.toTrack()) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            }

        if (primary.isNotEmpty()) return primary

        // Graceful fallback to liked songs or recent library songs so the alarm never fails silently.
        val likedFallback =
            runCatching { songRepository.getLikedSongs().first().map { it.toTrack() } }
                .getOrDefault(emptyList())
        if (likedFallback.isNotEmpty()) return likedFallback

        return runCatching { songRepository.getAllSongs(30).first().map { it.toTrack() } }
            .getOrDefault(emptyList())
    }
}
