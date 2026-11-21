package com.maxrave.simpmusic.expect

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.mp.KoinPlatform.getKoin
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

actual object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    actual val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var activeJob: Job? = null

    actual fun resetState() {
        if (activeJob?.isActive != true) {
            _state.value = AppUpdateState.Idle
        }
    }

    actual fun startUpdate(tagName: String?) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            try {
                val context = getKoin().get<Context>()
                _state.value = AppUpdateState.Checking
                val releaseJson = fetchText(UPDATE_REPO_LATEST_API)
                val root = Json.parseToJsonElement(releaseJson).jsonObject
                val latestTag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: tagName ?: "latest"
                if (latestTag != "latest" && !VersionManager.isBehind(latestTag)) {
                    _state.value = AppUpdateState.UpToDate(latestTag)
                    return@launch
                }
                val assets = root["assets"]?.jsonArray ?: emptyList()

                val apkAsset = assets
                    .map { it.jsonObject }
                    .firstOrNull { asset ->
                        asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".apk", ignoreCase = true)
                    }
                    ?: throw IllegalStateException("No Android APK found in release $latestTag")

                val downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("Missing APK download URL in release $latestTag")

                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "SimpMusic-HeyJarvis-update.apk")

                downloadFile(downloadUrl, apkFile) { pct ->
                    _state.value = AppUpdateState.Downloading(
                        progressPercent = pct,
                        statusText = "Downloading $latestTag ($pct%)...",
                    )
                }

                _state.value = AppUpdateState.Installing("Opening Android installer...")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    apkFile,
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(installIntent)
                _state.value = AppUpdateState.Idle
            } catch (t: Throwable) {
                _state.value = AppUpdateState.Error(t.message ?: "Update failed")
            }
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "SimpMusic-HeyJarvis-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                conn.disconnect()
                current = loc
            } else {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code while fetching update")
                }
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while fetching update")
    }

    private fun fetchText(url: String): String {
        val conn = openFollowingRedirects(url)
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = openFollowingRedirects(url)
        try {
            val total = conn.contentLengthLong
            var readTotal = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        val pct = if (total > 0L) ((readTotal * 100L) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            onProgress(100)
        } finally {
            conn.disconnect()
        }
    }
}
