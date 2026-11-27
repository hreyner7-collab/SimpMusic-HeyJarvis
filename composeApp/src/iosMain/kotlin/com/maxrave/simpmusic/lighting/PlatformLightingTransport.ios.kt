package com.maxrave.simpmusic.lighting

actual class PlatformLightingTransport actual constructor() {
    actual suspend fun sendUdpPacket(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("LAN lighting transport unavailable on iOS"))

    actual suspend fun sendOpenRgbColor(
        host: String,
        port: Int,
        color: RgbColor,
        ledCount: Int,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("LAN lighting transport unavailable on iOS"))

    actual fun close() = Unit
}
