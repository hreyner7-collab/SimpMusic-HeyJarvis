package com.maxrave.simpmusic.lighting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
enum class LightingProtocol(
    val displayName: String,
    val defaultPort: Int,
) {
    GOVEE_BLE("Govee Bluetooth", 0),
    GOVEE_LAN("Govee LAN (Wi-Fi)", 4003),
    PHILIPS_HUE("Philips Hue", 80),
    WIZ_UDP("WiZ Bulb", 38899),
    YEELIGHT_TCP("Yeelight", 55443),
    LIFX_LAN("LIFX", 56700),
    WLED_UDP("WLED Strip", 21324),
    OPENRGB_TCP("OpenRGB PC", 6742),
    ALEXA_WEBHOOK("Alexa Routine Webhook", 443),
}

@Serializable
enum class LightingReactiveMode(
    val label: String,
) {
    DJ_DISCO("DJ Disco (Bass=Red, Pitch=Colors)"),
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
    val apiKey: String = "",
    val lightId: String = "",
)

sealed interface HuePairResult {
    data class Success(val apiKey: String) : HuePairResult

    data object LinkButtonRequired : HuePairResult

    data class Error(val message: String) : HuePairResult
}

@Serializable
data class SmartLightingConfig(
    val enabled: Boolean = false,
    val mode: LightingReactiveMode = LightingReactiveMode.DJ_DISCO,
    val brightness: Float = 0.90f,
    val sensitivity: Float = 1.15f,
    val maxFps: Int = 25,
    val syncDelayMs: Int = 180,
    val devices: List<SmartLightDevice> = emptyList(),
)

@Serializable
data class BleBridgeDevice(
    val name: String = "",
    val addr: String = "",
    val rssi: Int = -100,
)

@Serializable
data class BleBridgeResponse(
    val ok: Boolean = false,
    val devices: List<BleBridgeDevice> = emptyList(),
    val error: String? = null,
    val quit: Boolean = false,
)

data class LightDiscoveryResult(
    val devices: List<SmartLightDevice>,
    val diagnosticHint: String? = null,
)

fun formatFriendlyLightName(rawName: String): String {
    val trimmed = rawName.trim()
    if (trimmed.isEmpty()) return "Smart Light"
    val match = Regex("^(?:ihoment|govee|gbk)[_-]([A-Za-z0-9]+)[_-]([A-Za-z0-9]+)$", RegexOption.IGNORE_CASE).find(trimmed)
    return if (match != null) {
        "Govee ${match.groupValues[1].uppercase()} (${match.groupValues[2].uppercase()})"
    } else {
        trimmed
    }
}

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

    fun maxChannelDelta(other: RgbColor): Int =
        max(abs(r - other.r), max(abs(g - other.g), abs(b - other.b)))
}

data class DjLightFrame(
    val color: RgbColor,
    val brightness: Float,
    val activeCue: String,
    val isBeatHit: Boolean = false,
)

fun applyLedGamma(color: RgbColor, gamma: Float = 2.0f): RgbColor {
    val g = gamma.coerceIn(1.0f, 3.0f)
    fun mapChannel(c: Int): Int =
        ((c.coerceIn(0, 255) / 255f).pow(g) * 255f).roundToInt().coerceIn(0, 255)
    return RgbColor(mapChannel(color.r), mapChannel(color.g), mapChannel(color.b))
}

/**
 * Timestamped delay ring buffer that holds light frames until `deliverAtMs` (audio output time)
 * and drops superseded frames so BLE/Wi-Fi latency never accumulates.
 */
class DelayedLightQueue<T>(
    private val maxCapacity: Int = 64,
) {
    private data class Entry<T>(
        val deliverAtMs: Long,
        val item: T,
    )

    private val entries = ArrayDeque<Entry<T>>()

    fun enqueue(deliverAtMs: Long, item: T) {
        entries.addLast(Entry(deliverAtMs, item))
        while (entries.size > maxCapacity) {
            entries.removeFirst()
        }
    }

    fun drainLatestDue(nowMs: Long): T? {
        var latest: T? = null
        while (entries.isNotEmpty() && entries.first().deliverAtMs <= nowMs) {
            latest = entries.removeFirst().item
        }
        return latest
    }

    fun nextDeliverAtMs(): Long? = entries.firstOrNull()?.deliverAtMs

    fun clear() {
        entries.clear()
    }
}

/**
 * Stateful DJ lighting engine using linear transient expansion, spectral-flux onset detection,
 * adaptive AGC, and LED gamma correction for club-grade beat-locked visuals:
 * - Heavy Bass / Kick Drops -> Fiery Deep Red
 * - Vocals / Snare / Lead Synth -> Electric Magenta / Club Purple
 * - High Pitch / Hi-Hats / Cymbals -> Laser Blue / Electric Cyan
 * - Groove / Low-Mid -> Hot Orange / Gold
 */
class DjDiscoLightEngine {
    private var bassMean = 0.12f
    private var midMean = 0.12f
    private var highMean = 0.12f
    private var energyMean = 0.12f

    private var prevSubBass = 0f
    private var prevSnareVocal = 0f
    private var prevHiHatSpark = 0f

    private var bassFluxAvg = 0.02f
    private var midFluxAvg = 0.02f
    private var highFluxAvg = 0.02f

    private var msSinceKick = 200L
    private var msSinceAccent = 200L
    private var punchEnv = 0f
    private var currentTarget = RgbColor(255, 110, 0)
    private var currentCue = "GROOVE → ORANGE"

    private var smoothR = 0f
    private var smoothG = 0f
    private var smoothB = 0f

    fun reset() {
        bassMean = 0.12f
        midMean = 0.12f
        highMean = 0.12f
        energyMean = 0.12f
        prevSubBass = 0f
        prevSnareVocal = 0f
        prevHiHatSpark = 0f
        bassFluxAvg = 0.02f
        midFluxAvg = 0.02f
        highFluxAvg = 0.02f
        msSinceKick = 200L
        msSinceAccent = 200L
        punchEnv = 0f
        currentTarget = RgbColor(255, 110, 0)
        currentCue = "GROOVE → ORANGE"
        smoothR = 0f
        smoothG = 0f
        smoothB = 0f
    }

    fun step(
        bands: FloatArray,
        userBrightness: Float,
        sensitivity: Float,
        dtMs: Long = 40L,
    ): DjLightFrame {
        val safeBright = if (userBrightness.isFinite()) userBrightness.coerceIn(0.05f, 1f) else 0.9f
        val safeSens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.25f, 3f) else 1.15f
        val dt = dtMs.coerceIn(10L, 250L)

        if (bands.isEmpty()) {
            reset()
            return DjLightFrame(RgbColor(0, 0, 0), 0f, "Idle", isBeatHit = false)
        }

        // Decompress dB-compressed spectrum bands so drum transients pop above sustained pads.
        val subBass = (expandedAvg(bands, 0, 3) * safeSens).coerceIn(0f, 1.5f)
        val lowGroove = (expandedAvg(bands, 4, 8) * safeSens).coerceIn(0f, 1.5f)
        val snareVocal = (expandedAvg(bands, 9, 15) * safeSens).coerceIn(0f, 1.5f)
        val hiHatSpark = (expandedAvg(bands, 16, 23) * safeSens).coerceIn(0f, 1.5f)

        val totalEnergy = (subBass + lowGroove + snareVocal + hiHatSpark) / 4f
        if (!totalEnergy.isFinite() || totalEnergy < 0.008f) {
            smoothR *= 0.68f
            smoothG *= 0.68f
            smoothB *= 0.68f
            punchEnv *= 0.68f
            prevSubBass = 0f
            prevSnareVocal = 0f
            prevHiHatSpark = 0f
            val quietRgb =
                applyLedGamma(
                    RgbColor(smoothR.roundToInt(), smoothG.roundToInt(), smoothB.roundToInt()).clamped(),
                    gamma = 1.85f,
                )
            return DjLightFrame(
                color = quietRgb,
                brightness = 0.14f * safeBright,
                activeCue = "Quiet",
                isBeatHit = false,
            )
        }

        msSinceKick += dt
        msSinceAccent += dt

        val bassFlux = max(0f, subBass - prevSubBass)
        val midFlux = max(0f, snareVocal - prevSnareVocal)
        val highFlux = max(0f, hiHatSpark - prevHiHatSpark)

        prevSubBass = subBass
        prevSnareVocal = snareVocal
        prevHiHatSpark = hiHatSpark

        bassMean = bassMean * 0.90f + subBass * 0.10f
        midMean = midMean * 0.90f + snareVocal * 0.10f
        highMean = highMean * 0.90f + hiHatSpark * 0.10f
        energyMean = energyMean * 0.90f + totalEnergy * 0.10f

        bassFluxAvg = bassFluxAvg * 0.88f + bassFlux * 0.12f
        midFluxAvg = midFluxAvg * 0.88f + midFlux * 0.12f
        highFluxAvg = highFluxAvg * 0.88f + highFlux * 0.12f

        val isKick =
            (bassFlux > max(0.035f, bassFluxAvg * 1.45f) && subBass > max(0.08f, bassMean * 1.08f) && msSinceKick >= 120L) ||
                (subBass > 0.25f && subBass >= snareVocal * 1.35f && subBass >= hiHatSpark * 1.35f && msSinceKick >= 150L)

        val isHighHit =
            !isKick && (
                (highFlux > max(0.03f, highFluxAvg * 1.50f) && hiHatSpark > max(0.07f, highMean * 1.10f) && msSinceAccent >= 100L) ||
                    (hiHatSpark > 0.22f && hiHatSpark >= subBass * 1.25f && hiHatSpark >= snareVocal * 1.15f)
            )

        val isMidHit =
            !isKick && !isHighHit &&
                (midFlux > max(0.03f, midFluxAvg * 1.50f) && snareVocal > max(0.08f, midMean * 1.10f) && msSinceAccent >= 110L)

        var beatHit = false
        when {
            isKick -> {
                msSinceKick = 0L
                punchEnv = 1.0f
                currentTarget = RgbColor(255, 4, 12)
                currentCue = "HEAVY BASS → RED"
                beatHit = true
            }
            isHighHit -> {
                msSinceAccent = 0L
                punchEnv = max(punchEnv, 0.88f)
                currentTarget = RgbColor(0, 225, 255)
                currentCue = "HIGH PITCH → CYAN"
                beatHit = true
            }
            isMidHit -> {
                msSinceAccent = 0L
                punchEnv = max(punchEnv, 0.90f)
                currentTarget = RgbColor(220, 0, 255)
                currentCue = "VOCALS → PURPLE"
                beatHit = true
            }
            else -> {
                punchEnv *= exp(-dt / 165f)
                if (punchEnv < 0.25f) {
                    val (grooveColor, grooveCue) =
                        when {
                            subBass >= snareVocal * 1.20f && subBass >= hiHatSpark * 1.20f && subBass > 0.14f ->
                                RgbColor(255, 8, 16) to "HEAVY BASS → RED"
                            hiHatSpark >= snareVocal * 1.10f && hiHatSpark >= lowGroove ->
                                RgbColor(0, 210, 255) to "HIGH PITCH → CYAN"
                            snareVocal >= lowGroove ->
                                RgbColor(195, 0, 255) to "VOCALS → PURPLE"
                            else ->
                                RgbColor(255, 110, 0) to "GROOVE → ORANGE"
                        }
                    currentTarget = grooveColor
                    currentCue = grooveCue
                }
            }
        }

        val floorLevel = 0.18f + 0.20f * (totalEnergy / energyMean.coerceAtLeast(0.05f)).coerceIn(0f, 1.2f)
        val level = (floorLevel + (1f - floorLevel) * punchEnv).coerceIn(0.14f, 1f) * safeBright

        val tr = currentTarget.r * level
        val tg = currentTarget.g * level
        val tb = currentTarget.b * level

        val attackR = if (beatHit) 1.0f else if (tr > smoothR) 0.65f else 0.38f
        val attackG = if (beatHit) 1.0f else if (tg > smoothG) 0.65f else 0.38f
        val attackB = if (beatHit) 1.0f else if (tb > smoothB) 0.65f else 0.38f

        smoothR += (tr - smoothR) * attackR
        smoothG += (tg - smoothG) * attackG
        smoothB += (tb - smoothB) * attackB

        val rawRgb = RgbColor(smoothR.roundToInt(), smoothG.roundToInt(), smoothB.roundToInt()).clamped()
        val gammaRgb = applyLedGamma(rawRgb, gamma = 1.85f)
        return DjLightFrame(color = gammaRgb, brightness = level, activeCue = currentCue, isBeatHit = beatHit)
    }

    private fun expandedAvg(bands: FloatArray, start: Int, endInclusive: Int): Float {
        var sum = 0f
        var count = 0
        for (i in start..endInclusive) {
            if (i in bands.indices) {
                val v = bands[i]
                if (v.isFinite()) {
                    sum += v.coerceIn(0f, 1f).pow(2.4f)
                    count++
                }
            }
        }
        return if (count == 0) 0f else sum / count
    }
}

object SmartLightingFrameEncoder {
    const val GOVEE_BLE_SERVICE_UUID = "00010203-0405-0607-0809-0a0b0c0d1910"
    const val GOVEE_BLE_WRITE_CHAR_UUID = "00010203-0405-0607-0809-0a0b0c0d2b11"

    const val GOVEE_SCAN_MULTICAST = "239.255.255.250"
    const val GOVEE_SCAN_PORT = 4001
    const val GOVEE_LISTEN_PORT = 4002
    const val GOVEE_SCAN_PAYLOAD = """{"msg":{"cmd":"scan","data":{"account_topic":"reserve"}}}"""

    const val WIZ_DISCOVERY_PAYLOAD = """{"method":"getPilot","params":{}}"""
    const val YEELIGHT_MULTICAST = "239.255.255.250"
    const val YEELIGHT_SSDP_PORT = 1982
    const val YEELIGHT_SEARCH_PAYLOAD =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"

    const val HUE_DISCOVERY_URL = "https://discovery.meethue.com"
    const val HUE_PAIR_BODY = """{"devicetype":"simpmusic#dj"}"""

    private val hueJson = Json { ignoreUnknownKeys = true }

    fun parseHueDiscoveryJson(rawJson: String): List<SmartLightDevice> =
        runCatching {
            val arr = hueJson.parseToJsonElement(rawJson).jsonArray
            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val ip = obj["internalipaddress"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (ip.isBlank()) return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { ip }
                SmartLightDevice(
                    id = "hue-bridge-$id",
                    name = "Philips Hue Bridge ($ip)",
                    host = ip,
                    port = 80,
                    protocol = LightingProtocol.PHILIPS_HUE,
                    enabled = true,
                )
            }
        }.getOrDefault(emptyList())

    fun parseHuePairResponse(rawJson: String): HuePairResult =
        runCatching {
            val arr = hueJson.parseToJsonElement(rawJson).jsonArray
            for (elem in arr) {
                val obj = elem.jsonObject
                val username = obj["success"]?.jsonObject?.get("username")?.jsonPrimitive?.content?.trim()
                if (!username.isNullOrBlank()) {
                    return HuePairResult.Success(username)
                }
                val errObj = obj["error"]?.jsonObject
                if (errObj != null) {
                    val type = errObj["type"]?.jsonPrimitive?.content?.toIntOrNull()
                    val desc = errObj["description"]?.jsonPrimitive?.content.orEmpty()
                    if (type == 101 || desc.contains("link button", ignoreCase = true)) {
                        return HuePairResult.LinkButtonRequired
                    }
                    return HuePairResult.Error(desc.ifBlank { "Hue Bridge error $type" })
                }
            }
            HuePairResult.Error("Unexpected Hue Bridge response")
        }.getOrElse { HuePairResult.Error(it.message ?: "Invalid Hue Bridge response") }

    fun parseHueLightsJson(
        rawJson: String,
        bridgeHost: String,
        apiKey: String,
    ): List<SmartLightDevice> =
        runCatching {
            val root = hueJson.parseToJsonElement(rawJson).jsonObject
            root.entries
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .map { (lightId, value) ->
                    val name = value.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "Light $lightId" }
                    SmartLightDevice(
                        id = "hue-$bridgeHost-$lightId",
                        name = "Hue · $name",
                        host = bridgeHost,
                        port = 80,
                        protocol = LightingProtocol.PHILIPS_HUE,
                        enabled = true,
                        apiKey = apiKey,
                        lightId = lightId,
                    )
                }
        }.getOrDefault(emptyList())

    fun encodeHueStatePayload(
        color: RgbColor,
        brightness0To255: Int,
        transitionDecisec: Int = 1,
    ): String {
        val c = color.clamped()
        val (h16, s16, _) = rgbToHsbk(c)
        val sat = ((s16 / 65535f) * 254f).roundToInt().coerceIn(0, 254)
        val maxChannel = max(c.r, max(c.g, c.b))
        val briFromColor = ((maxChannel / 255f) * 254f).roundToInt()
        val briFromParam = ((brightness0To255.coerceIn(1, 255) / 255f) * 254f).roundToInt()
        val bri = min(briFromColor.coerceAtLeast(1), briFromParam.coerceAtLeast(1)).coerceIn(1, 254)
        val tt = transitionDecisec.coerceIn(0, 10)
        return """{"on":true,"hue":$h16,"sat":$sat,"bri":$bri,"transitiontime":$tt}"""
    }

    fun encodeHueOffPayload(): String = """{"on":false,"transitiontime":2}"""

    fun encodeHueBlinkPayload(): String = """{"on":true,"alert":"select"}"""

    fun isGoveeBleName(name: String?): Boolean {
        val lower = name?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return lower.contains("govee") ||
            lower.contains("ihoment") ||
            lower.contains("gbk") ||
            lower.contains("_h6")
    }

    fun encodeGoveeBlePower(on: Boolean): ByteArray =
        buildGoveeBleFrame(0x33, 0x01, byteArrayOf(if (on) 0x01 else 0x00))

    fun encodeGoveeBleBrightness(brightness0To255: Int): ByteArray =
        buildGoveeBleFrame(0x33, 0x04, byteArrayOf(brightness0To255.coerceIn(0, 255).toByte()))

    fun encodeGoveeBleColor(color: RgbColor): ByteArray {
        val c = color.clamped()
        return buildGoveeBleFrame(
            0x33,
            0x05,
            byteArrayOf(0x02, c.r.toByte(), c.g.toByte(), c.b.toByte()),
        )
    }

    fun encodeGoveeBleKeepAlive(): ByteArray = buildGoveeBleFrame(0xAA.toByte(), 0x01, ByteArray(0))

    private fun buildGoveeBleFrame(head: Byte, cmd: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(20)
        out[0] = head
        out[1] = cmd
        val copyLen = min(payload.size, 17)
        for (i in 0 until copyLen) {
            out[2 + i] = payload[i]
        }
        var xor = 0
        for (i in 0 until 19) {
            xor = xor xor (out[i].toInt() and 0xFF)
        }
        out[19] = (xor and 0xFF).toByte()
        return out
    }

    fun encodeGoveeLanTurnOnPacket(): ByteArray =
        """{"msg":{"cmd":"turn","data":{"value":1}}}""".encodeToByteArray()

    fun encodeGoveeLanColorPacket(color: RgbColor): ByteArray {
        val c = color.clamped()
        return """{"msg":{"cmd":"colorwc","data":{"color":{"r":${c.r},"g":${c.g},"b":${c.b}},"colorTemInKelvin":0}}}"""
            .encodeToByteArray()
    }

    fun parseGoveeScanReply(rawJson: String, fallbackHost: String): SmartLightDevice? {
        if (!rawJson.contains("\"scan\"") && !rawJson.contains("\"ip\"")) return null
        val ip = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1)?.trim() ?: fallbackHost
        if (ip.isBlank()) return null
        val sku = Regex("\"sku\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1)?.trim() ?: "Light"
        return SmartLightDevice(
            id = "govee-lan-$ip",
            name = "Govee $sku",
            host = ip,
            port = 4003,
            protocol = LightingProtocol.GOVEE_LAN,
            enabled = true,
        )
    }

    fun encodeWizSetPilotPacket(color: RgbColor, brightnessPercent: Int): ByteArray {
        val c = color.clamped()
        val dim = brightnessPercent.coerceIn(10, 100)
        return """{"method":"setPilot","params":{"state":true,"r":${c.r},"g":${c.g},"b":${c.b},"dimming":$dim}}"""
            .encodeToByteArray()
    }

    fun parseWizDiscoveryReply(rawJson: String, senderHost: String): SmartLightDevice? {
        if (!rawJson.contains("getPilot") && !rawJson.contains("\"result\"")) return null
        if (senderHost.isBlank()) return null
        val mac = Regex("\"mac\"\\s*:\\s*\"([^\"]+)\"").find(rawJson)?.groupValues?.get(1)?.takeLast(4) ?: senderHost
        return SmartLightDevice(
            id = "wiz-$senderHost",
            name = "WiZ Bulb ($mac)",
            host = senderHost,
            port = 38899,
            protocol = LightingProtocol.WIZ_UDP,
            enabled = true,
        )
    }

    fun encodeYeelightSetRgbCommand(color: RgbColor, smoothMs: Int = 60): ByteArray {
        val c = color.clamped()
        val rgbInt = ((c.r shl 16) or (c.g shl 8) or c.b).coerceAtLeast(1)
        return """{"id":1,"method":"set_rgb","params":[$rgbInt,"smooth",${smoothMs.coerceIn(30, 500)}]}"""
            .plus("\r\n")
            .encodeToByteArray()
    }

    fun parseYeelightDiscoveryReply(rawResponse: String, fallbackHost: String): SmartLightDevice? {
        val locMatch = Regex("Location:\\s*yeelight://([^:\\r\\n]+):(\\d+)", RegexOption.IGNORE_CASE).find(rawResponse)
            ?: return null
        val host = locMatch.groupValues[1].trim().ifBlank { fallbackHost }
        val port = locMatch.groupValues[2].toIntOrNull()?.coerceIn(1, 65535) ?: 55443
        val model = Regex("model:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE).find(rawResponse)?.groupValues?.get(1)?.trim() ?: "Bulb"
        return SmartLightDevice(
            id = "yeelight-$host",
            name = "Yeelight $model",
            host = host,
            port = port,
            protocol = LightingProtocol.YEELIGHT_TCP,
            enabled = true,
        )
    }

    fun encodeLifxGetServicePacket(): ByteArray {
        val packet = ByteArray(36)
        writeShortLE(packet, 0, 36)
        writeShortLE(packet, 2, 0x3400)
        writeIntLE(packet, 4, 0x53494D50)
        packet[23] = 0x01
        writeShortLE(packet, 32, 2)
        return packet
    }

    fun isLifxStateServiceReply(bytes: ByteArray, length: Int): Boolean {
        if (length < 36 || bytes.size < 36) return false
        val msgType = (bytes[32].toInt() and 0xFF) or ((bytes[33].toInt() and 0xFF) shl 8)
        return msgType == 3
    }

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
            LightingReactiveMode.DJ_DISCO -> {
                if (bass >= mid * 1.15f && bass >= treble * 1.15f && bass > 0.15f) {
                    RgbColor((255f * safeBrightness).roundToInt(), 0, (16f * safeBrightness).roundToInt()).clamped()
                } else {
                    val hue = when {
                        treble > mid -> 195f
                        mid > 0.2f -> 285f
                        else -> 25f
                    }
                    hsvToRgb(hue, 0.92f, max(bass, max(mid, treble)).coerceIn(0.15f, 1f) * safeBrightness)
                }
            }

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

    fun encodeLifxSetColorPacket(
        color: RgbColor,
        transitionMs: Int = 50,
    ): ByteArray {
        val packet = ByteArray(49)
        writeShortLE(packet, 0, 49)
        writeShortLE(packet, 2, 0x3400)
        writeIntLE(packet, 4, 0x53494D50)
        packet[22] = 0x00
        packet[23] = 0x01
        writeShortLE(packet, 32, 102)
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
