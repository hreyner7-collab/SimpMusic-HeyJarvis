package com.maxrave.simpmusic.jerry

import org.junit.Assert.*
import org.junit.Test

class JerryWakeSessionTest {
    @Test fun ignoresCommandsBeforeWake() {
        assertNull(JerryWakeSession().acceptFinal("next song", 0))
        assertNull(JerryWakeSession().acceptFinal("hey jenny next song", 0))
    }

    @Test fun acceptsOneShotAndConsumesWindow() {
        val session = JerryWakeSession()
        assertEquals(JerryCommand.Next, session.acceptFinal("hey jarvis next song", 0))
        assertNull(session.acceptFinal("next song", 1))
    }

    @Test fun acceptsCommandAfterSeparateWake() {
        val session = JerryWakeSession()
        assertNull(session.acceptFinal("hey jarvis", 100))
        assertTrue(session.awaitingCommand)
        assertEquals(JerryCommand.VolumeUp, session.acceptFinal("volume up", 7000))
        assertFalse(session.awaitingCommand)
    }

    @Test fun partialWakeDoesNotDuplicateOrExtendDeadline() {
        val session = JerryWakeSession()
        assertTrue(session.observePartial("hey jarvis play", 0))
        assertFalse(session.observePartial("hey jarvis play", 7900))
        assertNull(session.acceptFinal("next song", 8000))
        assertFalse(session.awaitingCommand)
    }

    @Test fun finalWakeAfterPartialLeavesWindowOpen() {
        val session = JerryWakeSession()
        session.observePartial("hey jarvis", 0)
        session.acceptFinal("hey jarvis", 500)
        assertEquals(JerryCommand.Next, session.acceptFinal("next song", 1000))
    }

    @Test fun cancelsUnknownCommandsAndReset() {
        val session = JerryWakeSession()
        session.acceptFinal("hey jarvis", 0)
        assertNull(session.acceptFinal("what is the weather", 1))
        assertFalse(session.awaitingCommand)
        session.acceptFinal("hey jarvis", 100)
        session.reset()
        assertNull(session.acceptFinal("next song", 101))
    }

    @Test fun doesNotWakeOnQuotedOrNearPhrase() {
        val session = JerryWakeSession()
        assertNull(session.acceptFinal("they said hey jarvis next song", 0))
        assertFalse(session.awaitingCommand)
        assertFalse(session.observePartial("hey jarvisson", 0))
    }

    @Test fun oldWakeWordNoLongerTriggers() {
        val session = JerryWakeSession()
        assertNull(session.acceptFinal("hey jerry next song", 0))
        assertFalse(session.awaitingCommand)
    }

    @Test fun wakesOnTheWaysTheModelActuallyHearsIt() {
        listOf("hi jarvis next song", "how jarvis next so", "i'm jarvis next song", "home jarvis next song")
            .forEach { assertEquals(it, JerryCommand.Next, JerryWakeSession().acceptFinal(it, 0)) }
        assertEquals(JerryCommand.Next, JerryWakeSession().acceptFinal("jarvis next song", 0))
    }

    @Test fun wakeStillHasToOpenTheUtterance() {
        listOf("so i said hey jarvis next song", "the jarvis next song").forEach {
            val session = JerryWakeSession()
            assertNull(it, session.acceptFinal(it, 0))
            assertFalse(it, session.awaitingCommand)
        }
    }

    @Test fun stripsUnkAndFillerTokensBeforeGreeting() {
        assertEquals(JerryCommand.Resume, JerryWakeSession().acceptFinal("the hey jarvis play music", 0))
        assertEquals(JerryCommand.Next, JerryWakeSession().acceptFinal("[unk] hey jarvis next song [unk]", 0))
        assertEquals(JerryCommand.Resume, JerryWakeSession().acceptFinal("um okay hey jarvis play music", 0))
        assertEquals(JerryCommand.Resume, JerryWakeSession().acceptFinal("hey job is play music", 0))
        assertEquals(JerryCommand.Next, JerryWakeSession().acceptFinal("hey jervis next song", 0))
        assertEquals(JerryCommand.Next, JerryWakeSession().acceptFinal("hey harvis next song", 0))
    }

    @Test fun extractsCommandAfterRepeatedWakeWords() {
        assertEquals(JerryCommand.Pause, JerryWakeSession().acceptFinal("hey jarvis hey jarvis pause", 0))
        assertEquals(JerryCommand.Resume, JerryWakeSession().acceptFinal("hey jarvis hey jarvis play music", 0))
        assertEquals(JerryCommand.Pause, JerryWakeSession().acceptInstantPartial("hey jarvis hey jarvis pause", 0))
        assertEquals(
            JerryCommand.PlayQuery("Disparate Youth"),
            JerryWakeSession().acceptFinal("Hey Jarvis, play Disparate Youth.", 0),
        )
    }

    @Test fun hasWakeWordMatchesValidPrefixesAndRejectsNonWake() {
        val session = JerryWakeSession()
        listOf(
            "hey jarvis",
            "the hey jarvis",
            "[unk] hey jarvis",
            "jarvis",
            "hey job is",
            "hey jarvis hey jarvis play music",
        ).forEach { assertTrue(it, session.hasWakeWord(it)) }

        listOf(
            "",
            "[unk]",
            "they said hey jarvis next song",
            "so i said hey jarvis next song",
            "the jarvis next song",
            "hey jenny next song",
            "hey jerry next song",
            "hey jarvisson",
        ).forEach { assertFalse(it, session.hasWakeWord(it)) }
    }

    @Test fun emptyUnkBurstDoesNotCancelOpenWakeWindow() {
        val session = JerryWakeSession()
        assertNull(session.acceptFinal("Hey Jarvis.", 100))
        assertTrue(session.awaitingCommand)
        assertNull(session.acceptFinal("[unk]", 200))
        assertTrue(session.awaitingCommand)
        assertEquals(JerryCommand.VolumeUp, session.acceptFinal("turn it up please", 300))
        assertFalse(session.awaitingCommand)
    }

    @Test fun wakesOnAdditionalGreetingsAndMixAndVolumeCommands() {
        assertEquals(JerryCommand.Previous, JerryWakeSession().acceptFinal("j jarvis previous song", 0))
        assertEquals(JerryCommand.SetVolume(50), JerryWakeSession().acceptFinal("jay jarvis volume 5", 0))
        assertEquals(
            JerryCommand.PlayQuery("discovery mix"),
            JerryWakeSession().acceptFinal("hey jarvis play discovery mix", 0),
        )
        assertEquals(
            JerryCommand.PlayQuery("mixes"),
            JerryWakeSession().acceptFinal("hey jarvis play mixes", 0),
        )
        val partialSession = JerryWakeSession()
        partialSession.observePartial("hey jarvis volume 1", 0)
        assertNull(partialSession.acceptInstantPartial("hey jarvis volume 1", 10))
        assertEquals(JerryCommand.SetVolume(100), partialSession.acceptFinal("hey jarvis volume 10", 200))
    }
}
