package com.maxrave.simpmusic.dj

import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.DjRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Builds a session and starts it as the player's queue; also answers the listener's requests. */
class DjSessionStarter(
    private val builder: DjSessionBuilder,
    private val handler: MediaPlayerHandler,
    private val djRepository: DjRepository,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
) {
    /** A new mix from the listener's own music. The DJ button on the DJ screen calls this again for different picks. */
    suspend fun start(): Result<Int> =
        runCatching {
            DjSessionState.setStatus("Putting a mix together...")
            val picks = builder.build()
            if (picks.size < MIN_SESSION) {
                error("The DJ needs a few more songs to work with. Play and like some music first.")
            }
            play(picks)
        }.also { DjSessionState.setStatus(it.exceptionOrNull()?.message) }

    /** Songs for a request such as "something upbeat", found by the user's AI and searched on YouTube Music. */
    suspend fun request(text: String): Result<Int> =
        runCatching {
            DjSessionState.setStatus("Finding songs for \"$text\"...")
            val taste =
                songRepository
                    .getMostPlayedSongs()
                    .first()
                    .take(5)
                    .mapNotNull { it.artistName?.firstOrNull() }
                    .distinct()
                    .joinToString(", ")
            val queries = djRepository.suggest(text, taste).getOrThrow()
            val tracks = mutableListOf<Track>()
            for (query in queries) {
                val found = runCatching { searchRepository.getSearchDataSong(query).first().data?.firstOrNull() }.getOrNull()
                val track = found?.toTrack()
                if (track != null && tracks.none { it.videoId == track.videoId }) tracks += track
                if (tracks.size >= MAX_REQUEST) break
            }
            if (tracks.isEmpty()) error("No songs were found for that request.")
            play(tracks.map { DjPick(it, "the listener asked for \"$text\"", DjKind.REQUEST) })
        }.also { DjSessionState.setStatus(it.exceptionOrNull()?.message) }

    private suspend fun play(picks: List<DjPick>): Int {
        // Start fetching the natural voice now, so it is ready by the time the DJ speaks.
        DjVoice.warmUp()
        DjSessionState.begin(picks)
        val tracks = picks.map { it.track }
        withContext(Dispatchers.Main.immediate) {
            handler.reset()
            handler.setQueueData(
                QueueData.Data(
                    listTracks = ArrayList(tracks),
                    firstPlayedTrack = tracks.first(),
                    playlistId = null,
                    playlistName = "DJ",
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            handler.loadMediaItem(tracks.first(), Config.PLAYLIST_CLICK, 0)
        }
        Logger.i("AiDj", "DJ session started with ${tracks.size} songs")
        return tracks.size
    }

    private companion object {
        const val MIN_SESSION = 5
        const val MAX_REQUEST = 8
    }
}
