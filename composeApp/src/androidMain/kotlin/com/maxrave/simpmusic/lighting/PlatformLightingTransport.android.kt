package com.maxrave.simpmusic.lighting

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

actual class PlatformLightingTransport actual constructor() {
    @Volatile
    private var udpSocket: DatagramSocket? = null

    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val gattWriteChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()

    private fun getOrCreateSocket(): DatagramSocket {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        return DatagramSocket().also { udpSocket = it }
    }

    actual suspend fun discoverLights(timeoutMs: Long): List<SmartLightDevice> =
        withContext(Dispatchers.IO) {
            val ctx = runCatching { getKoin().get<Context>() }.getOrNull()
            val multicastLock =
                runCatching {
                    val wifi = ctx?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifi?.createMulticastLock("SimpMusic-SmartLights")?.apply {
                        setReferenceCounted(true)
                        acquire()
                    }
                }.getOrNull()

            try {
                val found = mutableListOf<SmartLightDevice>()
                coroutineScope {
                    val bleJob = async { runCatching { scanGoveeBle(ctx, timeoutMs.coerceIn(2500L, 5000L)) }.getOrDefault(emptyList()) }
                    val goveeLanJob = async { runCatching { scanGoveeLan(1800L) }.getOrDefault(emptyList()) }
                    val wizJob = async { runCatching { scanWiz(1600L) }.getOrDefault(emptyList()) }
                    val lifxJob = async { runCatching { scanLifx(1600L) }.getOrDefault(emptyList()) }
                    val yeelightJob = async { runCatching { scanYeelight(1600L) }.getOrDefault(emptyList()) }

                    found += bleJob.await()
                    found += goveeLanJob.await()
                    found += wizJob.await()
                    found += lifxJob.await()
                    found += yeelightJob.await()
                }
                found.distinctBy { "${it.protocol}:${it.host}" }
            } finally {
                runCatching {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                }
            }
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
            runCatching {
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleBrightness(brightness0To255))
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleColor(color))
            }
        }

    actual suspend fun sendGoveeBlePower(
        address: String,
        on: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBlePower(on))
            }
        }

    actual suspend fun blinkGoveeBle(address: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBlePower(true))
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleBrightness(255))
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleColor(RgbColor(255, 0, 0)))
                delay(850)
                writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBlePower(false))
            }
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

    @SuppressLint("MissingPermission")
    actual fun close() {
        runCatching { udpSocket?.close() }
        udpSocket = null
        gattClients.values.forEach { gatt ->
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
        gattClients.clear()
        gattWriteChars.clear()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanGoveeBle(context: Context?, timeoutMs: Long): List<SmartLightDevice> {
        if (context == null) return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter: BluetoothAdapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()

        val found = ConcurrentHashMap<String, SmartLightDevice>()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    val name = runCatching { dev.name }.getOrNull() ?: result.scanRecord?.deviceName
                    if (SmartLightingFrameEncoder.isGoveeBleName(name)) {
                        val addr = dev.address ?: return
                        found[addr] =
                            SmartLightDevice(
                                id = "govee-ble-$addr",
                                name = name ?: "Govee $addr",
                                host = addr,
                                port = 0,
                                protocol = LightingProtocol.GOVEE_BLE,
                                enabled = true,
                            )
                    }
                }
            }
        return try {
            scanner.startScan(callback)
            delay(timeoutMs)
            found.values.toList()
        } catch (_: SecurityException) {
            emptyList()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeGoveeBleFrame(address: String, frame: ByteArray) {
        val ctx = getKoin().get<Context>()
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: error("Bluetooth unavailable")
        val adapter = manager.adapter ?: error("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) error("Bluetooth is off")

        var char = gattWriteChars[address]
        var gatt = gattClients[address]
        if (gatt == null || char == null) {
            val device = adapter.getRemoteDevice(address)
            val connectedGatt =
                device.connectGatt(
                    ctx,
                    false,
                    object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                runCatching { g.discoverServices() }
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                gattClients.remove(address)
                                gattWriteChars.remove(address)
                            }
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            val service = g.getService(UUID.fromString(SmartLightingFrameEncoder.GOVEE_BLE_SERVICE_UUID))
                            val writeChar = service?.getCharacteristic(UUID.fromString(SmartLightingFrameEncoder.GOVEE_BLE_WRITE_CHAR_UUID))
                            if (writeChar != null) {
                                gattWriteChars[address] = writeChar
                            }
                        }
                    },
                ) ?: error("Failed to connect GATT to $address")
            gattClients[address] = connectedGatt
            // Wait briefly for service discovery
            repeat(20) {
                if (gattWriteChars.containsKey(address)) return@repeat
                delay(150)
            }
            gatt = connectedGatt
            char = gattWriteChars[address] ?: error("Govee GATT characteristic not ready")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = frame
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

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
}
