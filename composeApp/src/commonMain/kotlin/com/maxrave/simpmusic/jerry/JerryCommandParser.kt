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
            buildVolumeUpPhrases() to JerryCommand.VolumeUp,
            buildVolumeDownPhrases() to JerryCommand.VolumeDown,
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
        "one" to 1, "won" to 1,
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
        "thirty" to 30, "forty" to 40, "fifty" to 50,
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

        val transportCandidate = withoutPrefix
            .replace(transportSuffixPattern, "")
            .trimEnd(',', '.', '!', '?', ':', ';')
            .trim()
        val transportLower = transportCandidate.lowercase(Locale.ROOT)

        if (transportLower.isNotEmpty()) {
            parseVolumeCommand(transportLower)?.let { return it }
            phrases.firstOrNull { (words, _) -> transportLower in words }?.let { return it.second }
            matchPreviousPattern(transportLower)?.let { return it }
            matchNextPattern(transportLower)?.let { return it }
        }

        val prefixLower = withoutPrefix.lowercase(Locale.ROOT)
        extractPlayQueryText(withoutPrefix, prefixLower)?.let { rawQuery ->
            val qLower = rawQuery.lowercase(Locale.ROOT)
            return when {
                qLower in indirectDiscoveryMixPhrases -> JerryCommand.PlayQuery("discovery mix")
                qLower in indirectMixesPhrases -> JerryCommand.PlayQuery("mixes")
                else -> JerryCommand.PlayQuery(rawQuery)
            }
        }

        if (transportLower in indirectDiscoveryMixPhrases) {
            return JerryCommand.PlayQuery("discovery mix")
        }
        if (transportLower in indirectMixesPhrases) {
            return JerryCommand.PlayQuery("mixes")
        }

        return if (transportLower.isNotEmpty()) truncated(transportLower) else null
    }

    private fun extractPlayQueryText(withoutPrefix: String, prefixLower: String): String? {
        val rawBody = when {
            prefixLower.startsWith("play ") || prefixLower.startsWith("play,") ->
                withoutPrefix.substring(4)
            prefixLower.startsWith("put on ") -> withoutPrefix.substring(7)
            prefixLower.startsWith("listen to ") -> withoutPrefix.substring(10)
            prefixLower.startsWith("stream ") -> withoutPrefix.substring(7)
            prefixLower.startsWith("start playing ") &&
                prefixLower.removePrefix("start playing ").trim() !in setOf("music", "the music", "again", "it") ->
                withoutPrefix.substring(14)
            else -> return null
        }
        val cleaned = rawBody
            .trimStart(',', ' ')
            .replace(querySuffixPattern, "")
            .trimEnd('.', '!', '?', ',')
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
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
        "start the music", "start playing", "start playback", "start the song",
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
        "stop track", "stop the track", "quiet", "be quiet", "shut up", "mute",
        "silence", "turn off music", "turn off the music", "turn the music off",
        "kill the music", "cut the music",
    )

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
