package com.maxrave.simpmusic.lighting

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
enum class LightingProtocol(
    val displayName: String,
    val defaultPort: Int,
) {
    WLED_UDP("WLED UDP", 21324),
    LIFX_LAN("LIFX LAN", 56700),
    OPENRGB_TCP("OpenRGB TCP", 6742),
}

@Serializable
enum class LightingReactiveMode(
    val label: String,
) {
    SPECTRUM_PULSE("Spectrum Pulse"),
    BASS_FLASH("Bass Flash"),
    AMBIENT_FLOW("Ambient Flow"),
}

@Serializable
data class SmartLightDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 21324,
    val protocol: LightingProtocol = LightingProtocol.WLED_UDP,
    val ledCount: Int = 60,
    val enabled: Boolean = true,
)

@Serializable
data class SmartLightingConfig(
    val enabled: Boolean = false,
    val mode: LightingReactiveMode = LightingReactiveMode.SPECTRUM_PULSE,
    val brightness: Float = 0.85f,
    val sensitivity: Float = 1.0f,
    val maxFps: Int = 20,
    val devices: List<SmartLightDevice> = emptyList(),
)

data class RgbColor(
    val r: Int,
    val g: Int,
    val b: Int,
) {
    fun clamped(): RgbColor =
        RgbColor(
            r = r.coerceIn(0, 255),
            g = g.coerceIn(0, 255),
            b = b.coerceIn(0, 255),
        )
}

object SmartLightingFrameEncoder {
    private fun safeBand(bands: FloatArray, index: Int): Float {
        if (index < 0 || index >= bands.size) return 0f
        val v = bands[index]
        return if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
    }

    private fun averageRange(bands: FloatArray, start: Int, endInclusive: Int): Float {
        if (bands.isEmpty()) return 0f
        var sum = 0f
        var count = 0
        for (i in start..endInclusive) {
            if (i in bands.indices) {
                sum += safeBand(bands, i)
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    fun computeRgbFromSpectrum(
        bands: FloatArray,
        mode: LightingReactiveMode,
        brightness: Float,
        sensitivity: Float,
        hueSeedDegrees: Float = 210f,
    ): RgbColor {
        val safeBrightness = if (brightness.isFinite()) brightness.coerceIn(0f, 1f) else 0.85f
        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3.0f) else 1.0f

        val bass = (averageRange(bands, 0, 4) * safeSens).coerceIn(0f, 1f)
        val mid = (averageRange(bands, 5, 13) * safeSens).coerceIn(0f, 1f)
        val treble = (averageRange(bands, 14, 23) * safeSens).coerceIn(0f, 1f)

        return when (mode) {
            LightingReactiveMode.SPECTRUM_PULSE -> {
                val r = (bass * 255f * safeBrightness).roundToInt()
                val g = (mid * 255f * safeBrightness).roundToInt()
                val b = (treble * 255f * safeBrightness).roundToInt()
                RgbColor(r, g, b).clamped()
            }

            LightingReactiveMode.BASS_FLASH -> {
                val flash = (bass * bass * 1.15f).coerceIn(0f, 1f) * safeBrightness
                val baseHue = ((hueSeedDegrees % 360f) + 360f) % 360f
                hsvToRgb(baseHue, 0.88f, flash)
            }

            LightingReactiveMode.AMBIENT_FLOW -> {
                val energy = ((bass * 0.45f + mid * 0.35f + treble * 0.2f)).coerceIn(0.08f, 1f)
                val hue = (((hueSeedDegrees + mid * 90f + treble * 60f) % 360f) + 360f) % 360f
                hsvToRgb(hue, 0.78f, energy * safeBrightness)
            }
        }
    }

    /**
     * Encodes a WLED DRGB UDP realtime packet:
     * byte 0 = 0x02 (DRGB), byte 1 = timeoutSeconds, followed by `ledCount * 3` RGB bytes.
     */
    fun encodeWledDrgbPacket(
        bands: FloatArray,
        ledCount: Int,
        mode: LightingReactiveMode,
        brightness: Float,
        sensitivity: Float,
        hueSeedDegrees: Float = 210f,
        timeoutSeconds: Int = 2,
    ): ByteArray {
        val count = ledCount.coerceIn(1, 300)
        val out = ByteArray(2 + count * 3)
        out[0] = 0x02
        out[1] = timeoutSeconds.coerceIn(1, 255).toByte()
        val safeBrightness = if (brightness.isFinite()) brightness.coerceIn(0f, 1f) else 0.85f
        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3.0f) else 1.0f

        val baseColor = computeRgbFromSpectrum(bands, mode, safeBrightness, safeSens, hueSeedDegrees)
        for (i in 0 until count) {
            val offset = 2 + i * 3
            if (mode == LightingReactiveMode.SPECTRUM_PULSE && bands.isNotEmpty()) {
                val bandIdx = ((i.toFloat() / count.toFloat()) * bands.size).toInt().coerceIn(0, bands.lastIndex)
                val level = (safeBand(bands, bandIdx) * safeSens).coerceIn(0f, 1f) * safeBrightness
                val hue = ((hueSeedDegrees + (i.toFloat() / count.toFloat()) * 180f) % 360f + 360f) % 360f
                val c = hsvToRgb(hue, 0.9f, level)
                out[offset] = c.r.toByte()
                out[offset + 1] = c.g.toByte()
                out[offset + 2] = c.b.toByte()
            } else {
                out[offset] = baseColor.r.toByte()
                out[offset + 1] = baseColor.g.toByte()
                out[offset + 2] = baseColor.b.toByte()
            }
        }
        return out
    }

    /**
     * Encodes a 49-byte LIFX LAN `SetColor` (message type 102) little-endian packet.
     */
    fun encodeLifxSetColorPacket(
        color: RgbColor,
        transitionMs: Int = 50,
    ): ByteArray {
        val packet = ByteArray(49)
        // Frame (8 bytes): size=49, protocol=1024 | addressable(0x1000) | tagged(0x2000) = 0x3400
        writeShortLE(packet, 0, 49)
        writeShortLE(packet, 2, 0x3400)
        // source = 0x53494D50 ("SIMP")
        writeIntLE(packet, 4, 0x53494D50)

        // Frame Address (16 bytes): target=0 (broadcast), reserved=0, res_required/ack_required=0, sequence=0
        packet[22] = 0x00 // flags
        packet[23] = 0x01 // sequence

        // Protocol Header (12 bytes): reserved(8), type=102 (SetColor), reserved(2)
        writeShortLE(packet, 32, 102)

        // Payload (13 bytes): reserved(1), H(2), S(2), B(2), K(2), durationMs(4)
        val (h, s, b) = rgbToHsbk(color.clamped())
        packet[36] = 0x00
        writeShortLE(packet, 37, h)
        writeShortLE(packet, 39, s)
        writeShortLE(packet, 41, b)
        writeShortLE(packet, 43, 3500)
        writeIntLE(packet, 45, transitionMs.coerceAtLeast(0))
        return packet
    }

    fun encodeOpenRgbSetClientName(clientName: String = "SimpMusic"): ByteArray {
        val nameBytes = (clientName.ifBlank { "SimpMusic" } + "\u0000").encodeToByteArray()
        return encodeOpenRgbPacket(deviceId = 0, packetId = 50, payload = nameBytes)
    }

    fun encodeOpenRgbColorFrame(
        deviceIndex: Int,
        ledCount: Int,
        color: RgbColor,
    ): ByteArray {
        val count = ledCount.coerceIn(1, 300)
        val clamped = color.clamped()
        // OpenRGB NET_PACKET_ID_RGBCONTROLLER_UPDATELEDS (1050):
        // 4-byte data_size + 2-byte num_colors + count * 4-byte RGBColor (R, G, B, 0)
        val payloadSize = 4 + 2 + count * 4
        val payload = ByteArray(payloadSize)
        writeIntLE(payload, 0, payloadSize)
        writeShortLE(payload, 4, count)
        for (i in 0 until count) {
            val offset = 6 + i * 4
            payload[offset] = clamped.r.toByte()
            payload[offset + 1] = clamped.g.toByte()
            payload[offset + 2] = clamped.b.toByte()
            payload[offset + 3] = 0x00
        }
        return encodeOpenRgbPacket(deviceId = deviceIndex.coerceAtLeast(0), packetId = 1050, payload = payload)
    }

    private fun encodeOpenRgbPacket(
        deviceId: Int,
        packetId: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(16 + payload.size)
        // Magic "ORGB"
        packet[0] = 'O'.code.toByte()
        packet[1] = 'R'.code.toByte()
        packet[2] = 'G'.code.toByte()
        packet[3] = 'B'.code.toByte()
        writeIntLE(packet, 4, deviceId)
        writeIntLE(packet, 8, packetId)
        writeIntLE(packet, 12, payload.size)
        payload.copyInto(packet, destinationOffset = 16)
        return packet
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): RgbColor {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) =
            when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
        return RgbColor(
            r = ((rf + m) * 255f).roundToInt(),
            g = ((gf + m) * 255f).roundToInt(),
            b = ((bf + m) * 255f).roundToInt(),
        ).clamped()
    }

    private fun rgbToHsbk(color: RgbColor): Triple<Int, Int, Int> {
        val rf = color.r / 255f
        val gf = color.g / 255f
        val bf = color.b / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        val hueDeg =
            when {
                delta == 0f -> 0f
                maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
                maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }

        val sat = if (maxC == 0f) 0f else delta / maxC
        val h16 = ((hueDeg / 360f) * 65535f).roundToInt().coerceIn(0, 65535)
        val s16 = (sat * 65535f).roundToInt().coerceIn(0, 65535)
        val b16 = (maxC * 65535f).roundToInt().coerceIn(0, 65535)
        return Triple(h16, s16, b16)
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
