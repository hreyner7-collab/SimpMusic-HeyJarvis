package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.DeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles
import com.maxrave.domain.data.player.DeviceAudioProfiles.applyDeviceAudioProfile
import com.maxrave.domain.data.player.DeviceAudioProfiles.captureProfileFor
import com.maxrave.domain.data.player.DeviceAudioProfiles.deviceAudioProfilesFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.perDeviceAudioProfilesEnabledFlow
import com.maxrave.domain.data.player.DeviceAudioProfiles.saveDeviceAudioProfile
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "DeviceAudioProfileCtrl"

/**
 * Watches the player's active [AudioOutput] across both Android and Desktop. Whenever the active
 * output changes (e.g. connecting AirPods, switching to Car Bluetooth, or unplugging headphones)
 * and Per-Device Audio Profiles are enabled, automatically applies that device's saved EQ, AutoEQ,
 * Stereo Widener, Normalize Volume, and Crossfade settings.
 */
class DeviceAudioProfileController(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _activeOutput = MutableStateFlow<AudioOutput?>(null)
    val activeOutput: StateFlow<AudioOutput?> = _activeOutput.asStateFlow()

    private val _lastAppliedProfile = MutableStateFlow<DeviceAudioProfile?>(null)
    val lastAppliedProfile: StateFlow<DeviceAudioProfile?> = _lastAppliedProfile.asStateFlow()

    private var lastHandledDeviceKey: String? = null
    private var watchJob: Job? = null

    fun start() {
        if (watchJob?.isActive == true) return
        watchJob =
            scope.launch {
                combine(
                    mediaPlayerHandler.player.audioOutputs,
                    dataStoreManager.perDeviceAudioProfilesEnabledFlow(),
                    dataStoreManager.deviceAudioProfilesFlow(),
                ) { outputs, enabled, profiles ->
                    Triple(outputs.firstOrNull { it.isActive } ?: outputs.firstOrNull(), enabled, profiles)
                }.distinctUntilChanged { a, b ->
                    a.first?.let { DeviceAudioProfiles.deviceKeyFor(it) } ==
                        b.first?.let { DeviceAudioProfiles.deviceKeyFor(it) } &&
                        a.second == b.second &&
                        a.third == b.third
                }.collect { (active, enabled, profiles) ->
                    _activeOutput.value = active
                    if (!enabled || active == null) {
                        if (!enabled) lastHandledDeviceKey = null
                        return@collect
                    }
                    val key = DeviceAudioProfiles.deviceKeyFor(active)
                    if (key == lastHandledDeviceKey) return@collect
                    lastHandledDeviceKey = key
                    val matched = profiles.firstOrNull { it.deviceKey == key }
                    if (matched != null) {
                        Logger.i(TAG, "Switching audio profile -> ${matched.displayName} ($key)")
                        runCatching {
                            dataStoreManager.applyDeviceAudioProfile(matched)
                            _lastAppliedProfile.value = matched
                        }.onFailure {
                            Logger.w(TAG, "Failed applying profile $key: ${it.message}")
                        }
                    }
                }
            }
    }

    suspend fun saveCurrentForActiveDevice(): DeviceAudioProfile? {
        val outputs = mediaPlayerHandler.player.audioOutputs.value
        val active = outputs.firstOrNull { it.isActive } ?: outputs.firstOrNull() ?: return null
        val captured = dataStoreManager.captureProfileFor(active)
        dataStoreManager.saveDeviceAudioProfile(captured)
        lastHandledDeviceKey = captured.deviceKey
        _lastAppliedProfile.value = captured
        return captured
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }
}
