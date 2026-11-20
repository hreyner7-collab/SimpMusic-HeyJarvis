package com.maxrave.simpmusic.jerry

import java.util.Locale

object JerryCommandParser {
    private val unkPattern = Regex("\\[unk\\]", RegexOption.IGNORE_CASE)
    private val whitespacePattern = Regex("\\s+")
    private val politePrefixPattern = Regex(
        "^(?:(?:hey[\\s,]+jarvis|j[\\s,]+jarvis|jay[\\s,]+jarvis|jarvis|please|can\\s+you|could\\s+you|would\\s+you|will\\s+you|" +
            "i\\s+want\\s+you\\s+to|i\\s+need\\s+you\\s+to|go\\s+ahead\\s+and|just|right\\s+now|now|um|uh|well)\\b[\\s,.:;!?-]*)+",
        RegexOption.IGNORE_CASE,
    )
    private val transportSuffixPattern = Regex(
        "(?:[\\s,.:;!?-]+\\b(?:hey[\\s,]+jarvis|jarvis|please|for\\s+me|right\\s+now|now|thanks|thank\\s+you|already))+$",
        RegexOption.IGNORE_CASE,
    )
    private val querySuffixPattern = Regex(
        "(?:[\\s,.:;!?-]+\\b(?:hey[\\s,]+jarvis|jarvis|please|for\\s+me))+$",
        RegexOption.IGNORE_CASE,
    )

    private val phrases: List<Pair<Set<String>, JerryCommand>> =
        listOf(
            buildNextPhrases() to JerryCommand.Next,
            buildPreviousPhrases() to JerryCommand.Previous,
            buildResumePhrases() to JerryCommand.Resume,
            buildPausePhrases() to JerryCommand.Pause,
            buildStopPhrases() to JerryCommand.Stop,
            buildMutePhrases() to JerryCommand.Mute,
            buildUnmutePhrases() to JerryCommand.Unmute,
            buildRestartPhrases() to JerryCommand.RestartTrack,
            buildClearQueuePhrases() to JerryCommand.ClearQueue,
            buildShowQueuePhrases() to JerryCommand.ShowQueue,
            buildVolumeUpPhrases() to JerryCommand.VolumeUp,
            buildVolumeDownPhrases() to JerryCommand.VolumeDown,
            buildNavigatePhrases(JerryNavTarget.HOME) to JerryCommand.Navigate(JerryNavTarget.HOME),
            buildNavigatePhrases(JerryNavTarget.SEARCH) to JerryCommand.Navigate(JerryNavTarget.SEARCH),
            buildNavigatePhrases(JerryNavTarget.LIBRARY) to JerryCommand.Navigate(JerryNavTarget.LIBRARY),
            buildNavigatePhrases(JerryNavTarget.SETTINGS) to JerryCommand.Navigate(JerryNavTarget.SETTINGS),
            buildNavigatePhrases(JerryNavTarget.MIX_FOR_YOU) to JerryCommand.Navigate(JerryNavTarget.MIX_FOR_YOU),
            buildNavigatePhrases(JerryNavTarget.ANALYTICS) to JerryCommand.Navigate(JerryNavTarget.ANALYTICS),
            buildNavigatePhrases(JerryNavTarget.NOW_PLAYING) to JerryCommand.Navigate(JerryNavTarget.NOW_PLAYING),
            buildNavigatePhrases(JerryNavTarget.FULLSCREEN) to JerryCommand.Navigate(JerryNavTarget.FULLSCREEN),
            buildNavigatePhrases(JerryNavTarget.BACK) to JerryCommand.Navigate(JerryNavTarget.BACK),
        )

    private val indirectDiscoveryMixPhrases = setOf(
        "music i never heard", "music i never heard before",
        "music i have not heard", "music i haven't heard",
        "music i haven t heard", "music i've never heard",
        "songs i never heard", "songs i never heard before",
        "songs i haven't heard", "songs i have not heard",
        "something i never heard", "something i haven't heard",
        "something i have not heard", "new music for me",
        "discover new music", "discover music",
    )

    private val indirectMixesPhrases = setOf(
        "a mix", "some mix", "some mixes", "random mix", "a random mix",
        "random mixes", "any mix", "my mixes", "mixes for me", "mixes for you",
        "mix for you", "mix for me", "all mixes", "all my mixes", "all of my mixes",
        "the mixes",
        "something i'll like", "something i will like",
        "something i like", "music i'll like", "music i will like", "music i like",
        "songs i'll like", "songs i will like", "songs i like",
        "mixes that i'll like", "mixes that i like", "mixes i'll like", "mixes i like",
        "mixes it thinks i'll like", "mix that i'll like", "something good",
    )

    private val volumePresetMap: Map<String, Int> = mapOf(
        // Max (100%)
        "volume all the way up" to 100,
        "turn volume all the way up" to 100,
        "turn the volume all the way up" to 100,
        "turn it all the way up" to 100,
        "turn the music all the way up" to 100,
        "turn music all the way up" to 100,
        "turn the sound all the way up" to 100,
        "put volume all the way up" to 100,
        "put the volume all the way up" to 100,
        "bring volume all the way up" to 100,
        "bring the volume all the way up" to 100,
        "set volume all the way up" to 100,
        "set the volume all the way up" to 100,
        "all the way up" to 100,
        "way up" to 100,
        "max volume" to 100,
        "maximum volume" to 100,
        "full volume" to 100,
        "volume max" to 100,
        "volume max max" to 100,
        "volume maximum" to 100,
        "volume full" to 100,
        "max max" to 100,
        "full blast" to 100,
        "crank it" to 100,
        "crank it up" to 100,
        "crank the volume" to 100,
        "crank the volume up" to 100,
        "max it out" to 100,
        "max out the volume" to 100,
        "max out" to 100,
        "all the way loud" to 100,
        "as loud as possible" to 100,
        "loudest" to 100,
        "loudest volume" to 100,
        "highest volume" to 100,
        "top volume" to 100,
        // Min audible (10% — lowest non-silent level)
        "volume all the way down" to 10,
        "turn volume all the way down" to 10,
        "turn the volume all the way down" to 10,
        "turn it all the way down" to 10,
        "turn the music all the way down" to 10,
        "turn music all the way down" to 10,
        "turn the sound all the way down" to 10,
        "put volume all the way down" to 10,
        "put the volume all the way down" to 10,
        "bring volume all the way down" to 10,
        "bring the volume all the way down" to 10,
        "set volume all the way down" to 10,
        "set the volume all the way down" to 10,
        "all the way down" to 10,
        "way down" to 10,
        "min volume" to 10,
        "minimum volume" to 10,
        "volume min" to 10,
        "volume minimum" to 10,
        "lowest volume" to 10,
        "bottom volume" to 10,
        "whisper volume" to 10,
        "barely audible" to 10,
        "super quiet" to 10,
        "super low" to 10,
        "very low volume" to 10,
        "as quiet as possible" to 10,
        "as low as possible" to 10,
        "as soft as possible" to 10,
        "quietest" to 10,
        "quietest volume" to 10,
        // Medium (50%)
        "medium volume" to 50,
        "volume medium" to 50,
        "half volume" to 50,
        "volume half" to 50,
        "halfway volume" to 50,
        "half way volume" to 50,
        "halfway" to 50,
        "half way" to 50,
        "middle volume" to 50,
        "mid volume" to 50,
        "normal volume" to 50,
        "moderate volume" to 50,
        "volume normal" to 50,
        "volume middle" to 50,
    )

    private val numberWords: Map<String, Int> = mapOf(
        "zero" to 0, "oh" to 0,
        "a" to 1, "an" to 1, "one" to 1, "won" to 1,
        "two" to 2, "to" to 2, "too" to 2,
        "three" to 3, "tree" to 3, "free" to 3,
        "four" to 4, "for" to 4, "fore" to 4,
        "five" to 5,
        "six" to 6, "sicks" to 6, "sex" to 6,
        "seven" to 7,
        "eight" to 8, "ate" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "twenty five" to 25, "twenty-five" to 25,
        "thirty" to 30, "forty" to 40, "forty five" to 45, "forty-five" to 45, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "seventy five" to 75, "seventy-five" to 75,
        "eighty" to 80, "ninety" to 90,
        "hundred" to 100, "one hundred" to 100,
    )

    private val numberTokenRegex = Regex(
        "\\b(?:(?:set|put|change|turn|make|adjust|keep)\\s+)?(?:the\\s+)?(?:volume|sound|audio|music\\s+volume|level)" +
            "(?:\\s+(?:level|number|down|up))?(?:\\s+(?:to|at|on|is|equals))?\\s+" +
            "([a-z0-9%-]+(?:\\s+five)?)\\s*(percent|%)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val prefixNumberVolumeRegex = Regex(
        "^(\\d{1,3}|zero|one|two|three|four|five|six|seven|eight|nine|ten|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|one hundred)" +
            "\\s*(?:(percent|%)\\s*)?(?:volume|sound|audio|level)$",
        RegexOption.IGNORE_CASE,
    )
    private val bareLevelRegex = Regex(
        "^(?:set\\s+(?:it|volume|the\\s+volume|to)\\s+|turn\\s+it\\s+to\\s+|level\\s+)(\\d{1,3}|zero|one|two|three|four|five|six|seven|eight|nine|ten|" +
            "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|one hundred)\\s*(percent|%)?$",
        RegexOption.IGNORE_CASE,
    )

    private val sleepCancelPhrases = setOf(
        "cancel sleep timer", "cancel the sleep timer", "stop sleep timer", "stop the sleep timer",
        "turn off sleep timer", "turn off the sleep timer", "turn sleep timer off", "turn the sleep timer off",
        "disable sleep timer", "disable the sleep timer", "clear sleep timer", "clear the sleep timer",
        "sleep timer off", "off sleep timer", "remove sleep timer", "delete sleep timer",
    )

    private val sleepTimerRegex = Regex(
        "^(?:(?:set|start|turn\\s+on|enable)\\s+)?(?:a\\s+|the\\s+)?sleep\\s+timer(?:\\s+(?:for|to|in))?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val sleepInRegex = Regex(
        "^(?:stop(?:\\s+music|\\s+playing|\\s+playback)?|pause(?:\\s+music|\\s+playing)?|sleep)\\s+in\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val seekToPercentRegex = Regex(
        "^(?:seek|jump|skip|scrub|go)\\s+(?:to\\s+)?(\\d{1,3}|[a-z]+(?:\\s+five)?)\\s*(?:percent|%)(?:\\s+(?:of\\s+(?:the\\s+)?(?:song|track))?)?$",
        RegexOption.IGNORE_CASE,
    )
    private val seekPercentOnlyRegex = Regex(
        "^(\\d{1,3})\\s*(?:percent|%)\\s+(?:of\\s+)?(?:the\\s+)?(?:song|track)$",
        RegexOption.IGNORE_CASE,
    )
    private val seekForwardBareSet = setOf(
        "fast forward", "fast-forward", "seek forward", "seek ahead", "jump forward", "jump ahead",
    )
    private val seekBackwardBareSet = setOf(
        "rewind", "seek back", "seek backward", "seek backwards", "jump back", "jump backward", "jump backwards",
    )
    private val seekForwardDurationRegex = Regex(
        "^(?:fast[\\s-]+forward|seek\\s+forward|seek\\s+ahead|skip\\s+forward|skip\\s+ahead|jump\\s+forward|jump\\s+ahead|go\\s+forward|forward)\\s+(?:by\\s+|for\\s+)?(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val seekBackwardDurationRegex = Regex(
        "^(?:rewind|seek\\s+back(?:ward|wards)?|skip\\s+back(?:ward|wards)?|jump\\s+back(?:ward|wards)?|go\\s+back(?:ward|wards)?|back(?:ward|wards)?)\\s+(?:by\\s+|for\\s+)?(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val playNextPrefixRegex = Regex("^play\\s+next\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val playBodyNextRegex = Regex("^(?:play|queue|put)\\s+(.+?)\\s+next$", RegexOption.IGNORE_CASE)
    private val queueUpNextRegex = Regex("^queue\\s+(.+?)\\s+next$", RegexOption.IGNORE_CASE)
    private val playNextExclusions = setOf("song", "track", "one", "the song", "the track", "the one", "up")

    private val addToQueuePrefixRegex = Regex(
        "^add\\s+(?:to\\s+(?:the\\s+)?(?:queue|cue|que|queu|qeue|kew)\\s+)(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val addBodyToQueueRegex = Regex(
        "^add\\s+(.+?)\\s+to\\s+(?:the\\s+)?(?:queue|cue|que|queu|qeue|kew)$",
        RegexOption.IGNORE_CASE,
    )
    private val queueDirectRegex = Regex(
        "^(?:enqueue|(?:queue|cue|que|queu|qeue|kew)(?:\\s+up)?)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val shuffleTargetRegex = Regex(
        "^shuffle\\s+(?:play\\s+)?(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val genericPlayMixOrLibraryHints = setOf(
        "mix", "mixes", "remix", "remixes", "liked", "favorite", "favorites", "favourite",
        "favourites", "downloaded", "downloads", "offline", "most played", "top played", "top songs",
    )

    fun parse(text: String): JerryCommand? {
        val clean = text
            .replace(unkPattern, " ")
            .replace(whitespacePattern, " ")
            .trim()
            .trimStart(',', '.', '!', '?', ':', ';', '-')
            .trimEnd('.', '!', '?')
            .trim()
        if (clean.isEmpty()) return null

        val withoutPrefix = clean
            .replace(politePrefixPattern, "")
            .trimEnd('.', '!', '?')
            .trim()
        if (withoutPrefix.isEmpty()) return null

        val verbNormalized = JerrySttNormalizer.normalizeLeadVerb(withoutPrefix)

        val transportCandidate = verbNormalized
            .replace(transportSuffixPattern, "")
            .trimEnd(',', '.', '!', '?', ':', ';')
            .trim()
        val transportLowerRaw = transportCandidate.lowercase(Locale.ROOT)
        val transportNorm = JerrySttNormalizer.normalizeCommandTokens(transportLowerRaw)

        if (transportNorm.isNotEmpty()) {
            parseVolumeCommand(transportNorm)?.let { return it }
            parseSleepTimerCommand(transportNorm)?.let { return it }
            parseSeekCommand(transportNorm)?.let { return it }
            parseShuffleModeCommand(transportNorm)?.let { return it }
            parseRepeatModeCommand(transportNorm)?.let { return it }
            parseLikeDislikeCommand(transportNorm)?.let { return it }
            phrases.firstOrNull { (words, _) -> transportNorm in words }?.let { return it.second }
            matchPreviousPattern(transportNorm)?.let { return it }
            matchNextPattern(transportNorm)?.let { return it }
        }

        val queryCleaned = verbNormalized
            .replace(querySuffixPattern, "")
            .trimEnd('.', '!', '?', ',')
            .trim()

        if (queryCleaned.isNotEmpty()) {
            parseQueueAddCommand(queryCleaned)?.let { return it }
            parseShuffleTargetCommand(queryCleaned)?.let { return it }
            parseRadioCommand(queryCleaned)?.let { return it }
            extractPlayTargetCommand(queryCleaned)?.let { return it }
        }

        if (transportNorm in indirectDiscoveryMixPhrases) {
            return JerryCommand.PlayQuery("discovery mix")
        }
        if (transportNorm in indirectMixesPhrases) {
            return JerryCommand.PlayQuery("mixes")
        }

        return if (transportNorm.isNotEmpty()) truncated(transportNorm) else null
    }

    private fun parseQueueAddCommand(queryCleaned: String): JerryCommand? {
        playNextPrefixRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty() && target.lowercase(Locale.ROOT) !in playNextExclusions) {
                return JerryCommand.PlayNextQuery(target)
            }
        }
        playBodyNextRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            val targetLower = target.lowercase(Locale.ROOT)
            if (target.isNotEmpty() && targetLower !in setOf("the", "a", "song", "track", "one", "this", "up")) {
                return JerryCommand.PlayNextQuery(target)
            }
        }
        queueUpNextRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) return JerryCommand.PlayNextQuery(target)
        }

        addToQueuePrefixRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) return JerryCommand.AddToQueueQuery(target)
        }
        addBodyToQueueRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) return JerryCommand.AddToQueueQuery(target)
        }
        queueDirectRegex.matchEntire(queryCleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            val tLower = target.lowercase(Locale.ROOT)
            if (target.isNotEmpty() && tLower !in setOf("up", "next")) {
                return JerryCommand.AddToQueueQuery(target)
            }
        }
        return null
    }

    private fun parseShuffleTargetCommand(queryCleaned: String): JerryCommand? {
        val m = shuffleTargetRegex.matchEntire(queryCleaned) ?: return null
        var target = m.groupValues[1].trim()
        if (target.isEmpty()) return null
        target = JerrySttNormalizer.normalizeLeadingDescriptor(target)
        val lower = target.lowercase(Locale.ROOT)
        if (lower in setOf("on", "off", "mode", "mode on", "mode off", "queue", "the queue")) {
            return null
        }

        if (lower.startsWith("album ") || lower.startsWith("the album ")) {
            val body = target.replaceFirst(Regex("^(?:the\\s+)?album\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (body.isNotEmpty()) return JerryCommand.PlayAlbum(body)
        }
        if (lower.startsWith("playlist ") || lower.startsWith("the playlist ")) {
            val body = target.replaceFirst(Regex("^(?:the\\s+)?playlist\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (body.isNotEmpty()) return JerryCommand.PlayPlaylist(body)
        }
        if (lower.startsWith("genre ") || lower.startsWith("mood ")) {
            val body = target.replaceFirst(Regex("^(?:genre|mood)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (body.isNotEmpty()) {
                return JerryCommand.PlayGenreOrMood(
                    JerrySttNormalizer.matchGenreOrMood(body, hasExplicitCue = true) ?: body,
                )
            }
        }
        JerrySttNormalizer.matchGenreOrMood(target)?.let { matched ->
            return JerryCommand.PlayGenreOrMood(matched)
        }

        val cleanedArtist = target
            .replaceFirst(Regex("^(?:songs\\s+by|music\\s+by|tracks\\s+by|artist|the\\s+artist)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanedArtist.isEmpty()) return null
        return JerryCommand.PlayArtist(cleanedArtist, shuffle = true)
    }

    private fun parseRadioCommand(queryCleaned: String): JerryCommand.PlayRadio? {
        val lower = JerrySttNormalizer.normalizeCommandTokens(queryCleaned.lowercase(Locale.ROOT))
        if (lower in setOf("radio", "play radio", "start radio", "play the radio", "start the radio", "play a radio", "start a radio")) {
            return JerryCommand.PlayRadio(null)
        }
        Regex("^(?:play|start)\\s+radio\\s+(?:for|on|based\\s+on|of)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(queryCleaned)?.let { m ->
                val body = m.groupValues[1].trim()
                if (body.isNotEmpty()) return JerryCommand.PlayRadio(body)
            }
        Regex("^(?:play|start)\\s+radio\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(queryCleaned)?.let { m ->
                val body = m.groupValues[1].trim()
                if (body.isNotEmpty()) return JerryCommand.PlayRadio(body)
            }
        Regex("^(?:play|start)?\\s*(.+?)\\s+(?:radio\\s+station|radio|station)$", RegexOption.IGNORE_CASE)
            .matchEntire(queryCleaned)?.let { m ->
                val body = m.groupValues[1].trim()
                    .replaceFirst(Regex("^(?:a|the|some)\\s+", RegexOption.IGNORE_CASE), "")
                    .trim()
                return JerryCommand.PlayRadio(body.takeIf { it.isNotEmpty() })
            }
        return null
    }

    private fun extractPlayTargetCommand(queryCleaned: String): JerryCommand? {
        val lower = queryCleaned.lowercase(Locale.ROOT)
        val rawBody = when {
            lower.startsWith("play ") || lower.startsWith("play,") -> queryCleaned.substring(4)
            lower.startsWith("put on ") -> queryCleaned.substring(7)
            lower.startsWith("throw on ") -> queryCleaned.substring(9)
            lower.startsWith("spin up ") -> queryCleaned.substring(8)
            lower.startsWith("spin ") -> queryCleaned.substring(5)
            lower.startsWith("let's listen to ") -> queryCleaned.substring(16)
            lower.startsWith("lets listen to ") -> queryCleaned.substring(15)
            lower.startsWith("listen to ") -> queryCleaned.substring(10)
            lower.startsWith("i want to hear ") -> queryCleaned.substring(15)
            lower.startsWith("i wanna hear ") -> queryCleaned.substring(13)
            lower.startsWith("let's hear ") -> queryCleaned.substring(11)
            lower.startsWith("lets hear ") -> queryCleaned.substring(10)
            lower.startsWith("stream ") -> queryCleaned.substring(7)
            lower.startsWith("start playing ") &&
                lower.removePrefix("start playing ").trim() !in setOf("music", "the music", "again", "it") ->
                queryCleaned.substring(14)
            else -> return null
        }

        val cleaned = rawBody
            .trimStart(',', ' ')
            .replace(querySuffixPattern, "")
            .trimEnd('.', '!', '?', ',')
            .trim()
        if (cleaned.isEmpty()) return null

        val descNormalized = JerrySttNormalizer.normalizeLeadingDescriptor(cleaned)
        val qLower = descNormalized.lowercase(Locale.ROOT)

        if (qLower in indirectDiscoveryMixPhrases) {
            return JerryCommand.PlayQuery("discovery mix")
        }
        if (qLower in indirectMixesPhrases) {
            return JerryCommand.PlayQuery("mixes")
        }
        if (qLower in setOf("something", "some", "a", "an", "the")) {
            return null
        }

        // Explicit target prefixes
        Regex("^(?:the\\s+)?artist\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return JerryCommand.PlayArtist(body)
        }
        Regex("^(?:songs|music|tracks)\\s+(?:by|from)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return JerryCommand.PlayArtist(body)
        }
        Regex("^(?:the\\s+)?album\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return JerryCommand.PlayAlbum(body)
        }
        Regex("^(?:the\\s+)?playlist\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return JerryCommand.PlayPlaylist(body)
        }
        Regex("^(?:the\\s+)?(?:song|track)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) return JerryCommand.PlaySong(body)
        }
        Regex("^(?:the\\s+)?(?:genre|mood)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            if (body.isNotEmpty()) {
                return JerryCommand.PlayGenreOrMood(
                    JerrySttNormalizer.matchGenreOrMood(body, hasExplicitCue = true) ?: body,
                )
            }
        }

        // Preserve mixes & library collection queries as PlayQuery
        val isMixOrLibrary = genericPlayMixOrLibraryHints.any { hint ->
            qLower == hint || qLower.contains(" $hint") || qLower.startsWith("$hint ")
        }
        if (isMixOrLibrary) {
            return JerryCommand.PlayQuery(descNormalized)
        }

        // Offline genre / mood lexicon match (with false-positive guards)
        JerrySttNormalizer.matchGenreOrMood(descNormalized)?.let { matched ->
            return JerryCommand.PlayGenreOrMood(matched)
        }

        // Explicit suffixes
        Regex("^(.+?)\\s+album$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].removePrefix("the ").removePrefix("The ").trim()
            if (body.length >= 2) return JerryCommand.PlayAlbum(body)
        }
        Regex("^(.+?)\\s+playlist$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].removePrefix("the ").removePrefix("The ").trim()
            if (body.length >= 2) return JerryCommand.PlayPlaylist(body)
        }
        Regex("^(.+?)\\s+(?:songs|music|tracks)$", RegexOption.IGNORE_CASE).matchEntire(descNormalized)?.let {
            val body = it.groupValues[1].trim()
            val bLower = body.lowercase(Locale.ROOT)
            if (body.length >= 2 && bLower !in setOf("some", "the", "a", "my", "good", "new", "more", "any", "this", "that")) {
                return JerryCommand.PlayArtist(body)
            }
        }

        return JerryCommand.PlayQuery(descNormalized)
    }

    private fun parseVolumeCommand(lower: String): JerryCommand.SetVolume? {
        volumePresetMap[lower]?.let { return JerryCommand.SetVolume(it) }

        numberTokenRegex.find(lower)?.let { m ->
            val rawNum = m.groupValues[1].trim()
            val hasPercent = m.groupValues[2].isNotBlank() || rawNum.endsWith("%")
            parseScaledVolume(rawNum.removeSuffix("%"), hasPercent)?.let { return JerryCommand.SetVolume(it) }
        }

        prefixNumberVolumeRegex.find(lower)?.let { m ->
            val rawNum = m.groupValues[1].trim()
            val hasPercent = m.groupValues[2].isNotBlank()
            parseScaledVolume(rawNum, hasPercent)?.let { return JerryCommand.SetVolume(it) }
        }

        bareLevelRegex.find(lower)?.let { m ->
            val rawNum = m.groupValues[1].trim()
            val hasPercent = m.groupValues[2].isNotBlank()
            parseScaledVolume(rawNum, hasPercent)?.let { return JerryCommand.SetVolume(it) }
        }

        return null
    }

    private fun parseScaledVolume(token: String, hasPercent: Boolean): Int? {
        val clean = token.trim().lowercase(Locale.ROOT)
        val n = clean.toIntOrNull() ?: numberWords[clean] ?: return null
        if (n < 0 || n > 100) return null
        if (hasPercent) return n
        return when {
            n == 0 -> 0
            n in 1..10 -> n * 10
            else -> n
        }
    }

    private fun parseSleepTimerCommand(lower: String): JerryCommand.SetSleepTimer? {
        if (lower in sleepCancelPhrases) {
            return JerryCommand.SetSleepTimer(null)
        }
        val tail = sleepTimerRegex.matchEntire(lower)?.groupValues?.get(1)?.trim()
            ?: sleepInRegex.matchEntire(lower)?.groupValues?.get(1)?.trim()
            ?: return null

        if (tail in setOf("off", "cancel", "stop", "clear", "none", "zero")) {
            return JerryCommand.SetSleepTimer(null)
        }
        parseDurationMinutes(tail)?.let { mins ->
            return JerryCommand.SetSleepTimer(if (mins <= 0) null else mins)
        }
        return null
    }

    private fun parseDurationMinutes(tail: String): Int? {
        val clean = tail.trim().lowercase(Locale.ROOT)
        if (clean == "half an hour" || clean == "half hour") return 30
        if (clean == "an hour and a half" || clean == "one and a half hours") return 90
        if (clean == "an hour" || clean == "one hour" || clean == "1 hour") return 60
        Regex("^(\\d{1,3}|[a-z-]+(?:\\s+[a-z-]+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m)?$")
            .matchEntire(clean)?.let { m ->
                val numTok = m.groupValues[1].trim()
                val unit = m.groupValues[2].trim()
                val n = numTok.toIntOrNull() ?: numberWords[numTok] ?: return null
                return if (unit.startsWith("h")) n * 60 else n
            }
        return null
    }

    private fun parseSeekCommand(lower: String): JerryCommand? {
        seekToPercentRegex.matchEntire(lower)?.let { m ->
            val raw = m.groupValues[1].trim()
            val n = raw.toIntOrNull() ?: numberWords[raw]
            if (n != null && n in 0..100) return JerryCommand.SeekToPercent(n)
        }
        seekPercentOnlyRegex.matchEntire(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 0..100) return JerryCommand.SeekToPercent(n)
        }
        if (lower in seekForwardBareSet) return JerryCommand.SeekForward(10)
        if (lower in seekBackwardBareSet) return JerryCommand.SeekBackward(10)

        seekForwardDurationRegex.matchEntire(lower)?.let { m ->
            parseDurationSeconds(m.groupValues[1])?.let { secs ->
                return JerryCommand.SeekForward(secs)
            }
        }
        seekBackwardDurationRegex.matchEntire(lower)?.let { m ->
            parseDurationSeconds(m.groupValues[1])?.let { secs ->
                return JerryCommand.SeekBackward(secs)
            }
        }
        return null
    }

    private fun parseDurationSeconds(tail: String): Int? {
        val clean = tail.trim().lowercase(Locale.ROOT)
        if (clean == "a minute" || clean == "one minute" || clean == "1 minute") return 60
        if (clean == "half a minute") return 30
        Regex("^(\\d{1,4}|[a-z-]+(?:\\s+[a-z-]+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m)?$")
            .matchEntire(clean)?.let { m ->
                val numTok = m.groupValues[1].trim()
                val unit = m.groupValues[2].trim()
                val n = numTok.toIntOrNull() ?: numberWords[numTok] ?: return null
                if (n <= 0) return null
                return if (unit.startsWith("m")) n * 60 else n
            }
        return null
    }

    private fun parseShuffleModeCommand(lower: String): JerryCommand.SetShuffle? = when (lower) {
        "shuffle on", "turn shuffle on", "turn on shuffle", "enable shuffle",
        "shuffle mode on", "turn shuffle mode on", "enable shuffle mode",
        -> JerryCommand.SetShuffle(true)

        "shuffle off", "turn shuffle off", "turn off shuffle", "disable shuffle",
        "unshuffle", "shuffle mode off", "turn shuffle mode off", "disable shuffle mode",
        -> JerryCommand.SetShuffle(false)

        "shuffle", "toggle shuffle", "shuffle mode", "toggle shuffle mode",
        "shuffle queue", "shuffle the queue",
        -> JerryCommand.SetShuffle(null)

        else -> null
    }

    private fun parseRepeatModeCommand(lower: String): JerryCommand.SetRepeat? = when (lower) {
        "repeat off", "turn repeat off", "turn off repeat", "disable repeat",
        "stop repeating", "no repeat", "loop off", "turn loop off", "disable loop",
        -> JerryCommand.SetRepeat(RepeatTarget.OFF)

        "repeat one", "repeat 1", "repeat this song", "repeat this track",
        "repeat song", "repeat track", "repeat current song",
        "loop this song", "loop song", "loop one", "loop 1",
        -> JerryCommand.SetRepeat(RepeatTarget.ONE)

        "repeat all", "repeat queue", "repeat the queue", "repeat playlist",
        "repeat the playlist", "repeat on", "turn repeat on", "turn on repeat",
        "enable repeat", "loop all", "loop queue", "loop on", "turn loop on", "enable loop",
        -> JerryCommand.SetRepeat(RepeatTarget.ALL)

        "repeat", "toggle repeat", "repeat mode", "cycle repeat", "loop", "toggle loop",
        -> JerryCommand.SetRepeat(RepeatTarget.CYCLE)

        else -> null
    }

    private fun parseLikeDislikeCommand(lower: String): JerryCommand? = when (lower) {
        "like", "like song", "like this", "like this song", "like track", "like this track",
        "like the song", "love this song", "love this", "favorite this", "favorite this song",
        "favourite this", "favourite this song", "add to favorites", "add to favourites",
        "add to liked", "add to liked songs", "thumbs up",
        -> JerryCommand.ToggleLike(true)

        "unlike", "unlike song", "unlike this", "unlike this song", "unlike track",
        "unlike this track", "unlike the song", "remove like", "remove from favorites",
        "remove from favourites", "remove from liked", "remove from liked songs",
        "unfavorite", "unfavourite",
        -> JerryCommand.ToggleLike(false)

        "toggle like" -> JerryCommand.ToggleLike(null)

        "dislike", "dislike song", "dislike this", "dislike this song", "dislike track",
        "dislike this track", "dislike the song", "thumbs down", "i don't like this",
        "i don't like this song", "don't like this", "not for me", "hate this song",
        -> JerryCommand.Dislike

        else -> null
    }

    private fun matchPreviousPattern(lower: String): JerryCommand? {
        val exactOrPrefix = Regex(
            "^(?:(?:play|replay|go\\s+to|switch\\s+to|return\\s+to|back\\s+to|put\\s+on|skip\\s+to|jump\\s+to|played)\\s+)?" +
                "(?:the\\s+|a\\s+|my\\s+|that\\s+)?" +
                "(?:previous|last|prior|earlier)\\s+(?:song|track|rack|music|one)$",
        )
        if (exactOrPrefix.matches(lower)) return JerryCommand.Previous

        val beforePattern = Regex(
            "^(?:(?:play|replay|played|put|put\\s+on|go\\s+to|go\\s+back\\s+to|back\\s+to|switch\\s+to|what\\s+was|what\\s+played)\\s+)?" +
                "(?:the\\s+|a\\s+|that\\s+)?(?:(?:song|track|rack|music|one)\\s+)?before(?:\\s+(?:this|it|that|this\\s+one|that\\s+one|now))?$",
        )
        if (beforePattern.matches(lower)) return JerryCommand.Previous

        return null
    }

    private fun matchNextPattern(lower: String): JerryCommand? {
        val exactOrPrefix = Regex(
            "^(?:(?:play|go\\s+to|move\\s+to|jump\\s+to|switch\\s+to|change\\s+to|skip\\s+to|put\\s+on)\\s+)?" +
                "(?:the\\s+|a\\s+)?(?:next|following|upcoming)\\s+(?:song|track|rack|music|one)$",
        )
        if (exactOrPrefix.matches(lower)) return JerryCommand.Next

        val afterPattern = Regex(
            "^(?:(?:play|skip\\s+to|go\\s+to)\\s+)?(?:the\\s+)?(?:song|track|one)\\s+after(?:\\s+(?:this|it|that|this\\s+one))?$",
        )
        if (afterPattern.matches(lower)) return JerryCommand.Next

        return null
    }

    /**
     * The recognizer likes to drop the last syllable of a phrase — “next so”, “volume u”. Matching
     * the last word by prefix keeps those usable without loosening the phrases themselves.
     */
    private fun truncated(lower: String): JerryCommand? {
        val heard = lower.split(' ')
        if (heard.size < 2) return null
        for ((words, command) in phrases) {
            for (word in words) {
                val expected = word.split(' ')
                if (expected.size != heard.size) continue
                if (heard.last().length < 2) continue
                val sameButLast = heard.subList(0, heard.size - 1) == expected.subList(0, expected.size - 1)
                if (sameButLast && expected.last().startsWith(heard.last()) && heard.last() != expected.last()) {
                    return command
                }
            }
        }
        return null
    }

    private fun buildNextPhrases(): Set<String> = buildSet {
        addAll(
            listOf(
                "next", "next up", "next please", "next one", "the next one",
                "skip", "skip it", "skip this", "skip one", "skip please",
                "skip song", "skip track", "skip music",
                "skip the song", "skip the track", "skip the music",
                "skip this song", "skip this track", "skip this one",
                "skip that song", "skip that track", "skip that one",
                "skip forward", "skip ahead", "forward", "go forward",
                "move forward", "step forward", "onward", "advance",
                "pass", "pass this", "pass this song", "pass this one",
                "another", "another song", "another track", "another one",
                "play another", "play another song", "play another track", "play another one",
                "different song", "different track", "play a different song", "play a different track",
                "play different song", "play different track",
                "change song", "change the song", "change track", "change the track",
                "switch song", "switch the song", "switch track", "switch the track",
                "play something else", "something else",
                "play next", "play the next", "onto the next",
            ),
        )
        for (verb in listOf("", "play ", "go to ", "skip to ", "switch to ")) {
            for (art in listOf("", "the ")) {
                for (noun in listOf("next song", "next track", "next one", "song after", "song after this", "song after it")) {
                    add("$verb$art$noun".trim())
                }
            }
        }
    }

    private fun buildPreviousPhrases(): Set<String> = buildSet {
        addAll(
            listOf(
                "previous", "previous please", "previous one", "the previous one",
                "back", "go back", "back up", "back one", "back a song", "back a track",
                "back one song", "back one track", "go back a song", "go back a track",
                "go back one", "go back one song", "go back one track",
                "go back to the last song", "go back to the previous song", "go back to last song",
                "go back to previous song", "step back", "skip back", "back track", "backtrack",
                "back song", "play previous", "play the previous", "play last",
                "before this", "before it", "before that",
                "what played before", "what was before", "what was before this",
                "last song i played", "song i just played", "track i just played",
                "last one", "the last one", "play last one", "play the last one",
            ),
        )
        for (verb in listOf("", "play ", "replay ", "played ", "go to ", "back to ", "switch to ", "return to ", "put on ")) {
            for (art in listOf("", "the ", "a ")) {
                for (noun in listOf(
                    "previous song", "previous track", "previous music", "previous one",
                    "last song", "last track", "last rack",
                    "prior song", "prior track", "earlier song", "earlier track",
                    "song before", "song before it", "song before this", "song before that",
                    "track before", "track before it", "track before this", "track before that",
                    "one before", "one before it", "one before this", "one before that",
                )) {
                    add("$verb$art$noun".trim())
                }
            }
        }
    }

    private fun buildResumePhrases(): Set<String> = setOf(
        "play", "resume", "resume it", "resume music", "resume the music",
        "resume playback", "resume playing", "resume song", "resume the song",
        "play music", "play the music", "play some music", "start music",
        "start the music", "start playing", "start playing music", "start playing the music",
        "start playback", "start the song",
        "unpause", "unpause it", "unpause music", "unpause the music",
        "continue", "continue playing", "continue music", "continue the music",
        "keep playing", "go on", "carry on", "music on", "turn music on",
        "turn the music on", "put the music back on", "put the music on",
    )

    private fun buildPausePhrases(): Set<String> = setOf(
        "pause", "pause music", "pause the music", "pause it", "pause playback",
        "pause song", "pause the song", "pause this", "pause this song",
        "pause track", "pause the track", "pause playing",
        "hold on", "hold up", "wait", "wait a second", "freeze",
    )

    private fun buildStopPhrases(): Set<String> = setOf(
        "stop", "stop music", "stop the music", "stop it", "stop playing",
        "stop playback", "stop song", "stop the song", "stop this", "stop this song",
        "stop track", "stop the track", "quiet", "be quiet", "shut up",
        "silence", "turn off music", "turn off the music", "turn the music off",
        "kill the music", "cut the music",
    )

    private fun buildMutePhrases(): Set<String> = setOf(
        "mute", "mute volume", "mute the volume", "mute audio", "mute the audio",
        "mute sound", "mute the sound", "mute music", "mute the music",
        "mute it", "mute playback", "silent mode",
        "turn off sound", "turn off the sound", "turn off audio", "turn off the audio",
    )

    private fun buildUnmutePhrases(): Set<String> = setOf(
        "unmute", "unmute volume", "unmute the volume", "unmute audio", "unmute the audio",
        "unmute sound", "unmute the sound", "unmute music", "unmute the music",
        "unmute it", "unmute playback",
        "sound on", "turn sound on", "turn the sound on", "turn on sound", "turn on the sound",
        "audio on", "turn audio on", "turn the audio on", "turn on audio", "turn on the audio",
        "bring the sound back",
    )

    private fun buildRestartPhrases(): Set<String> = setOf(
        "restart", "restart song", "restart the song", "restart track", "restart the track",
        "restart this song", "restart this track", "restart music",
        "start over", "start song over", "start the song over", "start track over", "start the track over",
        "play from the beginning", "play from beginning", "from the beginning", "from the start",
        "back to the beginning", "back to the start",
        "replay song", "replay this song", "replay track", "replay this track",
        "play it again", "play again",
    )

    private fun buildClearQueuePhrases(): Set<String> = setOf(
        "clear queue", "clear the queue", "empty queue", "empty the queue",
        "reset queue", "reset the queue",
    )

    private fun buildShowQueuePhrases(): Set<String> = setOf(
        "show queue", "show the queue", "open queue", "open the queue",
        "view queue", "view the queue",
        "what's in the queue", "whats in the queue", "what is in the queue",
        "what's up next", "whats up next", "what is up next",
        "queue", "the queue",
    )

    private fun buildNavigatePhrases(target: JerryNavTarget): Set<String> = when (target) {
        JerryNavTarget.HOME -> setOf(
            "home", "go home", "open home", "show home", "home screen",
            "go to home", "navigate home", "navigate to home",
        )
        JerryNavTarget.SEARCH -> setOf(
            "search", "open search", "go to search", "show search",
            "search screen", "navigate to search",
        )
        JerryNavTarget.LIBRARY -> setOf(
            "library", "my library", "open library", "go to library", "show library",
            "open my library", "go to my library", "navigate to library",
        )
        JerryNavTarget.SETTINGS -> setOf(
            "settings", "open settings", "go to settings", "show settings",
            "open the settings", "navigate to settings",
        )
        JerryNavTarget.MIX_FOR_YOU -> setOf(
            "mix for you", "open mix for you", "go to mix for you", "show mix for you",
            "open mixes", "show mixes", "go to mixes",
        )
        JerryNavTarget.ANALYTICS -> setOf(
            "analytics", "open analytics", "go to analytics", "show analytics",
            "open stats", "show stats", "listening stats",
        )
        JerryNavTarget.NOW_PLAYING -> setOf(
            "now playing", "open now playing", "show now playing", "go to now playing",
            "show player", "open player", "open the player",
        )
        JerryNavTarget.FULLSCREEN -> setOf(
            "fullscreen", "open fullscreen", "enter fullscreen", "fullscreen mode",
            "go fullscreen", "fullscreen player",
        )
        JerryNavTarget.BACK -> setOf(
            "navigate back", "go back a screen", "back a screen", "previous screen",
            "last screen", "close screen", "exit fullscreen", "close player",
            "minimize player", "close now playing",
        )
        JerryNavTarget.QUEUE -> emptySet()
    }

    private fun buildVolumeUpPhrases(): Set<String> = buildSet {
        addAll(
            listOf(
                "louder", "make it louder", "make this louder", "a bit louder",
                "a little louder", "little louder", "much louder", "more volume",
                "higher", "volume higher", "turn up", "turn it up",
                "up volume", "boost volume", "boost the volume",
                "pump it up", "pump up the volume", "crank it a little",
            ),
        )
        for (verb in listOf("turn ", "turn up ", "raise ", "increase ", "bring ", "put ", "make ")) {
            for (target in listOf("volume", "the volume", "sound", "the sound", "music", "the music", "audio", "the audio")) {
                add("$verb$target up".replace("up $target up", "up $target").trim())
            }
        }
        addAll(
            listOf(
                "volume up", "sound up", "audio up",
                "raise volume", "raise the volume",
                "increase volume", "increase the volume",
                "turn up volume", "turn up the volume",
                "turn up sound", "turn up the sound",
            ),
        )
    }

    private fun buildVolumeDownPhrases(): Set<String> = buildSet {
        addAll(
            listOf(
                "quieter", "make it quieter", "make it quiet", "a bit quieter",
                "a little quieter", "little quieter", "much quieter", "less volume",
                "lower", "volume lower", "softer", "make it softer", "a bit softer",
                "soften it", "quiet down", "tone it down", "turn down", "turn it down",
                "down volume", "reduce volume", "reduce the volume",
                "decrease volume", "decrease the volume",
            ),
        )
        for (verb in listOf("turn ", "turn down ", "lower ", "decrease ", "reduce ", "bring ", "put ")) {
            for (target in listOf("volume", "the volume", "sound", "the sound", "music", "the music", "audio", "the audio")) {
                add("$verb$target down".replace("down $target down", "down $target").trim())
            }
        }
        addAll(
            listOf(
                "volume down", "sound down", "audio down",
                "lower volume", "lower the volume",
                "turn down volume", "turn down the volume",
                "turn down sound", "turn down the sound",
            ),
        )
    }
}
