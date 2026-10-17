package app.hermesconduit.android.core.live

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.SpokenTextFilter
import java.net.URI

/**
 * A reply the user asked to hear again or in full (#451), as the live model
 * reads it: Markdown flattened into plain speech, so nothing is skipped or
 * read out as symbols. Headings, list items and table rows become
 * sentences, links keep their words, a bare link becomes its site, and code
 * stays in the chat.
 */
object VoiceReadBack {
    /** Said in place of a code block, in the app's language. */
    val codeBlockNote: String get() = L10n.tr("(There's code here; it's in the chat.)")

    fun plainSpeech(markdown: String): String {
        val note = codeBlockNote
        val lines = mutableListOf<String>()
        var fence: Pair<Char, Int>? = null
        for (rawLine in markdown.replace("\r\n", "\n").split("\n")) {
            val line = rawLine.trim(' ', '\t')
            val open = fence
            if (open != null) {
                // Closed only by the same mark, at least as long, alone on its line.
                val run = line.takeWhile { it == open.first }.length
                if (run >= open.second && line.drop(run).all { it.isWhitespace() }) fence = null
                continue
            }
            val mark = line.firstOrNull()
            if (mark == '`' || mark == '~') {
                val run = line.takeWhile { it == mark }.length
                // "```x```" is inline code: a fence's info has no backticks.
                if (run >= 3 && !(mark == '`' && line.drop(run).contains('`'))) {
                    fence = mark to run
                    if (lines.lastOrNull { it.isNotEmpty() } != note) lines += note
                    continue
                }
            }
            spokenLine(line)?.let { lines += it }
        }
        var text = SpokenTextFilter.speakable(lines.joinToString("\n"))
        text = Regex("[ \\t]+([.,!?;:])(?=\\s|$)").replace(text, "$1")
        text = Regex("(?m)^[ \\t]+|[ \\t]+$").replace(text, "")
        text = Regex("[ \\t]{2,}").replace(text, " ")
        text = Regex("\\n{3,}").replace(text, "\n\n")
        return text.trim()
    }

    private val rule = Regex("^([-*_])(\\s*\\1){2,}$")
    private val underline = Regex("^=+$")
    private val tableDivider = Regex("^[|:\\-\\s]+$")
    private val blockLead = Regex("^(#{1,6}|[-*+>]|[0-9]{1,3}[.)])\\s")
    private val quote = Regex("^(>\\s*)+")
    private val heading = Regex("^#{1,6}\\s+")
    private val headingTail = Regex("\\s+#+$")
    private val bullet = Regex("^[-*+]\\s+(\\[[ xX]\\]\\s+)?")
    private val numbered = Regex("^[0-9]{1,3}[.)]\\s+")

    /** One line without its block markup. Null drops the line (a rule, an underline, a table divider). */
    private fun spokenLine(line: String): String? {
        if (line.isEmpty()) return ""
        if (rule.containsMatchIn(line)) return null
        if (underline.containsMatchIn(line)) return null
        if (line.contains("|") && line.contains("-") && tableDivider.containsMatchIn(line)) return null
        if (line.startsWith("|") || (line.contains(" | ") && !blockLead.containsMatchIn(line))) {
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            return sentence(inline(cells.joinToString(", ")))
        }
        var text = quote.replaceFirst(line, "")
        heading.find(text)?.let {
            text = text.removeRange(it.range)
            text = headingTail.replace(text, "")
            return sentence(inline(text))
        }
        bullet.find(text)?.let {
            text = text.removeRange(it.range)
            return sentence(inline(text))
        }
        // The number stays: steps are read in order.
        if (numbered.containsMatchIn(text)) return sentence(inline(text))
        return inline(text)
    }

    private val inlineRules = listOf(
        Regex("!\\[([^\\]]*)\\]\\((?:[^()]|\\([^()]*\\))*\\)", RegexOption.IGNORE_CASE) to "$1",
        Regex("\\[([^\\]]+)\\]\\((?:[^()]|\\([^()]*\\))*\\)", RegexOption.IGNORE_CASE) to "$1",
        Regex("<(https?://[^>\\s]+)>", RegexOption.IGNORE_CASE) to "$1",
        Regex("<(?:mailto:)?([^\\s<>@]+@[^\\s<>]+)>", RegexOption.IGNORE_CASE) to "$1",
        Regex("<br\\s*/?>", RegexOption.IGNORE_CASE) to " ",
        Regex(
            "</?(?:a|abbr|b|blockquote|center|code|del|details|div|em|font|h[1-6]|hr|i|img|ins|kbd|li|mark|ol|p|pre|s|small|span|strong|sub|summary|sup|table|tbody|td|th|thead|tr|u|ul)(?:\\s(?:[^<>\"']|\"[^\"]*\"|'[^']*')*)?/?>",
            RegexOption.IGNORE_CASE,
        ) to "",
        Regex("\\[\\^[^\\]\\s]+\\]:?", RegexOption.IGNORE_CASE) to "",
        Regex("\\s+\\|\\s+", RegexOption.IGNORE_CASE) to ", ",
    )
    private val marks = listOf(
        Regex("(?<!`)(`+)(?!`)(.+?)(?<!`)\\1(?!`)") to "$2",
        Regex("\\*\\*(.+?)\\*\\*") to "$1",
        Regex("__(.+?)__") to "$1",
        Regex("~~(.+?)~~") to "$1",
        Regex("(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])") to "$1",
        Regex("(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])") to "$1",
    )
    private val bareLink = Regex("https?://(?:[^\\s<>()\\[\\]]|\\([^\\s<>()\\[\\]]*\\))*(?:[^\\s<>()\\[\\].,;:!?'\"]|\\([^\\s<>()\\[\\]]*\\))")

    private fun inline(input: String): String {
        var text = input
        for ((pattern, template) in inlineRules) text = pattern.replace(text, template)
        text = bareLink.replace(text) { match ->
            var site = runCatching { URI(match.value).host }.getOrNull().orEmpty()
            if (site.startsWith("www.")) site = site.drop(4)
            site.ifEmpty { L10n.tr("a link") }
        }
        for ((pattern, template) in marks) text = pattern.replace(text, template)
        return text
    }

    /** Ends `text` like a sentence, so it is read as one. */
    private fun sentence(text: String): String {
        val trimmed = text.trim(' ', '\t')
        val last = trimmed.lastOrNull() ?: return trimmed
        return if (last in ".!?:;,…。！？") trimmed else "$trimmed."
    }
}
