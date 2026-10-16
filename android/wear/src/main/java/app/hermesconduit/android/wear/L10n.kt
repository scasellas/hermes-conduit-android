package app.hermesconduit.android.wear

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * A small copy of the phone app's lookup: strings keyed by their English
 * text, as in the iOS Watch app's String Catalog (`assets/l10n/<lang>.json`
 * is generated from ConduitWatch/Localizable.xcstrings by
 * scripts/gen-l10n.py). Follows the watch's language; missing keys fall
 * back to English.
 */
object L10n {
    private val shipped = setOf("de", "es", "fr", "id", "it", "ja", "ko", "pl", "pt-BR", "ru", "tr", "zh-Hans", "zh-Hant")
    @Volatile private var table: Map<String, String> = emptyMap()

    fun load(context: Context) {
        val id = language(Locale.getDefault())
        table = if (id == "en") emptyMap() else runCatching {
            context.assets.open("l10n/$id.json").bufferedReader().use { reader ->
                (Json.parseToJsonElement(reader.readText()) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            }
        }.getOrDefault(emptyMap())
    }

    private fun language(locale: Locale): String {
        val lang = locale.language
        return when {
            lang == "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            lang == "pt" -> "pt-BR"
            lang == "in" -> "id"
            lang in shipped -> lang
            else -> "en"
        }
    }

    fun tr(key: String): String = table[key] ?: key

    fun tr(key: String, vararg args: Any?): String =
        runCatching { String.format(tr(key), *args) }.getOrElse { String.format(key, *args) }
}

/** Composable-friendly alias, as `t()` in the phone app. */
fun t(key: String): String = L10n.tr(key)

fun t(key: String, vararg args: Any?): String = L10n.tr(key, *args)
