package com.maxrave.simpmusic.lighting

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.AudioVisualizerBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SmartLightingManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
) {
    companion object {
        private const val PREF_KEY = "smart_lighting_v1"
        private const val BLE_MIN_INTERVAL_MS = 40L
        private const val WIFI_BULB_MIN_INTERVAL_MS = 95L
        private const val MIN_RGB_DELTA = 4
    }

    private data class QueuedLightFrame(
        val bands: FloatArray,
        val color: RgbColor,
        val dynBrightness: Float,
        val cue: String,
        val isBeatHit: Boolean,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = PlatformLightingTransport()
    private val djEngine = DjDiscoLightEngine()

    private val _config = MutableStateFlow(SmartLightingConfig(syncDelayMs = transport.defaultSyncDelayMs))
    val config: StateFlow<SmartLightingConfig> = _config.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _activeCue = MutableStateFlow<String?>(null)
    val activeCue: StateFlow<String?> = _activeCue.asStateFlow()

    private val _lastColor = MutableStateFlow(RgbColor(0, 0, 0))
    val lastColor: StateFlow<RgbColor> = _lastColor.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<SmartLightDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<SmartLightDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isPairingHue = MutableStateFlow(false)
    val isPairingHue: StateFlow<Boolean> = _isPairingHue.asStateFlow()

    private var loopJob: Job? = null
    private var huePairJob: Job? = null
    private var collectorRegistered = false
    private val inFlightDevices = HashSet<String>()
    private val lastSentAtMsById = HashMap<String, Long>()
    private val lastSentColorById = HashMap<String, RgbColor>()
    private val webhookTriggeredThisPlay = HashSet<String>()

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    fun start() {
        if (loopJob?.isActive == true) return
        scope.launch {
            val raw = runCatching { dataStoreManager.getString(PREF_KEY).first() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString<SmartLightingConfig>(raw) }
                    .getOrNull()
                    ?.let { loaded ->
                        _config.value =
                            if (raw.contains("\"syncDelayMs\"")) {
                                loaded
                            } else {
                                loaded.copy(syncDelayMs = transport.defaultSyncDelayMs)
                            }
                    }
            }
        }
        loopJob =
            scope.launch {
                val isPlayingFlow =
                    mediaPlayerHandler.controlState
                        .map { it.isPlaying }
                        .distinctUntilChanged()
                combine(_config, isPlayingFlow) { cfg, playing -> cfg to playing }
                    .collectLatest { (cfg, playing) ->
                        val activeDevices = cfg.devices.filter { it.enabled && it.host.isNotBlank() }
                        if (!cfg.enabled || activeDevices.isEmpty() || !playing) {
                            unregisterIfNeeded()
                            djEngine.reset()
                            webhookTriggeredThisPlay.clear()
                            _activeCue.value = null
                            _statusMessage.value =
                                when {
                                    !cfg.enabled -> null
                                    activeDevices.isEmpty() -> "Tap Scan to connect your Govee or Wi-Fi lights"
                                    else -> "Paused (0% CPU)"
                                }
                            return@collectLatest
                        }

                        registerIfNeeded()
                        _statusMessage.value = "Syncing ${activeDevices.size} light(s) • ${cfg.mode.label}"
                        val queue = DelayedLightQueue<QueuedLightFrame>()
                        val queueMutex = Mutex()
                        var lastAnalysisMs = nowMs()
                        try {
                            coroutineScope {
                                launch {
                                    AudioVisualizerBus.bands.collect { bands ->
                                        val now = nowMs()
                                        val dtMs = (now - lastAnalysisMs).coerceIn(15L, 200L)
                                        lastAnalysisMs = now
                                        val queued =
                                            if (cfg.mode == LightingReactiveMode.DJ_DISCO) {
                                                val frame = djEngine.step(bands, cfg.brightness, cfg.sensitivity, dtMs)
                                                QueuedLightFrame(bands, frame.color, frame.brightness, frame.activeCue, frame.isBeatHit)
                                            } else {
                                                val raw =
                                                    SmartLightingFrameEncoder.computeRgbFromSpectrum(
                                                        bands = bands,
                                                        mode = cfg.mode,
                                                        brightness = cfg.brightness,
                                                        sensitivity = cfg.sensitivity,
                                                    )
                                                val c = applyLedGamma(raw, gamma = 1.85f)
                                                QueuedLightFrame(bands, c, cfg.brightness, cfg.mode.label, isBeatHit = false)
                                            }
                                        val deliverAt = now + cfg.syncDelayMs.coerceIn(0, 600)
                                        queueMutex.withLock {
                                            queue.enqueue(deliverAt, queued)
                                        }
                                    }
                                }
                                launch {
                                    while (true) {
                                        val now = nowMs()
                                        val due = queueMutex.withLock { queue.drainLatestDue(now) }
                                        if (due != null) {
                                            _lastColor.value = due.color
                                            _activeCue.value = due.cue
                                            for (device in activeDevices) {
                                                val canLaunch = synchronized(inFlightDevices) { inFlightDevices.add(device.id) }
                                                if (canLaunch) {
                                                    launch {
                                                        try {
                                                            sendFrameThrottled(
                                                                device = device,
                                                                bands = due.bands,
                                                                color = due.color,
                                                                dynBrightness = due.dynBrightness,
                                                                cfg = cfg,
                                                                nowMs = now,
                                                                isBeatHit = due.isBeatHit,
                                                            )
                                                        } finally {
                                                            synchronized(inFlightDevices) { inFlightDevices.remove(device.id) }
                                                        }
                                                    }
                                                }
                                            }
                                            delay(12L)
                                        } else {
                                            val nextAt = queueMutex.withLock { queue.nextDeliverAtMs() }
                                            val sleepMs =
                                                if (nextAt != null) (nextAt - nowMs()).coerceIn(8L, 35L) else 35L
                                            delay(sleepMs)
                                        }
                                    }
                                }
                            }
                        } finally {
                            unregisterIfNeeded()
                        }
                    }
            }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        unregisterIfNeeded()
        transport.close()
    }

    fun scanForLights() {
        if (_isScanning.value) return
        _isScanning.value = true
        _statusMessage.value = "Scanning Bluetooth & Wi-Fi..."
        scope.launch {
            try {
                val res = transport.discoverLights(4200L)
                _discoveredDevices.value = res.devices
                _statusMessage.value =
                    if (res.devices.isEmpty()) {
                        res.diagnosticHint ?: "No lights found — make sure Bluetooth is ON or strip is powered"
                    } else {
                        "Found ${res.devices.size} light(s) — tap Connect!"
                    }
            } finally {
                _isScanning.value = false
            }
        }
    }

    suspend fun setManualColor(color: RgbColor) {
        val activeDevices = _config.value.devices.filter { it.enabled && it.host.isNotBlank() }
        if (activeDevices.isEmpty()) return
        _lastColor.value = color
        _activeCue.value = "MANUAL COLOR"
        val brightFactor = _config.value.brightness.coerceIn(0.15f, 1f)
        val b255 = (brightFactor * 255f).roundToInt()
        val scaledColor =
            RgbColor(
                r = (color.r * brightFactor).roundToInt(),
                g = (color.g * brightFactor).roundToInt(),
                b = (color.b * brightFactor).roundToInt(),
            ).clamped()
        for (device in activeDevices) {
            when (device.protocol) {
                LightingProtocol.GOVEE_BLE -> {
                    transport.sendGoveeBlePower(device.host, true)
                    transport.sendGoveeBleColor(device.host, scaledColor, b255)
                }
                LightingProtocol.GOVEE_LAN -> {
                    transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeGoveeLanTurnOnPacket())
                    transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeGoveeLanColorPacket(scaledColor))
                }
                LightingProtocol.PHILIPS_HUE -> {
                    if (device.apiKey.isNotBlank()) {
                        transport.sendHttpRequest(
                            url = hueEndpoint(device),
                            method = "PUT",
                            body = SmartLightingFrameEncoder.encodeHueStatePayload(scaledColor, b255, transitionDecisec = 2),
                        )
                    }
                }
                LightingProtocol.WIZ_UDP -> {
                    transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeWizSetPilotPacket(color, (b255 * 100) / 255))
                }
                LightingProtocol.YEELIGHT_TCP -> {
                    transport.sendTcpLine(device.host, device.port, SmartLightingFrameEncoder.encodeYeelightSetRgbCommand(color))
                }
                LightingProtocol.LIFX_LAN -> {
                    transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeLifxSetColorPacket(color, 80))
                }
                LightingProtocol.OPENRGB_TCP -> {
                    transport.sendOpenRgbColor(device.host, device.port, color, device.ledCount)
                }
                else -> Unit
            }
        }
    }

    suspend fun turnOffAllDevices() {
        val devices = _config.value.devices
        for (device in devices) {
            turnOffDevice(device)
        }
    }

    suspend fun blinkDevice(device: SmartLightDevice): Result<Unit> =
        when (device.protocol) {
            LightingProtocol.GOVEE_BLE -> transport.blinkGoveeBle(device.host)
            LightingProtocol.PHILIPS_HUE ->
                if (device.apiKey.isBlank()) {
                    Result.failure(IllegalStateException("Pair your Hue Bridge first"))
                } else {
                    transport.sendHttpRequest(
                        url = hueEndpoint(device),
                        method = "PUT",
                        body = SmartLightingFrameEncoder.encodeHueBlinkPayload(),
                    ).map { Unit }
                }
            else -> testFlashDevice(device)
        }

    fun cancelHuePairing() {
        huePairJob?.cancel()
        huePairJob = null
        _isPairingHue.value = false
    }

    fun quickConnectDevice(device: SmartLightDevice) {
        if (device.protocol == LightingProtocol.PHILIPS_HUE && device.apiKey.isBlank()) {
            startHueBridgePairing(device)
            return
        }
        updateConfig { cfg ->
            val exists = cfg.devices.any { it.protocol == device.protocol && it.host.equals(device.host, ignoreCase = true) && it.lightId == device.lightId }
            val updatedList =
                if (exists) {
                    cfg.devices.map {
                        if (it.protocol == device.protocol && it.host.equals(device.host, ignoreCase = true) && it.lightId == device.lightId) {
                            it.copy(enabled = true)
                        } else {
                            it
                        }
                    }
                } else {
                    cfg.devices + device.copy(enabled = true)
                }
            cfg.copy(enabled = true, mode = LightingReactiveMode.DJ_DISCO, devices = updatedList)
        }
        scope.launch {
            if (device.protocol == LightingProtocol.GOVEE_BLE) {
                transport.sendGoveeBlePower(device.host, true)
            }
            testFlashDevice(device)
        }
    }

    private fun startHueBridgePairing(bridge: SmartLightDevice) {
        huePairJob?.cancel()
        _isPairingHue.value = true
        huePairJob =
            scope.launch {
                try {
                    val pairUrl = "http://${bridge.host}/api"
                    var pairedKey: String? = null
                    for (attempt in 0 until 20) {
                        val remainingSec = (20 - attempt) * 1.5f
                        _statusMessage.value = "Press the round button on your Philips Hue Bridge (${remainingSec.roundToInt()}s)..."
                        val res = transport.sendHttpRequest(pairUrl, "POST", SmartLightingFrameEncoder.HUE_PAIR_BODY)
                        val body = res.getOrNull()
                        if (body != null) {
                            when (val parsed = SmartLightingFrameEncoder.parseHuePairResponse(body)) {
                                is HuePairResult.Success -> {
                                    pairedKey = parsed.apiKey
                                    break
                                }
                                is HuePairResult.Error -> {
                                    _statusMessage.value = "Hue pairing error: ${parsed.message}"
                                    return@launch
                                }
                                HuePairResult.LinkButtonRequired -> Unit
                            }
                        }
                        delay(1500L)
                    }

                    val apiKey = pairedKey
                    if (apiKey.isNullOrBlank()) {
                        _statusMessage.value = "Hue pairing timed out — press the Bridge button and tap Pair Bridge again"
                        return@launch
                    }

                    val lightsRaw = transport.sendHttpRequest("http://${bridge.host}/api/$apiKey/lights", "GET").getOrNull().orEmpty()
                    val parsedLights = SmartLightingFrameEncoder.parseHueLightsJson(lightsRaw, bridge.host, apiKey)
                    val toAdd =
                        if (parsedLights.isNotEmpty()) {
                            parsedLights
                        } else {
                            listOf(bridge.copy(apiKey = apiKey, lightId = "", enabled = true))
                        }

                    updateConfig { cfg ->
                        val existingIds = cfg.devices.mapTo(HashSet()) { it.id }
                        val merged =
                            cfg.devices.map { existing ->
                                val replacement = toAdd.firstOrNull { it.id == existing.id }
                                replacement?.copy(enabled = true) ?: existing
                            } + toAdd.filter { it.id !in existingIds }
                        cfg.copy(enabled = true, mode = LightingReactiveMode.DJ_DISCO, devices = merged)
                    }
                    _statusMessage.value = "Connected ${toAdd.size} Philips Hue light(s)!"
                    toAdd.firstOrNull()?.let { testFlashDevice(it) }
                } finally {
                    _isPairingHue.value = false
                }
            }
    }

    suspend fun turnOffDevice(device: SmartLightDevice): Result<Unit> =
        when (device.protocol) {
            LightingProtocol.GOVEE_BLE -> transport.sendGoveeBlePower(device.host, false)
            LightingProtocol.PHILIPS_HUE ->
                if (device.apiKey.isBlank()) {
                    Result.success(Unit)
                } else {
                    transport.sendHttpRequest(
                        url = hueEndpoint(device),
                        method = "PUT",
                        body = SmartLightingFrameEncoder.encodeHueOffPayload(),
                    ).map { Unit }
                }
            else -> Result.success(Unit)
        }

    private fun hueEndpoint(device: SmartLightDevice): String =
        if (device.lightId.isNotBlank()) {
            "http://${device.host}/api/${device.apiKey}/lights/${device.lightId}/state"
        } else {
            "http://${device.host}/api/${device.apiKey}/groups/0/action"
        }

    private fun registerIfNeeded() {
        if (!collectorRegistered) {
            AudioVisualizerBus.registerCollector()
            collectorRegistered = true
        }
    }

    private fun unregisterIfNeeded() {
        if (collectorRegistered) {
            AudioVisualizerBus.unregisterCollector()
            collectorRegistered = false
        }
    }

    private suspend fun sendFrameThrottled(
        device: SmartLightDevice,
        bands: FloatArray,
        color: RgbColor,
        dynBrightness: Float,
        cfg: SmartLightingConfig,
        nowMs: Long,
        isBeatHit: Boolean = false,
    ) {
        if (device.protocol == LightingProtocol.ALEXA_WEBHOOK) {
            if (webhookTriggeredThisPlay.add(device.id)) {
                transport.triggerWebhook(device.host)
            }
            return
        }

        val minInterval =
            when (device.protocol) {
                LightingProtocol.GOVEE_BLE -> BLE_MIN_INTERVAL_MS
                LightingProtocol.PHILIPS_HUE -> {
                    val hueCount =
                        cfg.devices.count {
                            it.enabled && it.protocol == LightingProtocol.PHILIPS_HUE && it.host.equals(device.host, ignoreCase = true)
                        }.coerceAtLeast(1)
                    (100L * hueCount).coerceAtLeast(110L)
                }
                LightingProtocol.GOVEE_LAN, LightingProtocol.WIZ_UDP, LightingProtocol.YEELIGHT_TCP -> WIFI_BULB_MIN_INTERVAL_MS
                else -> 0L
            }
        if (minInterval > 0L) {
            val prevAt = lastSentAtMsById[device.id] ?: 0L
            val effectiveInterval = if (isBeatHit) minInterval / 2L else minInterval
            if (nowMs - prevAt < effectiveInterval) return
            val prevColor = lastSentColorById[device.id]
            if (!isBeatHit && prevColor != null && prevColor.maxChannelDelta(color) < MIN_RGB_DELTA) return
        }

        lastSentAtMsById[device.id] = nowMs
        lastSentColorById[device.id] = color

        when (device.protocol) {
            LightingProtocol.GOVEE_BLE -> {
                transport.sendGoveeBleColor(device.host, color, 255)
            }

            LightingProtocol.GOVEE_LAN -> {
                transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeGoveeLanColorPacket(color))
            }

            LightingProtocol.PHILIPS_HUE -> {
                if (device.apiKey.isNotBlank()) {
                    transport.sendHttpRequest(
                        url = hueEndpoint(device),
                        method = "PUT",
                        body = SmartLightingFrameEncoder.encodeHueStatePayload(color, 255, transitionDecisec = 1),
                    )
                }
            }

            LightingProtocol.WIZ_UDP -> {
                val pct = (dynBrightness.coerceIn(0.1f, 1f) * 100f).roundToInt()
                transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeWizSetPilotPacket(color, pct))
            }

            LightingProtocol.YEELIGHT_TCP -> {
                transport.sendTcpLine(device.host, device.port, SmartLightingFrameEncoder.encodeYeelightSetRgbCommand(color))
            }

            LightingProtocol.WLED_UDP -> {
                val payload =
                    SmartLightingFrameEncoder.encodeWledDrgbPacket(
                        bands = bands,
                        ledCount = device.ledCount,
                        mode = cfg.mode,
                        brightness = cfg.brightness,
                        sensitivity = cfg.sensitivity,
                    )
                transport.sendUdpPacket(device.host, device.port, payload)
            }

            LightingProtocol.LIFX_LAN -> {
                val payload = SmartLightingFrameEncoder.encodeLifxSetColorPacket(color, transitionMs = 50)
                transport.sendUdpPacket(device.host, device.port, payload)
            }

            LightingProtocol.OPENRGB_TCP -> {
                transport.sendOpenRgbColor(device.host, device.port, color, device.ledCount)
            }

            LightingProtocol.ALEXA_WEBHOOK -> Unit
        }
    }

    fun setEnabled(enabled: Boolean) = updateConfig { it.copy(enabled = enabled) }

    fun setMode(mode: LightingReactiveMode) = updateConfig { it.copy(mode = mode) }

    fun setBrightness(brightness: Float) = updateConfig { it.copy(brightness = brightness.coerceIn(0.05f, 1f)) }

    fun setSensitivity(sensitivity: Float) = updateConfig { it.copy(sensitivity = sensitivity.coerceIn(0.25f, 3f)) }

    fun setSyncDelayMs(delayMs: Int) = updateConfig { it.copy(syncDelayMs = delayMs.coerceIn(0, 600)) }

    fun addDevice(device: SmartLightDevice) =
        updateConfig { cfg ->
            cfg.copy(devices = cfg.devices + device)
        }

    fun toggleDevice(id: String) =
        updateConfig { cfg ->
            cfg.copy(devices = cfg.devices.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        }

    fun removeDevice(id: String) =
        updateConfig { cfg ->
            cfg.copy(devices = cfg.devices.filterNot { it.id == id })
        }

    suspend fun testFlashDevice(device: SmartLightDevice): Result<Unit> {
        val flashColor = RgbColor(255, 16, 24)
        val fakeBands = FloatArray(AudioVisualizerBus.BAND_COUNT) { 0.9f }
        return when (device.protocol) {
            LightingProtocol.GOVEE_BLE -> {
                transport.sendGoveeBlePower(device.host, true)
                transport.sendGoveeBleColor(device.host, flashColor, 255)
            }

            LightingProtocol.GOVEE_LAN -> {
                transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeGoveeLanTurnOnPacket())
                transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeGoveeLanColorPacket(flashColor))
            }

            LightingProtocol.PHILIPS_HUE -> {
                if (device.apiKey.isBlank()) {
                    Result.failure(IllegalStateException("Pair your Hue Bridge first"))
                } else {
                    transport.sendHttpRequest(
                        url = hueEndpoint(device),
                        method = "PUT",
                        body = SmartLightingFrameEncoder.encodeHueStatePayload(flashColor, 255, transitionDecisec = 1),
                    ).map { Unit }
                }
            }

            LightingProtocol.WIZ_UDP -> {
                transport.sendUdpPacket(device.host, device.port, SmartLightingFrameEncoder.encodeWizSetPilotPacket(flashColor, 100))
            }

            LightingProtocol.YEELIGHT_TCP -> {
                transport.sendTcpLine(device.host, device.port, SmartLightingFrameEncoder.encodeYeelightSetRgbCommand(flashColor))
            }

            LightingProtocol.WLED_UDP -> {
                val payload =
                    SmartLightingFrameEncoder.encodeWledDrgbPacket(
                        bands = fakeBands,
                        ledCount = device.ledCount,
                        mode = LightingReactiveMode.BASS_FLASH,
                        brightness = 1f,
                        sensitivity = 1f,
                    )
                transport.sendUdpPacket(device.host, device.port, payload)
            }

            LightingProtocol.LIFX_LAN -> {
                transport.sendUdpPacket(
                    device.host,
                    device.port,
                    SmartLightingFrameEncoder.encodeLifxSetColorPacket(flashColor, transitionMs = 80),
                )
            }

            LightingProtocol.OPENRGB_TCP -> {
                transport.sendOpenRgbColor(device.host, device.port, flashColor, device.ledCount)
            }

            LightingProtocol.ALEXA_WEBHOOK -> {
                transport.triggerWebhook(device.host)
            }
        }
    }

    private fun updateConfig(transform: (SmartLightingConfig) -> SmartLightingConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        scope.launch {
            runCatching {
                dataStoreManager.putString(PREF_KEY, json.encodeToString(updated))
            }
        }
    }
}
