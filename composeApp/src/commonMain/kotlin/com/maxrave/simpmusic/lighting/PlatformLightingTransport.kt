package com.maxrave.simpmusic.lighting

expect class PlatformLightingTransport() {
    suspend fun sendUdpPacket(
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

    fun close()
}
