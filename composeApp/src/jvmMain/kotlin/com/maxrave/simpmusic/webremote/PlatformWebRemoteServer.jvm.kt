package com.maxrave.simpmusic.webremote

import com.maxrave.logger.Logger
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PlatformWebRemoteJvm"
private const val WORKER_POOL_SIZE = 4
private const val SOCKET_TIMEOUT_MS = 5_000

actual class PlatformWebRemoteServer actual constructor() {
    actual val isSupportedOnCurrentPlatform: Boolean = true

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workerPool: ExecutorService? = null
    private var acceptThread: Thread? = null

    actual fun start(
        preferredPort: Int,
        onHandleRequest: (method: String, path: String, headers: Map<String, String>, body: String) -> RemoteHttpResult,
    ): RemoteServerHandle? {
        synchronized(lifecycleLock) {
            stopLocked()
            val server =
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(preferredPort))
                    }
                }.recoverCatching {
                    Logger.w(TAG, "Port $preferredPort unavailable (${it.message}); falling back to ephemeral port")
                    ServerSocket(0)
                }.onFailure {
                    Logger.e(TAG, "Could not open LAN Web Remote socket: ${it.message}", it)
                }.getOrNull() ?: return null

            val workerIdx = AtomicInteger(1)
            val pool =
                Executors.newFixedThreadPool(WORKER_POOL_SIZE) { runnable ->
                    Thread(runnable, "SimpMusic-WebRemote-Worker-${workerIdx.getAndIncrement()}").apply {
                        isDaemon = true
                    }
                }

            serverSocket = server
            workerPool = pool
            running.set(true)

            val acceptor =
                Thread(
                    {
                        while (running.get() && !server.isClosed) {
                            try {
                                val client = server.accept()
                                runCatching {
                                    pool.execute {
                                        handleClient(client, onHandleRequest)
                                    }
                                }.onFailure {
                                    runCatching { client.close() }
                                }
                            } catch (_: Exception) {
                                if (!running.get()) break
                            }
                        }
                    },
                    "SimpMusic-WebRemote-Accept",
                ).apply {
                    isDaemon = true
                }
            acceptThread = acceptor
            acceptor.start()

            val lanIp = discoverLanIpv4()
            Logger.i(TAG, "LAN Web Remote listening on http://$lanIp:${server.localPort}")
            return RemoteServerHandle(boundPort = server.localPort, lanIpAddress = lanIp)
        }
    }

    actual fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { workerPool?.shutdownNow() }
        workerPool = null
        acceptThread = null
    }

    private fun handleClient(
        socket: Socket,
        onHandleRequest: (method: String, path: String, headers: Map<String, String>, body: String) -> RemoteHttpResult,
    ) {
        socket.use { s ->
            runCatching {
                s.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()

                val headerLines = mutableListOf<String>()
                val lineBuf = StringBuilder()
                while (headerLines.size < RemoteHttpProtocol.MAX_HEADER_LINES) {
                    val b = input.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        val line = lineBuf.toString().trimEnd('\r')
                        lineBuf.setLength(0)
                        if (line.isEmpty()) break
                        headerLines += line
                    } else if (lineBuf.length < 4096) {
                        lineBuf.append(b.toChar())
                    }
                }

                val parsedHeaders = RemoteHttpProtocol.parseRequestHeaderLines(headerLines) ?: return
                val contentLength = RemoteHttpProtocol.contentLength(parsedHeaders.headers)
                val body =
                    if (contentLength > 0) {
                        val bytes = ByteArray(contentLength)
                        var offset = 0
                        while (offset < contentLength) {
                            val read = input.read(bytes, offset, contentLength - offset)
                            if (read <= 0) break
                            offset += read
                        }
                        String(bytes, 0, offset, StandardCharsets.UTF_8)
                    } else {
                        ""
                    }

                val result = onHandleRequest(parsedHeaders.method, parsedHeaders.path, parsedHeaders.headers, body)
                writeHttpResponse(output, result)
            }
        }
    }

    private fun writeHttpResponse(
        output: OutputStream,
        result: RemoteHttpResult,
    ) {
        val bodyBytes = result.body.toByteArray(StandardCharsets.UTF_8)
        val headers =
            "HTTP/1.1 ${result.statusCode} ${result.reason}\r\n" +
                "Content-Type: ${result.contentType}\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun discoverLanIpv4(): String {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val ifaceName = iface.name.orEmpty().lowercase()
                val isVirtualHint =
                    ifaceName.contains("docker") ||
                        ifaceName.contains("veth") ||
                        ifaceName.contains("wsl") ||
                        ifaceName.contains("vbox") ||
                        ifaceName.contains("vmware") ||
                        ifaceName.contains("tun") ||
                        ifaceName.contains("tap")
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        val priority =
                            when {
                                !isVirtualHint && host.startsWith("192.168.") -> 0
                                !isVirtualHint && host.startsWith("10.") -> 1
                                !isVirtualHint && addr.isSiteLocalAddress -> 2
                                !isVirtualHint -> 3
                                else -> 4
                            }
                        candidates += priority to host
                    }
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second ?: "127.0.0.1"
    }
}
