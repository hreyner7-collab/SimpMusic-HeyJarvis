package com.maxrave.simpmusic.lighting

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.AudioVisualizerBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SmartLightingManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
) {
    companion object {
        private const val PREF_KEY = "smart_lighting_v1"
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = PlatformLightingTransport()

    private val _config = MutableStateFlow(SmartLightingConfig())
    val config: StateFlow<SmartLightingConfig> = _config.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastColor = MutableStateFlow(RgbColor(0, 0, 0))
    val lastColor: StateFlow<RgbColor> = _lastColor.asStateFlow()

    private var loopJob: Job? = null
    private var collectorRegistered = false

    fun start() {
        if (loopJob?.isActive == true) return
        scope.launch {
            val raw = runCatching { dataStoreManager.getString(PREF_KEY).first() }.getOrNull()
            if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString<SmartLightingConfig>(raw) }
                    .getOrNull()
                    ?.let { _config.value = it }
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
                            _statusMessage.value =
                                when {
                                    !cfg.enabled -> null
                                    activeDevices.isEmpty() -> "Add & enable a light to sync"
                                    else -> "Paused (0% CPU)"
                                }
                            return@collectLatest
                        }

                        registerIfNeeded()
                        _statusMessage.value = "Syncing ${activeDevices.size} light(s) • ${cfg.mode.label}"
                        val intervalMs = (1000L / cfg.maxFps.coerceIn(5, 30)).coerceAtLeast(33L)
                        try {
                            while (true) {
                                val bands = AudioVisualizerBus.bands.value
                                val color =
                                    SmartLightingFrameEncoder.computeRgbFromSpectrum(
                                        bands = bands,
                                        mode = cfg.mode,
                                        brightness = cfg.brightness,
                                        sensitivity = cfg.sensitivity,
                                    )
                                _lastColor.value = color
                                for (device in activeDevices) {
                                    sendFrame(device, bands, color, cfg)
                                }
                                delay(intervalMs)
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

    private suspend fun sendFrame(
        device: SmartLightDevice,
        bands: FloatArray,
        color: RgbColor,
        cfg: SmartLightingConfig,
    ) {
        when (device.protocol) {
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
        }
    }

    fun setEnabled(enabled: Boolean) = updateConfig { it.copy(enabled = enabled) }

    fun setMode(mode: LightingReactiveMode) = updateConfig { it.copy(mode = mode) }

    fun setBrightness(brightness: Float) = updateConfig { it.copy(brightness = brightness.coerceIn(0.05f, 1f)) }

    fun setSensitivity(sensitivity: Float) = updateConfig { it.copy(sensitivity = sensitivity.coerceIn(0.25f, 3f)) }

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
        val flashColor = RgbColor(255, 110, 40)
        val fakeBands = FloatArray(AudioVisualizerBus.BAND_COUNT) { 0.9f }
        return when (device.protocol) {
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
