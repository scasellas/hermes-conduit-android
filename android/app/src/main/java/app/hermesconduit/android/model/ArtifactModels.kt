package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Something a chat produced: a file Hermes sent or wrote, a page it drew, a
 * file the user attached, or a link (plugin 0.40+, capability `artifacts`).
 * Port of iOS `ArtifactItem`.
 */
data class ArtifactItem(
    val kind: Kind,
    val source: Source,
    val name: String,
    /** The file on the Hermes host, for everything but links. */
    val path: String?,
    val url: String?,
    val host: String?,
    /** False when the host no longer has the file. */
    val exists: Boolean,
    val size: Long?,
    val sessionId: String,
    val sessionTitle: String,
    /** Hermes' `messages.id` of the message it came from. */
    val messageId: Long,
    val timestampMs: Long,
) {
    enum class Kind(val wire: String, val label: String) {
        IMAGE("image", "Image"), VIDEO("video", "Video"), AUDIO("audio", "Audio"), PAGE("page", "Page"),
        DOCUMENT("document", "Document"), FILE("file", "File"), LINK("link", "Link"),
    }

    enum class Source(val wire: String) { DELIVERED("delivered"), PREVIEW("preview"), WRITTEN("written"), ATTACHED("attached"), LINK("link") }

    /** One path or URL is one artifact, listed at its newest mention. */
    val id: String get() = path ?: url ?: "$messageId|$name"

    val isVisual: Boolean get() = kind == Kind.IMAGE || kind == Kind.VIDEO
}

enum class ArtifactFilter(val title: String) {
    ALL("All"), IMAGES("Images"), PAGES("Pages"), FILES("Files"), LINKS("Links");

    /** The host's `kind` filter; null lists everything. */
    val hostKind: String? get() = if (this == ALL) null else name.lowercase()

    fun includes(kind: ArtifactItem.Kind): Boolean = when (this) {
        ALL -> true
        IMAGES -> kind == ArtifactItem.Kind.IMAGE || kind == ArtifactItem.Kind.VIDEO
        PAGES -> kind == ArtifactItem.Kind.PAGE
        FILES -> kind == ArtifactItem.Kind.AUDIO || kind == ArtifactItem.Kind.DOCUMENT || kind == ArtifactItem.Kind.FILE
        LINKS -> kind == ArtifactItem.Kind.LINK
    }
}

data class ArtifactPage(val items: List<ArtifactItem>, val nextBefore: Long?)

data class ArtifactDay(val day: LocalDate, val items: List<ArtifactItem>)

object ArtifactParser {
    const val CAPABILITY = "artifacts"
    const val PATH = "api/plugins/conduit_push/artifacts"
    const val PAGE_SIZE = 60

    fun item(element: JsonElement?): ArtifactItem? {
        val raw = element.obj ?: return null
        val kind = ArtifactItem.Kind.entries.firstOrNull { it.wire == raw["kind"].str } ?: return null
        val source = ArtifactItem.Source.entries.firstOrNull { it.wire == raw["source"].str } ?: return null
        val sessionId = raw["session_id"].str?.takeIf { it.isNotEmpty() } ?: return null
        val messageId = (raw["message_id"] as? JsonPrimitive)?.takeIf { !it.isString }?.long ?: return null
        val timestamp = (raw["timestamp"] as? JsonPrimitive)?.takeIf { !it.isString }?.double ?: return null
        val path = raw["path"].str?.takeIf { it.isNotEmpty() }
        val url = raw["url"].str?.takeIf { webUrl(it) }
        // A file needs its path; a link needs a web address.
        if (source == ArtifactItem.Source.LINK) {
            if (url == null) return null
        } else if (path == null) {
            return null
        }
        val trimmed = raw["name"].str?.trim().orEmpty()
        val name = trimmed.ifEmpty { path?.substringAfterLast('/') ?: url?.let { runCatching { URI(it).host }.getOrNull() } ?: "" }
        return ArtifactItem(
            kind = kind,
            source = source,
            name = name,
            path = path,
            url = url,
            host = raw["host"].str,
            exists = raw["exists"].bool ?: true,
            size = raw["size"].long,
            sessionId = sessionId,
            sessionTitle = raw["session_title"].str.orEmpty(),
            messageId = messageId,
            timestampMs = (timestamp * 1000).toLong(),
        )
    }

    private fun webUrl(value: String): Boolean {
        val scheme = runCatching { URI(value).scheme?.lowercase() }.getOrNull()
        return scheme == "https" || scheme == "http"
    }

    /** Null when the host's answer is malformed. */
    fun page(response: JsonObject): ArtifactPage? {
        if (response["ok"].bool != true) return null
        val rows = response["items"].arr ?: return null
        return ArtifactPage(merged(emptyList(), rows.mapNotNull(::item)), response["next_before"].long)
    }

    /** `older` appended to `items`, each artifact once (its newest mention stays). */
    fun merged(items: List<ArtifactItem>, older: List<ArtifactItem>): List<ArtifactItem> {
        val seen = items.map { it.id }.toMutableSet()
        return items + older.filter { seen.add(it.id) }
    }

    fun query(profile: String, sessionId: String?, filter: ArtifactFilter, query: String, before: Long?): Map<String, String?> = mapOf(
        "limit" to PAGE_SIZE.toString(),
        "session_id" to sessionId?.takeIf { it.isNotEmpty() },
        "kind" to filter.hostKind,
        "q" to query.trim().takeIf { it.isNotEmpty() },
        "before" to before?.toString(),
        "profile" to profile.takeIf { it != "default" },
    )

    /** Items grouped by the calendar day they came from, in list order. */
    fun days(items: List<ArtifactItem>, zone: ZoneId = ZoneId.systemDefault()): List<ArtifactDay> {
        val groups = LinkedHashMap<LocalDate, MutableList<ArtifactItem>>()
        items.forEach { item ->
            val day = Instant.ofEpochMilli(item.timestampMs).atZone(zone).toLocalDate()
            groups.getOrPut(day) { mutableListOf() }.add(item)
        }
        return groups.map { (day, list) -> ArtifactDay(day, list) }
    }
}
