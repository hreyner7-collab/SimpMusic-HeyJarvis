package com.maxrave.simpmusic.webremote

data class RemoteServerHandle(
    val boundPort: Int,
    val lanIpAddress: String,
) {
    val displayUrl: String
        get() = "http://$lanIpAddress:$boundPort"
}

data class RemoteHttpResult(
    val statusCode: Int,
    val reason: String,
    val contentType: String,
    val body: String,
)

/**
 * Lightweight LAN HTTP server backing [LanWebRemoteManager].
 *
 * - JVM & Android: pure-Kotlin `java.net.ServerSocket` with a bounded 4-thread pool and
 *   local IPv4 LAN address discovery.
 * - iOS: safe no-op stub.
 */
expect class PlatformWebRemoteServer() {
    val isSupportedOnCurrentPlatform: Boolean

    fun start(
        preferredPort: Int,
        onHandleRequest: (method: String, path: String, headers: Map<String, String>, body: String) -> RemoteHttpResult,
    ): RemoteServerHandle?

    fun stop()
}
