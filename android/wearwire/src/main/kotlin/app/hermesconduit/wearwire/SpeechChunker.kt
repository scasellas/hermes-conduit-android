package app.hermesconduit.wearwire

/**
 * Turns a reply streaming in as cumulative text into sentences the watch can
 * speak as soon as each is whole, so Hermes starts talking before the turn
 * ends. Markdown and other things that read badly aloud are cleaned out.
 */
class SpeechChunker(private val minChunk: Int = 24) {
    private var consumed = 0
    private var latest = ""

    /** Feeds the reply so far; returns the newly complete sentences to speak. */
    fun update(cumulative: String): List<String> {
        if (cumulative.length < consumed || !cumulative.startsWith(latest.take(consumed))) {
            // The reply was rewritten (a final text that differs): speak only what's new past the old point.
            consumed = consumed.coerceAtMost(cumulative.length)
        }
        latest = cumulative
        val out = mutableListOf<String>()
        while (true) {
            val end = sentenceEnd(cumulative, consumed) ?: break
            val chunk = cumulative.substring(consumed, end)
            consumed = end
            SpeakableText.clean(chunk).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    /** The rest, once the reply is done. */
    fun finish(final: String? = null): List<String> {
        val text = final ?: latest
        if (text.length <= consumed) return emptyList()
        val rest = text.substring(consumed)
        consumed = text.length
        latest = text
        return listOfNotNull(SpeakableText.clean(rest).takeIf { it.isNotBlank() })
    }

    fun reset() {
        consumed = 0
        latest = ""
    }

    /** Index just past the next sentence boundary at or after [minChunk] chars from [from]. */
    private fun sentenceEnd(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            val boundary = when (c) {
                '\n' -> true
                '.', '!', '?', '。', '！', '？', ';' -> i + 1 < text.length && (text[i + 1].isWhitespace() || c in "。！？")
                else -> false
            }
            if (boundary && (i + 1 - from >= minChunk || c == '\n' && text.substring(from, i).isNotBlank())) return i + 1
            i++
        }
        return null
    }
}

/** Text as it should be read aloud: no markdown, code fences, links or tags. */
object SpeakableText {
    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val tag = Regex("</?[a-zA-Z][^>]*>")
    private val emphasis = Regex("[*_`~]+")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*([-*+]|\\d+\\.)\\s+")
    private val spaces = Regex("\\s+")

    fun clean(text: String): String = text
        .replace(fence, " ")
        .replace(tag, " ")
        .replace(link, "$1")
        .replace(url, " ")
        .replace(heading, "")
        .replace(bullet, "")
        .replace(emphasis, "")
        .replace(spaces, " ")
        .trim()
}

/**
 * The watch's view of a turn's reply: [ReplyEvent.Text] messages carry the
 * reply so far with a sequence number, so a late or repeated one is ignored.
 */
class ReplyAssembler {
    var text: String = ""
        private set
    private var seq = -1

    /** True when [event] moved the text forward. */
    fun apply(event: ReplyEvent.Text): Boolean {
        if (event.seq <= seq) return false
        seq = event.seq
        if (event.text == text) return false
        text = event.text
        return true
    }

    fun finish(final: String) {
        if (final.isNotEmpty()) text = final
        seq = Int.MAX_VALUE
    }
}
