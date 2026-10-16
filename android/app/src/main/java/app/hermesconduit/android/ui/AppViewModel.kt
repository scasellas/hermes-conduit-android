package app.hermesconduit.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.ModelOptions
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.firstString
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.ConnectionUrlPolicy
import app.hermesconduit.android.net.NativeOAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface Screen {
    data object Loading : Screen
    data class Login(
        val prefillUrl: String = "",
        val error: String? = null,
        val busy: Boolean = false,
        /** Adding another dashboard while one is still signed in. */
        val canCancel: Boolean = false,
    ) : Screen
    data object Main : Screen
}

data class SessionsState(
    val sessions: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application), ChatActions, DrawerActions {
    private companion object {
        const val HISTORY_PAGE = 120
    }

    val hermes: HermesConnection = application.hermes

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    val link: StateFlow<LinkState> = hermes.link
    val profile: StateFlow<String> = hermes.profile
    val dashboards = hermes.dashboards
    val connection = hermes.connection

    private val _profiles = MutableStateFlow(listOf("default"))
    val profiles: StateFlow<List<String>> = _profiles

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat

    private val _models = MutableStateFlow<ModelOptions?>(null)
    val models: StateFlow<ModelOptions?> = _models

    val dashboardUrl: String? get() = hermes.connection.value?.baseUrl

    init {
        viewModelScope.launch { hermes.events.collect(::onEvent) }
        viewModelScope.launch {
            hermes.connected.collect {
                // Reattach to the open chat with the new runtime, reloading the
                // transcript since events were missed while disconnected.
                _chat.value.sessionId?.let { resume(it, reloadHistory = true) }
            }
        }
        viewModelScope.launch {
            hermes.signedOut.collect { message -> signOutWithMessage(message) }
        }
        if (hermes.isSignedIn) {
            _screen.value = Screen.Main
            startSession()
        } else {
            _screen.value = Screen.Login(prefillUrl = hermes.store.lastBaseUrl.orEmpty())
        }
    }

    // region Sign-in and dashboards

    private fun candidate(url: String, username: String, password: String, cfId: String, cfSecret: String): SavedConnection? {
        val baseUrl = try {
            ConnectionUrlPolicy.normalizedBaseUrl(HermesConnection.withScheme(url))
        } catch (e: IllegalArgumentException) {
            _screen.value = Screen.Login(url, e.message, canCancel = hermes.isSignedIn)
            return null
        }
        return SavedConnection(
            baseUrl = baseUrl,
            username = username.trim(),
            password = password,
            cfClientId = cfId.trim().ifEmpty { null },
            cfClientSecret = cfSecret.trim().ifEmpty { null },
        )
    }

    fun signIn(url: String, username: String, password: String, cfId: String, cfSecret: String) {
        val candidate = candidate(url, username, password, cfId, cfSecret) ?: return
        val canCancel = hermes.isSignedIn
        _screen.value = Screen.Login(url, busy = true, canCancel = canCancel)
        viewModelScope.launch {
            try {
                hermes.signInWithPassword(candidate)
                onSignedIn()
            } catch (e: Exception) {
                _screen.value = Screen.Login(url, e.message ?: "Couldn't sign in.", canCancel = canCancel)
            }
        }
    }

    /**
     * Native OAuth: listens on a loopback port, asks [openBrowser] to show
     * the authorize page, then redeems the code for bearer tokens.
     */
    fun signInWithBrowser(url: String, cfId: String, cfSecret: String, openBrowser: (String) -> Unit) {
        val base = candidate(url, "", "", cfId, cfSecret) ?: return
        val canCancel = hermes.isSignedIn
        _screen.value = Screen.Login(url, busy = true, canCancel = canCancel)
        viewModelScope.launch {
            try {
                val api = app.hermesconduit.android.net.DashboardApi(
                    base.baseUrl,
                    app.hermesconduit.android.net.SessionCookieJar(),
                    app.hermesconduit.android.net.CloudflareAccess.of(base.cfClientId, base.cfClientSecret),
                    hermes.baseHttp,
                )
                val providers = api.authProviders()
                if (!api.supportsNativeOAuth()) {
                    error("This dashboard doesn't support app sign-in through the browser. Update Hermes, or use a password.")
                }
                val pkce = NativeOAuth.pkce()
                val state = NativeOAuth.randomUrlSafe(16)
                NativeOAuth.LoopbackListener().use { listener ->
                    openBrowser(NativeOAuth.authorizeUrl(base.baseUrl, pkce.challenge, listener.redirectUri, state, providers.oauthProvider))
                    val code = NativeOAuth.callbackCode(listener.awaitRequestTarget(), state)
                    val tokens = NativeOAuth.exchange(api, code, pkce.verifier)
                    hermes.signInWithTokens(
                        base.copy(
                            username = tokens.userId.orEmpty(),
                            authMode = "oauth",
                            oauthAccessToken = tokens.accessToken,
                            oauthRefreshToken = tokens.refreshToken,
                            oauthExpiresAt = tokens.expiresAt,
                            oauthProvider = tokens.provider ?: providers.oauthProvider,
                        ),
                    )
                }
                onSignedIn()
            } catch (e: Exception) {
                _screen.value = Screen.Login(url, e.message ?: "Couldn't sign in.", canCancel = canCancel)
            }
        }
    }

    private fun onSignedIn() {
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        _screen.value = Screen.Main
        startSession()
    }

    /** Shows the sign-in form to add another dashboard. */
    override fun addDashboard() {
        _screen.value = Screen.Login(canCancel = true)
    }

    fun cancelAddDashboard() {
        if (hermes.isSignedIn) _screen.value = Screen.Main
    }

    override fun switchDashboard(id: String) {
        if (id == hermes.connection.value?.id) return
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        _profiles.value = listOf("default")
        hermes.switchDashboard(id)
        viewModelScope.launch {
            refreshProfiles()
            refreshSessions()
        }
    }

    override fun signOut() {
        _sessions.value = SessionsState()
        _chat.value = ChatState()
        _profiles.value = listOf("default")
        if (hermes.signOut()) {
            onSignedIn()
        } else {
            _screen.value = Screen.Login(prefillUrl = hermes.store.lastBaseUrl.orEmpty())
        }
    }

    private fun signOutWithMessage(message: String?) {
        val url = dashboardUrl.orEmpty()
        signOut()
        if (_screen.value is Screen.Login) _screen.value = Screen.Login(url, message)
    }

    // endregion

    private fun startSession() {
        hermes.start()
        viewModelScope.launch {
            refreshProfiles()
            refreshSessions()
        }
    }

    override fun reconnectNow() = hermes.reconnectNow()

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

    // region Profiles and sessions

    private suspend fun refreshProfiles() {
        runCatching { hermes.rest { it.profiles() } }.onSuccess { _profiles.value = it }
    }

    override fun selectProfile(name: String) {
        if (name == hermes.profile.value) return
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        hermes.setProfile(name)
        viewModelScope.launch { refreshSessions() }
    }

    override fun refreshSessionsAsync() {
        viewModelScope.launch { refreshSessions() }
    }

    private suspend fun refreshSessions() {
        _sessions.update { it.copy(loading = true, error = null) }
        try {
            val profile = hermes.profile.value
            val all = mutableListOf<SessionSummary>()
            var offset = 0
            for (page in 0 until 5) {
                val result = hermes.rest { it.sessions(profile, offset) }
                all += result.sessions
                if (!result.hasMore) break
                offset = result.nextOffset
            }
            val sorted = all.distinctBy { it.id }
                .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActiveMs ?: 0 })
            _sessions.value = SessionsState(sorted)
        } catch (e: AuthExpiredException) {
            _sessions.update { it.copy(loading = false) }
        } catch (e: Exception) {
            _sessions.update { it.copy(loading = false, error = e.message ?: "Couldn't load chats.") }
        }
    }

    override fun renameSession(id: String, title: String) = sessionMutation { it.renameSession(id, hermes.profile.value, title) }

    override fun togglePinned(session: SessionSummary) = sessionMutation { it.setPinned(session.id, hermes.profile.value, !session.pinned) }

    override fun archiveSession(id: String) = sessionMutation {
        it.setArchived(id, hermes.profile.value, true)
        if (_chat.value.sessionId == id) _chat.value = ChatState()
    }

    override fun deleteSession(id: String) = sessionMutation {
        it.deleteSession(id, hermes.profile.value)
        if (_chat.value.sessionId == id) _chat.value = ChatState()
    }

    private fun sessionMutation(block: suspend (app.hermesconduit.android.net.DashboardApi) -> Unit) {
        viewModelScope.launch {
            try {
                hermes.rest { block(it) }
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
                val page = hermes.rest { it.messages(sessionId, hermes.profile.value) }
                _chat.update {
                    if (it.sessionId != sessionId) it
                    else it.copy(messages = page.messages, hasEarlier = page.hasMore, loadedOffset = HISTORY_PAGE)
                }
            } catch (e: Exception) {
                _chat.update { if (it.sessionId != sessionId) it else it.copy(error = e.message ?: "Couldn't load this chat.") }
            }
        }
        try {
            val result = hermes.rpc(
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
                val page = hermes.rest { it.messages(sessionId, hermes.profile.value, offset = chat.loadedOffset) }
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
                    val created = hermes.rpc(
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
                val result = hermes.rpc(
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
            runCatching { hermes.rpc("session.interrupt", buildJsonObject { put("session_id", runtimeId) }) }
                .onFailure { e -> _chat.update { it.copy(error = e.message) } }
        }
    }

    override fun respondApproval(choice: String) {
        val approval = _chat.value.approval ?: return
        _chat.update { it.copy(approval = null) }
        viewModelScope.launch {
            try {
                hermes.rpc(
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
                        hermes.respond(request.requestId, buildJsonObject { put("answer", answers.firstOrNull().orEmpty()) })
                    request.isServerRequest -> request.questions.forEachIndexed { i, q ->
                        hermes.rpc(
                            "clarify.lock",
                            buildJsonObject {
                                put("request_id", request.requestId)
                                put("question_id", q.id ?: i.toString())
                                put("answer", answers.getOrElse(i) { "" })
                            },
                        )
                    }
                    else -> request.questions.forEachIndexed { i, q ->
                        hermes.rpc(
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
                hermes.rpc(
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
                val result = hermes.rpc(
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

}
