package app.hermesconduit.android.net

import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/** The session is no longer valid (401, a 403 that isn't a permission refusal, or a redirect to /login). */
class AuthExpiredException(message: String = "Your dashboard session has expired.") : IOException(message)

class DashboardHttpException(val code: Int, val detail: String) : IOException(
    if (detail.isNotBlank()) detail else "The dashboard returned HTTP $code.",
)

data class AuthProviders(
    val supportsPassword: Boolean,
    val names: List<String>,
    /** The single native-OAuth provider, when exactly one is offered. */
    val oauthProvider: String? = null,
    val hasOAuthProvider: Boolean = false,
)

data class SessionPage(val sessions: List<SessionSummary>, val hasMore: Boolean, val nextOffset: Int)

data class MessagePage(val messages: List<ChatMessage>, val hasMore: Boolean)

/**
 * REST half of the Hermes dashboard protocol: cookie-based password login,
 * WebSocket tickets, profiles, the session catalog and transcripts.
 */
class DashboardApi(
    baseUrl: String,
    val cookieJar: SessionCookieJar,
    private val cloudflare: CloudflareAccess? = null,
    client: OkHttpClient = OkHttpClient(),
    /** User-defined headers bound to this dashboard; sent over HTTPS only. */
    private val customHeaders: Map<String, String> = emptyMap(),
) {
    /** Native OAuth access token; when set, requests carry `Authorization: Bearer`. */
    @Volatile var bearerToken: String? = null

    val baseUrl: String = ConnectionUrlPolicy.normalizedBaseUrl(baseUrl)
    private val base: HttpUrl = this.baseUrl.toHttpUrl()

    val http: OkHttpClient = client.newBuilder()
        .cookieJar(cookieJar)
        // Redirects are inspected by hand: a hop to /login means signed out.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().header("Accept", "application/json")
            if (request.url.isHttps) {
                cloudflare?.apply(builder)
                CustomHeaders.apply(builder, customHeaders)
            }
            bearerToken?.let { builder.header("Authorization", "Bearer $it") }
            if (request.method != "GET" && request.method != "HEAD") builder.header("Origin", origin(request.url))
            chain.proceed(builder.build())
        }
        .build()

    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    // region Auth

    suspend fun authProviders(): AuthProviders {
        val response = execute(Request.Builder().url(url("api/auth/providers")).get().build(), authenticated = false)
        val providers = response["providers"].arr ?: throw IOException("This server doesn't look like a Hermes dashboard.")
        val oauth = providers.mapNotNull { it.obj }.filter {
            it["supports_password"].bool == false && it["supports_session"].bool != false
        }
        return AuthProviders(
            supportsPassword = providers.any { it.obj?.get("supports_password").bool == true },
            names = providers.mapNotNull { it.obj?.get("name").str },
            oauthProvider = oauth.singleOrNull()?.get("name").str,
            hasOAuthProvider = oauth.isNotEmpty(),
        )
    }

    /** `GET /api/status` → whether `auth_flows` lists `native_pkce`. */
    suspend fun supportsNativeOAuth(): Boolean {
        val status = runCatching { execute(Request.Builder().url(url("api/status")).get().build(), authenticated = false) }.getOrNull()
        return status?.get("auth_flows").arr?.any { it.str == "native_pkce" } == true
    }

    /** Public `/api/status` payload (version, gateway state…). */
    suspend fun status(): JsonObject = execute(Request.Builder().url(url("api/status")).get().build(), authenticated = false)

    suspend fun passwordLogin(username: String, password: String) {
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", username)
            put("password", password)
        }
        val request = Request.Builder().url(url("auth/password-login")).post(body.toRequestBody()).build()
        withContext(Dispatchers.IO) {
            var current = request
            // Follow same-origin redirects by hand so their cookies are kept.
            repeat(MAX_REDIRECTS) {
                http.newCall(current).execute().use { response ->
                    when {
                        response.isRedirect -> {
                            val next = response.header("Location")?.let { current.url.resolve(it) }
                                ?: throw IOException("Sign-in redirected without a destination.")
                            if (origin(next) != origin(base)) throw IOException("Sign-in redirected to another site.")
                            current = Request.Builder().url(next).get().build()
                        }
                        response.code in 401..403 -> throw DashboardHttpException(response.code, "Incorrect username or password.")
                        response.code == 429 -> throw DashboardHttpException(429, "Too many sign-in attempts. Wait a moment and try again.")
                        !response.isSuccessful -> throw DashboardHttpException(response.code, errorDetail(response))
                        else -> {
                            if (cookieJar.isEmpty()) throw IOException("The dashboard didn't start a session. Check the address.")
                            return@withContext
                        }
                    }
                }
            }
            throw IOException("Too many redirects during sign-in.")
        }
    }

    /** Single-use ticket that authenticates the next WebSocket connect. */
    suspend fun webSocketTicket(): String {
        val result = execute(Request.Builder().url(url("api/auth/ws-ticket")).post(ByteArray(0).toRequestBody()).build())
        return result["ticket"].str?.takeIf { it.isNotEmpty() } ?: throw AuthExpiredException()
    }

    // endregion

    suspend fun profiles(): List<String> {
        val result = execute(Request.Builder().url(url("api/profiles")).get().build())
        val names = (result["profiles"].arr ?: result["_array"].arr)?.mapNotNull { it.str ?: it.obj?.get("name").str }.orEmpty()
        return (listOf("default") + names).distinct()
    }

    suspend fun sessions(profile: String, offset: Int = 0, archived: Boolean = false): SessionPage {
        val query = mapOf(
            "limit" to PAGE_SIZE.toString(),
            "offset" to offset.toString(),
            "min_messages" to "1",
            "archived" to if (archived) "only" else "exclude",
            "order" to "recent",
            "exclude_sources" to "cron",
            "profile" to profile,
        )
        val result = execute(Request.Builder().url(url("api/profiles/sessions", query)).get().build())
        val rows = result["sessions"].arr ?: result["_array"].arr ?: JsonArray(emptyList())
        val sessions = MessageNormalizer.sessions(rows)
            .filter { (it.messageCount ?: 1) > 0 }
            .filter { it.profile == null || it.profile == profile }
        val total = result["total"].long
        val next = (result["next_offset"] ?: result["nextOffset"]).long?.toInt() ?: (offset + rows.size)
        val hasMore = (result["has_more"] ?: result["hasMore"]).bool
            ?: (total?.let { offset + rows.size < it } ?: (rows.size == PAGE_SIZE))
        return SessionPage(sessions, hasMore && next > offset, next)
    }

    /** One page of the transcript, newest page first (`offset` counts back from the latest row). */
    suspend fun messages(sessionId: String, profile: String, offset: Int = 0, limit: Int = 120): MessagePage {
        val query = mapOf(
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "order" to "latest",
            "include_compacted" to "true",
            "inline_images" to "false",
            "profile" to profile.takeIf { it != "default" },
        )
        val result = execute(Request.Builder().url(url("api/sessions/$sessionId/messages", query)).get().build())
        val rows = result["messages"].arr ?: result["data"].arr ?: result["_array"].arr ?: JsonArray(emptyList())
        return MessagePage(MessageNormalizer.messages(rows), hasMore = rows.size >= limit)
    }

    suspend fun renameSession(sessionId: String, profile: String, title: String) =
        mutate(sessionId, profile, "PATCH", buildJsonObject { put("title", title) })

    suspend fun setArchived(sessionId: String, profile: String, archived: Boolean) =
        mutate(sessionId, profile, "PATCH", buildJsonObject { put("archived", archived) })

    suspend fun setPinned(sessionId: String, profile: String, pinned: Boolean) =
        mutate(sessionId, profile, "PATCH", buildJsonObject { put("pinned", pinned) })

    suspend fun deleteSession(sessionId: String, profile: String) = mutate(sessionId, profile, "DELETE", null)

    /**
     * Session writes carry a mutation identity read from
     * `/mutation-snapshot`; a stale identity (409) is retried once.
     */
    private suspend fun mutate(sessionId: String, profile: String, method: String, fields: JsonObject?, retry: Boolean = true) {
        val profileQuery = profile.takeIf { it != "default" }
        val identity = mutationIdentity(sessionId, profileQuery)
        val request = if (method == "DELETE") {
            val query = sortedMapOf<String, String?>()
            identity?.forEach { (k, v) -> query[k] = v.str }
            query["profile"] = profileQuery
            Request.Builder().url(url("api/sessions/$sessionId", query)).delete().build()
        } else {
            val body = JsonObject(
                fields.orEmpty() + identity.orEmpty() +
                    (profileQuery?.let { mapOf("profile" to kotlinx.serialization.json.JsonPrimitive(it)) } ?: emptyMap()),
            )
            Request.Builder().url(url("api/sessions/$sessionId", mapOf("profile" to profileQuery)))
                .method(method, body.toRequestBody()).build()
        }
        try {
            execute(request)
        } catch (e: DashboardHttpException) {
            val stale = listOf("revision_conflict", "stale_generation", "mutation_identity_required").any { it in e.detail }
            if (retry && e.code == 409 && stale) return mutate(sessionId, profile, method, fields, retry = false)
            throw e
        }
    }

    private suspend fun mutationIdentity(sessionId: String, profile: String?): Map<String, JsonElement>? {
        val snapshot = try {
            execute(Request.Builder().url(url("api/sessions/$sessionId/mutation-snapshot", mapOf("profile" to profile))).get().build())
        } catch (e: DashboardHttpException) {
            // An older host without the snapshot route takes the bare write.
            if (e.code == 404 && "not found" in e.detail.lowercase() && "session" !in e.detail.lowercase()) return null
            throw e
        }
        return buildMap {
            put("request_id", kotlinx.serialization.json.JsonPrimitive(UUID.randomUUID().toString().lowercase()))
            snapshot["runtime_revision"]?.let { put("expected_revision", it) }
            snapshot["runtime_generation"]?.let { put("expected_generation", it) }
        }
    }

    // region Generic JSON (feature modules)

    /** `GET` a dashboard JSON route; arrays come back as `{"_array": …}`. */
    suspend fun getJson(path: String, query: Map<String, String?> = emptyMap()): JsonObject =
        execute(Request.Builder().url(url(path, query)).get().build())

    /** `POST`/`PUT`/`PATCH`/`DELETE` with an optional JSON body. */
    suspend fun sendJson(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): JsonObject {
        val requestBody = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "DELETE" || method == "GET" -> null
            else -> ByteArray(0).toRequestBody(JSON)
        }
        return execute(Request.Builder().url(url(path, query)).method(method, requestBody).build())
    }

    /** Raw bytes of an authenticated route (files, images). */
    suspend fun getBytes(path: String, query: Map<String, String?> = emptyMap()): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url(path, query)).get().build()).execute().use { response ->
                if (response.code == 401) throw AuthExpiredException()
                if (!response.isSuccessful) throw DashboardHttpException(response.code, errorDetail(response))
                (response.body?.bytes() ?: ByteArray(0)) to response.header("Content-Type")
            }
        }

    // endregion

    // region Plumbing

    private suspend fun execute(request: Request, authenticated: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location").orEmpty()
                if (authenticated && "/login" in location) throw AuthExpiredException()
                if ("cloudflareaccess.com" in location) {
                    throw IOException(
                        if (cloudflare != null) "Cloudflare Access rejected the service token."
                        else "This dashboard is behind Cloudflare Access. Add a service token.",
                    )
                }
                throw IOException("The dashboard redirected to $location.")
            }
            val detail = if (response.isSuccessful) "" else errorDetail(response)
            if (authenticated && response.code == 401) throw AuthExpiredException()
            if (authenticated && response.code == 403 && "permission_denied" !in detail && "profile_mismatch" !in detail) {
                throw AuthExpiredException()
            }
            if (!response.isSuccessful) throw DashboardHttpException(response.code, detail)
            normalize(response.body?.string().orEmpty())
        }
    }

    private fun errorDetail(response: Response): String {
        val text = response.peekBody(64 * 1024).string()
        val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull()
        return obj?.let { it["detail"].str ?: it["error"].str ?: it["message"].str ?: it["detail"]?.toString() } ?: ""
    }

    private fun JsonObject.toRequestBody() = toString().toRequestBody(JSON)

    private fun origin(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        return "${url.scheme}://${url.host}" + if (url.port != defaultPort) ":${url.port}" else ""
    }

    // endregion

    companion object {
        const val PAGE_SIZE = 200
        private const val MAX_REDIRECTS = 5
        private val JSON = "application/json".toMediaType()

        /** Objects pass through; arrays become `{"_array": …}`, scalars `{"value": …}`. */
        fun normalize(text: String): JsonObject {
            if (text.isBlank()) return JsonObject(emptyMap())
            return when (val element = ConduitJson.parseToJsonElement(text)) {
                is JsonObject -> element
                is JsonArray -> JsonObject(mapOf("_array" to element))
                else -> JsonObject(mapOf("value" to element))
            }
        }
    }
}
