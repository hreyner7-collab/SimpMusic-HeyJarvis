package com.maxrave.simpmusic.jerry

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.repository.AccountRepository
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.mp.KoinPlatform.getKoin
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

/** Desktop "Hey Jarvis": always-on dual-stage offline wake word + command recognition. */
object DesktopJarvis {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_BYTES = 1280 // 40ms chunks at 16kHz 16-bit mono
    private const val CHUNK_SAMPLES = CHUNK_BYTES / 2
    private const val RING_SECONDS = 12
    private const val RING_CAPACITY = SAMPLE_RATE * RING_SECONDS
    private const val DUCK_VOLUME = 0.15f
    private const val SILENCE_TAIL_CHUNKS = 15 // ~600ms quiet tail before finalizing Stage 2 utterance
    private const val END_OF_SPEECH_SILENCE_MS = 520L // responsive end-of-speech gate for instant partials
    private const val MAX_STAGE2_ARMED_MS = 10_000L // allow up to 10s of continuous speech before forced decode
    private const val IDLE_SILENCE_GATE_CHUNKS = 40 // ~1.6s quiet before parking Stage 1 with pre-roll
    private const val PRE_ROLL_COUNT = 12 // ~480ms audio pre-roll so "Hey" is never clipped
    private const val INJECT_SILENCE_CHUNKS = 65 // ~2.6s trailing silence for deterministic injection
    private const val DEAD_MIC_ZERO_MS = 20_000L
    private const val DEAD_MIC_REOPEN_COOLDOWN_MS = 60_000L

    private data class CaptureDeviceEntry(
        val name: String,
        val mixer: Mixer.Info,
    )

    private data class OpenedMic(
        val line: TargetDataLine,
        val name: String,
        val format: MicCaptureFormat,
    )

    val running = MutableStateFlow(false)
    @Volatile private var stopFlag = false
    @Volatile var onMemoryTrimRequested: ((String) -> Unit)? = null
    private var thread: Thread? = null

    private val initialConfig = JarvisConfigStore.load()
    @Volatile private var preferredMicName: String = initialConfig.preferredMicName
    @Volatile private var currentSensitivity: JarvisSensitivity = initialConfig.sensitivity
    @Volatile private var wakeChimeEnabled: Boolean = initialConfig.wakeChimeEnabled

    @Volatile private var pendingMicReload = false
    @Volatile private var pendingParakeetReload = false
    @Volatile private var pendingManualWake = false

    private val _diagnostics = MutableStateFlow(
        JarvisDiagnostics(
            preferredMicName = initialConfig.preferredMicName,
            sensitivity = initialConfig.sensitivity,
            wakeChimeEnabled = initialConfig.wakeChimeEnabled,
            parakeetStatus = ParakeetModelManager.status.value,
        ),
    )
    val diagnostics: StateFlow<JarvisDiagnostics> = _diagnostics.asStateFlow()

    private val statusSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        statusSyncScope.launch {
            ParakeetModelManager.status.collect { modelStatus ->
                _diagnostics.update { it.copy(parakeetStatus = modelStatus) }
            }
        }
    }

    private val unkRegex = Regex("\\[unk]", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("\\s+")
    private val stage2PunctRegex = Regex("[,.;:!?\"()\\[\\]]+")
    private val strictPartialWakeRegex =
        Regex(
            "^(?:hey|hay|hi|hello|yo|okay|ok)[\\s,]+(?:jarvis|javis|jarvus|jervis|harvis)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val embeddedWakePrefixRegex =
        Regex(
            "\\b(?:hey|hay|hi|hello|yo|okay|ok)[\\s,]+(?:jarvis|javis|jarvus|jervis|harvis)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val quotedWakeGuardRegex =
        Regex(
            "\\b(?:said|says|say|told|tell|asked|ask|called|call|heard|hear|mean|means|wrote|write)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val wakePeekRegex =
        Regex(
            "(?:^|\\G[\\s,]+)(?:" +
                "(?:(?:the|a|an|um|uh|oh|well|and|okay|ok|please)[\\s,]+){0,3}" +
                "(?:hey|hay|hi|hello|yo|okay|ok|o|k|kay|he|eh|they|ay|aye|ey|um|uh|a|j|jay|i'm|im|i|m|home|how|ho)[\\s,]+" +
                "(?:jarvis|javis|jarvus|jervis|harvis|jave|jarv|chavez|jar\\s+vis|jar\\s+this|job\\s+is|jobs|job's|java|travis|service)" +
                "|(?:jarvis|javis|jarvus|jervis|harvis)" +
                ")\\b",
            RegexOption.IGNORE_CASE,
        )

    fun start() {
        if (running.value) return
        stopFlag = false
        remember(true)
        running.value = true
        refreshDevices()
        thread =
            Thread({ supervise() }, "hey-jarvis").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
                start()
            }
    }

    fun stop() {
        stopFlag = true
        remember(false)
        thread?.interrupt()
        thread = null
        running.value = false
        _diagnostics.update {
            it.copy(
                activeMicName = "Stopped",
                engineLabel = "Off",
                micLevel = 0f,
                speechActive = false,
            )
        }
    }

    /** Brings the microphone back up on launch when the user left it on last time. */
    fun restoreIfEnabled() {
        // Always-on by default: only stay off when the user explicitly turned it off.
        if (!File(flagFile().parentFile, "jarvis.disabled").exists()) start()
    }

    fun refreshDevices() {
        val devices = enumerateCaptureDevices().map { it.name }
        _diagnostics.update { it.copy(availableMics = devices) }
    }

    fun setPreferredMic(micName: String) {
        val cleaned = micName.trim()
        preferredMicName = cleaned
        saveCurrentConfig()
        _diagnostics.update { it.copy(preferredMicName = cleaned) }
        pendingMicReload = true
    }

    fun setSensitivity(sensitivity: JarvisSensitivity) {
        currentSensitivity = sensitivity
        saveCurrentConfig()
        _diagnostics.update { it.copy(sensitivity = sensitivity) }
    }

    fun setWakeChimeEnabled(enabled: Boolean) {
        wakeChimeEnabled = enabled
        saveCurrentConfig()
        _diagnostics.update { it.copy(wakeChimeEnabled = enabled) }
    }

    fun downloadHighAccuracyModel() {
        ParakeetModelManager.ensureDownloadedAsync(
            onLog = ::logLine,
            onReady = { pendingParakeetReload = true },
        )
    }

    fun triggerManualWake() {
        pendingManualWake = true
        if (!running.value) {
            start()
        }
    }

    fun onLowRamChanged(lowRam: Boolean) {
        ParakeetModelManager.refreshStatus()
        pendingParakeetReload = true
        if (!lowRam && !ParakeetModelManager.isModelReady() && running.value) {
            downloadHighAccuracyModel()
        }
    }

    private fun saveCurrentConfig() {
        JarvisConfigStore.save(
            JarvisPersistedSettings(
                preferredMicName = preferredMicName,
                sensitivity = currentSensitivity,
                wakeChimeEnabled = wakeChimeEnabled,
            ),
        )
    }

    internal fun stripLeadingStage1Noise(cleanText: String): String {
        val trimmed = cleanText.trim()
        if (trimmed.isEmpty()) return trimmed
        if (strictPartialWakeRegex.containsMatchIn(trimmed)) return trimmed
        val match = embeddedWakePrefixRegex.find(trimmed) ?: return trimmed
        val prefix = trimmed.substring(0, match.range.first).trim()
        if (prefix.isEmpty()) return trimmed
        val prefixWords = prefix.split(multiSpaceRegex).filter { it.isNotEmpty() }
        if (prefixWords.size <= 2 && !quotedWakeGuardRegex.containsMatchIn(prefix)) {
            return trimmed.substring(match.range.first).trim()
        }
        return trimmed
    }

    /** Keeps the wake-word loop alive: retries after mic/model errors (device unplugged, driver reset, ...). */
    private fun supervise() {
        var failures = 0
        while (!stopFlag) {
            val startedAt = System.currentTimeMillis()
            running.value = true
            loop()
            if (stopFlag) break
            failures = if (System.currentTimeMillis() - startedAt > 60_000) 1 else failures + 1
            val waitMs = minOf(30_000L, 2_000L * failures)
            logLine("loop ended unexpectedly, restarting in ${waitMs}ms")
            try { Thread.sleep(waitMs) } catch (_: InterruptedException) { break }
        }
        running.value = false
    }

    private fun flagFile() = File(File(System.getProperty("user.home"), ".simpmusic"), "jarvis.enabled")

    private fun remember(enabled: Boolean) {
        runCatching {
            val file = flagFile()
            val disabled = File(file.parentFile, "jarvis.disabled")
            file.parentFile?.mkdirs()
            if (enabled) {
                file.writeText("true")
                disabled.delete()
            } else {
                file.delete()
                disabled.writeText("true")
            }
        }
    }

    private fun loop() {
        var line: TargetDataLine? = null
        var stage1Recognizer: Recognizer? = null
        var fallbackRecognizer: Recognizer? = null
        var voskModel: Model? = null
        var parakeet: OfflineRecognizer? = null
        var handler: MediaPlayerHandler? = null
        var ducked = false
        var savedVolume = 1f

        fun duckMusic() {
            val player = handler?.player ?: return
            if (ducked) return
            val current = player.volume
            if (current > DUCK_VOLUME) {
                savedVolume = current
                ducked = true
                player.volume = DUCK_VOLUME
                logLine("ducked volume ${"%.2f".format(Locale.ROOT, current)} -> $DUCK_VOLUME")
            }
        }

        fun restoreDuck() {
            val player = handler?.player ?: return
            if (!ducked) return
            ducked = false
            player.volume = savedVolume
            logLine("restored volume -> ${"%.2f".format(Locale.ROOT, savedVolume)}")
        }

        val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mediaHandler = getKoin().get<MediaPlayerHandler>()
            val dataStoreManager = getKoin().get<DataStoreManager>()
            handler = mediaHandler
            val playback = JerryMediaPlayback(
                mediaHandler,
                getKoin().get<SearchRepository>(),
                getKoin().get<SongRepository>(),
                getKoin().get<StreamRepository>(),
                object : JerryVolume {
                    @Volatile private var preMuteVolume: Float? = null

                    private fun applyVolume(next: Float) {
                        mediaHandler.player.volume = next
                        playbackScope.launch {
                            mediaHandler.onPlayerEvent(PlayerEvent.UpdateVolume(next))
                            dataStoreManager.setPlayerVolume(next)
                        }
                    }

                    override fun adjust(up: Boolean) {
                        val next = (mediaHandler.player.volume + if (up) 0.1f else -0.1f).coerceIn(0f, 1f)
                        if (next > 0f) preMuteVolume = next
                        applyVolume(next)
                    }

                    override fun setLevel(percent: Int) {
                        val next = (percent / 100f).coerceIn(0f, 1f)
                        if (next > 0f) preMuteVolume = next
                        applyVolume(next)
                    }

                    override suspend fun mute() {
                        val current = mediaHandler.player.volume
                        if (current > 0f) preMuteVolume = current
                        applyVolume(0f)
                    }

                    override suspend fun unmute() {
                        if (mediaHandler.player.volume > 0f) return
                        val restore = preMuteVolume?.takeIf { it > 0f } ?: 0.5f
                        applyVolume(restore)
                    }
                },
                playlistRepository = getKoin().get<PlaylistRepository>(),
                localPlaylistRepository = getKoin().get<LocalPlaylistRepository>(),
                albumRepository = getKoin().get<AlbumRepository>(),
                scope = playbackScope,
                artistRepository = getKoin().get<ArtistRepository>(),
                homeRepository = getKoin().get<HomeRepository>(),
                dataStoreManager = dataStoreManager,
                accountRepository = getKoin().get<AccountRepository>(),
            )
            val coordinator = JerryCommandCoordinator(playback)
            val session = JerryWakeSession()

            runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
            val dir = voskModelDir()
            logLine("loading stage1 vosk model: ${dir.name}")
            voskModel = Model(dir.absolutePath)
            stage1Recognizer = runCatching {
                Recognizer(voskModel, SAMPLE_RATE.toFloat(), JerryGrammar.stage1GrammarJson)
            }.getOrElse {
                Recognizer(voskModel, SAMPLE_RATE.toFloat())
            }

            ParakeetModelManager.refreshStatus()
            parakeet = loadParakeetRecognizer()
            if (parakeet == null) {
                logLine("parakeet unavailable; initializing secondary vosk fallback recognizer")
                fallbackRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                if (!com.maxrave.simpmusic.utils.isJarvisLowRamEnabled() && !ParakeetModelManager.isModelReady()) {
                    downloadHighAccuracyModel()
                }
            }

            fun engineDescription(): String =
                when {
                    parakeet != null -> "Vosk + Parakeet-110M"
                    com.maxrave.simpmusic.utils.isJarvisLowRamEnabled() -> "Vosk (Low-RAM)"
                    else -> "Vosk (Fallback)"
                }

            var openedMic = openBestMic(preferredMicName)
            line = openedMic.line
            var captureFormat = openedMic.format
            var resampler = PcmResampler(captureFormat.sampleRate, captureFormat.channels, SAMPLE_RATE)

            _diagnostics.update {
                it.copy(
                    activeMicName = "${openedMic.name} (${captureFormat.displayLabel()})",
                    engineLabel = engineDescription(),
                    parakeetStatus = ParakeetModelManager.status.value,
                )
            }

            onMemoryTrimRequested?.invoke("models-ready")
            JarvisStatus.set(JarvisPhase.Listening)
            logLine("listening (model ready, engine=${engineDescription()})")

            val processor = AdaptivePcmProcessor(AdaptiveSpeechVad.paramsFor(currentSensitivity).maxAgcGain)
            val vad = AdaptiveSpeechVad(currentSensitivity)
            val ringBuffer = FloatRingBuffer(RING_CAPACITY)
            val chunkFloats = FloatArray(CHUNK_SAMPLES * 2)

            var ringConsumedSample = 0L
            var idleSafeStartSample = 0L
            var utteranceStartSample = 0L
            var idleSilentChunks = 0
            val preRollBuffers = Array(PRE_ROLL_COUNT) { ByteArray(CHUNK_BYTES) }
            val preRollLengths = IntArray(PRE_ROLL_COUNT)
            var preRollWriteIdx = 0
            var preRollFilled = 0

            var stage2Armed = false
            var stage2ArmedAtMs = 0L
            var stage2SilentChunks = 0
            var stage2UtterancePeak = 0
            var lastSpeechAtMs = 0L
            var lastPartialChangedAtMs = 0L
            var resultUntil = 0L
            var lastPartial = ""
            var peak = 0
            var peakSince = System.currentTimeMillis()
            var smoothedMeter = 0f
            var lastMeterEmitMs = 0L
            var lastNonZeroPcmAtMs = System.currentTimeMillis()
            var lastDeadMicReopenAtMs = 0L

            fun enterAwakeState(preview: String = "") {
                duckMusic()
                if (wakeChimeEnabled) {
                    JarvisWakeChime.playWake()
                }
                JarvisStatus.set(JarvisPhase.Awake, preview)
            }

            fun executeCommand(command: JerryCommand): Long {
                logLine("command: $command")
                if (command !is JerryCommand.Pause && command !is JerryCommand.Stop) {
                    restoreDuck()
                }
                JarvisStatus.set(JarvisPhase.Thinking)
                val message = try {
                    runBlocking { coordinator.execute(command) }
                } catch (t: Throwable) {
                    logLine("command error: ${t::class.simpleName}: ${t.message}")
                    "Could not complete command"
                } finally {
                    restoreDuck()
                }
                logLine("result: $message")
                JarvisStatus.set(JarvisPhase.Result, message)
                onMemoryTrimRequested?.invoke("command")
                return System.currentTimeMillis() + 1800
            }

            fun resetAfterCommand(nowMs: Long) {
                stage1Recognizer.reset()
                lastPartial = ""
                stage2Armed = false
                stage2SilentChunks = 0
                stage2UtterancePeak = 0
                idleSilentChunks = 0
                preRollFilled = 0
                preRollWriteIdx = 0
                ringConsumedSample = ringBuffer.totalWritten
                idleSafeStartSample = ringBuffer.totalWritten
                utteranceStartSample = ringBuffer.totalWritten
                resultUntil = nowMs
            }

            fun runStage2Decode(now: Long) {
                stage2Armed = false
                stage2SilentChunks = 0
                stage2UtterancePeak = 0
                val startSample = maxOf(ringConsumedSample, utteranceStartSample)
                val audio = ringBuffer.extractSince(startSample)
                ringConsumedSample = ringBuffer.totalWritten
                idleSafeStartSample = ringBuffer.totalWritten

                if (audio.isEmpty()) {
                    if (!session.awaitingCommand) {
                        restoreDuck()
                        if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                            JarvisStatus.set(JarvisPhase.Listening)
                        }
                    }
                    return
                }

                fun runFallbackVosk(): String {
                    val rec = fallbackRecognizer ?: Recognizer(voskModel, SAMPLE_RATE.toFloat()).also {
                        fallbackRecognizer = it
                    }
                    return decodeWithFallbackVosk(rec, audio)
                }

                var rawDecoded = ""
                var engineUsed = "parakeet"
                val activeParakeet = parakeet
                if (activeParakeet != null) {
                    runCatching {
                        val stream = activeParakeet.createStream()
                        try {
                            stream.acceptWaveform(audio, SAMPLE_RATE)
                            activeParakeet.decode(stream)
                            rawDecoded = activeParakeet.getResult(stream).text
                        } finally {
                            stream.release()
                        }
                    }.onFailure { err ->
                        logLine("parakeet decode failed (${err.message}); falling back to secondary vosk")
                        engineUsed = "vosk-fallback"
                        rawDecoded = runFallbackVosk()
                    }
                } else {
                    engineUsed = "vosk-fallback"
                    rawDecoded = runFallbackVosk()
                }

                val normalized = normalizeStage2Text(rawDecoded)
                logLine("stage2 ($engineUsed, ${audio.size} samples): \"$normalized\"")
                if (normalized.isNotBlank()) {
                    _diagnostics.update { it.copy(lastHeard = normalized) }
                }
                if (normalized.isBlank()) {
                    if (activeParakeet != null) onMemoryTrimRequested?.invoke("stage2-empty")
                    if (!session.awaitingCommand) {
                        restoreDuck()
                        if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                            JarvisStatus.set(JarvisPhase.Listening)
                        }
                        stage1Recognizer.reset()
                        lastPartial = ""
                    }
                    return
                }
                val command = session.acceptFinal(normalized, now)
                if (command != null) {
                    val until = executeCommand(command)
                    resetAfterCommand(until)
                    line?.let { activeLine ->
                        if (activeLine.available() > captureFormat.chunkBytesFor40Ms() * 4) activeLine.flush()
                    }
                } else {
                    if (activeParakeet != null) onMemoryTrimRequested?.invoke("stage2-nocmd")
                    if (!session.awaitingCommand) {
                        restoreDuck()
                        if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                            JarvisStatus.set(JarvisPhase.Listening)
                        }
                        stage1Recognizer.reset()
                        lastPartial = ""
                    }
                }
            }

            /** Single shared PCM processing pipeline for both live mic (resampled to 16 kHz mono) and deterministic injection. */
            fun processPcmChunk(pcmBytes: ByteArray, byteCount: Int, now: Long) {
                if (byteCount < 2) return
                val sens = currentSensitivity
                if (vad.sensitivity != sens) {
                    vad.sensitivity = sens
                }
                processor.maxGain = vad.currentParams().maxAgcGain

                val sampleCount = minOf(byteCount / 2, chunkFloats.size)
                val rawPeakEstimate = peakOf(pcmBytes, sampleCount * 2)
                if (rawPeakEstimate > 0) {
                    lastNonZeroPcmAtMs = now
                }
                val likelySignal = rawPeakEstimate >= vad.currentParams().minPeak && rawPeakEstimate >= vad.noisePeak * 1.6f
                val stats = processor.process(pcmBytes, sampleCount * 2, chunkFloats, likelySignal)
                ringBuffer.write(chunkFloats, sampleCount)

                peak = maxOf(peak, stats.rawPeak)
                if (now - peakSince > 10_000) {
                    logLine(
                        "mic level peak=$peak (of 32767) floor=${vad.noisePeak.toInt()} gain=${"%.1f".format(Locale.ROOT, stats.appliedGain)}x",
                    )
                    peak = 0
                    peakSince = now
                }

                val allowNoiseUpdate = !session.awaitingCommand && !stage2Armed
                val isSpeech = vad.evaluate(stats.rawPeak, stats.rawRms, allowNoiseUpdate)
                if (isSpeech) {
                    lastSpeechAtMs = now
                }

                // Smooth meter value for the Settings UI
                val targetMeter = ((stats.rawPeak.toFloat() * stats.appliedGain) / 14_000f).coerceIn(0f, 1f)
                smoothedMeter = if (targetMeter > smoothedMeter) targetMeter else smoothedMeter * 0.78f + targetMeter * 0.22f
                if (now - lastMeterEmitMs >= 90L) {
                    lastMeterEmitMs = now
                    _diagnostics.update { it.copy(micLevel = smoothedMeter, speechActive = isSpeech) }
                }

                if (allowNoiseUpdate) {
                    if (!isSpeech) {
                        idleSilentChunks++
                        if (idleSilentChunks >= 12) {
                            idleSafeStartSample = maxOf(ringConsumedSample, ringBuffer.totalWritten - 5_000L)
                        }
                        if (idleSilentChunks == IDLE_SILENCE_GATE_CHUNKS) {
                            stage1Recognizer.reset()
                            lastPartial = ""
                        }
                        if (idleSilentChunks >= IDLE_SILENCE_GATE_CHUNKS) {
                            val copyLen = minOf(sampleCount * 2, CHUNK_BYTES)
                            System.arraycopy(pcmBytes, 0, preRollBuffers[preRollWriteIdx], 0, copyLen)
                            preRollLengths[preRollWriteIdx] = copyLen
                            preRollWriteIdx = (preRollWriteIdx + 1) % PRE_ROLL_COUNT
                            if (preRollFilled < PRE_ROLL_COUNT) preRollFilled++
                            return
                        }
                    } else {
                        if (idleSilentChunks >= IDLE_SILENCE_GATE_CHUNKS && preRollFilled > 0) {
                            val startIdx = (preRollWriteIdx - preRollFilled + PRE_ROLL_COUNT) % PRE_ROLL_COUNT
                            for (i in 0 until preRollFilled) {
                                val idx = (startIdx + i) % PRE_ROLL_COUNT
                                stage1Recognizer.acceptWaveForm(preRollBuffers[idx], preRollLengths[idx])
                            }
                            preRollFilled = 0
                            preRollWriteIdx = 0
                        }
                        idleSilentChunks = 0
                    }
                }

                val accepted = stage1Recognizer.acceptWaveForm(pcmBytes, sampleCount * 2)
                if (stage2Armed) {
                    stage2UtterancePeak = maxOf(stage2UtterancePeak, stats.rawPeak)
                    if (accepted) {
                        val trailing = text(stage1Recognizer.result)
                        if (trailing.isNotBlank()) {
                            logLine("stage1 trailing: $trailing")
                            lastPartialChangedAtMs = now
                        }
                    } else {
                        val partial = partial(stage1Recognizer.partialResult)
                        if (partial != lastPartial) {
                            lastPartial = partial
                            lastPartialChangedAtMs = now
                            val cleanPreview = stripUnkAndCollapse(partial)
                            if (cleanPreview.isNotBlank() && JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                                val afterWake = extractCommandCandidate(cleanPreview, session.awaitingCommand)
                                val displayText = afterWake?.takeIf { it.isNotBlank() } ?: cleanPreview
                                JarvisStatus.set(JarvisPhase.Awake, displayText)
                            }
                        }
                    }
                    val dropQuiet = !isSpeech ||
                        (stage2UtterancePeak > 900 && stats.rawPeak < (stage2UtterancePeak * 0.22f).toInt())
                    if (dropQuiet) stage2SilentChunks++ else stage2SilentChunks = 0
                    val partialStaleTimeout =
                        now - stage2ArmedAtMs >= 1_500L &&
                            lastPartialChangedAtMs > 0L &&
                            now - lastPartialChangedAtMs >= 2_200L
                    if (stage2SilentChunks >= SILENCE_TAIL_CHUNKS ||
                        now - stage2ArmedAtMs >= MAX_STAGE2_ARMED_MS ||
                        partialStaleTimeout
                    ) {
                        runStage2Decode(now)
                    }
                    return
                }

                if (accepted) {
                    val rawStage1 = text(stage1Recognizer.result)
                    if (rawStage1.isNotBlank()) logLine("stage1 final: $rawStage1")
                    val hasUnk = rawStage1.contains("[unk]", ignoreCase = true)
                    val cleanStage1 = stripLeadingStage1Noise(stripUnkAndCollapse(rawStage1))
                    if (cleanStage1.isNotBlank() && !session.awaitingCommand) {
                        _diagnostics.update { it.copy(lastHeard = cleanStage1) }
                    }
                    val candidate = extractCommandCandidate(cleanStage1, session.awaitingCommand)

                    when {
                        candidate == null -> {
                            if (!session.awaitingCommand) {
                                idleSafeStartSample = maxOf(ringConsumedSample, ringBuffer.totalWritten - 5_000L)
                            }
                        }
                        candidate.isEmpty() -> {
                            val newlyWoke = session.observePartial(cleanStage1, now)
                            if (newlyWoke) {
                                utteranceStartSample =
                                    if (hasUnk) {
                                        maxOf(ringConsumedSample, idleSafeStartSample)
                                    } else {
                                        ringBuffer.totalWritten
                                    }
                                enterAwakeState()
                            } else if (!hasUnk) {
                                utteranceStartSample = ringBuffer.totalWritten
                            }
                            if (hasUnk) {
                                stage2Armed = true
                                stage2ArmedAtMs = now
                                stage2SilentChunks = 0
                                stage2UtterancePeak = stats.rawPeak
                                lastPartialChangedAtMs = now
                            }
                        }
                        !hasUnk -> {
                            val parsed = JerryCommandParser.parse(candidate)
                            val speechFinished = now - lastSpeechAtMs >= 180L
                            if (
                                parsed != null &&
                                !isQueryBearingCommand(parsed) &&
                                !JerryGrammar.hasIncompleteCommandTail(candidate) &&
                                speechFinished
                            ) {
                                if (session.observePartial(cleanStage1, now)) {
                                    enterAwakeState(candidate)
                                }
                                _diagnostics.update { it.copy(lastHeard = cleanStage1) }
                                val cmd = session.acceptFinal(cleanStage1, now) ?: parsed
                                val until = executeCommand(cmd)
                                resetAfterCommand(until)
                                line?.let { activeLine ->
                                    if (activeLine.available() > captureFormat.chunkBytesFor40Ms() * 4) activeLine.flush()
                                }
                            } else {
                                if (strictPartialWakeRegex.containsMatchIn(cleanStage1) && session.observePartial(cleanStage1, now)) {
                                    utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                    enterAwakeState(candidate)
                                } else if (!session.awaitingCommand) {
                                    utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                }
                                stage2Armed = true
                                stage2ArmedAtMs = now
                                stage2SilentChunks = 0
                                stage2UtterancePeak = stats.rawPeak
                                lastPartialChangedAtMs = now
                            }
                        }
                        else -> {
                            if (strictPartialWakeRegex.containsMatchIn(cleanStage1) && session.observePartial(cleanStage1, now)) {
                                utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                enterAwakeState(candidate)
                            } else if (!session.awaitingCommand) {
                                utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                            }
                            stage2Armed = true
                            stage2ArmedAtMs = now
                            stage2SilentChunks = 0
                            stage2UtterancePeak = stats.rawPeak
                            lastPartialChangedAtMs = now
                        }
                    }
                } else {
                    val partial = partial(stage1Recognizer.partialResult)
                    if (partial != lastPartial) {
                        lastPartial = partial
                        lastPartialChangedAtMs = now
                        if (partial.isNotBlank() && (partial.contains("jarvis", ignoreCase = true) || partial.contains("hey", ignoreCase = true))) {
                            logLine("partial: $partial")
                        }
                    }
                    val partialHasUnk = partial.contains("[unk]", ignoreCase = true)
                    val cleanPartial = stripLeadingStage1Noise(stripUnkAndCollapse(partial))
                    val partialStableMs = now - lastPartialChangedAtMs
                    if (
                        !session.awaitingCommand &&
                        partialStableMs >= 100L &&
                        strictPartialWakeRegex.containsMatchIn(cleanPartial) &&
                        session.observePartial(cleanPartial, now)
                    ) {
                        utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                        enterAwakeState()
                    }
                    if (session.awaitingCommand && JarvisStatus.state.value.phase == JarvisPhase.Awake && cleanPartial.isNotBlank()) {
                        val afterWake = extractCommandCandidate(cleanPartial, true)
                        if (!afterWake.isNullOrBlank()) {
                            JarvisStatus.set(JarvisPhase.Awake, afterWake)
                        }
                    }
                    val endOfSpeech =
                        lastSpeechAtMs > 0L &&
                            (now - lastSpeechAtMs >= END_OF_SPEECH_SILENCE_MS) &&
                            (now - lastPartialChangedAtMs >= 260L)
                    if (endOfSpeech && !partialHasUnk && !isAmbiguousBarePlayPartial(cleanPartial, session.awaitingCommand)) {
                        val instant = session.acceptInstantPartial(cleanPartial, now)
                        if (instant != null) {
                            logLine("instant: $instant")
                            _diagnostics.update { it.copy(lastHeard = cleanPartial) }
                            val until = executeCommand(instant)
                            resetAfterCommand(until)
                            line?.let { activeLine ->
                                if (activeLine.available() > captureFormat.chunkBytesFor40Ms() * 4) activeLine.flush()
                            }
                        }
                    }
                }

                if (session.expire(now)) {
                    restoreDuck()
                    ringConsumedSample = ringBuffer.totalWritten
                    idleSafeStartSample = ringBuffer.totalWritten
                    utteranceStartSample = ringBuffer.totalWritten
                    if (JarvisStatus.state.value.phase == JarvisPhase.Awake) {
                        JarvisStatus.set(JarvisPhase.Listening)
                    }
                }
                if (resultUntil != 0L && now > resultUntil && JarvisStatus.state.value.phase == JarvisPhase.Result) {
                    JarvisStatus.set(JarvisPhase.Listening)
                    resultUntil = 0L
                }
            }

            fun feedInjectedAudioIfPresent() {
                val rawBytes = claimInjectedAudio() ?: return
                val pcmBytes = stripWavHeaderIfPresent(rawBytes)
                if (pcmBytes.size < 2) return
                logLine("inject-audio: ${pcmBytes.size} bytes (${pcmBytes.size / 32}ms)")
                stage1Recognizer.reset()
                lastPartial = ""
                processor.reset()
                vad.reset()
                stage2Armed = false
                stage2SilentChunks = 0
                stage2UtterancePeak = 0
                ringConsumedSample = ringBuffer.totalWritten
                idleSafeStartSample = ringBuffer.totalWritten
                utteranceStartSample = ringBuffer.totalWritten

                var offset = 0
                val chunkBuf = ByteArray(CHUNK_BYTES)
                while (!stopFlag && offset + 1 < pcmBytes.size) {
                    val len = minOf(CHUNK_BYTES, pcmBytes.size - offset)
                    val evenLen = len and -2
                    if (evenLen <= 0) break
                    System.arraycopy(pcmBytes, offset, chunkBuf, 0, evenLen)
                    processPcmChunk(chunkBuf, evenLen, System.currentTimeMillis())
                    offset += evenLen
                }
                val silenceBuf = ByteArray(CHUNK_BYTES)
                for (i in 0 until INJECT_SILENCE_CHUNKS) {
                    if (stopFlag) break
                    silenceBuf.fill(0)
                    processPcmChunk(silenceBuf, CHUNK_BYTES, System.currentTimeMillis())
                    if (i >= SILENCE_TAIL_CHUNKS && !stage2Armed && !session.awaitingCommand) break
                }
                line?.let { if (it.available() > 0) it.flush() }
            }

            var rawBuf = ByteArray(captureFormat.chunkBytesFor40Ms())
            val resampledBuf = ByteArray(CHUNK_BYTES * 2)
            var injectCheckCounter = 0
            while (!stopFlag) {
                val loopNow = System.currentTimeMillis()
                if (pendingMicReload) {
                    pendingMicReload = false
                    runCatching {
                        line?.stop()
                        line?.close()
                        openedMic = openBestMic(preferredMicName)
                        line = openedMic.line
                        captureFormat = openedMic.format
                        resampler = PcmResampler(captureFormat.sampleRate, captureFormat.channels, SAMPLE_RATE)
                        rawBuf = ByteArray(captureFormat.chunkBytesFor40Ms())
                        processor.reset()
                        vad.reset()
                        stage1Recognizer.reset()
                        lastPartial = ""
                        lastNonZeroPcmAtMs = System.currentTimeMillis()
                        _diagnostics.update {
                            it.copy(
                                activeMicName = "${openedMic.name} (${captureFormat.displayLabel()})",
                            )
                        }
                        logLine("switched mic -> \"${openedMic.name}\" (${captureFormat.displayLabel()})")
                    }.onFailure { err ->
                        logLine("mic switch failed: ${err.message}")
                    }
                }

                if (pendingParakeetReload) {
                    pendingParakeetReload = false
                    val wantLowRam = com.maxrave.simpmusic.utils.isJarvisLowRamEnabled()
                    val activeToRelease = parakeet
                    if (wantLowRam && activeToRelease != null) {
                        runCatching { activeToRelease.release() }
                        parakeet = null
                        if (fallbackRecognizer == null) {
                            fallbackRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                        }
                    } else if (!wantLowRam && parakeet == null) {
                        parakeet = loadParakeetRecognizer()
                        if (parakeet != null) {
                            fallbackRecognizer?.close()
                            fallbackRecognizer = null
                        }
                    }
                    _diagnostics.update { it.copy(engineLabel = engineDescription()) }
                    logLine("voice engine updated -> ${engineDescription()}")
                }

                if (pendingManualWake) {
                    pendingManualWake = false
                    session.reset()
                    if (session.observePartial("hey jarvis", loopNow)) {
                        stage1Recognizer.reset()
                        lastPartial = ""
                        utteranceStartSample = ringBuffer.totalWritten
                        enterAwakeState("Speak your command…")
                    }
                }

                if ((injectCheckCounter++ and 15) == 0) {
                    feedInjectedAudioIfPresent()
                }
                val activeLine = line ?: break
                val n = activeLine.read(rawBuf, 0, rawBuf.size)
                if (n <= 0) continue

                val readNow = System.currentTimeMillis()
                val resampledBytes = resampler.resample(rawBuf, n, resampledBuf)
                var off = 0
                while (off + 1 < resampledBytes) {
                    val step = minOf(CHUNK_BYTES, resampledBytes - off) and -2
                    if (step <= 0) break
                    val chunkSlice = ByteArray(step)
                    System.arraycopy(resampledBuf, off, chunkSlice, 0, step)
                    processPcmChunk(chunkSlice, step, readNow)
                    off += step
                }

                if (
                    preferredMicName.isBlank() &&
                    readNow - lastNonZeroPcmAtMs >= DEAD_MIC_ZERO_MS &&
                    readNow - lastDeadMicReopenAtMs >= DEAD_MIC_REOPEN_COOLDOWN_MS
                ) {
                    lastDeadMicReopenAtMs = readNow
                    lastNonZeroPcmAtMs = readNow
                    logLine("mic \"${openedMic.name}\" produced only 0 amplitude for ${DEAD_MIC_ZERO_MS / 1000}s; re-probing microphones")
                    pendingMicReload = true
                }
            }
            val finalText = stripUnkAndCollapse(text(stage1Recognizer.finalResult))
            if (finalText.isNotBlank()) {
                session.acceptFinal(finalText, System.currentTimeMillis())?.let { cmd ->
                    executeCommand(cmd)
                }
            }
        } catch (e: Exception) {
            log(e)
            JarvisStatus.set(JarvisPhase.Error, e.message ?: "microphone / model error")
        } finally {
            playbackScope.cancel()
            restoreDuck()
            line?.stop()
            line?.close()
            stage1Recognizer?.close()
            fallbackRecognizer?.close()
            voskModel?.close()
            runCatching { parakeet?.release() }
            if (stopFlag && JarvisStatus.state.value.phase != JarvisPhase.Error) JarvisStatus.set(JarvisPhase.Off)
        }
    }

    private fun extractCommandCandidate(cleanText: String, alreadyAwake: Boolean): String? {
        if (cleanText.isBlank()) return null
        val match = wakePeekRegex.findAll(cleanText).lastOrNull()
        return when {
            match != null -> cleanText.substring(match.range.last + 1).trim()
            alreadyAwake -> cleanText
            else -> null
        }
    }

    private fun isQueryBearingCommand(command: JerryCommand): Boolean = when (command) {
        is JerryCommand.PlayQuery,
        is JerryCommand.PlayArtist,
        is JerryCommand.PlaySong,
        is JerryCommand.PlayAlbum,
        is JerryCommand.PlayPlaylist,
        is JerryCommand.PlayGenreOrMood,
        is JerryCommand.PlayRadio,
        is JerryCommand.PlayNextQuery,
        is JerryCommand.AddToQueueQuery,
        -> true
        else -> false
    }

    private fun isAmbiguousBarePlayPartial(cleanPartial: String, alreadyAwake: Boolean): Boolean {
        val candidate = extractCommandCandidate(cleanPartial, alreadyAwake) ?: return false
        return JerryGrammar.hasIncompleteCommandTail(candidate)
    }

    private fun stripUnkAndCollapse(raw: String): String =
        raw.replace(unkRegex, " ").replace(multiSpaceRegex, " ").trim()

    private fun normalizeStage2Text(raw: String): String =
        raw.replace(stage2PunctRegex, " ").replace(multiSpaceRegex, " ").trim()

    private fun decodeWithFallbackVosk(recognizer: Recognizer, samples: FloatArray): String {
        val shorts = ShortArray(samples.size) { i ->
            (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        recognizer.reset()
        recognizer.acceptWaveForm(shorts, shorts.size)
        return text(recognizer.finalResult)
    }

    private fun loadParakeetRecognizer(): OfflineRecognizer? {
        if (com.maxrave.simpmusic.utils.isJarvisLowRamEnabled()) {
            logLine("jarvis low-ram mode enabled; skipping parakeet recognizer")
            return null
        }
        val dir = ParakeetModelManager.modelDir
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!ParakeetModelManager.isModelReady()) {
            logLine("parakeet model files missing in ${dir.absolutePath}")
            return null
        }
        return try {
            val t0 = System.currentTimeMillis()
            val transducer = OfflineTransducerModelConfig.builder()
                .setEncoder(encoder.absolutePath)
                .setDecoder(decoder.absolutePath)
                .setJoiner(joiner.absolutePath)
                .build()
            val numThreads = if (Runtime.getRuntime().availableProcessors() <= 4) 1 else 2
            val modelConfig = OfflineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens.absolutePath)
                .setNumThreads(numThreads)
                .setDebug(false)
                .setModelType("nemo_transducer")
                .build()
            val config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build()
            val rec = OfflineRecognizer(config)
            logLine("loaded parakeet-110m-int8 in ${System.currentTimeMillis() - t0}ms")
            rec
        } catch (t: Throwable) {
            logLine("failed to load parakeet-110m-int8: ${t::class.simpleName}: ${t.message}")
            null
        }
    }

    private fun claimInjectedAudio(): ByteArray? {
        val dir = File(System.getProperty("user.home"), ".simpmusic")
        val inject = File(dir, "inject-audio.pcm")
        if (!inject.exists()) return null
        val claimed = File(dir, "inject-audio.pcm.claimed")
        return runCatching {
            Files.move(inject.toPath(), claimed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                claimed.readBytes().takeIf { it.isNotEmpty() }
            } finally {
                claimed.delete()
            }
        }.getOrNull()
    }

    private fun stripWavHeaderIfPresent(raw: ByteArray): ByteArray {
        if (raw.size <= 44) return raw
        if (raw[0] != 'R'.code.toByte() || raw[1] != 'I'.code.toByte() || raw[2] != 'F'.code.toByte() || raw[3] != 'F'.code.toByte()) {
            return raw
        }
        if (raw[8] != 'W'.code.toByte() || raw[9] != 'A'.code.toByte() || raw[10] != 'V'.code.toByte() || raw[11] != 'E'.code.toByte()) {
            return raw
        }
        var offset = 12
        while (offset + 8 <= raw.size) {
            val id = String(raw, offset, 4, Charsets.US_ASCII)
            val size = (raw[offset + 4].toInt() and 0xff) or
                ((raw[offset + 5].toInt() and 0xff) shl 8) or
                ((raw[offset + 6].toInt() and 0xff) shl 16) or
                ((raw[offset + 7].toInt() and 0xff) shl 24)
            offset += 8
            if (id == "data") {
                val end = minOf(raw.size, offset + maxOf(0, size))
                return raw.copyOfRange(offset, end)
            }
            offset += maxOf(0, size)
            if (size % 2 == 1) offset++
        }
        return raw.copyOfRange(44, raw.size)
    }

    private fun peakOf(buf: ByteArray, n: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            max = maxOf(max, abs(s))
            i += 2
        }
        return max
    }

    private fun isVirtualMic(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return "asus utility" in lower ||
            "ai noise-cancelling" in lower ||
            "stereo mix" in lower ||
            "virtual" in lower ||
            "vb-audio" in lower ||
            "cable output" in lower ||
            "voicemeeter" in lower
    }

    private fun hardwarePreferenceScore(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            "headset" in lower || "usb" in lower || "fifine" in lower || "blue" in lower || "rode" in lower || "shure" in lower || "hyperx" in lower -> 0
            "microphone array" in lower -> 1
            "realtek" in lower -> 2
            "primary sound" in lower -> 3
            else -> 2
        }
    }

    private fun enumerateCaptureDevices(): List<CaptureDeviceEntry> {
        val allMixers = AudioSystem.getMixerInfo()
        val entries = mutableListOf<CaptureDeviceEntry>()
        for (mixerInfo in allMixers) {
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: continue
            val supportsAny = MicCaptureFormat.SUPPORTED_FORMATS.any { fmt ->
                val info = DataLine.Info(TargetDataLine::class.java, fmt.toAudioFormat())
                runCatching { mixer.isLineSupported(info) }.getOrDefault(false)
            }
            if (supportsAny && entries.none { it.name == mixerInfo.name }) {
                entries.add(CaptureDeviceEntry(mixerInfo.name, mixerInfo))
            }
        }
        return entries
    }

    private fun tryOpenAnyFormat(mixerInfo: Mixer.Info?): Triple<TargetDataLine, String, MicCaptureFormat>? {
        val name = mixerInfo?.name ?: "System Default"
        for (fmt in MicCaptureFormat.SUPPORTED_FORMATS) {
            val audioFormat = fmt.toAudioFormat()
            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
            val line = runCatching {
                val rawLine = if (mixerInfo == null) {
                    AudioSystem.getLine(info)
                } else {
                    val m = AudioSystem.getMixer(mixerInfo)
                    if (!m.isLineSupported(info)) return@runCatching null
                    m.getLine(info)
                }
                rawLine as? TargetDataLine
            }.getOrNull() ?: continue

            val opened = runCatching {
                val bufferBytes = fmt.chunkBytesFor40Ms() * 6
                if (!runCatching { line.open(audioFormat, bufferBytes); true }.getOrDefault(false)) {
                    line.open(audioFormat)
                }
                line.start()
                true
            }.getOrDefault(false)
            if (opened) {
                return Triple(line, name, fmt)
            }
            runCatching { line.close() }
        }
        return null
    }

    private fun openBestMic(preferredName: String): OpenedMic {
        val devices = enumerateCaptureDevices()
        val deviceNames = devices.map { it.name }
        _diagnostics.update { it.copy(availableMics = deviceNames) }
        logLine("capture devices: " + deviceNames.joinToString())

        val preferredCandidate = preferredName.takeIf { it.isNotBlank() }?.let { pref ->
            devices.firstOrNull { it.name.equals(pref, ignoreCase = true) }?.mixer
        }

        val physical = devices.filter { !isVirtualMic(it.name) }
        val primarySound = physical.filter {
            val l = it.name.lowercase(Locale.ROOT)
            "primary sound" in l || "default" in l
        }
        val otherHardware = physical
            .filterNot { it in primarySound }
            .sortedBy { hardwarePreferenceScore(it.name) }
        val virtualFallback = devices.filter { isVirtualMic(it.name) }.map { it.mixer }

        val autoCandidates: List<Mixer.Info?> =
            listOf<Mixer.Info?>(null) +
                primarySound.map { it.mixer } +
                otherHardware.map { it.mixer }

        var firstOpened: OpenedMic? = null
        var firstOpenedPeak = 0

        fun probeCandidates(candidates: List<Mixer.Info?>, minActivePeak: Int): OpenedMic? {
            for (candidate in candidates) {
                val (l, name, fmt) = tryOpenAnyFormat(candidate) ?: continue
                val buf = ByteArray(fmt.chunkBytesFor40Ms())
                var peak = 0
                val end = System.currentTimeMillis() + 450
                while (System.currentTimeMillis() < end) {
                    val n = l.read(buf, 0, buf.size)
                    if (n > 0) peak = maxOf(peak, peakOf(buf, n))
                    if (peak >= minActivePeak) break
                }
                logLine("mic \"$name\" (${fmt.displayLabel()}) peak=$peak")
                if (peak >= minActivePeak) {
                    firstOpened?.line?.close()
                    logLine("selected mic: \"$name\" (${fmt.displayLabel()}, peak=$peak)")
                    return OpenedMic(l, name, fmt)
                }
                if (firstOpened == null) {
                    firstOpened = OpenedMic(l, name, fmt)
                    firstOpenedPeak = peak
                } else {
                    l.close()
                }
            }
            return null
        }

        if (preferredCandidate != null) {
            tryOpenAnyFormat(preferredCandidate)?.let { (l, name, fmt) ->
                logLine("selected user mic: \"$name\" (${fmt.displayLabel()})")
                return OpenedMic(l, name, fmt)
            }
            logLine("WARNING: preferred mic \"$preferredName\" could not be opened; falling back to auto")
        }

        probeCandidates(autoCandidates, minActivePeak = 3)?.let { return it }
        firstOpened?.let { opened ->
            logLine("WARNING: primary capture devices are quiet; using \"${opened.name}\" (${opened.format.displayLabel()}, peak=$firstOpenedPeak)")
            logLine("selected mic: \"${opened.name}\" (${opened.format.displayLabel()}, peak=$firstOpenedPeak)")
            return opened
        }
        probeCandidates(virtualFallback, minActivePeak = 20)?.let { return it }
        error("no microphone available")
    }

    private fun logLine(message: String) {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".simpmusic")
            dir.mkdirs()
            val logFile = File(dir, "jarvis.log")
            if (logFile.length() > 256L * 1024L) {
                logFile.writeText("")
            }
            logFile.appendText("${java.time.Instant.now()} $message\n")
        }
    }

    private fun log(e: Exception) {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".simpmusic")
            dir.mkdirs()
            File(dir, "jarvis.log").appendText("${java.time.Instant.now()} ${e.stackTraceToString()}\n")
        }
    }

    private fun text(json: String): String =
        Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""

    private fun partial(json: String): String =
        Json.parseToJsonElement(json).jsonObject["partial"]?.jsonPrimitive?.contentOrNull ?: ""

    private fun voskModelDir(): File {
        val root = File(System.getProperty("user.home"), ".simpmusic/jerry-model")
        val dir = File(root, "vosk-model-small-en-us-0.15")
        if (File(dir, "am/final.mdl").exists()) return dir
        root.mkdirs()
        val resource = DesktopJarvis::class.java.getResourceAsStream("/jerry/model.zip")
            ?: error("model resource missing")
        resource.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val out = File(root, e.name)
                    if (!out.canonicalPath.startsWith(root.canonicalPath + File.separator)) error("bad entry")
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    e = zip.nextEntry
                }
            }
        }
        return dir
    }

    /** Rolling 16 kHz mono FloatArray ring buffer backing Stage 2 offline transcription. */
    private class FloatRingBuffer(private val capacity: Int) {
        private val data = FloatArray(capacity)
        var totalWritten: Long = 0L
            private set

        fun write(samples: FloatArray, count: Int) {
            for (i in 0 until count) {
                val idx = ((totalWritten + i) % capacity).toInt()
                data[idx] = samples[i]
            }
            totalWritten += count
        }

        fun extractSince(fromSample: Long, preRollSamples: Int = 4_000): FloatArray {
            val oldest = maxOf(0L, totalWritten - capacity)
            val start = maxOf(oldest, fromSample)
            val len = (totalWritten - start).toInt()
            if (len <= 0) return FloatArray(0)
            val raw = FloatArray(len)
            var maxPeak = 0f
            for (i in 0 until len) {
                val idx = ((start + i) % capacity).toInt()
                val v = data[idx]
                raw[i] = v
                val av = abs(v)
                if (av > maxPeak) maxPeak = av
            }
            if (maxPeak < 0.002f) return FloatArray(0)
            val onsetThreshold = minOf(0.015f, maxPeak * 0.15f)
            var firstSpeech = 0
            for (i in raw.indices) {
                if (abs(raw[i]) >= onsetThreshold) {
                    firstSpeech = i
                    break
                }
            }
            val trimStart = maxOf(0, firstSpeech - preRollSamples)
            return if (trimStart == 0) raw else raw.copyOfRange(trimStart, raw.size)
        }
    }
}
