package app.hermesconduit.wearwire

/**
 * The spoken commands a Watch call answers itself (port of the iOS
 * `VoiceSpokenCommands`): the stop and end phrases, plus the answers a call
 * takes to a job's approval when the user allows voice approvals. Matching
 * is deliberately conservative: the whole utterance must be the phrase,
 * give or take courtesies ("okay, goodbye") and punctuation.
 */
object VoiceCommands {
    val stopPhrases = listOf("stop", "stop talking", "be quiet", "停止", "别说了", "不要说了")
    val endPhrases = listOf("goodbye", "bye", "end conversation", "that's all", "再见", "拜拜", "结束对话", "就这样吧")
    val hangUpPhrases = listOf(
        "hang up", "end the call", "end this call", "end call",
        "end the conversation", "end this conversation", "close the call",
        "you can hang up", "go ahead and hang up",
    )

    /** Voice only ever approves once (WatchJobSettings.voiceApprovals). */
    val approvePhrases = listOf("approve", "approve once", "approve it", "yes approve", "allow", "allow once", "approved")
    val denyPhrases = listOf("deny", "deny it", "don't approve", "do not approve", "reject", "denied")

    private val leadingCourtesies = listOf(
        listOf("all", "right"), listOf("thank", "you"),
        listOf("ok"), listOf("okay"), listOf("alright"), listOf("thanks"), listOf("cool"), listOf("great"),
        listOf("well"), listOf("so"), listOf("please"), listOf("好的"), listOf("好"), listOf("谢谢"),
    )
    private val trailingCourtesies = listOf(
        listOf("for", "now"), listOf("thank", "you"),
        listOf("thanks"), listOf("now"), listOf("then"), listOf("please"), listOf("谢谢"),
    )

    /** Case folding, typographic apostrophes, outer whitespace and punctuation. */
    fun canonicalized(text: String): String =
        text.lowercase().replace('’', '\'').trim { it.isWhitespace() || isPunctuation(it) }

    fun matches(utterance: String, phrases: List<String>): Boolean {
        val normalized = canonicalized(utterance)
        if (normalized.isEmpty()) return false
        return phrases.any { canonicalized(it) == normalized }
    }

    /**
     * Like [matches], but inner spaces and punctuation don't count, up to
     * three phrases may follow each other ("bye bye") and courtesies around
     * them are allowed. Anything else means it isn't a command.
     */
    fun matchesSpokenCommand(utterance: String, phrases: List<String>): Boolean {
        if (matches(utterance, phrases)) return true
        val spoken = stripCourtesies(commandWords(utterance)).joinToString("")
        if (spoken.isEmpty()) return false
        val keys = phrases.map { stripCourtesies(commandWords(it)).joinToString("") }.filter { it.isNotEmpty() }.toSet()
        return isSequence(spoken, keys, 3)
    }

    fun asksToEndCall(utterance: String): Boolean = matchesSpokenCommand(utterance, endPhrases + hangUpPhrases)

    /** With the profile's own end phrases, as a live call gets them from the phone; none turns ending by voice off. */
    fun asksToEndCall(utterance: String, endPhrases: List<String>): Boolean =
        endPhrases.isNotEmpty() && matchesSpokenCommand(utterance, endPhrases + hangUpPhrases)

    private val endCallMarker = Regex("^\\s*end[ -]?(?:(?:the|this)\\s+)?call\\s*:", RegexOption.IGNORE_CASE)

    /**
     * "End call:" at the very start of a GPT-Live delegation: the model, having
     * said its goodbye, asks Conduit to close the call (GPT-Live has no
     * function tools, so this stands in for end_conversation).
     */
    fun isEndCallMarker(request: String): Boolean = endCallMarker.containsMatchIn(request)

    fun asksToStop(utterance: String): Boolean = matchesSpokenCommand(utterance, stopPhrases)

    /** [WearWire.APPROVE_ONCE], [WearWire.DENY], or null when the words aren't an answer. */
    fun approvalAnswer(utterance: String): String? = when {
        matchesSpokenCommand(utterance, denyPhrases) -> WearWire.DENY
        matchesSpokenCommand(utterance, approvePhrases) -> WearWire.APPROVE_ONCE
        else -> null
    }

    private fun isSequence(text: String, keys: Set<String>, limit: Int): Boolean {
        if (limit <= 0) return false
        return keys.any { key -> text.startsWith(key) && (text.length == key.length || isSequence(text.substring(key.length), keys, limit - 1)) }
    }

    private fun commandWords(text: String): List<String> =
        canonicalized(text).split(Regex("[\\s\\p{Punct}&&[^']]+|[\\p{IsPunctuation}&&[^']]+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

    private fun stripCourtesies(input: List<String>): List<String> {
        var words = input
        var changed = true
        while (changed) {
            changed = false
            for (c in leadingCourtesies) if (words.size > c.size && words.take(c.size) == c) { words = words.drop(c.size); changed = true }
            for (c in trailingCourtesies) if (words.size > c.size && words.takeLast(c.size) == c) { words = words.dropLast(c.size); changed = true }
        }
        return words
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}
