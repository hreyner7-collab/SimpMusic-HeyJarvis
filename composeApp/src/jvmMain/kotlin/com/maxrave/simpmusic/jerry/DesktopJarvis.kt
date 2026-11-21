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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.tanh

/** Desktop "Hey Jarvis": always-on dual-stage offline wake word + command recognition. */
object DesktopJarvis {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_BYTES = 1280 // 40ms chunks at 16kHz 16-bit mono
    private const val CHUNK_SAMPLES = CHUNK_BYTES / 2
    private const val RING_SECONDS = 6
    private const val RING_CAPACITY = SAMPLE_RATE * RING_SECONDS
    private const val DUCK_VOLUME = 0.15f
    private const val SILENCE_TAIL_CHUNKS = 22 // ~880ms quiet tail before finalizing utterance
    private const val END_OF_SPEECH_SILENCE_MS = 850L // wait for user to finish speaking before committing partial
    private const val MAX_STAGE2_ARMED_MS = 10_000L // allow up to 10s of continuous speech before forced decode
    private const val INJECT_SILENCE_CHUNKS = 65 // ~2.6s trailing silence for deterministic injection

    val running = MutableStateFlow(false)
    @Volatile private var stopFlag = false
    @Volatile var onMemoryTrimRequested: ((String) -> Unit)? = null
    private var thread: Thread? = null

    private val unkRegex = Regex("\\[unk]", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("\\s+")
    private val stage2PunctRegex = Regex("[,.;:!?\"()\\[\\]]+")
    private val strictPartialWakeRegex =
        Regex(
            "^(?:hey|hay|hi|hello|yo)[\\s,]+(?:jarvis|javis|jarvus|jervis|harvis)\\b",
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
        thread = Thread({ supervise() }, "hey-jarvis").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopFlag = true
        remember(false)
        thread?.interrupt()
        thread = null
        running.value = false
    }

    /** Brings the microphone back up on launch when the user left it on last time. */
    fun restoreIfEnabled() {
        // Always-on by default: only stay off when the user explicitly turned it off.
        if (!File(flagFile().parentFile, "jarvis.disabled").exists()) start()
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

            parakeet = loadParakeetRecognizer()
            if (parakeet == null) {
                logLine("parakeet unavailable; initializing secondary vosk fallback recognizer")
                fallbackRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
            }

            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            line = openBestMic(format)
            onMemoryTrimRequested?.invoke("models-ready")
            JarvisStatus.set(JarvisPhase.Listening)
            logLine("listening (model ready)")

            val processor = PcmAudioProcessor()
            val ringBuffer = FloatRingBuffer(RING_CAPACITY)
            val chunkFloats = FloatArray(CHUNK_SAMPLES)

            var ringConsumedSample = 0L
            var idleSafeStartSample = 0L
            var utteranceStartSample = 0L
            var idleSilentChunks = 0
            var noiseFloor = 200f
            val preRollCount = 8
            val preRollBuffers = Array(preRollCount) { ByteArray(CHUNK_BYTES) }
            val preRollLengths = IntArray(preRollCount)
            var preRollWriteIdx = 0
            var preRollFilled = 0

            var stage2Armed = false
            var stage2ArmedAtMs = 0L
            var stage2SilentChunks = 0
            var lastSpeechAtMs = 0L
            var lastPartialChangedAtMs = 0L
            var resultUntil = 0L
            var lastPartial = ""
            var peak = 0
            var peakSince = System.currentTimeMillis()

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
                if (normalized.isBlank()) {
                    if (activeParakeet != null) onMemoryTrimRequested?.invoke("stage2-empty")
                    return
                }
                val command = session.acceptFinal(normalized, now)
                if (command != null) {
                    val until = executeCommand(command)
                    resetAfterCommand(until)
                    if (line.available() > CHUNK_BYTES * 4) line.flush()
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

            /** Single shared PCM processing pipeline for both live mic and deterministic audio injection. */
            fun processPcmChunk(pcmBytes: ByteArray, byteCount: Int, now: Long) {
                if (byteCount < 2) return
                val sampleCount = byteCount / 2
                val chunkPeak = processor.process(pcmBytes, byteCount, chunkFloats)
                ringBuffer.write(chunkFloats, sampleCount)

                peak = maxOf(peak, chunkPeak)
                if (now - peakSince > 10_000) {
                    logLine("mic level peak=$peak (of 32767) over last 10s")
                    peak = 0
                    peakSince = now
                }

                val speechThreshold = maxOf(900f, noiseFloor * 3.0f)
                val isSpeech = chunkPeak.toFloat() >= speechThreshold
                if (isSpeech) {
                    lastSpeechAtMs = now
                }
                if (!session.awaitingCommand && !stage2Armed) {
                    noiseFloor = if (chunkPeak < noiseFloor) {
                        noiseFloor * 0.90f + chunkPeak * 0.10f
                    } else if (!isSpeech) {
                        minOf(2500f, noiseFloor * 0.99f + chunkPeak * 0.01f)
                    } else {
                        noiseFloor
                    }
                    if (!isSpeech) {
                        idleSilentChunks++
                        if (idleSilentChunks >= 10) {
                            idleSafeStartSample = maxOf(ringConsumedSample, ringBuffer.totalWritten - 4_000L)
                        }
                        if (idleSilentChunks == 25) {
                            stage1Recognizer.reset()
                            lastPartial = ""
                        }
                        if (idleSilentChunks >= 25) {
                            val copyLen = minOf(byteCount, CHUNK_BYTES)
                            System.arraycopy(pcmBytes, 0, preRollBuffers[preRollWriteIdx], 0, copyLen)
                            preRollLengths[preRollWriteIdx] = copyLen
                            preRollWriteIdx = (preRollWriteIdx + 1) % preRollCount
                            if (preRollFilled < preRollCount) preRollFilled++
                            return
                        }
                    } else {
                        if (idleSilentChunks >= 25 && preRollFilled > 0) {
                            val startIdx = (preRollWriteIdx - preRollFilled + preRollCount) % preRollCount
                            for (i in 0 until preRollFilled) {
                                val idx = (startIdx + i) % preRollCount
                                stage1Recognizer.acceptWaveForm(preRollBuffers[idx], preRollLengths[idx])
                            }
                            preRollFilled = 0
                            preRollWriteIdx = 0
                        }
                        idleSilentChunks = 0
                    }
                }

                val accepted = stage1Recognizer.acceptWaveForm(pcmBytes, byteCount)
                if (stage2Armed) {
                    if (accepted) {
                        val trailing = text(stage1Recognizer.result)
                        if (trailing.isNotBlank()) logLine("stage1 trailing: $trailing")
                    }
                    if (!isSpeech) stage2SilentChunks++ else stage2SilentChunks = 0
                    if (stage2SilentChunks >= SILENCE_TAIL_CHUNKS || now - stage2ArmedAtMs >= MAX_STAGE2_ARMED_MS) {
                        runStage2Decode(now)
                    }
                    return
                }

                if (accepted) {
                    val rawStage1 = text(stage1Recognizer.result)
                    if (rawStage1.isNotBlank()) logLine("stage1 final: $rawStage1")
                    val hasUnk = rawStage1.contains("[unk]", ignoreCase = true)
                    val cleanStage1 = stripUnkAndCollapse(rawStage1)
                    val candidate = extractCommandCandidate(cleanStage1, session.awaitingCommand)

                    when {
                        candidate == null -> {
                            if (!session.awaitingCommand) {
                                idleSafeStartSample = maxOf(ringConsumedSample, ringBuffer.totalWritten - 4_000L)
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
                                duckMusic()
                                JarvisStatus.set(JarvisPhase.Awake)
                            } else if (!hasUnk) {
                                utteranceStartSample = ringBuffer.totalWritten
                            }
                            if (hasUnk) {
                                stage2Armed = true
                                stage2ArmedAtMs = now
                                stage2SilentChunks = 0
                            }
                        }
                        !hasUnk -> {
                            val parsed = JerryCommandParser.parse(candidate)
                            val speechFinished = now - lastSpeechAtMs >= 350L
                            if (
                                parsed != null &&
                                !isQueryBearingCommand(parsed) &&
                                !JerryGrammar.hasIncompleteCommandTail(candidate) &&
                                speechFinished
                            ) {
                                if (session.observePartial(cleanStage1, now)) {
                                    duckMusic()
                                    JarvisStatus.set(JarvisPhase.Awake)
                                }
                                val cmd = session.acceptFinal(cleanStage1, now) ?: parsed
                                val until = executeCommand(cmd)
                                resetAfterCommand(until)
                                if (line.available() > CHUNK_BYTES * 4) line.flush()
                            } else {
                                if (strictPartialWakeRegex.containsMatchIn(cleanStage1) && session.observePartial(cleanStage1, now)) {
                                    utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                    duckMusic()
                                    JarvisStatus.set(JarvisPhase.Awake)
                                } else if (!session.awaitingCommand) {
                                    utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                }
                                stage2Armed = true
                                stage2ArmedAtMs = now
                                stage2SilentChunks = 0
                            }
                        }
                        else -> {
                            if (strictPartialWakeRegex.containsMatchIn(cleanStage1) && session.observePartial(cleanStage1, now)) {
                                utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                                duckMusic()
                                JarvisStatus.set(JarvisPhase.Awake)
                            } else if (!session.awaitingCommand) {
                                utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                            }
                            stage2Armed = true
                            stage2ArmedAtMs = now
                            stage2SilentChunks = 0
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
                    val cleanPartial = stripUnkAndCollapse(partial)
                    val partialStableMs = now - lastPartialChangedAtMs
                    if (
                        !session.awaitingCommand &&
                        partialStableMs >= 120L &&
                        strictPartialWakeRegex.containsMatchIn(cleanPartial) &&
                        session.observePartial(cleanPartial, now)
                    ) {
                        utteranceStartSample = maxOf(ringConsumedSample, idleSafeStartSample)
                        duckMusic()
                        JarvisStatus.set(JarvisPhase.Awake)
                    }
                    // Wait until end-of-speech (~850ms quiet after speaking + stable partial) so we never
                    // guess or interrupt mid-sentence while the user is still talking.
                    val endOfSpeech =
                        lastSpeechAtMs > 0L &&
                            (now - lastSpeechAtMs >= END_OF_SPEECH_SILENCE_MS) &&
                            (now - lastPartialChangedAtMs >= 450L)
                    if (endOfSpeech && !partialHasUnk && !isAmbiguousBarePlayPartial(cleanPartial, session.awaitingCommand)) {
                        val instant = session.acceptInstantPartial(cleanPartial, now)
                        if (instant != null) {
                            logLine("instant: $instant")
                            val until = executeCommand(instant)
                            resetAfterCommand(until)
                            if (line.available() > CHUNK_BYTES * 4) line.flush()
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
                stage2Armed = false
                stage2SilentChunks = 0
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
                if (line.available() > 0) line.flush()
            }

            val buf = ByteArray(CHUNK_BYTES)
            while (!stopFlag) {
                feedInjectedAudioIfPresent()
                val n = line.read(buf, 0, buf.size)
                if (n <= 0) continue
                processPcmChunk(buf, n, System.currentTimeMillis())
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
        val dir = File(System.getProperty("user.home"), ".simpmusic/jerry-model/parakeet-110m-int8")
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!encoder.isFile || !decoder.isFile || !joiner.isFile || !tokens.isFile) {
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
            val modelConfig = OfflineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens.absolutePath)
                .setNumThreads(2)
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

    /**
     * Pins Realtek / hardware microphones first while skipping silent virtual inputs (such as
     * ASUS Utility AI Noise-cancelling Input) unless no physical capture device can be opened.
     */
    private fun openBestMic(format: AudioFormat): TargetDataLine {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val mixers = AudioSystem.getMixerInfo().filter {
            runCatching { AudioSystem.getMixer(it).isLineSupported(info) }.getOrDefault(false)
        }
        logLine("capture devices: " + mixers.joinToString { it.name })

        val physical = mixers.filter { !isVirtualMic(it.name) }
        val realtek = physical.filter { "realtek" in it.name.lowercase(Locale.ROOT) }
            .sortedBy { if ("microphone array" in it.name.lowercase(Locale.ROOT)) 0 else 1 }
        val otherHardware = physical.filter {
            val lower = it.name.lowercase(Locale.ROOT)
            "realtek" !in lower && "primary sound" !in lower && "default" !in lower
        }
        val primaryOrDefault = listOf<Mixer.Info?>(null) + physical.filter {
            val lower = it.name.lowercase(Locale.ROOT)
            "primary sound" in lower || "default" in lower
        }
        val virtualFallback = mixers.filter { isVirtualMic(it.name) }

        val primaryCandidates: List<Mixer.Info?> = realtek + otherHardware + primaryOrDefault
        var firstOpened: TargetDataLine? = null
        var firstOpenedName = ""
        var firstOpenedPeak = 0

        fun probeCandidates(candidates: List<Mixer.Info?>, minActivePeak: Int): TargetDataLine? {
            for (candidate in candidates) {
                val l = runCatching {
                    (if (candidate == null) AudioSystem.getLine(info) else AudioSystem.getMixer(candidate).getLine(info)) as TargetDataLine
                }.getOrNull() ?: continue
                val opened = runCatching { l.open(format); l.start(); true }.getOrDefault(false)
                if (!opened) { runCatching { l.close() }; continue }
                val name = candidate?.name ?: "system default"
                val buf = ByteArray(CHUNK_BYTES)
                var peak = 0
                val end = System.currentTimeMillis() + 1_500
                while (System.currentTimeMillis() < end) {
                    val n = l.read(buf, 0, buf.size)
                    if (n > 0) peak = maxOf(peak, peakOf(buf, n))
                }
                logLine("mic \"$name\" peak=$peak")
                if (peak >= minActivePeak) {
                    firstOpened?.close()
                    logLine("selected mic: \"$name\" (peak=$peak)")
                    return l
                }
                if (firstOpened == null) {
                    firstOpened = l
                    firstOpenedName = name
                    firstOpenedPeak = peak
                } else {
                    l.close()
                }
            }
            return null
        }

        probeCandidates(primaryCandidates, minActivePeak = 10)?.let { return it }
        firstOpened?.let { opened ->
            logLine("WARNING: primary capture devices are quiet; using \"$firstOpenedName\" (peak=$firstOpenedPeak)")
            logLine("selected mic: \"$firstOpenedName\" (peak=$firstOpenedPeak)")
            return opened
        }
        probeCandidates(virtualFallback, minActivePeak = 20)?.let { return it }
        error("no microphone available")
    }

    private fun logLine(message: String) {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".simpmusic")
            dir.mkdirs()
            File(dir, "jarvis.log").appendText("${java.time.Instant.now()} $message\n")
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

    /** 80 Hz 1st-order RC high-pass filter + adaptive soft 2x gain boost for 16 kHz 16-bit mono PCM. */
    private class PcmAudioProcessor {
        private var prevX = 0f
        private var prevY = 0f
        private var adaptiveGain = 1.0f

        // alpha = 1 / (1 + 2 * pi * 80 / 16000)
        private val hpAlpha = 0.96953125f

        fun reset() {
            prevX = 0f
            prevY = 0f
            adaptiveGain = 1.0f
        }

        fun process(pcmBytes: ByteArray, byteCount: Int, floatOut: FloatArray): Int {
            val sampleCount = minOf(byteCount / 2, floatOut.size)
            var hpPeakNorm = 0f
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[2 * i].toInt() and 0xff
                val hi = pcmBytes[2 * i + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                val y = hpAlpha * (prevY + s - prevX)
                prevX = s
                prevY = y
                floatOut[i] = y
                val absY = abs(y)
                if (absY > hpPeakNorm) hpPeakNorm = absY
            }
            val rawPeak = (hpPeakNorm * 32767f).toInt().coerceIn(0, 32767)

            val desiredGain = (0.75f / maxOf(hpPeakNorm, 0.01f)).coerceIn(1.0f, 2.0f)
            adaptiveGain = if (desiredGain < adaptiveGain) {
                desiredGain
            } else {
                adaptiveGain * 0.85f + desiredGain * 0.15f
            }

            val gain = adaptiveGain
            for (i in 0 until sampleCount) {
                val boosted = softLimit(floatOut[i] * gain)
                floatOut[i] = boosted
                val quantized = (boosted * 32767f).toInt().coerceIn(-32768, 32767)
                pcmBytes[2 * i] = (quantized and 0xff).toByte()
                pcmBytes[2 * i + 1] = ((quantized ushr 8) and 0xff).toByte()
            }
            return rawPeak
        }

        private fun softLimit(x: Float): Float {
            val ax = abs(x)
            if (ax <= 0.85f) return x
            val excess = (ax - 0.85f) / 0.15f
            val limited = 0.85f + 0.15f * tanh(excess.toDouble()).toFloat()
            return if (x < 0f) -limited else limited
        }
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
