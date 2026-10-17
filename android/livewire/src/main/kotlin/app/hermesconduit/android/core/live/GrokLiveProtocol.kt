package app.hermesconduit.android.core.live

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Wire format for xAI's realtime voice API (Grok Live), whose events follow
 * OpenAI's Realtime API: session.update, input_audio_buffer.append,
 * conversation.item.create, response.create out; audio and transcript
 * deltas, function calls and response.done back. Server events decode into
 * the same events Gemini Live's do, so one conversation controller drives
 * both. Pure encode/decode.
 */
object GrokLiveProtocol {
    const val DEFAULT_MODEL = "grok-voice-latest"
    /** What the session declares; the 16 kHz capture is upsampled to it. */
    const val INPUT_SAMPLE_RATE = 24_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val TRANSCRIPTION_MODEL = "grok-transcribe"

    /** Added to Grok Live's instructions after the shared ones. Sent verbatim, so not localized. */
    const val DELEGATION_INSTRUCTIONS = "\nYou can't see the user's email, messages, calendar and schedule, to-dos, health or patient portals, bank or other accounts, files or systems, and web lookups can't reach them either; their Hermes agent can. Whenever the user asks you to check, read, find, send or do anything there (\"what's on my plate today?\" included), call start_job with their request instead of saying you can't. If they only ask whether you can see it, say Hermes can check it for them. Never answer it from guesses or a web lookup, and if start_job fails, tell them plainly. Public facts anyone could look up (weather, news, sports, stock prices) aren't Hermes work: look them up with web_search or x_search on this call, never with start_job."

    fun grokInstructions(nowMs: Long, timeZone: TimeZone): String = DELEGATION_INSTRUCTIONS + clockInstructions(nowMs, timeZone)

    /** The phone's clock at the start of the call, in a fixed English format. Not localized. */
    fun clockInstructions(nowMs: Long, timeZone: TimeZone): String {
        val formatter = SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US)
        formatter.timeZone = timeZone
        return "\nWhen this call started it was ${formatter.format(Date(nowMs))} in the user's time zone (${timeZone.id}). Use that for the time, date and day of the week, counting on from it as the call goes; never look them up on the web."
    }

    /** xAI's own search tools, run on xAI's side during the call (#598). */
    private val nativeTools: List<JsonObject> = listOf(
        buildJsonObject { put("type", "web_search") },
        buildJsonObject { put("type", "x_search") },
    )

    /** Names xAI keeps for its own tools. */
    val reservedToolNames = setOf("web_search", "x_search", "browse_page", "file_search")

    // region Client → server

    fun sessionUpdate(instructions: String, functions: List<GeminiLiveProtocol.FunctionDeclaration>, voice: String?): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("instructions", instructions)
            // Fast replies: this is a conversation, the work goes to Hermes.
            putJsonObject("reasoning") { put("effort", "none") }
            putJsonObject("turn_detection") {
                put("type", "server_vad")
                put("silence_duration_ms", 700)
                put("prefix_padding_ms", 300)
            }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", INPUT_SAMPLE_RATE) }
                    putJsonObject("transcription") { put("model", TRANSCRIPTION_MODEL) }
                }
                putJsonObject("output") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", OUTPUT_SAMPLE_RATE) }
                }
            }
            put("tools", JsonArray(nativeTools + functions.filter { it.name !in reservedToolNames }.map(::functionTool)))
            put("tool_choice", "auto")
            if (!voice.isNullOrEmpty()) put("voice", voice)
        }
    }

    /** A Gemini declaration as a Realtime function tool: JSON Schema types are lowercase. */
    fun functionTool(declaration: GeminiLiveProtocol.FunctionDeclaration): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", declaration.name)
        put("description", declaration.description)
        put("parameters", jsonSchema(declaration.parameters))
    }

    fun jsonSchema(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, inner) ->
            if (key == "type" && inner is JsonPrimitive && inner.isString) JsonPrimitive(inner.content.lowercase()) else jsonSchema(inner)
        })
        is JsonArray -> JsonArray(value.map(::jsonSchema))
        else -> value
    }

    /**
     * The frames that carry one client message, in order. A message the
     * model should answer ends with `response.create`; the session holds
     * that back while a response is still running.
     */
    fun frames(message: LiveVoiceClientMessage): Pair<List<JsonObject>, Boolean> = when (message) {
        is LiveVoiceClientMessage.Audio ->
            listOf(audioAppend(upsampledForInput(message.pcm16))) to false
        // Server turn detection ends a turn on silence, and a muted microphone sends none.
        LiveVoiceClientMessage.AudioStreamEnd -> listOf(audioAppend(silence)) to false
        is LiveVoiceClientMessage.TextTurn -> listOf(userText(message.text)) to true
        // No response.create: the model keeps it for the user's next turn.
        is LiveVoiceClientMessage.ContextNote -> listOf(userText(message.text)) to false
        is LiveVoiceClientMessage.ToolResponse -> {
            val item = buildJsonObject {
                put("type", "conversation.item.create")
                putJsonObject("item") {
                    put("type", "function_call_output")
                    put("call_id", message.id)
                    put("output", jsonStringMap(message.result).toString())
                }
            }
            // A silent answer is absorbed without the model speaking.
            listOf(item) to (message.scheduling != GeminiLiveProtocol.Scheduling.SILENT)
        }
    }

    private fun audioAppend(pcm: ByteArray) = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }

    private fun userText(text: String) = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }
        }
    }

    val responseCreate: JsonObject = buildJsonObject { put("type", "response.create") }

    /** 0.8 s of PCM16 silence at the input rate: longer than the turn detector's silence window. */
    val silence: ByteArray = ByteArray((INPUT_SAMPLE_RATE * 0.8).toInt() * 2)

    /** 16 kHz capture as 24 kHz PCM16 by linear interpolation: three output samples for every two captured. */
    fun upsampledForInput(pcm16: ByteArray): ByteArray {
        val count = pcm16.size / 2
        if (count == 0) return ByteArray(0)
        val samples = IntArray(count) { (pcm16[2 * it].toInt() and 0xFF or (pcm16[2 * it + 1].toInt() shl 8)).toShort().toInt() }
        val outputCount = count * 3 / 2
        val output = ByteArray(outputCount * 2)
        for (index in 0 until outputCount) {
            val numerator = index * 2
            val lower = numerator / 3
            val fraction = (numerator % 3) / 3.0
            val a = samples[lower].toDouble()
            val b = samples[minOf(lower + 1, count - 1)].toDouble()
            // Swift's .rounded() is half away from zero.
            val raw = a + (b - a) * fraction
            val rounded = if (raw >= 0) kotlin.math.floor(raw + 0.5) else -kotlin.math.floor(-raw + 0.5)
            val value = rounded.roundToInt().coerceIn(-32_768, 32_767)
            output[2 * index] = (value and 0xFF).toByte()
            output[2 * index + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }

    fun encode(message: JsonObject): String = message.toString()

    // endregion

    // region Server → client

    sealed interface ServerFrame {
        data object SessionUpdated : ServerFrame
        data object ResponseStarted : ServerFrame
        data object ResponseDone : ServerFrame
        data class Error(val message: String) : ServerFrame
        data class OutputTranscriptDone(val text: String) : ServerFrame
        /** The user's words so far in one input item, as a cumulative snapshot. */
        data class InputTranscriptSnapshot(val itemId: String?, val text: String) : ServerFrame
        data class Event(val event: GeminiLiveProtocol.ServerEvent) : ServerFrame
    }

    fun decode(text: String): List<ServerFrame> {
        val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull() ?: return emptyList()
        val type = obj["type"].str ?: return emptyList()
        return when (type) {
            "session.updated" -> listOf(ServerFrame.SessionUpdated)
            "error" -> {
                val error = obj["error"].obj
                listOf(ServerFrame.Error(error?.get("message").str ?: obj["message"].str ?: error?.get("code").str ?: ""))
            }
            "response.created" -> listOf(ServerFrame.ResponseStarted)
            "response.done" -> listOf(ServerFrame.ResponseDone, ServerFrame.Event(GeminiLiveProtocol.ServerEvent.TurnComplete))
            "input_audio_buffer.speech_started" -> listOf(ServerFrame.Event(GeminiLiveProtocol.ServerEvent.Interrupted))
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = obj["transcript"].str?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(ServerFrame.InputTranscriptSnapshot(obj["item_id"].str?.takeIf { it.isNotEmpty() }, transcript))
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                val pcm = obj["delta"].str?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(ServerFrame.Event(GeminiLiveProtocol.ServerEvent.Audio(pcm, OUTPUT_SAMPLE_RATE)))
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                val delta = obj["delta"].str?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(ServerFrame.Event(GeminiLiveProtocol.ServerEvent.OutputTranscription(delta)))
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                val transcript = obj["transcript"].str?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(ServerFrame.OutputTranscriptDone(transcript))
            }
            "response.function_call_arguments.done" -> {
                val call = functionCall(obj) ?: return emptyList()
                // xAI runs its own search tools: their calls aren't Conduit's.
                if (call.name in reservedToolNames) emptyList() else listOf(ServerFrame.Event(GeminiLiveProtocol.ServerEvent.ToolCall(listOf(call))))
            }
            else -> emptyList()
        }
    }

    /**
     * What a cumulative input transcript snapshot adds to the item's last one;
     * null when it adds nothing. Only a snapshot that extends the last one adds words.
     */
    fun inputTranscriptDelta(previous: String, snapshot: String): String? {
        if (snapshot == previous || !snapshot.startsWith(previous)) return null
        return snapshot.substring(previous.length).ifEmpty { null }
    }

    /** `arguments` is a JSON string; Conduit's tools take string values. */
    fun functionCall(obj: JsonObject): GeminiLiveProtocol.FunctionCall? {
        val id = obj["call_id"].str?.takeIf { it.isNotEmpty() } ?: return null
        val name = obj["name"].str?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = obj["arguments"].str?.let { runCatching { ConduitJson.parseToJsonElement(it).obj }.getOrNull() }
        return GeminiLiveProtocol.FunctionCall(id, name, GeminiLiveProtocol.stringArguments(parsed))
    }

    // endregion
}
