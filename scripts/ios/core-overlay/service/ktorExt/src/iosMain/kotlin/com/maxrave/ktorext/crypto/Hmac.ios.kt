package com.maxrave.ktorext.crypto

actual class Hmac actual constructor(
    private val algorithm: String,
    private val secretKey: String,
) {
    actual fun getMacTimestampPair(uri: String): Pair<String, String> = "" to "0"

    actual fun generateHmac(data: String): String = ""

    actual fun validateHmac(data: String, hmac: String): Boolean = true

    actual fun isValidTimestamp(timestamp: String): Boolean = true
}
