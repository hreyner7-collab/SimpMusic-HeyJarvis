package com.maxrave.simpmusic.dj

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

actual object DjListen {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    // Windows' own dictation recogniser (System.Speech). It runs in a short-lived PowerShell process
    // that listens once and prints what it heard, so nothing stays open on the microphone.
    private const val SCRIPT = """
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
${'$'}engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine
${'$'}engine.SetInputToDefaultAudioDevice()
${'$'}engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
${'$'}engine.InitialSilenceTimeout = [TimeSpan]::FromSeconds(6)
${'$'}engine.EndSilenceTimeout = [TimeSpan]::FromSeconds(1.2)
${'$'}result = ${'$'}engine.Recognize([TimeSpan]::FromSeconds(12))
if (${'$'}result -ne ${'$'}null) { [Console]::Out.Write(${'$'}result.Text) }
"""

    actual fun isAvailable(): Boolean = isWindows

    actual suspend fun listen(): String? {
        if (!isWindows) return null
        return withContext(Dispatchers.IO) {
            val script = File.createTempFile("simp-dj-listen-", ".ps1")
            try {
                script.writeText(SCRIPT, Charsets.UTF_8)
                val process =
                    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
                        .redirectErrorStream(false)
                        .start()
                val heard = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
                heard.ifBlank { null }
            } catch (e: Exception) {
                Logger.w("DjListen", "Could not listen: ${e.message}")
                null
            } finally {
                script.delete()
            }
        }
    }
}
