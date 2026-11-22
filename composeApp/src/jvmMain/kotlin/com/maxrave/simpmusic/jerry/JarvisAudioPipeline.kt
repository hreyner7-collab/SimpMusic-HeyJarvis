package com.maxrave.simpmusic.jerry

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class MicCaptureFormat(
    val sampleRate: Int,
    val channels: Int,
) {
    fun toAudioFormat(): AudioFormat = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)

    fun chunkBytesFor40Ms(): Int = (sampleRate * 40 / 1000) * channels * 2

    fun displayLabel(): String = "${sampleRate / 1000}kHz ${if (channels == 1) "mono" else "stereo"}"

    companion object {
        val SUPPORTED_FORMATS: List<MicCaptureFormat> = listOf(
            MicCaptureFormat(16_000, 1),
            MicCaptureFormat(48_000, 1),
            MicCaptureFormat(48_000, 2),
            MicCaptureFormat(44_100, 1),
            MicCaptureFormat(44_100, 2),
            MicCaptureFormat(16_000, 2),
        )
    }
}

/**
 * Stateful anti-aliased downsampler converting 16 kHz / 44.1 kHz / 48 kHz mono or stereo
 * signed 16-bit LE PCM to 16 kHz 16-bit LE mono PCM.
 */
class PcmResampler(
    private val sourceRate: Int,
    private val sourceChannels: Int,
    private val targetRate: Int = 16_000,
) {
    private val step: Double = sourceRate.toDouble() / targetRate.toDouble()
    private var bucketSum = 0.0
    private var bucketFilled = 0.0

    fun reset() {
        bucketSum = 0.0
        bucketFilled = 0.0
    }

    fun resample(
        srcBytes: ByteArray,
        srcByteCount: Int,
        dstMonoBytes: ByteArray,
    ): Int {
        val frameBytes = sourceChannels.coerceAtLeast(1) * 2
        val frameCount = srcByteCount / frameBytes
        if (frameCount <= 0) return 0

        if (sourceRate == targetRate && sourceChannels == 1) {
            val copyLen = minOf(frameCount * 2, dstMonoBytes.size and -2)
            System.arraycopy(srcBytes, 0, dstMonoBytes, 0, copyLen)
            return copyLen
        }

        if (sourceRate == targetRate && sourceChannels == 2) {
            val outFrames = minOf(frameCount, dstMonoBytes.size / 2)
            for (i in 0 until outFrames) {
                val base = i * 4
                val left = ((srcBytes[base + 1].toInt() shl 8) or (srcBytes[base].toInt() and 0xff)).toShort().toInt()
                val right = ((srcBytes[base + 3].toInt() shl 8) or (srcBytes[base + 2].toInt() and 0xff)).toShort().toInt()
                val avg = ((left + right) / 2).coerceIn(-32768, 32767)
                dstMonoBytes[2 * i] = (avg and 0xff).toByte()
                dstMonoBytes[2 * i + 1] = ((avg ushr 8) and 0xff).toByte()
            }
            return outFrames * 2
        }

        var outBytes = 0
        for (i in 0 until frameCount) {
            val base = i * frameBytes
            val monoSample: Double =
                if (sourceChannels >= 2) {
                    val left = ((srcBytes[base + 1].toInt() shl 8) or (srcBytes[base].toInt() and 0xff)).toShort().toDouble()
                    val right = ((srcBytes[base + 3].toInt() shl 8) or (srcBytes[base + 2].toInt() and 0xff)).toShort().toDouble()
                    (left + right) * 0.5
                } else {
                    ((srcBytes[base + 1].toInt() shl 8) or (srcBytes[base].toInt() and 0xff)).toShort().toDouble()
                }

            val remaining = step - bucketFilled
            if (remaining > 1.0 + 1e-9) {
                bucketSum += monoSample
                bucketFilled += 1.0
            } else {
                val take = remaining.coerceIn(0.0, 1.0)
                bucketSum += monoSample * take
                val avg = (bucketSum / step).roundToInt().coerceIn(-32768, 32767)
                if (outBytes + 2 <= dstMonoBytes.size) {
                    dstMonoBytes[outBytes] = (avg and 0xff).toByte()
                    dstMonoBytes[outBytes + 1] = ((avg ushr 8) and 0xff).toByte()
                    outBytes += 2
                }
                val leftover = (1.0 - take).coerceAtLeast(0.0)
                bucketSum = monoSample * leftover
                bucketFilled = leftover
            }
        }
        return outBytes
    }
}

data class ProcessedChunkStats(
    val rawPeak: Int,
    val rawRms: Float,
    val appliedGain: Float,
)

/**
 * 80 Hz RC high-pass filter + noise-aware soft-knee AGC for 16 kHz 16-bit mono PCM.
 * Boosts quiet laptop/headset mics up to [maxGain] without amplifying steady room hum during silence.
 */
class AdaptivePcmProcessor(
    @Volatile var maxGain: Float = 8.0f,
) {
    private var prevX = 0f
    private var prevY = 0f
    private var adaptiveGain = 1.5f

    // alpha = 1 / (1 + 2 * pi * 80 / 16000)
    private val hpAlpha = 0.96953125f

    fun reset() {
        prevX = 0f
        prevY = 0f
        adaptiveGain = 1.5f
    }

    fun process(
        pcmBytes: ByteArray,
        byteCount: Int,
        floatOut: FloatArray,
        isSpeechLikely: Boolean = true,
    ): ProcessedChunkStats {
        val sampleCount = minOf(byteCount / 2, floatOut.size)
        if (sampleCount <= 0) return ProcessedChunkStats(0, 0f, adaptiveGain)

        var hpPeakNorm = 0f
        var sumSq = 0.0
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
            sumSq += y.toDouble() * y.toDouble()
        }

        val rawPeak = (hpPeakNorm * 32767f).toInt().coerceIn(0, 32767)
        val rawRms = (sqrt(sumSq / sampleCount) * 32767.0).toFloat().coerceIn(0f, 32767f)

        val currentMax = maxGain.coerceAtLeast(1.0f)
        val idleGain = minOf(2.0f, currentMax)
        val desiredGain =
            if (isSpeechLikely || rawPeak >= 140) {
                (0.50f / maxOf(hpPeakNorm, 0.015f)).coerceIn(1.0f, currentMax)
            } else {
                minOf(idleGain, (0.50f / maxOf(hpPeakNorm, 0.015f)).coerceAtLeast(1.0f))
            }

        adaptiveGain =
            when {
                desiredGain < adaptiveGain -> adaptiveGain * 0.40f + desiredGain * 0.60f
                isSpeechLikely || rawPeak >= 140 -> adaptiveGain * 0.60f + desiredGain * 0.40f
                else -> adaptiveGain * 0.92f + desiredGain * 0.08f
            }

        val gain = adaptiveGain
        for (i in 0 until sampleCount) {
            val boosted = softLimit(floatOut[i] * gain)
            floatOut[i] = boosted
            val quantized = (boosted * 32767f).toInt().coerceIn(-32768, 32767)
            pcmBytes[2 * i] = (quantized and 0xff).toByte()
            pcmBytes[2 * i + 1] = ((quantized ushr 8) and 0xff).toByte()
        }
        return ProcessedChunkStats(rawPeak, rawRms, gain)
    }

    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= 0.85f) return x
        val excess = (ax - 0.85f) / 0.15f
        val limited = 0.85f + 0.15f * tanh(excess.toDouble()).toFloat()
        return if (x < 0f) -limited else limited
    }
}

/**
 * Dual-metric (peak + RMS) adaptive VAD with rolling minimum-envelope noise tracking and hangover.
 * Works across both very quiet laptop microphones (peak ~200-700) and noisy rooms (fan/music).
 */
class AdaptiveSpeechVad(
    sensitivity: JarvisSensitivity = JarvisSensitivity.NORMAL,
) {
    data class SensitivityParams(
        val minPeak: Float,
        val minRms: Float,
        val snrMult: Float,
        val maxAgcGain: Float,
        val maxNoisePeak: Float,
        val maxNoiseRms: Float,
    )

    @Volatile var sensitivity: JarvisSensitivity = sensitivity

    var noisePeak: Float = 60f
        private set
    var noiseRms: Float = 18f
        private set

    private var hangover = 0
    private var windowCount = 0
    private var windowMinPeak = Float.MAX_VALUE
    private var windowMinRms = Float.MAX_VALUE

    fun reset() {
        noisePeak = 60f
        noiseRms = 18f
        hangover = 0
        windowCount = 0
        windowMinPeak = Float.MAX_VALUE
        windowMinRms = Float.MAX_VALUE
    }

    fun currentParams(): SensitivityParams = paramsFor(sensitivity)

    fun isLikelySignal(rawPeak: Int, rawRms: Float): Boolean {
        val p = currentParams()
        val peakThresh = maxOf(p.minPeak, noisePeak * p.snrMult)
        val rmsThresh = maxOf(p.minRms, noiseRms * p.snrMult)
        return rawPeak >= peakThresh || rawRms >= rmsThresh
    }

    fun evaluate(
        rawPeak: Int,
        rawRms: Float,
        allowNoiseUpdate: Boolean,
    ): Boolean {
        val p = currentParams()
        val rawSpeech = isLikelySignal(rawPeak, rawRms)

        if (allowNoiseUpdate) {
            // Minimum-statistics envelope over ~1.2s blocks prevents steady fan/music from pinning rawSpeech=true.
            windowMinPeak = minOf(windowMinPeak, rawPeak.toFloat())
            windowMinRms = minOf(windowMinRms, rawRms)
            windowCount++
            if (windowCount >= 30) {
                if (windowMinPeak < p.maxNoisePeak) {
                    noisePeak =
                        if (windowMinPeak < noisePeak) {
                            noisePeak * 0.40f + windowMinPeak * 0.60f
                        } else {
                            minOf(p.maxNoisePeak, noisePeak * 0.45f + windowMinPeak * 0.55f)
                        }
                }
                if (windowMinRms < p.maxNoiseRms) {
                    noiseRms =
                        if (windowMinRms < noiseRms) {
                            noiseRms * 0.40f + windowMinRms * 0.60f
                        } else {
                            minOf(p.maxNoiseRms, noiseRms * 0.45f + windowMinRms * 0.55f)
                        }
                }
                windowCount = 0
                windowMinPeak = Float.MAX_VALUE
                windowMinRms = Float.MAX_VALUE
            }

            if (rawPeak < noisePeak) {
                noisePeak = noisePeak * 0.88f + rawPeak * 0.12f
            } else if (!rawSpeech) {
                noisePeak = minOf(p.maxNoisePeak, noisePeak * 0.985f + rawPeak * 0.015f)
            }
            if (rawRms < noiseRms) {
                noiseRms = noiseRms * 0.88f + rawRms * 0.12f
            } else if (!rawSpeech) {
                noiseRms = minOf(p.maxNoiseRms, noiseRms * 0.985f + rawRms * 0.015f)
            }
        }

        return if (rawSpeech) {
            hangover = HANGOVER_CHUNKS
            true
        } else if (hangover > 0) {
            hangover--
            true
        } else {
            false
        }
    }

    companion object {
        private const val HANGOVER_CHUNKS = 4

        fun paramsFor(sensitivity: JarvisSensitivity): SensitivityParams =
            when (sensitivity) {
                JarvisSensitivity.HIGH -> SensitivityParams(130f, 35f, 1.9f, 10.0f, 1200f, 400f)
                JarvisSensitivity.NORMAL -> SensitivityParams(200f, 55f, 2.3f, 8.0f, 1800f, 600f)
                JarvisSensitivity.LOW -> SensitivityParams(450f, 130f, 3.0f, 4.5f, 2800f, 900f)
            }
    }
}

data class JarvisPersistedSettings(
    val preferredMicName: String = "",
    val sensitivity: JarvisSensitivity = JarvisSensitivity.NORMAL,
    val wakeChimeEnabled: Boolean = true,
)

object JarvisConfigStore {
    private val file: File
        get() = File(File(System.getProperty("user.home"), ".simpmusic"), "jarvis-settings.conf")

    @Synchronized
    fun load(): JarvisPersistedSettings {
        val props = Properties()
        runCatching {
            val f = file
            if (f.isFile) {
                StringReader(f.readText(Charsets.UTF_8)).use { props.load(it) }
            }
        }
        val mic = props.getProperty("micName", "").trim()
        val sensName = props.getProperty("sensitivity", JarvisSensitivity.NORMAL.name)
        val sens = runCatching { JarvisSensitivity.valueOf(sensName) }.getOrDefault(JarvisSensitivity.NORMAL)
        val chime = props.getProperty("wakeChime", "true").toBooleanStrictOrNull() ?: true
        return JarvisPersistedSettings(mic, sens, chime)
    }

    @Synchronized
    fun save(settings: JarvisPersistedSettings) {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty("micName", settings.preferredMicName.trim())
            props.setProperty("sensitivity", settings.sensitivity.name)
            props.setProperty("wakeChime", settings.wakeChimeEnabled.toString())
            val sw = StringWriter()
            props.store(sw, "SimpMusic Hey Jarvis configuration")
            f.writeText(sw.toString(), Charsets.UTF_8)
        }
    }
}

object JarvisWakeChime {
    fun playWake() {
        thread(isDaemon = true, name = "jarvis-chime", priority = Thread.NORM_PRIORITY) {
            runCatching {
                val sampleRate = 16_000
                val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as? SourceDataLine ?: return@runCatching
                line.open(format, 4096)
                line.start()
                try {
                    // Subtle 130ms ascending two-note confirmation (660 Hz -> 880 Hz)
                    val totalSamples = (sampleRate * 130) / 1000
                    val half = totalSamples / 2
                    val pcm = ByteArray(totalSamples * 2)
                    for (i in 0 until totalSamples) {
                        val freq = if (i < half) 660.0 else 880.0
                        val segIdx = if (i < half) i else (i - half)
                        val segLen = if (i < half) half else (totalSamples - half)
                        val env = sin(PI * segIdx.toDouble() / segLen.toDouble())
                        val sample = (sin(2.0 * PI * freq * i / sampleRate) * env * 0.16 * 32767.0)
                            .roundToInt()
                            .coerceIn(-32768, 32767)
                        pcm[2 * i] = (sample and 0xff).toByte()
                        pcm[2 * i + 1] = ((sample ushr 8) and 0xff).toByte()
                    }
                    line.write(pcm, 0, pcm.size)
                    line.drain()
                } finally {
                    line.stop()
                    line.close()
                }
            }
        }
    }
}
