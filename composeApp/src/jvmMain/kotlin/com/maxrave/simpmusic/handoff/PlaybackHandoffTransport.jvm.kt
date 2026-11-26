package com.maxrave.simpmusic.handoff

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "HandoffTransportJvm"
private const val MAX_BODY_BYTES = 2 * 1024 * 1024

actual class PlaybackHandoffTransport actual constructor() {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    actual fun defaultDeviceName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        val os = System.getProperty("os.name").orEmpty()
        return when {
            host != null -> "$host (Desktop)"
            os.contains("Windows", ignoreCase = true) -> "Windows PC"
            os.contains("Mac", ignoreCase = true) -> "Mac"
            else -> "SimpMusic Desktop"
        }
    }

    actual fun platformLabel(): String = "DESKTOP"

    actual fun startServer(
        preferredPort: Int,
        beaconProvider: () -> HandoffBeacon,
        onGetState: (pauseLocal: Boolean) -> HandoffPlaybackSnapshot?,
        onReceiveTransfer: (HandoffPlaybackSnapshot) -> Unit,
        onPeerDiscovered: (HandoffPeer) -> Unit,
        onReceiveCustomPost: (path: String, body: String) -> Boolean,
    ): Int {
        stopServer()
        val server =
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort))
                }
            }.getOrElse {
                ServerSocket(0)
            }
        serverSocket = server
        running.set(true)

        thread(name = "SimpMusic-Handoff-HTTP", isDaemon = true) {
            while (running.get() && !server.isClosed) {
                try {
                    val client = server.accept()
                    thread(name = "SimpMusic-Handoff-Conn", isDaemon = true) {
                        handleHttpClient(client, onGetState, onReceiveTransfer, onReceiveCustomPost)
                    }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }

        runCatching {
            val udp =
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PlaybackHandoffWire.UDP_DISCOVERY_PORT))
                }
            udpSocket = udp
            thread(name = "SimpMusic-Handoff-UDP", isDaemon = true) {
                val buf = ByteArray(4096)
                while (running.get() && !udp.isClosed) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        udp.receive(packet)
                        val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                        if (msg == PlaybackHandoffWire.DISCOVER_PING) {
                            val replyBytes = PlaybackHandoffWire.encodeBeacon(beaconProvider()).toByteArray(StandardCharsets.UTF_8)
                            udp.send(DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port))
                        } else {
                            val beacon = PlaybackHandoffWire.decodeBeacon(msg)
                            if (beacon != null && beacon.httpPort > 0) {
                                onPeerDiscovered(
                                    HandoffPeer(
                                        deviceId = beacon.deviceId,
                                        deviceName = beacon.deviceName,
                                        platform = beacon.platform,
                                        host = packet.address.hostAddress ?: "",
                                        httpPort = beacon.httpPort,
                                        nowPlayingTitle = beacon.nowPlayingTitle,
                                        nowPlayingArtist = beacon.nowPlayingArtist,
                                        isPlaying = beacon.isPlaying,
                                        activeRoomCode = beacon.activeRoomCode,
                                        wholeHomeGroupId = beacon.wholeHomeGroupId,
                                    ),
                                )
                            }
                        }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
        }.onFailure {
            Logger.w(TAG, "UDP discovery bind failed (port ${PlaybackHandoffWire.UDP_DISCOVERY_PORT}): ${it.message}")
        }

        return server.localPort
    }

    actual fun broadcastDiscoverPing() {
        val socket = udpSocket ?: return
        val bytes = PlaybackHandoffWire.DISCOVER_PING.toByteArray(StandardCharsets.UTF_8)
        for (addr in broadcastAddresses()) {
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, addr, PlaybackHandoffWire.UDP_DISCOVERY_PORT))
            }
        }
    }

    actual suspend fun sendSnapshot(
        peer: HandoffPeer,
        snapshot: HandoffPlaybackSnapshot,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = PlaybackHandoffWire.encodeSnapshot(snapshot).toByteArray(StandardCharsets.UTF_8)
                val conn =
                    (URL("http://${peer.host}:${peer.httpPort}/handoff/transfer").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 3_000
                        readTimeout = 5_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setFixedLengthStreamingMode(body.size)
                    }
                try {
                    conn.outputStream.use { it.write(body) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "sendSnapshot to ${peer.host}:${peer.httpPort} failed: ${e.message}")
                false
            }
        }

    actual suspend fun sendCustomPost(
        peer: HandoffPeer,
        path: String,
        body: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                val normalizedPath = if (path.startsWith("/")) path else "/$path"
                val conn =
                    (URL("http://${peer.host}:${peer.httpPort}$normalizedPath").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 2_500
                        readTimeout = 4_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setFixedLengthStreamingMode(bytes.size)
                    }
                try {
                    conn.outputStream.use { it.write(bytes) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "sendCustomPost($path) to ${peer.host}:${peer.httpPort} failed: ${e.message}")
                false
            }
        }

    actual suspend fun pullSnapshot(
        peer: HandoffPeer,
        pauseRemote: Boolean,
    ): HandoffPlaybackSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val query = if (pauseRemote) "?pause=1" else ""
                val conn =
                    (URL("http://${peer.host}:${peer.httpPort}/handoff/state$query").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3_000
                        readTimeout = 5_000
                    }
                try {
                    if (conn.responseCode !in 200..299) return@withContext null
                    val raw = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    PlaybackHandoffWire.decodeSnapshot(raw)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "pullSnapshot from ${peer.host}:${peer.httpPort} failed: ${e.message}")
                null
            }
        }

    actual fun stopServer() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { udpSocket?.close() }
        udpSocket = null
    }

    private fun handleHttpClient(
        socket: Socket,
        onGetState: (pauseLocal: Boolean) -> HandoffPlaybackSnapshot?,
        onReceiveTransfer: (HandoffPlaybackSnapshot) -> Unit,
        onReceiveCustomPost: (path: String, body: String) -> Boolean,
    ) {
        socket.use { s ->
            s.soTimeout = 5_000
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val headerLines = mutableListOf<String>()
            val lineBuf = StringBuilder()
            while (headerLines.size < 64) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = lineBuf.toString().trimEnd('\r')
                    lineBuf.setLength(0)
                    if (line.isEmpty()) break
                    headerLines += line
                } else {
                    lineBuf.append(b.toChar())
                }
            }
            val requestLine = headerLines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullPath = parts[1]
            val contentLength =
                headerLines
                    .drop(1)
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
                    ?.coerceIn(0, MAX_BODY_BYTES)
                    ?: 0

            when {
                method == "GET" && fullPath.startsWith("/handoff/state") -> {
                    val pauseLocal = fullPath.contains("pause=1")
                    val snapshot = onGetState(pauseLocal)
                    if (snapshot == null) {
                        writeHttpResponse(output, 204, "No Content", "")
                    } else {
                        writeHttpResponse(output, 200, "OK", PlaybackHandoffWire.encodeSnapshot(snapshot))
                    }
                }

                method == "POST" && fullPath.startsWith("/handoff/transfer") -> {
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(bodyBytes, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    val snapshot = PlaybackHandoffWire.decodeSnapshot(String(bodyBytes, 0, offset, StandardCharsets.UTF_8))
                    if (snapshot != null && snapshot.tracks.isNotEmpty()) {
                        onReceiveTransfer(snapshot)
                        writeHttpResponse(output, 200, "OK", """{"ok":true}""")
                    } else {
                        writeHttpResponse(output, 400, "Bad Request", """{"ok":false}""")
                    }
                }

                method == "POST" -> {
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(bodyBytes, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    val rawBody = String(bodyBytes, 0, offset, StandardCharsets.UTF_8)
                    if (onReceiveCustomPost(fullPath, rawBody)) {
                        writeHttpResponse(output, 200, "OK", """{"ok":true}""")
                    } else {
                        writeHttpResponse(output, 404, "Not Found", """{"ok":false}""")
                    }
                }

                else -> writeHttpResponse(output, 404, "Not Found", "")
            }
        }
    }

    private fun writeHttpResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers =
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifAddr in iface.interfaceAddresses) {
                    if (ifAddr.address is Inet4Address) {
                        ifAddr.broadcast?.let { result.add(it) }
                    }
                }
            }
        }
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        return result.toList()
    }
}
