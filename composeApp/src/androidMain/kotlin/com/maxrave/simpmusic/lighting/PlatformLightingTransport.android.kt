package com.maxrave.simpmusic.lighting

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
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
    actual val defaultSyncDelayMs: Int = 180

    @Volatile
    private var udpSocket: DatagramSocket? = null

    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val gattWriteChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val initializedHardwareByAddr = ConcurrentHashMap<String, Boolean>()
    private val writeInFlightUntilMs = ConcurrentHashMap<String, Long>()

    private fun getOrCreateSocket(): DatagramSocket {
        val existing = udpSocket
        if (existing != null && !existing.isClosed) return existing
        return DatagramSocket().also { udpSocket = it }
    }

    actual suspend fun discoverLights(timeoutMs: Long): LightDiscoveryResult =
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
                var bleHint: String? = null
                coroutineScope {
                    val bleJob = async { scanGoveeBle(ctx, timeoutMs.coerceIn(2500L, 5000L)) }
                    val goveeLanJob = async { runCatching { scanGoveeLan(1800L) }.getOrDefault(emptyList()) }
                    val wizJob = async { runCatching { scanWiz(1600L) }.getOrDefault(emptyList()) }
                    val lifxJob = async { runCatching { scanLifx(1600L) }.getOrDefault(emptyList()) }
                    val yeelightJob = async { runCatching { scanYeelight(1600L) }.getOrDefault(emptyList()) }

                    val bleRes = bleJob.await()
                    found += bleRes.devices
                    bleHint = bleRes.diagnosticHint
                    found += goveeLanJob.await()
                    found += wizJob.await()
                    found += lifxJob.await()
                    found += yeelightJob.await()
                }
                val unique = found.distinctBy { "${it.protocol}:${it.host}" }
                LightDiscoveryResult(
                    devices = unique,
                    diagnosticHint = if (unique.isEmpty()) bleHint ?: "Make sure Bluetooth is ON and your Govee strip is plugged in" else null,
                )
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
                if (initializedHardwareByAddr.putIfAbsent(address, true) == null) {
                    writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBlePower(true))
                    delay(25)
                    writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleBrightness(255))
                    delay(25)
                }
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
                if (on) {
                    delay(25)
                    writeGoveeBleFrame(address, SmartLightingFrameEncoder.encodeGoveeBleBrightness(255))
                    initializedHardwareByAddr[address] = true
                } else {
                    initializedHardwareByAddr.remove(address)
                }
                Unit
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
        initializedHardwareByAddr.clear()
        writeInFlightUntilMs.clear()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanGoveeBle(context: Context?, timeoutMs: Long): LightDiscoveryResult {
        if (context == null) return LightDiscoveryResult(emptyList(), "Android context unavailable")
        val hasPerm =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        if (!hasPerm) {
            return LightDiscoveryResult(emptyList(), "Allow Nearby Devices (Bluetooth) permission to find Govee lights")
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return LightDiscoveryResult(emptyList(), "Bluetooth unavailable on this device")
        val adapter: BluetoothAdapter = manager.adapter
            ?: return LightDiscoveryResult(emptyList(), "Bluetooth adapter unavailable")
        if (!adapter.isEnabled) {
            return LightDiscoveryResult(emptyList(), "Turn on Bluetooth on your phone to find Govee lights")
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return LightDiscoveryResult(emptyList(), "Bluetooth LE scanner unavailable")

        val goveeServiceUuid = ParcelUuid.fromString(SmartLightingFrameEncoder.GOVEE_BLE_SERVICE_UUID)
        val found = ConcurrentHashMap<String, SmartLightDevice>()

        // Also include already-bonded Govee devices if any.
        runCatching {
            adapter.bondedDevices?.forEach { dev ->
                val name = dev.name
                if (SmartLightingFrameEncoder.isGoveeBleName(name) && !dev.address.isNullOrBlank()) {
                    found[dev.address] =
                        SmartLightDevice(
                            id = "govee-ble-${dev.address}",
                            name = formatFriendlyLightName(name ?: "Govee ${dev.address}"),
                            host = dev.address,
                            port = 0,
                            protocol = LightingProtocol.GOVEE_BLE,
                            enabled = true,
                        )
                }
            }
        }

        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    val name = result.scanRecord?.deviceName ?: runCatching { dev.name }.getOrNull()
                    val hasGoveeUuid = result.scanRecord?.serviceUuids?.any { it == goveeServiceUuid } == true
                    if (SmartLightingFrameEncoder.isGoveeBleName(name) || hasGoveeUuid) {
                        val addr = dev.address ?: return
                        found[addr] =
                            SmartLightDevice(
                                id = "govee-ble-$addr",
                                name = formatFriendlyLightName(name ?: "Govee $addr"),
                                host = addr,
                                port = 0,
                                protocol = LightingProtocol.GOVEE_BLE,
                                enabled = true,
                            )
                    }
                }
            }
        return try {
            val settings =
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            scanner.startScan(null, settings, callback)
            delay(timeoutMs)
            LightDiscoveryResult(found.values.toList())
        } catch (_: SecurityException) {
            LightDiscoveryResult(emptyList(), "Allow Nearby Devices (Bluetooth) permission to find Govee lights")
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
            val gattCallback =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { g.discoverServices() }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gattClients.remove(address)
                            gattWriteChars.remove(address)
                            initializedHardwareByAddr.remove(address)
                            writeInFlightUntilMs.remove(address)
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                        val service = g.getService(UUID.fromString(SmartLightingFrameEncoder.GOVEE_BLE_SERVICE_UUID))
                        val writeChar = service?.getCharacteristic(UUID.fromString(SmartLightingFrameEncoder.GOVEE_BLE_WRITE_CHAR_UUID))
                        if (writeChar != null) {
                            gattWriteChars[address] = writeChar
                        }
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        writeInFlightUntilMs.remove(address)
                    }
                }
            val connectedGatt =
                device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    ?: error("Failed to connect GATT to $address")
            gattClients[address] = connectedGatt
            // Wait briefly for service discovery
            repeat(20) {
                if (gattWriteChars.containsKey(address)) return@repeat
                delay(150)
            }
            gatt = connectedGatt
            char = gattWriteChars[address] ?: error("Govee GATT characteristic not ready")
        }

        var waitMs = 0
        while (waitMs < 75 && (writeInFlightUntilMs[address] ?: 0L) > System.currentTimeMillis()) {
            delay(8)
            waitMs += 8
        }
        writeInFlightUntilMs[address] = System.currentTimeMillis() + 85L

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
