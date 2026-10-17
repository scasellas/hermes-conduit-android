package app.hermesconduit.wearwire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * A watch call's tools through the push relay, over the watch's own internet
 * (port of the iOS `WatchToolRelayClient`): web_search and recall_memory
 * while the phone can't be reached, chat search and reading, Hermes jobs
 * when the grant carries them, and a fresh Gemini token when it carries
 * live_token. Each call is sealed with the call's grant ([WatchToolSeal]),
 * held open on the relay until Hermes answers, and opened here. Anything
 * that doesn't get an answer falls back to the phone's path.
 */
class WatchToolRelayClient private constructor(
    grant: WatchVoiceWire.DirectToolGrant,
    private val keys: WatchToolSeal.Keys,
    private val base: String,
    private val http: OkHttpClient,
    private val now: () -> Long,
) {
    sealed class Outcome {
        /** Hermes' answer, as its route would give it. */
        data class Answered(val body: JsonObject) : Outcome()
        /** Hermes took the call but didn't answer within the relay's wait. */
        data object TimedOut : Outcome()
        /**
         * Not answered this way; [grantGone] when the grant can't be used
         * again (closed, expired, spent), [sent] when the call went out,
         * which spends one of the grant's calls.
         */
        data class Unavailable(val reason: String, val grantGone: Boolean, val sent: Boolean) : Outcome()

        val logLabel: String
            get() = when (this) {
                is Answered -> "answered"
                TimedOut -> "timedOut"
                is Unavailable -> "unavailable"
            }
    }

    val grantId: String = grant.grantId
    val tools: Set<String>
    val expiresAtMs: Long? = grant.expiresAtMs
    /** Jobs one call may start this way; 0 without jobs. */
    val maxJobs: Int
    /** The model may answer approvals (the user's setting on the phone). */
    val voiceApprovals: Boolean
    /** The user's profiles a job may name, where the host runs jobs on others; null otherwise. */
    val jobProfiles: List<WatchVoiceWire.JobProfileName>?
    /** Those others, lowercased. */
    val hostJobProfiles: Set<String> = grant.hostJobProfiles.orEmpty().map { it.lowercase() }.toSet()
    private val watchKey = grant.watchKey
    private val maxCalls = grant.maxCalls
    private val grantUrl = base.trimEnd('/') + "/v1/watch-tools/grants/${grant.grantId}"

    @Volatile var callsSent = 0
        private set
    @Volatile var isGone = false
        private set
    private var inFlight = 0
    private var closing = false
    private var closeSent = false

    init {
        val granted = grant.tools.toSet()
        val jobs = granted.containsAll(WatchJobAnswer.tools + WatchJobAnswer.calls)
        tools = granted.intersect(
            WatchToolAnswer.tools +
                (if (jobs) WatchJobAnswer.tools + WatchJobAnswer.calls + WatchJobAnswer.INTERRUPT_JOB else emptySet()) +
                WatchLiveToken.TOOL,
        )
        maxJobs = if (jobs) maxOf(0, grant.maxJobs ?: 0) else 0
        voiceApprovals = jobs && grant.voiceApprovals == true
        jobProfiles = if (jobs && grant.hostJobProfiles != null) grant.jobProfiles else null
    }

    /** Where a start_job for [instructions] goes, when the host runs jobs on the user's other profiles. */
    fun jobRoute(instructions: String, spokenProfile: String?): WatchJobProfiles.RelayRoute? {
        val names = jobProfiles ?: return null
        return WatchJobProfiles.relayRoute(instructions, spokenProfile, names, hostJobProfiles)
    }

    val hasJobs: Boolean get() = maxJobs > 0

    /** Calls the grant still allows; the watch renews it before they run out. */
    val callsLeft: Int get() = maxOf(0, maxCalls - callsSent)

    /** Whether [name] can go this way now. */
    fun canRun(name: String) = !isGone && name in tools && callsSent < maxCalls && !expiresWithin(EXPIRY_MARGIN_MS)

    fun expiresWithin(marginMs: Long): Boolean = expiresAtMs?.let { it - now() < marginMs } ?: false

    /** A lookup, asked as the phone's client would ask its route. */
    suspend fun run(name: String, query: String): Outcome = run(WatchToolAnswer.request(name, query), name)

    /** A job call: [arguments] as the host's job call takes them. */
    suspend fun run(name: String, arguments: JsonObject): Outcome = run(buildJsonObject { put("tool", name); put("args", arguments) }, name)

    private suspend fun run(call: JsonObject, name: String): Outcome {
        if (name !in tools) return Outcome.Unavailable("notGranted", grantGone = false, sent = false)
        // Closed or spent ends it; one that expires soon isn't gone, it's skipped until the phone renews it.
        if (isGone) return Outcome.Unavailable("grantEnded", grantGone = true, sent = false)
        if (callsSent >= maxCalls) {
            isGone = true
            return Outcome.Unavailable("grantSpent", grantGone = true, sent = false)
        }
        if (expiresWithin(EXPIRY_MARGIN_MS)) return Outcome.Unavailable("grantExpiring", grantGone = false, sent = false)
        val rid = WatchToolSeal.newRequestId()
        val body = try {
            val plaintext = WatchToolSeal.json(call)
            if (plaintext.size > WatchToolSeal.MAX_CALL_BYTES) return Outcome.Unavailable("tooLarge", grantGone = false, sent = false)
            val sealed = WatchToolSeal.seal(plaintext, keys, WatchToolSeal.Direction.CALL, grantId, rid)
            WatchToolSeal.json(buildJsonObject { put("rid", rid); put("n", sealed.n); put("ct", sealed.ct) })
        } catch (_: Exception) {
            return Outcome.Unavailable("sealFailed", grantGone = false, sent = false)
        }
        val request = Request.Builder()
            .url("$grantUrl/calls")
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $watchKey")
            .header("Accept", "application/json")
            .build()
        synchronized(this) {
            callsSent += 1
            inFlight += 1
        }
        try {
            val (status, text) = try {
                withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
            } catch (e: SocketTimeoutException) {
                return Outcome.Unavailable("requestTimedOut", grantGone = false, sent = true)
            } catch (e: Exception) {
                return Outcome.Unavailable("network ${e.javaClass.simpleName}", grantGone = false, sent = true)
            }
            val answer = runCatching { WearWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            return when (status) {
                200 -> {
                    val n = answer?.text("n")
                    val ct = answer?.text("ct")
                    val opened = if (n != null && ct != null) runCatching {
                        WatchToolSeal.open(WatchToolSeal.Sealed(n, ct), keys, WatchToolSeal.Direction.RESULT, grantId, rid)
                    }.getOrNull() else null
                    val result = opened?.let { runCatching { WearWire.json.parseToJsonElement(it.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() }
                        ?: return Outcome.Unavailable("unreadableAnswer", grantGone = false, sent = true)
                    // Hermes' own word that this grant is over: not for the model. A 429 is the host's search limit, which the model hears.
                    val code = result.int("status")
                    if (!result.ok && result.containsKey("ok") && code != null && code in setOf(403, 410)) {
                        isGone = true
                        Outcome.Unavailable("host $code", grantGone = true, sent = true)
                    } else {
                        Outcome.Answered(result)
                    }
                }
                504 -> Outcome.TimedOut
                401, 404, 410 -> {
                    isGone = true
                    Outcome.Unavailable("relay $status", grantGone = true, sent = true)
                }
                else -> {
                    val error = answer?.text("error")
                    if (error == "grant_exhausted") isGone = true
                    Outcome.Unavailable("relay $status${error?.let { " $it" }.orEmpty()}", grantGone = isGone, sent = true)
                }
            }
        } finally {
            val sendClose = synchronized(this) {
                inFlight -= 1
                closing && inFlight == 0
            }
            if (sendClose) sendClose()
        }
    }

    /** Ends the grant on the relay, which tells Hermes, once no lookup waits on it. Best effort: it expires there anyway. */
    fun close() {
        val now = synchronized(this) {
            isGone = true
            closing = true
            inFlight == 0
        }
        if (now) sendClose()
    }

    private fun sendClose() {
        synchronized(this) {
            if (closeSent) return
            closeSent = true
        }
        val request = Request.Builder().url(grantUrl).delete().header("Authorization", "Bearer $watchKey").build()
        runCatching {
            http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build().newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
            })
        }
    }

    companion object {
        /** The relay holds a call 25 s for Hermes; this waits a little longer. */
        const val REQUEST_TIMEOUT_MS = 35_000L
        /** A grant this close to its end isn't used. */
        const val EXPIRY_MARGIN_MS = 30_000L
        private val JSON = "application/json".toMediaType()

        /** Never redirected (the relay key travels in a header), no cookies, no cache. */
        fun httpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .callTimeout(REQUEST_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** Null for a grant that can't be read or doesn't use https. */
        fun of(grant: WatchVoiceWire.DirectToolGrant, http: OkHttpClient = httpClient(), now: () -> Long = System::currentTimeMillis): WatchToolRelayClient? {
            val uri = runCatching { URI(grant.relayUrl) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrEmpty()) return null
            if (grant.grantId.length != 22 || WatchToolSeal.data(grant.grantId) == null) return null
            if (grant.watchKey.isEmpty() || WatchToolSeal.data(grant.watchKey) == null) return null
            val root = WatchToolSeal.data(grant.key) ?: return null
            val keys = WatchToolSeal.Keys.of(root) ?: return null
            return WatchToolRelayClient(grant, keys, grant.relayUrl, http, now)
        }
    }
}
