package app.hermesconduit.android.core.live

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The authenticated dashboard request the live clients use, injected so
 * tests can script responses: `(method, path, query, body, timeoutMs)`.
 * Production binds it to `DashboardApi.getJson`/`sendJson`.
 */
typealias LiveDashboardRequest = suspend (method: String, path: String, query: Map<String, String?>, body: JsonObject?, timeoutMs: Long) -> JsonObject

/** A route an older notifier plugin doesn't have. */
fun isMissingRoute(error: Throwable): Boolean = error is DashboardHttpException && (error.code == 404 || error.code == 410)

/** `?profile=` the way DashboardPath.withProfile scopes it: only for a named profile. */
fun profileQuery(profile: String): Map<String, String?> = mapOf("profile" to profile.takeIf { it != "default" && it.isNotEmpty() })

const val PLUGIN_BASE = "api/plugins/conduit_push"

// region Availability

sealed interface GPTLiveAvailability {
    data class Available(val model: String, val voice: String?) : GPTLiveAvailability
    data class Unavailable(val reason: String?) : GPTLiveAvailability
    data object PluginMissing : GPTLiveAvailability

    val isAvailable: Boolean get() = this is Available

    val userFacingReason: String?
        get() = when (this) {
            is Available -> null
            is Unavailable -> if (reason.isNullOrEmpty()) L10n.tr("GPT-Live is not available on this Hermes server.")
            else L10n.tr("GPT-Live is not available on this Hermes server: %s", reason)
            PluginMissing -> L10n.tr("Install or update the Hermes notifier plugin on your Hermes server.")
        }
}

/** What a live voice mode's host check found wrong. */
enum class LiveVoiceHostIssue {
    /** The Hermes notifier plugin (or its routes for this mode) is missing. */
    PLUGIN_MISSING,
    /** The host has the plugin but can't run the mode (no key, no sign-in). */
    NOT_SET_UP;

    companion object {
        fun of(availability: Any?): LiveVoiceHostIssue? = when (availability) {
            GeminiLiveAvailability.PluginMissing, GrokLiveAvailability.PluginMissing, GPTLiveAvailability.PluginMissing -> PLUGIN_MISSING
            is GeminiLiveAvailability.Unavailable, is GrokLiveAvailability.Unavailable, is GPTLiveAvailability.Unavailable -> NOT_SET_UP
            else -> null
        }

        fun of(error: Throwable): LiveVoiceHostIssue? = when (error) {
            is GeminiLiveTokenException.Unavailable -> of(error.availability)
            is GPTLiveClientException.Unavailable -> of(error.availability)
            is GrokLiveClientException.Unavailable -> of(error.availability)
            else -> null
        }
    }
}

// endregion

// region Gemini Live

/** The host's memory for a live conversation, and whether its memory provider can be searched. */
data class GeminiLiveMemoryContext(val text: String, val canRecall: Boolean)

data class GeminiLiveWebResult(val title: String, val url: String, val snippet: String)

/** A lookup or recall the host couldn't answer. Its text goes to the model, not the screen. */
class LiveLookupException(reason: String) : IOException(reason)

interface GeminiLiveWebSearching {
    suspend fun webSearch(query: String): List<GeminiLiveWebResult>
}

interface GeminiLiveMemoryRecalling {
    suspend fun recallMemory(query: String): String
}

/**
 * Gemini Live credentials come from the Hermes host, never the phone: the
 * conduit_push plugin keeps GEMINI_API_KEY and mints a single-use Google
 * ephemeral token per WebSocket connection. Also the host's web search,
 * memory and persona routes the live modes share.
 */
class GeminiLiveTokenClient(
    private val profile: () -> String,
    private val request: LiveDashboardRequest,
) : GeminiLiveTokenProviding, GeminiLiveWebSearching, GeminiLiveMemoryRecalling {
    private suspend fun fetch(method: String, path: String, body: JsonObject? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
        request(method, path, profileQuery(profile()), body, timeoutMs)

    override suspend fun availability(): GeminiLiveAvailability = try {
        availability(fetch("GET", STATUS_PATH))
    } catch (e: Exception) {
        if (isMissingRoute(e)) GeminiLiveAvailability.PluginMissing else throw e
    }

    override suspend fun freshToken(): GeminiLiveToken {
        val response = try {
            fetch("POST", TOKEN_PATH, JsonObject(emptyMap()))
        } catch (e: Exception) {
            if (isMissingRoute(e)) throw GeminiLiveTokenException.Unavailable(GeminiLiveAvailability.PluginMissing)
            throw e
        }
        return token(response)
    }

    /** Whether the host can answer quick lookups with its own backend. Any failure is false. */
    suspend fun webSearchAvailable(): Boolean = runCatching {
        val response = fetch("GET", WEB_SEARCH_STATUS_PATH)
        response["ok"].bool == true && response["available"].bool == true
    }.getOrDefault(false)

    override suspend fun webSearch(query: String): List<GeminiLiveWebResult> =
        webResults(fetch("POST", WEB_SEARCH_PATH, buildJsonObject { put("query", query); put("limit", WEB_SEARCH_LIMIT) }, WEB_SEARCH_TIMEOUT_MS))

    /** The host's memory for a new conversation; null when there is none or the plugin predates the route. */
    suspend fun memoryContext(): GeminiLiveMemoryContext? = runCatching { memoryContext(fetch("GET", MEMORY_CONTEXT_PATH)) }.getOrNull()

    /** The profile's SOUL.md persona for a new conversation; null when it has none. */
    suspend fun personality(): String? = runCatching { personality(fetch("GET", PERSONALITY_PATH)) }.getOrNull()

    override suspend fun recallMemory(query: String): String =
        memoryRecall(fetch("POST", MEMORY_RECALL_PATH, buildJsonObject { put("query", query) }, MEMORY_RECALL_TIMEOUT_MS))

    companion object {
        const val STATUS_PATH = "$PLUGIN_BASE/gemini-live/status"
        const val TOKEN_PATH = "$PLUGIN_BASE/gemini-live/token"
        const val WEB_SEARCH_STATUS_PATH = "$PLUGIN_BASE/web-search/status"
        const val WEB_SEARCH_PATH = "$PLUGIN_BASE/web-search"
        const val MEMORY_CONTEXT_PATH = "$PLUGIN_BASE/memory/context"
        const val MEMORY_RECALL_PATH = "$PLUGIN_BASE/memory/recall"
        const val PERSONALITY_PATH = "$PLUGIN_BASE/personality"
        const val WEB_SEARCH_LIMIT = 3
        const val DEFAULT_TIMEOUT_MS = 12_000L
        /** Past the host's own 20 s search timeout, so a slow backend ends in the host's answer. */
        const val WEB_SEARCH_TIMEOUT_MS = 25_000L
        /** Past the host's 12 s bound on a memory recall. */
        const val MEMORY_RECALL_TIMEOUT_MS = 15_000L
        const val MEMORY_CONTEXT_LIMIT = 8_000
        const val MEMORY_RECALL_LIMIT = 4_000
        const val PERSONALITY_LIMIT = 8_000

        private fun reason(response: JsonObject, vararg keys: String) = keys.firstNotNullOfOrNull { response[it].str }

        fun availability(response: JsonObject): GeminiLiveAvailability {
            val reason = reason(response, "reason", "error")
            if (response["ok"].bool != true || response["available"].bool != true) return GeminiLiveAvailability.Unavailable(reason)
            return GeminiLiveAvailability.Available(response["model"].str?.takeIf { it.isNotEmpty() } ?: GeminiLiveProtocol.MODEL)
        }

        fun token(response: JsonObject): GeminiLiveToken {
            if (response["ok"].bool != true) {
                throw GeminiLiveTokenException.Unavailable(GeminiLiveAvailability.Unavailable(reason(response, "reason", "error")))
            }
            val token = response["token"].str?.takeIf { it.isNotEmpty() } ?: throw GeminiLiveTokenException.Malformed()
            val url = response["websocket_url"].str ?: throw GeminiLiveTokenException.Malformed()
            val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "wss") throw GeminiLiveTokenException.Malformed()
            return GeminiLiveToken(
                token = token,
                expiresAtMs = dateMs(response["expires_at"]),
                newSessionExpiresAtMs = dateMs(response["new_session_expires_at"]),
                model = response["model"].str?.takeIf { it.isNotEmpty() } ?: GeminiLiveProtocol.MODEL,
                webSocketUrl = url,
            )
        }

        fun memoryContext(response: JsonObject): GeminiLiveMemoryContext? {
            if (response["ok"].bool != true || response["available"].bool != true) return null
            val text = response["context"].str.orEmpty().trim().take(MEMORY_CONTEXT_LIMIT)
            val canRecall = response["recall"].bool == true
            if (text.isEmpty() && !canRecall) return null
            return GeminiLiveMemoryContext(text, canRecall)
        }

        fun personality(response: JsonObject): String? {
            if (response["ok"].bool != true || response["available"].bool != true) return null
            return response["text"].str.orEmpty().trim().take(PERSONALITY_LIMIT).ifEmpty { null }
        }

        fun memoryRecall(response: JsonObject): String {
            val results = response["results"].str
            if (response["ok"].bool != true || response["available"].bool == false || results == null) {
                throw LiveLookupException(reason(response, "detail", "error", "reason")?.takeIf { it.isNotEmpty() } ?: "Hermes memory is not available")
            }
            return results.trim().take(MEMORY_RECALL_LIMIT)
        }

        fun webResults(response: JsonObject): List<GeminiLiveWebResult> {
            val items = response["results"] as? JsonArray
            if (response["ok"].bool != true || items == null) {
                throw LiveLookupException(reason(response, "detail", "error", "reason")?.takeIf { it.isNotEmpty() } ?: "The Hermes web search returned no results")
            }
            return items.mapNotNull { it.obj }.mapNotNull { item ->
                val url = item["url"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                GeminiLiveWebResult(item["title"].str.orEmpty(), url, item["snippet"].str.orEmpty())
            }
        }

        /** ISO 8601 (with or without fractional seconds) or epoch seconds. */
        fun dateMs(value: JsonElement?): Long? {
            val p = value as? JsonPrimitive ?: return null
            if (!p.isString) return p.double?.let { (it * 1000).toLong() }
            val text = p.content.ifEmpty { return null }
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        }
    }
}

// endregion

// region Grok Live

/**
 * Grok Live runs through the Hermes host: the conduit_push plugin opens
 * xAI's realtime socket with the host's SuperGrok sign-in (or XAI_API_KEY)
 * and relays Conduit's events both ways. The socket is opened with the same
 * single-use dashboard ticket the speech stream uses.
 */
class GrokLiveClient(
    private val profile: () -> String,
    private val request: LiveDashboardRequest,
    private val mintTicket: suspend () -> String,
    /** Builds the relay's upgrade request for this path and query (ticket and profile). */
    private val makeSocketRequest: (path: String, query: Map<String, String>) -> LiveSocketRequest,
) : GrokLiveConnecting {
    override suspend fun availability(): GrokLiveAvailability = try {
        availability(request("GET", STATUS_PATH, profileQuery(profile()), null, 30_000))
    } catch (e: Exception) {
        if (isMissingRoute(e)) GrokLiveAvailability.PluginMissing else throw e
    }

    override suspend fun socketRequest(): LiveSocketRequest {
        val ticket = mintTicket()
        return makeSocketRequest(SOCKET_PATH, mapOf("ticket" to ticket, "profile" to profile()))
    }

    companion object {
        const val STATUS_PATH = "$PLUGIN_BASE/grok-live/status"
        const val SOCKET_PATH = "$PLUGIN_BASE/grok-live/socket"

        fun availability(response: JsonObject): GrokLiveAvailability {
            val reason = response["reason"].str ?: response["detail"].str ?: response["error"].str
            if (response["ok"].bool != true || response["available"].bool != true) return GrokLiveAvailability.Unavailable(reason)
            return GrokLiveAvailability.Available(
                model = response["model"].str?.takeIf { it.isNotEmpty() } ?: GrokLiveProtocol.DEFAULT_MODEL,
                voice = response["voice"].str?.takeIf { it.isNotEmpty() },
                auth = response["auth"].str?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

// endregion

// region GPT-Live

/** The host's answer to Conduit's offer. */
data class GPTLiveSessionAnswer(
    val sessionId: String?,
    val sdp: String,
    /** The voice the host started the call with; null from an older plugin. */
    val voice: String? = null,
    /** The host put Conduit's briefing into the session's instructions. */
    val briefingApplied: Boolean = false,
    /** The host made the model speak first. */
    val greetingApplied: Boolean = false,
    val billing: GPTLiveBilling = GPTLiveBilling.SUBSCRIPTION,
)

sealed class GPTLiveClientException(message: String) : IOException(message) {
    class Unavailable(val availability: GPTLiveAvailability) : GPTLiveClientException(availability.userFacingReason.orEmpty())
    /** The host refused or failed the exchange; its own message, shown as is. */
    class Host(detail: String) : GPTLiveClientException(detail)
    class Malformed : GPTLiveClientException(L10n.tr("Hermes returned an invalid GPT-Live answer."))
    class NotSubscription : GPTLiveClientException(L10n.tr("Hermes did not start GPT-Live on the ChatGPT subscription, so Conduit did not connect."))
    class NotApiKey : GPTLiveClientException(L10n.tr("Hermes did not start GPT-Live on the API key, so Conduit did not connect. Update the Hermes notifier plugin."))
}

interface GPTLiveSessionProviding {
    suspend fun availability(): GPTLiveAvailability
    /** Exchanges a WebRTC offer for GPT-Live's answer. */
    suspend fun createSession(offer: String, history: List<JsonObject>, voice: String?, briefing: String?, greeting: String?, callOpening: Boolean): GPTLiveSessionAnswer
}

/**
 * GPT-Live starts on the Hermes host, never the phone: the conduit_push
 * plugin holds the host's Codex (ChatGPT) sign-in, exchanges Conduit's
 * WebRTC offer for GPT-Live's answer, and bills the ChatGPT subscription.
 * A profile can instead choose the host's OpenAI API key; the host answers
 * with the path it used and a call on any other path is dropped. Neither
 * path ever falls back to the other.
 */
class GPTLiveClient(
    private val profile: () -> String,
    private val billing: () -> GPTLiveBilling,
    private val request: LiveDashboardRequest,
) : GPTLiveSessionProviding {
    override suspend fun availability(): GPTLiveAvailability = availability(billing())

    suspend fun availability(billing: GPTLiveBilling): GPTLiveAvailability = try {
        availability(request("GET", STATUS_PATH, profileQuery(profile()), null, 12_000), billing)
    } catch (e: Exception) {
        if (isMissingRoute(e)) GPTLiveAvailability.PluginMissing else throw e
    }

    override suspend fun createSession(offer: String, history: List<JsonObject>, voice: String?, briefing: String?, greeting: String?, callOpening: Boolean): GPTLiveSessionAnswer {
        val billing = billing()
        val body = buildJsonObject {
            put("sdp", offer)
            // Always named, the subscription too: the host bills only the path asked for.
            put("billing", billing.raw)
            if (!briefing.isNullOrEmpty()) put("briefing", briefing)
            if (greeting != null) put("greeting", greeting)
            if (callOpening) put("call_opening", true)
            if (history.isNotEmpty()) put("history", JsonArray(history))
            if (!voice.isNullOrEmpty()) put("voice", voice)
        }
        val response = try {
            request("POST", SESSION_PATH, profileQuery(profile()), body, SESSION_TIMEOUT_MS)
        } catch (e: DashboardHttpException) {
            if (isMissingRoute(e)) throw GPTLiveClientException.Unavailable(GPTLiveAvailability.PluginMissing)
            // The host's detail already names the problem and says no API fallback was used.
            if (e.detail.isNotEmpty()) throw GPTLiveClientException.Host(e.detail)
            throw e
        }
        return answer(response, billing)
    }

    companion object {
        const val STATUS_PATH = "$PLUGIN_BASE/gpt-live/status"
        const val SESSION_PATH = "$PLUGIN_BASE/gpt-live/session"
        /** The host gives OpenAI 30 s and itself 32 s: its own timeout message is the one the user sees. */
        const val SESSION_TIMEOUT_MS = 40_000L

        fun availability(response: JsonObject, billing: GPTLiveBilling = GPTLiveBilling.SUBSCRIPTION): GPTLiveAvailability {
            if (billing == GPTLiveBilling.API) return apiAvailability(response)
            val reason = response["reason"].str ?: response["detail"].str ?: response["error"].str
            if (response["ok"].bool != true || response["available"].bool != true) return GPTLiveAvailability.Unavailable(reason)
            // Only the subscription route is ever used.
            if (response["auth"].str != "subscription") return GPTLiveAvailability.Unavailable(reason)
            return GPTLiveAvailability.Available(
                response["model"].str?.takeIf { it.isNotEmpty() } ?: "gpt-live",
                response["voice"].str?.takeIf { it.isNotEmpty() },
            )
        }

        /** The API-key path's readiness, which the host reports under `api`. */
        fun apiAvailability(response: JsonObject): GPTLiveAvailability {
            if (response["ok"].bool != true) {
                return GPTLiveAvailability.Unavailable(response["reason"].str ?: response["detail"].str ?: response["error"].str)
            }
            val api = response["api"].obj
                ?: return GPTLiveAvailability.Unavailable(L10n.tr("GPT-Live on an API key needs the Hermes notifier plugin 0.22 or newer."))
            if (api["available"].bool != true || api["auth"].str != GPTLiveBilling.API.raw) return GPTLiveAvailability.Unavailable(api["reason"].str)
            return GPTLiveAvailability.Available(
                api["model"].str?.takeIf { it.isNotEmpty() } ?: "gpt-live-1",
                api["voice"].str?.takeIf { it.isNotEmpty() },
            )
        }

        fun answer(response: JsonObject, billing: GPTLiveBilling = GPTLiveBilling.SUBSCRIPTION): GPTLiveSessionAnswer {
            if (response["ok"].bool != true) {
                val detail = response["detail"].str ?: response["reason"].str ?: response["error"].str
                if (!detail.isNullOrEmpty()) throw GPTLiveClientException.Host(detail)
                throw GPTLiveClientException.Malformed()
            }
            // The path the host billed must be the one asked for, or nothing connects.
            if (response["auth"].str != billing.raw) {
                throw if (billing == GPTLiveBilling.SUBSCRIPTION) GPTLiveClientException.NotSubscription() else GPTLiveClientException.NotApiKey()
            }
            val transport = response["transport"].obj
            val sdp = transport?.get("sdp").str
            if (transport?.get("type").str != "webrtc" || sdp == null || !sdp.startsWith("v=0")) throw GPTLiveClientException.Malformed()
            return GPTLiveSessionAnswer(
                sessionId = response["session"].obj?.get("id").str,
                sdp = sdp,
                voice = response["voice"].str?.takeIf { it.isNotEmpty() },
                briefingApplied = response["briefing_applied"].bool == true,
                greetingApplied = response["greeting_applied"].bool == true,
                billing = billing,
            )
        }

        /** The short label the call screen and Settings show, so a call always says which path it's on. */
        fun badge(billing: GPTLiveBilling): String = when (billing) {
            GPTLiveBilling.SUBSCRIPTION -> L10n.tr("ChatGPT plan")
            GPTLiveBilling.API -> L10n.tr("API key · billed per minute")
        }
    }
}

// endregion
