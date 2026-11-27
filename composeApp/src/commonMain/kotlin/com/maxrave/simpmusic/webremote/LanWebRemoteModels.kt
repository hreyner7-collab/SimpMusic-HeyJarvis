package com.maxrave.simpmusic.webremote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
data class LanWebRemoteConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val pin: String = LanWebRemoteSecurity.generatePin(),
) {
    fun normalized(): LanWebRemoteConfig =
        copy(
            port = port.coerceIn(MIN_PORT, MAX_PORT),
            pin = if (LanWebRemoteSecurity.isValidPinFormat(pin)) pin else LanWebRemoteSecurity.generatePin(),
        )

    companion object {
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

@Serializable
data class RemoteQueueItem(
    val index: Int,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
    val isCurrent: Boolean = false,
)

@Serializable
data class RemotePlaybackSnapshot(
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: String = REPEAT_OFF,
    val volume: Float = 1f,
    val isLiked: Boolean = false,
    val queue: List<RemoteQueueItem> = emptyList(),
) {
    companion object {
        const val REPEAT_OFF = "OFF"
        const val REPEAT_ALL = "ALL"
        const val REPEAT_ONE = "ONE"
    }
}

@Serializable
data class RemoteCommandRequest(
    val action: String,
    val value: Double? = null,
    val index: Int? = null,
)

@Serializable
data class RemoteCommandResponse(
    val ok: Boolean,
    val message: String = "",
    val error: String? = null,
)

sealed class ParsedRemoteCommand {
    data object PlayPause : ParsedRemoteCommand()

    data object Play : ParsedRemoteCommand()

    data object Pause : ParsedRemoteCommand()

    data object Next : ParsedRemoteCommand()

    data object Previous : ParsedRemoteCommand()

    data object ToggleShuffle : ParsedRemoteCommand()

    data object CycleRepeat : ParsedRemoteCommand()

    data object ToggleLike : ParsedRemoteCommand()

    data class SeekToMs(
        val positionMs: Long,
    ) : ParsedRemoteCommand()

    data class SetVolume(
        val volume: Float,
    ) : ParsedRemoteCommand()

    data class JumpToQueueIndex(
        val index: Int,
    ) : ParsedRemoteCommand()
}

object LanWebRemoteSecurity {
    const val PIN_HEADER = "X-Remote-Pin"
    const val PIN_HEADER_LOWER = "x-remote-pin"
    const val PIN_LENGTH = 4

    fun generatePin(random: Random = Random.Default): String =
        random.nextInt(0, 10_000).toString().padStart(PIN_LENGTH, '0')

    fun isValidPinFormat(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    /**
     * Constant-time PIN verification to avoid timing leaks on LAN requests.
     */
    fun verifyPinConstantTime(
        expectedPin: String,
        providedPin: String?,
    ): Boolean {
        if (expectedPin.isEmpty() || providedPin == null) return false
        val expected = expectedPin
        val actual = providedPin.trim()
        val maxLen = maxOf(expected.length, actual.length)
        var diff = expected.length xor actual.length
        for (i in 0 until maxLen) {
            val a = if (i < expected.length) expected[i].code else 0
            val b = if (i < actual.length) actual[i].code else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }
}

object RemoteCommandParser {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun parseJson(rawBody: String): Result<ParsedRemoteCommand> {
        if (rawBody.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty command payload"))
        }
        val req =
            runCatching { json.decodeFromString<RemoteCommandRequest>(rawBody) }
                .getOrElse { return Result.failure(IllegalArgumentException("Malformed JSON command")) }

        return when (req.action.trim().lowercase()) {
            "play_pause", "playpause", "toggle" -> Result.success(ParsedRemoteCommand.PlayPause)
            "play" -> Result.success(ParsedRemoteCommand.Play)
            "pause" -> Result.success(ParsedRemoteCommand.Pause)
            "next", "skip_next" -> Result.success(ParsedRemoteCommand.Next)
            "previous", "prev", "skip_previous" -> Result.success(ParsedRemoteCommand.Previous)
            "shuffle", "toggle_shuffle" -> Result.success(ParsedRemoteCommand.ToggleShuffle)
            "repeat", "cycle_repeat" -> Result.success(ParsedRemoteCommand.CycleRepeat)
            "like", "toggle_like" -> Result.success(ParsedRemoteCommand.ToggleLike)

            "seek" -> {
                val rawVal = req.value
                if (rawVal == null || !rawVal.isFinite() || rawVal < 0.0) {
                    Result.failure(IllegalArgumentException("seek requires non-negative numeric 'value' (ms)"))
                } else {
                    Result.success(ParsedRemoteCommand.SeekToMs(rawVal.toLong()))
                }
            }

            "volume" -> {
                val rawVal = req.value
                if (rawVal == null || !rawVal.isFinite() || rawVal < 0.0 || rawVal > 1.0) {
                    Result.failure(IllegalArgumentException("volume requires numeric 'value' in 0.0..1.0"))
                } else {
                    Result.success(ParsedRemoteCommand.SetVolume(rawVal.toFloat()))
                }
            }

            "jump", "queue_jump" -> {
                val idx = req.index ?: req.value?.takeIf { it.isFinite() }?.toInt()
                if (idx == null || idx < 0) {
                    Result.failure(IllegalArgumentException("jump requires non-negative integer 'index'"))
                } else {
                    Result.success(ParsedRemoteCommand.JumpToQueueIndex(idx))
                }
            }

            else -> Result.failure(IllegalArgumentException("Unsupported action '${req.action}'"))
        }
    }
}

data class ParsedHttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String = "",
)

object RemoteHttpProtocol {
    const val MAX_HEADER_LINES = 64
    const val MAX_BODY_BYTES = 64 * 1024

    fun parseRequestHeaderLines(
        headerLines: List<String>,
        body: String = "",
    ): ParsedHttpRequest? {
        val requestLine = headerLines.firstOrNull()?.trim() ?: return null
        val parts = requestLine.split(" ").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val cleanPath = rawTarget.substringBefore('?').ifBlank { "/" }
        val headers = LinkedHashMap<String, String>()
        for (line in headerLines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty()) {
                headers[key] = value
            }
        }
        return ParsedHttpRequest(
            method = method,
            rawTarget = rawTarget,
            path = cleanPath,
            headers = headers,
            body = body,
        )
    }

    fun contentLength(headers: Map<String, String>): Int =
        headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0

    fun extractPin(headers: Map<String, String>): String? =
        headers[LanWebRemoteSecurity.PIN_HEADER_LOWER]
            ?: headers.entries.firstOrNull { it.key.equals(LanWebRemoteSecurity.PIN_HEADER, ignoreCase = true) }?.value
}

class LanWebRemoteRouter(
    private val pinProvider: () -> String,
    private val htmlProvider: () -> String,
    private val snapshotProvider: () -> RemotePlaybackSnapshot,
    private val onCommand: (ParsedRemoteCommand) -> Result<String>,
) {
    private val json = RemoteCommandParser.json

    fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteHttpResult {
        val cleanMethod = method.uppercase()
        val cleanPath = path.substringBefore('?').ifBlank { "/" }

        if (cleanMethod == "GET" && (cleanPath == "/" || cleanPath == "/index.html")) {
            return RemoteHttpResult(
                statusCode = 200,
                reason = "OK",
                contentType = "text/html; charset=utf-8",
                body = htmlProvider(),
            )
        }

        if (cleanPath == "/api/state" || cleanPath == "/api/command") {
            val providedPin = RemoteHttpProtocol.extractPin(headers)
            if (!LanWebRemoteSecurity.verifyPinConstantTime(pinProvider(), providedPin)) {
                return jsonResponse(
                    statusCode = 401,
                    reason = "Unauthorized",
                    payload =
                        json.encodeToString(
                            RemoteCommandResponse(
                                ok = false,
                                error = "Invalid or missing X-Remote-Pin",
                            ),
                        ),
                )
            }
        }

        return when {
            cleanMethod == "GET" && cleanPath == "/api/state" -> {
                val snapshot = snapshotProvider()
                jsonResponse(200, "OK", json.encodeToString(snapshot))
            }

            cleanMethod == "POST" && cleanPath == "/api/command" -> {
                val parsed = RemoteCommandParser.parseJson(body)
                if (parsed.isFailure) {
                    jsonResponse(
                        statusCode = 400,
                        reason = "Bad Request",
                        payload =
                            json.encodeToString(
                                RemoteCommandResponse(
                                    ok = false,
                                    error = parsed.exceptionOrNull()?.message ?: "Invalid command",
                                ),
                            ),
                    )
                } else {
                    val result = onCommand(parsed.getOrThrow())
                    if (result.isSuccess) {
                        jsonResponse(
                            statusCode = 200,
                            reason = "OK",
                            payload =
                                json.encodeToString(
                                    RemoteCommandResponse(
                                        ok = true,
                                        message = result.getOrDefault("OK"),
                                    ),
                                ),
                        )
                    } else {
                        jsonResponse(
                            statusCode = 400,
                            reason = "Bad Request",
                            payload =
                                json.encodeToString(
                                    RemoteCommandResponse(
                                        ok = false,
                                        error = result.exceptionOrNull()?.message ?: "Command failed",
                                    ),
                                ),
                        )
                    }
                }
            }

            else -> {
                jsonResponse(
                    statusCode = 404,
                    reason = "Not Found",
                    payload = json.encodeToString(RemoteCommandResponse(ok = false, error = "Not Found")),
                )
            }
        }
    }

    private fun jsonResponse(
        statusCode: Int,
        reason: String,
        payload: String,
    ): RemoteHttpResult =
        RemoteHttpResult(
            statusCode = statusCode,
            reason = reason,
            contentType = "application/json; charset=utf-8",
            body = payload,
        )
}

object LanWebRemoteStoreCodec {
    const val DATASTORE_KEY = "lan_web_remote_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encode(config: LanWebRemoteConfig): String = json.encodeToString(config)

    fun decode(raw: String?): LanWebRemoteConfig {
        if (raw.isNullOrBlank()) return LanWebRemoteConfig()
        return runCatching {
            json.decodeFromString<LanWebRemoteConfig>(raw).normalized()
        }.getOrDefault(LanWebRemoteConfig())
    }
}
