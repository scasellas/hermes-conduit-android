package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** A live voice mode, as consent settings store it (iOS `LiveVoiceModeName`). */
enum class LiveVoiceMode(val displayName: String, val storageID: String, val company: String) {
    GEMINI_LIVE("Gemini Live", "gemini-live", "Google"),
    GPT_LIVE("GPT-Live", "gpt-live", "OpenAI"),
    GROK_LIVE("Grok Live", "grok-live", "xAI");

    companion object {
        fun of(storageID: String): LiveVoiceMode? = entries.firstOrNull { it.storageID == storageID }
    }
}

/**
 * A tool a live voice model calls directly (iOS `VoiceHostTool`): the
 * host's `conduit_health`, `conduit_location`, `conduit_calendar`…, read
 * through the plugin's sealed `/voice-tools` route, or one of the phone's own
 * (calendar changes, maps). `key` says which agreed key seals its calls.
 */
data class VoiceHostTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val key: String,
) {
    private val properties: Map<String, JsonObject>
        get() = parameters["properties"].obj?.mapNotNull { (k, v) -> v.obj?.let { k to it } }?.toMap().orEmpty()

    /** The call's arguments in the host schema's JSON types. Names the schema doesn't have are dropped. */
    fun arguments(values: JsonObject): JsonObject = buildJsonObject {
        for ((name, value) in values) {
            val property = properties[name] ?: continue
            convert(value, property)?.let { put(name, it) }
        }
    }

    /** "Health" for conduit_health: the word GPT-Live delegates with. */
    val marker: String
        get() {
            val bare = name.removePrefix("conduit_")
            val words = bare.split('_').filter { it.isNotEmpty() }
            val first = words.firstOrNull() ?: return name
            return (listOf(first.replaceFirstChar { it.uppercase() }) + words.drop(1)).joinToString(" ")
        }

    companion object {
        const val MAXIMUM_RESULT_CHARACTERS = 12_000
        const val ANSWER_NOTE = "This is the user's own data, shared from their iPhone. Answer from it by ear: the numbers that matter, rounded, and how fresh it is when that matters. Never send it anywhere else."

        /** The tools in a `GET /voice-tools` answer. */
        fun tools(response: JsonObject): List<VoiceHostTool> = response["tools"].arr.orEmpty().mapNotNull { entry ->
            val o = entry.obj ?: return@mapNotNull null
            val name = o["name"].str?.takeIf(::isToolName) ?: return@mapNotNull null
            val description = o["description"].str ?: return@mapNotNull null
            val parameters = o["parameters"].obj ?: return@mapNotNull null
            val key = o["key"].str ?: return@mapNotNull null
            VoiceHostTool(name, description, parameters, key)
        }

        fun isToolName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 64 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

        /** One value in the JSON type its property declares, or null when it can't be. */
        fun convert(value: JsonElement, property: JsonObject): JsonElement? {
            val type = property["type"].str?.lowercase() ?: "string"
            val primitive = value as? JsonPrimitive
            if (value is JsonNull) return null
            if (primitive == null || !primitive.isString) {
                // Real lists and numbers (GPT-Live's JSON) meet the same rules as text.
                return when {
                    type == "integer" && primitive != null && primitive.booleanOrNull == null ->
                        primitive.doubleOrNull?.takeIf { it == Math.rint(it) }?.let { JsonPrimitive(it.toLong()) }
                    type == "number" && primitive != null && primitive.booleanOrNull == null -> primitive.doubleOrNull?.let(::JsonPrimitive)
                    type == "boolean" && primitive?.booleanOrNull != null -> JsonPrimitive(primitive.booleanOrNull!!)
                    type == "array" && value is JsonArray -> value
                    type == "object" && value is JsonObject -> value
                    type == "string" && primitive != null && primitive.booleanOrNull == null && primitive.doubleOrNull != null -> JsonPrimitive(primitive.content)
                    type == "string" && value is JsonArray && property["list"].bool == true -> value
                    else -> null
                }
            }
            val trimmed = primitive.content.trim()
            return when (type) {
                "array" -> {
                    if (trimmed.startsWith("[")) runCatching { ConduitJson.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()?.let { return it }
                    val items = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (items.isEmpty()) null else JsonArray(items.map(::JsonPrimitive))
                }
                "integer" -> trimmed.toLongOrNull()?.let(::JsonPrimitive)
                    ?: trimmed.toDoubleOrNull()?.takeIf { it == Math.rint(it) }?.let { JsonPrimitive(it.toLong()) }
                "number" -> trimmed.toDoubleOrNull()?.let(::JsonPrimitive)
                "boolean" -> when (trimmed.lowercase()) {
                    "true", "1", "yes" -> JsonPrimitive(true)
                    "false", "0", "no" -> JsonPrimitive(false)
                    else -> null
                }
                "object" -> runCatching { ConduitJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                else -> if (trimmed.isEmpty()) null else JsonPrimitive(trimmed)
            }
        }

        /** What the model gets back: `result` (JSON text) and `note`, or `error`. Not UI copy. */
        fun modelResult(result: JsonElement): Map<String, String> {
            val wrapped = if (result is JsonObject || result is JsonArray) result else buildJsonObject { put("value", result) }
            val text = CanonicalJson.encode(wrapped)
            if (text.length > MAXIMUM_RESULT_CHARACTERS) return mapOf("error" to "That answer is too long for a call. Ask for fewer days or metrics.")
            return mapOf("result" to text, "note" to ANSWER_NOTE)
        }
    }
}
