package app.hermesconduit.android.core.live

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * What a live voice conversation sends, in no provider's wire format: each
 * session encodes it for its own server (Gemini Live, Grok Live).
 */
sealed interface LiveVoiceClientMessage {
    /** 16 kHz PCM16 mono microphone audio. */
    class Audio(val pcm16: ByteArray) : LiveVoiceClientMessage
    /** The microphone was muted mid-utterance: end the user's turn now. */
    data object AudioStreamEnd : LiveVoiceClientMessage
    /** A text turn from the client (a background-job update). */
    data class TextTurn(val text: String) : LiveVoiceClientMessage
    /** Context the model keeps without answering it (#363): a turn typed in the attached chat. */
    data class ContextNote(val text: String) : LiveVoiceClientMessage
    /** The answer to a function call. */
    data class ToolResponse(val id: String, val name: String, val result: Map<String, String>, val scheduling: GeminiLiveProtocol.Scheduling?) : LiveVoiceClientMessage
}

/**
 * Wire format for the Gemini Live API (v1beta BidiGenerateContent) as used
 * by Conduit's Gemini Live voice mode. Pure encode/decode: no sockets, no
 * audio, so every message shape is unit-testable.
 */
object GeminiLiveProtocol {
    const val MODEL = "models/gemini-3.8-live"
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val INPUT_MIME_TYPE = "audio/pcm;rate=16000"

    // region Client → server

    /** Function scheduling for a response to a NON_BLOCKING call. */
    enum class Scheduling(val raw: String) {
        /** Wait until the model has finished what it is currently doing. */
        WHEN_IDLE("WHEN_IDLE"),
        /** Absorb silently; use later in the conversation. */
        SILENT("SILENT"),
    }

    enum class FunctionBehavior(val raw: String) { BLOCKING("BLOCKING"), NON_BLOCKING("NON_BLOCKING") }

    data class FunctionDeclaration(
        val name: String,
        val description: String,
        /** OpenAPI-subset schema object for the arguments. */
        val parameters: JsonObject,
        val behavior: FunctionBehavior,
    ) {
        val json: JsonObject
            get() = buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
                put("behavior", behavior.raw)
            }
    }

    /** `gemini-3.8-live` → `models/gemini-3.8-live`. */
    fun qualifiedModel(model: String) = if (model.startsWith("models/")) model else "models/$model"

    fun setupMessage(
        model: String = MODEL,
        systemInstruction: String,
        functions: List<FunctionDeclaration>,
        googleSearch: Boolean = true,
        voice: String? = null,
        resumptionHandle: String?,
    ): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", qualifiedModel(model))
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                // No voice leaves Gemini's own default.
                if (!voice.isNullOrEmpty()) {
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
            }
            putJsonArray("tools") {
                add(buildJsonObject { put("functionDeclarations", JsonArray(functions.map { it.json })) })
                // Gemini's own Google Search answers quick lookups directly.
                if (googleSearch) add(buildJsonObject { putJsonObject("googleSearch") {} })
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", false) }
                // The user's speech always interrupts the model.
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }
            // An empty object still opts in: the server then sends resumption handles.
            putJsonObject("sessionResumption") {
                if (!resumptionHandle.isNullOrEmpty()) put("handle", resumptionHandle)
            }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }

    fun audioMessage(pcm16: ByteArray): JsonObject = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("mimeType", INPUT_MIME_TYPE)
                put("data", Base64.getEncoder().encodeToString(pcm16))
            }
        }
    }

    /** Sent when the microphone is muted mid-utterance so the server does not wait for more audio. */
    fun audioStreamEndMessage(): JsonObject = buildJsonObject { putJsonObject("realtimeInput") { put("audioStreamEnd", true) } }

    private fun clientContent(text: String, complete: Boolean) = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            }
            put("turnComplete", complete)
        }
    }

    /** A text turn from the client (background-job updates whose function call is no longer open). */
    fun textTurnMessage(text: String) = clientContent(text, true)

    /** Context added without asking for a reply: `turnComplete: false` leaves generation to the user's next turn. */
    fun contextNoteMessage(text: String) = clientContent(text, false)

    fun toolResponseMessage(id: String, name: String, result: Map<String, String>, scheduling: Scheduling?): JsonObject = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                add(buildJsonObject {
                    put("id", id)
                    put("name", name)
                    // Google's examples put scheduling inside `response`; the API also defines it as a sibling. Both.
                    if (scheduling != null) put("scheduling", scheduling.raw)
                    putJsonObject("response") {
                        for ((key, value) in result) put(key, value)
                        if (scheduling != null) put("scheduling", scheduling.raw)
                    }
                })
            }
        }
    }

    /** The Gemini Live frame for a client message. */
    fun message(message: LiveVoiceClientMessage): JsonObject = when (message) {
        is LiveVoiceClientMessage.Audio -> audioMessage(message.pcm16)
        LiveVoiceClientMessage.AudioStreamEnd -> audioStreamEndMessage()
        is LiveVoiceClientMessage.TextTurn -> textTurnMessage(message.text)
        is LiveVoiceClientMessage.ContextNote -> contextNoteMessage(message.text)
        is LiveVoiceClientMessage.ToolResponse -> toolResponseMessage(message.id, message.name, message.result, message.scheduling)
    }

    fun encode(message: JsonObject): String = message.toString()

    // endregion

    // region Server → client

    data class FunctionCall(
        val id: String,
        val name: String,
        /** String-valued arguments only: every Conduit tool takes strings. */
        val arguments: Map<String, String>,
    )

    sealed interface ServerEvent {
        data object SetupComplete : ServerEvent
        /** Model speech: 16-bit PCM at `sampleRate`. */
        class Audio(val pcm: ByteArray, val sampleRate: Int) : ServerEvent
        data class InputTranscription(val text: String) : ServerEvent
        data class OutputTranscription(val text: String) : ServerEvent
        /** The user started speaking: stop playback now. */
        data object Interrupted : ServerEvent
        data object TurnComplete : ServerEvent
        data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
        data class ToolCallCancellation(val ids: List<String>) : ServerEvent
        data class GoAway(val timeLeftSeconds: Double?) : ServerEvent
        data class ResumptionUpdate(val handle: String?, val resumable: Boolean) : ServerEvent
    }

    /** One server frame can carry several events. Unknown fields are ignored. */
    fun decode(text: String): List<ServerEvent> {
        val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull() ?: return emptyList()
        val events = mutableListOf<ServerEvent>()
        if (obj.containsKey("setupComplete")) events += ServerEvent.SetupComplete
        obj["serverContent"].obj?.let { content ->
            content["modelTurn"].obj?.get("parts").arr?.forEach { part ->
                val inline = part.obj?.get("inlineData").obj ?: return@forEach
                val mime = inline["mimeType"].str ?: return@forEach
                if (!mime.startsWith("audio/pcm")) return@forEach
                val data = inline["data"].str ?: return@forEach
                val pcm = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return@forEach
                events += ServerEvent.Audio(pcm, sampleRate(mime) ?: OUTPUT_SAMPLE_RATE)
            }
            content["inputTranscription"].obj?.get("text").str?.takeIf { it.isNotEmpty() }?.let { events += ServerEvent.InputTranscription(it) }
            content["outputTranscription"].obj?.get("text").str?.takeIf { it.isNotEmpty() }?.let { events += ServerEvent.OutputTranscription(it) }
            if ((content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true) events += ServerEvent.Interrupted
            if ((content["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true) events += ServerEvent.TurnComplete
        }
        obj["toolCall"].obj?.get("functionCalls").arr?.let { calls ->
            val parsed = calls.mapNotNull { element ->
                val call = element.obj ?: return@mapNotNull null
                val id = (call["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val name = (call["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                FunctionCall(id, name, stringArguments(call["args"].obj))
            }
            if (parsed.isNotEmpty()) events += ServerEvent.ToolCall(parsed)
        }
        obj["toolCallCancellation"].obj?.get("ids").arr?.mapNotNull { it.str }?.takeIf { it.isNotEmpty() }?.let { events += ServerEvent.ToolCallCancellation(it) }
        obj["goAway"].obj?.let { events += ServerEvent.GoAway(duration(it["timeLeft"])) }
        obj["sessionResumptionUpdate"].obj?.let { update ->
            events += ServerEvent.ResumptionUpdate(update["newHandle"].str?.takeIf { it.isNotEmpty() }, update["resumable"].bool ?: false)
        }
        return events
    }

    /** Strings stay strings; numbers become their text; anything else is dropped. */
    internal fun stringArguments(raw: JsonObject?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw?.forEach { (key, value) ->
            val p = value as? JsonPrimitive ?: return@forEach
            if (p.isString) out[key] = p.content
            else if (p.booleanOrNull == null && p.content != "null") out[key] = numberText(p)
        }
        return out
    }

    /** NSNumber.stringValue: whole numbers without ".0". */
    private fun numberText(p: JsonPrimitive): String {
        val d = p.content.toDoubleOrNull() ?: return p.content
        return if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else p.content
    }

    /** `audio/pcm;rate=24000` → 24000. */
    fun sampleRate(mime: String): Int? {
        for (parameter in mime.split(';').drop(1)) {
            val pair = parameter.split('=', limit = 2)
            if (pair.size != 2 || pair[0].trim().lowercase() != "rate") continue
            val rate = pair[1].trim().toDoubleOrNull() ?: continue
            if (rate > 0) return rate.toInt()
        }
        return null
    }

    /** Protobuf Duration in JSON: `"10s"`/`"1.5s"`, or `{seconds, nanos}`. */
    fun duration(value: JsonElement?): Double? {
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
            if (!text.endsWith("s")) return null
            return text.dropLast(1).toDoubleOrNull()
        }
        val obj = value.obj ?: return null
        val seconds = obj["seconds"].double ?: obj["seconds"].str?.toDoubleOrNull() ?: 0.0
        val nanos = obj["nanos"].double ?: 0.0
        return seconds + nanos / 1_000_000_000
    }

    // endregion
}

/**
 * How the server ended a WebSocket: its close code and reason text, or the
 * HTTP status when it refused the WebSocket upgrade itself.
 */
data class LiveServerClose(val code: Int, val reason: String, val isHttpStatus: Boolean = false) {
    /**
     * Codes that mean Google rejected what was sent (the setup, token or
     * model), which a retry won't change. An exhausted quota is final too.
     */
    val isRefusal: Boolean
        get() {
            if (isQuotaExhausted) return true
            if (isHttpStatus) return code in setOf(400, 401, 403, 404)
            return code in setOf(1003, 1007, 1008) || code in 4000..4999
        }

    /** Google's wording for a spent quota, not any reason that mentions a quota. */
    val isQuotaExhausted: Boolean
        get() {
            val text = reason.lowercase().replace("_", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return "exceeded your current quota" in text || "resource exhausted" in text || "resource has been exhausted" in text
        }

    /** Google no longer has the session a resumption handle named. */
    val isResumptionLost: Boolean
        get() {
            val text = reason.lowercase()
            return listOf("session not found", "session expired", "session has expired", "expired session").any { it in text }
        }

    /** The first line of Google's reason (capped), or the code when there is none. */
    val summary: String
        get() {
            var text = reason.lines().firstOrNull().orEmpty()
            val pointer = text.indexOf("For more information on this error", ignoreCase = true)
            if (pointer >= 0) text = text.substring(0, pointer)
            text = text.trim()
            if (text.isEmpty()) return if (isHttpStatus) "HTTP $code" else L10n.tr("close code %s", code.toString())
            if (text.length <= 300) return text
            val capped = text.take(300)
            var cut = capped.lastIndexOf(' ').let { if (it < 0) capped.length else it }
            // A space too early would drop most of the reason: cut hard instead.
            if (cut < 200) cut = capped.length
            return capped.substring(0, cut) + "…"
        }
}

internal fun jsonStringMap(map: Map<String, String>): JsonObject = buildJsonObject { map.toSortedMap().forEach { (k, v) -> put(k, v) } }

internal fun jsonStrings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
