package com.maxrave.simpmusic.jerry

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JerryGrammarParityTest {
    private val json = Json

    private fun decodeGrammarTokens(): List<String> =
        json.decodeFromString<List<String>>(JerryGrammar.stage1GrammarJson)

    @Test
    fun stage1GrammarIsValidNonEmptyJsonArrayWithUnkAndNoBlanks() {
        val tokens = decodeGrammarTokens()
        assertTrue(tokens.isNotEmpty(), "stage1GrammarJson should not be empty")
        assertTrue("[unk]" in tokens, "stage1GrammarJson must contain [unk]")
        assertTrue(tokens.all { it.isNotBlank() && it == it.trim() }, "Grammar tokens must be non-blank and trimmed")
        assertEquals(tokens.size, tokens.distinct().size, "Grammar tokens should not contain duplicates")
    }

    @Test
    fun wakeVariantsExistInGrammarAndMatchWakeSession() {
        val tokens = decodeGrammarTokens().toSet()
        val session = JerryWakeSession()

        val expectedWakeVariants = listOf(
            "hey jarvis",
            "hay jarvis",
            "hi jarvis",
            "hello jarvis",
            "okay jarvis",
            "ok jarvis",
            "yo jarvis",
            "jarvis",
            "how jarvis",
            "home jarvis",
            "jervis",
            "hey service",
            "hey javis",
            "hey jarvus",
            "hey jervis",
            "hey harvis",
        )

        for (wake in expectedWakeVariants) {
            assertTrue(wake in tokens, "Expected wake variant \"$wake\" in JerryGrammar.stage1GrammarJson")
            assertTrue(session.hasWakeWord(wake), "Expected JerryWakeSession.hasWakeWord(\"$wake\") == true")
            assertEquals(
                JerryCommand.Pause,
                JerryWakeSession().acceptFinal("$wake pause", 1_000L),
                "Expected \"$wake pause\" to execute Pause",
            )
        }

        // Parity check for adversarial vowel confusion UTT-098 ("hey jarvas")
        val jarvasInGrammar = "hey jarvas" in tokens
        val jarvasInSession = session.hasWakeWord("hey jarvas")
        assertEquals(
            jarvasInGrammar,
            jarvasInSession,
            "JerryGrammar and JerryWakeSession must agree on \"hey jarvas\"",
        )
    }

    @Test
    fun stage1GrammarContainsNewActionAndCatalogVocabulary() {
        val tokens = decodeGrammarTokens().toSet()
        val joined = tokens.joinToString(" ")

        // Core new action vocabulary groups
        val requiredAnyOfGroups = listOf(
            "shuffle" to listOf("shuffle", "shuffle on", "shuffle off"),
            "repeat" to listOf("repeat", "repeat all", "repeat one", "repeat off"),
            "like/dislike" to listOf("like", "like this song", "unlike", "dislike"),
            "seek" to listOf("seek", "rewind", "skip forward", "seek forward", "seek backward"),
            "queue" to listOf("queue", "clear queue", "open queue", "show queue"),
            "mute/unmute" to listOf("mute", "unmute"),
            "navigation" to listOf("open settings", "go home", "open search", "open library", "open now playing", "open queue"),
            "radio" to listOf("radio", "start radio"),
            "catalog cues" to listOf("artist", "album", "playlist"),
            "sleep timer" to listOf("sleep timer", "cancel sleep timer"),
        )

        for ((group, candidates) in requiredAnyOfGroups) {
            assertTrue(
                candidates.any { it in tokens || Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(joined) },
                "Expected $group vocabulary (${candidates.joinToString()}) in stage1GrammarJson",
            )
        }

        // Exact token checks for critical new controls
        assertTrue("mute" in tokens, "Expected \"mute\" in stage1GrammarJson")
        assertTrue("unmute" in tokens, "Expected \"unmute\" in stage1GrammarJson")
    }

    @Test
    fun stage1CommandPhrasesAreConsistentWithParserAndPartialGuards() {
        val tokens = decodeGrammarTokens().toSet()

        // Complete unambiguous Stage-1 command tokens must parse as expected
        val unambiguousCommands = mapOf(
            "next song" to JerryCommand.Next,
            "previous song" to JerryCommand.Previous,
            "pause" to JerryCommand.Pause,
            "stop" to JerryCommand.Stop,
            "mute" to JerryCommand.Mute,
            "unmute" to JerryCommand.Unmute,
            "volume up" to JerryCommand.VolumeUp,
            "volume down" to JerryCommand.VolumeDown,
            "clear queue" to JerryCommand.ClearQueue,
        )
        for ((phrase, expected) in unambiguousCommands) {
            assertTrue(phrase in tokens, "Expected \"$phrase\" in stage1GrammarJson")
            assertEquals(expected, JerryCommandParser.parse(phrase), "Parser mismatch for \"$phrase\"")
        }

        // Partial-commit traps: phrases in grammar that MUST NOT early-commit in acceptInstantPartial
        val guardedPartials = listOf(
            "hey jarvis play",
            "hey jarvis play music",
            "hey jarvis play something",
            "hey jarvis volume 1",
            "hey jarvis add to queue",
        )
        for (partial in guardedPartials) {
            val session = JerryWakeSession()
            session.observePartial(partial, 100L)
            assertNull(
                session.acceptInstantPartial(partial, 110L),
                "Stage-1 partial \"$partial\" must NOT commit early",
            )
        }

        // Verify "play music by Drake" resolves cleanly even if Stage-1 only saw "play music"
        assertNotNull(JerryCommandParser.parse("play music by Drake") as? JerryCommand.PlayArtist)
        assertFalse(JerryWakeSession().hasWakeWord("they said hey jarvis next song"))
    }
}
