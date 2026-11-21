package com.maxrave.simpmusic.jerry

object JerryGrammar {
    private val unkPattern = Regex("\\[unk\\]", RegexOption.IGNORE_CASE)
    private val whitespacePattern = Regex("\\s+")
    private val wakePrefixPattern = Regex(
        "^(?:(?:(?:the|a|an|um|uh|oh|well|and|okay|ok|please)[\\s,]+){0,3}" +
            "(?:hey|hay|hi|hello|yo|okay|ok|um|uh|a|i'm|im|i|m|home|how|ho|j|jay|he|eh|they|o|k|kay|ay|aye|ey)[\\s,]+" +
            "(?:jarvis|javis|jarvus|jervis|harvis|jave|jarv|chavez|jar\\s+vis|jar\\s+this|job\\s+is|jobs|job's|java|travis|service)" +
            "|(?:jarvis|javis|jarvus|jervis|harvis))\\b[\\s,.:;!?-]*",
        RegexOption.IGNORE_CASE,
    )
    private val leadingPolitePattern = Regex(
        "^(?:(?:please|can\\s+you|could\\s+you|would\\s+you|will\\s+you|i\\s+want\\s+you\\s+to|i\\s+need\\s+you\\s+to|go\\s+ahead\\s+and|just|right\\s+now|now|um|uh|well)\\b[\\s,.:;!?-]*)+",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPolitePattern = Regex(
        "(?:[\\s,.:;!?-]+\\b(?:hey[\\s,]+jarvis|jarvis|please|for\\s+me|right\\s+now|now))+$",
        RegexOption.IGNORE_CASE,
    )

    val wakePhrases: List<String> = listOf(
        "hey jarvis", "hay jarvis", "how jarvis", "home jarvis", "hi jarvis", "hello jarvis",
        "ok jarvis", "okay jarvis", "yo jarvis", "jarvis", "jervis",
        "hey javis", "hey jarvus", "hey jervis", "hey harvis", "hey service",
    )

    val commandPhrases: List<String> = listOf(
        // Next / Previous
        "next", "skip", "skip song", "next song", "next track", "play next", "play next song",
        "play the next song", "skip this", "skip this song", "skip it", "skip track", "skip this track",
        "skip the song", "next one", "next up", "forward", "skip forward", "go forward",
        "another song", "play another", "play another song", "change song", "change the song",
        "switch song", "switch the song", "different song", "skip one",
        "previous", "back", "go back", "previous song", "previous track", "play previous",
        "play previous song", "play the previous song", "last song", "last track", "last one", "previous one",
        "back one", "go back a song", "back a song", "go back one", "before", "after", "another", "different",
        "change", "switch", "song before", "song before it", "song before this", "the song before",
        "the song before it", "the song before this", "play the song before", "play the song before it",
        "play the song before this", "track before", "track before it", "track before this",
        // Resume / Pause / Stop / Restart
        "play", "resume", "play music", "play the music", "resume music", "play some music", "start music",
        "start the music", "start playing", "resume playback", "resume the music", "resume playing", "unpause",
        "continue", "continue playing", "continue music", "keep playing",
        "pause", "pause music", "pause the music", "pause it", "pause playback", "pause song",
        "pause the song", "pause this", "pause this song", "pause playing", "hold on",
        "stop", "stop music", "stop the music", "stop it", "stop playing", "stop playback",
        "stop song", "stop the song", "stop this", "stop this song", "quiet", "be quiet", "shut up",
        "restart", "restart song", "restart the song", "restart track", "restart the track",
        "start over", "from the beginning", "replay song",
        // Seek / Shuffle / Repeat / Like / Dislike / Sleep Timer
        "fast forward", "rewind", "seek forward", "seek backward", "seek back", "seek to",
        "seconds", "minutes", "fifteen", "thirty", "forty five",
        "shuffle", "shuffle on", "shuffle off", "turn shuffle on", "turn shuffle off",
        "repeat", "repeat off", "repeat one", "repeat all", "repeat song", "loop",
        "like", "like this song", "unlike", "dislike", "dislike this song", "thumbs up", "thumbs down",
        "sleep timer", "set sleep timer", "set sleep timer for", "cancel sleep timer",
        "stop sleep timer", "sleep timer off", "hour",
        // Volume / Mute / Unmute
        "mute", "unmute", "mute volume", "unmute volume", "mute audio", "unmute audio",
        "sound on", "turn sound on",
        "volume", "set", "level", "to", "percent", "set volume", "set the volume", "volume to",
        "set volume to", "set the volume to", "volume level",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "twenty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
        "a hundred", "one hundred", "all", "way", "all the way up", "all the way down",
        "volume all the way up", "volume all the way down", "turn volume all the way up", "turn volume all the way down",
        "max", "maximum", "max volume", "maximum volume", "full volume",
        "min", "minimum", "min volume", "minimum volume", "lowest volume",
        "medium", "medium volume", "half", "half volume", "middle volume",
        "volume up", "turn volume up", "turn the volume up", "louder", "turn it up", "turn up",
        "turn up volume", "turn up the volume", "make it louder", "higher", "increase volume",
        "increase the volume", "raise volume", "raise the volume",
        "volume down", "turn volume down", "turn the volume down", "quieter", "turn it down", "turn down",
        "turn down volume", "turn down the volume", "make it quieter", "make it quiet", "lower",
        "lower volume", "lower the volume", "softer", "make it softer", "decrease volume", "decrease the volume",
        // Queue
        "add to queue", "add to the queue", "queue", "queue up",
        "clear queue", "clear the queue", "empty queue",
        "show queue", "show the queue", "open queue", "what is up next",
        // Mixes / Library / Media Cues / Genres & Moods
        "mix", "mixes", "my mix", "my mixes", "remix", "remixes", "my remixes", "all remixes", "all of my remixes",
        "play remixes", "play my remixes", "discover", "discovery", "discover mix", "discovery mix",
        "play mix", "play mixes", "play a mix", "play some mixes", "play random mix",
        "play discover mix", "play discovery mix",
        "replay", "replay mix", "supermix", "my supermix", "release", "new release mix", "archive", "archive mix",
        "party", "party mix", "chill", "chill mix", "romantic", "romantic mix",
        "focus", "focus mix", "workout", "workout mix", "energy", "energy mix", "commute", "commute mix",
        "liked", "liked music", "favorite", "favorites", "downloaded", "most", "played", "most played",
        "artist", "songs by", "music by", "music from", "play artist", "play songs by", "play music by",
        "album", "play album", "playlist", "play playlist", "song", "play song", "track",
        "genre", "mood", "play genre", "play mood", "radio", "play radio", "start radio", "station",
        "put on", "throw on", "spin up", "listen to", "let's listen to", "i want to hear", "stream",
        "jazz", "smooth jazz", "rock", "classic rock", "indie", "pop", "metal", "hip hop",
        "r and b", "low fi", "e d m", "electronic", "ambient", "blues", "soul", "funk",
        "country", "folk", "classical", "acoustic", "k pop",
        "sleep", "sleeping", "study", "sad", "happy", "relax", "relaxing", "calm", "meditation", "driving", "gaming",
        // Navigation
        "home", "open home", "go home",
        "search", "open search", "go to search",
        "library", "open library", "go to library", "my library",
        "settings", "open settings", "go to settings",
        "mix for you", "open mix for you",
        "analytics", "open analytics",
        "now playing", "open now playing", "show now playing",
        "fullscreen", "full screen", "open fullscreen",
        "navigate back", "previous screen", "close screen", "close player", "exit fullscreen",
        // Multi-word fillers & polite tokens
        "by", "from", "the", "some", "a", "for", "of", "in", "on", "with",
        "please", "can you", "could you", "would you", "for me", "right now", "now",
    )

    val stage1Phrases: List<String> = (wakePhrases + commandPhrases).distinct() + listOf("[unk]")

    val stage1GrammarJson: String = stage1Phrases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    private val incompleteTails: Set<String> = setOf(
        "play",
        "play music",
        "play the music",
        "play some",
        "play the",
        "play a",
        "play song",
        "play artist",
        "play album",
        "play playlist",
        "play genre",
        "play mood",
        "play radio",
        "play next",
        "play songs by",
        "play music by",
        "play music from",
        "put on",
        "throw on",
        "spin up",
        "listen to",
        "let's listen to",
        "i want to hear",
        "stream",
        "start playing",
        "shuffle",
        "add to queue",
        "add to the queue",
        "queue",
        "queue up",
        "seek to",
        "set volume",
        "set volume to",
        "set sleep timer",
        "set sleep timer for",
        "open",
        "go to",
        "start",
    )

    fun hasIncompleteCommandTail(cleanBody: String): Boolean {
        var stripped = cleanBody
            .replace(unkPattern, " ")
            .replace(whitespacePattern, " ")
            .trim()
        if (stripped.isEmpty()) return false

        while (true) {
            val m = wakePrefixPattern.find(stripped) ?: break
            stripped = stripped.substring(m.range.last + 1).trim()
        }
        stripped = stripped
            .replace(leadingPolitePattern, "")
            .replace(trailingPolitePattern, "")
            .trim()
            .lowercase()
        if (stripped.isEmpty()) return false
        return stripped in incompleteTails
    }
}
