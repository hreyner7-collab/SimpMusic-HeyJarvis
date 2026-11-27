package com.maxrave.simpmusic.lighting

expect class PlatformLightingTransport() {
    suspend fun discoverLights(timeoutMs: Long = 4000L): LightDiscoveryResult

    suspend fun sendUdpPacket(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit>

    suspend fun sendTcpLine(
        host: String,
        port: Int,
        payload: ByteArray,
    ): Result<Unit>

    suspend fun sendOpenRgbColor(
        host: String,
        port: Int,
        color: RgbColor,
        ledCount: Int,
    ): Result<Unit>

    suspend fun sendGoveeBleColor(
        address: String,
        color: RgbColor,
        brightness0To255: Int,
    ): Result<Unit>

    suspend fun sendGoveeBlePower(
        address: String,
        on: Boolean,
    ): Result<Unit>

    suspend fun blinkGoveeBle(address: String): Result<Unit>

    suspend fun triggerWebhook(url: String): Result<Unit>

    fun close()
}
