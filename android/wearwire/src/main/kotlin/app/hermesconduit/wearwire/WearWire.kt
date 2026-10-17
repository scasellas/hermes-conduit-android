package app.hermesconduit.wearwire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The phone ↔ watch wire over the Wear Data Layer, the counterpart of the
 * iOS `WatchVoiceWire` (WatchConnectivity). Compiled into both the phone app
 * and the Wear OS app, so both ends always agree.
 *
 * Every message is one MessageClient message: a path naming its kind and a
 * UTF-8 JSON body. State the watch reads whenever it runs (the phone's
 * context) is a DataItem; the watch's call log lines are queued DataItems,
 * so they reach the phone even when it isn't reachable right now.
 *
 * The bridge call (iOS WatchBridgeCallModel): the watch hears the user,
 * sends the words as a [WearMessage.Turn]; the phone runs the turn through
 * Hermes (`prompt.submit`, surface "voice", `voice_turn`) and streams the
 * reply back as [WearMessage.Reply] events, which the watch speaks.
 */
object WearWire {
    /** Bumped on an incompatible change; each side refuses a newer major. */
    const val VERSION = 1

    /** Capabilities each app advertises (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "conduit_phone"
    const val CAPABILITY_WATCH = "conduit_watch"

    const val PREFIX = "/conduit/"
    const val TURN = "/conduit/voice/turn"
    const val STOP = "/conduit/voice/stop"
    const val REPLY = "/conduit/voice/reply"
    const val CHATS_REQUEST = "/conduit/chats/request"
    const val CHATS = "/conduit/chats"
    const val APPROVALS_REQUEST = "/conduit/approvals/request"
    const val APPROVALS = "/conduit/approvals"
    const val APPROVAL_RESPOND = "/conduit/approvals/respond"
    const val APPROVAL_RESULT = "/conduit/approvals/result"

    /** DataItem the phone keeps current: [PhoneContext]. */
    const val CONTEXT = "/conduit/context"
    /** Queued DataItems, one per watch call log line: `/conduit/log/<id>`. */
    const val LOG_PREFIX = "/conduit/log/"
    /** DataMap key holding a DataItem's JSON. */
    const val DATA_KEY = "json"

    /** Answers the watch may give an approval: never for the session or always (WatchJobAnswer). */
    const val APPROVE_ONCE = "once"
    const val DENY = "deny"

    internal val json = Json { ignoreUnknownKeys = true }

    fun encode(message: WearMessage): Pair<String, ByteArray> = message.path to message.body().toString().toByteArray(Charsets.UTF_8)

    /** Null for a path this build doesn't know or a body it can't read. */
    fun decode(path: String, data: ByteArray): WearMessage? {
        val body = runCatching { json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
        return runCatching { WearMessage.from(path, body) }.getOrNull()
    }
}

/** A chat the phone lists on the watch ("Recent chats"). */
data class WearChat(val id: String, val title: String, val preview: String?, val lastActiveMs: Long?) {
    fun toJson() = buildJsonObject {
        put("id", id); put("title", title)
        preview?.let { put("preview", it) }
        lastActiveMs?.let { put("last_active_ms", it) }
    }

    companion object {
        fun from(o: JsonObject): WearChat? {
            val id = o.s("id")?.takeIf { it.isNotEmpty() } ?: return null
            return WearChat(id, o.s("title").orEmpty(), o.s("preview"), o.l("last_active_ms"))
        }
    }
}

/** An approval request waiting on Hermes, as the watch shows it (WatchApprovalCard). */
data class WearApproval(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val command: String,
    val description: String,
) {
    /** One identity per request, also for a request without a queue id. */
    val key: String get() = requestId.ifEmpty { sessionId }

    fun toJson() = buildJsonObject {
        put("session_id", sessionId); put("request_id", requestId)
        put("title", title); put("command", command); put("description", description)
    }

    companion object {
        fun from(o: JsonObject): WearApproval? {
            val session = o.s("session_id")?.takeIf { it.isNotEmpty() } ?: return null
            return WearApproval(session, o.s("request_id").orEmpty(), o.s("title").orEmpty(), o.s("command").orEmpty(), o.s("description").orEmpty())
        }
    }
}

/**
 * What the watch should show from the phone (iOS WatchPhoneContext): whether
 * Conduit there is signed in and connected, and the user's Watch settings.
 */
data class PhoneContext(
    val signedIn: Boolean,
    val connected: Boolean,
    /** Voice settings > Watch: "Tap or voice" lets a call take an approval's answer by voice. */
    val voiceApprovals: Boolean,
    val profile: String,
    val version: Int = WearWire.VERSION,
    val updatedAtMs: Long = 0,
    /** Each watch voice's last check on this profile ([WatchCallVoices]); one not checked yet is missing. */
    val voices: Map<String, Boolean> = emptyMap(),
) {
    /** Whether [voice] is known not to start. */
    fun notSetUp(voice: String): Boolean = voices[voice] == false

    fun toJson() = buildJsonObject {
        put("v", version); put("signed_in", signedIn); put("connected", connected)
        put("voice_approvals", voiceApprovals); put("profile", profile); put("updated_at_ms", updatedAtMs)
        if (voices.isNotEmpty()) put("voices", buildJsonObject { voices.forEach { (k, v) -> put(k, v) } })
    }

    fun encoded(): String = toJson().toString()

    companion object {
        fun decode(text: String): PhoneContext? = runCatching {
            val o = WearWire.json.parseToJsonElement(text) as JsonObject
            PhoneContext(
                signedIn = o.b("signed_in") ?: false,
                connected = o.b("connected") ?: false,
                voiceApprovals = o.b("voice_approvals") ?: false,
                profile = o.s("profile") ?: "default",
                version = o.i("v") ?: 1,
                updatedAtMs = o.l("updated_at_ms") ?: 0,
                voices = (o["voices"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }?.toMap().orEmpty(),
            )
        }.getOrNull()
    }
}

sealed class WearMessage {
    abstract val path: String
    internal abstract fun body(): JsonObject

    /** The user's words for one turn of the call [callId]; [sessionId] continues a chat. */
    data class Turn(val callId: String, val turnId: Int, val text: String, val sessionId: String? = null) : WearMessage() {
        override val path get() = WearWire.TURN
        override fun body() = buildJsonObject {
            put("v", WearWire.VERSION); put("call_id", callId); put("turn_id", turnId); put("text", text)
            sessionId?.let { put("session_id", it) }
        }
    }

    /** Interrupts the running turn (barge-in); [end] also forgets the call. */
    data class Stop(val callId: String, val end: Boolean = false) : WearMessage() {
        override val path get() = WearWire.STOP
        override fun body() = buildJsonObject { put("call_id", callId); put("end", end) }
    }

    /** One event of a turn's reply, phone → watch. */
    data class Reply(val callId: String, val turnId: Int, val event: ReplyEvent) : WearMessage() {
        override val path get() = WearWire.REPLY
        override fun body() = buildJsonObject {
            put("call_id", callId); put("turn_id", turnId)
            when (event) {
                is ReplyEvent.Started -> { put("kind", "started"); put("session_id", event.sessionId) }
                is ReplyEvent.Text -> { put("kind", "text"); put("seq", event.seq); put("text", event.text) }
                is ReplyEvent.Done -> { put("kind", "done"); put("text", event.text); put("interrupted", event.interrupted) }
                is ReplyEvent.Failed -> { put("kind", "failed"); put("message", event.message) }
                is ReplyEvent.Approval -> { put("kind", "approval"); put("approval", event.approval.toJson()) }
                is ReplyEvent.ApprovalCleared -> { put("kind", "approval_cleared"); put("request_id", event.requestId) }
            }
        }
    }

    object ChatsRequest : WearMessage() {
        override val path get() = WearWire.CHATS_REQUEST
        override fun body() = buildJsonObject { put("v", WearWire.VERSION) }
    }

    data class Chats(val chats: List<WearChat>, val error: String? = null) : WearMessage() {
        override val path get() = WearWire.CHATS
        override fun body() = buildJsonObject {
            put("chats", buildJsonArray { chats.forEach { add(it.toJson()) } })
            error?.let { put("error", it) }
        }
    }

    object ApprovalsRequest : WearMessage() {
        override val path get() = WearWire.APPROVALS_REQUEST
        override fun body() = buildJsonObject { put("v", WearWire.VERSION) }
    }

    data class Approvals(val approvals: List<WearApproval>, val error: String? = null) : WearMessage() {
        override val path get() = WearWire.APPROVALS
        override fun body() = buildJsonObject {
            put("approvals", buildJsonArray { approvals.forEach { add(it.toJson()) } })
            error?.let { put("error", it) }
        }
    }

    /** Approve once or deny ([WearWire.APPROVE_ONCE], [WearWire.DENY]); anything else is refused. */
    data class ApprovalRespond(val sessionId: String, val requestId: String, val choice: String) : WearMessage() {
        override val path get() = WearWire.APPROVAL_RESPOND
        override fun body() = buildJsonObject { put("session_id", sessionId); put("request_id", requestId); put("choice", choice) }
    }

    data class ApprovalResult(val sessionId: String, val requestId: String, val ok: Boolean, val error: String? = null) : WearMessage() {
        override val path get() = WearWire.APPROVAL_RESULT
        override fun body() = buildJsonObject {
            put("session_id", sessionId); put("request_id", requestId); put("ok", ok)
            error?.let { put("error", it) }
        }
    }

    companion object {
        internal fun from(path: String, o: JsonObject): WearMessage? = parse(path, o)

        private fun parse(path: String, o: JsonObject): WearMessage? {
            return when (path) {
            WearWire.TURN -> {
                val text = o.s("text")?.trim().orEmpty()
                if (text.isEmpty() || (o.i("v") ?: 1) > WearWire.VERSION) null
                else Turn(o.s("call_id") ?: return null, o.i("turn_id") ?: 0, text, o.s("session_id")?.takeIf { it.isNotEmpty() })
            }
            WearWire.STOP -> Stop(o.s("call_id") ?: return null, o.b("end") ?: false)
            WearWire.REPLY -> {
                val event = when (o.s("kind")) {
                    "started" -> ReplyEvent.Started(o.s("session_id").orEmpty())
                    "text" -> ReplyEvent.Text(o.i("seq") ?: 0, o.s("text").orEmpty())
                    "done" -> ReplyEvent.Done(o.s("text").orEmpty(), o.b("interrupted") ?: false)
                    "failed" -> ReplyEvent.Failed(o.s("message").orEmpty())
                    "approval" -> ReplyEvent.Approval((o["approval"] as? JsonObject)?.let(WearApproval::from) ?: return null)
                    "approval_cleared" -> ReplyEvent.ApprovalCleared(o.s("request_id").orEmpty())
                    else -> return null
                }
                Reply(o.s("call_id") ?: return null, o.i("turn_id") ?: 0, event)
            }
            WearWire.CHATS_REQUEST -> ChatsRequest
            WearWire.CHATS -> Chats(o.objects("chats").mapNotNull(WearChat::from), o.s("error"))
            WearWire.APPROVALS_REQUEST -> ApprovalsRequest
            WearWire.APPROVALS -> Approvals(o.objects("approvals").mapNotNull(WearApproval::from), o.s("error"))
            WearWire.APPROVAL_RESPOND -> {
                val choice = o.s("choice")
                if (choice != WearWire.APPROVE_ONCE && choice != WearWire.DENY) null
                else ApprovalRespond(o.s("session_id")?.takeIf { it.isNotEmpty() } ?: return null, o.s("request_id").orEmpty(), choice)
            }
            WearWire.APPROVAL_RESULT -> ApprovalResult(o.s("session_id").orEmpty(), o.s("request_id").orEmpty(), o.b("ok") ?: false, o.s("error"))
            else -> null
            }
        }
    }
}

sealed class ReplyEvent {
    /** The phone has the call's Hermes chat ([sessionId] is its stored id). */
    data class Started(val sessionId: String) : ReplyEvent()
    /** The reply so far (cumulative, so a lost or reordered message costs nothing); higher [seq] wins. */
    data class Text(val seq: Int, val text: String) : ReplyEvent()
    data class Done(val text: String, val interrupted: Boolean = false) : ReplyEvent()
    data class Failed(val message: String) : ReplyEvent()
    data class Approval(val approval: WearApproval) : ReplyEvent()
    data class ApprovalCleared(val requestId: String) : ReplyEvent()
}

private fun JsonObject.p(key: String) = this[key] as? JsonPrimitive
internal fun JsonObject.s(key: String): String? = p(key)?.takeIf { it.isString || it.contentOrNull != "null" }?.contentOrNull
internal fun JsonObject.i(key: String): Int? = p(key)?.intOrNull
internal fun JsonObject.l(key: String): Long? = p(key)?.longOrNull
internal fun JsonObject.b(key: String): Boolean? = p(key)?.booleanOrNull
internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/**
 * Which of the watch's voices can start, as the phone last found them for
 * the active dashboard and profile (port of the iOS `WatchCallVoices`): the
 * watch's voice picker says which one isn't set up on the Hermes host.
 */
data class WatchCallVoices(
    /** The dashboard and profile the checks were for. */
    val scope: String = "",
    /** Each voice's last check; one not checked yet is missing. */
    val ready: Map<String, Boolean> = emptyMap(),
) {
    /** This record for [to]: another dashboard's or profile's checks say nothing about it. */
    fun scoped(to: String) = if (to == scope) this else WatchCallVoices(to)

    fun noted(voice: String, isReady: Boolean, scope: String) = scoped(scope).let { it.copy(ready = it.ready + (voice to isReady)) }

    fun encoded(): String = buildJsonObject {
        put("scope", scope)
        put("ready", buildJsonObject { ready.forEach { (k, v) -> put(k, v) } })
    }.toString()

    companion object {
        /** The watch's voices, as both apps name them. */
        const val GEMINI_LIVE = "geminiLive"
        const val GPT_LIVE = "gptLive"
        const val GROK_LIVE = "grokLive"

        fun decode(text: String?): WatchCallVoices = runCatching {
            val o = WearWire.json.parseToJsonElement(text ?: return WatchCallVoices()) as JsonObject
            WatchCallVoices(
                o.s("scope").orEmpty(),
                (o["ready"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }?.toMap().orEmpty(),
            )
        }.getOrDefault(WatchCallVoices())
    }
}
