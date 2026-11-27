package com.maxrave.simpmusic.smartplaylist

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SmartPlaylistRuleField(
    val displayLabel: String,
    val isBoolean: Boolean = false,
    val isNumeric: Boolean = false,
) {
    LIKED("Liked", isBoolean = true),
    DOWNLOADED("Downloaded", isBoolean = true),
    DURATION_SECONDS("Duration (sec)", isNumeric = true),
    TOTAL_PLAY_TIME_SECONDS("Total Play Time (sec)", isNumeric = true),
    DAYS_SINCE_LAST_PLAYED("Days Since Last Played", isNumeric = true),
    ARTIST_CONTAINS("Artist"),
    TITLE_CONTAINS("Title"),
}

@Serializable
enum class SmartPlaylistOperator(
    val displayLabel: String,
) {
    EQUALS("is"),
    GREATER_THAN(">"),
    LESS_THAN("<"),
    CONTAINS("contains"),
}

@Serializable
data class SmartPlaylistCondition(
    val field: SmartPlaylistRuleField,
    val operator: SmartPlaylistOperator,
    val value: String,
) {
    fun summaryText(): String =
        when (field) {
            SmartPlaylistRuleField.LIKED ->
                if (value.equals("false", ignoreCase = true)) "Not liked" else "Liked"
            SmartPlaylistRuleField.DOWNLOADED ->
                if (value.equals("false", ignoreCase = true)) "Not downloaded" else "Downloaded"
            else -> "${field.displayLabel} ${operator.displayLabel} $value"
        }
}

@Serializable
enum class SmartPlaylistMatchMode(
    val displayLabel: String,
) {
    ALL("Match ALL rules"),
    ANY("Match ANY rule"),
}

@Serializable
enum class SmartPlaylistSort(
    val displayLabel: String,
) {
    MOST_PLAYED("Most Played"),
    LEAST_PLAYED("Least Played"),
    RECENTLY_ADDED("Recently Added"),
    SHORTEST("Shortest First"),
    LONGEST("Longest First"),
    TITLE_ASC("Title A–Z"),
}

@Serializable
data class SmartPlaylistDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val matchMode: SmartPlaylistMatchMode = SmartPlaylistMatchMode.ALL,
    val conditions: List<SmartPlaylistCondition> = emptyList(),
    val sort: SmartPlaylistSort = SmartPlaylistSort.MOST_PLAYED,
    val limit: Int = 50,
    val isBuiltIn: Boolean = false,
)

object SmartPlaylistPresets {
    const val FORGOTTEN_FAVORITES_ID = "preset_forgotten_favorites"
    const val HEAVY_ROTATION_ID = "preset_heavy_rotation"
    const val SHORT_AND_PUNCHY_ID = "preset_short_and_punchy"
    const val DEEP_CUTS_ID = "preset_deep_cuts"
    const val OFFLINE_READY_ID = "preset_offline_ready"

    val forgottenFavorites =
        SmartPlaylistDefinition(
            id = FORGOTTEN_FAVORITES_ID,
            name = "Forgotten Favorites",
            description = "Liked songs you haven't played in 60+ days",
            matchMode = SmartPlaylistMatchMode.ALL,
            conditions =
                listOf(
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.LIKED,
                        operator = SmartPlaylistOperator.EQUALS,
                        value = "true",
                    ),
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.DAYS_SINCE_LAST_PLAYED,
                        operator = SmartPlaylistOperator.GREATER_THAN,
                        value = "60",
                    ),
                ),
            sort = SmartPlaylistSort.LEAST_PLAYED,
            limit = 50,
            isBuiltIn = true,
        )

    val heavyRotation =
        SmartPlaylistDefinition(
            id = HEAVY_ROTATION_ID,
            name = "Heavy Rotation",
            description = "Your most-played tracks from the last 30 days",
            matchMode = SmartPlaylistMatchMode.ALL,
            conditions =
                listOf(
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.TOTAL_PLAY_TIME_SECONDS,
                        operator = SmartPlaylistOperator.GREATER_THAN,
                        value = "300",
                    ),
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.DAYS_SINCE_LAST_PLAYED,
                        operator = SmartPlaylistOperator.LESS_THAN,
                        value = "30",
                    ),
                ),
            sort = SmartPlaylistSort.MOST_PLAYED,
            limit = 50,
            isBuiltIn = true,
        )

    val shortAndPunchy =
        SmartPlaylistDefinition(
            id = SHORT_AND_PUNCHY_ID,
            name = "Short & Punchy",
            description = "Quick bangers under 3 minutes",
            matchMode = SmartPlaylistMatchMode.ALL,
            conditions =
                listOf(
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.DURATION_SECONDS,
                        operator = SmartPlaylistOperator.LESS_THAN,
                        value = "180",
                    ),
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.DURATION_SECONDS,
                        operator = SmartPlaylistOperator.GREATER_THAN,
                        value = "0",
                    ),
                ),
            sort = SmartPlaylistSort.SHORTEST,
            limit = 50,
            isBuiltIn = true,
        )

    val deepCuts =
        SmartPlaylistDefinition(
            id = DEEP_CUTS_ID,
            name = "Deep Cuts",
            description = "Liked tracks with low play time waiting to be rediscovered",
            matchMode = SmartPlaylistMatchMode.ALL,
            conditions =
                listOf(
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.LIKED,
                        operator = SmartPlaylistOperator.EQUALS,
                        value = "true",
                    ),
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.TOTAL_PLAY_TIME_SECONDS,
                        operator = SmartPlaylistOperator.LESS_THAN,
                        value = "180",
                    ),
                ),
            sort = SmartPlaylistSort.LEAST_PLAYED,
            limit = 50,
            isBuiltIn = true,
        )

    val offlineReady =
        SmartPlaylistDefinition(
            id = OFFLINE_READY_ID,
            name = "Offline Ready",
            description = "Downloaded favorites ready for flights & dead zones",
            matchMode = SmartPlaylistMatchMode.ALL,
            conditions =
                listOf(
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.DOWNLOADED,
                        operator = SmartPlaylistOperator.EQUALS,
                        value = "true",
                    ),
                    SmartPlaylistCondition(
                        field = SmartPlaylistRuleField.LIKED,
                        operator = SmartPlaylistOperator.EQUALS,
                        value = "true",
                    ),
                ),
            sort = SmartPlaylistSort.RECENTLY_ADDED,
            limit = 100,
            isBuiltIn = true,
        )

    val all: List<SmartPlaylistDefinition> =
        listOf(
            forgottenFavorites,
            heavyRotation,
            shortAndPunchy,
            deepCuts,
            offlineReady,
        )
}

object SmartPlaylistCodec {
    const val DATASTORE_KEY = "smart_playlists_v1"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encodeCustomPlaylists(list: List<SmartPlaylistDefinition>): String =
        json.encodeToString(list.filterNot { it.isBuiltIn })

    fun decodeCustomPlaylists(raw: String?): List<SmartPlaylistDefinition> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SmartPlaylistDefinition>>(raw).map { it.copy(isBuiltIn = false) }
        }.getOrDefault(emptyList())
    }
}
