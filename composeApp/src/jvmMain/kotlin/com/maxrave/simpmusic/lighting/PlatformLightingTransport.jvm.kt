package com.maxrave.simpmusic.lighting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

actual class PlatformLightingTransport actual constructor() {
    @Volatile
    private var udpSocket: DatagramSocket? = null

    private fun getOrCreateSocket(): DatagramSocket {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        return DatagramSocket().also { udpSocket = it }
    }

    actual suspend fun sendUdpPacket(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = InetAddress.getByName(host.trim())
                val packet = DatagramPacket(payload, payload.size, address, port.coerceIn(1, 65535))
                getOrCreateSocket().send(packet)
            }
        }

    actual suspend fun sendOpenRgbColor(
        host: String,
        port: Int,
        color: RgbColor,
        ledCount: Int,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port.coerceIn(1, 65535)), 400)
                    socket.soTimeout = 400
                    val out = socket.getOutputStream()
                    out.write(SmartLightingFrameEncoder.encodeOpenRgbSetClientName("SimpMusic"))
                    out.write(SmartLightingFrameEncoder.encodeOpenRgbColorFrame(0, ledCount, color))
                    out.flush()
                }
            }
        }

    actual fun close() {
        runCatching { udpSocket?.close() }
        udpSocket = null
    }
}
