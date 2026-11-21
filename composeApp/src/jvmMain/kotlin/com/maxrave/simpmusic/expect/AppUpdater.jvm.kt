package com.maxrave.simpmusic.expect

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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

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
                _state.value = AppUpdateState.Checking
                val releaseJson = fetchText(UPDATE_REPO_LATEST_API)
                val root = Json.parseToJsonElement(releaseJson).jsonObject
                val latestTag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: tagName ?: "latest"
                val assets = root["assets"]?.jsonArray ?: emptyList()

                val zipAsset = assets
                    .map { it.jsonObject }
                    .firstOrNull { asset ->
                        val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        name.endsWith(".zip", ignoreCase = true) && name.contains("Windows", ignoreCase = true)
                    }
                    ?: assets
                        .map { it.jsonObject }
                        .firstOrNull { asset ->
                            asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".zip", ignoreCase = true)
                        }
                    ?: throw IllegalStateException("No Windows update package found in release $latestTag")

                val downloadUrl = zipAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("Missing download URL in release $latestTag")

                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath
                val workDir = File(localAppData, "SimpMusic-Jarvis-Update")
                workDir.mkdirs()

                val zipFile = File(workDir, "update.zip")
                val stagingDir = File(workDir, "staging")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                downloadFile(downloadUrl, zipFile) { pct ->
                    _state.value = AppUpdateState.Downloading(
                        progressPercent = pct,
                        statusText = "Downloading $latestTag ($pct%)...",
                    )
                }

                _state.value = AppUpdateState.Installing("Extracting update...")
                extractZip(zipFile, stagingDir)

                // If the zip wraps files in a single top-level folder (e.g. SimpMusic/), unwrap it
                val topEntries = stagingDir.listFiles().orEmpty()
                val sourceDir = if (topEntries.size == 1 && topEntries[0].isDirectory) {
                    topEntries[0]
                } else {
                    stagingDir
                }

                val installDir = resolveInstallDir(localAppData)
                val exePath = File(installDir, "SimpMusic.exe").absolutePath
                val pid = ProcessHandle.current().pid()

                val scriptFile = File(workDir, "apply_update.ps1")
                val d = '$'
                scriptFile.writeText(
                    """
                    ${d}ErrorActionPreference = 'SilentlyContinue'
                    Start-Sleep -Milliseconds 600
                    ${d}deadline = (Get-Date).AddSeconds(20)
                    while ((Get-Process -Name SimpMusic -ErrorAction SilentlyContinue) -and (Get-Date) -lt ${d}deadline) {
                        Stop-Process -Name SimpMusic -Force -ErrorAction SilentlyContinue
                        Start-Sleep -Milliseconds 300
                    }
                    Start-Sleep -Milliseconds 500
                    ${d}src = '${sourceDir.absolutePath.replace("'", "''")}'
                    ${d}dst = '${installDir.absolutePath.replace("'", "''")}'
                    ${d}exe = '${exePath.replace("'", "''")}'
                    if (!(Test-Path ${d}dst)) { New-Item -ItemType Directory -Path ${d}dst -Force | Out-Null }
                    & robocopy ${d}src ${d}dst /MIR /R:8 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
                    Remove-Item -Path '${zipFile.absolutePath.replace("'", "''")}' -Force -ErrorAction SilentlyContinue
                    Remove-Item -Path '${stagingDir.absolutePath.replace("'", "''")}' -Recurse -Force -ErrorAction SilentlyContinue
                    if (Test-Path ${d}exe) {
                        Start-Process -FilePath ${d}exe -WorkingDirectory ${d}dst
                    }
                    """.trimIndent(),
                    Charsets.UTF_8,
                )

                _state.value = AppUpdateState.Installing("Closing app, installing & restarting...")
                ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    "start",
                    "",
                    "/B",
                    "powershell.exe",
                    "-NoProfile",
                    "-WindowStyle",
                    "Hidden",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    scriptFile.absolutePath,
                ).start()

                Thread.sleep(300)
                Runtime.getRuntime().halt(0)
            } catch (t: Throwable) {
                _state.value = AppUpdateState.Error(t.message ?: "Update failed")
            }
        }
    }

    private fun resolveInstallDir(localAppData: String): File {
        val appPath = System.getProperty("jpackage.app-path")
        if (!appPath.isNullOrBlank()) {
            val parent = File(appPath).parentFile
            if (parent != null && parent.isDirectory) return parent
        }
        return File(localAppData, "Programs\\SimpMusic-Jarvis")
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
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

    private fun extractZip(zipFile: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val normalizedName = entry.name.replace('\\', '/')
                val isDir = entry.isDirectory || normalizedName.endsWith("/")
                val outFile = File(targetDir, normalizedName)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget)) {
                    throw SecurityException("Blocked zip slip entry: ${entry.name}")
                }
                if (isDir) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
            }
        }
    }
}
