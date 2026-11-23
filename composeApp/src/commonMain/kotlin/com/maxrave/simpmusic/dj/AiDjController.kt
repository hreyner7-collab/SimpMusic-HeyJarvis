package com.maxrave.simpmusic.dj

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.maxrave.domain.repository.DjRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The AI DJ: before every Nth song a short intro, written by the user's own AI provider and read
 * aloud by the system voice over the start of the song, with the music turned down while it talks.
 *
 * The music is turned down through the player's sleep-fade level rather than its volume: the volume
 * is the user's own and drives the slider, so changing it would drag the slider down and, if the
 * app died mid-sentence, leave the music quiet for good.
 */
class AiDjController(
    private val handler: MediaPlayerHandler,
    private val dataStore: DataStoreManager,
    private val repository: DjRepository,
) {
    suspend fun run(): Unit =
        coroutineScope {
            var lastId: String? = null
            var previousTitle: String? = null
            var previousArtist: String? = null
            var songsSinceIntro = 0
            var speech: Job? = null
            var lastKind: DjKind? = null

            handler.nowPlayingState.collect { state ->
                if (!state.isNotEmpty()) return@collect
                val id = state.mediaItem.mediaId
                if (id == lastId) return@collect
                lastId = id
                // A skip ends whatever was being said about the song that just left.
                speech?.cancel()

                val title = state.mediaItem.metadata.title?.toString().orEmpty()
                val artist = state.mediaItem.metadata.artist?.toString().orEmpty()
                val beforeTitle = previousTitle
                val beforeArtist = previousArtist
                previousTitle = title
                previousArtist = artist

                // A DJ session talks whenever the kind of run changes, whatever the every-Nth setting says.
                if (DjSessionState.active.value) {
                    val pick = DjSessionState.pickFor(id)
                    if (pick == null) {
                        // The listener moved on to other music: the session is over.
                        DjSessionState.end()
                        lastKind = null
                    } else {
                        if (pick.kind != lastKind && DjVoice.isAvailable()) {
                            speech = launch { introduce(id, title, artist, beforeTitle, beforeArtist, pick.reason) }
                        }
                        lastKind = pick.kind
                        return@collect
                    }
                }
                // Not in a DJ session: the DJ stays silent. It only talks while a session started from
                // the DJ screen (or its play-bar button) is running, never over the user's own playlists.
                lastKind = null
            }
        }

    private suspend fun introduce(
        id: String,
        title: String,
        artist: String,
        previousTitle: String?,
        previousArtist: String?,
        reason: String? = null,
    ) {
        // Commentary off: music only, and no AI request spent on a line nobody will hear.
        if (dataStore.djCommentary.first() != DataStoreManager.TRUE) return
        val result =
            withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                repository.intro(title, artist, previousTitle, previousArtist, reason)
            }
        // Without an AI provider (or when it refuses or is slow) the DJ still talks, with a built-in
        // line, so the voice never depends on a key.
        val text =
            result?.getOrNull() ?: run {
                Logger.w(TAG, "AI intro unavailable (${result?.exceptionOrNull()?.message ?: "too slow"}); using the built-in line")
                fallbackLine(title, artist, reason)
            }
        // The answer can arrive before the song has started (the built-in line is instant), or late.
        // Wait for it to be playing, but never talk over a different song.
        var waitedMs = 0L
        while (!handler.controlState.value.isPlaying) {
            if (handler.nowPlayingState.value.mediaItem.mediaId != id || waitedMs >= PLAY_WAIT_MS) {
                Logger.w(TAG, "DJ line dropped: the song changed or never started playing")
                return
            }
            delay(200)
            waitedMs += 200
        }
        if (handler.nowPlayingState.value.mediaItem.mediaId != id) return
        Logger.i(TAG, "DJ speaks: $text")

        DjVoice.preferSystemVoice = dataStore.djVoice.first() == "SYSTEM"
        // The music never plays under the voice: pause it while the DJ talks, then resume it.
        var pausedByDj = false
        try {
            if (handler.controlState.value.isPlaying) {
                handler.onPlayerEvent(PlayerEvent.PlayPause)
                pausedByDj = true
            }
            DjSessionState.setSpeaking(true)
            DjVoice.speak(text)
            Logger.i(TAG, "DJ finished speaking")
        } finally {
            DjSessionState.setSpeaking(false)
            DjVoice.stop()
            withContext(NonCancellable) {
                // Resume only if this song is still the one we paused and nobody else restarted it.
                if (pausedByDj &&
                    handler.nowPlayingState.value.mediaItem.mediaId == id &&
                    !handler.controlState.value.isPlaying &&
                    DjSessionState.active.value
                ) {
                    handler.onPlayerEvent(PlayerEvent.PlayPause)
                }
            }
        }
    }

    /** A plain spoken line that needs no AI: the reason the DJ picked the song, then the song. */
    private fun fallbackLine(
        title: String,
        artist: String,
        reason: String?,
    ): String {
        val by = if (artist.isBlank()) title else "$title by $artist"
        val variants =
            when {
                reason == null -> listOf("Here is $by.", "Up next, $by.")
                reason.contains("number one") ->
                    listOf("Let's start with your number one. $by.", "This one is your most played song of all. $by.")
                reason.contains("most played") ->
                    listOf("One of your favourites. $by.", "You play this one a lot. $by.", "Back to a song you love. $by.")
                reason.contains("asked for") ->
                    listOf("You asked for it. $by.", "Coming right up. $by.", "Here is one for your request. $by.")
                reason.contains("has not played it before") ->
                    listOf("Something new for you. $by.", "You haven't heard this one yet. $by.", "Here is a fresh find. $by.")
                else ->
                    listOf("It has been a while since you played this one. $by.", "Remember this one? $by.", "A song you saved and forgot. $by.")
            }
        return variants[(title.hashCode() and Int.MAX_VALUE) % variants.size]
    }

    private companion object {
        const val TAG = "AiDj"
        const val DUCKED_LEVEL = 0.25f
        const val GENERATE_TIMEOUT_MS = 10_000L
        const val PLAY_WAIT_MS = 15_000L
    }
}
