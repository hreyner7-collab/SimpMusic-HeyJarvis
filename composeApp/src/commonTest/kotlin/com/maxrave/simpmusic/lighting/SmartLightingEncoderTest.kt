package com.maxrave.simpmusic.lighting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartLightingEncoderTest {
    @Test
    fun wledDrgbPacketHasExactHeaderAndByteLength() {
        val bands = FloatArray(24) { idx -> (idx + 1) / 24f }
        val packet =
            SmartLightingFrameEncoder.encodeWledDrgbPacket(
                bands = bands,
                ledCount = 30,
                mode = LightingReactiveMode.SPECTRUM_PULSE,
                brightness = 0.9f,
                sensitivity = 1.2f,
                timeoutSeconds = 3,
            )

        assertEquals(2 + 30 * 3, packet.size)
        assertEquals(0x02.toByte(), packet[0])
        assertEquals(0x03.toByte(), packet[1])
        assertTrue(packet.drop(2).any { it != 0.toByte() })
    }

    @Test
    fun lifxSetColorPacketIs49BytesWithLittleEndianHeader() {
        val packet =
            SmartLightingFrameEncoder.encodeLifxSetColorPacket(
                color = RgbColor(255, 64, 32),
                transitionMs = 50,
            )
        assertEquals(49, packet.size)
        // Little-endian size = 49
        assertEquals(49.toByte(), packet[0])
        assertEquals(0.toByte(), packet[1])
        // Message type 102 at offset 32..33
        assertEquals(102.toByte(), packet[32])
        assertEquals(0.toByte(), packet[33])
    }

    @Test
    fun openRgbPacketsContainOrgbMagicAndPayload() {
        val clientPkt = SmartLightingFrameEncoder.encodeOpenRgbSetClientName("SimpMusic")
        assertEquals('O'.code.toByte(), clientPkt[0])
        assertEquals('R'.code.toByte(), clientPkt[1])
        assertEquals('G'.code.toByte(), clientPkt[2])
        assertEquals('B'.code.toByte(), clientPkt[3])

        val colorPkt = SmartLightingFrameEncoder.encodeOpenRgbColorFrame(0, 12, RgbColor(10, 200, 150))
        assertEquals(16 + 4 + 2 + 12 * 4, colorPkt.size)
    }

    @Test
    fun computeRgbHandlesSilenceAndNaN() {
        val silent = SmartLightingFrameEncoder.computeRgbFromSpectrum(FloatArray(24), LightingReactiveMode.SPECTRUM_PULSE, 1f, 1f)
        assertEquals(RgbColor(0, 0, 0), silent)

        val nanBands = FloatArray(24) { Float.NaN }
        val nanResult = SmartLightingFrameEncoder.computeRgbFromSpectrum(nanBands, LightingReactiveMode.BASS_FLASH, Float.NaN, Float.NaN)
        assertEquals(RgbColor(0, 0, 0), nanResult)
    }

    @Test
    fun goveeBleFramesMatchVerifiedXorChecksums() {
        val on = SmartLightingFrameEncoder.encodeGoveeBlePower(true)
        assertEquals(20, on.size)
        assertEquals(0x33.toByte(), on[0])
        assertEquals(0x01.toByte(), on[1])
        assertEquals(0x01.toByte(), on[2])
        assertEquals(0x33.toByte(), on[19])

        val off = SmartLightingFrameEncoder.encodeGoveeBlePower(false)
        assertEquals(0x32.toByte(), off[19])

        val red = SmartLightingFrameEncoder.encodeGoveeBleColor(RgbColor(255, 0, 0))
        assertEquals(0x33.toByte(), red[0])
        assertEquals(0x05.toByte(), red[1])
        assertEquals(0x02.toByte(), red[2])
        assertEquals(0xFF.toByte(), red[3])
        assertEquals(0xCB.toByte(), red[19])
    }

    @Test
    fun djDiscoEngineMapsHeavyBassToRedAndHighPitchToCyan() {
        val engine = DjDiscoLightEngine()
        val heavyBass = FloatArray(24) { if (it <= 4) 0.95f else 0.05f }
        val bassFrame = engine.step(heavyBass, userBrightness = 1f, sensitivity = 1f)
        assertEquals("HEAVY BASS → RED", bassFrame.activeCue)
        assertTrue(bassFrame.color.r > bassFrame.color.b && bassFrame.color.r > bassFrame.color.g)

        val engine2 = DjDiscoLightEngine()
        val highPitch = FloatArray(24) { if (it >= 16) 0.95f else 0.05f }
        val highFrame = engine2.step(highPitch, userBrightness = 1f, sensitivity = 1f)
        assertEquals("HIGH PITCH → CYAN", highFrame.activeCue)
        assertTrue(highFrame.color.b > highFrame.color.r)
    }

    @Test
    fun goveeLanWizYeelightAndLifxParsersWork() {
        val govee = SmartLightingFrameEncoder.parseGoveeScanReply("""{"msg":{"cmd":"scan","data":{"ip":"192.168.0.50","sku":"H6199"}}}""", "192.168.0.50")
        assertEquals("Govee H6199", govee?.name)
        assertEquals(LightingProtocol.GOVEE_LAN, govee?.protocol)

        val wiz = SmartLightingFrameEncoder.parseWizDiscoveryReply("""{"method":"getPilot","result":{"mac":"a8bb50123456","state":true}}""", "192.168.0.60")
        assertEquals("WiZ Bulb (3456)", wiz?.name)

        val yeelight = SmartLightingFrameEncoder.parseYeelightDiscoveryReply("Location: yeelight://192.168.0.70:55443\r\nmodel: color4\r\n", "192.168.0.70")
        assertEquals("Yeelight color4", yeelight?.name)

        val lifxReq = SmartLightingFrameEncoder.encodeLifxGetServicePacket()
        assertEquals(36, lifxReq.size)
    }

    @Test
    fun bleBridgeJsonParsesWithSpacesAndFormatsFriendlyNames() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = """{"ok": true, "devices": [{"name": "ihoment_H6159_DEB4", "addr": "A4:C1:38:E6:DE:B4", "rssi": -65}]}"""
        val parsed = json.decodeFromString<BleBridgeResponse>(raw)
        assertTrue(parsed.ok)
        assertEquals(1, parsed.devices.size)
        assertEquals("A4:C1:38:E6:DE:B4", parsed.devices.first().addr)
        assertEquals("Govee H6159 (DEB4)", formatFriendlyLightName(parsed.devices.first().name))
        assertEquals("Govee H6159 (E77A)", formatFriendlyLightName("ihoment_H6159_E77A"))
    }
}
