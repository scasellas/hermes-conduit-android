package app.hermesconduit.android.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Runtime localization keyed by English text, like the iOS String Catalogs
 * (`assets/l10n/<lang>.json` is generated from them by scripts/gen-l10n.py).
 * Missing keys fall back to English.
 */
object L10n {
    /** Shipped languages: identifier → its own name. */
    val languages = linkedMapOf(
        "en" to "English",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
        "id" to "Bahasa Indonesia",
        "it" to "Italiano",
        "ja" to "日本語",
        "ko" to "한국어",
        "pl" to "Polski",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文",
    )

    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile var language: String = "en"
        private set

    /** Resolves "system" (or an explicit identifier) and loads its table. */
    fun load(context: Context, preference: String?) {
        val id = if (preference.isNullOrEmpty() || preference == "system") systemLanguage() else preference
        language = id
        table = if (id == "en") emptyMap() else runCatching {
            context.assets.open("l10n/$id.json").bufferedReader().use { reader ->
                (Json.parseToJsonElement(reader.readText()) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            }
        }.getOrDefault(emptyMap())
    }

    fun systemLanguage(locale: Locale = Locale.getDefault()): String {
        val lang = locale.language
        return when {
            lang == "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            lang == "pt" -> "pt-BR"
            lang == "in" -> "id"
            languages.containsKey(lang) -> lang
            else -> "en"
        }
    }

    fun tr(key: String): String = table[key] ?: key

    fun tr(key: String, vararg args: Any?): String = runCatching { String.format(tr(key), *args) }.getOrElse { String.format(key, *args) }
}

/** Bumped when the language changes so text recomposes. */
val LocalLanguage = staticCompositionLocalOf { "en" }

@Composable
@ReadOnlyComposable
fun t(key: String): String {
    LocalLanguage.current
    return L10n.tr(key)
}

@Composable
@ReadOnlyComposable
fun t(key: String, vararg args: Any?): String {
    LocalLanguage.current
    return L10n.tr(key, *args)
}
