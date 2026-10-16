package app.hermesconduit.android.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Base64

/** A file picked into the composer, read into memory (≤ 16 MB, the iOS cap). */
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val base64: String get() = Base64.getEncoder().encodeToString(bytes)
    val dataUrl: String get() = "data:$mimeType;base64,$base64"

    override fun equals(other: Any?) = other is Attachment && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        fun read(context: Context, uri: Uri): Attachment {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size > MAX_BYTES) throw IllegalArgumentException("“$name” is larger than 16 MB.")
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) throw IllegalArgumentException("“$name” is larger than 16 MB.")
                }
                out.toByteArray()
            } ?: throw IllegalArgumentException("Couldn't read “$name”.")
            val mime = resolver.getType(uri) ?: guessMime(name)
            return Attachment(java.util.UUID.randomUUID().toString(), name, mime, bytes)
        }

        fun guessMime(name: String): String =
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

        /** Hermes' `format_reference_value`: quote values holding whitespace, brackets or quotes. */
        fun formatReferenceValue(value: String): String {
            if (value.none { it.isWhitespace() || it in "()[]{}<>\"'`" }) return value
            for (quote in listOf("`", "\"", "'")) if (quote !in value) return "$quote$value$quote"
            return value
        }

        fun promptText(text: String, fileReferences: List<String>): String {
            val references = fileReferences.joinToString("\n")
            if (references.isEmpty()) return text
            if (text.isBlank()) return references
            return "$references\n\n$text"
        }
    }
}
