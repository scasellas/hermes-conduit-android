package app.hermesconduit.android.data

import android.content.Context
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.SessionSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Read-only on-disk copy of the chat list and recently opened transcripts,
 * shown on a cold launch or while the dashboard can't be reached — the iOS
 * OfflineChatCache. Kept per dashboard and profile; capped in size.
 */
class OfflineCache(context: Context) {
    private val root = File(context.filesDir, "offline").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Transcript(val sessionId: String, val title: String, val savedAtMs: Long, val messages: List<ChatMessage>)

    private fun dir(dashboardId: String?, profile: String) =
        File(root, "${safe(dashboardId ?: "none")}/${safe(profile)}").apply { mkdirs() }

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    fun saveSessions(dashboardId: String?, profile: String, sessions: List<SessionSummary>) = runCatching {
        File(dir(dashboardId, profile), "sessions.json").writeText(json.encodeToString(ListSerializer(SessionSummary.serializer()), sessions.take(300)))
    }

    fun sessions(dashboardId: String?, profile: String): List<SessionSummary>? = runCatching {
        json.decodeFromString(ListSerializer(SessionSummary.serializer()), File(dir(dashboardId, profile), "sessions.json").readText())
    }.getOrNull()

    fun saveTranscript(dashboardId: String?, profile: String, sessionId: String, title: String, messages: List<ChatMessage>) = runCatching {
        val folder = File(dir(dashboardId, profile), "chats").apply { mkdirs() }
        val settled = messages.filterNot { it.streaming }.takeLast(200)
        File(folder, safe(sessionId) + ".json").writeText(
            json.encodeToString(Transcript.serializer(), Transcript(sessionId, title, System.currentTimeMillis(), settled)),
        )
        // Keep the 30 most recent chats.
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CHATS)?.forEach { it.delete() }
    }

    fun transcript(dashboardId: String?, profile: String, sessionId: String): Pair<List<ChatMessage>, Long>? = runCatching {
        val t = json.decodeFromString(Transcript.serializer(), File(File(dir(dashboardId, profile), "chats"), safe(sessionId) + ".json").readText())
        t.messages to t.savedAtMs
    }.getOrNull()

    fun clear(dashboardId: String?) = File(root, safe(dashboardId ?: "none")).deleteRecursively()

    private companion object { const val MAX_CHATS = 30 }
}
