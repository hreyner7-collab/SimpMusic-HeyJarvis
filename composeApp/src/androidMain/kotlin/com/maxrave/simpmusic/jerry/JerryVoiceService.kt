package com.maxrave.simpmusic.jerry

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.tan

class JerryVoiceService : Service(), KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val searchRepository: SearchRepository by inject()
    private val songRepository: SongRepository by inject()
    private val streamRepository: StreamRepository by inject()
    private val playlistRepository: PlaylistRepository by inject()
    private val localPlaylistRepository: LocalPlaylistRepository by inject()
    private val albumRepository: AlbumRepository by inject()
    private val artistRepository: ArtistRepository by inject()
    private val homeRepository: HomeRepository by inject()
    private val dataStoreManager: DataStoreManager by inject()
    private val accountRepository: AccountRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val session = JerryWakeSession()
    private lateinit var coordinator: JerryCommandCoordinator

    private val audioLock = Any()
    private var activePipeline: AudioPipeline? = null
    private var captureJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var stopping = false
    @Volatile private var ttsPaused = false
    @Volatile private var ttsPausedAtElapsedMs = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentUtteranceId: String? = null
    private var utteranceCounter = 0L
    private var savedVolume = 1f
    private var ducked = false

    private val resetToListeningRunnable = Runnable {
        if (JarvisStatus.state.value.phase == JarvisPhase.Result) {
            JarvisStatus.set(JarvisPhase.Listening)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Started via startForegroundService: must enter foreground before stopping.
            startForegroundCompat(buildNotification("Hey Jarvis needs microphone permission"))
            stopSelf()
            return START_NOT_STICKY
        }
        if (captureJob?.isActive == true) {
            val text = if (JarvisStatus.state.value.phase == JarvisPhase.Off) {
                "Starting Hey Jarvis…"
            } else {
                "Hey Jarvis is listening"
            }
            startForegroundCompat(buildNotification(text))
            return START_NOT_STICKY
        }

        stopping = false
        startForegroundCompat(buildNotification("Starting Hey Jarvis…"))
        listening.value = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
        coordinator = JerryCommandCoordinator(
            JerryMediaPlayback(
                handler,
                searchRepository,
                songRepository,
                streamRepository,
                AndroidVolume(this),
                playlistRepository = playlistRepository,
                localPlaylistRepository = localPlaylistRepository,
                albumRepository = albumRepository,
                scope = scope,
                artistRepository = artistRepository,
                homeRepository = homeRepository,
                dataStoreManager = dataStoreManager,
                accountRepository = accountRepository,
            ),
        )
        if (tts == null) initTts()

        watchdogJob?.cancel()
        watchdogJob = scope.launch { watchdog() }

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                runCaptureLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "microphone error"
                val notificationText = if (JarvisStatus.state.value.phase == JarvisPhase.Off) {
                    "Hey Jarvis could not start"
                } else {
                    "Hey Jarvis microphone error"
                }
                main.post {
                    if (stopping) return@post
                    restoreDuck()
                    updateNotification(notificationText)
                    JarvisStatus.set(JarvisPhase.Error, message)
                }
                delay(2000)
                main.post { if (!stopping) stopSelf() }
            }
        }
        // Listening is user-initiated; do not auto-restart a microphone service from background.
        return START_NOT_STICKY
    }

    private suspend fun runCaptureLoop() {
        var loadedModel: Model? = null
        var grammarRecognizer: Recognizer? = null
        var freeRecognizer: Recognizer? = null
        try {
            val modelDir = ensureModel()
            val m = Model(modelDir.absolutePath)
            loadedModel = m
            val grm = runCatching { Recognizer(m, SAMPLE_RATE.toFloat(), JerryGrammar.stage1GrammarJson) }
                .getOrElse { Recognizer(m, SAMPLE_RATE.toFloat()) }
            grammarRecognizer = grm
            val free = Recognizer(m, SAMPLE_RATE.toFloat())
            freeRecognizer = free

            val pipeline = openAudioPipeline()
            val stillActive = currentCoroutineContext().isActive
            synchronized(audioLock) {
                if (stopping || !stillActive) {
                    pipeline.release()
                    return
                }
                activePipeline = pipeline
            }

            main.post {
                if (!stopping) {
                    updateNotification("Hey Jarvis is listening")
                    JarvisStatus.set(JarvisPhase.Listening)
                }
            }

            val chunk = ByteArray(CHUNK_BYTES)
            val freeChunk = ByteArray(CHUNK_BYTES)
            val utterancePcm = ByteArray(MAX_UTTERANCE_BYTES)
            val highPass = HighPassFilter80Hz()
            var utteranceLen = 0
            var wasPaused = false
            var lastPartial = ""
            var lastSpeechAtMs = 0L
            var lastPartialChangedAtMs = 0L
            var noiseFloor = 250f

            while (!stopping && currentCoroutineContext().isActive) {
                val n = pipeline.record.read(chunk, 0, CHUNK_BYTES)
                if (n < 0) {
                    if (stopping || !currentCoroutineContext().isActive) break
                    throw IOException("AudioRecord read error: $n")
                }
                if (n == 0) {
                    if (pipeline.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                    delay(10)
                    continue
                }

                if (ttsPaused) {
                    if (SystemClock.elapsedRealtime() - ttsPausedAtElapsedMs > TTS_PAUSE_TIMEOUT_MS) {
                        ttsPaused = false
                    } else {
                        if (!wasPaused) {
                            wasPaused = true
                            utteranceLen = 0
                            lastPartial = ""
                            grm.reset()
                            free.reset()
                        }
                        continue
                    }
                }
                if (wasPaused) {
                    wasPaused = false
                    highPass.reset()
                }

                highPass.processInPlace(chunk, n)
                val chunkPeak = computePcmPeak(chunk, n)
                val speechThreshold = maxOf(450f, noiseFloor * 2.5f)
                val isSpeech = chunkPeak.toFloat() >= speechThreshold
                val nowElapsed = SystemClock.elapsedRealtime()
                if (isSpeech) {
                    lastSpeechAtMs = nowElapsed
                } else {
                    noiseFloor = if (chunkPeak < noiseFloor) {
                        noiseFloor * 0.90f + chunkPeak * 0.10f
                    } else {
                        minOf(2500f, noiseFloor * 0.99f + chunkPeak * 0.01f)
                    }
                }
                utteranceLen = appendUtterance(utterancePcm, utteranceLen, chunk, n)

                if (grm.acceptWaveForm(chunk, n)) {
                    val grmText = extractJsonField(grm.result, "text")
                    lastPartial = ""
                    val (wasAwaiting, grmHasWake) = withContext(Dispatchers.Main) {
                        session.awaitingCommand to (grmText.isNotBlank() && session.hasWakeWord(grmText))
                    }
                    val needsFree = (wasAwaiting || grmHasWake) &&
                        (grmText.contains("[unk]", ignoreCase = true) || JerryGrammar.hasIncompleteCommandTail(grmText))
                    val finalText = if (needsFree) {
                        recognizeFree(free, freeChunk, utterancePcm, utteranceLen).ifBlank { grmText }
                    } else {
                        grmText
                    }
                    utteranceLen = 0
                    if (finalText.isNotBlank()) {
                        handleFinalOnMain(finalText, SystemClock.elapsedRealtime())
                    }
                } else {
                    val partial = extractJsonField(grm.partialResult, "partial")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        lastPartialChangedAtMs = nowElapsed
                    }
                    if (partial.isNotBlank() && partial.replace(UNK_REGEX, "").isNotBlank()) {
                        val endOfSpeech =
                            lastSpeechAtMs > 0L &&
                                (nowElapsed - lastSpeechAtMs >= END_OF_SPEECH_SILENCE_MS) &&
                                (nowElapsed - lastPartialChangedAtMs >= 240L)
                        val instantHandled = handlePartialOnMain(partial, nowElapsed, allowInstantCommit = endOfSpeech)
                        if (instantHandled) {
                            grm.reset()
                            free.reset()
                            utteranceLen = 0
                            lastPartial = ""
                        }
                    }
                }
            }
        } finally {
            synchronized(audioLock) {
                activePipeline?.release()
                activePipeline = null
            }
            grammarRecognizer?.let { runCatching { it.close() } }
            freeRecognizer?.let { runCatching { it.close() } }
            loadedModel?.let { runCatching { it.close() } }
        }
    }

    private fun openAudioPipeline(): AudioPipeline {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferBytes = maxOf(if (minBuf > 0) minBuf * 2 else 0, CHUNK_BYTES * 8)

        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes)
            } catch (_: Exception) {
                null
            } ?: continue

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                continue
            }

            val sessionId = record.audioSessionId
            val aec = if (AcousticEchoCanceler.isAvailable()) {
                runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
            } else {
                null
            }

            val started = runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (started) {
                return AudioPipeline(record, aec)
            }

            aec?.let { runCatching { it.release() } }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        throw IllegalStateException("Failed to initialize AudioRecord")
    }

    private fun appendUtterance(dst: ByteArray, dstLen: Int, src: ByteArray, srcLen: Int): Int {
        if (srcLen <= 0) return dstLen
        if (srcLen >= dst.size) {
            System.arraycopy(src, srcLen - dst.size, dst, 0, dst.size)
            return dst.size
        }
        var len = dstLen
        if (len + srcLen > dst.size) {
            val drop = (len + srcLen) - dst.size
            val keep = len - drop
            if (keep > 0) System.arraycopy(dst, drop, dst, 0, keep)
            len = maxOf(0, keep)
        }
        System.arraycopy(src, 0, dst, len, srcLen)
        return len + srcLen
    }

    private fun recognizeFree(
        recognizer: Recognizer,
        chunkBuf: ByteArray,
        pcm: ByteArray,
        lengthBytes: Int,
    ): String {
        if (lengthBytes <= 0) return ""
        recognizer.reset()
        val sb = StringBuilder()
        var off = 0
        while (off < lengthBytes) {
            val len = minOf(chunkBuf.size, lengthBytes - off)
            System.arraycopy(pcm, off, chunkBuf, 0, len)
            if (recognizer.acceptWaveForm(chunkBuf, len)) {
                val part = extractJsonField(recognizer.result, "text")
                if (part.isNotBlank()) sb.append(part).append(' ')
            }
            off += len
        }
        val tail = extractJsonField(recognizer.finalResult, "text")
        if (tail.isNotBlank()) sb.append(tail)
        return sb.toString().trim()
    }

    private suspend fun handlePartialOnMain(
        partial: String,
        nowMs: Long,
        allowInstantCommit: Boolean,
    ): Boolean =
        withContext(Dispatchers.Main) {
            if (stopping) return@withContext false
            if (session.observePartial(partial, nowMs)) {
                duck()
                JarvisStatus.set(JarvisPhase.Awake)
            }
            if (!allowInstantCommit || !shouldTryInstantPartial(partial)) return@withContext false
            val command = session.acceptInstantPartial(partial, nowMs) ?: return@withContext false
            dispatchCommandOnMain(command)
            true
        }

    private suspend fun handleFinalOnMain(text: String, nowMs: Long) {
        withContext(Dispatchers.Main) {
            if (stopping) return@withContext
            val command = session.acceptFinal(text, nowMs)
            if (command != null) {
                dispatchCommandOnMain(command)
            } else if (!session.awaitingCommand) {
                restoreDuck()
                if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                    JarvisStatus.set(JarvisPhase.Listening)
                }
            }
        }
    }

    // Main-thread confined: executes a recognized command and restores ducking/status on completion.
    private fun dispatchCommandOnMain(command: JerryCommand) {
        main.removeCallbacks(resetToListeningRunnable)
        JarvisStatus.set(JarvisPhase.Thinking)
        scope.launch {
            val message = coordinator.execute(command)
            main.post {
                if (stopping) return@post
                restoreDuck()
                JarvisStatus.set(JarvisPhase.Result, message)
                speak(message)
                main.removeCallbacks(resetToListeningRunnable)
                main.postDelayed(resetToListeningRunnable, RESULT_RESET_DELAY_MS)
            }
        }
    }

    private fun shouldTryInstantPartial(partial: String): Boolean =
        !JerryGrammar.hasIncompleteCommandTail(partial)

    private fun extractJsonField(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key, "") }.getOrDefault("")

    private suspend fun watchdog() {
        while (scope.isActive && !stopping) {
            delay(400)
            main.post {
                if (stopping) return@post
                if (session.expire(SystemClock.elapsedRealtime())) {
                    restoreDuck()
                    if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                        JarvisStatus.set(JarvisPhase.Listening)
                    }
                }
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { unpauseAfterTts(utteranceId) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { unpauseAfterTts(utteranceId) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    main.post { unpauseAfterTts(utteranceId) }
                }
            })
        }
    }

    private fun unpauseAfterTts(utteranceId: String?) {
        if (utteranceId == null || utteranceId == currentUtteranceId) {
            currentUtteranceId = null
            ttsPaused = false
        }
    }

    private fun ensureModel(): File {
        val root = File(filesDir, "jerry-model")
        val marker = File(root, ".ready")
        if (marker.exists()) return File(root, "vosk-model-small-en-us-0.15")
        root.mkdirs()
        assets.open("jerry/model.zip").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(root, entry.name)
                    if (!out.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                        throw SecurityException("Bad model entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        marker.createNewFile()
        return File(root, "vosk-model-small-en-us-0.15")
    }

    // Main-thread confined: all duck/restore and session access happens on the main looper.
    private fun duck() {
        if (ducked) return
        ducked = true
        savedVolume = handler.player.volume
        if (savedVolume > 0.25f) {
            handler.player.volume = 0.25f
        }
    }

    private fun restoreDuck() {
        if (!ducked) return
        ducked = false
        handler.player.volume = savedVolume
    }

    private fun speak(text: String) {
        if (!ttsReady || stopping) return
        val id = "jerry-${++utteranceCounter}"
        currentUtteranceId = id
        ttsPausedAtElapsedMs = SystemClock.elapsedRealtime()
        ttsPaused = true
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            currentUtteranceId = null
            ttsPaused = false
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JerryVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopping = true
        listening.value = false
        JarvisStatus.set(JarvisPhase.Off)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        // onDestroy runs on the main thread: restore synchronously and stop AudioRecord before cancelling.
        restoreDuck()
        currentUtteranceId = null
        ttsPaused = false
        synchronized(audioLock) { activePipeline?.stop() }
        watchdogJob?.cancel()
        captureJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private class AudioPipeline(
        val record: AudioRecord,
        private val aec: AcousticEchoCanceler?,
    ) {
        fun stop() {
            runCatching { record.stop() }
        }

        fun release() {
            runCatching { record.stop() }
            runCatching { record.release() }
            aec?.let { runCatching { it.release() } }
        }
    }

    private class HighPassFilter80Hz {
        private val b0: Float
        private val a1: Float
        private var prevX = 0f
        private var prevY = 0f

        init {
            val k = tan(PI * HIGH_PASS_HZ / SAMPLE_RATE).toFloat()
            b0 = 1f / (1f + k)
            a1 = (1f - k) / (1f + k)
        }

        fun processInPlace(pcm: ByteArray, lengthBytes: Int) {
            val evenLen = lengthBytes and 1.inv()
            var i = 0
            while (i < evenLen) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val x = ((hi shl 8) or lo).toFloat()
                val rawY = b0 * (x - prevX) + a1 * prevY
                val y = if (rawY.isFinite()) rawY else 0f
                prevX = x
                prevY = y
                val clamped = y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[i] = (clamped and 0xFF).toByte()
                pcm[i + 1] = ((clamped ushr 8) and 0xFF).toByte()
                i += 2
            }
        }

        fun reset() {
            prevX = 0f
            prevY = 0f
        }
    }

    companion object {
        const val ACTION_STOP = "com.maxrave.simpmusic.jerry.STOP"
        const val PREFS = "jerry"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL = "jerry_listening"
        private const val NOTIF_ID = 4711

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 1280 // 40ms @ 16kHz 16-bit mono
        private const val MAX_UTTERANCE_BYTES = SAMPLE_RATE * 2 * 10
        private const val HIGH_PASS_HZ = 80.0
        private const val RESULT_RESET_DELAY_MS = 2500L
        private const val TTS_PAUSE_TIMEOUT_MS = 8000L
        private const val END_OF_SPEECH_SILENCE_MS = 380L

        private fun computePcmPeak(pcm: ByteArray, lengthBytes: Int): Int {
            var peak = 0
            val evenLen = lengthBytes and 1.inv()
            var i = 0
            while (i < evenLen) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = (hi shl 8) or lo
                val a = if (s < 0) -s else s
                if (a > peak) peak = a
                i += 2
            }
            return peak
        }

        private val UNK_REGEX = Regex("\\[unk\\]", RegexOption.IGNORE_CASE)

        val listening = MutableStateFlow(false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JerryVoiceService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JerryVoiceService::class.java).setAction(ACTION_STOP))
        }
    }
}
