package app.hermesconduit.android.core.live

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.BreakIterator

/** Who pays for a GPT-Live call: the host's `billing` request field and its answer's `auth`. */
enum class GPTLiveBilling(val raw: String) {
    /** The ChatGPT subscription the Hermes host is signed in to. */
    SUBSCRIPTION("subscription"),
    /** The Hermes host's OpenAI API key, billed per minute. */
    API("api");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Wire format for GPT-Live on a ChatGPT subscription: the Codex "frameless"
 * realtime events, carried over the WebRTC `oai-events` data channel. Audio
 * travels as WebRTC media tracks. Pure encode/decode.
 *
 * GPT-Live on an API key speaks the public GPT-Live API's events instead
 * (`Dialect.API`): `session.input_transcript.delta` /
 * `session.output_transcript.delta`, `session.delegation.created`, and
 * appends as `session.commentary.append` (said aloud) or
 * `session.thinking.append` (quiet). It has no `turn.done`: [TurnAssembler]
 * makes one.
 */
object GPTLiveProtocol {
    const val DATA_CHANNEL_LABEL = "oai-events"
    /** The most UTF-8 bytes one context append may carry. */
    const val CONTEXT_APPEND_MAX_BYTES = 500

    enum class Dialect {
        CODEX, API;

        companion object {
            fun of(billing: GPTLiveBilling) = if (billing == GPTLiveBilling.API) API else CODEX
        }
    }

    sealed interface ServerEvent {
        data class SessionStarted(val id: String?) : ServerEvent
        data class InputTranscript(val text: String) : ServerEvent
        data class OutputTranscript(val text: String) : ServerEvent
        data class TurnDone(val role: String, val transcript: String) : ServerEvent
        /** The model handed work to the client. `text` is often empty: the request is then in the transcript. */
        data class Delegation(val id: String, val text: String) : ServerEvent
        data class Error(val code: String?, val message: String) : ServerEvent
        data class SessionClosed(val reason: String?) : ServerEvent
    }

    fun decode(text: String): ServerEvent? {
        val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull() ?: return null
        return when (obj["type"].str) {
            "session.started", "session.updated" -> ServerEvent.SessionStarted(obj["session"].obj?.get("id").str)
            "input_transcript.added" -> obj["item"].obj?.get("text").str?.let(ServerEvent::InputTranscript)
            "output_transcript.added" -> obj["item"].obj?.get("text").str?.let(ServerEvent::OutputTranscript)
            "session.input_transcript.delta" -> obj["delta"].str?.let(ServerEvent::InputTranscript)
            "session.output_transcript.delta" -> obj["delta"].str?.let(ServerEvent::OutputTranscript)
            "session.delegation.created" -> {
                val delegation = obj["delegation"].obj ?: return null
                if ((delegation["target"].str ?: "client") != "client") return null
                val id = delegation["id"].str?.takeIf { it.isNotEmpty() } ?: return null
                ServerEvent.Delegation(id, "")
            }
            "turn.done" -> {
                val turn = obj["turn"].obj ?: return null
                val role = turn["role"].str ?: return null
                val transcript = turn["transcript"].str ?: return null
                ServerEvent.TurnDone(role, transcript)
            }
            "delegation.created" -> {
                val item = obj["item"].obj ?: return null
                if (item["type"].str != "delegation" || item["target"].str != "client") return null
                val id = item["id"].str?.takeIf { it.isNotEmpty() } ?: return null
                val content = item["content"].arr.orEmpty().mapNotNull { it.obj }
                    .filter { it["type"].str == "input_text" }
                    .mapNotNull { it["text"].str }
                    .joinToString("")
                ServerEvent.Delegation(id, content)
            }
            "error" -> {
                val error = obj["error"].obj
                ServerEvent.Error(error?.get("code").str, error?.get("message").str ?: obj["message"].str ?: "")
            }
            "session.closed" -> ServerEvent.SessionClosed(obj["reason"].str)
            else -> null
        }
    }

    enum class Channel(val raw: String) {
        /** Said aloud (paraphrased) by the model. */
        SPEAKABLE("speakable"),
        /** Quiet context: progress, state the model may use. */
        COMMENTARY("commentary"),
    }

    /** Context appends for `text`, one per 500-byte chunk. With a delegation id they answer it; without, they add to the session. */
    fun contextAppendMessages(text: String, channel: Channel, delegationId: String? = null, dialect: Dialect = Dialect.CODEX): List<JsonObject> {
        if (dialect == Dialect.API) {
            return chunks(text).map { chunk ->
                buildJsonObject {
                    put("content", chunk)
                    // Null adds to the session rather than a delegation.
                    put("delegation_id", delegationId?.let(::JsonPrimitive) ?: JsonNull)
                    put("type", if (channel == Channel.SPEAKABLE) "session.commentary.append" else "session.thinking.append")
                }
            }
        }
        return chunks(text).map { chunk ->
            buildJsonObject {
                put("channel", channel.raw)
                putJsonArray("content") { add(buildJsonObject { put("text", chunk); put("type", "input_text") }) }
                if (delegationId != null) put("delegation_item_id", delegationId)
                put("type", if (delegationId == null) "session.context.append" else "delegation.context.append")
            }
        }
    }

    fun sessionCloseMessage(): JsonObject = buildJsonObject { put("type", "session.close") }

    /**
     * `text` (trimmed) in pieces of at most `limit` UTF-8 bytes, never inside a
     * character. A single character over the limit is split between its code points.
     */
    fun chunks(text: String, limit: Int = CONTEXT_APPEND_MAX_BYTES): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        fun append(piece: String, size: Int) {
            if (bytes + size > limit && current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
                bytes = 0
            }
            current.append(piece)
            bytes += size
        }
        val breaker = BreakIterator.getCharacterInstance()
        breaker.setText(trimmed)
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val character = trimmed.substring(start, end)
            val size = character.toByteArray(Charsets.UTF_8).size
            if (size <= limit) append(character, size)
            else character.codePoints().forEach { cp ->
                val scalar = String(Character.toChars(cp))
                append(scalar, scalar.toByteArray(Charsets.UTF_8).size)
            }
            start = end
            end = breaker.next()
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** Sorted keys, as the Swift client writes them. */
    fun encode(message: JsonObject): String = JsonObject(message.toSortedMap()).toString()

    /**
     * Makes the `turn.done` the public API doesn't send: a turn ends when the
     * other speaker starts, when the model delegates, or after the quiet
     * interval without new words ([flush], on a timer). Pure.
     */
    class TurnAssembler {
        private var role: String? = null
        private var text = StringBuilder()

        val hasOpenTurn: Boolean get() = role != null

        /** How long the open turn may go quiet before it ends. */
        val openTurnQuietIntervalMs: Long get() = if (role == "user") USER_QUIET_INTERVAL_MS else QUIET_INTERVAL_MS

        fun receive(event: ServerEvent): List<ServerEvent> = when (event) {
            is ServerEvent.InputTranscript -> take(event.text, "user") + event
            is ServerEvent.OutputTranscript -> take(event.text, "assistant") + event
            is ServerEvent.Delegation -> if (role == "user") listOfNotNull(flush(), event) else listOf(event)
            is ServerEvent.SessionClosed -> listOfNotNull(flush(), event)
            is ServerEvent.TurnDone -> {
                role = null
                text = StringBuilder()
                listOf(event)
            }
            else -> listOf(event)
        }

        fun flush(): ServerEvent? {
            val open = role ?: return null
            val done = ServerEvent.TurnDone(open, text.toString())
            role = null
            text = StringBuilder()
            return done
        }

        private fun take(fragment: String, speaker: String): List<ServerEvent> {
            val closed = mutableListOf<ServerEvent>()
            if (role != speaker) flush()?.let { closed += it }
            role = speaker
            text.append(fragment)
            return closed
        }

        companion object {
            const val QUIET_INTERVAL_MS = 1_500L
            /** People pause mid-sentence; the model answering or delegating ends their turn first anyway. */
            const val USER_QUIET_INTERVAL_MS = 3_000L
        }
    }

    /** A conversation item for the session's `history` (the host passes it on as `initial_items`). */
    fun historyItem(role: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", role)
        putJsonArray("content") {
            add(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text) })
        }
    }
}
