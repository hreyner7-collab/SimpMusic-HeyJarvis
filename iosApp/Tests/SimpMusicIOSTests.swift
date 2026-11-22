import Foundation

@main
struct SimpMusicIOSTestRunner {
    static var passed = 0
    static var failed = 0

    static func check(_ condition: @autoclosure () -> Bool, _ message: String) {
        if condition() {
            passed += 1
            print("  [PASS] \(message)")
        } else {
            failed += 1
            print("  [FAIL] \(message)")
        }
    }

    static func main() {
        print("=== Running SimpMusic iOS Unit & Voice Engine Test Suite ===")

        // 1. JerryWakeSession & Noise Stripping Tests
        let session = JerryWakeSession()
        check(session.hasWakeWord("hey jarvis"), "Wake word: hey jarvis")
        check(session.hasWakeWord("hi jarvis"), "Wake word: hi jarvis")
        check(session.hasWakeWord("yo jarvis"), "Wake word: yo jarvis")
        check(session.hasWakeWord("ok jarvis"), "Wake word: ok jarvis")
        check(session.hasWakeWord("hay jarvis"), "Wake word: hay jarvis")
        check(!session.hasWakeWord("they said hey jarvis next song"), "Rejects quoted wake phrase")
        check(
            JerryWakeSession.stripLeadingStage1Noise("louder yo jarvis") == "yo jarvis",
            "Strips leading Stage-1 stray token before yo jarvis"
        )
        check(
            JerryWakeSession.stripLeadingStage1Noise("they said hey jarvis next song") == "they said hey jarvis next song",
            "Preserves quoted 'they said hey jarvis' untouched"
        )

        // 2. JerryCommandParser & Offline STT Normalization Tests
        check(
            JerryWakeSession().acceptFinal("hey jarvis please play Bohemian Rhapsody", nowMs: 1_000) == .playQuery("Bohemian Rhapsody"),
            "Parses 'hey jarvis please play Bohemian Rhapsody'"
        )
        check(
            JerryWakeSession().acceptFinal("hay jarvis ply smoth jas", nowMs: 1_000) == .playGenreOrMood("smooth jazz"),
            "Normalizes offline STT typos 'ply smoth jas' -> PlayGenreOrMood('smooth jazz')"
        )
        check(
            JerryWakeSession().acceptFinal("hey jarvis pley lo fi", nowMs: 1_000) == .playGenreOrMood("lo-fi"),
            "Normalizes 'pley lo fi' -> PlayGenreOrMood('lo-fi')"
        )
        check(
            JerryWakeSession().acceptFinal("how jarvis valume 7", nowMs: 1_000) == .setVolume(70),
            "Parses 'how jarvis valume 7' -> SetVolume(70)"
        )
        check(
            JerryWakeSession().acceptFinal("hey service paws", nowMs: 1_000) == .pause,
            "Parses 'hey service paws' -> Pause"
        )
        check(
            JerryWakeSession().acceptFinal("jervis nex song", nowMs: 1_000) == .next,
            "Parses 'jervis nex song' -> Next"
        )
        check(
            JerryWakeSession().acceptFinal("hey jarvis skip forward 20 seconds", nowMs: 1_000) == .seekForward(20),
            "Parses 'skip forward 20 seconds' -> SeekForward(20)"
        )
        check(
            JerryWakeSession().acceptFinal("hey jarvis sleep timer 30 minutes", nowMs: 1_000) == .setSleepTimer(30),
            "Parses 'sleep timer 30 minutes' -> SetSleepTimer(30)"
        )
        check(
            JerryWakeSession().acceptFinal("hey jarvis play songs by Taylor Swift", nowMs: 1_000) == .playArtist("Taylor Swift", shuffle: false),
            "Parses 'play songs by Taylor Swift' -> PlayArtist"
        )
        check(
            JerryWakeSession().acceptFinal("hey jarvis open search", nowMs: 1_000) == .navigate(.search),
            "Parses 'open search' -> Navigate(.search)"
        )

        // 3. A-B Loop State Tests
        var loop = ABLoopState()
        check(!loop.isActive, "ABLoop starts inactive")
        loop.setA(10.0)
        check(!loop.isActive, "ABLoop inactive with only point A")
        loop.setB(25.0)
        check(loop.isActive, "ABLoop active when B - A >= 0.5s")
        check(loop.shouldSeekToA(currentPositionSeconds: 24.5) == nil, "ABLoop does not seek before B")
        check(loop.shouldSeekToA(currentPositionSeconds: 25.1) == 10.0, "ABLoop seeks back to A at/after B")
        loop.clear()
        check(!loop.isActive, "ABLoop clears cleanly")

        // 4. PlaylistLinkParser Tests
        check(
            PlaylistLinkParser.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=123") == .spotifyPlaylist(id: "37i9dQZF1DXcBWIGoYBM5M"),
            "Parses Spotify playlist URL"
        )
        check(
            PlaylistLinkParser.parse("spotify:album:4aawyAB9vmqN3uQ7FjRGTy") == .spotifyAlbum(id: "4aawyAB9vmqN3uQ7FjRGTy"),
            "Parses Spotify album URI"
        )
        check(
            PlaylistLinkParser.parse("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI") == .youtubePlaylist(id: "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
            "Parses YouTube playlist URL"
        )

        // 5. SpotifyEmbedParser Tests
        let sampleEmbedHtml = """
        <html><body><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","id":"pl123","name":"Coding Beats","subtitle":"SimpMusic","trackList":[{"uid":"t1","title":"Midnight City","subtitle":"M83","duration":243000}]}}}}}}
        </script></body></html>
        """
        let parsedEmbed = SpotifyEmbedParser.parseEmbedHtml(sampleEmbedHtml, fallbackKind: "playlist", fallbackId: "fallback")
        check(parsedEmbed?.title == "Coding Beats", "SpotifyEmbedParser extracts playlist title")
        check(parsedEmbed?.tracks.first?.title == "Midnight City", "SpotifyEmbedParser extracts track title")

        // 6. LrcParser Tests
        let lrcLines = LrcParser.parse("[00:05.00]First line\n[00:12.50]Second line\n[00:20.00]Third line")
        check(lrcLines.count == 3, "LrcParser parses 3 lines")
        check(LrcParser.activeIndex(in: lrcLines, at: 13.0) == 1, "LrcParser identifies active line index at 13.0s")

        // 7. Audio WAV Synthesizer Fallback Test
        let wavUrl = YouTubeMusicService.ensurePlayableAudioUrl(for: YouTubeMusicService.defaultCatalog[0])
        let wavExists = FileManager.default.fileExists(atPath: wavUrl.path)
        check(wavExists, "YouTubeMusicService generates valid playable WAV file at \(wavUrl.lastPathComponent)")

        print("=== Results: \(passed) passed, \(failed) failed ===")
        if failed > 0 {
            exit(1)
        }
    }
}
