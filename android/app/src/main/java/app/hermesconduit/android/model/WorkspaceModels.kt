package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonObject

data class WorkspaceEntry(val name: String, val path: String, val isDirectory: Boolean)

data class WorkspaceFilePreview(
    val binary: Boolean,
    val byteSize: Long,
    val language: String,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
)

object WorkspaceParser {
    /** `/api/fs/list`: folders first, then by name. Throws on the host's `error`. */
    fun entries(response: JsonObject): List<WorkspaceEntry> {
        response["error"].str?.takeIf { it.isNotEmpty() }?.let { throw IllegalStateException(it) }
        return response["entries"].arr.orEmpty().mapNotNull { element ->
            val item = element.obj ?: return@mapNotNull null
            val name = item["name"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val path = item["path"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WorkspaceEntry(name, path, item["isDirectory"].bool ?: false)
        }.sortedWith(compareBy<WorkspaceEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun preview(response: JsonObject) = WorkspaceFilePreview(
        binary = response["binary"].bool ?: false,
        byteSize = response["byteSize"].long ?: 0,
        language = response["language"].str ?: "text",
        mimeType = response["mimeType"].str ?: "text/plain",
        text = response["text"].str.orEmpty(),
        truncated = response["truncated"].bool ?: false,
    )

    /** Decodes a `data:<mime>;base64,<payload>` URL to (bytes, mime). */
    fun decodeDataUrl(dataUrl: String): Pair<ByteArray, String?>? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',').takeIf { it > 0 } ?: return null
        val header = dataUrl.substring(5, comma)
        if (!header.endsWith(";base64")) return null
        val mime = header.removeSuffix(";base64").substringBefore(';').ifEmpty { null }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim()) }.getOrNull() ?: return null
        return bytes to mime
    }

    /** Folder rows shown expanded, depth-first. */
    fun flatten(
        root: String,
        entries: Map<String, List<WorkspaceEntry>>,
        expanded: Set<String>,
    ): List<Pair<WorkspaceEntry, Int>> {
        val out = mutableListOf<Pair<WorkspaceEntry, Int>>()
        fun walk(path: String, depth: Int) {
            entries[path].orEmpty().forEach { entry ->
                out += entry to depth
                if (entry.isDirectory && entry.path in expanded) walk(entry.path, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }
}
