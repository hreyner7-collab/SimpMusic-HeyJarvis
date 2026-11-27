package com.maxrave.simpmusic.webremote

actual class PlatformWebRemoteServer actual constructor() {
    actual val isSupportedOnCurrentPlatform: Boolean = false

    actual fun start(
        preferredPort: Int,
        onHandleRequest: (method: String, path: String, headers: Map<String, String>, body: String) -> RemoteHttpResult,
    ): RemoteServerHandle? = null

    actual fun stop() = Unit
}
