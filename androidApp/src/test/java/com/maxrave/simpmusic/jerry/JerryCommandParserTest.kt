package com.maxrave.simpmusic.jerry

import org.junit.Assert.*
import org.junit.Test

class JerryCommandParserTest {
    @Test fun transportAliases() {
        mapOf(
            "next" to JerryCommand.Next,
            "skip" to JerryCommand.Next,
            "next song" to JerryCommand.Next,
            "previous song" to JerryCommand.Previous,
            "go back" to JerryCommand.Previous,
            "play music" to JerryCommand.Resume,
            "resume" to JerryCommand.Resume,
            "pause the music" to JerryCommand.Pause,
            "stop the music" to JerryCommand.Stop,
            "volume up" to JerryCommand.VolumeUp,
            "turn the volume down" to JerryCommand.VolumeDown,
        ).forEach { (text, expected) -> assertEquals(text, expected, JerryCommandParser.parse(text)) }
    }

    @Test fun normalizesSpacingCaseAndTerminalPunctuation() {
        assertEquals(JerryCommand.Next, JerryCommandParser.parse("  NEXT   SONG!  "))
    }

    @Test fun preservesSongNamesAndSeparatesResume() {
        assertEquals(JerryCommand.PlayQuery("Taylor Swift"), JerryCommandParser.parse("play Taylor Swift"))
        assertEquals(JerryCommand.PlayQuery("Don't Stop Me Now"), JerryCommandParser.parse("Play Don't Stop Me Now"))
        assertEquals(JerryCommand.Resume, JerryCommandParser.parse("play"))
    }

    @Test fun toleratesTruncatedLastSyllable() {
        assertEquals(JerryCommand.Next, JerryCommandParser.parse("next so"))
        assertEquals(JerryCommand.Previous, JerryCommandParser.parse("previous son"))
        assertEquals(JerryCommand.Stop, JerryCommandParser.parse("stop the musi"))
        assertEquals(JerryCommand.VolumeUp, JerryCommandParser.parse("volume up"))
    }

    @Test fun ignoresUnrelatedSpeechAndEmptyInput() {
        listOf("", "   ", "[unk]", "please", "can you", "the next song was nice", "hey jenny", "volume", "playing music")
            .forEach { assertNull(it, JerryCommandParser.parse(it)) }
    }

    @Test fun stripsUnkAndPoliteWrappers() {
        assertEquals(JerryCommand.Next, JerryCommandParser.parse("can you skip this song please"))
        assertEquals(JerryCommand.VolumeUp, JerryCommandParser.parse("turn it up please"))
        assertEquals(
            JerryCommand.PlayQuery("Disparate Youth"),
            JerryCommandParser.parse("play Disparate Youth please"),
        )
        assertEquals(
            JerryCommand.PlayQuery("Disparate Youth"),
            JerryCommandParser.parse("could you please play [unk] Disparate Youth hey jarvis"),
        )
        assertEquals(JerryCommand.Pause, JerryCommandParser.parse("[unk] jarvis pause right now [unk]"))
        assertEquals(JerryCommand.Resume, JerryCommandParser.parse("would you play some music for me please"))
    }

    @Test fun expandedTransportAliases() {
        mapOf(
            "skip this" to JerryCommand.Next,
            "skip it" to JerryCommand.Next,
            "next one" to JerryCommand.Next,
            "forward" to JerryCommand.Next,
            "last song" to JerryCommand.Previous,
            "previous one" to JerryCommand.Previous,
            "play some music" to JerryCommand.Resume,
            "start music" to JerryCommand.Resume,
            "start playing" to JerryCommand.Resume,
            "resume playback" to JerryCommand.Resume,
            "unpause" to JerryCommand.Resume,
            "continue" to JerryCommand.Resume,
            "pause it" to JerryCommand.Pause,
            "pause playback" to JerryCommand.Pause,
            "hold on" to JerryCommand.Pause,
            "stop it" to JerryCommand.Stop,
            "stop playing" to JerryCommand.Stop,
            "quiet" to JerryCommand.Stop,
            "shut up" to JerryCommand.Stop,
            "mute" to JerryCommand.Stop,
            "turn it up" to JerryCommand.VolumeUp,
            "make it louder" to JerryCommand.VolumeUp,
            "higher" to JerryCommand.VolumeUp,
            "increase volume" to JerryCommand.VolumeUp,
            "turn it down" to JerryCommand.VolumeDown,
            "make it quieter" to JerryCommand.VolumeDown,
            "lower" to JerryCommand.VolumeDown,
            "softer" to JerryCommand.VolumeDown,
            "decrease volume" to JerryCommand.VolumeDown,
        ).forEach { (text, expected) -> assertEquals(text, expected, JerryCommandParser.parse(text)) }
    }

    @Test fun volumeScaleAndNaturalLevels() {
        mapOf(
            "volume 1" to JerryCommand.SetVolume(10),
            "volume 2" to JerryCommand.SetVolume(20),
            "volume 5" to JerryCommand.SetVolume(50),
            "volume 10" to JerryCommand.SetVolume(100),
            "volume 0" to JerryCommand.SetVolume(0),
            "volume 75%" to JerryCommand.SetVolume(75),
            "set volume to 35 percent" to JerryCommand.SetVolume(35),
            "volume all the way down" to JerryCommand.SetVolume(10),
            "volume all the way up" to JerryCommand.SetVolume(100),
            "max volume" to JerryCommand.SetVolume(100),
            "min volume" to JerryCommand.SetVolume(10),
            "medium volume" to JerryCommand.SetVolume(50),
            "half volume" to JerryCommand.SetVolume(50),
            "volume five" to JerryCommand.SetVolume(50),
            "volume one" to JerryCommand.SetVolume(10),
            "volume ten" to JerryCommand.SetVolume(100),
            "volume to" to JerryCommand.SetVolume(20),
            "volume for" to JerryCommand.SetVolume(40),
            "volume won" to JerryCommand.SetVolume(10),
            "volume ate" to JerryCommand.SetVolume(80),
        ).forEach { (text, expected) -> assertEquals(text, expected, JerryCommandParser.parse(text)) }
    }

    @Test fun naturalPreviousNextAndMixPhrasings() {
        mapOf(
            "previous song" to JerryCommand.Previous,
            "song before it" to JerryCommand.Previous,
            "play the song before this" to JerryCommand.Previous,
            "play the last track" to JerryCommand.Previous,
            "go back one song" to JerryCommand.Previous,
            "replay the last song" to JerryCommand.Previous,
            "next song" to JerryCommand.Next,
            "skip this track" to JerryCommand.Next,
            "play the song after this" to JerryCommand.Next,
            "change the song" to JerryCommand.Next,
            "another song" to JerryCommand.Next,
            "play discovery mix" to JerryCommand.PlayQuery("discovery mix"),
            "play mixes" to JerryCommand.PlayQuery("mixes"),
            "play all of my mixes" to JerryCommand.PlayQuery("mixes"),
            "play a mix" to JerryCommand.PlayQuery("mixes"),
            "play remixes" to JerryCommand.PlayQuery("remixes"),
            "play my remixes" to JerryCommand.PlayQuery("my remixes"),
            "play replay mix" to JerryCommand.PlayQuery("replay mix"),
            "play music I never heard before" to JerryCommand.PlayQuery("discovery mix"),
            "play something I'll like" to JerryCommand.PlayQuery("mixes"),
        ).forEach { (text, expected) -> assertEquals(text, expected, JerryCommandParser.parse(text)) }
    }
}
