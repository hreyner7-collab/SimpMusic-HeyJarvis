package com.maxrave.simpmusic.dj

import com.maxrave.common.Config
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One song in a DJ session and, in plain words, why the DJ picked it. */
data class DjPick(
    val track: Track,
    val reason: String,
    val kind: DjKind,
)

/** The three kinds of run in a session; the DJ speaks when the kind changes. */
enum class DjKind { FAVOURITE, FORGOTTEN, FRESH, REQUEST }

/**
 * The running DJ session. The AI DJ speaks when the reason changes from one song to the next, so
 * the reason of each song is kept here for as long as the session lasts.
 */
object DjSessionState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val picks = HashMap<String, DjPick>()

    private val _speaking = MutableStateFlow(false)

    /** True while the DJ is talking; the DJ screen animates its ring from this. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun setSpeaking(value: Boolean) {
        _speaking.value = value
    }

    private val _status = MutableStateFlow<String?>(null)

    /** A short message for the DJ screen: what the DJ is doing, or why it could not. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun setStatus(value: String?) {
        _status.value = value
    }

    fun begin(list: List<DjPick>) {
        synchronized(picks) {
            picks.clear()
            list.forEach { picks[it.track.videoId] = it }
        }
        _active.value = true
    }

    fun pickFor(videoId: String): DjPick? = synchronized(picks) { picks[videoId] }

    fun end() {
        synchronized(picks) { picks.clear() }
        _active.value = false
    }
}

/**
 * Chooses what the DJ plays, from the listener's own library:
 *  - favourites: the songs they play the most;
 *  - forgotten: songs they saved but have not played lately;
 *  - fresh: songs related to their favourites that they have never played.
 * The three are woven together in short runs, so every few songs the DJ has something new to say.
 */
class DjSessionBuilder(
    private val songRepository: SongRepository,
    private val commonRepository: CommonRepository,
) {
    suspend fun build(): List<DjPick> {
        val mostPlayed = songRepository.getMostPlayedSongs().first().filter { it.totalPlayTime > 0 }
        val liked = songRepository.getLikedSongs().first()
        val recentIds =
            commonRepository
                .getAllRecentData()
                .first()
                .filterIsInstance<SongEntity>()
                .take(RECENT_WINDOW)
                .map { it.videoId }
                .toSet()
        val knownIds = (mostPlayed.map { it.videoId } + liked.map { it.videoId } + recentIds).toSet()

        val favourites =
            mostPlayed.take(FAVOURITE_POOL).let { top ->
                // The first song is the listener's number one, which makes the best opening line.
                listOfNotNull(top.firstOrNull()) + top.drop(1).shuffled()
            }.take(RUN_SIZE * ROUNDS).mapIndexed { index, song ->
                DjPick(
                    song.toTrack(),
                    if (index == 0) {
                        "it is the listener's number one most played song"
                    } else {
                        "it is one of the listener's most played songs"
                    },
                    DjKind.FAVOURITE,
                )
            }

        val forgotten =
            liked
                .filter { it.videoId !in recentIds }
                .shuffled()
                .take(RUN_SIZE * ROUNDS)
                .map { DjPick(it.toTrack(), "the listener saved it but has not played it in a while", DjKind.FORGOTTEN) }

        val fresh = mutableListOf<DjPick>()
        val seeds = mostPlayed.take(FAVOURITE_POOL).shuffled().take(SEED_COUNT)
        for (seed in seeds) {
            val related =
                runCatching { songRepository.getRelatedData(seed.videoId).first().data?.first.orEmpty() }
                    .getOrDefault(emptyList())
            related
                .asSequence()
                .filter { it.videoId !in knownIds && fresh.none { pick -> pick.track.videoId == it.videoId } }
                .take(RUN_SIZE * 2)
                .forEach { fresh += DjPick(it, "the listener has not played it before, and it is close to ${seed.title}", DjKind.FRESH) }
        }

        // Weave: a run from each pool in turn, so the reason changes every RUN_SIZE songs.
        val pools = listOf(favourites.toMutableList(), fresh, forgotten.toMutableList())
        val result = mutableListOf<DjPick>()
        var round = 0
        while (result.size < MAX_SESSION && pools.any { it.isNotEmpty() } && round < 40) {
            for (pool in pools) {
                val run = pool.take(RUN_SIZE)
                repeat(run.size) { pool.removeAt(0) }
                result += run
            }
            round++
        }
        return result.distinctBy { it.track.videoId }.take(MAX_SESSION)
    }

    private companion object {
        const val RECENT_WINDOW = 60
        const val FAVOURITE_POOL = 40
        const val SEED_COUNT = 4
        const val RUN_SIZE = 3
        const val ROUNDS = 4
        const val MAX_SESSION = 40
    }
}
