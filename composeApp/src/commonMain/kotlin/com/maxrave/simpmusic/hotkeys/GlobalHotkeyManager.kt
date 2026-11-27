package com.maxrave.simpmusic.hotkeys

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

private const val TAG = "GlobalHotkeyManager"
private const val VOLUME_STEP = 0.05f

/**
 * Manages Windows system-wide media & Hey Jarvis hotkeys persisted in DataStore (`global_hotkeys_v1`).
 */
class GlobalHotkeyManager(
    private val dataStoreManager: DataStoreManager,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val engine: PlatformGlobalHotkeyEngine = PlatformGlobalHotkeyEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    /** Optional hook wired by desktop startup to trigger Hey Jarvis listening on global hotkey press. */
    @Volatile
    var onTriggerJarvis: (() -> Unit)? = null

    val isSupported: Boolean = engine.isSupportedOnCurrentPlatform

    private val _config = MutableStateFlow(GlobalHotkeyConfig())
    val config: StateFlow<GlobalHotkeyConfig> = _config.asStateFlow()

    private val _registeredActions = MutableStateFlow<Set<HotkeyAction>>(emptySet())
    val registeredActions: StateFlow<Set<HotkeyAction>> = _registeredActions.asStateFlow()

    private val _duplicateWarnings = MutableStateFlow<Map<Pair<Int, Int>, List<HotkeyAction>>>(emptyMap())
    val duplicateWarnings: StateFlow<Map<Pair<Int, Int>, List<HotkeyAction>>> = _duplicateWarnings.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val raw =
                runCatching { dataStoreManager.getString(GlobalHotkeyStoreCodec.DATASTORE_KEY).firstOrNull() }
                    .getOrNull()
            val loaded = GlobalHotkeyStoreCodec.decode(raw)
            _config.value = loaded
            _duplicateWarnings.value = HotkeyShortcutFormatter.findDuplicateShortcuts(loaded.bindings)
            applyToEngine(loaded)
        }
    }

    fun stop() {
        started = false
        runCatching { engine.stop() }
        _registeredActions.value = emptySet()
    }

    fun setEnabled(enabled: Boolean) {
        updateConfig(_config.value.copy(enabled = enabled))
    }

    fun updateBinding(
        action: HotkeyAction,
        modifiers: Int,
        virtualKey: Int,
        enabled: Boolean = true,
    ) {
        val current = _config.value
        val updatedBindings =
            current.bindings.map { binding ->
                if (binding.action == action) {
                    HotkeyBinding(
                        action = action,
                        modifiers = modifiers,
                        virtualKey = virtualKey,
                        enabled = enabled,
                    ).normalized()
                } else {
                    binding
                }
            }
        updateConfig(current.copy(bindings = updatedBindings))
    }

    fun toggleBinding(
        action: HotkeyAction,
        enabled: Boolean,
    ) {
        val current = _config.value
        val updatedBindings =
            current.bindings.map { binding ->
                if (binding.action == action) binding.copy(enabled = enabled) else binding
            }
        updateConfig(current.copy(bindings = updatedBindings))
    }

    fun resetToDefaults() {
        updateConfig(GlobalHotkeyConfig(enabled = _config.value.enabled, bindings = GlobalHotkeyConfig.defaultBindings()))
    }

    internal fun dispatchAction(action: HotkeyAction) {
        Logger.d(TAG, "Global hotkey triggered: $action")
        scope.launch {
            runCatching {
                when (action) {
                    HotkeyAction.PLAY_PAUSE ->
                        withContext(mainDispatcher) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.PlayPause)
                        }

                    HotkeyAction.NEXT_TRACK ->
                        withContext(mainDispatcher) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Next)
                        }

                    HotkeyAction.PREVIOUS_TRACK ->
                        withContext(mainDispatcher) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.Previous)
                        }

                    HotkeyAction.VOLUME_UP ->
                        withContext(mainDispatcher) {
                            val currentVol = mediaPlayerHandler.player.volume.coerceIn(0f, 1f)
                            val nextVol = (currentVol + VOLUME_STEP).coerceIn(0f, 1f)
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateVolume(nextVol))
                        }

                    HotkeyAction.VOLUME_DOWN ->
                        withContext(mainDispatcher) {
                            val currentVol = mediaPlayerHandler.player.volume.coerceIn(0f, 1f)
                            val nextVol = (currentVol - VOLUME_STEP).coerceIn(0f, 1f)
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.UpdateVolume(nextVol))
                        }

                    HotkeyAction.LIKE_CURRENT ->
                        withContext(mainDispatcher) {
                            mediaPlayerHandler.onPlayerEvent(PlayerEvent.ToggleLike)
                        }

                    HotkeyAction.TRIGGER_JARVIS ->
                        withContext(mainDispatcher) {
                            onTriggerJarvis?.invoke()
                        }
                }
            }.onFailure {
                Logger.w(TAG, "Failed executing hotkey action $action: ${it.message}")
            }
        }
    }

    private fun updateConfig(next: GlobalHotkeyConfig) {
        val normalized = next.normalized()
        _config.value = normalized
        _duplicateWarnings.value = HotkeyShortcutFormatter.findDuplicateShortcuts(normalized.bindings)
        scope.launch {
            runCatching {
                dataStoreManager.putString(
                    GlobalHotkeyStoreCodec.DATASTORE_KEY,
                    GlobalHotkeyStoreCodec.encode(normalized),
                )
            }.onFailure {
                Logger.w(TAG, "Failed to save global hotkey config: ${it.message}")
            }
            applyToEngine(normalized)
        }
    }

    private fun applyToEngine(cfg: GlobalHotkeyConfig) {
        if (!isSupported || !cfg.enabled) {
            runCatching { engine.stop() }
            _registeredActions.value = emptySet()
            return
        }
        val active =
            runCatching {
                engine.start(cfg.bindings) { action ->
                    dispatchAction(action)
                }
            }.onFailure {
                Logger.w(TAG, "Failed starting hotkey engine: ${it.message}")
            }.getOrDefault(emptySet())
        _registeredActions.value = active
    }
}
