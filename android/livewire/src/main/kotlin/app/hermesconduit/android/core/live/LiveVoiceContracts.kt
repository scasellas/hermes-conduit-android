package app.hermesconduit.android.core.live

import app.hermesconduit.android.core.L10n
import java.io.IOException
import java.net.URI

/*
 * What the Gemini Live and Grok Live sessions need from whoever hosts them:
 * the phone's Hermes-hosted clients, or a watch call's tokens and bridge
 * (iOS Shared/GeminiLive, built into both apps). Compiled into the phone app
 * and the Wear OS app (android/livewire), so both run the same sessions.
 */

// region Availability

sealed interface GeminiLiveAvailability {
    data class Available(val model: String) : GeminiLiveAvailability
    /** Reachable, but the host cannot serve Gemini Live (no key, plugin disabled…). */
    data class Unavailable(val reason: String?) : GeminiLiveAvailability
    /** The conduit_push plugin (or its Gemini Live routes) is not installed. */
    data object PluginMissing : GeminiLiveAvailability

    val isAvailable: Boolean get() = this is Available

    /** What Voice settings and the call screen show. */
    val userFacingReason: String?
        get() = when (this) {
            is Available -> null
            is Unavailable -> when {
                reason.isNullOrEmpty() -> L10n.tr("Gemini Live is not available on this Hermes server.")
                reason == "no_api_key" -> L10n.tr("Add a Gemini API key on your Hermes server.")
                else -> L10n.tr("Gemini Live is not available on this Hermes server: %s", reason)
            }
            PluginMissing -> L10n.tr("Install or update the Hermes notifier plugin on your Hermes server.")
        }
}

sealed interface GrokLiveAvailability {
    /** `auth` is "subscription" (SuperGrok) or "api_key". */
    data class Available(val model: String, val voice: String?, val auth: String?) : GrokLiveAvailability
    data class Unavailable(val reason: String?) : GrokLiveAvailability
    data object PluginMissing : GrokLiveAvailability

    val isAvailable: Boolean get() = this is Available

    val userFacingReason: String?
        get() = when (this) {
            is Available -> null
            is Unavailable -> if (reason.isNullOrEmpty()) L10n.tr("Grok Live is not available on this Hermes server.")
            else L10n.tr("Grok Live is not available on this Hermes server: %s", reason)
            PluginMissing -> L10n.tr("Install or update the Hermes notifier plugin on your Hermes server.")
        }
}

// endregion

// region Gemini Live

data class GeminiLiveToken(
    val token: String,
    val expiresAtMs: Long?,
    val newSessionExpiresAtMs: Long?,
    val model: String,
    val webSocketUrl: String,
) {
    /** The URL to open: the plugin's WebSocket URL with the single-use token as `access_token` (kept if already there). */
    val connectUrl: String
        get() {
            val uri = runCatching { URI(webSocketUrl) }.getOrNull() ?: return webSocketUrl
            val query = uri.rawQuery.orEmpty()
            if (query.split('&').any { it.substringBefore('=') == "access_token" }) return webSocketUrl
            val encoded = java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20")
            val joined = if (query.isEmpty()) "access_token=$encoded" else "$query&access_token=$encoded"
            return URI(uri.scheme, uri.rawAuthority, uri.rawPath, null, null).toString() + "?" + joined +
                (uri.rawFragment?.let { "#$it" } ?: "")
        }
}

sealed class GeminiLiveTokenException(message: String) : IOException(message) {
    class Unavailable(val availability: GeminiLiveAvailability) : GeminiLiveTokenException(availability.userFacingReason.orEmpty())
    class Malformed : GeminiLiveTokenException(L10n.tr("Hermes returned an invalid Gemini Live token."))
}

interface GeminiLiveTokenProviding {
    suspend fun availability(): GeminiLiveAvailability
    /** A fresh single-use token. Once per WebSocket connection, resumptions included. */
    suspend fun freshToken(): GeminiLiveToken
}

// endregion

// region Grok Live

sealed class GrokLiveClientException(message: String) : IOException(message) {
    class Unavailable(val availability: GrokLiveAvailability) : GrokLiveClientException(availability.userFacingReason.orEmpty())
    class SocketUnavailable : GrokLiveClientException(L10n.tr("Could not open the Grok Live connection."))
}

/** One relay connection's upgrade: the URL (with a fresh ticket) and headers the dashboard needs. */
data class LiveSocketRequest(val url: String, val headers: Map<String, String> = emptyMap())

interface GrokLiveConnecting {
    suspend fun availability(): GrokLiveAvailability
    /** The upgrade request for one relay connection, with a fresh single-use ticket. */
    suspend fun socketRequest(): LiveSocketRequest
}

// endregion

/** A message for the screen from any error: its own message, never a URL with a token. */
fun liveErrorMessage(error: Throwable): String {
    val message = error.message?.takeIf { it.isNotBlank() } ?: return L10n.tr("Something went wrong.")
    return LiveErrorText.cleaned(message)
}

/** An error's text without URLs (a connection URL can carry a live credential), one line, capped. */
object LiveErrorText {
    fun cleaned(raw: String): String {
        var text = raw
        for (pattern in listOf(",?\\s*(?<![\\w-])url=(['\"]).*?\\1", "\\b(?:wss?|https?)://\\S+")) {
            text = Regex(pattern).replace(text, "")
        }
        text = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.length <= 240) return text.ifEmpty { L10n.tr("Hermes speech stream failed.") }
        return text.take(240) + "…"
    }
}
