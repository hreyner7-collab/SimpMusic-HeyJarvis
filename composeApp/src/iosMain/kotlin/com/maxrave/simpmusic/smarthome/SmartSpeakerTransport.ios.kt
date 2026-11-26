package com.maxrave.simpmusic.smarthome

actual class SmartSpeakerTransport actual constructor() {
    actual suspend fun discoverSpeakers(timeoutMs: Long): List<SmartSpeakerDevice> = emptyList()

    actual suspend fun sendSoapAction(
        controlUrl: String,
        serviceType: String,
        action: String,
        envelopeXml: String,
    ): Result<String> = Result.failure(UnsupportedOperationException("DLNA smart speakers are not supported on iOS"))
}
