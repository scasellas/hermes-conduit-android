package app.hermesconduit.android.model

/**
 * Splits an assistant reply into what the transcript draws differently:
 * Markdown text, Mermaid diagrams, display math, Hermes `MEDIA:<path>`
 * delivery tags and `::preview{file="…"}` directives. Ports the iOS
 * GatewayMediaTags / TranscriptDirectives / markdown block rules.
 */
sealed interface ContentSegment {
    data class Markdown(val text: String) : ContentSegment
    data class Mermaid(val source: String, val complete: Boolean) : ContentSegment
    data class Math(val source: String, val complete: Boolean) : ContentSegment
    data class Media(val path: String, val alt: String? = null) : ContentSegment
    data class Preview(val file: String) : ContentSegment
}

object ContentSegmenter {
    val knownExtensions = listOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif", "svg",
        "mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp",
        "mp3", "m2a", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "caf", "aiff", "aif",
        "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
        "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
        "kmz", "kml", "geojson", "gpx",
        "pptx", "ppt", "odp", "key", "pages", "numbers",
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa",
        "html", "htm",
    )

    /** Message-wide Hermes directives; they steer delivery elsewhere and are never shown. */
    val directives = listOf("[[audio_as_voice]]", "[[as_document]]")

    private const val CJK = "（）〈〉《》：，。；！？、\u201C\u201D\u2018\u2019【】"

    private val tagRegex: Regex = run {
        val alternation = knownExtensions.sortedByDescending { it.length }.joinToString("|")
        Regex(
            "[`\"'*_]{0,3}MEDIA:[^\\S\\n]*" +
                "(`[^`\\n]+?`|\"[^\"\\n]+?\"|'[^'\\n]+?'|" +
                "(?:~/|/|[A-Za-z]:[/\\\\])\\S+?(?:[^\\S\\n]+\\S+?)*?\\.(?:$alternation))" +
                "(?=[\\s`\"'*_,;:)\\]}\\[$CJK]|MEDIA:|\\.(?:\\s|$)|$)[`\"'*_]{0,3}\\.?",
            RegexOption.IGNORE_CASE,
        )
    }
    private val wholeLineRegex = Regex("^[`\"'*_]{0,3}MEDIA:[^\\S\\n]*((?:~/|/|[A-Za-z]:[/\\\\]).*?)[`\"'*_]{0,3}$", RegexOption.IGNORE_CASE)
    private val markdownImageRegex = Regex("^!?\\[([^\\]]*)\\]\\([^\\S\\n]*MEDIA:[^\\S\\n]*(.+?)(?:[^\\S\\n]+(?:\"[^\"\\n]*\"|'[^'\\n]*'))?[^\\S\\n]*\\)$", RegexOption.IGNORE_CASE)
    private val previewRegex = Regex("(?:^|(?<=\\s))::preview\\{([^{}\\n]{0,1024})\\}")
    private val attributeRegex = Regex("([A-Za-z][\\w-]{0,63})=(?:\"([^\"]*)\"|'([^']*)')")
    private val leftover = setOf('-', '+', '*', '_', '~', '`', '\'', '"', '(', ')', '[', ']', '{', '}', '.', ',', ';', ':', '!', '?', '>', '#')

    fun isHtml(path: String) = Regex("\\.(?:html?|xhtml)$", RegexOption.IGNORE_CASE).containsMatchIn(path)

    fun segments(text: String, streaming: Boolean = false): List<ContentSegment> {
        val out = mutableListOf<ContentSegment>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotBlank()) out += ContentSegment.Markdown(buffer.toString().trimEnd('\n'))
            buffer.clear()
        }
        val lines = text.split('\n')
        var i = 0
        var fence: String? = null
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (fence != null) {
                buffer.append(line).append('\n')
                if (trimmed.startsWith(fence) && trimmed.trimStart('`', '~').isEmpty()) fence = null
                i++
                continue
            }
            val fenceMatch = Regex("^(```+|~~~+)\\s*([\\w+-]*)").find(trimmed)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                val language = fenceMatch.groupValues[2].lowercase()
                if (language == "mermaid" || language == "math" || language == "latex" || language == "katex") {
                    val body = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        val t = lines[j].trim()
                        if (t.startsWith(marker.take(3)) && t.trimStart('`', '~').isEmpty()) { closed = true; break }
                        body.append(lines[j]).append('\n')
                        j++
                    }
                    flush()
                    val source = body.toString().trimEnd()
                    out += if (language == "mermaid") ContentSegment.Mermaid(source, closed) else ContentSegment.Math(source, closed)
                    i = if (closed) j + 1 else lines.size
                    continue
                }
                fence = marker.take(3)
                buffer.append(line).append('\n')
                i++
                continue
            }
            // Display math: $$ … $$ or \[ … \]
            if (trimmed.length > 4 && trimmed.startsWith("$$") && trimmed.endsWith("$$")) {
                flush(); out += ContentSegment.Math(trimmed.removePrefix("$$").removeSuffix("$$").trim(), true); i++; continue
            }
            if (trimmed == "$$" || trimmed == "\\[") {
                val closer = if (trimmed == "$$") "$$" else "\\]"
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim() == closer) { closed = true; break }
                    body.append(lines[j]).append('\n')
                    j++
                }
                flush()
                out += ContentSegment.Math(body.toString().trim(), closed)
                i = if (closed) j + 1 else lines.size
                continue
            }
            val previews = previewSegments(line)
            val media = if (previews == null) mediaSegments(line) else null
            when {
                previews != null -> { flush(); out += previews }
                media != null -> { flush(); out += media }
                else -> buffer.append(line).append('\n')
            }
            i++
        }
        flush()
        return out
    }

    private fun previewSegments(line: String): List<ContentSegment>? {
        if ("::preview{" !in line) return null
        val result = mutableListOf<ContentSegment>()
        var cursor = 0
        for (match in previewRegex.findAll(line)) {
            val attributes = attributeRegex.findAll(match.groupValues[1]).associate { m ->
                m.groupValues[1] to (m.groups[2]?.value ?: m.groups[3]?.value ?: "")
            }
            val file = attributes["file"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            appendText(line.substring(cursor, match.range.first), result)
            result += ContentSegment.Preview(file)
            cursor = match.range.last + 1
        }
        if (cursor == 0) return null
        appendText(line.substring(cursor), result)
        return result
    }

    /** The line split into text and media, null when it holds no tag; empty when only directives. */
    fun mediaSegments(line: String): List<ContentSegment>? {
        if (!line.contains("MEDIA:", ignoreCase = true) && directives.none { it in line }) return null
        var source = line
        var removedDirective = false
        for (d in directives) if (d in source) { source = source.replace(d, ""); removedDirective = true }
        val trimmed = source.trim()
        if (trimmed.startsWith(">")) {
            if (!removedDirective) return null
            return if (trimmed.trimStart('>', ' ').isEmpty()) emptyList() else listOf(ContentSegment.Markdown(trimmed))
        }
        markdownImageRegex.find(trimmed)?.let { m ->
            val path = normalizedPath(m.groupValues[2])
            if (path != null) return listOf(ContentSegment.Media(path, m.groupValues[1].trim().ifEmpty { null }))
        }
        val codeSpans = inlineCodeSpans(trimmed)
        val result = mutableListOf<ContentSegment>()
        var cursor = 0
        for (m in tagRegex.findAll(trimmed)) {
            if (m.range.first < cursor) continue
            val keyword = trimmed.indexOf("MEDIA:", m.range.first, ignoreCase = true)
            if (keyword >= 0 && codeSpans.any { keyword > it.first && keyword < it.last }) continue
            val path = normalizedPath(m.groupValues[1]) ?: continue
            appendText(trimmed.substring(cursor, m.range.first), result)
            result += ContentSegment.Media(path)
            cursor = m.range.last + 1
        }
        if (result.any { it is ContentSegment.Media }) {
            appendText(trimmed.substring(cursor), result)
            return result
        }
        wholeLineRegex.find(trimmed)?.let { m ->
            val raw = m.groupValues[1]
            if (!raw.contains("MEDIA:", ignoreCase = true) && codeSpans.none { it.first == 0 }) {
                val path = normalizedPath(raw)
                if (path != null) {
                    val name = path.split('/', '\\').last()
                    if (!path.any { it.isWhitespace() } || Regex("\\.[A-Za-z0-9]{1,10}$").containsMatchIn(name)) {
                        return listOf(ContentSegment.Media(path))
                    }
                }
            }
        }
        if (removedDirective) return if (trimmed.isEmpty()) emptyList() else listOf(ContentSegment.Markdown(trimmed))
        return null
    }

    /** `text` with tags and directives removed (for read-aloud). */
    fun removingTags(text: String): String = text.split('\n').mapNotNull { line ->
        val segments = mediaSegments(line) ?: return@mapNotNull line
        segments.mapNotNull { (it as? ContentSegment.Markdown)?.text ?: (it as? ContentSegment.Media)?.alt }
            .takeIf { it.isNotEmpty() }?.joinToString(" ")
    }.joinToString("\n")

    private fun normalizedPath(raw: String): String? {
        var path = raw.trim()
        if (path.length >= 2 && path.first() == path.last() && path.first() in "`\"'") path = path.substring(1, path.length - 1).trim()
        path = path.trimStart('`', '"', '\'').trimEnd('`', '"', '\'', ',', '.', ';', ':', ')', '}', ']')
        val name = path.split('/', '\\').lastOrNull().orEmpty()
        if (name.isEmpty() || name == "~" || (name.length == 2 && name.endsWith(":"))) return null
        return path
    }

    private fun appendText(raw: String, into: MutableList<ContentSegment>) {
        val text = raw.trim()
        if (text.any { !it.isWhitespace() && it !in leftover }) into += ContentSegment.Markdown(text)
    }

    private fun inlineCodeSpans(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var open: Int? = null
        line.forEachIndexed { index, c ->
            if (c == '`') {
                val start = open
                if (start != null) { spans += start..index; open = null } else open = index
            }
        }
        return spans
    }
}
