package app.hermesconduit.android.core

import android.content.Context
import app.hermesconduit.android.data.ConnectionStore
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.CloudflareAccess
import app.hermesconduit.android.net.ConnectionUrlPolicy
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.NativeOAuth
import app.hermesconduit.android.net.SessionCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class LinkState { CONNECTING, CONNECTED, OFFLINE }

/**
 * The app-wide link to the active Hermes dashboard: REST client, the
 * ticketed JSON-RPC socket (reconnected with backoff), the active profile and
 * a single event stream that survives reconnects. Feature view models share it.
 */
class HermesConnection(context: Context) {
    val store = ConnectionStore(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _connection = MutableStateFlow<SavedConnection?>(null)
    /** The active saved dashboard, or null when signed out. */
    val connection: StateFlow<SavedConnection?> = _connection

    private val _dashboards = MutableStateFlow<List<SavedConnection>>(emptyList())
    val dashboards: StateFlow<List<SavedConnection>> = _dashboards

    @Volatile var api: DashboardApi? = null
        private set
    @Volatile var gateway: GatewayClient? = null
        private set
    private var gatewayJobs: List<Job> = emptyList()
    private var reconnectJob: Job? = null
    private val reloginMutex = Mutex()

    private val _link = MutableStateFlow(LinkState.CONNECTING)
    val link: StateFlow<LinkState> = _link

    private val _profile = MutableStateFlow("default")
    val profile: StateFlow<String> = _profile

    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 1024)
    /** Every gateway event, across reconnects. */
    val events: SharedFlow<StreamEvent> = _events

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits after every successful socket (re)connect. */
    val connected: SharedFlow<Unit> = _connected

    private val _signedOut = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    /** Emits when the session can't be recovered (message for the login screen). */
    val signedOut: SharedFlow<String?> = _signedOut

    /** Gateway capabilities reported on connect (e.g. `declines_not_shown`). */
    val capabilities: JsonObject? get() = gateway?.capabilities

    init {
        _dashboards.value = store.all()
        store.load()?.let { activate(it) }
    }

    val isSignedIn: Boolean get() = api != null

    private fun makeApi(connection: SavedConnection) = DashboardApi(
        baseUrl = connection.baseUrl,
        cookieJar = SessionCookieJar { store.saveCookies(connection.id, it) },
        cloudflare = CloudflareAccess.of(connection.cfClientId, connection.cfClientSecret),
        client = baseHttp,
        customHeaders = connection.customHeaders,
    ).also { api ->
        api.cookieJar.restore(connection.baseUrl.toHttpUrl(), store.cookies(connection.id))
        if (connection.authMode == "oauth") api.bearerToken = connection.oauthAccessToken
    }

    private fun activate(connection: SavedConnection) {
        disconnectGateway()
        _connection.value = connection
        _profile.value = connection.profile
        api = makeApi(connection)
    }

    /** Validates a candidate, signs in with a password, saves and activates it. */
    suspend fun signInWithPassword(candidate: SavedConnection) {
        val candidateApi = makeApi(candidate.copy(authMode = "password"))
        val providers = candidateApi.authProviders()
        if (!providers.supportsPassword) {
            throw IllegalStateException("This dashboard doesn't offer password sign-in. Use “Sign in with browser”.")
        }
        candidateApi.passwordLogin(candidate.username, candidate.password)
        store.save(candidate)
        store.saveCookies(candidate.id, candidateApi.cookieJar.serialize())
        store.lastBaseUrl = candidate.baseUrl
        _dashboards.value = store.all()
        activate(candidate)
    }

    /** Saves a dashboard signed in through native OAuth and activates it. */
    fun signInWithTokens(candidate: SavedConnection) {
        store.save(candidate)
        store.lastBaseUrl = candidate.baseUrl
        _dashboards.value = store.all()
        activate(candidate)
    }

    fun switchDashboard(id: String) {
        val target = store.all().firstOrNull { it.id == id } ?: return
        store.activeId = id
        activate(target)
        start()
    }

    /** Forgets the active dashboard; returns true when another one took over. */
    fun signOut(): Boolean {
        val current = _connection.value
        disconnectGateway()
        api?.cookieJar?.clear()
        api = null
        _connection.value = null
        if (current != null) {
            store.lastBaseUrl = current.baseUrl
            store.remove(current.id)
        }
        _dashboards.value = store.all()
        val next = store.load() ?: return false
        activate(next)
        start()
        return true
    }

    fun updateConnection(transform: (SavedConnection) -> SavedConnection) {
        val current = _connection.value ?: return
        val next = transform(current)
        store.update(next)
        _connection.value = next
        _dashboards.value = store.all()
    }

    fun renameDashboard(id: String, name: String) {
        store.all().firstOrNull { it.id == id }?.let { store.update(it.copy(name = name)) }
        _dashboards.value = store.all()
        if (_connection.value?.id == id) _connection.value = store.all().firstOrNull { it.id == id }
    }

    fun setProfile(name: String) {
        if (name == _profile.value) return
        _profile.value = name
        updateConnection { it.copy(profile = name) }
        reconnectNow()
    }

    // region Requests

    /** Runs a REST call; on an expired session signs in again once and retries. */
    suspend fun <T> rest(block: suspend (DashboardApi) -> T): T {
        val api = api ?: throw IllegalStateException("Not signed in.")
        return try {
            block(api)
        } catch (_: AuthExpiredException) {
            relogin(api)
            block(api)
        }
    }

    private suspend fun relogin(api: DashboardApi) = reloginMutex.withLock {
        val saved = _connection.value ?: throw AuthExpiredException()
        try {
            if (saved.authMode == "oauth") {
                val refresh = saved.oauthRefreshToken ?: throw AuthExpiredException()
                val tokens = NativeOAuth.refresh(api, refresh, saved.oauthProvider)
                api.bearerToken = tokens.accessToken
                updateConnection {
                    it.copy(
                        oauthAccessToken = tokens.accessToken,
                        oauthRefreshToken = tokens.refreshToken ?: it.oauthRefreshToken,
                        oauthExpiresAt = tokens.expiresAt,
                    )
                }
            } else {
                api.passwordLogin(saved.username, saved.password)
            }
        } catch (e: Exception) {
            if (e is AuthExpiredException || e is app.hermesconduit.android.net.DashboardHttpException) {
                _signedOut.tryEmit("Your session expired. Sign in again.")
            }
            throw e
        }
    }

    suspend fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonElement =
        gateway?.request(method, params) ?: throw IllegalStateException("Not connected to the gateway.")

    suspend fun rpc(method: String, params: JsonObject, timeoutMs: Long): JsonElement =
        gateway?.request(method, params, timeoutMs) ?: throw IllegalStateException("Not connected to the gateway.")

    fun respond(id: String, result: JsonElement) {
        gateway?.respond(id, result)
    }

    // endregion

    // region Gateway

    /** Connects the socket for the active dashboard. */
    fun start() {
        scope.launch { connectGateway() }
    }

    private suspend fun connectGateway() {
        val api = api ?: return
        disconnectGateway()
        _link.value = LinkState.CONNECTING
        val saved = _connection.value
        val client = GatewayClient(
            http = api.http.newBuilder().pingInterval(0, TimeUnit.SECONDS).build(),
            scope = scope,
            profile = _profile.value,
            cloudflare = CloudflareAccess.of(saved?.cfClientId, saved?.cfClientSecret),
            customHeaders = saved?.customHeaders.orEmpty(),
        )
        gateway = client
        gatewayJobs = listOf(
            scope.launch {
                client.events.collect { event -> _events.emit(event) }
            },
            scope.launch {
                client.state.collect { state ->
                    if (gateway !== client) return@collect
                    when (state) {
                        GatewayClient.State.CONNECTED -> _link.value = LinkState.CONNECTED
                        GatewayClient.State.CLOSED -> {
                            _link.value = LinkState.OFFLINE
                            scheduleReconnect()
                        }
                        GatewayClient.State.CONNECTING -> _link.value = LinkState.CONNECTING
                    }
                }
            },
        )
        try {
            val ticket = rest { it.webSocketTicket() }
            client.connect(api.baseUrl, ticket)
            _connected.emit(Unit)
        } catch (e: Exception) {
            if (gateway === client) {
                _link.value = LinkState.OFFLINE
                if (e !is AuthExpiredException) scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || api == null) return
        reconnectJob = scope.launch {
            var delayMs = 1_000L
            while (gateway?.state?.value != GatewayClient.State.CONNECTED && api != null) {
                delay(delayMs)
                connectGateway()
                delayMs = (delayMs * 2).coerceAtMost(30_000)
            }
        }
    }

    fun reconnectNow() {
        reconnectJob?.cancel()
        scope.launch { connectGateway() }
    }

    private fun disconnectGateway() {
        val old = gateway
        gateway = null
        gatewayJobs.forEach { it.cancel() }
        gatewayJobs = emptyList()
        old?.close()
    }

    // endregion

    companion object {
        /** Adds a scheme when the user typed a bare host. */
        fun withScheme(url: String): String {
            val trimmed = url.trim()
            if ("://" in trimmed) return trimmed
            val host = trimmed.substringBefore('/').substringBefore(':')
            return if (ConnectionUrlPolicy.isInsecureTransportAllowed(host)) "http://$trimmed" else "https://$trimmed"
        }
    }
}
