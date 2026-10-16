package app.hermesconduit.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.data.ConnectionStore
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.ModelOptions
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.firstString
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.CloudflareAccess
import app.hermesconduit.android.net.ConnectionUrlPolicy
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.SessionCookieJar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Loading : Screen
    data class Login(val prefillUrl: String = "", val error: String? = null, val busy: Boolean = false) : Screen
    data object Main : Screen
}

enum class LinkState { CONNECTING, CONNECTED, OFFLINE }

data class SessionsState(
    val sessions: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application), ChatActions, DrawerActions {
    private companion object {
        const val HISTORY_PAGE = 120
    }

    private val store = ConnectionStore(application)
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var connection: SavedConnection? = null
    private var api: DashboardApi? = null
    private var gateway: GatewayClient? = null
    private var gatewayJobs: List<Job> = emptyList()
    private var reconnectJob: Job? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    private val _link = MutableStateFlow(LinkState.CONNECTING)
    val link: StateFlow<LinkState> = _link

    private val _profiles = MutableStateFlow(listOf("default"))
    val profiles: StateFlow<List<String>> = _profiles

    private val _profile = MutableStateFlow("default")
    val profile: StateFlow<String> = _profile

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat

    private val _models = MutableStateFlow<ModelOptions?>(null)
    val models: StateFlow<ModelOptions?> = _models

    val dashboardUrl: String? get() = connection?.baseUrl

    init {
        val saved = store.load()
        if (saved == null) {
            _screen.value = Screen.Login(prefillUrl = store.lastBaseUrl().orEmpty())
        } else {
            connection = saved
            _profile.value = saved.profile
            api = makeApi(saved).also { it.cookieJar.restore(saved.baseUrl.toHttpUrl(), store.cookies) }
            _screen.value = Screen.Main
            viewModelScope.launch { startSession() }
        }
    }

    // region Sign-in

    fun signIn(url: String, username: String, password: String, cfId: String, cfSecret: String) {
        val baseUrl = try {
            ConnectionUrlPolicy.normalizedBaseUrl(withScheme(url))
        } catch (e: IllegalArgumentException) {
            _screen.value = Screen.Login(url, e.message)
            return
        }
        _screen.value = Screen.Login(url, busy = true)
        viewModelScope.launch {
            val candidate = SavedConnection(
                baseUrl = baseUrl,
                username = username.trim(),
                password = password,
                cfClientId = cfId.trim().ifEmpty { null },
                cfClientSecret = cfSecret.trim().ifEmpty { null },
            )
            val candidateApi = makeApi(candidate)
            try {
                val providers = candidateApi.authProviders()
                if (!providers.supportsPassword) {
                    error("This dashboard only offers browser sign-in (${providers.names.joinToString()}), which the Android app doesn't support yet.")
                }
                candidateApi.passwordLogin(candidate.username, candidate.password)
                store.save(candidate)
                store.cookies = candidateApi.cookieJar.serialize()
                connection = candidate
                api = candidateApi
                _profile.value = candidate.profile
                _chat.value = ChatState()
                _screen.value = Screen.Main
                startSession()
            } catch (e: Exception) {
                _screen.value = Screen.Login(url, e.message ?: "Couldn't sign in.")
            }
        }
    }

    override fun signOut() {
        disconnectGateway()
        api?.cookieJar?.clear()
        store.clear()
        connection = null
        api = null
        _sessions.value = SessionsState()
        _chat.value = ChatState()
        _profiles.value = listOf("default")
        _screen.value = Screen.Login(prefillUrl = store.lastBaseUrl().orEmpty())
    }

    private fun withScheme(url: String): String {
        val trimmed = url.trim()
        if ("://" in trimmed) return trimmed
        val host = trimmed.substringBefore('/').substringBefore(':')
        return if (ConnectionUrlPolicy.isInsecureTransportAllowed(host)) "http://$trimmed" else "https://$trimmed"
    }

    private fun makeApi(connection: SavedConnection) = DashboardApi(
        baseUrl = connection.baseUrl,
        cookieJar = SessionCookieJar { store.cookies = it },
        cloudflare = CloudflareAccess.of(connection.cfClientId, connection.cfClientSecret),
        client = baseHttp,
    )

    /** Runs [block]; on an expired session signs in again with the saved password once. */
    private suspend fun <T> authed(block: suspend (DashboardApi) -> T): T {
        val api = api ?: throw IllegalStateException("Not signed in.")
        return try {
            block(api)
        } catch (_: AuthExpiredException) {
            val saved = connection ?: throw AuthExpiredException()
            api.passwordLogin(saved.username, saved.password)
            block(api)
        }
    }

    // endregion

    // region Gateway

    private suspend fun startSession() {
        refreshProfiles()
        refreshSessions()
        connectGateway()
    }

    private suspend fun connectGateway() {
        val api = api ?: return
        disconnectGateway()
        _link.value = LinkState.CONNECTING
        val client = GatewayClient(
            http = api.http.newBuilder().pingInterval(0, TimeUnit.SECONDS).build(),
            scope = viewModelScope,
            profile = _profile.value,
            cloudflare = CloudflareAccess.of(connection?.cfClientId, connection?.cfClientSecret),
        )
        gateway = client
        gatewayJobs = listOf(
            viewModelScope.launch { client.events.collect(::onEvent) },
            viewModelScope.launch {
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
            val ticket = authed { it.webSocketTicket() }
            client.connect(api.baseUrl, ticket)
            // Reattach to the open chat with the new runtime, reloading the
            // transcript since events were missed while disconnected.
            _chat.value.sessionId?.let { resume(it, reloadHistory = true) }
        } catch (e: Exception) {
            if (gateway === client) {
                _link.value = LinkState.OFFLINE
                if (e is AuthExpiredException) signOutWithMessage("Your session expired. Sign in again.")
                else scheduleReconnect()
            }
        }
    }

    private fun signOutWithMessage(message: String) {
        val url = connection?.baseUrl.orEmpty()
        signOut()
        _screen.value = Screen.Login(url, message)
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || api == null) return
        reconnectJob = viewModelScope.launch {
            var delayMs = 1_000L
            while (gateway?.state?.value != GatewayClient.State.CONNECTED && api != null) {
                delay(delayMs)
                connectGateway()
                delayMs = (delayMs * 2).coerceAtMost(30_000)
            }
        }
    }

    override fun reconnectNow() {
        reconnectJob?.cancel()
        viewModelScope.launch { connectGateway() }
    }

    private fun disconnectGateway() {
        val old = gateway
        gateway = null
        gatewayJobs.forEach { it.cancel() }
        gatewayJobs = emptyList()
        old?.close()
    }

    private fun onEvent(event: StreamEvent) {
        if (event is StreamEvent.Title) {
            _sessions.update { s ->
                s.copy(sessions = s.sessions.map { if (it.id == event.storedSessionId) it.copy(title = event.title) else it })
            }
        }
        val chat = _chat.value
        val addressed = event.sessionId.isEmpty() || event.sessionId == chat.runtimeId ||
            (event is StreamEvent.Title && event.storedSessionId == chat.sessionId)
        if (!addressed) return
        val next = ChatReducer.reduce(chat, event)
        _chat.value = next
        if (chat.running && !next.running) viewModelScope.launch { refreshSessions() }
    }

    private suspend fun rpc(method: String, params: kotlinx.serialization.json.JsonObject = buildJsonObject {}) =
        gateway?.request(method, params) ?: throw IllegalStateException("Not connected to the gateway.")

    // endregion

    // region Profiles and sessions

    private suspend fun refreshProfiles() {
        runCatching { authed { it.profiles() } }.onSuccess { _profiles.value = it }
    }

    override fun selectProfile(name: String) {
        if (name == _profile.value) return
        _profile.value = name
        connection = connection?.copy(profile = name)
        store.saveProfile(name)
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        viewModelScope.launch {
            refreshSessions()
            connectGateway()
        }
    }

    override fun refreshSessionsAsync() {
        viewModelScope.launch { refreshSessions() }
    }

    private suspend fun refreshSessions() {
        _sessions.update { it.copy(loading = true, error = null) }
        try {
            val profile = _profile.value
            val all = mutableListOf<SessionSummary>()
            var offset = 0
            for (page in 0 until 5) {
                val result = authed { it.sessions(profile, offset) }
                all += result.sessions
                if (!result.hasMore) break
                offset = result.nextOffset
            }
            val sorted = all.distinctBy { it.id }
                .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActiveMs ?: 0 })
            _sessions.value = SessionsState(sorted)
        } catch (e: AuthExpiredException) {
            signOutWithMessage("Your session expired. Sign in again.")
        } catch (e: Exception) {
            _sessions.update { it.copy(loading = false, error = e.message ?: "Couldn't load chats.") }
        }
    }

    override fun renameSession(id: String, title: String) = sessionMutation { it.renameSession(id, _profile.value, title) }

    override fun togglePinned(session: SessionSummary) = sessionMutation { it.setPinned(session.id, _profile.value, !session.pinned) }

    override fun archiveSession(id: String) = sessionMutation {
        it.setArchived(id, _profile.value, true)
        if (_chat.value.sessionId == id) _chat.value = ChatState()
    }

    override fun deleteSession(id: String) = sessionMutation {
        it.deleteSession(id, _profile.value)
        if (_chat.value.sessionId == id) _chat.value = ChatState()
    }

    private fun sessionMutation(block: suspend (DashboardApi) -> Unit) {
        viewModelScope.launch {
            try {
                authed { block(it) }
            } catch (e: Exception) {
                _sessions.update { it.copy(error = e.message ?: "That change didn't go through.") }
            }
            refreshSessions()
        }
    }

    // endregion

    // region Chat

    override fun newChat() {
        _chat.value = ChatState(model = _chat.value.model, provider = _chat.value.provider)
    }

    override fun openSession(session: SessionSummary) {
        _chat.value = ChatState(sessionId = session.id, title = session.title, loading = true, model = session.model)
        viewModelScope.launch { resume(session.id, reloadHistory = true) }
    }

    private suspend fun resume(sessionId: String, reloadHistory: Boolean) {
        if (reloadHistory) {
            try {
                val page = authed { it.messages(sessionId, _profile.value) }
                _chat.update {
                    if (it.sessionId != sessionId) it
                    else it.copy(messages = page.messages, hasEarlier = page.hasMore, loadedOffset = HISTORY_PAGE)
                }
            } catch (e: Exception) {
                _chat.update { if (it.sessionId != sessionId) it else it.copy(error = e.message ?: "Couldn't load this chat.") }
            }
        }
        try {
            val result = rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("cols", 96)
                    put("source", "desktop")
                    put("omit_messages", true)
                },
            ).obj
            val info = result?.get("info").obj
            val running = result?.get("running").bool ?: info?.get("running").bool ?: false
            val pendingApproval = result?.get("pending_approval").obj
                ?.let { MessageNormalizer.approvalRequest(it, result?.get("session_id").str.orEmpty()) }
            _chat.update {
                if (it.sessionId != sessionId) it
                else it.copy(
                    runtimeId = result?.get("session_id").str ?: it.runtimeId,
                    running = running,
                    model = info?.get("model").str ?: it.model,
                    provider = info?.get("provider").str ?: it.provider,
                    contextPercent = info?.get("context_percent").double ?: it.contextPercent,
                    approval = pendingApproval ?: it.approval,
                    loading = false,
                )
            }
            result?.get("open_requests")?.let { it as? JsonArray }?.forEach { element ->
                val request = element.obj ?: return@forEach
                if (request["method"].str == "clarify") {
                    val params = request["params"].obj ?: return@forEach
                    MessageNormalizer.clarifyRequest(params, result["session_id"].str.orEmpty(), request["id"].str)
                        ?.let { clarify -> _chat.update { if (it.sessionId == sessionId) it.copy(clarify = clarify) else it } }
                }
            }
        } catch (e: Exception) {
            _chat.update { if (it.sessionId != sessionId) it else it.copy(loading = false) }
        }
    }

    override fun loadEarlier() {
        val chat = _chat.value
        val sessionId = chat.sessionId ?: return
        if (!chat.hasEarlier || chat.loading) return
        _chat.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val page = authed { it.messages(sessionId, _profile.value, offset = chat.loadedOffset) }
                _chat.update {
                    if (it.sessionId != sessionId) it
                    else it.copy(
                        messages = page.messages + it.messages,
                        hasEarlier = page.hasMore,
                        loadedOffset = it.loadedOffset + HISTORY_PAGE,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _chat.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    override fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val local = ChatMessage("local-${System.nanoTime()}", MessageRole.USER, trimmed, timestampMs = System.currentTimeMillis())
        _chat.update { it.copy(messages = it.messages + local, error = null, running = true) }
        viewModelScope.launch {
            try {
                var runtimeId = _chat.value.runtimeId
                if (runtimeId == null) {
                    val created = rpc(
                        "session.create",
                        buildJsonObject {
                            put("cols", 96)
                            put("source", "desktop")
                        },
                    ).obj
                    runtimeId = created?.get("session_id").str ?: error("The gateway didn't create a session.")
                    val stored = created?.get("stored_session_id").str ?: runtimeId
                    _chat.update { it.copy(runtimeId = runtimeId, sessionId = stored, title = trimmed.take(60)) }
                }
                val result = rpc(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("text", trimmed)
                    },
                ).obj
                when (result?.get("status").str) {
                    "queued" -> _chat.update { it.copy(notice = "Queued — Hermes will answer after the current turn.") }
                    "steered" -> _chat.update { it.copy(notice = "Sent as guidance for the running turn.") }
                }
            } catch (e: Exception) {
                _chat.update { it.copy(running = false, error = e.message ?: "Couldn't send.") }
            }
        }
    }

    override fun stop() {
        val runtimeId = _chat.value.runtimeId ?: return
        viewModelScope.launch {
            runCatching { rpc("session.interrupt", buildJsonObject { put("session_id", runtimeId) }) }
                .onFailure { e -> _chat.update { it.copy(error = e.message) } }
        }
    }

    override fun respondApproval(choice: String) {
        val approval = _chat.value.approval ?: return
        _chat.update { it.copy(approval = null) }
        viewModelScope.launch {
            try {
                rpc(
                    "approval.respond",
                    buildJsonObject {
                        put("choice", choice)
                        put("session_id", approval.sessionId)
                        if (approval.requestId.isNotEmpty()) put("request_id", approval.requestId)
                    },
                )
            } catch (e: Exception) {
                _chat.update { it.copy(approval = approval, error = e.message ?: "The approval didn't go through.") }
            }
        }
    }

    /** One answer per question, in order. */
    override fun answerClarify(request: ClarifyRequest, answers: List<String>) {
        _chat.update { it.copy(clarify = null) }
        viewModelScope.launch {
            try {
                when {
                    request.isServerRequest && request.questions.size <= 1 ->
                        gateway?.respond(request.requestId, buildJsonObject { put("answer", answers.firstOrNull().orEmpty()) })
                    request.isServerRequest -> request.questions.forEachIndexed { i, q ->
                        rpc(
                            "clarify.lock",
                            buildJsonObject {
                                put("request_id", request.requestId)
                                put("question_id", q.id ?: i.toString())
                                put("answer", answers.getOrElse(i) { "" })
                            },
                        )
                    }
                    else -> request.questions.forEachIndexed { i, q ->
                        rpc(
                            "clarify.respond",
                            buildJsonObject {
                                put("request_id", request.requestId)
                                put("answer", answers.getOrElse(i) { "" })
                                q.id?.let { put("question_id", it) }
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                _chat.update { it.copy(clarify = request, error = e.message) }
            }
        }
    }

    override fun dismissNotice() = _chat.update { it.copy(notice = null, error = null) }

    override fun loadModels() {
        viewModelScope.launch {
            runCatching {
                rpc(
                    "model.options",
                    buildJsonObject {
                        put("explicit_only", true)
                        _chat.value.runtimeId?.let { put("session_id", it) }
                    },
                ).obj
            }.onSuccess { result -> _models.value = result?.let(MessageNormalizer::modelOptions) }
                .onFailure { e -> _chat.update { it.copy(error = e.message ?: "Couldn't load models.") } }
        }
    }

    override fun selectModel(provider: String, model: String, confirmed: Boolean) {
        val runtimeId = _chat.value.runtimeId
        if (runtimeId == null) {
            _chat.update { it.copy(notice = "Send a first message, then switch the model for this chat.") }
            return
        }
        _chat.update { it.copy(modelConfirm = null) }
        viewModelScope.launch {
            try {
                val result = rpc(
                    "config.set",
                    buildJsonObject {
                        put("key", "model")
                        put("session_id", runtimeId)
                        put("value", "$model --provider $provider --session")
                        if (confirmed) put("confirm_expensive_model", true)
                    },
                ).obj
                if (result?.get("confirm_required").bool == true) {
                    val message = result?.firstString("confirm_message", "warning") ?: "This model may be expensive. Switch anyway?"
                    _chat.update { it.copy(modelConfirm = ModelConfirm(provider, model, message)) }
                    return@launch
                }
                val applied = result?.get("value").str?.substringBefore(" --") ?: model
                _chat.update {
                    it.copy(
                        model = applied,
                        provider = provider,
                        notice = if (result?.get("deferred").bool == true) "The new model applies from the next turn." else null,
                    )
                }
            } catch (e: Exception) {
                _chat.update { it.copy(error = e.message ?: "Couldn't switch the model.") }
            }
        }
    }

    override fun cancelModelConfirm() = _chat.update { it.copy(modelConfirm = null) }

    // endregion

    override fun onCleared() {
        disconnectGateway()
        super.onCleared()
    }
}
