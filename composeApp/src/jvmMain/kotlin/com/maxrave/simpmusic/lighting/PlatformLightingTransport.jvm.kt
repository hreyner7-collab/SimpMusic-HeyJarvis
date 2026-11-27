package com.maxrave.simpmusic.lighting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

actual class PlatformLightingTransport actual constructor() {
    actual val defaultSyncDelayMs: Int = 0

    @Volatile
    private var udpSocket: DatagramSocket? = null

    private val bleJson = Json { ignoreUnknownKeys = true }
    private val bleMutex = Mutex()
    private var bleProcess: Process? = null
    private var bleWriter: BufferedWriter? = null
    private var bleReader: BufferedReader? = null
    private val bleStderrLines = ArrayDeque<String>()

    private fun getOrCreateSocket(): DatagramSocket {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        return DatagramSocket().also { udpSocket = it }
    }

    actual suspend fun discoverLights(timeoutMs: Long): LightDiscoveryResult =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<SmartLightDevice>()
            var bleHint: String? = null
            coroutineScope {
                val bleJob = async { scanGoveeBle(timeoutMs.coerceIn(2500L, 6000L)) }
                val goveeLanJob = async { runCatching { scanGoveeLan(1800L) }.getOrDefault(emptyList()) }
                val wizJob = async { runCatching { scanWiz(1600L) }.getOrDefault(emptyList()) }
                val lifxJob = async { runCatching { scanLifx(1600L) }.getOrDefault(emptyList()) }
                val yeelightJob = async { runCatching { scanYeelight(1600L) }.getOrDefault(emptyList()) }
                val openRgbJob = async { runCatching { probeOpenRgbLocal() }.getOrNull() }

                val bleRes = bleJob.await()
                found += bleRes.devices
                bleHint = bleRes.diagnosticHint
                found += goveeLanJob.await()
                found += wizJob.await()
                found += lifxJob.await()
                found += yeelightJob.await()
                openRgbJob.await()?.let { found += it }
            }
            val unique = found.distinctBy { "${it.protocol}:${it.host}" }
            LightDiscoveryResult(
                devices = unique,
                diagnosticHint = if (unique.isEmpty()) bleHint ?: "Make sure Bluetooth is ON and your Govee strip is plugged in" else null,
            )
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

    actual suspend fun sendTcpLine(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port.coerceIn(1, 65535)), 350)
                    socket.soTimeout = 350
                    val out = socket.getOutputStream()
                    out.write(payload)
                    out.flush()
                }
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

    actual suspend fun sendGoveeBleColor(
        address: String,
        color: RgbColor,
        brightness0To255: Int,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val c = color.clamped()
            val b = brightness0To255.coerceIn(0, 255)
            sendBleCommand("""{"cmd":"color","addr":"${escapeJson(address)}","r":${c.r},"g":${c.g},"b":${c.b},"brightness":$b}""")
                .map { Unit }
        }

    actual suspend fun sendGoveeBlePower(
        address: String,
        on: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            sendBleCommand("""{"cmd":"power","addr":"${escapeJson(address)}","on":$on}""").map { Unit }
        }

    actual suspend fun blinkGoveeBle(address: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            sendBleCommand("""{"cmd":"blink","addr":"${escapeJson(address)}"}""").map { Unit }
        }

    actual suspend fun triggerWebhook(url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = url.trim()
                require(trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                    "Webhook URL must start with http:// or https://"
                }
                val conn =
                    (URL(trimmed).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3000
                        readTimeout = 3000
                    }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) error("HTTP $code")
                } finally {
                    conn.disconnect()
                }
            }
        }

    actual fun close() {
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { bleWriter?.write("""{"cmd":"quit"}""" + "\n"); bleWriter?.flush() }
        runCatching { bleProcess?.destroyForcibly() }
        bleProcess = null
        bleWriter = null
        bleReader = null
    }

    private suspend fun scanGoveeBle(timeoutMs: Long): LightDiscoveryResult {
        val sec = (timeoutMs / 1000.0).coerceIn(2.0, 6.0)
        val result = sendBleCommand("""{"cmd":"scan","timeout":$sec}""")
        return result.fold(
            onSuccess = { raw ->
                val parsed = runCatching { bleJson.decodeFromString<BleBridgeResponse>(raw) }.getOrNull()
                val devices =
                    parsed?.devices.orEmpty().mapNotNull { dev ->
                        if (dev.addr.isBlank()) null
                        else SmartLightDevice(
                            id = "govee-ble-${dev.addr}",
                            name = formatFriendlyLightName(dev.name.ifBlank { "Govee ${dev.addr}" }),
                            host = dev.addr,
                            port = 0,
                            protocol = LightingProtocol.GOVEE_BLE,
                            enabled = true,
                        )
                    }
                LightDiscoveryResult(devices = devices, diagnosticHint = parsed?.error)
            },
            onFailure = { err ->
                LightDiscoveryResult(devices = emptyList(), diagnosticHint = err.message)
            },
        )
    }

    private suspend fun sendBleCommand(jsonLine: String): Result<String> =
        bleMutex.withLock {
            runCatching {
                ensureBleBridgeLocked()
                val writer = bleWriter ?: error("BLE bridge writer unavailable")
                val reader = bleReader ?: error("BLE bridge reader unavailable")
                writer.write(jsonLine)
                writer.write("\n")
                writer.flush()
                val line =
                    reader.readLine() ?: run {
                        val stderrTail = synchronized(bleStderrLines) { bleStderrLines.joinToString(" | ") }
                        error(if (stderrTail.isNotBlank()) "BLE bridge exited: $stderrTail" else "BLE bridge closed")
                    }
                val parsed = runCatching { bleJson.decodeFromString<BleBridgeResponse>(line) }.getOrNull()
                if (parsed != null && !parsed.ok) {
                    error(parsed.error ?: "BLE error")
                }
                line
            }.onFailure {
                runCatching { bleProcess?.destroyForcibly() }
                bleProcess = null
                bleWriter = null
                bleReader = null
            }
        }

    private fun ensureBleBridgeLocked() {
        val existing = bleProcess
        if (existing != null && existing.isAlive && bleWriter != null && bleReader != null) return

        val cacheDir = File(System.getProperty("user.home"), ".simpmusic${File.separator}cache").apply { mkdirs() }
        val isWindows = System.getProperty("os.name", "").contains("win", ignoreCase = true)
        val command: List<String> =
            if (isWindows) {
                val exeStream = PlatformLightingTransport::class.java.getResourceAsStream("/lighting/GoveeBleBridge.exe")
                if (exeStream != null) {
                    val exeBytes = exeStream.use { it.readBytes() }
                    val exeFile = File(cacheDir, "GoveeBleBridge.exe")
                    if (!exeFile.isFile || exeFile.length() != exeBytes.size.toLong()) {
                        exeFile.writeBytes(exeBytes)
                    }
                    listOf(exeFile.absolutePath)
                } else {
                    val scriptFile = File(cacheDir, "govee_ble_bridge.py")
                    scriptFile.writeText(GOVEE_BLE_BRIDGE_PY, StandardCharsets.UTF_8)
                    val pythonExe = findPythonExecutable() ?: error("Python 3 with bleak not found for Desktop BLE")
                    listOf(pythonExe, "-u", scriptFile.absolutePath)
                }
            } else {
                val scriptFile = File(cacheDir, "govee_ble_bridge.py")
                scriptFile.writeText(GOVEE_BLE_BRIDGE_PY, StandardCharsets.UTF_8)
                val pythonExe = findPythonExecutable() ?: error("Python 3 with bleak not found for Desktop BLE")
                listOf(pythonExe, "-u", scriptFile.absolutePath)
            }

        synchronized(bleStderrLines) { bleStderrLines.clear() }
        val proc =
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        bleProcess = proc
        bleWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        bleReader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        Thread(
            {
                runCatching {
                    BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                        lines.forEach { errLine ->
                            synchronized(bleStderrLines) {
                                bleStderrLines.addLast(errLine)
                                while (bleStderrLines.size > 8) bleStderrLines.removeFirst()
                            }
                        }
                    }
                }
            },
            "SimpMusic-BLE-Stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun findPythonExecutable(): String? {
        val localAppData = System.getenv("LOCALAPPDATA")
        val candidates =
            listOfNotNull(
                localAppData?.let { "$it\\Programs\\Python\\Python312\\python.exe" },
                localAppData?.let { "$it\\Programs\\Python\\Python311\\python.exe" },
                localAppData?.let { "$it\\Programs\\Python\\Python313\\python.exe" },
                "python",
                "python3",
            )
        for (c in candidates) {
            if (c.contains("\\") || c.contains("/")) {
                if (File(c).isFile && hasBleakModule(c)) return c
            } else if (hasBleakModule(c)) {
                return c
            }
        }
        return null
    }

    private fun hasBleakModule(pythonCmd: String): Boolean =
        runCatching {
            val p = ProcessBuilder(pythonCmd, "-c", "import bleak").start()
            p.waitFor() == 0
        }.getOrDefault(false)

    private fun scanGoveeLan(timeoutMs: Long): List<SmartLightDevice> {
        val out = mutableListOf<SmartLightDevice>()
        val listener =
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SmartLightingFrameEncoder.GOVEE_LISTEN_PORT))
                    soTimeout = 300
                }
            }.getOrElse {
                DatagramSocket().apply { soTimeout = 300 }
            }
        listener.use { sock ->
            val payload = SmartLightingFrameEncoder.GOVEE_SCAN_PAYLOAD.encodeToByteArray()
            DatagramSocket().use { sender ->
                sender.broadcast = true
                sender.send(DatagramPacket(payload, payload.size, InetAddress.getByName(SmartLightingFrameEncoder.GOVEE_SCAN_MULTICAST), SmartLightingFrameEncoder.GOVEE_SCAN_PORT))
                sender.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), SmartLightingFrameEncoder.GOVEE_SCAN_PORT))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    val raw = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    SmartLightingFrameEncoder.parseGoveeScanReply(raw, pkt.address.hostAddress.orEmpty())?.let { out += it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return out
    }

    private fun scanWiz(timeoutMs: Long): List<SmartLightDevice> {
        val out = mutableListOf<SmartLightDevice>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 300
            val payload = SmartLightingFrameEncoder.WIZ_DISCOVERY_PAYLOAD.encodeToByteArray()
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 38899))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    val raw = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    SmartLightingFrameEncoder.parseWizDiscoveryReply(raw, pkt.address.hostAddress.orEmpty())?.let { out += it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return out
    }

    private fun scanLifx(timeoutMs: Long): List<SmartLightDevice> {
        val out = mutableListOf<SmartLightDevice>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 300
            val payload = SmartLightingFrameEncoder.encodeLifxGetServicePacket()
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 56700))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    if (SmartLightingFrameEncoder.isLifxStateServiceReply(pkt.data, pkt.length)) {
                        val ip = pkt.address.hostAddress.orEmpty()
                        if (ip.isNotBlank()) {
                            out += SmartLightDevice("lifx-$ip", "LIFX ($ip)", ip, 56700, LightingProtocol.LIFX_LAN, enabled = true)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return out
    }

    private fun scanYeelight(timeoutMs: Long): List<SmartLightDevice> {
        val out = mutableListOf<SmartLightDevice>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 300
            val payload = SmartLightingFrameEncoder.YEELIGHT_SEARCH_PAYLOAD.encodeToByteArray()
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(SmartLightingFrameEncoder.YEELIGHT_MULTICAST), SmartLightingFrameEncoder.YEELIGHT_SSDP_PORT))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    val raw = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    SmartLightingFrameEncoder.parseYeelightDiscoveryReply(raw, pkt.address.hostAddress.orEmpty())?.let { out += it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return out
    }

    private fun probeOpenRgbLocal(): SmartLightDevice? =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 6742), 250)
                SmartLightDevice("openrgb-local", "OpenRGB (This PC)", "127.0.0.1", 6742, LightingProtocol.OPENRGB_TCP, enabled = true)
            }
        }.getOrNull()

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private val GOVEE_BLE_BRIDGE_PY =
            """
            import asyncio
            import json
            import sys
            from bleak import BleakClient, BleakScanner

            WRITE_CHAR = "00010203-0405-0607-0809-0a0b0c0d2b11"
            TOKENS = ("govee", "ihoment", "gbk", "_h6")
            clients = {}
            initialized_addrs = set()

            def make_frame(head, cmd, payload):
                b = [0] * 20
                b[0] = head & 0xFF
                b[1] = cmd & 0xFF
                for i, v in enumerate(payload[:17]):
                    b[2 + i] = v & 0xFF
                x = 0
                for i in range(19):
                    x ^= b[i]
                b[19] = x & 0xFF
                return bytes(b)

            async def get_client(addr):
                c = clients.get(addr)
                if c is not None and c.is_connected:
                    return c
                c = BleakClient(addr, timeout=8.0)
                await c.connect()
                clients[addr] = c
                initialized_addrs.discard(addr)
                return c

            async def write_frame(addr, frame):
                c = await get_client(addr)
                try:
                    await c.write_gatt_char(WRITE_CHAR, frame, response=False)
                except Exception:
                    await c.write_gatt_char(WRITE_CHAR, frame, response=True)

            async def handle(req):
                cmd = req.get("cmd")
                if cmd == "scan":
                    timeout = float(req.get("timeout", 4.0))
                    try:
                        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
                    except Exception as e:
                        msg = str(e)
                        if "powered on" in msg.lower() or "not available" in msg.lower():
                            return {"ok": False, "error": "Turn on Bluetooth in Windows Settings to find Govee lights"}
                        raise
                    items = sorted(found.values(), key=lambda pair: pair[1].rssi, reverse=True)
                    out = []
                    for d, adv in items:
                        name = (d.name or adv.local_name or "").strip()
                        low = name.lower()
                        if any(t in low for t in TOKENS):
                            out.append({"name": name, "addr": d.address, "rssi": int(adv.rssi)})
                    return {"ok": True, "devices": out}
                elif cmd == "power":
                    addr = req["addr"]
                    on = bool(req.get("on", True))
                    await write_frame(addr, make_frame(0x33, 0x01, [0x01 if on else 0x00]))
                    if not on:
                        initialized_addrs.discard(addr)
                    return {"ok": True}
                elif cmd == "color":
                    addr = req["addr"]
                    r, g, b = int(req["r"]), int(req["g"]), int(req["b"])
                    if addr not in initialized_addrs:
                        await write_frame(addr, make_frame(0x33, 0x04, [0xFF]))
                        initialized_addrs.add(addr)
                    await write_frame(addr, make_frame(0x33, 0x05, [0x02, r, g, b]))
                    return {"ok": True}
                elif cmd == "blink":
                    addr = req["addr"]
                    await write_frame(addr, make_frame(0x33, 0x01, [0x01]))
                    await write_frame(addr, make_frame(0x33, 0x04, [0xFF]))
                    await write_frame(addr, make_frame(0x33, 0x05, [0x02, 0xFF, 0x00, 0x00]))
                    await asyncio.sleep(0.85)
                    await write_frame(addr, make_frame(0x33, 0x01, [0x00]))
                    return {"ok": True}
                elif cmd == "quit":
                    for c in list(clients.values()):
                        try:
                            await c.disconnect()
                        except Exception:
                            pass
                    return {"ok": True, "quit": True}
                return {"ok": False, "error": f"Unknown cmd {cmd}"}

            async def main():
                loop = asyncio.get_event_loop()
                while True:
                    line = await loop.run_in_executor(None, sys.stdin.readline)
                    if not line:
                        break
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        req = json.loads(line)
                        res = await handle(req)
                    except Exception as e:
                        res = {"ok": False, "error": str(e)}
                    print(json.dumps(res), flush=True)
                    if res.get("quit"):
                        break

            if __name__ == "__main__":
                asyncio.run(main())
            """.trimIndent()
    }
}
