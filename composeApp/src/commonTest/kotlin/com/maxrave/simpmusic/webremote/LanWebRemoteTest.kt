package com.maxrave.simpmusic.webremote

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanWebRemoteTest {
    @Test
    fun httpRequestParserExtractsMethodPathHeadersAndPin() {
        val lines =
            listOf(
                "POST /api/command?ts=123 HTTP/1.1",
                "Host: 192.168.1.42:8765",
                "Content-Type: application/json",
                "Content-Length: 24",
                "X-Remote-Pin: 4829",
            )
        val parsed = RemoteHttpProtocol.parseRequestHeaderLines(lines, body = """{"action":"play_pause"}""")
        assertNotNull(parsed)
        assertEquals("POST", parsed.method)
        assertEquals("/api/command", parsed.path)
        assertEquals(24, RemoteHttpProtocol.contentLength(parsed.headers))
        assertEquals("4829", RemoteHttpProtocol.extractPin(parsed.headers))

        assertNull(RemoteHttpProtocol.parseRequestHeaderLines(emptyList()))
        assertNull(RemoteHttpProtocol.parseRequestHeaderLines(listOf("INVALID")))
    }

    @Test
    fun pinGenerationAndConstantTimeValidation() {
        val rng = Random(42)
        repeat(20) {
            val pin = LanWebRemoteSecurity.generatePin(rng)
            assertTrue(LanWebRemoteSecurity.isValidPinFormat(pin), "Invalid PIN format: $pin")
        }

        assertTrue(LanWebRemoteSecurity.verifyPinConstantTime("0429", "0429"))
        assertTrue(LanWebRemoteSecurity.verifyPinConstantTime("0429", " 0429 "))
        assertFalse(LanWebRemoteSecurity.verifyPinConstantTime("0429", "0428"))
        assertFalse(LanWebRemoteSecurity.verifyPinConstantTime("0429", "042"))
        assertFalse(LanWebRemoteSecurity.verifyPinConstantTime("0429", "04290"))
        assertFalse(LanWebRemoteSecurity.verifyPinConstantTime("0429", null))
        assertFalse(LanWebRemoteSecurity.verifyPinConstantTime("", ""))
    }

    @Test
    fun routerRejectsUnauthorizedApiCallsAndAllowsAuthorizedDispatch() {
        val dispatched = mutableListOf<ParsedRemoteCommand>()
        val sampleSnapshot =
            RemotePlaybackSnapshot(
                title = "Midnight City",
                artist = "M83",
                thumbnailUrl = "https://example.com/art.jpg",
                isPlaying = true,
                positionMs = 45_000L,
                durationMs = 243_000L,
                shuffle = true,
                repeat = RemotePlaybackSnapshot.REPEAT_ALL,
                volume = 0.75f,
                isLiked = true,
                queue =
                    listOf(
                        RemoteQueueItem(
                            index = 0,
                            videoId = "v1",
                            title = "Midnight City",
                            artist = "M83",
                            isCurrent = true,
                        ),
                    ),
            )
        val router =
            LanWebRemoteRouter(
                pinProvider = { "7391" },
                htmlProvider = { LanWebRemoteHtml.renderPage("Studio PC") },
                snapshotProvider = { sampleSnapshot },
                onCommand = { cmd ->
                    dispatched += cmd
                    Result.success("ok")
                },
            )

        // Root HTML page is accessible without PIN (the JS inside prompts for PIN)
        val htmlRes = router.route("GET", "/", emptyMap(), "")
        assertEquals(200, htmlRes.statusCode)
        assertTrue(htmlRes.contentType.startsWith("text/html"))

        // Missing PIN rejected on /api/state and /api/command
        assertEquals(401, router.route("GET", "/api/state", emptyMap(), "").statusCode)
        assertEquals(
            401,
            router.route("POST", "/api/command", emptyMap(), """{"action":"play_pause"}""").statusCode,
        )
        // Wrong PIN rejected
        assertEquals(
            401,
            router.route("POST", "/api/command", mapOf("x-remote-pin" to "0000"), """{"action":"next"}""").statusCode,
        )
        assertTrue(dispatched.isEmpty(), "Unauthorized requests must never dispatch commands")

        // Authorized GET /api/state returns snapshot JSON
        val stateRes = router.route("GET", "/api/state", mapOf("x-remote-pin" to "7391"), "")
        assertEquals(200, stateRes.statusCode)
        assertTrue(stateRes.body.contains("Midnight City"))
        assertTrue(stateRes.body.contains("M83"))

        // Authorized POST /api/command dispatches commands
        val cmdRes =
            router.route(
                "POST",
                "/api/command",
                mapOf("x-remote-pin" to "7391"),
                """{"action":"volume","value":0.6}""",
            )
        assertEquals(200, cmdRes.statusCode)
        assertEquals(1, dispatched.size)
        assertEquals(ParsedRemoteCommand.SetVolume(0.6f), dispatched.single())

        // Invalid payload with valid PIN returns 400
        val badRes =
            router.route(
                "POST",
                "/api/command",
                mapOf("x-remote-pin" to "7391"),
                """{"action":"volume","value":5.0}""",
            )
        assertEquals(400, badRes.statusCode)

        // Unknown route returns 404
        assertEquals(404, router.route("GET", "/unknown", emptyMap(), "").statusCode)
    }

    @Test
    fun commandParserSupportsAllActionsAndRejectsInvalidValues() {
        assertEquals(ParsedRemoteCommand.PlayPause, RemoteCommandParser.parseJson("""{"action":"play_pause"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.Play, RemoteCommandParser.parseJson("""{"action":"play"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.Pause, RemoteCommandParser.parseJson("""{"action":"pause"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.Next, RemoteCommandParser.parseJson("""{"action":"next"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.Previous, RemoteCommandParser.parseJson("""{"action":"previous"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.ToggleShuffle, RemoteCommandParser.parseJson("""{"action":"shuffle"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.CycleRepeat, RemoteCommandParser.parseJson("""{"action":"repeat"}""").getOrThrow())
        assertEquals(ParsedRemoteCommand.ToggleLike, RemoteCommandParser.parseJson("""{"action":"like"}""").getOrThrow())
        assertEquals(
            ParsedRemoteCommand.SeekToMs(92_500L),
            RemoteCommandParser.parseJson("""{"action":"seek","value":92500}""").getOrThrow(),
        )
        assertEquals(
            ParsedRemoteCommand.SetVolume(0.45f),
            RemoteCommandParser.parseJson("""{"action":"volume","value":0.45}""").getOrThrow(),
        )
        assertEquals(
            ParsedRemoteCommand.JumpToQueueIndex(3),
            RemoteCommandParser.parseJson("""{"action":"jump","index":3}""").getOrThrow(),
        )

        assertTrue(RemoteCommandParser.parseJson("").isFailure)
        assertTrue(RemoteCommandParser.parseJson("{bad json").isFailure)
        assertTrue(RemoteCommandParser.parseJson("""{"action":"seek","value":-5}""").isFailure)
        assertTrue(RemoteCommandParser.parseJson("""{"action":"volume","value":1.5}""").isFailure)
        assertTrue(RemoteCommandParser.parseJson("""{"action":"jump","index":-1}""").isFailure)
        assertTrue(RemoteCommandParser.parseJson("""{"action":"self_destruct"}""").isFailure)
    }

    @Test
    fun htmlRendererIncludesControlsPinPromptAndEscapesDeviceLabel() {
        val html = LanWebRemoteHtml.renderPage("<script>alert(1)</script>")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("#000000"))
        assertTrue(html.contains("#fa233b"))
        assertTrue(html.contains("sessionStorage"))
        assertTrue(html.contains("X-Remote-Pin"))
        assertTrue(html.contains("/api/state"))
        assertTrue(html.contains("/api/command"))
        assertTrue(html.contains("setInterval(fetchState, 1000)"))
        assertFalse(html.contains("<script>alert(1)</script>"), "Device label must be HTML-escaped")
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }

    @Test
    fun configCodecRoundTrip() {
        val cfg = LanWebRemoteConfig(enabled = true, port = 8765, pin = "1357")
        val encoded = LanWebRemoteStoreCodec.encode(cfg)
        val decoded = LanWebRemoteStoreCodec.decode(encoded)
        assertEquals(cfg, decoded)
    }
}
