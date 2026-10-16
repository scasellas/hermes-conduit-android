package app.hermesconduit.android.ui.gateway

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.GatewayRestartAction
import app.hermesconduit.android.model.GatewayRestartMonitor
import app.hermesconduit.android.model.GatewayRestartStage
import app.hermesconduit.android.model.GatewayRestartState
import app.hermesconduit.android.model.GatewayRunStatus
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A dashboard request: (method, path, query, body) → JSON object. */
typealias DashboardRequest = suspend (method: String, path: String, query: Map<String, String?>, body: JsonElement?) -> JsonObject

/** Hermes' own words from a refused request, when it gave any. */
internal fun Throwable.hermesDetail(): String? = (this as? DashboardHttpException)?.detail?.trim()?.takeIf { it.isNotEmpty() }

internal fun Throwable.httpCode(): Int? = (this as? DashboardHttpException)?.code

internal fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: toString()

/**
 * A JSON call on the active dashboard with its own timeout (Hermes pulls a
 * plugin before it answers), re-signing in once on an expired session.
 */
suspend fun HermesConnection.requestJson(
    method: String,
    path: String,
    query: Map<String, String?> = emptyMap(),
    body: JsonElement? = null,
    timeoutSeconds: Long = 30,
): JsonObject = rest { api -> execute(api, method, path, query, body, timeoutSeconds) }

private suspend fun execute(
    api: DashboardApi,
    method: String,
    path: String,
    query: Map<String, String?>,
    body: JsonElement?,
    timeoutSeconds: Long,
): JsonObject = withContext(Dispatchers.IO) {
    val client = api.http.newBuilder()
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds + 5, TimeUnit.SECONDS)
        .build()
    val requestBody = when {
        body != null -> body.toString().toRequestBody(JSON)
        method == "GET" || method == "DELETE" -> null
        else -> ByteArray(0).toRequestBody(JSON)
    }
    client.newCall(Request.Builder().url(api.url(path, query)).method(method, requestBody).build()).execute().use { response ->
        if (response.isRedirect) {
            if ("/login" in response.header("Location").orEmpty()) throw AuthExpiredException()
            throw DashboardHttpException(response.code, "The dashboard redirected to ${response.header("Location").orEmpty()}.")
        }
        if (response.code == 401) throw AuthExpiredException()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull()
            val detail = obj?.let { it["detail"].str ?: it["error"].str ?: it["message"].str ?: it["detail"]?.toString() }.orEmpty()
            throw DashboardHttpException(response.code, detail)
        }
        DashboardApi.normalize(text)
    }
}

private val JSON = "application/json".toMediaType()

/**
 * Runs one Restart Gateway: the request, then polls until the gateway is
 * back, the restart fails, or Conduit stops watching (iOS `GatewayRestarter`).
 */
class GatewayRestarter(
    private val request: DashboardRequest,
    private val pollIntervalMs: Long = 1_500,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun run(progress: (GatewayRestartState) -> Unit): GatewayRestartState {
        progress(GatewayRestartState.Requesting)
        val before = gatewayStatus()
        try {
            request("POST", RESTART_PATH, emptyMap(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return outcomeForRequestError(e)
        }
        val started = now()
        val monitor = GatewayRestartMonitor(before)
        var actionLogAvailable = true
        var latestLine: String? = null
        progress(GatewayRestartState.Restarting(GatewayRestartStage.STOPPING, null))
        while (true) {
            sleep(pollIntervalMs)
            var action: GatewayRestartAction? = null
            if (actionLogAvailable) {
                try {
                    action = GatewayRestartAction.parse(request("GET", ACTION_STATUS_PATH, mapOf("lines" to "20"), null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A Hermes without the action log still restarts; watch /api/status alone.
                    if (e.httpCode() == 404) actionLogAvailable = false
                }
            }
            val gateway = gatewayStatus()
            latestLine = action?.latestLine ?: latestLine
            val elapsed = (now() - started) / 1000.0
            when (val verdict = monitor.observe(action, gateway, elapsed)) {
                is GatewayRestartMonitor.Verdict.Waiting -> progress(GatewayRestartState.Restarting(verdict.stage, latestLine))
                is GatewayRestartMonitor.Verdict.Back -> return GatewayRestartState.Restarted(verdict.status.sharedProfiles)
                is GatewayRestartMonitor.Verdict.Failed -> return GatewayRestartState.Failed(verdict.detail)
                GatewayRestartMonitor.Verdict.FailedToStart -> return GatewayRestartState.FailedToStart
                GatewayRestartMonitor.Verdict.NotBackYet -> return GatewayRestartState.NotBackYet
            }
        }
    }

    private suspend fun gatewayStatus(): GatewayRunStatus? = try {
        GatewayRunStatus.parse(request("GET", STATUS_PATH, emptyMap(), null))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val RESTART_PATH = "api/gateway/restart"
        const val ACTION_STATUS_PATH = "api/actions/gateway-restart/status"
        const val STATUS_PATH = "api/status"

        /** A dashboard without the route predates the action; anything else is Hermes' own failure. */
        fun outcomeForRequestError(e: Throwable): GatewayRestartState = when (e.httpCode()) {
            404, 405, 501 -> GatewayRestartState.Unsupported
            else -> GatewayRestartState.Failed(e.hermesDetail() ?: e.userMessage())
        }
    }
}

/** Restart Gateway's state, bound to the dashboard it started on. */
data class GatewayRestartSlot(val dashboardId: String? = null, val state: GatewayRestartState = GatewayRestartState.Idle) {
    fun state(activeDashboardId: String?): GatewayRestartState =
        if (dashboardId == activeDashboardId) state else GatewayRestartState.Idle
}

/** App-wide Restart Gateway, shared by Settings › Gateway and its diagnostics page. */
class GatewayRestartController private constructor(private val hermes: HermesConnection) {
    private val _slot = MutableStateFlow(GatewayRestartSlot())
    val slot: StateFlow<GatewayRestartSlot> = _slot
    private var job: Job? = null

    fun state(): GatewayRestartState = _slot.value.state(hermes.connection.value?.id)

    val canRestart: Boolean get() = hermes.isSignedIn && !state().isInProgress

    fun restart() {
        if (!canRestart) return
        job?.cancel()
        val dashboardId = hermes.connection.value?.id
        _slot.value = GatewayRestartSlot(dashboardId, GatewayRestartState.Requesting)
        val restarter = GatewayRestarter(request = { method, path, query, body ->
            if (hermes.connection.value?.id != dashboardId) throw IllegalStateException("Another dashboard is in use.")
            hermes.requestJson(method, path, query, body, timeoutSeconds = 15)
        })
        job = hermes.scope.launch {
            val outcome = restarter.run { state ->
                if (hermes.connection.value?.id == dashboardId) _slot.value = GatewayRestartSlot(dashboardId, state)
                else job?.cancel()
            }
            _slot.value = if (hermes.connection.value?.id == dashboardId) GatewayRestartSlot(dashboardId, outcome) else GatewayRestartSlot()
            // The chat connection normally stays up; reconnect only if it dropped.
            if (outcome is GatewayRestartState.Restarted && hermes.link.value != app.hermesconduit.android.core.LinkState.CONNECTED) {
                hermes.reconnectNow()
            }
        }
    }

    /** Clears a finished restart's outcome. A restart in progress stays. */
    fun dismiss() {
        if (_slot.value.state.isInProgress) return
        _slot.value = GatewayRestartSlot()
    }

    companion object {
        @Volatile private var instance: GatewayRestartController? = null

        fun of(hermes: HermesConnection): GatewayRestartController =
            instance?.takeIf { it.hermes === hermes } ?: synchronized(this) {
                instance?.takeIf { it.hermes === hermes } ?: GatewayRestartController(hermes).also { instance = it }
            }
    }
}
