package com.maxrave.simpmusic.dj

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread

/**
 * The AI DJ's voice. On Windows it is Piper, a free open-source neural voice that runs offline and
 * sounds far more natural than the built-in Windows voices; it is downloaded once, checked against
 * fixed SHA-256 fingerprints, and never run if the check fails. Everywhere else, and whenever Piper
 * is not ready, it falls back to the system's own speech (Windows speech, macOS `say`).
 */
actual object DjVoice {
    private const val TAG = "DjVoice"

    private const val PIPER_ZIP_URL = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip"
    private const val VOICE_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
    private const val MODEL_NAME = "en_US-lessac-medium.onnx"
    private const val CONFIG_NAME = "en_US-lessac-medium.onnx.json"

    // Fingerprints of the files this build was tested with. Anything else is never executed.
    private const val PIPER_EXE_SHA256 = "96f3da3811151580073e40bb4dd20eb0fb8115f5f5f76e2fb54282b3edfa5c1f"
    private const val MODEL_SHA256 = "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f"

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac")
    private val voiceDir = File(System.getProperty("user.home"), ".simpmusic/dj-voice")
    private val piperExe = File(voiceDir, "piper/piper.exe")
    private val modelFile = File(voiceDir, MODEL_NAME)

    @Volatile private var piperReady = false

    @Volatile private var preparing = false

    @Volatile private var piperFailed = false

    @Volatile private var process: Process? = null

    @Volatile private var clip: javax.sound.sampled.Clip? = null

    actual fun isAvailable(): Boolean = true

    @Volatile actual var preferSystemVoice: Boolean = false

    /** Starts fetching and checking the natural voice in the background; safe to call often. */
    actual fun warmUp() {
        if (!isWindows || piperReady || preparing || piperFailed) return
        preparing = true
        thread(isDaemon = true, name = "DjVoice-Prepare") {
            try {
                piperReady = preparePiper()
                if (!piperReady) piperFailed = true
                Logger.i(TAG, if (piperReady) "Natural DJ voice is ready" else "Natural DJ voice unavailable; using the system voice")
            } catch (e: Exception) {
                piperFailed = true
                Logger.w(TAG, "Natural DJ voice could not be prepared: ${e.message}")
            } finally {
                preparing = false
            }
        }
    }

    actual suspend fun speak(text: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            if (!preferSystemVoice && piperReady && speakWithPiper(text)) return@withContext
            speakWithSystem(text)
        }
    }

    actual fun stop() {
        process?.destroyForcibly()
        clip?.stop()
    }

    // ---------------------------------------------------------------- Piper

    private suspend fun speakWithPiper(text: String): Boolean {
        val wav = File.createTempFile("simp-dj-", ".wav")
        try {
            val started =
                ProcessBuilder(piperExe.absolutePath, "--model", modelFile.absolutePath, "--output_file", wav.absolutePath)
                    .directory(piperExe.parentFile)
                    .redirectErrorStream(true)
                    .start()
            process = started
            started.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            started.inputStream.readBytes()
            if (started.waitFor() != 0 || wav.length() < 1024) {
                Logger.w(TAG, "Piper produced no audio (exit ${started.exitValue()}); using the system voice")
                return false
            }
            playWav(wav)
            return true
        } catch (e: Exception) {
            Logger.w(TAG, "Piper failed: ${e.message}")
            return false
        } finally {
            process?.destroyForcibly()
            process = null
            wav.delete()
        }
    }

    private suspend fun playWav(file: File) {
        withContext(Dispatchers.IO) {
            AudioSystem.getAudioInputStream(file).use { stream ->
                val c = AudioSystem.getClip()
                clip = c
                try {
                    c.open(stream)
                    c.start()
                    // Wait for the end, but give up the moment the caller is cancelled (a skipped song).
                    Thread.sleep(80)
                    while (c.isRunning && isActive) Thread.sleep(50)
                } finally {
                    c.stop()
                    c.close()
                    clip = null
                }
            }
        }
    }

    private fun preparePiper(): Boolean {
        voiceDir.mkdirs()
        if (!(piperExe.exists() && sha256(piperExe) == PIPER_EXE_SHA256)) {
            Logger.i(TAG, "Downloading the natural DJ voice engine")
            val zip = File(voiceDir, "piper.zip.part")
            download(PIPER_ZIP_URL, zip)
            unzip(zip, voiceDir)
            zip.delete()
        }
        if (!(modelFile.exists() && sha256(modelFile) == MODEL_SHA256)) {
            Logger.i(TAG, "Downloading the natural DJ voice")
            val part = File(voiceDir, "$MODEL_NAME.part")
            download("$VOICE_BASE/$MODEL_NAME", part)
            modelFile.delete()
            part.renameTo(modelFile)
        }
        val config = File(voiceDir, CONFIG_NAME)
        if (!config.exists()) download("$VOICE_BASE/$CONFIG_NAME", config)

        val ok = piperExe.exists() && modelFile.exists() && sha256(piperExe) == PIPER_EXE_SHA256 && sha256(modelFile) == MODEL_SHA256
        if (!ok) {
            Logger.w(TAG, "Downloaded voice files did not match the expected fingerprints; they will not be run")
            piperExe.delete()
            modelFile.delete()
        }
        return ok
    }

    private fun download(
        url: String,
        target: File,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun unzip(
        zip: File,
        into: File,
    ) {
        val root = into.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(into, entry.name).canonicalFile
                // Never write outside the voice folder.
                if (!out.path.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------- system speech

    private fun speakWithSystem(text: String) {
        val builder =
            when {
                isWindows ->
                    // The text travels in an environment variable, never inside the command line,
                    // so nothing the AI writes can be read as PowerShell code.
                    ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-WindowStyle",
                        "Hidden",
                        "-Command",
                        "Add-Type -AssemblyName System.Speech; " +
                            "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak(\$env:SIMP_DJ_TEXT)",
                    ).also { it.environment()["SIMP_DJ_TEXT"] = text }
                isMac -> ProcessBuilder("say", text)
                else -> ProcessBuilder("spd-say", "--wait", text)
            }
        try {
            val started = builder.redirectErrorStream(true).start()
            process = started
            // Drain the output so the process can never block on a full pipe.
            started.inputStream.readBytes()
            started.waitFor()
        } catch (e: java.io.IOException) {
            Logger.w(TAG, "No speech engine could be started: ${e.message}")
        } finally {
            process?.destroyForcibly()
            process = null
        }
    }
}
