package com.maxrave.simpmusic.hotkeys

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object HotkeyModifiers {
    const val MOD_ALT: Int = 1
    const val MOD_CONTROL: Int = 2
    const val MOD_SHIFT: Int = 4
    const val MOD_WIN: Int = 8
    const val USER_MODIFIER_MASK: Int = MOD_ALT or MOD_CONTROL or MOD_SHIFT or MOD_WIN
    const val MOD_NOREPEAT: Int = 0x4000
}

object HotkeyVirtualKeys {
    const val VK_SPACE: Int = 0x20
    const val VK_LEFT: Int = 0x25
    const val VK_UP: Int = 0x26
    const val VK_RIGHT: Int = 0x27
    const val VK_DOWN: Int = 0x28

    const val VK_A: Int = 0x41
    const val VK_J: Int = 0x4A
    const val VK_K: Int = 0x4B
    const val VK_L: Int = 0x4C
    const val VK_M: Int = 0x4D
    const val VK_N: Int = 0x4E
    const val VK_P: Int = 0x50
    const val VK_V: Int = 0x56
    const val VK_Z: Int = 0x5A

    const val VK_0: Int = 0x30
    const val VK_9: Int = 0x39

    const val VK_F1: Int = 0x70
    const val VK_F12: Int = 0x7B

    const val VK_MEDIA_NEXT_TRACK: Int = 0xB0
    const val VK_MEDIA_PREV_TRACK: Int = 0xB1
    const val VK_MEDIA_STOP: Int = 0xB2
    const val VK_MEDIA_PLAY_PAUSE: Int = 0xB3
    const val VK_OEM_PLUS: Int = 0xBB
    const val VK_OEM_MINUS: Int = 0xBD
}

@Serializable
enum class HotkeyAction(
    val displayTitle: String,
) {
    PLAY_PAUSE("Play / Pause"),
    NEXT_TRACK("Next Track"),
    PREVIOUS_TRACK("Previous Track"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    LIKE_CURRENT("Like Current Song"),
    TRIGGER_JARVIS("Trigger Hey Jarvis"),
}

@Serializable
data class HotkeyBinding(
    val action: HotkeyAction,
    /** Bitmask: MOD_ALT=1, MOD_CONTROL=2, MOD_SHIFT=4, MOD_WIN=8. */
    val modifiers: Int,
    /** Win32 Virtual-Key code (e.g., 0x20 for Space, 0x4C for 'L'). */
    val virtualKey: Int,
    val enabled: Boolean = true,
    val displayLabel: String = HotkeyShortcutFormatter.format(modifiers, virtualKey),
) {
    fun normalized(): HotkeyBinding {
        val cleanMods = modifiers and HotkeyModifiers.USER_MODIFIER_MASK
        return copy(
            modifiers = cleanMods,
            displayLabel = HotkeyShortcutFormatter.format(cleanMods, virtualKey),
        )
    }
}

@Serializable
data class GlobalHotkeyConfig(
    val enabled: Boolean = true,
    val bindings: List<HotkeyBinding> = defaultBindings(),
) {
    fun normalized(): GlobalHotkeyConfig {
        val byAction = bindings.associateBy { it.action }
        val complete =
            HotkeyAction.entries.map { action ->
                (byAction[action] ?: defaultBindingFor(action)).normalized()
            }
        return copy(bindings = complete)
    }

    companion object {
        fun defaultBindingFor(action: HotkeyAction): HotkeyBinding {
            val ctrlAlt = HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_ALT
            return when (action) {
                HotkeyAction.PLAY_PAUSE ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_SPACE,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+Space",
                    )

                HotkeyAction.NEXT_TRACK ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_RIGHT,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+Right",
                    )

                HotkeyAction.PREVIOUS_TRACK ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_LEFT,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+Left",
                    )

                HotkeyAction.VOLUME_UP ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_UP,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+Up",
                    )

                HotkeyAction.VOLUME_DOWN ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_DOWN,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+Down",
                    )

                HotkeyAction.LIKE_CURRENT ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_L,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+L",
                    )

                HotkeyAction.TRIGGER_JARVIS ->
                    HotkeyBinding(
                        action = action,
                        modifiers = ctrlAlt,
                        virtualKey = HotkeyVirtualKeys.VK_J,
                        enabled = true,
                        displayLabel = "Ctrl+Alt+J",
                    )
            }
        }

        fun defaultBindings(): List<HotkeyBinding> = HotkeyAction.entries.map { defaultBindingFor(it) }
    }
}

object HotkeyShortcutFormatter {
    private val keyCodeToName: Map<Int, String> =
        buildMap {
            put(HotkeyVirtualKeys.VK_SPACE, "Space")
            put(HotkeyVirtualKeys.VK_LEFT, "Left")
            put(HotkeyVirtualKeys.VK_UP, "Up")
            put(HotkeyVirtualKeys.VK_RIGHT, "Right")
            put(HotkeyVirtualKeys.VK_DOWN, "Down")
            put(HotkeyVirtualKeys.VK_MEDIA_NEXT_TRACK, "MediaNext")
            put(HotkeyVirtualKeys.VK_MEDIA_PREV_TRACK, "MediaPrev")
            put(HotkeyVirtualKeys.VK_MEDIA_STOP, "MediaStop")
            put(HotkeyVirtualKeys.VK_MEDIA_PLAY_PAUSE, "MediaPlayPause")
            put(HotkeyVirtualKeys.VK_OEM_PLUS, "Plus")
            put(HotkeyVirtualKeys.VK_OEM_MINUS, "Minus")
            for (vk in HotkeyVirtualKeys.VK_A..HotkeyVirtualKeys.VK_Z) {
                put(vk, vk.toChar().toString())
            }
            for (vk in HotkeyVirtualKeys.VK_0..HotkeyVirtualKeys.VK_9) {
                put(vk, vk.toChar().toString())
            }
            for (i in 1..12) {
                put(HotkeyVirtualKeys.VK_F1 + (i - 1), "F$i")
            }
        }

    private val nameToKeyCode: Map<String, Int> =
        buildMap {
            keyCodeToName.forEach { (code, name) ->
                put(name.uppercase(), code)
            }
            put("SPACEBAR", HotkeyVirtualKeys.VK_SPACE)
            put("ARROWLEFT", HotkeyVirtualKeys.VK_LEFT)
            put("ARROWUP", HotkeyVirtualKeys.VK_UP)
            put("ARROWRIGHT", HotkeyVirtualKeys.VK_RIGHT)
            put("ARROWDOWN", HotkeyVirtualKeys.VK_DOWN)
            put("+", HotkeyVirtualKeys.VK_OEM_PLUS)
            put("-", HotkeyVirtualKeys.VK_OEM_MINUS)
        }

    fun keyName(virtualKey: Int): String =
        keyCodeToName[virtualKey] ?: "0x${virtualKey.toString(16).uppercase()}"

    fun format(
        modifiers: Int,
        virtualKey: Int,
    ): String {
        val parts = mutableListOf<String>()
        if ((modifiers and HotkeyModifiers.MOD_CONTROL) != 0) parts += "Ctrl"
        if ((modifiers and HotkeyModifiers.MOD_ALT) != 0) parts += "Alt"
        if ((modifiers and HotkeyModifiers.MOD_SHIFT) != 0) parts += "Shift"
        if ((modifiers and HotkeyModifiers.MOD_WIN) != 0) parts += "Win"
        parts += keyName(virtualKey)
        return parts.joinToString("+")
    }

    /**
     * Parses a human-readable shortcut label such as `"Ctrl+Alt+Space"` or `"Shift+Win+F8"`
     * into `(modifiers, virtualKey)`, returning null if invalid or missing a recognized key.
     */
    fun parse(label: String): Pair<Int, Int>? {
        val tokens =
            label
                .split("+")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var modifiers = 0
        var virtualKey: Int? = null
        for (token in tokens) {
            when (token.uppercase()) {
                "CTRL", "CONTROL" -> modifiers = modifiers or HotkeyModifiers.MOD_CONTROL
                "ALT", "OPTION" -> modifiers = modifiers or HotkeyModifiers.MOD_ALT
                "SHIFT" -> modifiers = modifiers or HotkeyModifiers.MOD_SHIFT
                "WIN", "WINDOWS", "META", "SUPER" -> modifiers = modifiers or HotkeyModifiers.MOD_WIN
                else -> {
                    val code =
                        nameToKeyCode[token.uppercase()]
                            ?: token.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                            ?: return null
                    if (virtualKey != null) return null // Multiple primary keys in one shortcut
                    virtualKey = code
                }
            }
        }
        val key = virtualKey ?: return null
        return modifiers to key
    }

    /**
     * Returns every `(modifiers, virtualKey)` pair assigned to more than one enabled action.
     */
    fun findDuplicateShortcuts(bindings: List<HotkeyBinding>): Map<Pair<Int, Int>, List<HotkeyAction>> =
        bindings
            .filter { it.enabled }
            .groupBy { (it.modifiers and HotkeyModifiers.USER_MODIFIER_MASK) to it.virtualKey }
            .filterValues { it.size > 1 }
            .mapValues { (_, list) -> list.map { it.action } }

    /**
     * Curated conflict-safe preset shortcuts per action for the Settings UI picker.
     */
    fun presetCombinations(action: HotkeyAction): List<Pair<Int, Int>> {
        val ctrlAlt = HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_ALT
        val ctrlShift = HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_SHIFT
        val altShift = HotkeyModifiers.MOD_ALT or HotkeyModifiers.MOD_SHIFT
        return when (action) {
            HotkeyAction.PLAY_PAUSE ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_SPACE,
                    ctrlAlt to HotkeyVirtualKeys.VK_P,
                    ctrlShift to HotkeyVirtualKeys.VK_SPACE,
                    altShift to HotkeyVirtualKeys.VK_P,
                )

            HotkeyAction.NEXT_TRACK ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_RIGHT,
                    ctrlAlt to HotkeyVirtualKeys.VK_N,
                    ctrlShift to HotkeyVirtualKeys.VK_RIGHT,
                    altShift to HotkeyVirtualKeys.VK_RIGHT,
                )

            HotkeyAction.PREVIOUS_TRACK ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_LEFT,
                    ctrlShift to HotkeyVirtualKeys.VK_LEFT,
                    altShift to HotkeyVirtualKeys.VK_LEFT,
                )

            HotkeyAction.VOLUME_UP ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_UP,
                    ctrlAlt to HotkeyVirtualKeys.VK_OEM_PLUS,
                    ctrlShift to HotkeyVirtualKeys.VK_UP,
                )

            HotkeyAction.VOLUME_DOWN ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_DOWN,
                    ctrlAlt to HotkeyVirtualKeys.VK_OEM_MINUS,
                    ctrlShift to HotkeyVirtualKeys.VK_DOWN,
                )

            HotkeyAction.LIKE_CURRENT ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_L,
                    ctrlShift to HotkeyVirtualKeys.VK_L,
                    altShift to HotkeyVirtualKeys.VK_L,
                )

            HotkeyAction.TRIGGER_JARVIS ->
                listOf(
                    ctrlAlt to HotkeyVirtualKeys.VK_J,
                    ctrlShift to HotkeyVirtualKeys.VK_J,
                    altShift to HotkeyVirtualKeys.VK_J,
                    ctrlAlt to HotkeyVirtualKeys.VK_M,
                )
        }
    }

    val selectableKeys: List<Pair<Int, String>> =
        listOf(
            HotkeyVirtualKeys.VK_SPACE to "Space",
            HotkeyVirtualKeys.VK_UP to "Up",
            HotkeyVirtualKeys.VK_DOWN to "Down",
            HotkeyVirtualKeys.VK_LEFT to "Left",
            HotkeyVirtualKeys.VK_RIGHT to "Right",
            HotkeyVirtualKeys.VK_OEM_PLUS to "Plus",
            HotkeyVirtualKeys.VK_OEM_MINUS to "Minus",
        ) +
            (HotkeyVirtualKeys.VK_A..HotkeyVirtualKeys.VK_Z).map { it to it.toChar().toString() } +
            (1..12).map { (HotkeyVirtualKeys.VK_F1 + it - 1) to "F$it" }
}

object GlobalHotkeyStoreCodec {
    const val DATASTORE_KEY = "global_hotkeys_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encode(config: GlobalHotkeyConfig): String = json.encodeToString(config)

    fun decode(raw: String?): GlobalHotkeyConfig {
        if (raw.isNullOrBlank()) return GlobalHotkeyConfig()
        return runCatching {
            json.decodeFromString<GlobalHotkeyConfig>(raw).normalized()
        }.getOrDefault(GlobalHotkeyConfig())
    }
}
