package com.maxrave.simpmusic.jerry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarvisAudioPipelineTest {
    private fun generateSinePcm16Le(
        sampleRate: Int,
        channels: Int,
        frames: Int,
        freqHz: Double,
        amplitude: Int,
        frameOffset: Int = 0,
    ): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (i in 0 until frames) {
            val s = (sin(2.0 * PI * freqHz * (frameOffset + i) / sampleRate) * amplitude)
                .roundToInt()
                .coerceIn(-32768, 32767)
            for (ch in 0 until channels) {
                val idx = (i * channels + ch) * 2
                out[idx] = (s and 0xff).toByte()
                out[idx + 1] = ((s ushr 8) and 0xff).toByte()
            }
        }
        return out
    }

    @Test
    fun `PcmResampler passes 16kHz mono through untouched`() {
        val resampler = PcmResampler(sourceRate = 16_000, sourceChannels = 1, targetRate = 16_000)
        val input = generateSinePcm16Le(16_000, 1, 640, 440.0, 5_000)
        val output = ByteArray(1280)
        val written = resampler.resample(input, input.size, output)
        assertEquals(1280, written)
        assertTrue(input.contentEquals(output))
    }

    @Test
    fun `PcmResampler downsamples 48kHz stereo and 44100Hz stereo cleanly to 16kHz mono`() {
        val resampler48 = PcmResampler(sourceRate = 48_000, sourceChannels = 2, targetRate = 16_000)
        val chunk48 = generateSinePcm16Le(48_000, 2, 1_920, 440.0, 10_000)
        val out48 = ByteArray(2560)
        val bytes48 = resampler48.resample(chunk48, chunk48.size, out48)
        assertEquals(640 * 2, bytes48)

        var maxPeak = 0
        for (i in 0 until bytes48 / 2) {
            val s = ((out48[2 * i + 1].toInt() shl 8) or (out48[2 * i].toInt() and 0xff)).toShort().toInt()
            maxPeak = maxOf(maxPeak, abs(s))
        }
        assertTrue(maxPeak in 8_500..10_200, "48kHz->16kHz 440Hz sine peak should be preserved, was $maxPeak")

        // 44.1 kHz stereo across 5 chunks (8820 frames total -> ~3200 output samples at 16 kHz)
        val resampler44 = PcmResampler(sourceRate = 44_100, sourceChannels = 2, targetRate = 16_000)
        val out44 = ByteArray(2560)
        var totalSamples44 = 0
        for (c in 0 until 5) {
            val chunk44 = generateSinePcm16Le(44_100, 2, 1_764, 440.0, 8_000, frameOffset = c * 1_764)
            val written = resampler44.resample(chunk44, chunk44.size, out44)
            totalSamples44 += written / 2
        }
        assertTrue(
            abs(totalSamples44 - 3_200) <= 1,
            "44.1kHz->16kHz over 200ms should yield ~3200 samples, got $totalSamples44",
        )
    }

    @Test
    fun `quiet laptop mic speech is detected and boosted without clipping`() {
        val processor = AdaptivePcmProcessor(maxGain = 8.0f)
        val vad = AdaptiveSpeechVad(JarvisSensitivity.NORMAL)
        val floats = FloatArray(640)

        var lastPeakFloat = 0f
        var detectedSpeech = false
        for (c in 0 until 6) {
            val pcm = generateSinePcm16Le(16_000, 1, 640, 350.0, amplitude = 350, frameOffset = c * 640)
            val stats = processor.process(pcm, pcm.size, floats, isSpeechLikely = true)
            if (vad.evaluate(stats.rawPeak, stats.rawRms, allowNoiseUpdate = true)) {
                detectedSpeech = true
            }
            lastPeakFloat = floats.maxOf { abs(it) }
        }

        assertTrue(detectedSpeech, "Quiet laptop mic speech (peak=350) must trigger VAD")
        val inputNormPeak = 350f / 32768f
        assertTrue(
            lastPeakFloat > inputNormPeak * 4.0f,
            "AdaptivePcmProcessor should boost quiet speech > 4x (in=$inputNormPeak, out=$lastPeakFloat)",
        )
        assertTrue(floats.all { it in -1.0f..1.0f }, "Output must never exceed [-1, 1]")
    }

    @Test
    fun `idle room silence stays non-speech and does not over-amplify noise`() {
        val processor = AdaptivePcmProcessor(maxGain = 8.0f)
        val vad = AdaptiveSpeechVad(JarvisSensitivity.NORMAL)
        val floats = FloatArray(640)

        var anySpeech = false
        var lastGain = 0f
        for (c in 0 until 20) {
            val pcm = generateSinePcm16Le(16_000, 1, 640, 220.0, amplitude = 25, frameOffset = c * 640)
            val stats = processor.process(pcm, pcm.size, floats, isSpeechLikely = false)
            if (vad.evaluate(stats.rawPeak, stats.rawRms, allowNoiseUpdate = true)) {
                anySpeech = true
            }
            lastGain = stats.appliedGain
        }

        assertFalse(anySpeech, "Low electrical hum (peak=25) must stay non-speech")
        assertTrue(lastGain <= 2.1f, "Idle gain should relax <= 2.1x during silence, got $lastGain")
    }

    @Test
    fun `noisy fan environment adapts noise floor via minimum-statistics tracker and separates speech`() {
        val vad = AdaptiveSpeechVad(JarvisSensitivity.NORMAL)
        val processor = AdaptivePcmProcessor(maxGain = vad.currentParams().maxAgcGain)
        val floats = FloatArray(640)

        // 40 chunks (~1.6s) of constant fan noise (peak ~600)
        var fanAfterAdaptSpeech = true
        for (c in 0 until 40) {
            val fan = generateSinePcm16Le(16_000, 1, 640, 180.0, amplitude = 600, frameOffset = c * 640)
            val stats = processor.process(fan, fan.size, floats, isSpeechLikely = false)
            fanAfterAdaptSpeech = vad.evaluate(stats.rawPeak, stats.rawRms, allowNoiseUpdate = true)
        }
        assertFalse(fanAfterAdaptSpeech, "Steady fan floor (600) should settle to non-speech after adaptation")

        // Real voice spike above the fan floor (peak=2200)
        val speech = generateSinePcm16Le(16_000, 1, 640, 320.0, amplitude = 2200)
        val speechStats = processor.process(speech, speech.size, floats, isSpeechLikely = true)
        assertTrue(
            vad.evaluate(speechStats.rawPeak, speechStats.rawRms, allowNoiseUpdate = true),
            "Speech peak=2200 above 600 fan floor must be detected",
        )
    }

    @Test
    fun `sensitivity presets step thresholds monotonically`() {
        val highVad = AdaptiveSpeechVad(JarvisSensitivity.HIGH)
        val normalVad = AdaptiveSpeechVad(JarvisSensitivity.NORMAL)
        val lowVad = AdaptiveSpeechVad(JarvisSensitivity.LOW)

        assertTrue(highVad.evaluate(160, 45f, allowNoiseUpdate = false))
        assertFalse(normalVad.evaluate(160, 45f, allowNoiseUpdate = false))
        assertFalse(lowVad.evaluate(160, 45f, allowNoiseUpdate = false))

        assertTrue(normalVad.evaluate(300, 80f, allowNoiseUpdate = false))
        assertFalse(lowVad.evaluate(300, 80f, allowNoiseUpdate = false))

        assertTrue(lowVad.evaluate(600, 180f, allowNoiseUpdate = false))
    }

    @Test
    fun `stripLeadingStage1Noise removes stray prefix tokens but protects quoted wake phrases`() {
        assertEquals("yo jarvis", DesktopJarvis.stripLeadingStage1Noise("louder yo jarvis"))
        assertEquals("hey jarvis pause", DesktopJarvis.stripLeadingStage1Noise("play hey jarvis pause"))
        assertEquals("ok jarvis skip", DesktopJarvis.stripLeadingStage1Noise("um the ok jarvis skip"))
        assertEquals(
            "they said hey jarvis next song",
            DesktopJarvis.stripLeadingStage1Noise("they said hey jarvis next song"),
        )
        assertEquals(
            "she asked ok jarvis pause",
            DesktopJarvis.stripLeadingStage1Noise("she asked ok jarvis pause"),
        )
    }
}
