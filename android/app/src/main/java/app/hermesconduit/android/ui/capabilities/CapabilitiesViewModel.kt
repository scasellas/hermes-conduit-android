package app.hermesconduit.android.ui.capabilities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.AgentPluginRow
import app.hermesconduit.android.model.CapabilityInstallState
import app.hermesconduit.android.model.CapabilityParsers
import app.hermesconduit.android.model.CapabilityRestartMonitor
import app.hermesconduit.android.model.CapabilitySkill
import app.hermesconduit.android.model.CapabilityToolset
import app.hermesconduit.android.model.CapabilityToolsetOrdering
import app.hermesconduit.android.model.ConnectorCatalogEntry
import app.hermesconduit.android.model.ConnectorProbe
import app.hermesconduit.android.model.ConnectorServer
import app.hermesconduit.android.model.CapabilityRestartState
import app.hermesconduit.android.model.HermesActionStatus
import app.hermesconduit.android.model.OfficialSkill
import app.hermesconduit.android.model.PluginCatalogEntry
import app.hermesconduit.android.model.PluginInstallOutcome
import app.hermesconduit.android.model.PluginResponses
import app.hermesconduit.android.model.PluginUpdateOutcome
import app.hermesconduit.android.model.TerminalBackendOption
import app.hermesconduit.android.model.ToolsetConfig
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Everything Settings > Capabilities shows, for one profile on one dashboard. */
data class CapabilitiesState(
    val profile: String = "default",
    // Skills and toolsets
    val skills: List<CapabilitySkill> = emptyList(),
    val toolsets: List<CapabilityToolset> = emptyList(),
    /** A load for this scope has finished (rows, if any, belong to it). */
    val snapshotLoaded: Boolean = false,
    val capabilitiesLoading: Boolean = false,
    val loadError: String? = null,
    /** A failed skill or toolset switch. */
    val toggleError: String? = null,
    val bulkRunning: Boolean = false,
    val officialSkills: List<OfficialSkill> = emptyList(),
    val officialSkillsLoaded: Boolean = false,
    val skillInstalls: Map<String, CapabilityInstallState> = emptyMap(),
    /** Tool name -> calls over the last year; null until read. */
    val toolCalls: Map<String, Int>? = null,
    // Connectors
    val servers: List<ConnectorServer> = emptyList(),
    val connectorCatalog: List<ConnectorCatalogEntry> = emptyList(),
    val connectorsLoaded: Boolean = false,
    val connectorsError: String? = null,
    val probes: Map<String, ConnectorProbe> = emptyMap(),
    val probing: Set<String> = emptySet(),
    val connectorInstalls: Map<String, CapabilityInstallState> = emptyMap(),
    // Plugins
    val plugins: List<AgentPluginRow> = emptyList(),
    val pluginCatalog: List<PluginCatalogEntry> = emptyList(),
    val pluginsLoaded: Boolean = false,
    val pluginsError: String? = null,
    val pluginBusy: Set<String> = emptySet(),
    /** A plugin change Hermes applies only after a gateway restart. */
    val pluginsNeedRestart: Boolean = false,
    val restart: CapabilityRestartState = CapabilityRestartState.Idle,
) {
    /** Hermes' plugin routes act on the dashboard's own (default) profile, so other profiles only read. */
    val canManagePlugins: Boolean get() = profile == "default"
}

/** Thrown for a refusal that already reads as a sentence. */
class CapabilityDeclined(message: String) : Exception(message)

/**
 * Settings > Capabilities (iOS `CapabilitiesStore` plus the skill/toolset
 * loads of `AppState`). Scoped to the active profile and dashboard: a switch
 * drops everything read so far, and a request that lands after it commits
 * nothing.
 */
class CapabilitiesViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val _state = MutableStateFlow(CapabilitiesState())
    val state: StateFlow<CapabilitiesState> = _state

    /** Bumped on every profile or dashboard switch: work started before it commits nothing. */
    @Volatile private var generation = 0
    private var capabilityRequest = 0
    private var restartJob: Job? = null

    private val profile: String get() = _state.value.profile

    init {
        viewModelScope.launch {
            combine(hermes.profile, hermes.connection) { profile, connection -> profile to connection?.id }
                .distinctUntilChanged()
                .collect { (profile, _) -> configure(profile) }
        }
    }

    private fun configure(profile: String) {
        generation += 1
        restartJob?.cancel()
        _state.value = CapabilitiesState(profile = profile)
        loadCapabilities()
    }

    private inline fun commit(token: Int, block: (CapabilitiesState) -> CapabilitiesState) {
        if (token == generation) _state.update(block)
    }

    // region Requests

    private val profileQuery: Map<String, String?> get() = mapOf("profile" to profile.takeIf { it != "default" })

    private suspend fun get(path: String, query: Map<String, String?> = emptyMap(), scoped: Boolean = true, timeoutMs: Long? = null): JsonObject =
        hermes.rest { it.getJson(path, if (scoped) query + profileQuery else query, timeoutMs) }

    private suspend fun send(
        method: String,
        path: String,
        body: JsonObject? = null,
        scoped: Boolean = true,
        timeoutMs: Long? = null,
    ): JsonObject = hermes.rest { it.sendJson(method, path, body, if (scoped) profileQuery else emptyMap(), timeoutMs) }

    /** A body with the profile added when it isn't the default, as `DashboardPath.bodyWithProfile`. */
    private fun scopedBody(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        build()
        if (profile != "default" && profile.isNotEmpty()) put("profile", profile)
    }

    /**
     * Polls a background action until it stops. A few failed polls in a row
     * (the dashboard busy installing) are retried; a profile switch ends it.
     */
    private suspend fun waitForAction(name: String, token: Int, limitMs: Long = 900_000): HermesActionStatus {
        val deadline = System.currentTimeMillis() + limitMs
        var failures = 0
        while (true) {
            delay(ACTION_POLL_MS)
            if (token != generation) throw CancellationException()
            try {
                val status = HermesActionStatus.parse(get("api/actions/$name/status", mapOf("lines" to "200"), scoped = false))
                failures = 0
                if (!status.running) return status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += 1
                if (failures >= 4) throw e
            }
            if (System.currentTimeMillis() > deadline) {
                throw CapabilityDeclined(L10n.tr("Hermes is still working on it. Check again in a moment."))
            }
        }
    }

    // endregion

    // region Skills and toolsets

    /** `GET /api/skills` and `GET /api/tools/toolsets` together. */
    fun loadCapabilities(): Job = viewModelScope.launch { refreshCapabilities() }

    suspend fun refreshCapabilities() {
        val token = generation
        val request = ++capabilityRequest
        if (!hermes.isSignedIn) {
            _state.update { it.copy(capabilitiesLoading = false, loadError = L10n.tr("Connect to a Hermes dashboard to load capabilities.")) }
            return
        }
        _state.update { it.copy(capabilitiesLoading = true, loadError = null) }
        try {
            val (skills, toolsets) = coroutineScope {
                val skills = async { CapabilityParsers.skills(get("api/skills")) }
                val toolsets = async { CapabilityParsers.toolsets(get("api/tools/toolsets")) }
                skills.await() to toolsets.await()
            }
            if (token != generation || request != capabilityRequest) return
            _state.update {
                it.copy(
                    skills = skills,
                    toolsets = toolsets,
                    snapshotLoaded = true,
                    capabilitiesLoading = false,
                    loadError = if (skills.isEmpty() && toolsets.isEmpty()) L10n.tr("No capabilities found.") else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token != generation || request != capabilityRequest) return
            _state.update {
                it.copy(capabilitiesLoading = false, loadError = L10n.tr("Could not load capabilities: %s", e.userMessage()))
            }
        }
    }

    fun toggleSkill(name: String, enabled: Boolean): Job = viewModelScope.launch { setSkill(name, enabled) }

    private suspend fun setSkill(name: String, enabled: Boolean) {
        val token = generation
        val requested = profile
        _state.update { s -> s.copy(toggleError = null, skills = s.skills.map { if (it.name == name) it.copy(enabled = enabled) else it }) }
        try {
            send("PUT", "api/skills/toggle", buildJsonObject {
                put("name", name)
                put("enabled", enabled)
                put("profile", requested)
            }, scoped = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            commit(token) { s ->
                s.copy(
                    skills = s.skills.map { if (it.name == name) it.copy(enabled = !enabled) else it },
                    toggleError = L10n.tr("Could not update skill: %s", e.userMessage()),
                )
            }
        }
    }

    fun toggleToolset(name: String, enabled: Boolean): Job = viewModelScope.launch { setToolset(name, enabled) }

    private suspend fun setToolset(name: String, enabled: Boolean) {
        val token = generation
        val requested = profile
        _state.update { s -> s.copy(toggleError = null, toolsets = s.toolsets.map { if (it.name == name) it.copy(enabled = enabled) else it }) }
        try {
            send("PUT", "api/tools/toolsets/$name", buildJsonObject {
                put("enabled", enabled)
                put("profile", requested)
            }, scoped = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            commit(token) { s ->
                s.copy(
                    toolsets = s.toolsets.map { if (it.name == name) it.copy(enabled = !enabled) else it },
                    toggleError = L10n.tr("Could not update toolset: %s", e.userMessage()),
                )
            }
        }
    }

    /** One switch at a time, as Hermes takes them; ones already in that state are left alone. */
    fun setAllSkills(names: List<String>, enabled: Boolean) {
        val targets = _state.value.skills.filter { it.name in names && it.enabled != enabled }.map { it.name }
        if (_state.value.bulkRunning || targets.isEmpty()) return
        _state.update { it.copy(bulkRunning = true) }
        viewModelScope.launch {
            try {
                targets.forEach { setSkill(it, enabled) }
            } finally {
                _state.update { it.copy(bulkRunning = false) }
            }
        }
    }

    fun setAllToolsets(enabled: Boolean) {
        val targets = _state.value.toolsets.filter { CapabilityToolsetOrdering.isVisible(it) && it.enabled != enabled }.map { it.name }
        if (_state.value.bulkRunning || targets.isEmpty()) return
        _state.update { it.copy(bulkRunning = true) }
        viewModelScope.launch {
            try {
                targets.forEach { setToolset(it, enabled) }
            } finally {
                _state.update { it.copy(bulkRunning = false) }
            }
        }
    }

    // endregion

    // region Skills catalog

    suspend fun loadOfficialSkills() {
        val token = generation
        val response = try {
            get("api/skills/hub/official", timeoutMs = 30_000)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        commit(token) { it.copy(officialSkills = OfficialSkill.list(response), officialSkillsLoaded = true) }
    }

    /** A skill's SKILL.md, frontmatter included. */
    suspend fun skillContent(name: String): String = get("api/skills/content", mapOf("name" to name))["content"].str.orEmpty()

    /** A catalog skill's SKILL.md before it's installed. */
    suspend fun skillPreview(identifier: String): String =
        get("api/skills/hub/preview", mapOf("identifier" to identifier), timeoutMs = 45_000)["skill_md"].str.orEmpty()

    fun installSkill(skill: OfficialSkill) = viewModelScope.launch {
        if (doInstallSkill(skill)) refreshCapabilities()
    }

    /** Hermes runs `hermes skills install` in the background; this waits for it. */
    private suspend fun doInstallSkill(skill: OfficialSkill): Boolean {
        val token = generation
        _state.update { it.copy(skillInstalls = it.skillInstalls + (skill.identifier to CapabilityInstallState.Running)) }
        return try {
            val started = send("POST", "api/skills/hub/install", scopedBody { put("identifier", skill.identifier) }, timeoutMs = 45_000)
            val action = started["name"].str ?: throw CapabilityDeclined(L10n.tr("Hermes didn't start the install."))
            val status = waitForAction(action, token)
            if (token != generation) return false
            if (!status.succeeded) {
                val reason = status.failureReason ?: L10n.tr("The install didn't finish.")
                commit(token) { it.copy(skillInstalls = it.skillInstalls + (skill.identifier to CapabilityInstallState.Failed(reason))) }
                return false
            }
            commit(token) { s ->
                s.copy(
                    skillInstalls = s.skillInstalls - skill.identifier,
                    officialSkills = s.officialSkills.map { if (it.identifier == skill.identifier) it.copy(installed = true) else it },
                )
            }
            true
        } catch (e: CancellationException) {
            false
        } catch (e: Exception) {
            commit(token) { it.copy(skillInstalls = it.skillInstalls + (skill.identifier to CapabilityInstallState.Failed(e.userMessage()))) }
            false
        }
    }

    // endregion

    // region Tools

    /** Per-tool calls over a year; a failed read keeps what was read before. */
    suspend fun loadToolCalls() {
        val token = generation
        val response = try {
            get("api/analytics/usage", mapOf("days" to "365"), timeoutMs = 30_000)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        commit(token) { it.copy(toolCalls = CapabilityToolsetOrdering.toolCallCounts(response)) }
    }

    suspend fun toolsetConfig(name: String): ToolsetConfig = ToolsetConfig.parse(get("api/tools/toolsets/$name/config", timeoutMs = 30_000))

    /** Makes [provider] the toolset's backend (for web, for one capability). True when it still needs a Nous sign-in. */
    suspend fun selectProvider(toolset: String, provider: String, capability: String?): Boolean {
        val response = send("PUT", "api/tools/toolsets/$toolset/provider", scopedBody {
            put("provider", provider)
            if (capability != null) put("capability", capability)
        })
        return (response["needs_nous_auth"] as? JsonPrimitive)?.content == "true"
    }

    suspend fun terminalBackends(): List<TerminalBackendOption> =
        TerminalBackendOption.list(get("api/tools/terminal/backends", timeoutMs = 30_000))

    suspend fun selectTerminalBackend(backend: String) {
        send("PUT", "api/tools/terminal/backend", scopedBody { put("backend", backend) })
    }

    /** Saves an API key to the profile's `.env`. Conduit never reads keys back. */
    suspend fun saveKey(key: String, value: String) {
        send("PUT", "api/env", scopedBody {
            put("key", key)
            put("value", value)
        })
    }

    // endregion

    // region Connectors

    suspend fun loadConnectors() {
        val token = generation
        coroutineScope {
            val catalogCall = async {
                try {
                    ConnectorCatalogEntry.list(get("api/mcp/catalog", timeoutMs = 30_000))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            try {
                val servers = ConnectorServer.list(get("api/mcp/servers", timeoutMs = 30_000))
                val catalog = catalogCall.await()
                commit(token) { s ->
                    val names = servers.map { it.name }.toSet()
                    s.copy(
                        servers = servers,
                        connectorCatalog = catalog ?: s.connectorCatalog,
                        connectorsError = null,
                        probes = s.probes.filterKeys { it in names },
                        connectorsLoaded = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogCall.await()
                commit(token) { it.copy(connectorsError = e.userMessage(), connectorsLoaded = true) }
            }
        }
    }

    /** Tests every server that's on and hasn't been tested lately, three at a time: each test starts the server. */
    suspend fun probeServers(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val s = _state.value
        val due = s.servers.filter { server ->
            if (!server.enabled || server.name in s.probing) return@filter false
            val probe = s.probes[server.name]
            force || probe == null || now - probe.checkedAt > PROBE_LIFETIME_MS
        }
        val gate = Semaphore(3)
        coroutineScope {
            due.forEach { server -> launch { gate.withPermit { probe(server.name) } } }
        }
    }

    fun probeLater(name: String) = viewModelScope.launch { probe(name) }

    suspend fun probe(name: String) {
        if (name in _state.value.probing) return
        val token = generation
        _state.update { it.copy(probing = it.probing + name) }
        val probe = try {
            ConnectorProbe.parse(send("POST", "api/mcp/servers/$name/test", timeoutMs = 60_000))
        } catch (e: CancellationException) {
            commit(token) { it.copy(probing = it.probing - name) }
            throw e
        } catch (e: Exception) {
            ConnectorProbe.failure(e.userMessage())
        }
        commit(token) { it.copy(probing = it.probing - name, probes = it.probes + (name to probe)) }
    }

    suspend fun setServerEnabled(name: String, enabled: Boolean) {
        val token = generation
        if (_state.value.servers.none { it.name == name }) return
        fun flip(value: Boolean) = _state.update { s -> s.copy(servers = s.servers.map { if (it.name == name) it.copy(enabled = value) else it }) }
        flip(enabled)
        try {
            send("PUT", "api/mcp/servers/$name/enabled", scopedBody { put("enabled", enabled) })
        } catch (e: Exception) {
            if (token == generation) flip(!enabled)
            throw e
        }
        if (token != generation) return
        if (enabled) probe(name) else _state.update { it.copy(probes = it.probes - name) }
    }

    suspend fun removeServer(name: String) {
        val token = generation
        send("DELETE", "api/mcp/servers/$name")
        commit(token) { s ->
            s.copy(
                servers = s.servers.filterNot { it.name == name },
                probes = s.probes - name,
                connectorCatalog = s.connectorCatalog.map { if (it.name == name) it.copy(installed = false) else it },
            )
        }
    }

    /** Installs a catalog server with the keys it asks for; entries Hermes builds run in the background and are waited on. */
    fun installConnector(entry: ConnectorCatalogEntry, env: Map<String, String>) = viewModelScope.launch {
        val token = generation
        _state.update { it.copy(connectorInstalls = it.connectorInstalls + (entry.name to CapabilityInstallState.Running)) }
        try {
            val response = send("POST", "api/mcp/catalog/install", scopedBody {
                put("name", entry.name)
                putJsonObject("env") { env.forEach { (k, v) -> put(k, v) } }
                put("enable", true)
            }, timeoutMs = 60_000)
            val action = response["action"].str
            if ((response["background"] as? JsonPrimitive)?.content == "true" && action != null) {
                val status = waitForAction(action, token)
                if (!status.succeeded) {
                    val reason = status.failureReason ?: L10n.tr("The install didn't finish.")
                    commit(token) { it.copy(connectorInstalls = it.connectorInstalls + (entry.name to CapabilityInstallState.Failed(reason))) }
                    return@launch
                }
            }
            if (token != generation) return@launch
            _state.update { it.copy(connectorInstalls = it.connectorInstalls - entry.name) }
            loadConnectors()
            probe(entry.name)
        } catch (e: CancellationException) {
            return@launch
        } catch (e: Exception) {
            commit(token) { it.copy(connectorInstalls = it.connectorInstalls + (entry.name to CapabilityInstallState.Failed(e.userMessage()))) }
        }
    }

    fun clearConnectorInstallFailure(name: String) = _state.update {
        if (it.connectorInstalls[name] is CapabilityInstallState.Failed) it.copy(connectorInstalls = it.connectorInstalls - name) else it
    }

    fun clearSkillInstallFailure(identifier: String) = _state.update {
        if (it.skillInstalls[identifier] is CapabilityInstallState.Failed) it.copy(skillInstalls = it.skillInstalls - identifier) else it
    }

    /** Starts a server's OAuth sign-in; the person finishes it in the browser at the returned address. */
    suspend fun startSignIn(name: String): Pair<String, String> {
        val response = send("POST", "api/mcp/servers/$name/auth", timeoutMs = 45_000)
        response["error"].str?.takeIf { it.isNotEmpty() }?.let { throw CapabilityDeclined(it) }
        val flow = response["flow_id"].str
        val url = response["authorization_url"].str
        if (flow.isNullOrEmpty() || url.isNullOrEmpty()) throw CapabilityDeclined(L10n.tr("Hermes didn't return a sign-in page."))
        return flow to url
    }

    /** starting, authorization_required, approved or error. */
    suspend fun signInStatus(flowId: String): Pair<String, String?> {
        val response = get("api/mcp/oauth/flows/$flowId", scoped = false)
        return response["status"].str.orEmpty() to response["error"].str?.takeIf { it.isNotEmpty() }
    }

    fun cancelSignIn(flowId: String) = viewModelScope.launch {
        runCatching { send("DELETE", "api/mcp/oauth/flows/$flowId", scoped = false) }
    }

    // endregion

    // region Plugins

    suspend fun loadPlugins() {
        val token = generation
        coroutineScope {
            val catalogCall = async {
                try {
                    PluginCatalogEntry.list(get("api/dashboard/plugins/catalog", scoped = false, timeoutMs = 30_000))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            try {
                val plugins = AgentPluginRow.list(get("api/dashboard/plugins/hub", timeoutMs = 30_000))
                val catalog = catalogCall.await()
                commit(token) { s ->
                    s.copy(plugins = plugins, pluginCatalog = catalog ?: s.pluginCatalog, pluginsError = null, pluginsLoaded = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogCall.await()
                commit(token) { it.copy(pluginsError = e.userMessage(), pluginsLoaded = true) }
            }
        }
    }

    private fun requirePluginProfile() {
        if (!_state.value.canManagePlugins) {
            throw CapabilityDeclined(L10n.tr("Hermes installs plugins for the host's default profile. Switch to it to change them."))
        }
    }

    private suspend fun <T> busy(name: String, token: Int, block: suspend () -> T): T {
        _state.update { it.copy(pluginBusy = it.pluginBusy + name) }
        try {
            return block()
        } finally {
            commit(token) { it.copy(pluginBusy = it.pluginBusy - name) }
        }
    }

    /** Turning one on loads it live when the gateway answers; turning one off always waits for a restart. */
    suspend fun setPluginEnabled(plugin: AgentPluginRow, enabled: Boolean) {
        requirePluginProfile()
        val token = generation
        busy(plugin.name, token) {
            val response = send(
                "POST",
                "api/dashboard/agent-plugins/${plugin.name}/${if (enabled) "enable" else "disable"}",
                scoped = false,
                timeoutMs = 60_000,
            )
            commit(token) { s ->
                s.copy(
                    plugins = s.plugins.map { if (it.name == plugin.name) it.copy(runtimeStatus = if (enabled) "enabled" else "disabled") else it },
                    pluginsNeedRestart = s.pluginsNeedRestart || (response["restart_required"] as? JsonPrimitive)?.content == "true",
                )
            }
        }
    }

    suspend fun updatePlugin(plugin: AgentPluginRow, acceptCapabilities: Boolean): PluginUpdateOutcome {
        requirePluginProfile()
        val token = generation
        val outcome = busy(plugin.name, token) {
            val response = send(
                "POST",
                "api/dashboard/agent-plugins/${plugin.folderName}/update",
                buildJsonObject { if (acceptCapabilities) put("accept_capabilities", true) },
                scoped = false,
                timeoutMs = 180_000,
            )
            PluginResponses.update(response)
        }
        if (outcome is PluginUpdateOutcome.NeedsConsent) return outcome
        // A profile or dashboard switch discards the answer.
        if (token != generation) throw CancellationException()
        if (outcome is PluginUpdateOutcome.Updated) {
            if (outcome.restartRequired) _state.update { it.copy(pluginsNeedRestart = true) }
            loadPlugins()
        }
        return outcome
    }

    suspend fun removePlugin(plugin: AgentPluginRow) {
        requirePluginProfile()
        val token = generation
        busy(plugin.name, token) {
            send("DELETE", "api/dashboard/agent-plugins/${plugin.folderName}", scoped = false, timeoutMs = 60_000)
        }
        if (token != generation) return
        // Hermes can't unload a plugin while the gateway runs.
        _state.update { s -> s.copy(plugins = s.plugins.filterNot { it.name == plugin.name }, pluginsNeedRestart = true) }
        loadPlugins()
    }

    suspend fun installPlugin(entry: PluginCatalogEntry): PluginInstallOutcome {
        requirePluginProfile()
        val token = generation
        val outcome = busy(entry.name, token) {
            val response = send("POST", "api/dashboard/agent-plugins/install", buildJsonObject {
                put("identifier", "")
                put("catalog_name", entry.name)
                put("enable", true)
            }, scoped = false, timeoutMs = 180_000)
            PluginResponses.install(response, entry.displayName)
        }
        if (token != generation) return outcome
        if (outcome.restartRequired) _state.update { it.copy(pluginsNeedRestart = true) }
        loadPlugins()
        return outcome
    }

    // endregion

    // region Gateway restart

    /**
     * Restart Gateway, offered after a plugin change Hermes applies on the
     * gateway's next start: `POST /api/gateway/restart`, then follow the
     * restart action and `/api/status` until the gateway is back.
     */
    fun restartGateway() {
        if (_state.value.restart.isInProgress) return
        val token = generation
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            fun report(state: CapabilityRestartState) = commit(token) {
                it.copy(restart = state, pluginsNeedRestart = it.pluginsNeedRestart && state !is CapabilityRestartState.Restarted)
            }
            report(CapabilityRestartState.Requesting)
            val before = gatewayStatus()
            try {
                send("POST", "api/gateway/restart", scoped = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? DashboardHttpException)?.code
                report(if (code in setOf(404, 405, 501)) CapabilityRestartState.Unsupported else CapabilityRestartState.Failed(e.userMessage()))
                return@launch
            }
            val started = System.currentTimeMillis()
            val monitor = CapabilityRestartMonitor(before)
            var actionLogAvailable = true
            var latestLine: String? = null
            report(CapabilityRestartState.Restarting(CapabilityRestartState.Stage.STOPPING, null))
            while (true) {
                delay(1_500)
                var action: HermesActionStatus? = null
                if (actionLogAvailable) {
                    try {
                        val json = get("api/actions/gateway-restart/status", mapOf("lines" to "20"), scoped = false)
                        if (json["running"] != null) action = HermesActionStatus.parse(json)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if ((e as? DashboardHttpException)?.code == 404) actionLogAvailable = false
                    }
                }
                val gateway = gatewayStatus()
                latestLine = action?.let(CapabilityRestartMonitor::latestLine) ?: latestLine
                val verdict = monitor.observe(action, gateway, System.currentTimeMillis() - started)
                if (verdict is CapabilityRestartState.Restarting) {
                    report(verdict.copy(detail = latestLine))
                } else {
                    report(verdict)
                    break
                }
            }
        }
    }

    private suspend fun gatewayStatus(): CapabilityRestartMonitor.GatewayStatus? = try {
        hermes.rest(DashboardApi::status).let(CapabilityRestartMonitor.GatewayStatus::parse)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun dismissRestartOutcome() = _state.update { if (it.restart.isInProgress) it else it.copy(restart = CapabilityRestartState.Idle) }

    // endregion

    companion object {
        /** A connector's probe stays fresh this long, as on Desktop. */
        const val PROBE_LIFETIME_MS = 300_000L
        const val ACTION_POLL_MS = 1_200L
    }
}

/** The error's own sentence, as `UserFacingError.message`. */
fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: L10n.tr("Something went wrong.")
