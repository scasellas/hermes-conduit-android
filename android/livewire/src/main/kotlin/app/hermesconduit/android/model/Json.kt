package app.hermesconduit.android.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val ConduitJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Lenient accessors: the gateway's payloads vary between versions. */
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray

val JsonElement?.str: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }

/** A string for display: primitives as-is, objects/arrays as JSON. */
val JsonElement?.descriptive: String?
    get() = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> content
        else -> toString()
    }

val JsonElement?.bool: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.booleanOrNull ?: when (p.content.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

val JsonElement?.long: Long?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }

val JsonElement?.double: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull
    }

fun JsonObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key].str?.takeIf { it.isNotBlank() } }

fun JsonObject.firstElement(vararg keys: String): JsonElement? =
    keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it !is JsonNull } }
