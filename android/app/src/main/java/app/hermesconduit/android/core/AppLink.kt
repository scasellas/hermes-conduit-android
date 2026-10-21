package app.hermesconduit.android.core

import android.net.Uri

/** `conduit://session/<id>?profile=…`, `conduit://bot/<profile>`, `conduit://settings/<page>` (iOS ConduitAppLink), plus Android's `conduit://new` and `conduit://voice`. */
sealed interface AppLink {
    data class Session(val id: String, val profile: String?) : AppLink
    data class Bot(val profile: String) : AppLink
    data class Settings(val page: String) : AppLink
    data object NewChat : AppLink
    data object Voice : AppLink
    data object Open : AppLink

    companion object {
        private val token = Regex("^[A-Za-z0-9_\\-.:]{1,128}$")
        private val settingsPages = setOf("calls", "voice", "plugin", "health", "location", "phone")

        fun parse(uri: Uri?, external: Boolean = false): AppLink? {
            if (uri == null || uri.scheme?.lowercase() != "conduit") return null
            val host = uri.host?.lowercase()
            if (host == "new") return NewChat
            if (host == "voice") return Voice
            if (host == "open") return Open
            val id = uri.path?.trim('/')?.takeIf { it.isNotEmpty() && '/' !in it } ?: return null
            val link = when (host) {
                "session" -> Session(id, uri.getQueryParameter("profile")?.trim()?.ifEmpty { null })
                "bot" -> Bot(id)
                "settings" -> if (id.lowercase() in settingsPages) Settings(id.lowercase()) else null
                else -> null
            } ?: return null
            // Links from other apps may only open chats, by plain ids.
            if (external) {
                if (link !is Session || !token.matches(link.id) || (link.profile != null && !token.matches(link.profile))) return null
            }
            return link
        }

        /** Hermes settings link pages → Android routes. */
        fun settingsRoute(page: String) = when (page) {
            "voice" -> "settings.voice"
            "plugin" -> "settings.plugin"
            "health" -> "settings.health"
            "location" -> "settings.location"
            "phone" -> "settings.phone"
            "calls" -> "settings.calls"
            else -> "settings"
        }
    }
}
