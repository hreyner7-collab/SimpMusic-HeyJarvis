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
}
