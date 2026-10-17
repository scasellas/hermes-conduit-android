package app.hermesconduit.wearwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * A watch voice call's control messages between the watch and the phone
 * (port of the iOS `WatchVoiceWire`, designs/apple-watch-voice-direct.md and
 * apple-watch-gpt-live.md): the direct Gemini Live call, Grok and GPT-Live
 * through the Hermes host's audio bridge. Each message travels on the Wear
 * Data Layer as one request on [PATH] (answered by the other side's reply)
 * or one queued DataItem under [QUEUE_PREFIX].
 *
 * The JSON is what Swift's synthesized Codable writes for the same values:
 * an enum case is `{"case":{"label":value}}` (`_0` for an unlabeled one),
 * a missing optional is left out, `Data` is base64 and a `Date` is seconds
 * since 2001-01-01, so both platforms read each other's messages.
 */
object WatchVoiceWire {
    /** Bumped when either side changes what a message means; a mismatch refuses the call instead of misreading it. */
    const val VERSION = 2
    /** The request path of a watch voice message. */
    const val PATH = "/conduit/wv"
    /** Queued messages (a call's end, a job the phone takes later): `/conduit/wvq/<id>`. */
    const val QUEUE_PREFIX = "/conduit/wvq/"

    /** Seconds between 1970 and Swift's reference date (2001-01-01). */
    private const val REFERENCE_DATE_SECONDS = 978_307_200.0

    fun encode(message: Message): ByteArray = message.json().toString().toByteArray(Charsets.UTF_8)

    /** Null for a message this build can't read. */
    fun decode(bytes: ByteArray): Message? = runCatching {
        val o = WearWire.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return null
        Message.from(o)
    }.getOrNull()

    // region Values

    /**
     * A single-use Gemini Live token the phone got from the Hermes host. With
     * the call's tool grant, the only credential the watch ever holds: it
     * works for one session and expires within the half hour.
     */
    data class DirectToken(
        val token: String,
        val expiresAtMs: Long?,
        val newSessionExpiresAtMs: Long?,
        val model: String,
        val webSocketUrl: String,
    ) {
        fun json() = obj {
            put("token", token)
            date("expiresAt", expiresAtMs)
            date("newSessionExpiresAt", newSessionExpiresAtMs)
            put("model", model)
            put("webSocketURL", webSocketUrl)
        }

        companion object {
            fun from(o: JsonObject) = DirectToken(o.req("token"), o.date("expiresAt"), o.date("newSessionExpiresAt"), o.req("model"), o.req("webSocketURL"))
        }
    }

    /**
     * Everything a watch call needs to run Gemini Live the way a call on the
     * phone does, built there: the same instructions, functions and voice,
     * and a token for the first connection.
     */
    data class DirectSession(
        val token: DirectToken,
        /** [DirectSetup] as raw-deflate JSON: the instructions carry the persona and memory, which can be long. */
        val setup: ByteArray,
        /** The setup's uncompressed size, for the log. */
        val setupBytes: Int,
        val googleSearch: Boolean,
        val voice: String?,
        /** The call's greeting turn, when the user asked for one. */
        val openingPrompt: String?,
        /** Lets the call's lookups and jobs reach Hermes through the push relay. Null when the host can't grant one. */
        val toolGrant: DirectToolGrant?,
        /** The profile's spoken end phrases. Null from an older phone. */
        val endPhrases: List<String>? = null,
    ) {
        fun json() = obj {
            put("token", token.json())
            data("setup", setup)
            put("setupBytes", setupBytes)
            put("googleSearch", googleSearch)
            opt("voice", voice)
            opt("openingPrompt", openingPrompt)
            toolGrant?.let { put("toolGrant", it.json()) }
            strings("endPhrases", endPhrases)
        }

        override fun equals(other: Any?) = other is DirectSession && json() == other.json()
        override fun hashCode() = json().hashCode()

        companion object {
            fun from(o: JsonObject) = DirectSession(
                DirectToken.from(o.objReq("token")), o.dataReq("setup"), o.intReq("setupBytes"), o.boolReq("googleSearch"),
                o.str("voice"), o.str("openingPrompt"), o.obj("toolGrant")?.let(DirectToolGrant::from), o.strings("endPhrases"),
            )
        }
    }

    /**
     * One call's grant to run web_search, recall_memory, the chat tools and,
     * with the user's job setting, Hermes jobs through the push relay. The key
     * seals what the watch asks and what Hermes answers; the relay never gets
     * it, and keeps only a hash of [watchKey].
     */
    data class DirectToolGrant(
        val grantId: String,
        /** The relay's https base URL. */
        val relayUrl: String,
        /** The 32-byte root of the grant's sealing keys, base64url. */
        val key: String,
        /** The watch's key to the grant on the relay, base64url. */
        val watchKey: String,
        val expiresAtMs: Long?,
        val tools: List<String>,
        val maxCalls: Int,
        /** The jobs one call may start through the relay, when the grant carries jobs. */
        val maxJobs: Int? = null,
        /** The user lets the model answer job approvals by voice; set by the phone from its setting. */
        val voiceApprovals: Boolean? = null,
        /** A renewal: the previous grant whose jobs Hermes moved to this one. */
        val jobsCarriedFrom: String? = null,
        /** The call's audio bridge for GPT-Live and Grok through the Hermes host. */
        val audio: AudioBridge? = null,
        /** The names a job may give the user's profiles; set by the phone. */
        val jobProfiles: List<JobProfileName>? = null,
        /** The other profiles the host runs this call's jobs on. */
        val hostJobProfiles: List<String>? = null,
    ) {
        fun json() = obj {
            put("grantID", grantId)
            put("relayURL", relayUrl)
            put("key", key)
            put("watchKey", watchKey)
            date("expiresAt", expiresAtMs)
            strings("tools", tools)
            put("maxCalls", maxCalls)
            maxJobs?.let { put("maxJobs", it) }
            voiceApprovals?.let { put("voiceApprovals", it) }
            opt("jobsCarriedFrom", jobsCarriedFrom)
            audio?.let { put("audio", it.json()) }
            jobProfiles?.let { list -> put("jobProfiles", JsonArray(list.map { it.json() })) }
            strings("hostJobProfiles", hostJobProfiles)
        }

        companion object {
            fun from(o: JsonObject) = DirectToolGrant(
                o.req("grantID"), o.req("relayURL"), o.req("key"), o.req("watchKey"), o.date("expiresAt"),
                o.strings("tools") ?: throw IllegalArgumentException("tools"), o.intReq("maxCalls"),
                o.int("maxJobs"), o.bool("voiceApprovals"), o.str("jobsCarriedFrom"), o.obj("audio")?.let(AudioBridge::from),
                o.objects("jobProfiles")?.map(JobProfileName::from), o.strings("hostJobProfiles"),
            )
        }
    }

    /** One of the user's profiles as a job may name it: the profile (null: the call's own) and every name it goes by. */
    data class JobProfileName(val profile: String?, val names: List<String>) {
        fun json() = obj {
            opt("profile", profile)
            strings("names", names)
        }

        companion object {
            fun from(o: JsonObject) = JobProfileName(o.str("profile"), o.strings("names").orEmpty())
        }
    }

    /**
     * Where the watch dials the host's audio bridge: the relay's
     * `wss://…/v1/watch-audio/{grant}/watch`. [engines] are those the host
     * can run now: GPT-Live only once its WebRTC runtime is made.
     */
    data class AudioBridge(val url: String, val version: Int, val engines: List<String>) {
        fun json() = obj {
            put("url", url)
            put("version", version)
            strings("engines", engines)
        }

        companion object {
            fun from(o: JsonObject) = AudioBridge(o.req("url"), o.intReq("version"), o.strings("engines").orEmpty())
        }
    }

    /** A watch call to GPT-Live through the host's audio bridge, built on the phone. */
    data class BridgeSession(
        val engine: String,
        val grant: DirectToolGrant,
        /** Conduit's rules, persona and memory for the call as raw-deflate UTF-8. */
        val briefing: ByteArray,
        val briefingBytes: Int,
        val greeting: String?,
        val voice: String?,
        val endPhrases: List<String>? = null,
        /** A call from Hermes the watch answered opens with this turn. */
        val openingTurn: String? = null,
    ) {
        /** The briefing as the phone built it; null if it can't be read. */
        val briefingText: String? get() = WatchDirectWire.inflate(briefing)?.toString(Charsets.UTF_8)

        fun json() = obj {
            put("engine", engine)
            put("grant", grant.json())
            data("briefing", briefing)
            put("briefingBytes", briefingBytes)
            opt("greeting", greeting)
            opt("voice", voice)
            strings("endPhrases", endPhrases)
            opt("openingTurn", openingTurn)
        }

        override fun equals(other: Any?) = other is BridgeSession && json() == other.json()
        override fun hashCode() = json().hashCode()

        companion object {
            fun from(o: JsonObject) = BridgeSession(
                o.req("engine"), DirectToolGrant.from(o.objReq("grant")), o.dataReq("briefing"), o.intReq("briefingBytes"),
                o.str("greeting"), o.str("voice"), o.strings("endPhrases"), o.str("openingTurn"),
            )

            /** The briefing packed for the wire, and its size before compression. */
            fun pack(briefing: String): Pair<ByteArray, Int> {
                val text = briefing.toByteArray(Charsets.UTF_8)
                return WatchDirectWire.deflate(text) to text.size
            }
        }
    }

    /**
     * A watch call to Grok, built on the phone. The watch runs the
     * conversation as it runs Gemini's, but xAI's session is held by the
     * Hermes host and reached through the grant's audio bridge.
     */
    data class GrokSession(
        val setup: ByteArray,
        val setupBytes: Int,
        val voice: String?,
        val openingPrompt: String?,
        val grant: DirectToolGrant,
        val endPhrases: List<String>? = null,
    ) {
        fun json() = obj {
            data("setup", setup)
            put("setupBytes", setupBytes)
            opt("voice", voice)
            opt("openingPrompt", openingPrompt)
            put("grant", grant.json())
            strings("endPhrases", endPhrases)
        }

        override fun equals(other: Any?) = other is GrokSession && json() == other.json()
        override fun hashCode() = json().hashCode()

        companion object {
            fun from(o: JsonObject) = GrokSession(
                o.dataReq("setup"), o.intReq("setupBytes"), o.str("voice"), o.str("openingPrompt"),
                DirectToolGrant.from(o.objReq("grant")), o.strings("endPhrases"),
            )
        }
    }

    /** A function call from the model, for the phone's tool bridge to run. */
    data class DirectToolCall(val id: String, val name: String, val arguments: Map<String, String>) {
        fun json() = obj {
            put("id", id)
            put("name", name)
            put("arguments", stringMap(arguments))
        }

        companion object {
            fun from(o: JsonObject) = DirectToolCall(o.req("id"), o.req("name"), o.stringMap("arguments").orEmpty())
        }
    }

    /** What the tool bridge asks the call to send the model, as on the phone. */
    sealed class DirectOutgoing {
        /** [fallback] is said instead, as a text turn, when the call can't be answered any more (a new connection took over). */
        data class ToolResponse(val id: String, val name: String, val result: Map<String, String>, val scheduling: String?, val fallback: String?) : DirectOutgoing()
        /** Sent as a text turn once nobody is speaking. */
        data class TextWhenIdle(val text: String) : DirectOutgoing()
        /** Kept by the model without a reply, sent once nobody is speaking. */
        data class ContextWhenIdle(val text: String) : DirectOutgoing()
        /** The model said goodbye: end once it has played. */
        data object EndConversation : DirectOutgoing()

        /** Waits for a quiet moment, as the phone's call holds its updates. */
        val waitsForQuiet: Boolean get() = this is TextWhenIdle || this is ContextWhenIdle

        fun json(): JsonObject = when (this) {
            is ToolResponse -> case("toolResponse") {
                put("id", id)
                put("name", name)
                put("result", stringMap(result))
                opt("scheduling", scheduling)
                opt("fallback", fallback)
            }
            is TextWhenIdle -> case("textWhenIdle") { put("_0", text) }
            is ContextWhenIdle -> case("contextWhenIdle") { put("_0", text) }
            EndConversation -> case("endConversation") {}
        }

        companion object {
            fun from(o: JsonObject): DirectOutgoing? {
                val (name, body) = o.case() ?: return null
                return when (name) {
                    "toolResponse" -> ToolResponse(body.req("id"), body.req("name"), body.stringMap("result").orEmpty(), body.str("scheduling"), body.str("fallback"))
                    "textWhenIdle" -> TextWhenIdle(body.req("_0"))
                    "contextWhenIdle" -> ContextWhenIdle(body.req("_0"))
                    "endConversation" -> EndConversation
                    else -> null
                }
            }
        }
    }

    /** The phone's answer to a tool call or a job poll. */
    data class DirectToolResult(
        val outgoing: List<DirectOutgoing>,
        /** Voice jobs still running on Hermes: the watch asks again while there are any. */
        val runningJobs: Int,
    ) {
        fun json() = obj {
            put("outgoing", JsonArray(outgoing.map { it.json() }))
            put("runningJobs", runningJobs)
        }

        companion object {
            fun from(o: JsonObject) = DirectToolResult(o.objects("outgoing")?.mapNotNull(DirectOutgoing::from).orEmpty(), o.int("runningJobs") ?: 0)
        }
    }

    /** One settled line of a watch call's transcript. */
    data class DirectTurn(val role: Role, val text: String, val atMs: Long) {
        enum class Role(val raw: String) { USER("user"), ASSISTANT("assistant") }

        fun json() = obj {
            put("role", role.raw)
            put("text", text)
            date("at", atMs)
        }

        companion object {
            fun from(o: JsonObject) = DirectTurn(
                Role.entries.firstOrNull { it.raw == o.str("role") } ?: throw IllegalArgumentException("role"),
                o.str("text").orEmpty(), o.date("at") ?: 0,
            )
        }
    }

    /** A job a watch call started through the push relay. */
    data class DirectJob(val jobId: String, val title: String, val sessionId: String?, val startedAtMs: Long) {
        fun json() = obj {
            put("jobID", jobId)
            put("title", title)
            opt("sessionID", sessionId)
            date("startedAt", startedAtMs)
        }

        companion object {
            fun from(o: JsonObject) = DirectJob(o.req("jobID"), o.str("title").orEmpty(), o.str("sessionID"), o.date("startedAt") ?: 0)
        }
    }

    /** The jobs a watch call started through the relay, in order, from the start answers and the job news. */
    class DirectJobLog {
        private val _jobs = mutableListOf<DirectJob>()
        val jobs: List<DirectJob> get() = _jobs.toList()

        fun started(jobId: String, title: String, atMs: Long = System.currentTimeMillis()) {
            if (jobId.isEmpty() || _jobs.any { it.jobId == jobId }) return
            _jobs += DirectJob(jobId, title.ifEmpty { DEFAULT_TITLE }, null, atMs)
        }

        /** News names the job's chat; a job first heard of here (its start answer was lost) is kept from now. */
        fun heard(jobId: String, title: String, sessionId: String?, atMs: Long = System.currentTimeMillis()) {
            started(jobId, title, atMs)
            val index = _jobs.indexOfFirst { it.jobId == jobId }
            if (index < 0) return
            var job = _jobs[index]
            if (!sessionId.isNullOrEmpty()) job = job.copy(sessionId = sessionId)
            if (title.isNotEmpty() && job.title == DEFAULT_TITLE) job = job.copy(title = title)
            _jobs[index] = job
        }

        fun reset() = _jobs.clear()

        companion object {
            const val DEFAULT_TITLE = "Hermes job"
        }
    }

    /** A finished watch call, queued to the phone to save in voice history whenever Conduit next runs there. */
    data class DirectTranscript(
        /** Names the saved call, so a transcript delivered twice is saved once. */
        val callUuid: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val turns: List<DirectTurn>,
        /** The engine the call ran on; null for Gemini. */
        val engine: String? = null,
        /** The jobs the call started through the push relay. Null from an older watch. */
        val jobs: List<DirectJob>? = null,
    ) {
        fun json() = obj {
            put("callUUID", callUuid)
            date("startedAt", startedAtMs)
            date("endedAt", endedAtMs)
            put("turns", JsonArray(turns.map { it.json() }))
            opt("engine", engine)
            jobs?.let { list -> put("jobs", JsonArray(list.map { it.json() })) }
        }

        companion object {
            fun from(o: JsonObject) = DirectTranscript(
                o.req("callUUID"), o.date("startedAt") ?: 0, o.date("endedAt") ?: 0,
                o.objects("turns")?.map(DirectTurn::from).orEmpty(), o.str("engine"), o.objects("jobs")?.map(DirectJob::from),
            )

            /** The newest turns whose text fits this many UTF-8 bytes: a long call keeps its end. */
            const val MAX_TEXT_BYTES = 40_000

            fun capped(turns: List<DirectTurn>, bytes: Int = MAX_TEXT_BYTES): List<DirectTurn> {
                val kept = mutableListOf<DirectTurn>()
                var total = 0
                for (turn in turns.asReversed()) {
                    total += turn.text.toByteArray(Charsets.UTF_8).size
                    if (total > bytes) {
                        // A newest turn too long on its own keeps its opening, so a call is never saved empty.
                        if (kept.isEmpty()) {
                            val clipped = clippedPrefix(turn.text, bytes)
                            if (clipped.isNotEmpty()) kept += turn.copy(text = clipped)
                        }
                        break
                    }
                    kept += turn
                }
                return kept.asReversed()
            }

            /** The longest whole-character opening of [text] within [bytes] of UTF-8. */
            fun clippedPrefix(text: String, bytes: Int): String {
                val out = StringBuilder()
                var used = 0
                val breaker = java.text.BreakIterator.getCharacterInstance()
                breaker.setText(text)
                var start = breaker.first()
                var end = breaker.next()
                while (end != java.text.BreakIterator.DONE) {
                    val character = text.substring(start, end)
                    val size = character.toByteArray(Charsets.UTF_8).size
                    if (used + size > bytes) break
                    used += size
                    out.append(character)
                    start = end
                    end = breaker.next()
                }
                return out.toString()
            }
        }
    }

    // endregion

    // region Messages

    /** Control messages, both directions. Call ids are UInt32 on iOS: kept in a Long. */
    sealed class Message {
        // Watch → phone
        /** A watch call to Gemini Live wants its session. [ring]: the call from Hermes it answers. */
        data class DirectStart(override val callId: Long, val version: Int, val ring: String? = null) : Message()
        /** A new single-use token for the call's next connection. */
        data class DirectTokenRequest(override val callId: Long) : Message()
        data class DirectTool(override val callId: Long, val call: DirectToolCall) : Message()
        /** The model withdrew these calls (the user interrupted). */
        data class DirectToolCancel(override val callId: Long, val ids: List<String>) : Message()
        /** While the call's jobs run: anything to tell the user? */
        data class DirectPoll(override val callId: Long) : Message()
        /** The call ended; queued, so it arrives even if the phone can't be reached right now. */
        data class DirectEnd(override val callId: Long, val transcript: DirectTranscript) : Message()
        /** A new tool grant, before the call's runs out; Hermes moves the jobs of [carryJobsFrom] to it. */
        data class DirectGrant(override val callId: Long, val carryJobsFrom: String? = null) : Message()
        /** A watch call through the host's audio bridge (GPT-Live) wants its grant and briefing. */
        data class BridgeStart(override val callId: Long, val version: Int, val engine: String, val ring: String? = null) : Message()
        /** A watch call to Grok wants its setup and the grant that opens the host's audio bridge. */
        data class GrokStart(override val callId: Long, val version: Int, val ring: String? = null) : Message()
        // Phone → watch
        /** Why the phone can't serve the call, as the watch shows it. */
        data class CallRefused(override val callId: Long, val reason: String) : Message()
        data class DirectSessionIssued(override val callId: Long, val session: DirectSession) : Message()
        data class DirectTokenIssued(override val callId: Long, val token: DirectToken) : Message()
        data class DirectToolResultIssued(override val callId: Long, val result: DirectToolResult) : Message()
        data class DirectGrantIssued(override val callId: Long, val grant: DirectToolGrant) : Message()
        data class BridgeSessionIssued(override val callId: Long, val session: BridgeSession) : Message()
        data class GrokSessionIssued(override val callId: Long, val session: GrokSession) : Message()

        abstract val callId: Long

        fun json(): JsonObject = when (this) {
            is DirectStart -> case("directStart") { put("callID", callId); put("version", version); opt("ring", ring) }
            is DirectTokenRequest -> case("directToken") { put("callID", callId) }
            is DirectTool -> case("directTool") { put("callID", callId); put("call", call.json()) }
            is DirectToolCancel -> case("directToolCancel") { put("callID", callId); strings("ids", ids) }
            is DirectPoll -> case("directPoll") { put("callID", callId) }
            is DirectEnd -> case("directEnd") { put("callID", callId); put("transcript", transcript.json()) }
            is DirectGrant -> case("directGrant") { put("callID", callId); opt("carryJobsFrom", carryJobsFrom) }
            is BridgeStart -> case("bridgeStart") { put("callID", callId); put("version", version); put("engine", engine); opt("ring", ring) }
            is GrokStart -> case("grokStart") { put("callID", callId); put("version", version); opt("ring", ring) }
            is CallRefused -> case("callRefused") { put("callID", callId); put("reason", reason) }
            is DirectSessionIssued -> case("directSession") { put("callID", callId); put("session", session.json()) }
            is DirectTokenIssued -> case("directTokenIssued") { put("callID", callId); put("token", token.json()) }
            is DirectToolResultIssued -> case("directToolResult") { put("callID", callId); put("result", result.json()) }
            is DirectGrantIssued -> case("directGrantIssued") { put("callID", callId); put("grant", grant.json()) }
            is BridgeSessionIssued -> case("bridgeSession") { put("callID", callId); put("session", session.json()) }
            is GrokSessionIssued -> case("grokSession") { put("callID", callId); put("session", session.json()) }
        }

        companion object {
            fun from(o: JsonObject): Message? {
                val (name, b) = o.case() ?: return null
                val id = b.long("callID") ?: return null
                return when (name) {
                    "directStart" -> DirectStart(id, b.intReq("version"), b.str("ring"))
                    "directToken" -> DirectTokenRequest(id)
                    "directTool" -> DirectTool(id, DirectToolCall.from(b.objReq("call")))
                    "directToolCancel" -> DirectToolCancel(id, b.strings("ids").orEmpty())
                    "directPoll" -> DirectPoll(id)
                    "directEnd" -> DirectEnd(id, DirectTranscript.from(b.objReq("transcript")))
                    "directGrant" -> DirectGrant(id, b.str("carryJobsFrom"))
                    "bridgeStart" -> BridgeStart(id, b.intReq("version"), b.req("engine"), b.str("ring"))
                    "grokStart" -> GrokStart(id, b.intReq("version"), b.str("ring"))
                    "callRefused" -> CallRefused(id, b.str("reason").orEmpty())
                    "directSession" -> DirectSessionIssued(id, DirectSession.from(b.objReq("session")))
                    "directTokenIssued" -> DirectTokenIssued(id, DirectToken.from(b.objReq("token")))
                    "directToolResult" -> DirectToolResultIssued(id, DirectToolResult.from(b.objReq("result")))
                    "directGrantIssued" -> DirectGrantIssued(id, DirectToolGrant.from(b.objReq("grant")))
                    "bridgeSession" -> BridgeSessionIssued(id, BridgeSession.from(b.objReq("session")))
                    "grokSession" -> GrokSessionIssued(id, GrokSession.from(b.objReq("session")))
                    else -> null
                }
            }
        }
    }

    // endregion

    // region Swift Codable JSON

    private class Builder {
        val map = linkedMapOf<String, JsonElement>()
        fun put(key: String, value: String) { map[key] = JsonPrimitive(value) }
        fun put(key: String, value: Number) { map[key] = JsonPrimitive(value) }
        fun put(key: String, value: Boolean) { map[key] = JsonPrimitive(value) }
        fun put(key: String, value: JsonElement) { map[key] = value }
        fun opt(key: String, value: String?) { if (value != null) put(key, value) }
        fun strings(key: String, values: List<String>?) { if (values != null) map[key] = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } } }
        fun data(key: String, bytes: ByteArray) { put(key, Base64.getEncoder().encodeToString(bytes)) }
        fun date(key: String, ms: Long?) { if (ms != null) put(key, ms / 1000.0 - REFERENCE_DATE_SECONDS) }
    }

    private fun obj(build: Builder.() -> Unit): JsonObject = JsonObject(Builder().apply(build).map)

    private fun case(name: String, build: Builder.() -> Unit): JsonObject = buildJsonObject { put(name, obj(build)) }

    private fun stringMap(map: Map<String, String>) = buildJsonObject { map.forEach { (k, v) -> put(k, v) } }

    private fun JsonObject.case(): Pair<String, JsonObject>? {
        val entry = entries.singleOrNull() ?: return null
        return entry.key to (entry.value as? JsonObject ?: return null)
    }

    private fun JsonObject.prim(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.req(key: String): String = str(key) ?: throw IllegalArgumentException(key)
    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
    private fun JsonObject.intReq(key: String): Int = int(key) ?: throw IllegalArgumentException(key)
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.boolReq(key: String): Boolean = bool(key) ?: throw IllegalArgumentException(key)
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.objReq(key: String): JsonObject = obj(key) ?: throw IllegalArgumentException(key)
    private fun JsonObject.objects(key: String): List<JsonObject>? = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    private fun JsonObject.stringMap(key: String): Map<String, String>? =
        obj(key)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap()
    private fun JsonObject.dataReq(key: String): ByteArray = Base64.getDecoder().decode(req(key))
    private fun JsonObject.date(key: String): Long? = prim(key)?.doubleOrNull?.let { ((it + REFERENCE_DATE_SECONDS) * 1000).toLong() }

    // endregion
}
