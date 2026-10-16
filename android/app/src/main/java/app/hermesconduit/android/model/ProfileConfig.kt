package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A profile setting value as the iOS ProfileSettingValue: bool, text or number. */
sealed interface SettingValue {
    data class Bool(val value: Boolean) : SettingValue
    data class Text(val value: String) : SettingValue
    data class Number(val value: Double) : SettingValue

    val text: String?
        get() = when (this) {
            is Text -> value
            is Number -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
        }
    val bool: Boolean? get() = (this as? Bool)?.value

    fun toJson(): JsonElement = when (this) {
        is Bool -> JsonPrimitive(value)
        is Text -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
    }
}

sealed interface SettingControl {
    data class Toggle(val default: Boolean) : SettingControl
    data class TextToggle(val on: String, val off: String, val default: Boolean) : SettingControl
    data class Options(val options: List<String>, val default: String) : SettingControl
    data class LabeledOptions(val options: List<Pair<String, String>>, val default: String) : SettingControl
    data class Text(val default: String) : SettingControl
    data class Number(val default: Double) : SettingControl
}

data class SettingField(val key: String, val label: String, val help: String, val control: SettingControl)

/** Dotted-key reads and writes on the `/api/config` document. */
object ProfileConfig {
    fun valueAt(config: JsonObject, key: String): SettingValue? {
        var current: JsonElement = config
        for (part in key.split('.')) current = (current as? JsonObject)?.get(part) ?: return null
        val p = current as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        if (p.isString) return SettingValue.Text(p.content)
        p.booleanOrNull?.let { return SettingValue.Bool(it) }
        p.doubleOrNull?.let { return SettingValue.Number(it) }
        return null
    }

    fun withValue(config: JsonObject, key: String, value: SettingValue): JsonObject {
        val parts = key.split('.')
        fun set(obj: JsonObject, index: Int): JsonObject {
            val part = parts[index]
            if (index == parts.lastIndex) return JsonObject(obj + (part to value.toJson()))
            val child = obj[part] as? JsonObject ?: JsonObject(emptyMap())
            return JsonObject(obj + (part to set(child, index + 1)))
        }
        return set(config, 0)
    }
}
