package com.maxrave.simpmusic.jerry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JerryUtteranceSuiteTest {
    private data class UtteranceParseCase(
        val id: String,
        val tier: String,
        val raw: String,
        val wakePrefixed: String?,
        val expected: JerryCommand?,
    )

    private fun assertCommandAndWake(case: UtteranceParseCase) {
        val parsed = JerryCommandParser.parse(case.raw)
        assertEquals(case.expected, parsed, "${case.id} (${case.tier}) parse(\"${case.raw}\")")
        if (case.wakePrefixed != null) {
            val wakeResult = JerryWakeSession().acceptFinal(case.wakePrefixed, 1_000L)
            assertEquals(
                case.expected,
                wakeResult,
                "${case.id} (${case.tier}) acceptFinal(\"${case.wakePrefixed}\")",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tier 1: Core Unit & Contract Parsing (UTT-001 .. UTT-075)
    // -------------------------------------------------------------------------

    @Test
    fun tier1VariedPhrasings_utt001To012() {
        val cases = listOf(
            UtteranceParseCase("UTT-001", "Tier 1", "please play Bohemian Rhapsody", "hey jarvis please play Bohemian Rhapsody", JerryCommand.PlayQuery("Bohemian Rhapsody")),
            UtteranceParseCase("UTT-002", "Tier 1", "can you play Hotel California", "hey jarvis can you play Hotel California", JerryCommand.PlayQuery("Hotel California")),
            UtteranceParseCase("UTT-003", "Tier 1", "could you please play Disparate Youth for me", "hey jarvis could you please play Disparate Youth for me", JerryCommand.PlayQuery("Disparate Youth")),
            UtteranceParseCase("UTT-004", "Tier 1", "would you pause the music please", "hey jarvis would you pause the music please", JerryCommand.Pause),
            UtteranceParseCase("UTT-005", "Tier 1", "hey jarvis could you skip this song thanks", "hey jarvis could you skip this song thanks", JerryCommand.Next),
            UtteranceParseCase("UTT-006", "Tier 1", "put on Blinding Lights", "hey jarvis put on Blinding Lights", JerryCommand.PlayQuery("Blinding Lights")),
            UtteranceParseCase("UTT-007", "Tier 1", "throw on some classic rock", "hey jarvis throw on some classic rock", JerryCommand.PlayGenreOrMood("classic rock")),
            UtteranceParseCase("UTT-008", "Tier 1", "spin up lo-fi beats", "hey jarvis spin up lo-fi beats", JerryCommand.PlayGenreOrMood("lo-fi")),
            UtteranceParseCase("UTT-009", "Tier 1", "let's listen to smooth jazz", "hey jarvis let's listen to smooth jazz", JerryCommand.PlayGenreOrMood("smooth jazz")),
            UtteranceParseCase("UTT-010", "Tier 1", "i want to hear Midnight City", "hey jarvis i want to hear Midnight City", JerryCommand.PlayQuery("Midnight City")),
            UtteranceParseCase("UTT-011", "Tier 1", "play the song Lose Yourself please", "hey jarvis play the song Lose Yourself please", JerryCommand.PlaySong("Lose Yourself")),
            UtteranceParseCase("UTT-012", "Tier 1", "i need you to turn the volume up right now", "hey jarvis i need you to turn the volume up right now", JerryCommand.VolumeUp),
        )
        cases.forEach(::assertCommandAndWake)
    }

    @Test
    fun tier1OfflineSttMishearings_utt013To034() {
        val strictCases = listOf(
            UtteranceParseCase("UTT-013", "Tier 1", "ply smoth jas", "hey jarvis ply smoth jas", JerryCommand.PlayGenreOrMood("smooth jazz")),
            UtteranceParseCase("UTT-014", "Tier 1", "pley lo fi", "hey jarvis pley lo fi", JerryCommand.PlayGenreOrMood("lo-fi")),
            UtteranceParseCase("UTT-015", "Tier 1", "plai clasic rok", "hey jarvis plai clasic rok", JerryCommand.PlayGenreOrMood("classic rock")),
            UtteranceParseCase("UTT-016", "Tier 1", "plea taylor swift", "hey jarvis plea taylor swift", JerryCommand.PlayQuery("taylor swift")),
            UtteranceParseCase("UTT-017", "Tier 1", "pray coldplay", "hey jarvis pray coldplay", JerryCommand.PlayQuery("coldplay")),
            UtteranceParseCase("UTT-018", "Tier 1", "putt on jazz", "hey jarvis putt on jazz", JerryCommand.PlayGenreOrMood("jazz")),
            // UTT-019 handled below (PlayGenreOrMood("chillhop") or PlayQuery("chillhop"))
            UtteranceParseCase("UTT-020", "Tier 1", "nex song", "hey jarvis nex song", JerryCommand.Next),
            UtteranceParseCase("UTT-021", "Tier 1", "paws", "hey jarvis paws", JerryCommand.Pause),
            UtteranceParseCase("UTT-022", "Tier 1", "pos", "hey jarvis pos", JerryCommand.Pause),
            UtteranceParseCase("UTT-023", "Tier 1", "shufle on", "hey jarvis shufle on", JerryCommand.SetShuffle(true)),
            UtteranceParseCase("UTT-024", "Tier 1", "shuffel", "hey jarvis shuffel", JerryCommand.SetShuffle(null)),
            UtteranceParseCase("UTT-025", "Tier 1", "repet all", "hey jarvis repet all", JerryCommand.SetRepeat(RepeatTarget.ALL)),
            UtteranceParseCase("UTT-026", "Tier 1", "valume 5", "hey jarvis valume 5", JerryCommand.SetVolume(50)),
            UtteranceParseCase("UTT-027", "Tier 1", "volum up", "hey jarvis volum up", JerryCommand.VolumeUp),
            UtteranceParseCase("UTT-028", "Tier 1", "mut", "hey jarvis mut", JerryCommand.Mute),
            UtteranceParseCase("UTT-029", "Tier 1", "unmut", "hey jarvis unmut", JerryCommand.Unmute),
            UtteranceParseCase("UTT-030", "Tier 1", "que bohemian rhapsody", "hey jarvis que bohemian rhapsody", JerryCommand.AddToQueueQuery("bohemian rhapsody")),
            UtteranceParseCase("UTT-031", "Tier 1", "cue Take Five", "hey jarvis cue Take Five", JerryCommand.AddToQueueQuery("Take Five")),
            UtteranceParseCase("UTT-032", "Tier 1", "play artest taylor swift", "hey jarvis play artest taylor swift", JerryCommand.PlayArtist("taylor swift", shuffle = false)),
            UtteranceParseCase("UTT-033", "Tier 1", "play albm abbey road", "hey jarvis play albm abbey road", JerryCommand.PlayAlbum("abbey road")),
            UtteranceParseCase("UTT-034", "Tier 1", "play playlst workout", "hey jarvis play playlst workout", JerryCommand.PlayPlaylist("workout")),
        )
        strictCases.forEach(::assertCommandAndWake)

        // UTT-019: liston to chillhop -> PlayGenreOrMood("chillhop") or PlayQuery("chillhop")
        val utt019Parsed = JerryCommandParser.parse("liston to chillhop")
        assertTrue(
            utt019Parsed == JerryCommand.PlayGenreOrMood("chillhop") ||
                utt019Parsed == JerryCommand.PlayQuery("chillhop"),
            "UTT-019 expected PlayGenreOrMood(\"chillhop\") or PlayQuery(\"chillhop\"), got $utt019Parsed",
        )
        val utt019Wake = JerryWakeSession().acceptFinal("hey jarvis liston to chillhop", 1_000L)
        assertEquals(utt019Parsed, utt019Wake, "UTT-019 wake-prefixed parity")
    }

    @Test
    fun tier1ArtistsGenresMoods_utt035To058() {
        val cases = listOf(
            // 1C. Artists
            UtteranceParseCase("UTT-035", "Tier 1", "play Adele", "hey jarvis play Adele", JerryCommand.PlayQuery("Adele")),
            UtteranceParseCase("UTT-036", "Tier 1", "play Kendrick Lamar", "hey jarvis play Kendrick Lamar", JerryCommand.PlayQuery("Kendrick Lamar")),
            UtteranceParseCase("UTT-037", "Tier 1", "play The Beatles", "hey jarvis play The Beatles", JerryCommand.PlayQuery("The Beatles")),
            UtteranceParseCase("UTT-038", "Tier 1", "play The Weeknd", "hey jarvis play The Weeknd", JerryCommand.PlayQuery("The Weeknd")),
            UtteranceParseCase("UTT-039", "Tier 1", "play songs by Taylor Swift", "hey jarvis play songs by Taylor Swift", JerryCommand.PlayArtist("Taylor Swift", shuffle = false)),
            UtteranceParseCase("UTT-040", "Tier 1", "play music from Drake", "hey jarvis play music from Drake", JerryCommand.PlayArtist("Drake", shuffle = false)),
            UtteranceParseCase("UTT-041", "Tier 1", "play artist Adele", "hey jarvis play artist Adele", JerryCommand.PlayArtist("Adele", shuffle = false)),
            UtteranceParseCase("UTT-042", "Tier 1", "play Dua Lipa songs", "hey jarvis play Dua Lipa songs", JerryCommand.PlayArtist("Dua Lipa", shuffle = false)),
            UtteranceParseCase("UTT-043", "Tier 1", "shuffle The Beatles", "hey jarvis shuffle The Beatles", JerryCommand.PlayArtist("The Beatles", shuffle = true)),
            UtteranceParseCase("UTT-044", "Tier 1", "play Daft Punk radio", "hey jarvis play Daft Punk radio", JerryCommand.PlayRadio("Daft Punk")),
            // 1D. Genres & Moods
            UtteranceParseCase("UTT-045", "Tier 1", "play smooth jazz", "hey jarvis play smooth jazz", JerryCommand.PlayGenreOrMood("smooth jazz")),
            UtteranceParseCase("UTT-046", "Tier 1", "play lo-fi beats", "hey jarvis play lo-fi beats", JerryCommand.PlayGenreOrMood("lo-fi")),
            UtteranceParseCase("UTT-047", "Tier 1", "play workout music", "hey jarvis play workout music", JerryCommand.PlayGenreOrMood("workout")),
            UtteranceParseCase("UTT-048", "Tier 1", "play chill vibes", "hey jarvis play chill vibes", JerryCommand.PlayGenreOrMood("chill")),
            UtteranceParseCase("UTT-049", "Tier 1", "play focus music", "hey jarvis play focus music", JerryCommand.PlayGenreOrMood("focus")),
            UtteranceParseCase("UTT-050", "Tier 1", "play study beats", "hey jarvis play study beats", JerryCommand.PlayGenreOrMood("study")),
            UtteranceParseCase("UTT-051", "Tier 1", "play sleep music", "hey jarvis play sleep music", JerryCommand.PlayGenreOrMood("sleep")),
            UtteranceParseCase("UTT-052", "Tier 1", "play hip hop", "hey jarvis play hip hop", JerryCommand.PlayGenreOrMood("hip hop")),
            UtteranceParseCase("UTT-053", "Tier 1", "play r&b", "hey jarvis play r&b", JerryCommand.PlayGenreOrMood("r&b")),
            UtteranceParseCase("UTT-054", "Tier 1", "play indie rock", "hey jarvis play indie rock", JerryCommand.PlayGenreOrMood("indie rock")),
            UtteranceParseCase("UTT-055", "Tier 1", "play k-pop", "hey jarvis play k-pop", JerryCommand.PlayGenreOrMood("k-pop")),
            UtteranceParseCase("UTT-056", "Tier 1", "play ambient music", "hey jarvis play ambient music", JerryCommand.PlayGenreOrMood("ambient")),
            UtteranceParseCase("UTT-057", "Tier 1", "play feel good music", "hey jarvis play feel good music", JerryCommand.PlayGenreOrMood("feel good")),
            UtteranceParseCase("UTT-058", "Tier 1", "play blues", "hey jarvis play blues", JerryCommand.PlayGenreOrMood("blues")),
        )
        cases.forEach(::assertCommandAndWake)
    }

    @Test
    fun tier1AlbumsPlaylistsMixesLibraryRadioAndControls_utt059To075() {
        val cases = listOf(
            // 1E. Albums, Playlists, Mixes, Library & Radio
            UtteranceParseCase("UTT-059", "Tier 1", "play album Abbey Road", "hey jarvis play album Abbey Road", JerryCommand.PlayAlbum("Abbey Road")),
            UtteranceParseCase("UTT-060", "Tier 1", "play the album Random Access Memories", "hey jarvis play the album Random Access Memories", JerryCommand.PlayAlbum("Random Access Memories")),
            UtteranceParseCase("UTT-061", "Tier 1", "play playlist Chill Hits", "hey jarvis play playlist Chill Hits", JerryCommand.PlayPlaylist("Chill Hits")),
            UtteranceParseCase("UTT-062", "Tier 1", "play my liked songs", "hey jarvis play my liked songs", JerryCommand.PlayQuery("my liked songs")),
            UtteranceParseCase("UTT-063", "Tier 1", "play discovery mix", "hey jarvis play discovery mix", JerryCommand.PlayQuery("discovery mix")),
            UtteranceParseCase("UTT-064", "Tier 1", "play mix for you", "hey jarvis play mix for you", JerryCommand.PlayQuery("mixes")),
            UtteranceParseCase("UTT-065", "Tier 1", "play downloaded songs", "hey jarvis play downloaded songs", JerryCommand.PlayQuery("downloaded songs")),
            UtteranceParseCase("UTT-066", "Tier 1", "play most played", "hey jarvis play most played", JerryCommand.PlayQuery("most played")),
            UtteranceParseCase("UTT-067", "Tier 1", "start radio", "hey jarvis start radio", JerryCommand.PlayRadio(null)),
            UtteranceParseCase("UTT-068", "Tier 1", "play radio for Daft Punk", "hey jarvis play radio for Daft Punk", JerryCommand.PlayRadio("Daft Punk")),
            // 1F. General SimpMusic Controls
            UtteranceParseCase("UTT-069", "Tier 1", "restart song", "hey jarvis restart song", JerryCommand.RestartTrack),
            UtteranceParseCase("UTT-070", "Tier 1", "skip forward 30 seconds", "hey jarvis skip forward 30 seconds", JerryCommand.SeekForward(30)),
            UtteranceParseCase("UTT-071", "Tier 1", "rewind 15 seconds", "hey jarvis rewind 15 seconds", JerryCommand.SeekBackward(15)),
            UtteranceParseCase("UTT-072", "Tier 1", "seek to 50 percent", "hey jarvis seek to 50 percent", JerryCommand.SeekToPercent(50)),
            UtteranceParseCase("UTT-073", "Tier 1", "loop this song", "hey jarvis loop this song", JerryCommand.SetRepeat(RepeatTarget.ONE)),
            UtteranceParseCase("UTT-074", "Tier 1", "sleep timer 30 minutes", "hey jarvis sleep timer 30 minutes", JerryCommand.SetSleepTimer(30)),
            UtteranceParseCase("UTT-075", "Tier 1", "open settings", "hey jarvis open settings", JerryCommand.Navigate(JerryNavTarget.SETTINGS)),
        )
        cases.forEach(::assertCommandAndWake)
    }

    // -------------------------------------------------------------------------
    // Tier 2: Negatives, OOB, Wake Gating, Partial-Commit Traps (UTT-076 .. UTT-120)
    // -------------------------------------------------------------------------

    @Test
    fun tier2NegativesAndOutOfBounds_utt076To090() {
        // 2A. Empty, Whitespace, [unk], Punctuation, Gibberish, Filler Only
        val negatives = listOf(
            UtteranceParseCase("UTT-076", "Tier 2", "", "", null),
            UtteranceParseCase("UTT-077", "Tier 2", "   \t  \n  ", "   ", null),
            UtteranceParseCase("UTT-078", "Tier 2", "[unk] [unk]", "[unk]", null),
            UtteranceParseCase("UTT-079", "Tier 2", "... , ! ? -", "...", null),
            UtteranceParseCase("UTT-080", "Tier 2", "asdfghjkl", "hey jarvis asdfghjkl", null),
            UtteranceParseCase("UTT-081", "Tier 2", "please could you right now", "hey jarvis please could you right now", null),
            // 2B. Out-of-bounds volume & seek
            UtteranceParseCase("UTT-082", "Tier 2", "volume 150", "hey jarvis volume 150", null),
            UtteranceParseCase("UTT-083", "Tier 2", "set volume -5", "hey jarvis set volume -5", null),
            UtteranceParseCase("UTT-084", "Tier 2", "set volume to 101 percent", "hey jarvis set volume to 101 percent", null),
            UtteranceParseCase("UTT-085", "Tier 2", "seek to 120 percent", "hey jarvis seek to 120 percent", null),
            UtteranceParseCase("UTT-086", "Tier 2", "seek to -10 percent", "hey jarvis seek to -10 percent", null),
            // 2B. Boundaries
            UtteranceParseCase("UTT-087", "Tier 2", "volume 0", "hey jarvis volume 0", JerryCommand.SetVolume(0)),
            UtteranceParseCase("UTT-088", "Tier 2", "set volume to 70 percent", "hey jarvis set volume to 70 percent", JerryCommand.SetVolume(70)),
            UtteranceParseCase("UTT-089", "Tier 2", "cancel sleep timer", "hey jarvis cancel sleep timer", JerryCommand.SetSleepTimer(null)),
        )
        negatives.forEach(::assertCommandAndWake)

        // UTT-090: sleep timer 0 minutes -> SetSleepTimer(0) or SetSleepTimer(null)
        val utt090 = JerryCommandParser.parse("sleep timer 0 minutes")
        assertTrue(
            utt090 == JerryCommand.SetSleepTimer(0) || utt090 == JerryCommand.SetSleepTimer(null),
            "UTT-090 expected SetSleepTimer(0) or SetSleepTimer(null), got $utt090",
        )
        assertEquals(utt090, JerryWakeSession().acceptFinal("hey jarvis sleep timer 0 minutes", 1_000L), "UTT-090 wake")
    }

    @Test
    fun tier2WakeGatingAndMishearings_utt091To102() {
        // UTT-091: Idle non-wake background speech
        JerryWakeSession().let { s ->
            assertFalse(s.hasWakeWord("hello world"), "UTT-091 hasWakeWord")
            assertNull(s.acceptFinal("hello world", 100L), "UTT-091 acceptFinal")
            assertFalse(s.awaitingCommand, "UTT-091 awaitingCommand")
        }
        // UTT-092: Idle valid command without wake word
        JerryWakeSession().let { s ->
            assertNull(s.acceptFinal("play jazz", 100L), "UTT-092 acceptFinal")
            assertFalse(s.awaitingCommand, "UTT-092 awaitingCommand")
        }
        // UTT-093: Quoted wake word mid-sentence
        JerryWakeSession().let { s ->
            assertFalse(s.hasWakeWord("they said hey jarvis next song"), "UTT-093 hasWakeWord")
            assertNull(s.acceptFinal("they said hey jarvis next song", 100L), "UTT-093 acceptFinal")
        }
        // UTT-094: Near-wake word boundary
        JerryWakeSession().let { s ->
            assertFalse(s.hasWakeWord("hey jarvisson pause"), "UTT-094 hasWakeWord")
            assertNull(s.acceptFinal("hey jarvisson pause", 100L), "UTT-094 acceptFinal")
        }

        // UTT-095..097, UTT-099..100: Accepted wake variants
        assertEquals(JerryCommand.Pause, JerryWakeSession().acceptFinal("hay jarvis pause", 100L), "UTT-095")
        assertEquals(JerryCommand.PlayGenreOrMood("smooth jazz"), JerryWakeSession().acceptFinal("how jarvis play smooth jazz", 100L), "UTT-096")
        assertEquals(JerryCommand.VolumeUp, JerryWakeSession().acceptFinal("home jarvis volume up", 100L), "UTT-097")

        // UTT-098: Adversarial vowel confusion "hey jarvas next song"
        val utt098Session = JerryWakeSession()
        val utt098Expected = if (utt098Session.hasWakeWord("hey jarvas next song")) JerryCommand.Next else null
        assertEquals(utt098Expected, utt098Session.acceptFinal("hey jarvas next song", 100L), "UTT-098")

        assertEquals(JerryCommand.Mute, JerryWakeSession().acceptFinal("hey service mute", 100L), "UTT-099")
        assertEquals(JerryCommand.SetShuffle(true), JerryWakeSession().acceptFinal("jervis shuffle on", 100L), "UTT-100")

        // UTT-101: Wake window + [unk] burst + command
        JerryWakeSession().let { s ->
            assertNull(s.acceptFinal("hey jarvis", 1_000L), "UTT-101 wake")
            assertTrue(s.awaitingCommand, "UTT-101 window open")
            assertNull(s.acceptFinal("[unk]", 2_000L), "UTT-101 unk")
            assertTrue(s.awaitingCommand, "UTT-101 window stays open")
            assertEquals(JerryCommand.Unmute, s.acceptFinal("unmute", 3_000L), "UTT-101 unmute")
            assertFalse(s.awaitingCommand, "UTT-101 window closed")
        }

        // UTT-102: Exact 8,000 ms timeout expiry
        JerryWakeSession().let { s ->
            assertNull(s.acceptFinal("hey jarvis", 0L), "UTT-102 wake")
            assertTrue(s.awaitingCommand, "UTT-102 open at t=0")
            assertNull(s.acceptFinal("pause", 8_000L), "UTT-102 expired at t=8000")
            assertFalse(s.awaitingCommand, "UTT-102 closed after expiry")
        }
    }

    @Test
    fun tier2PartialCommitTrapsAndDisambiguations_utt103To120() {
        // 2D. Stage-1 Partial-Commit & Prefix Traps
        // UTT-103: bare "play"
        JerryWakeSession().let { s ->
            s.observePartial("hey jarvis play", 0L)
            assertNull(s.acceptInstantPartial("hey jarvis play", 10L), "UTT-103 partial blocked")
            assertEquals(JerryCommand.Resume, JerryCommandParser.parse("play"), "UTT-103 final parse")
        }
        // UTT-104: "play something"
        JerryWakeSession().let { s ->
            s.observePartial("hey jarvis play something", 0L)
            assertNull(s.acceptInstantPartial("hey jarvis play something", 10L), "UTT-104 partial blocked")
            assertNull(JerryCommandParser.parse("play something"), "UTT-104 final must NOT be Resume")
        }
        // UTT-105: "play music by Drake"
        JerryWakeSession().let { s ->
            s.observePartial("hey jarvis play music", 0L)
            assertNull(s.acceptInstantPartial("hey jarvis play music", 10L), "UTT-105 Stage-1 'play music' must NOT early-commit Resume")
            assertEquals(
                JerryCommand.PlayArtist("Drake", shuffle = false),
                s.acceptFinal("hey jarvis play music by Drake", 200L),
                "UTT-105 final PlayArtist",
            )
        }
        // UTT-106: "volume 1" partial of "volume 10"
        JerryWakeSession().let { s ->
            s.observePartial("hey jarvis volume 1", 0L)
            assertNull(s.acceptInstantPartial("hey jarvis volume 1", 10L), "UTT-106 partial blocked")
            assertEquals(JerryCommand.SetVolume(100), s.acceptFinal("hey jarvis volume 10", 200L), "UTT-106 final")
        }
        // UTT-107: "add to queue"
        JerryWakeSession().let { s ->
            s.observePartial("hey jarvis add to queue", 0L)
            assertNull(s.acceptInstantPartial("hey jarvis add to queue", 10L), "UTT-107 partial blocked")
            assertNull(JerryCommandParser.parse("add to queue"), "UTT-107 incomplete queue command")
        }

        // 2E. Semantic Disambiguations
        val disambiguations = listOf(
            UtteranceParseCase("UTT-108", "Tier 2", "mute", "hey jarvis mute", JerryCommand.Mute),
            UtteranceParseCase("UTT-109", "Tier 2", "mute volume", "hey jarvis mute volume", JerryCommand.Mute),
            UtteranceParseCase("UTT-110", "Tier 2", "go back", "hey jarvis go back", JerryCommand.Previous),
            UtteranceParseCase("UTT-111", "Tier 2", "navigate back", "hey jarvis navigate back", JerryCommand.Navigate(JerryNavTarget.BACK)),
            UtteranceParseCase("UTT-112", "Tier 2", "seek backward", "hey jarvis seek backward", JerryCommand.SeekBackward(10)),
            UtteranceParseCase("UTT-113", "Tier 2", "play next", "hey jarvis play next", JerryCommand.Next),
            UtteranceParseCase("UTT-114", "Tier 2", "play next Bohemian Rhapsody", "hey jarvis play next Bohemian Rhapsody", JerryCommand.PlayNextQuery("Bohemian Rhapsody")),
            UtteranceParseCase("UTT-115", "Tier 2", "add Blinding Lights to queue", "hey jarvis add Blinding Lights to queue", JerryCommand.AddToQueueQuery("Blinding Lights")),
            UtteranceParseCase("UTT-116", "Tier 2", "queue Hotel California", "hey jarvis queue Hotel California", JerryCommand.AddToQueueQuery("Hotel California")),
            UtteranceParseCase("UTT-117", "Tier 2", "clear queue", "hey jarvis clear queue", JerryCommand.ClearQueue),
            UtteranceParseCase("UTT-118", "Tier 2", "thumbs down", "hey jarvis thumbs down", JerryCommand.Dislike),
            UtteranceParseCase("UTT-119", "Tier 2", "play Don't Stop Me Now", "hey jarvis play Don't Stop Me Now", JerryCommand.PlayQuery("Don't Stop Me Now")),
            UtteranceParseCase("UTT-120", "Tier 2", "play favorite songs", "hey jarvis play favorite songs", JerryCommand.PlayQuery("favorite songs")),
        )
        disambiguations.forEach(::assertCommandAndWake)
    }

    // -------------------------------------------------------------------------
    // Tier 3: Cross-Feature Combinations (UTT-121 .. UTT-143)
    // -------------------------------------------------------------------------

    @Test
    fun tier3CrossFeatureCombinations_utt121To143() {
        val strictCases = listOf(
            UtteranceParseCase("UTT-121", "Tier 3", "could you ply smoth jas please", "hay jarvis could you ply smoth jas please", JerryCommand.PlayGenreOrMood("smooth jazz")),
            UtteranceParseCase("UTT-122", "Tier 3", "plea songs by The Weeknd for me", "how jarvis plea songs by The Weeknd for me", JerryCommand.PlayArtist("The Weeknd", shuffle = false)),
            UtteranceParseCase("UTT-123", "Tier 3", "shufle Daft Punk please", "home jarvis shufle Daft Punk please", JerryCommand.PlayArtist("Daft Punk", shuffle = true)),
            UtteranceParseCase("UTT-124", "Tier 3", "que Bohemian Rhapsody please", "hey service que Bohemian Rhapsody please", JerryCommand.AddToQueueQuery("Bohemian Rhapsody")),
            // UTT-125 handled below
            UtteranceParseCase("UTT-126", "Tier 3", "set valume to five", "hey jarvis set valume to five", JerryCommand.SetVolume(50)),
            UtteranceParseCase("UTT-127", "Tier 3", "turn it down", "hey jarvis turn it down", JerryCommand.VolumeDown),
            UtteranceParseCase("UTT-128", "Tier 3", "play relaxing jazz", "hey jarvis play relaxing jazz", JerryCommand.PlayGenreOrMood("relaxing jazz")),
            // UTT-129 split below
            UtteranceParseCase("UTT-130", "Tier 3", "seek to 0 percent", "hey jarvis seek to 0 percent", JerryCommand.SeekToPercent(0)),
            UtteranceParseCase("UTT-131", "Tier 3", "play Taylor Swift radio please", "hey jarvis play Taylor Swift radio please", JerryCommand.PlayRadio("Taylor Swift")),
            UtteranceParseCase("UTT-132", "Tier 3", "play albm Dark Side of the Moon", "hey jarvis play albm Dark Side of the Moon", JerryCommand.PlayAlbum("Dark Side of the Moon")),
            UtteranceParseCase("UTT-133", "Tier 3", "play playlst Late Night Vibes", "hey jarvis play playlst Late Night Vibes", JerryCommand.PlayPlaylist("Late Night Vibes")),
            UtteranceParseCase("UTT-134", "Tier 3", "play energize music", "hey jarvis play energize music", JerryCommand.PlayGenreOrMood("energize")),
            UtteranceParseCase("UTT-135", "Tier 3", "go home", "hey jarvis go home", JerryCommand.Navigate(JerryNavTarget.HOME)),
            UtteranceParseCase("UTT-136", "Tier 3", "open search", "hey jarvis open search", JerryCommand.Navigate(JerryNavTarget.SEARCH)),
            UtteranceParseCase("UTT-137", "Tier 3", "open library", "hey jarvis open library", JerryCommand.Navigate(JerryNavTarget.LIBRARY)),
            UtteranceParseCase("UTT-138", "Tier 3", "open mix for you", "hey jarvis open mix for you", JerryCommand.Navigate(JerryNavTarget.MIX_FOR_YOU)),
            UtteranceParseCase("UTT-139", "Tier 3", "show analytics", "hey jarvis show analytics", JerryCommand.Navigate(JerryNavTarget.ANALYTICS)),
            UtteranceParseCase("UTT-140", "Tier 3", "open fullscreen", "hey jarvis open fullscreen", JerryCommand.Navigate(JerryNavTarget.FULLSCREEN)),
            UtteranceParseCase("UTT-141", "Tier 3", "repeat off", "hey jarvis repeat off", JerryCommand.SetRepeat(RepeatTarget.OFF)),
            UtteranceParseCase("UTT-142", "Tier 3", "turn off shuffle", "hey jarvis turn off shuffle", JerryCommand.SetShuffle(false)),
        )
        strictCases.forEach(::assertCommandAndWake)

        // UTT-125: "play party mix" -> PlayQuery("party mix") or PlayGenreOrMood("party")
        val utt125 = JerryCommandParser.parse("play party mix")
        assertTrue(
            utt125 == JerryCommand.PlayQuery("party mix") || utt125 == JerryCommand.PlayGenreOrMood("party"),
            "UTT-125 expected PlayQuery(\"party mix\") or PlayGenreOrMood(\"party\"), got $utt125",
        )
        assertEquals(utt125, JerryWakeSession().acceptFinal("hey jarvis play party mix", 1_000L), "UTT-125 wake")

        // UTT-129: electronic / soul / acoustic individual genre lexicon checks
        assertCommandAndWake(UtteranceParseCase("UTT-129a", "Tier 3", "play electronic", "hey jarvis play electronic", JerryCommand.PlayGenreOrMood("electronic")))
        assertCommandAndWake(UtteranceParseCase("UTT-129b", "Tier 3", "play soul", "hey jarvis play soul", JerryCommand.PlayGenreOrMood("soul")))
        assertCommandAndWake(UtteranceParseCase("UTT-129c", "Tier 3", "play acoustic", "hey jarvis play acoustic", JerryCommand.PlayGenreOrMood("acoustic")))

        // UTT-143: Rapid re-query sequence parsing
        val session = JerryWakeSession()
        assertEquals(JerryCommand.PlayQuery("Coldplay"), session.acceptFinal("hey jarvis play Coldplay", 1_000L), "UTT-143 first")
        assertEquals(JerryCommand.PlayQuery("Adele"), session.acceptFinal("hey jarvis play Adele", 1_200L), "UTT-143 supersede")
    }

    // -------------------------------------------------------------------------
    // Tier 4: Realistic Multi-Step User Scenarios (UTT-144 .. UTT-168)
    // -------------------------------------------------------------------------

    @Test
    fun tier4ScenarioS1SmoothJazzSession_utt144To153() {
        val session = JerryWakeSession()
        var t = 1_000L
        val steps = listOf(
            "UTT-144" to ("hey jarvis play smooth jazz" to JerryCommand.PlayGenreOrMood("smooth jazz")),
            "UTT-145" to ("hey jarvis shuffle on" to JerryCommand.SetShuffle(true)),
            "UTT-146" to ("hey jarvis skip forward 20 seconds" to JerryCommand.SeekForward(20)),
            "UTT-147" to ("hey jarvis like this song" to JerryCommand.ToggleLike(true)),
            "UTT-148" to ("hey jarvis play next Take Five" to JerryCommand.PlayNextQuery("Take Five")),
            "UTT-149" to ("hey jarvis mute" to JerryCommand.Mute),
            "UTT-150" to ("hey jarvis unmute" to JerryCommand.Unmute),
            "UTT-151" to ("hey jarvis open queue" to JerryCommand.ShowQueue),
            "UTT-152" to ("hey jarvis sleep timer 30 minutes" to JerryCommand.SetSleepTimer(30)),
            "UTT-153" to ("hey jarvis stop" to JerryCommand.Stop),
        )
        for ((id, pair) in steps) {
            val (utterance, expected) = pair
            t += 500L
            assertEquals(expected, session.acceptFinal(utterance, t), "$id ($utterance)")
            assertFalse(session.awaitingCommand, "$id should consume wake window")
        }
    }

    @Test
    fun tier4ScenarioS2NoisyCommuteSession_utt154To158() {
        val session = JerryWakeSession()
        assertEquals(JerryCommand.PlayGenreOrMood("smooth jazz"), session.acceptFinal("hay jarvis ply smoth jas", 1_000L), "UTT-154")
        assertEquals(JerryCommand.Next, session.acceptFinal("jervis nex song", 2_000L), "UTT-155")
        assertEquals(JerryCommand.SetVolume(70), session.acceptFinal("how jarvis valume 7", 3_000L), "UTT-156")
        assertEquals(JerryCommand.SetRepeat(RepeatTarget.ALL), session.acceptFinal("home jarvis repet all", 4_000L), "UTT-157")
        assertEquals(JerryCommand.Pause, session.acceptFinal("hey service paws", 5_000L), "UTT-158a")
        assertEquals(JerryCommand.Resume, session.acceptFinal("hey jarvis resume", 6_000L), "UTT-158b")
    }

    @Test
    fun tier4ScenarioS3CatalogExplorationAndNavigation_utt159To163() {
        val session = JerryWakeSession()
        assertEquals(JerryCommand.PlayAlbum("Random Access Memories"), session.acceptFinal("hey jarvis play the album Random Access Memories", 1_000L), "UTT-159")
        assertEquals(JerryCommand.AddToQueueQuery("Blinding Lights"), session.acceptFinal("hey jarvis add Blinding Lights to queue", 2_000L), "UTT-160")
        assertEquals(JerryCommand.Navigate(JerryNavTarget.NOW_PLAYING), session.acceptFinal("hey jarvis open now playing", 3_000L), "UTT-161")
        assertEquals(JerryCommand.SeekToPercent(50), session.acceptFinal("hey jarvis seek to 50 percent", 4_000L), "UTT-162")
        assertEquals(JerryCommand.Navigate(JerryNavTarget.BACK), session.acceptFinal("hey jarvis navigate back", 5_000L), "UTT-163")
    }

    @Test
    fun tier4ScenarioS4InterruptionAndRecovery_utt164To168() {
        val session = JerryWakeSession()
        // UTT-164: Wake then non-music speech closes window cleanly
        assertNull(session.acceptFinal("hey jarvis", 1_000L), "UTT-164 wake")
        assertTrue(session.awaitingCommand, "UTT-164 window open")
        assertNull(session.acceptFinal("what is the weather", 1_500L), "UTT-164 unknown speech")
        assertFalse(session.awaitingCommand, "UTT-164 window reset after unknown")

        // UTT-165..168: Recovery
        assertEquals(JerryCommand.PlayArtist("Kendrick Lamar", shuffle = false), session.acceptFinal("hey jarvis play songs by Kendrick Lamar", 2_000L), "UTT-165a")
        assertEquals(JerryCommand.ClearQueue, session.acceptFinal("hey jarvis clear queue", 2_500L), "UTT-165b")
        assertEquals(JerryCommand.SetRepeat(RepeatTarget.CYCLE), session.acceptFinal("hey jarvis repeat", 3_000L), "UTT-166")
        assertEquals(JerryCommand.ToggleLike(false), session.acceptFinal("hey jarvis unlike this song", 3_500L), "UTT-167")
        assertEquals(JerryCommand.ToggleLike(null), session.acceptFinal("hey jarvis toggle like", 4_000L), "UTT-168")
    }

    @Test
    fun contractMatrixCoversAllRepeatTargetsAndNavTargets() {
        val repeatCommands = listOf(
            JerryCommandParser.parse("repeat off"),
            JerryCommandParser.parse("repeat all"),
            JerryCommandParser.parse("loop this song"),
            JerryCommandParser.parse("repeat"),
        )
        assertEquals(
            setOf(RepeatTarget.OFF, RepeatTarget.ALL, RepeatTarget.ONE, RepeatTarget.CYCLE),
            repeatCommands.mapNotNull { (it as? JerryCommand.SetRepeat)?.mode }.toSet(),
        )

        val navCommands = listOf(
            JerryCommandParser.parse("go home"),
            JerryCommandParser.parse("open search"),
            JerryCommandParser.parse("open library"),
            JerryCommandParser.parse("open settings"),
            JerryCommandParser.parse("open mix for you"),
            JerryCommandParser.parse("show analytics"),
            JerryCommandParser.parse("open now playing"),
            JerryCommandParser.parse("open fullscreen"),
            JerryCommandParser.parse("navigate back"),
        )
        val parsedNavTargets = navCommands.mapNotNull { (it as? JerryCommand.Navigate)?.target }.toSet()
        assertEquals(
            setOf(
                JerryNavTarget.HOME,
                JerryNavTarget.SEARCH,
                JerryNavTarget.LIBRARY,
                JerryNavTarget.SETTINGS,
                JerryNavTarget.MIX_FOR_YOU,
                JerryNavTarget.ANALYTICS,
                JerryNavTarget.NOW_PLAYING,
                JerryNavTarget.FULLSCREEN,
                JerryNavTarget.BACK,
            ),
            parsedNavTargets,
        )
        assertNotNull(JerryCommandParser.parse("open queue") as? JerryCommand.ShowQueue)
    }
}
