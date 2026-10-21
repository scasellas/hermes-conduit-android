package app.hermesconduit.android.ui.voice.live

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.live.LiveTranscriptEntry
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.voice.VoiceThreadTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A saved call started from (or resumed into) a chat (the iOS
 * `VoiceCallChatLink`): the chat shows a "Voice call" card that opens the
 * call's transcript, and Resume Call attaches the new call to the same chat.
 * Kept on the device: the host's call tag doesn't name a chat.
 */
data class VoiceCallChatLink(
    /** The live call this card is for (one per call or resume). */
    val callId: String,
    /** The saved call's row; empty when the call is kept with the chat. */
    val callSessionId: String,
    val chatRuntimeSessionId: String,
    val chatStoredSessionId: String?,
    val chatTitle: String,
    val profile: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val resumed: Boolean,
    /** The bot whose Bot Chat this is; null for a chat on the call's profile. */
    val chatProfile: String? = null,
) {
    val markerId: String get() = "voice-call-$callId"
    /** The card in the call's own transcript that leads back to the chat. */
    val originMarkerId: String get() = "voice-call-from-$callId"
    /** The call has no row: the plugin keeps its turns with the chat. */
    val storedWithChat: Boolean get() = callSessionId.isEmpty()

    /**
     * The chat is matched on its stored id when it has one: a runtime id
     * only names the chat while it is open, and a later session can run
     * under it.
     */
    fun belongs(ids: Set<String>): Boolean {
        if (!chatStoredSessionId.isNullOrEmpty()) return chatStoredSessionId in ids
        return chatRuntimeSessionId.isNotEmpty() && chatRuntimeSessionId in ids
    }

    /** The chat's id to open it by; null when the link names none. */
    val chatSessionId: String? get() = listOfNotNull(chatStoredSessionId, chatRuntimeSessionId).firstOrNull { it.isNotEmpty() }

    /** The chat as a live call's target, for Resume Call. */
    val thread: VoiceThreadTarget get() = VoiceThreadTarget(chatStoredSessionId ?: chatRuntimeSessionId, chatStoredSessionId, chatTitle, chatProfile)

    val durationMs: Long get() = maxOf(0L, endedAtMs - startedAtMs)

    val marker: ChatMessage
        get() = ChatMessage(
            id = markerId,
            role = MessageRole.SYSTEM,
            content = if (resumed) L10n.tr("Voice call resumed") else L10n.tr("Voice call"),
            timestampMs = startedAtMs,
            kind = DISPLAY_KIND,
        )

    val originMarker: ChatMessage
        get() = ChatMessage(
            id = originMarkerId,
            role = MessageRole.SYSTEM,
            content = L10n.tr("Started from %s", chatTitle),
            timestampMs = startedAtMs,
            kind = ORIGIN_DISPLAY_KIND,
        )

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("callID", JsonPrimitive(callId))
            put("callSessionID", JsonPrimitive(callSessionId))
            put("chatRuntimeSessionID", JsonPrimitive(chatRuntimeSessionId))
            chatStoredSessionId?.let { put("chatStoredSessionID", JsonPrimitive(it)) }
            put("chatTitle", JsonPrimitive(chatTitle))
            put("profile", JsonPrimitive(profile))
            put("startedAt", JsonPrimitive(startedAtMs))
            put("endedAt", JsonPrimitive(endedAtMs))
            put("resumed", JsonPrimitive(resumed))
            chatProfile?.let { put("chatProfile", JsonPrimitive(it)) }
        },
    )

    companion object {
        const val DISPLAY_KIND = "conduit_voice_call"
        const val ORIGIN_DISPLAY_KIND = "conduit_voice_call_origin"

        fun parse(obj: JsonObject): VoiceCallChatLink? {
            return VoiceCallChatLink(
                callId = obj["callID"].str ?: return null,
                callSessionId = obj["callSessionID"].str ?: return null,
                chatRuntimeSessionId = obj["chatRuntimeSessionID"].str.orEmpty(),
                chatStoredSessionId = obj["chatStoredSessionID"].str,
                chatTitle = obj["chatTitle"].str.orEmpty(),
                profile = obj["profile"].str ?: return null,
                startedAtMs = obj["startedAt"].long ?: return null,
                endedAtMs = obj["endedAt"].long ?: return null,
                resumed = obj["resumed"].bool ?: false,
                chatProfile = obj["chatProfile"].str,
            )
        }
    }
}

/** The chat a recording call is attached to, until its row is saved. */
data class VoiceCallAttachment(
    val thread: VoiceThreadTarget,
    val callId: String,
    val startedAtMs: Long,
    val resumed: Boolean,
    /** Set when the call closes, so a slow save doesn't lengthen it. */
    val endedAtMs: Long? = null,
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put(
                "thread",
                JsonObject(
                    buildMap {
                        put("runtimeSessionID", JsonPrimitive(thread.runtimeSessionId))
                        thread.storedSessionId?.let { put("storedSessionID", JsonPrimitive(it)) }
                        put("title", JsonPrimitive(thread.title))
                        thread.profile?.let { put("profile", JsonPrimitive(it)) }
                    },
                ),
            )
            put("callID", JsonPrimitive(callId))
            put("startedAt", JsonPrimitive(startedAtMs))
            put("resumed", JsonPrimitive(resumed))
            endedAtMs?.let { put("endedAt", JsonPrimitive(it)) }
        },
    )

    companion object {
        fun parse(element: JsonElement?): VoiceCallAttachment? {
            val obj = element.obj ?: return null
            val thread = obj["thread"].obj ?: return null
            return VoiceCallAttachment(
                VoiceThreadTarget(thread["runtimeSessionID"].str ?: return null, thread["storedSessionID"].str, thread["title"].str.orEmpty(), thread["profile"].str),
                obj["callID"].str ?: return null,
                obj["startedAt"].long ?: return null,
                obj["resumed"].bool ?: false,
                obj["endedAt"].long,
            )
        }
    }
}

/** The calls started from chats, kept on this device (the iOS `VoiceCallChatLinks`). */
data class VoiceCallChatLinks(val links: List<VoiceCallChatLink> = emptyList()) {
    fun adding(link: VoiceCallChatLink): VoiceCallChatLinks =
        VoiceCallChatLinks((links.filter { it.callId != link.callId } + link).takeLast(MAXIMUM_LINKS))

    /** The chat a saved call was last attached to, for Resume Call. */
    fun latest(callSessionId: String, profile: String): VoiceCallChatLink? =
        links.lastOrNull { it.callSessionId == callSessionId && it.profile == profile }

    fun link(markerId: String): VoiceCallChatLink? = links.firstOrNull { it.markerId == markerId || it.originMarkerId == markerId }

    /**
     * The chat's history with its call cards, each placed where its call
     * started. The history's own order is kept. `openIds` are the open
     * session's own ids, so only a call's own row counts as its transcript.
     */
    fun merge(history: List<ChatMessage>, chatIds: Set<String>, openIds: Set<String>, profile: String): List<ChatMessage> {
        val profileLinks = links.filter { it.profile == profile }
        // The open session is a call's own transcript (a resumed call has a
        // link per resume): it only leads back to its chat. Its ids can carry
        // the chat's, so no chat card is placed in it, this call's or
        // another's. A resume that stayed with the same chat adds no second card.
        val ownedChats = mutableSetOf<String>()
        val owned = profileLinks
            .filter { !it.storedWithChat && it.callSessionId in openIds }
            .sortedBy { it.startedAtMs }
            .filter { link ->
                // A chat seen first by its runtime and later by its stored id is still one chat.
                val ids = listOfNotNull(link.chatStoredSessionId, link.chatRuntimeSessionId).filter { it.isNotEmpty() }.toSet()
                if (ownedChats.any { it in ids }) {
                    ownedChats += ids
                    false
                } else {
                    ownedChats += ids.ifEmpty { setOf(link.callId) }
                    true
                }
            }
        val markers = if (owned.isEmpty()) {
            profileLinks.filter { it.belongs(chatIds) }.map { it to it.marker }
        } else {
            owned.map { it to it.originMarker }
        }
        if (markers.isEmpty()) return history
        val merged = history.toMutableList()
        for ((link, marker) in markers) {
            if (merged.any { it.id == marker.id }) continue
            val index = merged.indexOfFirst { message -> message.timestampMs?.let { it > link.startedAtMs } ?: false }
            merged.add(if (index < 0) merged.size else index, marker)
        }
        return merged
    }

    fun toJson(): String = JsonObject(mapOf("links" to JsonArray(links.map { it.toJson() }))).toString()

    companion object {
        const val STORAGE_KEY = "conduit.voiceCallChatLinks.v1"
        const val MAXIMUM_LINKS = 300

        /** A call started again in its chat within this long goes on as the same call: one card, one transcript. */
        const val CHAT_CALL_RECONNECT_WINDOW_MS = 120_000L

        fun parse(json: String?): VoiceCallChatLinks {
            val root = json?.let { runCatching { ConduitJson.parseToJsonElement(it).obj }.getOrNull() } ?: return VoiceCallChatLinks()
            return VoiceCallChatLinks(root["links"].arr?.mapNotNull { it.obj?.let(VoiceCallChatLink::parse) }.orEmpty())
        }

        /** Whether a call starting now goes on as the last call kept with a chat: same chat and profile, started again within the reconnect window. */
        fun continuesChatCall(lastChatId: String, lastProfile: String, endedAtMs: Long, chatId: String?, profile: String, nowMs: Long): Boolean {
            if (chatId == null || chatId != lastChatId || profile != lastProfile) return false
            val gap = nowMs - endedAtMs
            return gap in 0..CHAT_CALL_RECONNECT_WINDOW_MS
        }
    }
}

/** A call kept with its chat, as the chat's call card shows it (the iOS `VoiceChatCall`). */
data class VoiceChatCall(
    val callId: String,
    val turns: List<LiveTranscriptEntry>,
    /** Names the assistant's lines for TalkBack; null when the host didn't say. */
    val engine: String? = null,
) {
    companion object {
        /** `GET PLUGIN/voice/chat-calls` answers; null for a malformed one. */
        fun parse(response: JsonObject): List<VoiceChatCall>? {
            if (response["ok"].bool != true) return null
            val calls = response["calls"].arr ?: return null
            return calls.mapNotNull { it.obj }.mapNotNull { call ->
                val callId = call["call_id"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val turns = call["turns"].arr.orEmpty().mapNotNull { it.obj }.mapNotNull turn@{ turn ->
                    val text = turn["text"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@turn null
                    when (turn["role"].str) {
                        "user" -> LiveTranscriptEntry(fromUser = true, text = text)
                        "assistant" -> LiveTranscriptEntry(fromUser = false, text = text)
                        else -> null
                    }
                }
                val engine = call["engine"].str?.takeIf { it in setOf("gemini-live", "gpt-live", "grok-live") }
                VoiceChatCall(callId, turns, engine)
            }
        }
    }
}

/** How long a call card says the call ran: the two largest units, as iOS' abbreviated duration (hours, minutes, seconds). */
object VoiceCallDuration {
    enum class Unit { HOURS, MINUTES, SECONDS }

    fun parts(durationMs: Long): List<Pair<Unit, Int>> {
        val total = (maxOf(0L, durationMs) / 1000).toInt()
        val all = listOf(Unit.HOURS to total / 3600, Unit.MINUTES to (total % 3600) / 60, Unit.SECONDS to total % 60)
        val first = all.indexOfFirst { it.second > 0 }
        if (first < 0) return listOf(Unit.SECONDS to 0)
        return all.drop(first).take(2).filter { it.second > 0 }
    }
}
