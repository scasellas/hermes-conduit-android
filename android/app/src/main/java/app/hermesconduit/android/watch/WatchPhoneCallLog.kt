package app.hermesconduit.android.watch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.hermesconduit.wearwire.CallLogLines
import java.io.File

/**
 * The watch call log on the phone (iOS WatchPhoneCallLog): this side's
 * events and the watch's, as JSON lines in one file that Settings > Watch
 * can share for a support question. Never a token or key, never what was
 * said. Kept to its newest few megabytes.
 */
class WatchPhoneCallLog private constructor(context: Context) {
    private val app = context.applicationContext
    val file: File = File(app.filesDir, "WatchCalls/conduit-watch-call-log.jsonl").apply { parentFile?.mkdirs() }
    private var bytesSinceTrim = 0

    init {
        trim()
    }

    @Synchronized
    fun note(event: String, fields: Map<String, Any?> = emptyMap()) = append(CallLogLines.line(event, "phone", fields))

    /** A line the watch sent (its notes and call summaries). */
    @Synchronized
    fun watchLine(line: String) {
        if (line.isNotBlank()) append(line.trim())
    }

    @Synchronized
    fun clear() {
        bytesSinceTrim = 0
        file.writeBytes(ByteArray(0))
    }

    val sizeBytes: Long get() = if (file.exists()) file.length() else 0

    /** A share sheet with a copy of the log. */
    fun shareIntent(): Intent {
        val dir = File(app.cacheDir, "shared/watch-log").apply { mkdirs() }
        val copy = File(dir, "conduit-watch-call-log.jsonl")
        synchronized(this) { if (file.exists()) file.copyTo(copy, overwrite = true) else copy.writeBytes(ByteArray(0)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun append(line: String) {
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

    companion object {
        const val MAX_BYTES = 4_000_000
        @Volatile private var shared: WatchPhoneCallLog? = null
        fun get(context: Context): WatchPhoneCallLog = shared ?: synchronized(this) { shared ?: WatchPhoneCallLog(context).also { shared = it } }
    }
}
