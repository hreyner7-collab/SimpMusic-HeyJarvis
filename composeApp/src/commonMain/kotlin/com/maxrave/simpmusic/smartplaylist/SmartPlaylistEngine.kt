package com.maxrave.simpmusic.smartplaylist

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity

/**
 * Pure, deterministic rule evaluator for Smart Playlists.
 * Zero I/O or platform dependencies — 100% unit-testable in `commonTest`.
 */
object SmartPlaylistEngine {
    const val MILLIS_PER_DAY: Long = 86_400_000L

    fun evaluate(
        definition: SmartPlaylistDefinition,
        songs: List<SongEntity>,
        lastPlayedByVideoId: Map<String, Long>,
        nowMs: Long,
        playTimeSecondsOverride: Map<String, Long> = emptyMap(),
    ): List<SongEntity> {
        if (songs.isEmpty()) return emptyList()

        val filtered =
            if (definition.conditions.isEmpty()) {
                songs
            } else {
                songs.filter { song ->
                    when (definition.matchMode) {
                        SmartPlaylistMatchMode.ALL ->
                            definition.conditions.all { cond ->
                                matchesCondition(cond, song, lastPlayedByVideoId, nowMs, playTimeSecondsOverride)
                            }

                        SmartPlaylistMatchMode.ANY ->
                            definition.conditions.any { cond ->
                                matchesCondition(cond, song, lastPlayedByVideoId, nowMs, playTimeSecondsOverride)
                            }
                    }
                }
            }

        val sorted =
            when (definition.sort) {
                SmartPlaylistSort.MOST_PLAYED ->
                    filtered.sortedWith(
                        compareByDescending<SongEntity> { effectivePlayTime(it, playTimeSecondsOverride) }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )

                SmartPlaylistSort.LEAST_PLAYED ->
                    filtered.sortedWith(
                        compareBy<SongEntity> { effectivePlayTime(it, playTimeSecondsOverride) }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )

                SmartPlaylistSort.RECENTLY_ADDED ->
                    filtered.sortedWith(
                        compareByDescending<SongEntity> { it.inLibrary }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )

                SmartPlaylistSort.SHORTEST ->
                    filtered.sortedWith(
                        compareBy<SongEntity> { it.durationSeconds }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )

                SmartPlaylistSort.LONGEST ->
                    filtered.sortedWith(
                        compareByDescending<SongEntity> { it.durationSeconds }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )

                SmartPlaylistSort.TITLE_ASC ->
                    filtered.sortedWith(
                        compareBy<SongEntity> { it.title.lowercase() }
                            .thenBy { it.videoId },
                    )
            }

        return if (definition.limit > 0) sorted.take(definition.limit) else sorted
    }

    internal fun matchesCondition(
        condition: SmartPlaylistCondition,
        song: SongEntity,
        lastPlayedByVideoId: Map<String, Long>,
        nowMs: Long,
        playTimeSecondsOverride: Map<String, Long> = emptyMap(),
    ): Boolean {
        val rawVal = condition.value.trim()
        return when (condition.field) {
            SmartPlaylistRuleField.LIKED -> {
                val expected = parseBool(rawVal) ?: return false
                when (condition.operator) {
                    SmartPlaylistOperator.EQUALS -> song.liked == expected
                    else -> song.liked == expected
                }
            }

            SmartPlaylistRuleField.DOWNLOADED -> {
                val expected = parseBool(rawVal) ?: return false
                val isDownloaded = song.downloadState == DownloadState.STATE_DOWNLOADED
                when (condition.operator) {
                    SmartPlaylistOperator.EQUALS -> isDownloaded == expected
                    else -> isDownloaded == expected
                }
            }

            SmartPlaylistRuleField.DURATION_SECONDS -> {
                val target = rawVal.toLongOrNull() ?: return false
                compareNumeric(song.durationSeconds.toLong(), condition.operator, target)
            }

            SmartPlaylistRuleField.TOTAL_PLAY_TIME_SECONDS -> {
                val target = rawVal.toLongOrNull() ?: return false
                compareNumeric(effectivePlayTime(song, playTimeSecondsOverride), condition.operator, target)
            }

            SmartPlaylistRuleField.DAYS_SINCE_LAST_PLAYED -> {
                val targetDays = rawVal.toDoubleOrNull() ?: return false
                val lastPlayedMs = lastPlayedByVideoId[song.videoId]
                if (lastPlayedMs == null || lastPlayedMs <= 0L) {
                    // Never played: counts as infinitely long ago for GREATER_THAN (e.g. Forgotten Favorites),
                    // and NOT recently played for LESS_THAN / EQUALS (e.g. Heavy Rotation).
                    return condition.operator == SmartPlaylistOperator.GREATER_THAN
                }
                val elapsedDays = (nowMs - lastPlayedMs).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY.toDouble()
                when (condition.operator) {
                    SmartPlaylistOperator.GREATER_THAN -> elapsedDays > targetDays
                    SmartPlaylistOperator.LESS_THAN -> elapsedDays < targetDays
                    SmartPlaylistOperator.EQUALS -> elapsedDays.toLong() == targetDays.toLong()
                    SmartPlaylistOperator.CONTAINS -> false
                }
            }

            SmartPlaylistRuleField.ARTIST_CONTAINS -> {
                if (rawVal.isEmpty()) return true
                val artists = song.artistName.orEmpty()
                val joined = artists.joinToString(", ")
                when (condition.operator) {
                    SmartPlaylistOperator.EQUALS ->
                        joined.equals(rawVal, ignoreCase = true) ||
                            artists.any { it.equals(rawVal, ignoreCase = true) }

                    SmartPlaylistOperator.CONTAINS ->
                        joined.contains(rawVal, ignoreCase = true)

                    else -> joined.contains(rawVal, ignoreCase = true)
                }
            }

            SmartPlaylistRuleField.TITLE_CONTAINS -> {
                if (rawVal.isEmpty()) return true
                when (condition.operator) {
                    SmartPlaylistOperator.EQUALS -> song.title.equals(rawVal, ignoreCase = true)
                    SmartPlaylistOperator.CONTAINS -> song.title.contains(rawVal, ignoreCase = true)
                    else -> song.title.contains(rawVal, ignoreCase = true)
                }
            }
        }
    }

    private fun effectivePlayTime(
        song: SongEntity,
        overrideMap: Map<String, Long>,
    ): Long = overrideMap[song.videoId] ?: song.totalPlayTime

    private fun compareNumeric(
        actual: Long,
        operator: SmartPlaylistOperator,
        target: Long,
    ): Boolean =
        when (operator) {
            SmartPlaylistOperator.EQUALS -> actual == target
            SmartPlaylistOperator.GREATER_THAN -> actual > target
            SmartPlaylistOperator.LESS_THAN -> actual < target
            SmartPlaylistOperator.CONTAINS -> actual.toString().contains(target.toString())
        }

    private fun parseBool(raw: String): Boolean? =
        when (raw.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
}
