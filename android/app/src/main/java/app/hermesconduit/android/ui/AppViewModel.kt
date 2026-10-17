package app.hermesconduit.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.hermes
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.data.AppSettings
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
import app.hermesconduit.android.model.long
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
    /** "all", "unread", "needsApproval", "running", "voice", "scheduled" or "source.<kind>". */
    val filter: String = "all",
    val query: String = "",
    val searchResults: List<app.hermesconduit.android.model.SearchMatch>? = null,
    val searching: Boolean = false,
    /** Live status by any session id: "working", "waiting", "starting", "idle". */
    val liveStatus: Map<String, String> = emptyMap(),
    val needsApproval: Set<String> = emptySet(),
    val scheduled: List<SessionSummary>? = null,
    /** Showing the on-disk copy because the dashboard couldn't be reached. */
    val offline: Boolean = false,
) {
    fun statusOf(session: SessionSummary): String? =
        (session.alternateIds + session.id).firstNotNullOfOrNull { liveStatus[it] }

    fun isRunning(session: SessionSummary) = statusOf(session).let { it == "working" || it == "waiting" }

    fun needsApproval(session: SessionSummary) = (session.alternateIds + session.id).any { it in needsApproval }

    val visible: List<SessionSummary>
        get() {
            val base = when {
                filter == "scheduled" -> scheduled.orEmpty()
                else -> sessions
            }
            val filtered = when {
                filter == "unread" -> base.filter { it.unread }
                filter == "needsApproval" -> base.filter { needsApproval(it) }
                filter == "running" -> base.filter { isRunning(it) }
                filter == "voice" -> base.filter { it.sourceKind == "voice" || it.sourceKind == "voice_job" }
                filter.startsWith("source.") -> base.filter { it.sourceKind == filter.removePrefix("source.") }
                else -> base
            }
            if (query.isBlank()) return filtered
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            return filtered.filter { s -> words.all { w -> s.title.lowercase().contains(w) || s.preview?.lowercase()?.contains(w) == true } }
        }
}

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

    private val offline = app.hermesconduit.android.data.OfflineCache(application)

    private val _models = MutableStateFlow<ModelOptions?>(null)
    val models: StateFlow<ModelOptions?> = _models

    val dashboardUrl: String? get() = hermes.connection.value?.baseUrl

    init {
        viewModelScope.launch { hermes.events.collect(::onEvent) }
        viewModelScope.launch {
            hermes.connected.collect {
                launch { app.hermesconduit.android.ui.bots.BotRoster.refresh(hermes) }
                refreshLiveStatus()
                app.hermesconduit.android.ui.present.PresentedScreens.sync(getApplication())
                app.hermesconduit.android.ui.agentsettings.AgentSettingsSync.sync(getApplication())
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
                val failure = app.hermesconduit.android.net.ConnectionFailure.classify(e)
                val text = if (failure == app.hermesconduit.android.net.ConnectionFailure.UNKNOWN) e.message ?: failure.detail else "${failure.title}. ${failure.detail}"
                _screen.value = Screen.Login(url, text, canCancel = canCancel)
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
        app.hermesconduit.android.notify.ConnectionService.sync(getApplication())
        _chat.value = ChatState()
        _sessions.value = SessionsState()
        _screen.value = Screen.Main
        startSession()
    }

    private val _connectionTest = MutableStateFlow<ConnectionTestResult?>(null)
    val connectionTest: StateFlow<ConnectionTestResult?> = _connectionTest

    fun testConnection(url: String, username: String, password: String, cfId: String, cfSecret: String) {
        val candidate = candidate(url, username, password, cfId, cfSecret)
        if (candidate == null) {
            _connectionTest.value = ConnectionTestResult(listOf(L10n.tr("Dashboard reachable") to "failed"), app.hermesconduit.android.net.ConnectionFailure.INVALID_ADDRESS)
            return
        }
        viewModelScope.launch { ConnectionTest.run(candidate, hermes.baseHttp, viewModelScope) { _connectionTest.value = it } }
    }

    fun dismissConnectionTest() { _connectionTest.value = null }

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
        viewModelScope.launch { kotlinx.coroutines.delay(100); app.hermesconduit.android.notify.ConnectionService.sync(getApplication()) }
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
        restoreLastChat()
    }

    /** Settings > Chat > When returning: Continue reopens the chat you were reading. */
    private fun restoreLastChat() {
        if (settings.getString(AppSettings.CHAT_RESUME, "continue") != "continue" || _chat.value.sessionId != null) return
        val parts = settings.getString(AppSettings.LAST_SESSION, "").split('|', limit = 4)
        if (parts.size < 4 || parts[0] != hermes.connection.value?.id || parts[1] != hermes.profile.value) return
        _chat.value = ChatState(sessionId = parts[2], title = parts[3], loading = true)
        viewModelScope.launch {
            try {
                val page = hermes.rest { it.messages(parts[2], hermes.profile.value) }
                _chat.update { if (it.sessionId == parts[2]) it.copy(messages = page.messages, hasEarlier = page.hasMore, loadedOffset = HISTORY_PAGE, loading = false) else it }
            } catch (_: Exception) {
                _chat.update { if (it.sessionId == parts[2]) ChatState() else it }
            }
        }
    }

    override fun reconnectNow() = hermes.reconnectNow()

    override fun repairConnection() {
        viewModelScope.launch {
            hermes.repair().onFailure { e -> _chat.update { it.copy(error = L10n.tr("Couldn't repair the connection: %s", e.message ?: "")) } }
        }
    }

    private val responseHaptics = app.hermesconduit.android.core.Haptics.ResponseTracker()

    private fun onEvent(event: StreamEvent) {
        if (event is StreamEvent.Title) {
            _sessions.update { s ->
                s.copy(sessions = s.sessions.map { if (it.id == event.storedSessionId) it.copy(title = event.title) else it })
            }
        }
        if (event is StreamEvent.Busy || event is StreamEvent.MessageStart) viewModelScope.launch { refreshLiveStatus() }
        if (event is StreamEvent.Approval) _sessions.update { it.copy(needsApproval = it.needsApproval + event.sessionId) }
        if (event is StreamEvent.RequestCancelled && event.method == "approval") _sessions.update { it.copy(needsApproval = it.needsApproval - event.sessionId) }
        val chat = _chat.value
        // A Bot Chat keeps the bot's name; its wire title stays "Bot Chat".
        if (event is StreamEvent.Title && chat.botProfile != null) return
        val addressed = event.sessionId.isEmpty() || event.sessionId == chat.runtimeId ||
            (event is StreamEvent.Title && event.storedSessionId == chat.sessionId)
        if (!addressed) return
        val next = ChatReducer.reduce(chat, event)
        _chat.value = next
        app.hermesconduit.android.notify.ForegroundState.openSessionIds = setOfNotNull(next.sessionId, next.runtimeId)
        responseHaptics.onEvent(event)
        if ((event is StreamEvent.ToolStart && event.name.equals("conduit_present", true)) ||
            (event is StreamEvent.ToolComplete && event.name.equals("conduit_present", true))
        ) app.hermesconduit.android.ui.present.PresentedScreens.sync(getApplication())
        if ((event is StreamEvent.ToolComplete && event.name.contains("conduit_settings", true))) app.hermesconduit.android.ui.agentsettings.AgentSettingsSync.sync(getApplication())
        if (chat.running && !next.running) next.sessionId?.let { offline.saveTranscript(hermes.connection.value?.id, hermes.profile.value, it, next.title, next.messages) }
        if (chat.running && !next.running) viewModelScope.launch {
            refreshSessions()
            refreshLiveStatus()
            maybeGenerateTitle(next)
        }
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

    // region Sidebar extras

    private var searchJob: kotlinx.coroutines.Job? = null

    override fun setSessionFilter(filter: String) {
        _sessions.update { it.copy(filter = filter) }
        if (filter == "scheduled" && _sessions.value.scheduled == null) {
            viewModelScope.launch {
                runCatching { hermes.rest { it.cronSessions(hermes.profile.value) } }
                    .onSuccess { list -> _sessions.update { it.copy(scheduled = list) } }
            }
        }
    }

    /** Filters titles locally right away; Hermes' full-text search fills in older chats. */
    override fun setSessionQuery(query: String) {
        _sessions.update { it.copy(query = query, searchResults = if (query.isBlank()) null else it.searchResults) }
        searchJob?.cancel()
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            _sessions.update { it.copy(searching = true) }
            val results = runCatching { hermes.rest { it.searchSessions(query, hermes.profile.value) } }.getOrNull()
            _sessions.update { it.copy(searching = false, searchResults = results) }
        }
    }

    override fun openSessionById(id: String, title: String) {
        val known = _sessions.value.sessions.firstOrNull { it.matches(id) }
        openSession(known ?: SessionSummary(id, title.ifEmpty { L10n.tr("Untitled chat") }, null, null, null, false, false, false, null, null, null))
    }

    override fun setUnread(session: SessionSummary, unread: Boolean) {
        _sessions.update { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(unread = unread) else it }) }
        viewModelScope.launch {
            runCatching { hermes.rest { it.setUnread(session.id, hermes.profile.value, unread) } }
                .onFailure { e -> _sessions.update { it.copy(error = e.message) } }
        }
    }

    override fun markAllRead() {
        val unread = _sessions.value.sessions.filter { it.unread }
        unread.forEach { setUnread(it, false) }
    }

    /** `session.active_list`: which chats are working or waiting right now. */
    private suspend fun refreshLiveStatus() {
        val result = runCatching { hermes.rpc("session.active_list", buildJsonObject {}).obj }.getOrNull() ?: return
        val map = mutableMapOf<String, String>()
        (result["sessions"] as? JsonArray)?.forEach { row ->
            val o = row.obj ?: return@forEach
            val status = o["status"].str ?: return@forEach
            listOf("id", "session_key", "session_id", "stored_session_id").mapNotNull { o[it].str }.forEach { map[it] = status }
        }
        _sessions.update { it.copy(liveStatus = map) }
    }

    // endregion

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
            val roster = app.hermesconduit.android.ui.bots.BotRoster.bots.value
            val sorted = all.distinctBy { it.id }
                .filterNot { app.hermesconduit.android.model.BotChatHygiene.isCanonicalBotChatRow(it, roster) }
                .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActiveMs ?: 0 })
            _sessions.update { it.copy(sessions = sorted, loading = false, error = null, offline = false) }
            offline.saveSessions(hermes.connection.value?.id, profile, sorted)
            app.hermesconduit.android.widget.WidgetData.refresh(getApplication())
        } catch (e: AuthExpiredException) {
            _sessions.update { it.copy(loading = false) }
        } catch (e: Exception) {
            val cached = if (_sessions.value.sessions.isEmpty()) offline.sessions(hermes.connection.value?.id, hermes.profile.value) else null
            _sessions.update {
                it.copy(
                    loading = false,
                    error = e.message ?: "Couldn't load chats.",
                    sessions = cached ?: it.sessions,
                    offline = cached != null || it.offline,
                )
            }
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

    /** A new chat anchored in a project folder. */
    fun newChatIn(cwd: String, title: String? = null) {
        _chat.value = ChatState(cwd = cwd, title = title ?: L10n.tr("New chat"), model = _chat.value.model, provider = _chat.value.provider)
    }

    /** `session.workspace.move`: project membership follows the session's folder. */
    fun moveSessionToFolder(session: SessionSummary, cwd: String) {
        viewModelScope.launch {
            runCatching {
                hermes.rpc("session.workspace.move", buildJsonObject { put("session_key", session.id); put("cwd", cwd) })
            }.onSuccess { refreshSessions() }
                .onFailure { e -> _sessions.update { it.copy(error = e.message) } }
        }
    }

    override fun newChat() {
        settings.set(AppSettings.LAST_SESSION, "")
        _chat.value = ChatState(model = _chat.value.model, provider = _chat.value.provider)
    }

    private val settings = application.appSettings

    private val _openRoute = MutableStateFlow<String?>(null)
    /** A route a link asked to open (consumed by the navigation host). */
    val openRoute: StateFlow<String?> = _openRoute

    fun consumeOpenRoute() { _openRoute.value = null }

    /** Opens a route from outside the navigation host (an answered call, a wake phrase). */
    fun requestRoute(route: String) { _openRoute.value = route }

    fun handleLink(link: app.hermesconduit.android.core.AppLink) {
        when (link) {
            is app.hermesconduit.android.core.AppLink.Session -> openFromNotification(link.id, null, null, link.profile)
            is app.hermesconduit.android.core.AppLink.Bot -> _openRoute.value = "bots/" + android.net.Uri.encode(link.profile)
            is app.hermesconduit.android.core.AppLink.Settings -> _openRoute.value = app.hermesconduit.android.core.AppLink.settingsRoute(link.page)
            app.hermesconduit.android.core.AppLink.NewChat -> { newChat(); _openRoute.value = "home" }
            app.hermesconduit.android.core.AppLink.Voice -> _openRoute.value = "voice"
            app.hermesconduit.android.core.AppLink.Open -> Unit
        }
    }

    /** Text and files shared from another app land in a new chat's composer. */
    fun receiveShare(text: String, uris: List<android.net.Uri>) {
        newChat()
        _chat.update { it.copy(prefill = text.ifEmpty { null }, prefillAttachments = uris) }
        _openRoute.value = "home"
    }

    /** A notification tap: switch to its dashboard and profile, then open the chat. */
    fun openFromNotification(sessionId: String, title: String?, dashboardId: String?, profile: String?) {
        if (dashboardId != null && dashboardId != hermes.connection.value?.id) switchDashboard(dashboardId)
        if (profile != null && profile != hermes.profile.value) selectProfile(profile)
        openSessionById(sessionId, title.orEmpty())
    }

    override fun openSession(session: SessionSummary) {
        app.hermesconduit.android.notify.ForegroundState.openSessionIds = session.alternateIds + session.id
        settings.set(AppSettings.LAST_SESSION, "${hermes.connection.value?.id}|${hermes.profile.value}|${session.id}|${session.title}")
        if (session.unread) setUnread(session, false)
        _chat.value = ChatState(sessionId = session.id, title = session.title, loading = true, model = session.model)
        viewModelScope.launch { resume(session.id, reloadHistory = true) }
    }

    /** Opens a bot's canonical Bot Chat (resolved by the Bots screen) in the bot's own profile. */
    fun openBotChat(resumeId: String, registryId: String, profile: String, label: String) {
        hermes.scopeSession(resumeId, profile)
        hermes.scopeSession(registryId, profile)
        _chat.value = ChatState(sessionId = resumeId, title = label, loading = true, botProfile = profile)
        viewModelScope.launch { resume(resumeId, reloadHistory = true) }
    }

    /** Leaves the open chat when it is [profile]'s Bot Chat (the bot was deleted). */
    fun leaveBotChat(profile: String) {
        if (_chat.value.botProfile.equals(profile, ignoreCase = true)) newChat()
    }

    fun refreshProfilesAsync() {
        viewModelScope.launch { refreshProfiles() }
    }

    /** The profile the open conversation runs in: the bot's inside a Bot Chat. */
    private val conversationProfile: String get() = _chat.value.botProfile ?: hermes.profile.value

    override fun mentionCandidates(): List<app.hermesconduit.android.model.MentionAutocomplete.Candidate> =
        app.hermesconduit.android.model.MentionAutocomplete.botCandidates(app.hermesconduit.android.ui.bots.BotRoster.bots.value, conversationProfile)

    private suspend fun resume(sessionId: String, reloadHistory: Boolean) {
        if (reloadHistory) {
            try {
                val page = hermes.rest { it.messages(sessionId, conversationProfile) }
                _chat.update {
                    if (it.sessionId != sessionId) it
                    else it.copy(messages = page.messages, hasEarlier = page.hasMore, loadedOffset = HISTORY_PAGE, offlineSavedAtMs = null)
                }
                offline.saveTranscript(hermes.connection.value?.id, hermes.profile.value, sessionId, _chat.value.title, page.messages)
            } catch (e: Exception) {
                val cached = offline.transcript(hermes.connection.value?.id, hermes.profile.value, sessionId)
                _chat.update {
                    if (it.sessionId != sessionId) it
                    else if (cached != null) it.copy(messages = cached.first, offlineSavedAtMs = cached.second, loading = false)
                    else it.copy(error = e.message ?: "Couldn't load this chat.")
                }
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
            val runtime = result?.get("session_id").str
            val openRequests = result?.get("open_requests") as? JsonArray
            val inputPrompt = openRequests?.firstNotNullOfOrNull { element ->
                val request = element.obj ?: return@firstNotNullOfOrNull null
                MessageNormalizer.inputPrompt(request["id"].str.orEmpty(), request["method"].str.orEmpty(), request["params"].obj)
            }
            val pendingApproval = result?.get("pending_approval").obj
                ?.let { MessageNormalizer.approvalRequest(it, result?.get("session_id").str.orEmpty()) }
            _chat.update {
                if (it.sessionId != sessionId) it
                else (if (info != null) ChatReducer.applyInfo(it, info) else it).copy(
                    inputPrompt = inputPrompt ?: it.inputPrompt,
                    runtimeId = runtime ?: it.runtimeId,
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
            // Approvals raised before this socket attached aren't re-sent as events.
            if (runtime != null && pendingApproval == null) {
                runCatching { hermes.rpc("approval.pending", buildJsonObject { put("session_id", runtime) }).obj }
                    .getOrNull()?.get("approvals").let { it as? JsonArray }?.firstOrNull()?.obj
                    ?.let { MessageNormalizer.approvalRequest(it, runtime) }
                    ?.let { approval -> _chat.update { c -> if (c.sessionId == sessionId) c.copy(approval = approval) else c } }
            }
            loadSlashCatalog()
        } catch (e: Exception) {
            _chat.update { if (it.sessionId != sessionId) it else it.copy(loading = false) }
        }
    }

    private val titledSessions = mutableSetOf<String>()

    /**
     * After a new chat's first turn, Hermes usually titles it (session.title).
     * When it hasn't after a moment, ask the gateway for its title, and failing
     * that generate one with `llm.oneshot` if title generation is enabled.
     */
    private fun maybeGenerateTitle(chat: ChatState) {
        val runtime = chat.runtimeId ?: return
        if (chat.botProfile != null) return
        val users = chat.messages.filter { it.role == MessageRole.USER }
        val assistant = chat.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() } ?: return
        if (users.size != 1 || !titledSessions.add(runtime)) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_500)
            val known = _sessions.value.sessions.firstOrNull { it.matches(chat.sessionId) }
            if (known != null && known.title != users.first().content.take(60) && known.title != L10n.tr("Untitled chat")) return@launch
            runCatching {
                val existing = hermes.rpc("session.title", buildJsonObject { put("session_id", runtime) }).obj?.get("title").str?.trim()
                if (!existing.isNullOrEmpty()) {
                    _chat.update { if (it.runtimeId == runtime) it.copy(title = existing) else it }
                    refreshSessions()
                    return@launch
                }
                val config = hermes.rest { it.getJson("api/config", mapOf("profile" to hermes.profile.value.takeIf { p -> p != "default" })) }
                val aux = (config["config"].obj ?: config)["auxiliary"].obj?.get("title_generation").obj
                val enabled = aux?.get("enabled").let { e -> e.bool ?: e.str?.lowercase()?.let { it !in setOf("false", "0", "off", "no") } } ?: true
                if (!enabled) return@launch
                val language = aux?.get("language").str?.trim().orEmpty()
                val instruction = if (language.isEmpty()) "Write the title in the same language the user is writing in." else "Write the title in $language."
                val generated = hermes.rpc(
                    "llm.oneshot",
                    buildJsonObject {
                        put("session_id", runtime)
                        put("task", "title_generation")
                        put("instructions", "Generate a short, descriptive title (3-7 words) for a conversation that starts with the following exchange. The title should capture the main topic or intent. $instruction Return ONLY the title text, nothing else. No quotes, no punctuation at the end, no prefixes.")
                        put("input", "User: ${users.first().content.take(500)}\n\nAssistant: ${assistant.content.take(500)}")
                        put("max_tokens", 500)
                        put("temperature", 0.3)
                    },
                    90_000,
                ).obj?.get("text").str?.let(MessageNormalizer::generatedTitle) ?: return@launch
                hermes.rpc("session.title", buildJsonObject { put("session_id", runtime); put("title", generated) })
                _chat.update { if (it.runtimeId == runtime) it.copy(title = generated) else it }
                refreshSessions()
            }
        }
    }

    // region Chat extras

    private val _slashCommands = MutableStateFlow(SlashCommands.builtIns())
    val slashCommands: StateFlow<List<app.hermesconduit.android.model.SlashCommand>> = _slashCommands

    private val _contextBreakdown = MutableStateFlow<app.hermesconduit.android.model.ContextBreakdown?>(null)
    val contextBreakdown: StateFlow<app.hermesconduit.android.model.ContextBreakdown?> = _contextBreakdown

    private val _delegates = MutableStateFlow<List<app.hermesconduit.android.model.DelegateAgent>?>(null)
    val delegates: StateFlow<List<app.hermesconduit.android.model.DelegateAgent>?> = _delegates

    private fun loadSlashCatalog() {
        viewModelScope.launch {
            val result = runCatching {
                hermes.rpc("commands.catalog", buildJsonObject { _chat.value.runtimeId?.let { put("session_id", it) } })
            }.getOrNull() ?: return@launch
            _slashCommands.value = MessageNormalizer.slashCatalog(result, SlashCommands.builtIns())
        }
    }

    override fun refreshChat() {
        val id = _chat.value.sessionId ?: return
        _chat.update { it.copy(loading = true) }
        viewModelScope.launch { resume(id, reloadHistory = true) }
    }

    private fun currentSummary(): SessionSummary? = _chat.value.sessionId?.let { id -> _sessions.value.sessions.firstOrNull { it.matches(id) } }

    override fun renameCurrent(title: String) {
        val id = _chat.value.sessionId ?: return
        _chat.update { it.copy(title = title) }
        renameSession(id, title)
    }

    override fun archiveCurrent() { _chat.value.sessionId?.let { archiveSession(it) } }

    override fun deleteCurrent() { _chat.value.sessionId?.let { deleteSession(it) } }

    override fun togglePinCurrent() { currentSummary()?.let { togglePinned(it) } }

    override fun consumePrefill() = _chat.update { it.copy(prefill = null, prefillAttachments = emptyList()) }

    override fun quote(message: ChatMessage) {
        val quoted = message.content.trim().lines().joinToString("\n") { "> $it" }
        _chat.update { it.copy(prefill = "$quoted\n\n") }
    }

    override fun react(message: ChatMessage, emoji: String) {
        val runtimeId = _chat.value.runtimeId ?: return
        val mine = message.reactions.firstOrNull { it.isFromUser }?.emoji
        val next = if (mine == emoji) null else emoji
        val optimistic = app.hermesconduit.android.model.MessageReaction.applying(next, app.hermesconduit.android.model.MessageReaction.USER, message.reactions)
        _chat.update { c -> c.copy(messages = c.messages.map { if (it.id == message.id) it.copy(reactions = optimistic) else it }) }
        viewModelScope.launch {
            try {
                val result = hermes.rpc(
                    "message.react",
                    buildJsonObject {
                        put("session_id", runtimeId)
                        put("author", app.hermesconduit.android.model.MessageReaction.USER)
                        if (next == null) put("emoji", kotlinx.serialization.json.JsonNull) else put("emoji", next)
                        if (message.rowId != null) put("row_id", message.rowId)
                        else put("newest_role", if (message.role == MessageRole.USER) "user" else "assistant")
                    },
                ).obj
                val rowId = result?.get("row_id").long
                val reactions = MessageNormalizer.reactions(result?.get("reactions"))
                _chat.update { c -> c.copy(messages = c.messages.map { if (it.id == message.id) it.copy(rowId = rowId ?: it.rowId, reactions = reactions) else it }) }
            } catch (e: Exception) {
                _chat.update { c -> c.copy(error = e.message, messages = c.messages.map { if (it.id == message.id) it.copy(reactions = message.reactions) else it }) }
            }
        }
    }

    /** Forks a new chat whose history ends at [message] (an assistant reply). */
    override fun branchFrom(message: ChatMessage) {
        val chat = _chat.value
        val parent = chat.runtimeId ?: chat.sessionId ?: return
        if (chat.running) {
            _chat.update { it.copy(error = L10n.tr("Stop the active response before branching this conversation.")) }
            return
        }
        val index = chat.messages.indexOfFirst { it.id == message.id }
        if (index < 0) return
        val history = chat.messages.subList(0, index + 1)
            .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() }
        if (history.isEmpty()) {
            _chat.update { it.copy(error = L10n.tr("There is no message history to branch from.")) }
            return
        }
        val title = L10n.tr("Branch of %s", chat.title)
        viewModelScope.launch {
            try {
                val created = hermes.rpc(
                    "session.create",
                    buildJsonObject {
                        put("cols", 96)
                        put("source", "desktop")
                        put("parent_session_id", parent)
                        put("title", title)
                        put("messages", kotlinx.serialization.json.buildJsonArray {
                            history.forEach { m ->
                                add(buildJsonObject {
                                    put("role", if (m.role == MessageRole.USER) "user" else "assistant")
                                    put("content", m.content)
                                })
                            }
                        })
                        chat.cwd?.let { put("cwd", it) }
                    },
                ).obj
                val runtime = created?.get("session_id").str ?: error("The gateway didn't create a session.")
                val stored = created?.get("stored_session_id").str ?: runtime
                _chat.value = ChatState(
                    sessionId = stored,
                    runtimeId = runtime,
                    title = title,
                    messages = history,
                    model = chat.model,
                    provider = chat.provider,
                )
                refreshSessions()
            } catch (e: Exception) {
                _chat.update { it.copy(error = e.message ?: L10n.tr("Couldn't branch this conversation.")) }
            }
        }
    }

    private suspend fun ensureRuntime(): String? {
        _chat.value.runtimeId?.let { return it }
        val created = hermes.rpc("session.create", buildJsonObject { put("cols", 96); put("source", "desktop") }).obj
        val runtime = created?.get("session_id").str ?: return null
        val stored = created?.get("stored_session_id").str ?: runtime
        _chat.update { it.copy(runtimeId = runtime, sessionId = stored) }
        return runtime
    }

    /** Runs a `/command`; returns false when [text] isn't one. */
    override fun runSlash(text: String): Boolean {
        // The forever chat: /new and /reset compact a Bot Chat instead of forking it.
        BotMentionsHook.reroute(text, _chat.value)?.let { return runSlash(it) }
        val (name, argument, cleaned) = MessageNormalizer.parseSlash(text) ?: return false
        appendLocal(MessageRole.USER, text.trim())
        when (name) {
            "new", "reset" -> { newChat(); return true }
            "branch", "fork" -> {
                val last = _chat.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (last == null) _chat.update { it.copy(error = L10n.tr("There is no assistant response to branch from yet.")) } else branchFrom(last)
                return true
            }
            "help", "commands" -> { appendSlashOutput(SlashCommands.help(_slashCommands.value)); return true }
            "yolo" -> if (argument.isEmpty()) { setYolo(!_chat.value.yolo); return true }
            "compress", "compact" -> { compress(argument); return true }
            "model" -> if (argument.isEmpty()) { _chat.update { it.copy(notice = L10n.tr("Tap the model name at the top to change models.")) }; return true }
        }
        viewModelScope.launch {
            try {
                val runtime = ensureRuntime() ?: error("The gateway didn't create a session.")
                handleSlashResult(executeGatewaySlash(runtime, cleaned), 0, argument, runtime)
            } catch (e: Exception) {
                appendSlashOutput(L10n.tr("⚠️ Command failed: %s", e.message ?: ""))
            }
        }
        return true
    }

    private suspend fun executeGatewaySlash(runtime: String, command: String): kotlinx.serialization.json.JsonElement = try {
        hermes.rpc("slash.exec", buildJsonObject { put("session_id", runtime); put("command", command.trimStart('/')) })
    } catch (rejection: app.hermesconduit.android.net.RpcException) {
        val (name, arg, _) = MessageNormalizer.parseSlash("/$command") ?: throw rejection
        try {
            hermes.rpc("command.dispatch", buildJsonObject { put("session_id", runtime); put("name", name); put("arg", arg) })
        } catch (e: Exception) {
            if ("command.dispatch" in (rejection.message ?: "")) throw e
            throw rejection
        }
    }

    private suspend fun handleSlashResult(result: kotlinx.serialization.json.JsonElement, depth: Int, aliasArgument: String, runtime: String) {
        if (depth >= 4) { appendSlashOutput(L10n.tr("⚠️ Too many command aliases.")); return }
        val obj = result.obj ?: return
        val output = obj.firstString("output", "message", "notice").orEmpty()
        when (obj["type"].str.orEmpty()) {
            "exec", "plugin" -> if (output.isNotEmpty()) appendSlashOutput(output)
            "send", "skill" -> (obj["message"].str ?: output).takeIf { it.isNotEmpty() }?.let { sendRaw(it) }
            "prefill" -> {
                obj["notice"].str?.takeIf { it.isNotEmpty() }?.let(::appendSlashOutput)
                (obj["message"].str ?: output).takeIf { it.isNotEmpty() }?.let { m -> _chat.update { it.copy(prefill = m) } }
            }
            "alias" -> {
                val target = obj.firstString("target", "command", "name") ?: return
                val nested = executeGatewaySlash(runtime, if (aliasArgument.isEmpty()) target else "$target $aliasArgument")
                handleSlashResult(nested, depth + 1, aliasArgument, runtime)
            }
            else -> if (output.isNotEmpty()) appendSlashOutput(output)
        }
    }

    /** Sends [text] as a prompt without echoing it again. */
    private fun sendRaw(text: String) = send(text, emptyList(), null)

    private fun appendLocal(role: MessageRole, text: String) {
        _chat.update { it.copy(messages = it.messages + ChatMessage("local-${System.nanoTime()}", role, text, timestampMs = System.currentTimeMillis())) }
    }

    private fun appendSlashOutput(text: String) {
        _chat.update { it.copy(messages = it.messages + ChatMessage("slash-${System.nanoTime()}", MessageRole.SYSTEM, text, kind = "slash", timestampMs = System.currentTimeMillis())) }
    }

    override fun compress(focusTopic: String) {
        viewModelScope.launch {
            _chat.update { it.copy(compacting = true, notice = L10n.tr("Compressing this conversation…")) }
            try {
                val runtime = ensureRuntime() ?: error("No session.")
                val result = hermes.rpc(
                    "session.compress",
                    buildJsonObject {
                        put("session_id", runtime)
                        focusTopic.trim().takeIf { it.isNotEmpty() }?.let { put("focus_topic", it) }
                    },
                    660_000,
                ).obj
                val summary = result?.firstString("summary", "message", "output")
                _chat.update { it.copy(compacting = false, notice = summary ?: L10n.tr("Context compacted.")) }
            } catch (e: Exception) {
                _chat.update { it.copy(compacting = false, notice = null, error = L10n.tr("Compression failed: %s", e.message ?: "")) }
            }
        }
    }

    override fun loadContextBreakdown() {
        val runtime = _chat.value.runtimeId ?: return
        _contextBreakdown.value = null
        viewModelScope.launch {
            runCatching { hermes.rpc("session.context_breakdown", buildJsonObject { put("session_id", runtime) }).obj }
                .onSuccess { _contextBreakdown.value = MessageNormalizer.contextBreakdown(it) }
                .onFailure { e -> _chat.update { it.copy(error = e.message) } }
        }
    }

    override fun loadDelegates() {
        val runtime = _chat.value.runtimeId ?: return
        _delegates.value = null
        viewModelScope.launch {
            runCatching { hermes.rpc("subagent.list", buildJsonObject { put("session_id", runtime) }) }
                .onSuccess { _delegates.value = MessageNormalizer.delegateAgents(it) }
                .onFailure { _delegates.value = emptyList() }
        }
    }

    private fun configSet(key: String, value: String, apply: (ChatState) -> ChatState) {
        val runtime = _chat.value.runtimeId
        if (runtime == null) {
            _chat.update { it.copy(notice = L10n.tr("Send a first message, then change this chat's settings.")) }
            return
        }
        val before = _chat.value
        _chat.update(apply)
        viewModelScope.launch {
            runCatching {
                hermes.rpc("config.set", buildJsonObject { put("key", key); put("session_id", runtime); put("value", value) })
            }.onFailure { e -> _chat.update { it.copy(error = e.message, reasoningEffort = before.reasoningEffort, fast = before.fast, yolo = before.yolo) } }
        }
    }

    override fun setReasoning(effort: String) = configSet("reasoning", effort) { it.copy(reasoningEffort = effort) }

    override fun setFast(enabled: Boolean) = configSet("fast", if (enabled) "fast" else "normal") { it.copy(fast = enabled) }

    override fun setYolo(enabled: Boolean) = configSet("yolo", if (enabled) "1" else "0") { it.copy(yolo = enabled) }

    /** Answers a sudo/secret/vault request; an empty value skips. */
    override fun respondInputPrompt(value: String) {
        val prompt = _chat.value.inputPrompt ?: return
        hermes.respond(prompt.requestId, buildJsonObject { put("value", value) })
        _chat.update { it.copy(inputPrompt = null) }
    }

    // endregion

    override fun loadEarlier() {
        val chat = _chat.value
        val sessionId = chat.sessionId ?: return
        if (!chat.hasEarlier || chat.loading) return
        _chat.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val page = hermes.rest { it.messages(sessionId, conversationProfile, offset = chat.loadedOffset) }
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

    override fun send(text: String) = send(text, emptyList(), null)

    override fun send(text: String, attachments: List<app.hermesconduit.android.model.Attachment>) = send(text, attachments, null)

    fun send(text: String, attachments: List<app.hermesconduit.android.model.Attachment>, surface: String?) {
        viewModelScope.launch { submitPrompt(text, attachments, surface) }
    }

    /**
     * Sends a prompt (creating the chat first when needed) and reports whether
     * Hermes accepted it. `surface` "voice-live" marks a spoken turn
     * (`voice_turn`), so Hermes answers in a form meant to be read aloud.
     */
    suspend fun submitPrompt(text: String, attachments: List<app.hermesconduit.android.model.Attachment>, surface: String?): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        app.hermesconduit.android.core.Haptics.light()
        val shown = listOf(trimmed, attachments.joinToString("\n") { "📎 ${it.name}" }).filter { it.isNotEmpty() }.joinToString("\n\n")
        val local = ChatMessage("local-${System.nanoTime()}", MessageRole.USER, shown, timestampMs = System.currentTimeMillis())
        _chat.update { it.copy(messages = it.messages + local, error = null, running = true) }
        run {
            try {
                // Location-tagged sends: a fix goes first, waited on for at most 2.5 s (Settings > Location).
                app.hermesconduit.android.ui.device.DeviceSharing.beforeSending(getApplication())
                var runtimeId = _chat.value.runtimeId
                if (runtimeId == null) {
                    val cwd = _chat.value.cwd
                    val created = hermes.rpc(
                        "session.create",
                        buildJsonObject {
                            put("cols", 96)
                            put("source", "desktop")
                            if (cwd != null) put("cwd", cwd)
                            _chat.value.botProfile?.let { put("profile", it) }
                        },
                    ).obj
                    runtimeId = created?.get("session_id").str ?: error("The gateway didn't create a session.")
                    val stored = created?.get("stored_session_id").str ?: runtimeId
                    _chat.update { it.copy(runtimeId = runtimeId, sessionId = stored, title = trimmed.ifEmpty { attachments.first().name }.take(60)) }
                }
                val references = mutableListOf<String>()
                for (attachment in attachments) {
                    references += uploadAttachment(runtimeId, attachment) ?: continue
                }
                val outbound = BotMentionsHook.annotate(hermes, trimmed, conversationProfile)
                val voiceTurn = (surface == "voice" || surface == "voice-live") && !gatewayRefusesVoiceTurn
                fun params(marksVoiceTurn: Boolean) = buildJsonObject {
                    put("session_id", runtimeId)
                    put("text", app.hermesconduit.android.model.Attachment.promptText(outbound, references))
                    if (surface != null) put("surface", surface)
                    if (marksVoiceTurn) put("voice_turn", true)
                }
                val timeout = app.hermesconduit.android.net.GatewayClient.timeoutFor("prompt.submit")
                val result = try {
                    hermes.rpc("prompt.submit", params(voiceTurn), timeout).obj
                } catch (e: app.hermesconduit.android.net.RpcException) {
                    // A Hermes from before `voice_turn` refuses the key: send the turn without it.
                    if (!voiceTurn || e.code != 4000 || "invalid params" !in e.message.orEmpty() || "voice_turn" !in e.message.orEmpty()) throw e
                    gatewayRefusesVoiceTurn = true
                    hermes.rpc("prompt.submit", params(false), timeout).obj
                }
                when (result?.get("status").str) {
                    "queued" -> _chat.update { it.copy(notice = L10n.tr("Queued — Hermes will answer after the current turn.")) }
                    "steered" -> _chat.update { it.copy(notice = L10n.tr("Sent as guidance for the running turn.")) }
                }
                return true
            } catch (e: app.hermesconduit.android.net.RpcException) {
                if (e.reason == "SESSION_NOT_OWNED" || e.message?.contains("SESSION_NOT_OWNED") == true) {
                    val (id, surface) = TakeoverState.details(e.message.orEmpty())
                    val ids = listOfNotNull(id, _chat.value.sessionId, _chat.value.runtimeId).distinct()
                    _chat.update { c -> c.copy(running = false, messages = c.messages.filterNot { it.id == local.id }, takeover = TakeoverState(ids, surface, trimmed)) }
                    if (settings.getBool(AppSettings.AUTO_TAKEOVER, false)) takeOver()
                } else {
                    _chat.update { it.copy(running = false, error = e.message ?: "Couldn't send.") }
                }
                return false
            } catch (e: Exception) {
                _chat.update { it.copy(running = false, error = e.message ?: "Couldn't send.") }
                return false
            }
        }
    }

    /** POST the plugin's sessions/takeover, then resend what was refused. */
    override fun takeOver() {
        val state = _chat.value.takeover ?: return
        _chat.update { it.copy(takeover = state.copy(phase = "waiting")) }
        viewModelScope.launch {
            try {
                val profile = hermes.profile.value.takeIf { it != "default" }
                val response = hermes.rest {
                    it.sendJson(
                        "POST", "api/plugins/conduit_push/sessions/takeover",
                        buildJsonObject { put("session_ids", kotlinx.serialization.json.buildJsonArray { state.sessionIds.take(4).forEach { id -> add(kotlinx.serialization.json.JsonPrimitive(id)) } }) },
                        mapOf("profile" to profile),
                    )
                }
                if (response["ok"].bool != true) error("malformed")
                when (response["status"].str) {
                    "taken_over", "free" -> {
                        _chat.update { it.copy(takeover = null) }
                        _chat.value.sessionId?.let { resume(it, reloadHistory = false) }
                        if (state.refusedText.isNotBlank()) send(state.refusedText, emptyList(), null)
                    }
                    "busy" -> _chat.update { it.copy(takeover = state.copy(phase = "failed", message = takeoverBusy(state))) }
                    "same_host" -> _chat.update { it.copy(takeover = state.copy(phase = "unavailable", message = L10n.tr("This chat is open on the same computer as Hermes. Close it there to send from here."))) }
                    else -> error("malformed")
                }
            } catch (e: app.hermesconduit.android.net.DashboardHttpException) {
                val message = if (e.code == 404 || e.code == 405) L10n.tr("Taking over chats needs a newer Conduit plugin on this Hermes.") else e.message
                _chat.update { it.copy(takeover = state.copy(phase = "unavailable", message = message)) }
            } catch (e: Exception) {
                _chat.update { it.copy(takeover = state.copy(phase = "failed", message = L10n.tr("Couldn't take over this chat. Try again."))) }
            }
        }
    }

    private fun takeoverBusy(state: TakeoverState) = when (state.owner) {
        "desktop" -> L10n.tr("Hermes Desktop is still replying in this chat. Try again when it finishes.")
        "terminal" -> L10n.tr("A Hermes terminal is still replying in this chat. Try again when it finishes.")
        else -> L10n.tr("Another Hermes window is still replying in this chat. Try again when it finishes.")
    }

    override fun dismissTakeover() = _chat.update { it.copy(takeover = null) }

    /** Set once this gateway refused `prompt.submit`'s `voice_turn` key. */
    private var gatewayRefusesVoiceTurn = false

    /**
     * Images go up with `image.attach_bytes` (falling back to a file when
     * Hermes refuses them as images); everything else is staged with
     * `file.attach`, whose `@file:` reference the prompt must carry.
     */
    private suspend fun uploadAttachment(sessionId: String, attachment: app.hermesconduit.android.model.Attachment): String? {
        if (attachment.isImage) {
            try {
                hermes.rpc(
                    "image.attach_bytes",
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("content_base64", attachment.base64)
                        put("filename", attachment.name)
                    },
                    120_000,
                )
                return null
            } catch (e: app.hermesconduit.android.net.RpcException) {
                if (e.code != 4016 && e.code != 4018) throw e
            }
        }
        val result = hermes.rpc(
            "file.attach",
            buildJsonObject {
                put("session_id", sessionId)
                put("data_url", attachment.dataUrl)
                put("name", attachment.name)
                put("path", "")
            },
            120_000,
        ).obj
        result?.get("ref_text").str?.trim()?.takeIf { it.startsWith("@file:") && it.length > 6 }?.let { return it }
        val path = (result?.get("ref_path").str ?: result?.get("path").str)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "@file:" + app.hermesconduit.android.model.Attachment.formatReferenceValue(path)
    }

    override fun stop() {
        val runtimeId = _chat.value.runtimeId ?: return
        viewModelScope.launch {
            runCatching { hermes.rpc("session.interrupt", buildJsonObject { put("session_id", runtimeId) }) }
                .onFailure { e -> _chat.update { it.copy(error = e.message) } }
        }
    }

    override fun respondApproval(choice: String) {
        app.hermesconduit.android.core.Haptics.medium()
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
        val botProfile = _chat.value.botProfile
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
                                botProfile?.let { put("profile", it) }
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
                                botProfile?.let { put("profile", it) }
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
            _chat.update { it.copy(notice = L10n.tr("Send a first message, then switch the model for this chat.")) }
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
