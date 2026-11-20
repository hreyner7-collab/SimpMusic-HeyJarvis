package com.maxrave.simpmusic.jerry

import kotlin.math.abs

object JerrySttNormalizer {
    private val whitespacePattern = Regex("\\s+")
    private val nonAlnumKeepHyphenAmp = Regex("[^a-z0-9&\\s-]+")

    private val twoTokenLeadVerbMap: Map<Pair<String, String>, String> = mapOf(
        ("putt" to "on") to "put on",
        ("liston" to "to") to "listen to",
        ("lisen" to "to") to "listen to",
        ("listn" to "to") to "listen to",
        ("listen" to "too") to "listen to",
    )

    private val oneTokenUnconditionalLeadVerbMap: Map<String, String> = mapOf(
        "ply" to "play",
        "pley" to "play",
        "plai" to "play",
        "plae" to "play",
        "playe" to "play",
        "plsy" to "play",
        "strem" to "stream",
        "streem" to "stream",
        "shuffel" to "shuffle",
        "shufle" to "shuffle",
        "shuffl" to "shuffle",
        "shaffle" to "shuffle",
        "shufl" to "shuffle",
    )

    private val oneTokenGatedLeadVerbMap: Map<String, String> = mapOf(
        "plea" to "play",
        "pray" to "play",
        "blay" to "play",
    )

    private val commandTokenMap: Map<String, String> = mapOf(
        "nex" to "next", "neks" to "next", "nekt" to "next", "nxt" to "next",
        "previus" to "previous", "previos" to "previous", "preveous" to "previous", "previuos" to "previous", "prevus" to "previous",
        "paws" to "pause", "pos" to "pause", "paus" to "pause", "pauz" to "pause",
        "unpaws" to "unpause", "unpaus" to "unpause",
        "resum" to "resume", "rezume" to "resume", "reasume" to "resume",
        "stahp" to "stop", "stob" to "stop", "stopp" to "stop",
        "shuffel" to "shuffle", "shufle" to "shuffle", "shuffl" to "shuffle", "shaffle" to "shuffle", "shufl" to "shuffle",
        "repet" to "repeat", "repat" to "repeat", "repeet" to "repeat", "reapeat" to "repeat",
        "valume" to "volume", "volum" to "volume", "volumn" to "volume", "vollume" to "volume", "volumne" to "volume", "valum" to "volume",
        "mut" to "mute", "moot" to "mute", "myut" to "mute",
        "unmut" to "unmute", "unmoot" to "unmute", "unmyut" to "unmute",
        "que" to "queue", "cue" to "queue", "queu" to "queue", "qeue" to "queue", "kew" to "queue",
        "artest" to "artist", "artst" to "artist", "artits" to "artist", "artis" to "artist",
        "albm" to "album", "albun" to "album", "albem" to "album", "allbum" to "album",
        "playlst" to "playlist", "playlis" to "playlist", "plylist" to "playlist", "plalist" to "playlist",
        "genere" to "genre", "jenre" to "genre", "genra" to "genre", "gnre" to "genre",
        "radieo" to "radio", "radeo" to "radio",
        "sng" to "song", "sogn" to "song",
        "trak" to "track", "trck" to "track",
        "forwrd" to "forward", "foward" to "forward", "forword" to "forward",
        "backwrd" to "backward", "backword" to "backward", "bakward" to "backward",
        "rewnd" to "rewind", "rewined" to "rewind",
        "seak" to "seek", "sek" to "seek",
        "restar" to "restart", "restrt" to "restart",
        "timr" to "timer", "tiemr" to "timer",
        "setings" to "settings", "seting" to "settings", "settngs" to "settings",
        "librry" to "library", "libary" to "library", "libry" to "library",
        "serch" to "search", "srch" to "search",
        "analitics" to "analytics", "analytcs" to "analytics",
        "fulscreen" to "fullscreen", "fullscren" to "fullscreen",
        "dislik" to "dislike", "dislyk" to "dislike",
        "unlik" to "unlike", "unlyk" to "unlike",
        "louda" to "louder", "lowder" to "louder",
        "quietr" to "quieter", "quiter" to "quieter",
        "percnt" to "percent", "precent" to "percent",
        "minuts" to "minutes", "minits" to "minutes", "mins" to "minutes",
        "secnds" to "seconds", "secs" to "seconds",
    )

    private val leadingDescriptorMap: Map<String, String> = mapOf(
        "artest" to "artist", "artst" to "artist", "artits" to "artist", "artis" to "artist",
        "albm" to "album", "albun" to "album", "albem" to "album", "allbum" to "album",
        "playlst" to "playlist", "playlis" to "playlist", "plylist" to "playlist", "plalist" to "playlist",
        "genere" to "genre", "jenre" to "genre", "genra" to "genre", "gnre" to "genre",
        "radieo" to "radio", "radeo" to "radio",
        "sng" to "song", "sogn" to "song",
        "trak" to "track", "trck" to "track",
    )

    private val multiWordLexicon: List<String> = listOf(
        "smooth jazz", "acid jazz", "vocal jazz",
        "classic rock", "hard rock", "soft rock", "indie rock", "alternative rock",
        "punk rock", "folk rock", "progressive rock", "psychedelic rock", "pop rock",
        "hip hop", "drum and bass", "deep house", "tech house", "tropical house",
        "synth pop", "dream pop", "indie pop", "city pop",
        "heavy metal", "death metal", "nu metal", "neo soul", "bossa nova",
        "latin pop", "new age", "easy listening", "feel good", "late night",
        "rainy day", "good vibes", "high energy", "coffee shop", "road trip",
        "acoustic chill",
    )

    private val singleWordLexicon: Set<String> = setOf(
        "jazz", "rock", "pop", "metal", "indie", "alternative", "electronic", "edm",
        "house", "techno", "trance", "dubstep", "ambient", "acoustic", "blues",
        "soul", "funk", "reggae", "latin", "salsa", "country", "folk", "bluegrass",
        "classical", "orchestral", "opera", "soundtrack", "instrumental", "piano",
        "guitar", "punk", "grunge", "emo", "gospel", "christian", "afrobeats",
        "bollywood", "disco", "rap", "trap", "phonk", "chill", "chillout",
        "focus", "study", "sleep", "sleeping", "workout", "gym", "fitness",
        "party", "dance", "relax", "relaxing", "calm", "meditation", "yoga",
        "romance", "romantic", "sad", "melancholy", "happy", "upbeat",
        "energize", "energy", "commute", "driving", "morning", "gaming",
        "lo-fi", "r&b", "k-pop", "j-pop",
    )

    private val genreMoodAliases: Map<String, String> = mapOf(
        "lofi" to "lo-fi",
        "low fi" to "lo-fi",
        "lo fi" to "lo-fi",
        "rnb" to "r&b",
        "r n b" to "r&b",
        "r and b" to "r&b",
        "r & b" to "r&b",
        "hiphop" to "hip hop",
        "hip-hop" to "hip hop",
        "kpop" to "k-pop",
        "k pop" to "k-pop",
        "jpop" to "j-pop",
        "j pop" to "j-pop",
        "dnb" to "drum and bass",
        "drum n bass" to "drum and bass",
        "drum & bass" to "drum and bass",
        "e d m" to "edm",
        "work out" to "workout",
        "chill out" to "chillout",
    )

    private val trailingGenreCues: Set<String> = setOf(
        "music", "songs", "song", "tracks", "vibes", "beats", "playlist", "station",
    )

    /**
     * Layer 1: Corrects lead-verb typos at token index 0 while preserving original casing
     * of the remainder of the utterance.
     */
    fun normalizeLeadVerb(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed

        val firstSep = trimmed.indexOfFirst { it.isWhitespace() }
        if (firstSep < 0) {
            val w0 = trimmed.lowercase()
            val mapped = oneTokenUnconditionalLeadVerbMap[w0] ?: return trimmed
            return mapped
        }

        val tok0 = trimmed.substring(0, firstSep)
        val afterTok0 = trimmed.substring(firstSep).trimStart()
        val w0 = tok0.lowercase().trimEnd(',', '.')

        val secondSep = afterTok0.indexOfFirst { it.isWhitespace() }
        val tok1 = if (secondSep < 0) afterTok0 else afterTok0.substring(0, secondSep)
        val afterTok1 = if (secondSep < 0) "" else afterTok0.substring(secondSep).trimStart()
        val w1 = tok1.lowercase()

        twoTokenLeadVerbMap[w0 to w1]?.let { replacement ->
            return if (afterTok1.isEmpty()) replacement else "$replacement $afterTok1"
        }

        val commaSuffix = if (tok0.endsWith(",")) "," else ""
        oneTokenUnconditionalLeadVerbMap[w0]?.let { replacement ->
            return "$replacement$commaSuffix $afterTok0"
        }

        if (afterTok0.isNotEmpty()) {
            oneTokenGatedLeadVerbMap[w0]?.let { replacement ->
                return "$replacement$commaSuffix $afterTok0"
            }
        }

        return trimmed
    }

    /**
     * Layer 2: Normalizes command/transport/queue/mode tokens.
     * MUST ONLY be applied to non-query command candidates, never to free-form song/artist bodies.
     */
    fun normalizeCommandTokens(lower: String): String {
        val merged = lower
            .replace("un mute", "unmute")
            .replace("play list", "playlist")
            .replace("full screen", "fullscreen")
            .replace(whitespacePattern, " ")
            .trim()
        if (merged.isEmpty()) return merged
        return merged.split(' ').joinToString(" ") { tok ->
            commandTokenMap[tok] ?: tok
        }
    }

    /**
     * Scoped helper for media query bodies: only corrects a leading descriptor token
     * (at token 0, or token 1 after "the"/"a"/"some") so song titles are never corrupted.
     */
    fun normalizeLeadingDescriptor(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return trimmed
        val parts = trimmed.split(whitespacePattern)
        if (parts.isEmpty()) return trimmed

        val firstLower = parts[0].lowercase()
        leadingDescriptorMap[firstLower]?.let { replacement ->
            return (listOf(replacement) + parts.drop(1)).joinToString(" ")
        }
        if (parts.size >= 2 && firstLower in setOf("the", "a", "some")) {
            val secondLower = parts[1].lowercase()
            leadingDescriptorMap[secondLower]?.let { replacement ->
                return (listOf(parts[0], replacement) + parts.drop(2)).joinToString(" ")
            }
        }
        return trimmed
    }

    /**
     * Layer 3: Matches genre/mood queries against an offline lexicon using exact aliases,
     * phonetic skeletons, and Damerau-Levenshtein distance with strict false-positive guards.
     */
    fun matchGenreOrMood(rawQuery: String, hasExplicitCue: Boolean = false): String? {
        var cleaned = rawQuery.lowercase()
            .replace(nonAlnumKeepHyphenAmp, " ")
            .replace(whitespacePattern, " ")
            .trim()
        if (cleaned.isEmpty()) return null

        var cuePresent = hasExplicitCue
        if (cleaned.startsWith("some ") && cleaned.length > 5) {
            cleaned = cleaned.removePrefix("some ").trim()
        }

        val initialTokens = cleaned.split(' ').filter { it.isNotEmpty() }
        val tokens = if (initialTokens.size >= 2 && initialTokens.last() in trailingGenreCues) {
            cuePresent = true
            initialTokens.dropLast(1)
        } else {
            initialTokens
        }
        if (tokens.isEmpty()) return null

        val candidate = tokens.joinToString(" ")

        // 1. Exact alias or exact lexicon match always wins.
        genreMoodAliases[candidate]?.let { return it }
        if (candidate in multiWordLexicon || candidate in singleWordLexicon) {
            return candidate
        }

        // 2. Multi-word fuzzy match (same token count).
        if (tokens.size >= 2) {
            var bestEntry: String? = null
            var bestScore = Int.MAX_VALUE
            for (entry in multiWordLexicon) {
                val lexTokens = entry.split(' ')
                if (lexTokens.size != tokens.size) continue
                var totalDist = 0
                var allMatch = true
                for (i in tokens.indices) {
                    val inTok = tokens[i]
                    val lexTok = lexTokens[i]
                    if (inTok == lexTok) continue

                    val dist = damerauLevenshtein(inTok, lexTok)
                    val inPh = phoneticKey(inTok)
                    val lexPh = phoneticKey(lexTok)
                    val phoneticMatch = inPh.isNotEmpty() &&
                        inPh.firstOrNull() == lexPh.firstOrNull() &&
                        inPh == lexPh &&
                        dist <= 2 &&
                        abs(inTok.length - lexTok.length) <= 2
                    val tightEditMatch = lexTok.length >= 4 &&
                        dist <= 1 &&
                        inTok.firstOrNull() == lexTok.firstOrNull()

                    if (!phoneticMatch && !tightEditMatch) {
                        allMatch = false
                        break
                    }
                    totalDist += dist
                }
                if (allMatch && totalDist < bestScore) {
                    bestScore = totalDist
                    bestEntry = entry
                }
            }
            if (bestEntry != null) return bestEntry

            // 2b. Compound mood + genre combination where every token matches a known genre/mood
            // (e.g., "relaxing jazz", "chill pop", "study classical", "workout hip hop").
            val compoundParts = mutableListOf<String>()
            var idx = 0
            var compoundOk = true
            while (idx < tokens.size) {
                var matchedPart: String? = null
                if (idx + 1 < tokens.size) {
                    val pair = "${tokens[idx]} ${tokens[idx + 1]}"
                    val alias = genreMoodAliases[pair]
                    if (alias != null) {
                        matchedPart = alias
                        idx += 2
                    } else if (pair in multiWordLexicon) {
                        matchedPart = pair
                        idx += 2
                    }
                }
                if (matchedPart == null) {
                    val tok = tokens[idx]
                    val single = genreMoodAliases[tok] ?: tok.takeIf { it in singleWordLexicon }
                    if (single != null) {
                        matchedPart = single
                        idx += 1
                    } else {
                        compoundOk = false
                        break
                    }
                }
                compoundParts += matchedPart
            }
            if (compoundOk && compoundParts.size >= 2) {
                return compoundParts.joinToString(" ")
            }
            return null
        }

        // 3. Single-word fuzzy match with strict short-word false-positive guards.
        val inTok = tokens[0]
        val inPh = phoneticKey(inTok)
        if (inPh.isEmpty()) return null

        var bestSingle: String? = null
        var bestDist = Int.MAX_VALUE
        for (lexTok in singleWordLexicon) {
            if (lexTok.contains('-') || lexTok.contains('&')) continue
            if (cuePresent) {
                if (lexTok.length < 3) continue
            } else {
                // Without an explicit genre/mood cue, never fuzzy-match short words (<= 5 chars),
                // protecting artists/names like "Pink", "Josh", "Paul", "Beck".
                if (lexTok.length < 6 || inTok.length < 5) continue
            }
            val lexPh = phoneticKey(lexTok)
            if (inPh.firstOrNull() != lexPh.firstOrNull() || inPh != lexPh) continue
            val dist = damerauLevenshtein(inTok, lexTok)
            if (dist <= 2 && dist < bestDist) {
                bestDist = dist
                bestSingle = lexTok
            }
        }
        return bestSingle
    }

    internal fun phoneticKey(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""

        val transformed = buildString {
            var i = 0
            while (i < w.length) {
                when {
                    i == 0 && w.startsWith("kn") -> {
                        append('n'); i += 2
                    }
                    i == 0 && w.startsWith("wr") -> {
                        append('r'); i += 2
                    }
                    i == 0 && w.startsWith("ps") -> {
                        append('s'); i += 2
                    }
                    i == 0 && w.startsWith("wh") -> {
                        append('w'); i += 2
                    }
                    w.startsWith("ph", i) -> {
                        append('f'); i += 2
                    }
                    w.startsWith("ck", i) -> {
                        append('k'); i += 2
                    }
                    w.startsWith("qu", i) -> {
                        append("kw"); i += 2
                    }
                    w.startsWith("igh", i) -> {
                        append('i'); i += 3
                    }
                    w[i] == 'x' -> {
                        append("ks"); i++
                    }
                    w[i] == 'c' -> {
                        val next = w.getOrNull(i + 1)
                        append(if (next != null && next in "eiy") 's' else 'k')
                        i++
                    }
                    w[i] == 'z' -> {
                        append('s'); i++
                    }
                    else -> {
                        append(w[i]); i++
                    }
                }
            }
        }
        if (transformed.isEmpty()) return ""

        // Collapse consecutive duplicates first.
        val collapsed = buildString {
            for (ch in transformed) {
                if (isEmpty() || last() != ch) append(ch)
            }
        }
        if (collapsed.isEmpty()) return ""

        // Keep initial character, drop vowels after index 0, collapse consecutive duplicate consonants.
        return buildString {
            append(collapsed[0])
            for (i in 1 until collapsed.length) {
                val ch = collapsed[i]
                if (ch !in "aeiouy" && last() != ch) {
                    append(ch)
                }
            }
        }
    }

    internal fun damerauLevenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, dp[i - 2][j - 2] + cost)
                }
                dp[i][j] = best
            }
        }
        return dp[m][n]
    }
}
