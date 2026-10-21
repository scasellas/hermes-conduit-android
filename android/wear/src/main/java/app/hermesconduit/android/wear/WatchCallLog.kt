package app.hermesconduit.android.wear

import android.content.Context
import app.hermesconduit.wearwire.CallLogBuffer
import app.hermesconduit.wearwire.CallLogLines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The watch side of the call log (iOS WatchCallLog): every event is a JSON
 * line kept on the watch and also queued to the phone, where Settings >
 * Watch can share it for a support question. Kept to its newest megabyte.
 */
class WatchCallLog(context: Context, private val link: () -> PhoneLink?) {
    private val file = File(context.filesDir, "watch-call-log.jsonl")
    private val buffer = CallLogBuffer(KEPT_LINES)
    private var bytesSinceTrim = 0

    private val _lines = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    /** Newest first, each with its running number. */
    val lines: StateFlow<List<Pair<Int, String>>> = _lines

    init {
        trim()
        runCatching { file.takeIf { it.exists() }?.readLines()?.takeLast(KEPT_LINES)?.forEach(buffer::append) }
        publish()
    }

    /** Records one event, here and in the phone's log. */
    @Synchronized
    fun note(event: String, fields: Map<String, Any?> = emptyMap()) {
        val line = CallLogLines.line(event, "watch", fields)
        append(line)
        link()?.queueLog(line)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        bytesSinceTrim = 0
        file.delete()
        publish()
    }

    private fun append(line: String) {
        buffer.append(line)
        publish()
        val bytes = (line + "\n").toByteArray()
        runCatching { file.appendBytes(bytes) }
        bytesSinceTrim += bytes.size
        if (bytesSinceTrim >= MAX_BYTES / 8) {
            bytesSinceTrim = 0
            trim()
        }
    }

    private fun trim() {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        runCatching { CallLogLines.trimmed(file.readBytes(), MAX_BYTES)?.let(file::writeBytes) }
    }

    private fun publish() {
        _lines.value = buffer.newestFirst()
    }

    companion object {
        const val KEPT_LINES = 300
        const val MAX_BYTES = 1_000_000
    }
}
