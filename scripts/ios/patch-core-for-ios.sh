#!/bin/bash
set -euo pipefail

python3 - <<'PY'
import pathlib

root = pathlib.Path("core")
patched = 0

# 1. Add `import kotlinx.coroutines.IO` to any commonMain Kotlin file under core/
for path in root.rglob("commonMain/**/*.kt"):
    text = path.read_text(encoding="utf-8")
    if "Dispatchers.IO" in text and "import kotlinx.coroutines.IO" not in text:
        if "import kotlinx.coroutines.Dispatchers" in text:
            text = text.replace(
                "import kotlinx.coroutines.Dispatchers",
                "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.IO",
                1,
            )
            path.write_text(text, encoding="utf-8")
            print(f"Patched Dispatchers.IO import in {path}")
            patched += 1

# 2. QuickJsEngine.kt: replace JVM String.format
qjs = root / "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/cipher/QuickJsEngine.kt"
if qjs.exists():
    t = qjs.read_text(encoding="utf-8")
    t = t.replace('append("\\\\u%04x".format(c.code))', 'append("\\\\u" + c.code.toString(16).padStart(4, \'0\'))')
    qjs.write_text(t, encoding="utf-8")
    print("Patched QuickJsEngine.kt")

# 3. RemotePlayerConfigStore.kt: add kotlin.concurrent.Volatile import
rpcs = root / "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/cipher/RemotePlayerConfigStore.kt"
if rpcs.exists():
    t = rpcs.read_text(encoding="utf-8")
    if "import kotlin.concurrent.Volatile" not in t:
        t = t.replace("package com.maxrave.kotlinytmusicscraper.cipher", "package com.maxrave.kotlinytmusicscraper.cipher\n\nimport kotlin.concurrent.Volatile", 1)
        rpcs.write_text(t, encoding="utf-8")
        print("Patched RemotePlayerConfigStore.kt")

# 4. MapExt.kt: remove JVM-only kotlin.reflect.full.memberProperties
mapext = root / "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/extension/MapExt.kt"
if mapext.exists():
    mapext.write_text(
        "package com.maxrave.kotlinytmusicscraper.extension\n\n"
        "inline fun <reified T : Any> T.asMap(): Map<String, Any?> = emptyMap()\n",
        encoding="utf-8",
    )
    print("Patched MapExt.kt")

# 5. StringExt.kt: replace java.security.MessageDigest with pure Kotlin SHA-256
strext = root / "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/extension/StringExt.kt"
if strext.exists():
    t = strext.read_text(encoding="utf-8")
    t = t.replace("import java.security.MessageDigest\n", "")
    old_fn = '''fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(toByteArray())
    return hash.fold("", { str, it -> str + "%02x".format(it) })
}'''
    new_fn = '''fun String.sha256(): String {
    val bytes = encodeToByteArray()
    val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0x0bf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
    var h0 = 0x6a09e667; var h1 = -0x4498517b; var h2 = 0x3c6ef372; var h3 = -0x5ab00ac6
    var h4 = 0x510e527f; var h5 = -0x64fa9774; var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19
    val bitLen = bytes.size.toLong() * 8L
    val padLen = ((56 - (bytes.size + 1) % 64) + 64) % 64
    val msg = ByteArray(bytes.size + 1 + padLen + 8)
    bytes.copyInto(msg)
    msg[bytes.size] = 0x80.toByte()
    for (i in 0 until 8) msg[msg.size - 1 - i] = ((bitLen ushr (i * 8)) and 0xff).toByte()
    val w = IntArray(64)
    for (chunk in msg.indices step 64) {
        for (i in 0 until 16) {
            val o = chunk + i * 4
            w[i] = ((msg[o].toInt() and 0xff) shl 24) or ((msg[o + 1].toInt() and 0xff) shl 16) or ((msg[o + 2].toInt() and 0xff) shl 8) or (msg[o + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0; var b = h1; var c = h2; var d = h3; var e = h4; var f = h5; var g = h6; var hh = h7
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += hh
    }
    return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}'''
    t = t.replace(old_fn, new_fn)
    strext.write_text(t, encoding="utf-8")
    print("Patched StringExt.kt")

# 6. Utils.kt: replace java.security.MessageDigest & java.time.Instant & String.format
utils = root / "service/kotlinYtmusicScraper/src/commonMain/kotlin/com/maxrave/kotlinytmusicscraper/utils/Utils.kt"
if utils.exists():
    t = utils.read_text(encoding="utf-8")
    t = t.replace("import java.security.MessageDigest\n", "")
    t = t.replace("import java.time.Instant\n", "import kotlin.time.Clock\nimport kotlin.time.ExperimentalTime\n")
    t = t.replace(
        'fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }',
        'fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> (eachByte.toInt() and 0xFF).toString(16).padStart(2, \'0\') }',
    )
    t = t.replace(
        'fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()',
        '''fun sha1(str: String): String {
    val bytes = str.encodeToByteArray()
    val bitLen = bytes.size.toLong() * 8L
    val padLen = ((56 - (bytes.size + 1) % 64) + 64) % 64
    val msg = ByteArray(bytes.size + 1 + padLen + 8)
    bytes.copyInto(msg)
    msg[bytes.size] = 0x80.toByte()
    for (i in 0 until 8) msg[msg.size - 1 - i] = ((bitLen ushr (i * 8)) and 0xff).toByte()
    var h0 = 0x67452301; var h1 = -0x10325477; var h2 = -0x67452302; var h3 = 0x10325476; var h4 = -0x3c2d1e10
    val w = IntArray(80)
    for (chunk in msg.indices step 64) {
        for (i in 0 until 16) {
            val o = chunk + i * 4
            w[i] = ((msg[o].toInt() and 0xff) shl 24) or ((msg[o + 1].toInt() and 0xff) shl 16) or ((msg[o + 2].toInt() and 0xff) shl 8) or (msg[o + 3].toInt() and 0xff)
        }
        for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
        for (i in 0 until 80) {
            val (f, k) = when (i) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to -0x70e44324
                else -> (b xor c xor d) to -0x359d3e2a
            }
            val tmp = a.rotateLeft(5) + f + e + k + w[i]
            e = d; d = c; c = b.rotateLeft(30); b = a; a = tmp
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
    }
    return intArrayOf(h0, h1, h2, h3, h4).joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}''',
    )
    t = t.replace(
        "fun generateNetscapeCookies(",
        "@OptIn(ExperimentalTime::class)\nfun generateNetscapeCookies(",
    )
    t = t.replace("Instant.now().epochSecond", "Clock.System.now().epochSeconds")
    utils.write_text(t, encoding="utf-8")
    print("Patched Utils.kt")

# 7. listenTogether MessageCodec.kt: add okio.use import
mc = root / "service/listenTogether/src/commonMain/kotlin/org/simpmusic/listentogether/MessageCodec.kt"
if mc.exists():
    t = mc.read_text(encoding="utf-8")
    if "import okio.use" not in t:
        t = t.replace("import okio.Buffer", "import okio.Buffer\nimport okio.use", 1)
        mc.write_text(t, encoding="utf-8")
        print("Patched MessageCodec.kt")

print(f"Total Dispatchers.IO files patched: {patched}")
PY

# Remove duplicate per-arch iOS actuals in ktorExt that conflict with iosMain
rm -rf core/service/ktorExt/src/iosSimulatorArm64Main core/service/ktorExt/src/iosArm64Main
