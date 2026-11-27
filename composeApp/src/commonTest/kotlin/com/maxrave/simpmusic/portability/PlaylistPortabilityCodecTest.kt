package com.maxrave.simpmusic.portability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistPortabilityCodecTest {
    @Test
    fun m3u8RoundTripsYouTubeAndLocalTracks() {
        val tracks =
            listOf(
                PortableTrackEntry(
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    album = "A Night at the Opera",
                    durationSeconds = 354,
                    videoId = "fJ9rUzIMcZQ",
                ),
                PortableTrackEntry(
                    title = "Local Studio Demo",
                    artist = "Indie Band",
                    durationSeconds = 185,
                    videoId = null,
                    url = "file:///Users/demo/Music/demo.flac",
                ),
            )

        val m3u8 = PlaylistPortabilityCodec.exportToM3u8("Road Trip Mix", tracks)
        assertTrue(m3u8.startsWith("#EXTM3U\n"))
        assertTrue(m3u8.contains("#PLAYLIST:Road Trip Mix\n"))
        assertTrue(m3u8.contains("#EXTINF:354,Queen - Bohemian Rhapsody\n"))
        assertTrue(m3u8.contains("https://music.youtube.com/watch?v=fJ9rUzIMcZQ\n"))

        assertEquals("Road Trip Mix", PlaylistPortabilityCodec.extractPlaylistTitleFromM3u8(m3u8))

        val parsed = PlaylistPortabilityCodec.parseM3u8("\uFEFF" + m3u8.replace("\n", "\r\n"))
        assertEquals(2, parsed.size)
        assertEquals("Bohemian Rhapsody", parsed[0].title)
        assertEquals("Queen", parsed[0].artist)
        assertEquals(354, parsed[0].durationSeconds)
        assertEquals("fJ9rUzIMcZQ", parsed[0].videoId)

        assertEquals("Local Studio Demo", parsed[1].title)
        assertEquals("Indie Band", parsed[1].artist)
        assertEquals(null, parsed[1].videoId)
        assertEquals("file:///Users/demo/Music/demo.flac", parsed[1].url)
    }

    @Test
    fun rfc4180CsvRoundTripsCommasQuotesAndMultilineFields() {
        val tracks =
            listOf(
                PortableTrackEntry(
                    title = "Hello, \"World\"\n(Live)",
                    artist = "Earth, Wind & Fire",
                    album = "Greatest Hits, Vol. 1",
                    durationSeconds = 215,
                    videoId = "Gs069dndIYk",
                ),
                PortableTrackEntry(
                    title = "Simple Song",
                    artist = "Plain Artist",
                    album = "Plain Album",
                    durationSeconds = 180,
                    videoId = "dQw4w9WgXcQ",
                ),
            )

        val csv = PlaylistPortabilityCodec.exportToCsv(tracks)
        assertTrue(csv.startsWith(PlaylistPortabilityCodec.CSV_HEADER))
        assertTrue(csv.contains("\"Hello, \"\"World\"\"\n(Live)\""))

        val parsed = PlaylistPortabilityCodec.parseCsv(csv)
        assertEquals(2, parsed.size)
        assertEquals("Hello, \"World\"\n(Live)", parsed[0].title)
        assertEquals("Earth, Wind & Fire", parsed[0].artist)
        assertEquals("Greatest Hits, Vol. 1", parsed[0].album)
        assertEquals(215, parsed[0].durationSeconds)
        assertEquals("Gs069dndIYk", parsed[0].videoId)
        assertEquals("https://music.youtube.com/watch?v=Gs069dndIYk", parsed[0].url)

        assertEquals("Simple Song", parsed[1].title)
        assertEquals("dQw4w9WgXcQ", parsed[1].videoId)
    }

    @Test
    fun parsesExportifySpotifyAndAppleMusicCsvFormats() {
        val exportifyCsv =
            """
            Track URI,Track Name,Artist Name(s),Album Name,Disc Number,Track Number,Duration (ms)
            spotify:track:4cOdK2wGLETKBW3PvgPWqT,"Blinding Lights","The Weeknd","After Hours",1,9,200040
            spotify:track:7qiZfU4dY1lWllzX7mPBI3,"Get Lucky (feat. Pharrell Williams)","Daft Punk;Pharrell Williams;Nile Rodgers","Random Access Memories",1,8,369626
            """.trimIndent()

        val spotifyEntries = PlaylistPortabilityCodec.parseCsv(exportifyCsv)
        assertEquals(2, spotifyEntries.size)
        assertEquals("Blinding Lights", spotifyEntries[0].title)
        assertEquals("The Weeknd", spotifyEntries[0].artist)
        assertEquals("After Hours", spotifyEntries[0].album)
        assertEquals(200, spotifyEntries[0].durationSeconds)
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", spotifyEntries[0].url)

        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", spotifyEntries[1].artist)
        assertEquals(369, spotifyEntries[1].durationSeconds)

        val appleCsv =
            """
            Name,Artist,Album,Time,Link
            "Clair de Lune","Claude Debussy","Suite bergamasque",5:04,https://youtu.be/CvFH_6DNRCY
            """.trimIndent()

        val appleEntries = PlaylistPortabilityCodec.parseCsv(appleCsv)
        assertEquals(1, appleEntries.size)
        assertEquals("Clair de Lune", appleEntries[0].title)
        assertEquals("Claude Debussy", appleEntries[0].artist)
        assertEquals(304, appleEntries[0].durationSeconds)
        assertEquals("CvFH_6DNRCY", appleEntries[0].videoId)
    }

    @Test
    fun detectsFormatAndExtractsYouTubeVideoIds() {
        assertEquals("m3u8", PlaylistPortabilityCodec.detectFormat("#EXTM3U\nhttps://youtu.be/abc12345678"))
        assertEquals("csv", PlaylistPortabilityCodec.detectFormat("Title,Artist\nSong,Band"))
        assertEquals("abc12345678", PlaylistPortabilityCodec.extractYouTubeVideoId("https://music.youtube.com/watch?v=abc12345678&list=RD1"))
        assertEquals("abc12345678", PlaylistPortabilityCodec.extractYouTubeVideoId("https://youtu.be/abc12345678?t=42"))
        assertEquals("abc12345678", PlaylistPortabilityCodec.extractYouTubeVideoId("https://www.youtube.com/shorts/abc12345678"))
    }
}
