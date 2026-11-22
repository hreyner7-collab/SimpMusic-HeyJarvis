package com.maxrave.simpmusic.jerry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Manages the local Stage-2 sherpa-onnx Parakeet-110M INT8 speech model on Desktop PCs so fresh
 * installations automatically upgrade from Stage-1 Vosk fallback to high-accuracy offline ASR.
 */
object ParakeetModelManager {
    private data class ModelFileSpec(
        val fileName: String,
        val minBytes: Long,
    )

    private val requiredFiles = listOf(
        ModelFileSpec("encoder.int8.onnx", 100_000_000L),
        ModelFileSpec("decoder.int8.onnx", 1_000_000L),
        ModelFileSpec("joiner.int8.onnx", 500_000L),
        ModelFileSpec("tokens.txt", 1_000L),
    )

    private val mirrorBaseUrls = listOf(
        "https://huggingface.co/akm9191/sherpa-onnx-parakeet-tdt-110m-int8/resolve/main/",
        "https://huggingface.co/punitd/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8/resolve/main/",
    )

    private val downloading = AtomicBoolean(false)

    val modelDir: File
        get() = File(System.getProperty("user.home"), ".simpmusic/jerry-model/parakeet-110m-int8")

    private val _status = MutableStateFlow<ParakeetModelStatus>(initialStatus())
    val status: StateFlow<ParakeetModelStatus> = _status.asStateFlow()

    private fun initialStatus(): ParakeetModelStatus =
        if (com.maxrave.simpmusic.utils.isJarvisLowRamEnabled()) {
            ParakeetModelStatus.SkippedLowRam
        } else if (isModelReady()) {
            ParakeetModelStatus.Ready
        } else {
            ParakeetModelStatus.Missing
        }

    fun refreshStatus() {
        if (downloading.get()) return
        _status.value = initialStatus()
    }

    fun isModelReady(): Boolean {
        val dir = modelDir
        return requiredFiles.all { spec ->
            val f = File(dir, spec.fileName)
            f.isFile && f.length() >= spec.minBytes
        }
    }

    fun ensureDownloadedAsync(
        onLog: (String) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        if (com.maxrave.simpmusic.utils.isJarvisLowRamEnabled()) {
            _status.value = ParakeetModelStatus.SkippedLowRam
            return
        }
        if (isModelReady()) {
            _status.value = ParakeetModelStatus.Ready
            return
        }
        if (!downloading.compareAndSet(false, true)) return

        thread(isDaemon = true, name = "jarvis-model-download", priority = Thread.NORM_PRIORITY - 1) {
            try {
                val dir = modelDir
                dir.mkdirs()
                val client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build()

                val missing = requiredFiles.filter { spec ->
                    val f = File(dir, spec.fileName)
                    !f.isFile || f.length() < spec.minBytes
                }
                val totalExpectedBytes = 136_490_421L
                var completedBytes = requiredFiles
                    .filterNot { it in missing }
                    .sumOf { File(dir, it.fileName).length() }

                _status.value = ParakeetModelStatus.Downloading(
                    percent = ((completedBytes * 100L) / totalExpectedBytes).toInt().coerceIn(0, 99),
                    detail = "Connecting…",
                )
                onLog("starting parakeet-110m-int8 background download (${missing.size} file(s) needed)")

                for (spec in missing) {
                    val destFile = File(dir, spec.fileName)
                    val partFile = File(dir, "${spec.fileName}.part")
                    var fetched = false
                    var lastErr: Throwable? = null

                    for (baseUrl in mirrorBaseUrls) {
                        val url = baseUrl + spec.fileName
                        try {
                            val req = HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofMinutes(10))
                                .header("User-Agent", "SimpMusic-HeyJarvis/2.6.12")
                                .GET()
                                .build()
                            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
                            if (resp.statusCode() !in 200..299) {
                                resp.body().close()
                                error("HTTP ${resp.statusCode()} for ${spec.fileName}")
                            }
                            val fileLen = resp.headers().firstValueAsLong("Content-Length").orElse(-1L)
                            resp.body().use { input ->
                                partFile.outputStream().use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    var fileBytes = 0L
                                    var lastEmit = 0L
                                    while (true) {
                                        val read = input.read(buf)
                                        if (read < 0) break
                                        output.write(buf, 0, read)
                                        fileBytes += read
                                        val now = System.currentTimeMillis()
                                        if (now - lastEmit >= 250L) {
                                            lastEmit = now
                                            val totalSoFar = completedBytes + fileBytes
                                            val denom = if (fileLen > 0L && missing.size == 1) fileLen else totalExpectedBytes
                                            val pct = ((totalSoFar * 100L) / maxOf(1L, denom)).toInt().coerceIn(1, 99)
                                            _status.value = ParakeetModelStatus.Downloading(
                                                percent = pct,
                                                detail = "${spec.fileName} (${fileBytes / (1024 * 1024)} MB)",
                                            )
                                        }
                                    }
                                }
                            }
                            if (partFile.length() < spec.minBytes) {
                                error("Incomplete download for ${spec.fileName} (${partFile.length()} bytes)")
                            }
                            Files.move(
                                partFile.toPath(),
                                destFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            completedBytes += destFile.length()
                            fetched = true
                            onLog("downloaded ${spec.fileName} (${destFile.length()} bytes)")
                            break
                        } catch (t: Throwable) {
                            lastErr = t
                            runCatching { partFile.delete() }
                            onLog("mirror download failed for $url: ${t.message}")
                        }
                    }
                    if (!fetched) {
                        throw (lastErr ?: IllegalStateException("Failed to download ${spec.fileName}"))
                    }
                }

                if (isModelReady()) {
                    _status.value = ParakeetModelStatus.Ready
                    onLog("parakeet-110m-int8 download complete")
                    onReady()
                } else {
                    _status.value = ParakeetModelStatus.Error("Model verification failed")
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.simpleName ?: "Download failed"
                onLog("parakeet download error: $msg")
                _status.value = ParakeetModelStatus.Error(msg)
            } finally {
                downloading.set(false)
            }
        }
    }
}
