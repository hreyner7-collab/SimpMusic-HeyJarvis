package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.abs

/**
 * Scans a playlist for exact or normalized duplicates and finds playable replacements for
 * unavailable/deleted tracks without touching the order of kept tracks.
 */
object PlaylistHealthManager {
    data class DuplicateScanResult(
        val keptSongs: List<SongEntity>,
        val duplicatesToRemove: List<SongEntity>,
    )

    data class RepairProposal(
        val brokenSong: SongEntity,
        val replacement: Track,
        val score: Int,
    )

    data class HealthCleanReport(
        val duplicatesRemoved: Int,
        val tracksRepaired: Int,
        val unrepairableCount: Int,
    ) {
        val totalFixed: Int get() = duplicatesRemoved + tracksRepaired

        fun toSummaryMessage(): String =
            when {
                duplicatesRemoved == 0 && tracksRepaired == 0 && unrepairableCount == 0 ->
                    "Playlist is clean — no duplicates or unavailable tracks found."
                duplicatesRemoved > 0 && tracksRepaired > 0 ->
                    "Removed $duplicatesRemoved duplicate${if (duplicatesRemoved == 1) "" else "s"} & repaired $tracksRepaired track${if (tracksRepaired == 1) "" else "s"}."
                duplicatesRemoved > 0 ->
                    "Removed $duplicatesRemoved duplicate${if (duplicatesRemoved == 1) "" else "s"}."
                tracksRepaired > 0 ->
                    "Repaired $tracksRepaired unavailable track${if (tracksRepaired == 1) "" else "s"}."
                else ->
                    "Found $unrepairableCount unavailable track${if (unrepairableCount == 1) "" else "s"}, but no matching replacement was found."
            }
    }

    private val BRACKETED_NOISE =
        Regex(
            """[\(\[\{]\s*(?:official\s*(?:music\s*)?(?:video|audio|visualizer|lyric\s*video)?|lyrics?|lyric\s*video|audio|hd|4k|hq|explicit|clean|remaster(?:ed)?(?:\s*\d{4})?|radio\s*edit|topic|mv)\s*[\)\]\}]""",
            RegexOption.IGNORE_CASE,
        )
    private val FEAT_TAIL =
        Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^a-z0-9\s]""")
    private val MULTI_SPACE = Regex("""\s+""")

    private val UNWANTED_VARIANT_WORDS =
        listOf(
            "slowed",
            "reverb",
            "nightcore",
            "sped up",
            "speed up",
            "karaoke",
            "instrumental",
            "8d",
            "cover",
            "tribute",
            "lofi",
            "lo-fi",
            "bass boosted",
        )

    fun normalizeTrackTitle(raw: String): String {
        var cleaned = raw.trim()
        cleaned = BRACKETED_NOISE.replace(cleaned, " ")
        cleaned = FEAT_TAIL.replace(cleaned, " ")
        cleaned = cleaned.lowercase()
        cleaned = NON_ALNUM.replace(cleaned, " ")
        return MULTI_SPACE.replace(cleaned, " ").trim()
    }

    fun normalizeArtist(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lowered = raw.lowercase().replace(" - topic", "").trim()
        val stripped = NON_ALNUM.replace(lowered, " ")
        return MULTI_SPACE.replace(stripped, " ").trim()
    }

    fun canonicalTrackKey(
        title: String,
        primaryArtist: String?,
    ): String {
        val normTitle = normalizeTrackTitle(title)
        val normArtist = normalizeArtist(primaryArtist)
        if (normTitle.isBlank()) return ""
        return "$normArtist|$normTitle"
    }

    /**
     * Identifies duplicates by exact `videoId` OR normalized `(primaryArtist, title)`.
     * When duplicates exist, keeps the highest-quality copy (downloaded > available > earliest)
     * in its original slot and marks the other entries for removal.
     */
    fun findDuplicates(songs: List<SongEntity>): DuplicateScanResult {
        if (songs.size <= 1) return DuplicateScanResult(songs, emptyList())

        val groups = LinkedHashMap<String, MutableList<Pair<Int, SongEntity>>>()
        songs.forEachIndexed { idx, song ->
            val key =
                canonicalTrackKey(song.title, song.artistName?.firstOrNull())
                    .ifBlank { "id:${song.videoId}" }
            groups.getOrPut(key) { mutableListOf() }.add(idx to song)
        }

        // Also catch exact same videoId even if metadata differs slightly.
        val keepIndices = HashSet<Int>()
        val seenVideoIds = HashSet<String>()
        val duplicates = mutableListOf<SongEntity>()

        for ((_, entries) in groups) {
            val winner =
                entries.maxWithOrNull(
                    compareBy<Pair<Int, SongEntity>>(
                        { it.second.downloadState == DownloadState.STATE_DOWNLOADED },
                        { it.second.isAvailable },
                        { it.second.durationSeconds > 0 },
                        { -it.first },
                    ),
                ) ?: continue

            if (seenVideoIds.add(winner.second.videoId)) {
                keepIndices.add(winner.first)
            }
            for ((idx, song) in entries) {
                if (idx != winner.first || !keepIndices.contains(idx)) {
                    duplicates.add(song)
                }
            }
        }

        val kept = songs.filterIndexed { idx, _ -> idx in keepIndices }
        return DuplicateScanResult(keptSongs = kept, duplicatesToRemove = duplicates)
    }

    fun isLikelyUnavailable(song: SongEntity): Boolean =
        song.videoId.isBlank() ||
            !song.isAvailable ||
            song.title.equals("[Deleted video]", ignoreCase = true) ||
            song.title.equals("[Private video]", ignoreCase = true)

    fun scoreReplacementCandidate(
        originalTitle: String,
        originalArtist: String?,
        originalDurationSec: Int,
        candidate: SongsResult,
    ): Int {
        if (candidate.videoId.isBlank()) return Int.MIN_VALUE
        val origNormTitle = normalizeTrackTitle(originalTitle)
        val candNormTitle = normalizeTrackTitle(candidate.title.orEmpty())
        if (origNormTitle.isBlank() || candNormTitle.isBlank()) return -100

        var score = 0
        when {
            candNormTitle == origNormTitle -> score += 60
            candNormTitle.contains(origNormTitle) || origNormTitle.contains(candNormTitle) -> score += 35
            else -> {
                val origWords = origNormTitle.split(" ").filter { it.length >= 2 }.toSet()
                val candWords = candNormTitle.split(" ").filter { it.length >= 2 }.toSet()
                if (origWords.isNotEmpty()) {
                    val overlap = origWords.intersect(candWords).size.toFloat() / origWords.size.toFloat()
                    score += (overlap * 30f).toInt()
                }
            }
        }

        val origArtist = normalizeArtist(originalArtist)
        val candArtists = candidate.artists.orEmpty().map { normalizeArtist(it.name) }
        if (origArtist.isNotBlank() && candArtists.isNotEmpty()) {
            when {
                candArtists.any { it == origArtist } -> score += 35
                candArtists.any { it.contains(origArtist) || origArtist.contains(it) } -> score += 20
                else -> score -= 25
            }
        }

        val candDur = candidate.durationSeconds ?: 0
        if (originalDurationSec > 0 && candDur > 0) {
            val diff = abs(originalDurationSec - candDur)
            when {
                diff <= 5 -> score += 20
                diff <= 15 -> score += 10
                diff <= 30 -> score += 2
                diff > 60 -> score -= 30
            }
        }

        val origRawLower = originalTitle.lowercase()
        val candRawLower = candidate.title.orEmpty().lowercase()
        for (variant in UNWANTED_VARIANT_WORDS) {
            if (candRawLower.contains(variant) && !origRawLower.contains(variant)) {
                score -= 50
            }
        }
        return score
    }

    suspend fun findBestReplacement(
        brokenSong: SongEntity,
        searchRepository: SearchRepository,
        minScore: Int = 55,
    ): RepairProposal? {
        val cleanTitle = normalizeTrackTitle(brokenSong.title)
        if (cleanTitle.isBlank()) return null
        val artist = brokenSong.artistName?.firstOrNull().orEmpty()
        val query = "$cleanTitle $artist".trim()
        val resource = runCatching { searchRepository.getSearchDataSong(query).firstOrNull() }.getOrNull()
        val candidates = (resource as? Resource.Success)?.data.orEmpty()
        val best =
            candidates
                .asSequence()
                .filter { it.videoId.isNotBlank() && it.videoId != brokenSong.videoId }
                .map { candidate ->
                    candidate to scoreReplacementCandidate(
                        originalTitle = brokenSong.title,
                        originalArtist = artist,
                        originalDurationSec = brokenSong.durationSeconds,
                        candidate = candidate,
                    )
                }.maxByOrNull { it.second }
                ?: return null
        if (best.second < minScore) return null
        return RepairProposal(
            brokenSong = brokenSong,
            replacement = best.first.toTrack(),
            score = best.second,
        )
    }
}
