package app.hermesconduit.wearwire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The call log's JSON lines (iOS WatchVoiceStats.jsonLine): timings and
 * connection events from Watch calls, never what was said, never a token.
 * Keys are sorted; "at" is added when missing.
 */
object CallLogLines {
    fun jsonLine(fields: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()): String {
        val all = fields.toMutableMap()
        if (all["at"] == null) all["at"] = timestamp(nowMs)
        return JsonObject(all.toSortedMap().mapValues { (_, v) -> element(v) }).toString()
    }

    fun line(event: String, side: String, fields: Map<String, Any?> = emptyMap(), nowMs: Long = System.currentTimeMillis()): String =
        jsonLine(fields + mapOf("event" to event, "side" to side), nowMs)

    fun timestamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(ms))

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Keeps a log file to its newest half when it grows past [maxBytes],
     * cut at a line boundary (WatchVoiceStats.trimLog). Returns the bytes to
     * keep, or null when nothing needs trimming.
     */
    fun trimmed(content: ByteArray, maxBytes: Int): ByteArray? {
        if (content.size <= maxBytes) return null
        var start = content.size - maxBytes / 2
        val newline = (start until content.size).firstOrNull { content[it] == '\n'.code.toByte() }
        start = if (newline != null) newline + 1 else content.size
        return content.copyOfRange(start, content.size)
    }
}

/**
 * The watch's in-memory call log (WatchCallLog): the newest [keep] lines,
 * each with a running number so it keeps one identity as newer lines arrive
 * and older ones drop off.
 */
class CallLogBuffer(private val keep: Int = 300) {
    private val lines = ArrayDeque<String>()
    var firstLineNumber = 0
        private set

    val size: Int get() = lines.size

    fun append(line: String) {
        lines.addLast(line)
        while (lines.size > keep) {
            lines.removeFirst()
            firstLineNumber++
        }
    }

    fun clear() {
        firstLineNumber += lines.size
        lines.clear()
    }

    /** Newest first, each with its running number. */
    fun newestFirst(): List<Pair<Int, String>> = lines.mapIndexed { i, l -> (firstLineNumber + i) to l }.asReversed()
}
