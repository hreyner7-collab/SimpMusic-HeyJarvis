package com.maxrave.simpmusic.dj

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform
import java.util.UUID
import kotlin.coroutines.resume

actual object DjVoice {
    @Volatile private var tts: TextToSpeech? = null

    actual fun isAvailable(): Boolean = true

    actual fun warmUp() = Unit

    actual var preferSystemVoice: Boolean = false

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val context = KoinPlatform.getKoin().get<Context>()
        return suspendCancellableCoroutine { cont ->
            lateinit var created: TextToSpeech
            created =
                TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts = created
                        if (cont.isActive) cont.resume(created)
                    } else if (cont.isActive) {
                        cont.resume(null)
                    }
                }
        }
    }

    actual suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = engine() ?: return
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                },
            )
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    actual suspend fun playIntroClip() {
        val context = KoinPlatform.getKoin().get<Context>()
        val file = java.io.File(context.cacheDir, "ace_dj_voice.mp3")
        if (!file.exists()) {
            val bytes = simpmusic.composeapp.generated.resources.Res.readBytes("files/ace_dj_voice.mp3")
            file.writeBytes(bytes)
        }
        suspendCancellableCoroutine { cont ->
            val player = android.media.MediaPlayer()
            try {
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener {
                    it.release()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                player.prepare()
                player.start()
                cont.invokeOnCancellation {
                    runCatching {
                        if (player.isPlaying) player.stop()
                        player.release()
                    }
                }
            } catch (_: Exception) {
                runCatching { player.release() }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    actual fun stop() {
        tts?.stop()
    }
}
