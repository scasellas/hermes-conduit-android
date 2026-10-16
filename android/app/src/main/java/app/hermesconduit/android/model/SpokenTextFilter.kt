package app.hermesconduit.android.model

/**
 * Port of the iOS `SpokenTextFilter`: drops what a persona writes but nobody
 * should hear (emoji and narrated actions in single `*` or `_`, such as
 * "*sets down the gavel*"). Hermes' `@session:<id>` chat links are said as the
 * chat's name. The chat transcript is never filtered.
 *
 * Streaming: reply deltas split anywhere, so an opened marker holds text back
 * until it closes, the line ends, or the hold grows past what an action
 * plausibly is (then it is released unchanged). Works on code points.
 */
class SpokenTextFilter {
    private var pending: MutableList<Int> = mutableListOf()
    private val sessionReferences = SessionReferenceSpeechFilter()
    /** The last code point released, so a marker at the start of the next delta knows whether it sits inside a word. */
    private var before: Int? = null

    /** A high surrogate a delta ended on, joined with the next delta (emoji split mid-pair). */
    private var danglingSurrogate = ""

    fun reset() {
        danglingSurrogate = ""
        pending = mutableListOf()
        before = null
        sessionReferences.reset()
    }

    /** Speakable text that can be released now. */
    fun feed(text: String): String {
        var joined = danglingSurrogate + text
        danglingSurrogate = ""
        if (joined.isNotEmpty() && joined.last().isHighSurrogate()) {
            danglingSurrogate = joined.takeLast(1)
            joined = joined.dropLast(1)
        }
        pending.addAll(sessionReferences.feed(joined).codePointList())
        return drain(final = false)
    }

    /** Everything still held, at the end of the reply. */
    fun finish(): String {
        pending.addAll((sessionReferences.feed(danglingSurrogate) + sessionReferences.finish()).codePointList())
        danglingSurrogate = ""
        val text = drain(final = true)
        reset()
        return text
    }

    private fun drain(final: Boolean): String {
        val output = mutableListOf<Int>()
        val remaining = pending
        var start = 0
        while (true) {
            val open = firstActionMarker(remaining, start, output.lastOrNull() ?: before) ?: break
            val marker = remaining[open]
            output.addAll(remaining.subList(start, open))
            val afterOpen = open + 1
            val close = closingMarker(marker, remaining, afterOpen, final)
            if (close != null) {
                if (isAction(remaining, afterOpen, close)) {
                    output.add(' '.code)
                } else {
                    output.addAll(remaining.subList(open, close + 1))
                }
                start = close + 1
                continue
            }
            val held = remaining.subList(open, remaining.size)
            if (final || held.any(::isNewline) || held.size > MAXIMUM_HELD_CHARACTERS) {
                // Never closed: not an action. Release the marker as text and keep scanning after it.
                output.add(marker)
                start = afterOpen
                continue
            }
            pending = held.toMutableList()
            return release(output)
        }
        val last = remaining.lastOrNull()
        if (!final && start < remaining.size && (last == '*'.code || last == '_'.code)) {
            // A trailing run of markers may grow into "**" or open an action with the next delta.
            var run = 0
            var i = remaining.size - 1
            while (i >= start && remaining[i] == last) { run++; i-- }
            val keep = minOf(run + 1, remaining.size - start)
            output.addAll(remaining.subList(start, remaining.size - keep))
            pending = remaining.subList(remaining.size - keep, remaining.size).toMutableList()
        } else {
            output.addAll(remaining.subList(start, remaining.size))
            pending = mutableListOf()
        }
        return release(output)
    }

    private fun release(output: List<Int>): String {
        output.lastOrNull()?.let { before = it }
        return speakable(output.toCodePointString())
    }

    companion object {
        /** An emphasised word or two ("*really*") is speech; an action is longer. */
        const val MINIMUM_ACTION_WORDS = 3
        const val MAXIMUM_HELD_CHARACTERS = 400

        fun filter(text: String): String {
            val filter = SpokenTextFilter()
            return filter.feed(text) + filter.finish()
        }

        /** A single `*` or `_` that can open an action: not part of a doubled run (bold), and `_` only at a word start. */
        private fun firstActionMarker(text: List<Int>, from: Int, before: Int?): Int? {
            var index = from
            while (index < text.size) {
                val c = text[index]
                if (c != '*'.code && c != '_'.code) {
                    index++
                    continue
                }
                val previous = if (index > from) text[index - 1] else before
                val doubled = (index + 1 < text.size && text[index + 1] == c) || previous == c
                if (doubled) {
                    while (index < text.size && text[index] == c) index++
                    continue
                }
                val wordStart = previous?.let { !isWordCharacter(it) } ?: true
                if (c == '*'.code || wordStart) return index
                index++
            }
            return null
        }

        private fun closingMarker(marker: Int, text: List<Int>, from: Int, final: Boolean): Int? {
            var index = from
            while (index < text.size) {
                val c = text[index]
                if (isNewline(c)) return null
                if (c != marker) {
                    index++
                    continue
                }
                val next = index + 1
                if (next < text.size && text[next] == marker) {
                    index = next + 1
                    continue
                }
                if (marker == '_'.code) {
                    // Whether "_" ends a word is only known once the next character arrives.
                    if (next >= text.size && !final) return null
                    if (next < text.size && isWordCharacter(text[next])) {
                        index = next
                        continue
                    }
                }
                return index
            }
            return null
        }

        private fun isAction(text: List<Int>, from: Int, to: Int): Boolean =
            text.subList(from, to).toCodePointString().split(Regex("\\s+")).count { it.isNotEmpty() } >= MINIMUM_ACTION_WORDS

        /** Removes emoji and collapses the double spaces they and dropped actions leave. */
        fun speakable(text: String): String {
            if (text.isEmpty()) return text
            val builder = StringBuilder()
            text.codePoints().forEach { if (!isEmoji(it)) builder.appendCodePoint(it) }
            var result = builder.toString()
            while (result.contains("  ")) result = result.replace("  ", " ")
            return result
        }

        fun isEmoji(cp: Int): Boolean {
            when (cp) {
                0x200D, 0xFE0E, 0xFE0F, 0x20E3 -> return true
            }
            if (cp in 0x1F3FB..0x1F3FF) return true // skin tones
            if (cp in 0xE0020..0xE007F) return true // tag sequences (flags)
            // Pictographs, emoticons, transport, flags (regional indicators) and the supplemental blocks.
            if (cp in 0x1F000..0x1FAFF) return true
            // Text-default pictographs (☀, ♥, ✔) read as labels too; digits, #, *, ©, ® and ™ are emoji only with a selector.
            if (cp < 0x2190) return false
            return BMP_EMOJI.any { cp in it }
        }

        private val BMP_EMOJI: List<IntRange> = listOf(
            0x2194..0x2199, 0x21A9..0x21AA, 0x231A..0x231B, 0x2328..0x2328, 0x23CF..0x23CF, 0x23E9..0x23F3,
            0x23F8..0x23FA, 0x24C2..0x24C2, 0x25AA..0x25AB, 0x25B6..0x25B6, 0x25C0..0x25C0, 0x25FB..0x25FE,
            0x2600..0x2604, 0x260E..0x260E, 0x2611..0x2611, 0x2614..0x2615, 0x2618..0x2618, 0x261D..0x261D,
            0x2620..0x2620, 0x2622..0x2623, 0x2626..0x2626, 0x262A..0x262A, 0x262E..0x262F, 0x2638..0x263A,
            0x2640..0x2640, 0x2642..0x2642, 0x2648..0x2653, 0x265F..0x2660, 0x2663..0x2663, 0x2665..0x2666,
            0x2668..0x2668, 0x267B..0x267B, 0x267E..0x267F, 0x2692..0x2697, 0x2699..0x2699, 0x269B..0x269C,
            0x26A0..0x26A1, 0x26A7..0x26A7, 0x26AA..0x26AB, 0x26B0..0x26B1, 0x26BD..0x26BE, 0x26C4..0x26C5,
            0x26C8..0x26C8, 0x26CE..0x26CF, 0x26D1..0x26D1, 0x26D3..0x26D4, 0x26E9..0x26EA, 0x26F0..0x26F5,
            0x26F7..0x26FA, 0x26FD..0x26FD, 0x2702..0x2702, 0x2705..0x2705, 0x2708..0x270D, 0x270F..0x270F,
            0x2712..0x2712, 0x2714..0x2714, 0x2716..0x2716, 0x271D..0x271D, 0x2721..0x2721, 0x2728..0x2728,
            0x2733..0x2734, 0x2744..0x2744, 0x2747..0x2747, 0x274C..0x274C, 0x274E..0x274E, 0x2753..0x2755,
            0x2757..0x2757, 0x2763..0x2764, 0x2795..0x2797, 0x27A1..0x27A1, 0x27B0..0x27B0, 0x27BF..0x27BF,
            0x2934..0x2935, 0x2B05..0x2B07, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55, 0x3030..0x3030,
            0x303D..0x303D, 0x3297..0x3297, 0x3299..0x3299,
        )

        internal fun isNewline(cp: Int) = cp == 0x0A || cp == 0x0D || cp == 0x0B || cp == 0x0C || cp == 0x85 || cp == 0x2028 || cp == 0x2029

        internal fun isWordCharacter(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }
}

/**
 * Hermes' own reference to a chat: `@session:<profile>/<id>` (or `@session:<id>`),
 * also as the target of `[label](@session:…)`. Spoken, it is the chat's name,
 * or nothing when Conduit doesn't list it.
 */
object HermesSessionReference {
    private const val PATTERN = "(?<![\\w/`@])@session:(?:([A-Za-z0-9][A-Za-z0-9_-]{0,63})/)?([A-Za-z0-9_](?:[A-Za-z0-9_.:-]{0,126}[A-Za-z0-9_])?)"
    private val bare = Regex("(?U)$PATTERN")
    private const val TARGET = "\\(\\s*@session:(?:([A-Za-z0-9][A-Za-z0-9_-]{0,63})/)?([A-Za-z0-9_][A-Za-z0-9_.:-]{0,127})\\s*\\)"
    private val markdownLink = Regex("\\[((?:\\\\.|[^\\]\\\\\\n])*)\\]$TARGET")

    /** The chats Conduit lists, by id, for spoken names. */
    @Volatile var titles: Map<String, String> = emptyMap()

    fun title(id: String): String? = titles[id]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() }

    /** `text` as it is said: each reference is the chat's name, or nothing. */
    fun plain(text: String): String {
        if (!text.contains("@session:")) return text
        return outsideInlineCode(text) { segment ->
            if (!segment.contains("@session:")) return@outsideInlineCode segment
            val labels = markdownLink.replace(segment) { it.groupValues[1] }
            var result = bare.replace(labels) { match -> title(match.groupValues[2]) ?: "" }
            while (result.contains("  ")) result = result.replace("  ", " ")
            for (mark in listOf(" .", " ,", " ;", " !", " ?")) result = result.replace(mark, mark.drop(1))
            result
        }
    }

    private fun outsideInlineCode(line: String, transform: (String) -> String): String {
        if (!line.contains('`')) return transform(line)
        val parts = line.split('`')
        val closed = if (parts.size % 2 == 1) parts.size else parts.size - 1
        return parts.mapIndexed { index, part -> if (index % 2 == 0 || index >= closed) transform(part) else part }.joinToString("`")
    }
}

/** Streaming: a reference split across reply deltas is held until it ends, then said as [HermesSessionReference.plain] says it. */
class SessionReferenceSpeechFilter {
    private var pending = ""

    fun reset() { pending = "" }

    fun feed(text: String): String {
        pending += text
        var hold = holdStart() ?: run {
            val ready = pending
            pending = ""
            return HermesSessionReference.plain(ready)
        }
        // The spaces before it too: a reference said as nothing drops them.
        while (hold > 0 && pending[hold - 1] == ' ') hold--
        val ready = pending.substring(0, hold)
        pending = pending.substring(hold)
        return HermesSessionReference.plain(ready)
    }

    private fun holdStart(): Int? {
        val at = pending.lastIndexOf('@')
        if (at >= 0) {
            val tail = pending.substring(at)
            val mayGrow = if (tail.length < MARKER.length) {
                MARKER.startsWith(tail)
            } else {
                tail.startsWith(MARKER) && tail.length <= LONGEST && tail.none { it.isWhitespace() || it == ')' }
            }
            if (mayGrow) {
                val before = pending.substring(0, at)
                val link = Regex("\\[[^\\]\\n]{0,300}\\]\\(\\s*$").find(before)
                return link?.range?.first ?: at
            }
        }
        val open = pending.lastIndexOf('[')
        if (open >= 0 && Regex("^\\[[^\\]\\n]{0,300}(\\](\\(\\s*)?)?$").containsMatchIn(pending.substring(open))) return open
        return null
    }

    fun finish(): String {
        val ready = pending
        pending = ""
        return HermesSessionReference.plain(ready)
    }

    private companion object {
        const val MARKER = "@session:"
        const val LONGEST = 210
    }
}

internal fun String.codePointList(): List<Int> = codePoints().toArray().toList()

internal fun List<Int>.toCodePointString(): String {
    val builder = StringBuilder(size)
    forEach { builder.appendCodePoint(it) }
    return builder.toString()
}
