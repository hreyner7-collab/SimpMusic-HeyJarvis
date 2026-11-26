package com.maxrave.simpmusic.smarthome

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "SmartSpeakerJvm"

actual class SmartSpeakerTransport actual constructor() {
    actual suspend fun discoverSpeakers(timeoutMs: Long): List<SmartSpeakerDevice> =
        withContext(Dispatchers.IO) {
            val locations = LinkedHashSet<String>()
            val multicastAddr = InetAddress.getByName(SsdpProtocol.MULTICAST_ADDRESS)
            val targets = listOf(SsdpProtocol.ST_MEDIA_RENDERER, SsdpProtocol.ST_AV_TRANSPORT)

            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 350
                    socket.broadcast = true
                    for (st in targets) {
                        val payload = SsdpProtocol.buildMSearchPayload(st).toByteArray(StandardCharsets.UTF_8)
                        socket.send(DatagramPacket(payload, payload.size, multicastAddr, SsdpProtocol.MULTICAST_PORT))
                    }
                    val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 4_000L)
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                            val raw = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                            SsdpProtocol.parseSsdpResponse(raw)?.let { resp ->
                                locations.add(resp.location)
                            }
                        } catch (_: SocketTimeoutException) {
                            // Poll until deadline expires
                        }
                    }
                }
            }.onFailure {
                Logger.w(TAG, "SSDP discovery error: ${it.message}")
            }

            val now = System.currentTimeMillis()
            val devices = ArrayList<SmartSpeakerDevice>()
            for (loc in locations) {
                val xml = fetchXml(loc) ?: continue
                UpnpDeviceParser.parseDeviceDescriptionXml(loc, xml, seenAtMs = now)?.let {
                    devices.add(it)
                }
            }
            devices.distinctBy { it.udn }
        }

    actual suspend fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        action: String,
        envelopeXml: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = envelopeXml.toByteArray(StandardCharsets.UTF_8)
                val conn =
                    (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 3_000
                        readTimeout = 5_000
                        doOutput = true
                        setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                        setRequestProperty("SOAPACTION", UpnpSoapBuilder.soapActionHeader(serviceType, action))
                        setFixedLengthStreamingMode(bytes.size)
                    }
                try {
                    conn.outputStream.use { it.write(bytes) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        error("UPnP $action failed (HTTP $code)")
                    }
                    body
                } finally {
                    conn.disconnect()
                }
            }
        }

    private fun fetchXml(locationUrl: String): String? =
        runCatching {
            val conn =
                (URL(locationUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2_000
                    readTimeout = 2_500
                }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
}
