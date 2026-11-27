package com.maxrave.simpmusic.lighting

actual class PlatformLightingTransport actual constructor() {
    actual suspend fun discoverLights(timeoutMs: Long): List<SmartLightDevice> = emptyList()

    actual suspend fun sendUdpPacket(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("LAN lighting transport unavailable on iOS"))

    actual suspend fun sendTcpLine(
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

    actual suspend fun sendGoveeBleColor(
        address: String,
        color: RgbColor,
        brightness0To255: Int,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("BLE lighting transport unavailable on iOS"))

    actual suspend fun sendGoveeBlePower(
        address: String,
        on: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("BLE lighting transport unavailable on iOS"))

    actual suspend fun blinkGoveeBle(address: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("BLE lighting transport unavailable on iOS"))

    actual suspend fun triggerWebhook(url: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Webhook transport unavailable on iOS"))

    actual fun close() = Unit
}
