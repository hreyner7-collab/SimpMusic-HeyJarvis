package com.maxrave.simpmusic.hotkeys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalHotkeyModelsTest {
    @Test
    fun defaultBindingsCoverAllActionsWithoutDuplicates() {
        val config = GlobalHotkeyConfig()
        assertTrue(config.enabled)
        assertEquals(HotkeyAction.entries.size, config.bindings.size)
        assertEquals(HotkeyAction.entries.toSet(), config.bindings.map { it.action }.toSet())

        val byAction = config.bindings.associateBy { it.action }
        assertEquals("Ctrl+Alt+Space", byAction[HotkeyAction.PLAY_PAUSE]?.displayLabel)
        assertEquals("Ctrl+Alt+Right", byAction[HotkeyAction.NEXT_TRACK]?.displayLabel)
        assertEquals("Ctrl+Alt+Left", byAction[HotkeyAction.PREVIOUS_TRACK]?.displayLabel)
        assertEquals("Ctrl+Alt+Up", byAction[HotkeyAction.VOLUME_UP]?.displayLabel)
        assertEquals("Ctrl+Alt+Down", byAction[HotkeyAction.VOLUME_DOWN]?.displayLabel)
        assertEquals("Ctrl+Alt+L", byAction[HotkeyAction.LIKE_CURRENT]?.displayLabel)
        assertEquals("Ctrl+Alt+J", byAction[HotkeyAction.TRIGGER_JARVIS]?.displayLabel)

        assertTrue(HotkeyShortcutFormatter.findDuplicateShortcuts(config.bindings).isEmpty())
    }

    @Test
    fun modifierFormattingUsesCanonicalOrder() {
        val allMods =
            HotkeyModifiers.MOD_WIN or
                HotkeyModifiers.MOD_SHIFT or
                HotkeyModifiers.MOD_ALT or
                HotkeyModifiers.MOD_CONTROL
        assertEquals(
            "Ctrl+Alt+Shift+Win+F8",
            HotkeyShortcutFormatter.format(allMods, HotkeyVirtualKeys.VK_F1 + 7),
        )
        assertEquals(
            "Alt+Space",
            HotkeyShortcutFormatter.format(HotkeyModifiers.MOD_ALT, HotkeyVirtualKeys.VK_SPACE),
        )
    }

    @Test
    fun shortcutParserHandlesLabelsAndRejectsInvalidInput() {
        val parsed = HotkeyShortcutFormatter.parse("Ctrl + Alt + Space")
        assertNotNull(parsed)
        assertEquals(HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_ALT, parsed.first)
        assertEquals(HotkeyVirtualKeys.VK_SPACE, parsed.second)

        val parsedJarvis = HotkeyShortcutFormatter.parse("shift+win+j")
        assertNotNull(parsedJarvis)
        assertEquals(HotkeyModifiers.MOD_SHIFT or HotkeyModifiers.MOD_WIN, parsedJarvis.first)
        assertEquals(HotkeyVirtualKeys.VK_J, parsedJarvis.second)

        assertNull(HotkeyShortcutFormatter.parse(""))
        assertNull(HotkeyShortcutFormatter.parse("Ctrl+Alt")) // Missing key
        assertNull(HotkeyShortcutFormatter.parse("Ctrl+A+B")) // Multiple keys
        assertNull(HotkeyShortcutFormatter.parse("Ctrl+UnknownKey"))
    }

    @Test
    fun duplicateShortcutDetectionFlagsEnabledCollisionsOnly() {
        val ctrlAlt = HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_ALT
        val colliding =
            listOf(
                HotkeyBinding(HotkeyAction.PLAY_PAUSE, ctrlAlt, HotkeyVirtualKeys.VK_SPACE, enabled = true),
                HotkeyBinding(HotkeyAction.TRIGGER_JARVIS, ctrlAlt, HotkeyVirtualKeys.VK_SPACE, enabled = true),
                HotkeyBinding(HotkeyAction.LIKE_CURRENT, ctrlAlt, HotkeyVirtualKeys.VK_SPACE, enabled = false),
                HotkeyBinding(HotkeyAction.NEXT_TRACK, ctrlAlt, HotkeyVirtualKeys.VK_RIGHT, enabled = true),
            )

        val duplicates = HotkeyShortcutFormatter.findDuplicateShortcuts(colliding)
        assertEquals(1, duplicates.size)
        val actions = duplicates[ctrlAlt to HotkeyVirtualKeys.VK_SPACE]
        assertEquals(listOf(HotkeyAction.PLAY_PAUSE, HotkeyAction.TRIGGER_JARVIS), actions)
    }

    @Test
    fun jsonSerializationRoundTripAndNormalization() {
        val custom =
            GlobalHotkeyConfig(
                enabled = false,
                bindings =
                    GlobalHotkeyConfig.defaultBindings().map { b ->
                        if (b.action == HotkeyAction.TRIGGER_JARVIS) {
                            HotkeyBinding(
                                action = HotkeyAction.TRIGGER_JARVIS,
                                modifiers = HotkeyModifiers.MOD_CONTROL or HotkeyModifiers.MOD_SHIFT,
                                virtualKey = HotkeyVirtualKeys.VK_M,
                                enabled = true,
                            ).normalized()
                        } else {
                            b
                        }
                    },
            )
        val encoded = GlobalHotkeyStoreCodec.encode(custom)
        val decoded = GlobalHotkeyStoreCodec.decode(encoded)
        assertEquals(custom, decoded)
        assertEquals(
            "Ctrl+Shift+M",
            decoded.bindings.first { it.action == HotkeyAction.TRIGGER_JARVIS }.displayLabel,
        )

        // Partial saved JSON still restores missing actions with defaults
        val partialJson = """{"enabled":true,"bindings":[]}"""
        val restored = GlobalHotkeyStoreCodec.decode(partialJson)
        assertEquals(HotkeyAction.entries.size, restored.bindings.size)
    }
}
