package com.maxrave.simpmusic.jerry

class JerryWakeSession {
    private var deadline: Long? = null
    val awaitingCommand: Boolean get() = deadline != null

    private val unkPattern = Regex("\\[unk\\]", RegexOption.IGNORE_CASE)
    private val whitespacePattern = Regex("\\s+")

    /**
     * The offline model hears the wake word as “hi jarvis”, “how jarvis”, “i'm jarvis”, “hey job is”,
     * or a bare “jarvis” far more often than exact “hey jarvis”, and sometimes prepends short
     * noise/filler tokens (“the hey jarvis”). The wake phrase still has to open the utterance (with
     * consecutive repeated wake phrases allowed via `\G`): “they said hey jarvis …” stays ignored,
     * “the jarvis …” stays ignored because “the” is only a filler (not a greeting), and
     * “jarvisson” is not a match because of the word boundary.
     */
    private val wake =
        Regex(
            "(?:^|\\G[\\s,]+)(?:" +
                "(?:(?:the|a|an|um|uh|oh|well|and|okay|ok|please)[\\s,]+){0,3}" +
                "(?:hey|hay|hi|hello|yo|okay|ok|um|uh|a|i'm|im|i|m|home|how|ho|j|jay|he|eh|they|o|k|kay|ay|aye|ey)[\\s,]+" +
                "(?:jarvis|javis|jarvus|jervis|harvis|jave|jarv|chavez|jar\\s+vis|jar\\s+this|job\\s+is|jobs|job's|java|travis|service)" +
                "|(?:jarvis|javis|jarvus|jervis|harvis)" +
                ")\\b",
            RegexOption.IGNORE_CASE,
        )

    private fun normalize(text: String): String =
        text.replace(unkPattern, " ").replace(whitespacePattern, " ").trim()

    private fun trimBody(text: String): String =
        text.trimStart { it.isWhitespace() || it in ",.!?:;-" }.trim()

    private fun bodyAfterLastWake(clean: String): String? {
        val lastMatch = wake.findAll(clean).lastOrNull() ?: return null
        return trimBody(clean.substring(lastMatch.range.last + 1))
    }

    /** Returns true when [text] opens with a valid wake phrase (used for instant music ducking). */
    fun hasWakeWord(text: String): Boolean {
        val clean = normalize(text)
        return clean.isNotEmpty() && wake.containsMatchIn(clean)
    }

    fun observePartial(text: String, nowMs: Long): Boolean {
        expire(nowMs)
        if (deadline == null && hasWakeWord(text)) {
            deadline = nowMs + 8000
            return true
        }
        return false
    }

    /** Fires immediately on partial speech for unambiguous transport commands (no song-title tail to wait for). */
    fun acceptInstantPartial(text: String, nowMs: Long): JerryCommand? {
        expire(nowMs)
        val clean = normalize(text)
        if (clean.isEmpty()) return null
        val afterWake = bodyAfterLastWake(clean)
        val body = when {
            afterWake != null -> afterWake
            deadline != null -> trimBody(clean)
            else -> return null
        }
        if (body.isEmpty()) return null
        val cmd = JerryCommandParser.parse(body)
        return when (cmd) {
            is JerryCommand.Pause,
            is JerryCommand.Stop,
            is JerryCommand.Next,
            is JerryCommand.Previous,
            is JerryCommand.Resume,
            is JerryCommand.VolumeUp,
            is JerryCommand.VolumeDown,
            -> {
                reset()
                cmd
            }
            else -> null
        }
    }

    fun acceptFinal(text: String, nowMs: Long): JerryCommand? {
        expire(nowMs)
        val clean = normalize(text)
        val afterWake = bodyAfterLastWake(clean)
        if (afterWake != null) {
            if (deadline == null) deadline = nowMs + 8000
            if (afterWake.isEmpty()) return null
            reset()
            return JerryCommandParser.parse(afterWake)
        }
        val body = trimBody(clean)
        if (deadline == null || body.isEmpty()) return null
        reset()
        return JerryCommandParser.parse(body)
    }

    fun expire(nowMs: Long): Boolean {
        if (deadline?.let { nowMs >= it } == true) {
            reset()
            return true
        }
        return false
    }

    fun reset() { deadline = null }
}
