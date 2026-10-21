package app.hermesconduit.android.ui.plugin

import android.content.Context
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.GatewayRestartState
import app.hermesconduit.android.model.PluginStatus
import app.hermesconduit.android.model.PluginUpdateRules
import app.hermesconduit.android.model.PluginUpdateState
import app.hermesconduit.android.model.PluginUpdateStep
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.gateway.DashboardRequest
import app.hermesconduit.android.ui.gateway.GatewayRestartController
import app.hermesconduit.android.ui.gateway.GatewayRestarter
import app.hermesconduit.android.ui.gateway.hermesDetail
import app.hermesconduit.android.ui.gateway.httpCode
import app.hermesconduit.android.ui.gateway.requestJson
import app.hermesconduit.android.ui.gateway.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Conduit notifier plugin on the active dashboard's host: what it
 * serves (cached per dashboard) and the one-tap update. Other features ask
 * [capabilities] before they call a plugin route.
 */
class PluginStatusRepository private constructor(private val hermes: HermesConnection) {
    private val cache = mutableMapOf<String, PluginStatus>()
    private val mutex = Mutex()

    private val _status = MutableStateFlow(PluginStatus())
    /** The active dashboard's plugin, as last reported. */
    val status: StateFlow<PluginStatus> = _status

    private val _update = MutableStateFlow<Pair<String?, PluginUpdateState>>(null to PluginUpdateState.Idle)
    private var updateJob: Job? = null
    /** When the running update began (epoch ms). */
    var updateStartedAt: Long? = null
        private set

    private val _updateState = MutableStateFlow<PluginUpdateState>(PluginUpdateState.Idle)
    /** The update's state for the dashboard in use. */
    val update: StateFlow<PluginUpdateState> = _updateState

    init {
        hermes.scope.launch {
            hermes.connection.collect { connection ->
                _status.value = connection?.id?.let { cache[it] } ?: PluginStatus()
                publishUpdate()
            }
        }
    }

    private fun dashboardId(): String? = hermes.connection.value?.id

    /** Capabilities the plugin reported; empty when it predates reporting or couldn't be asked. */
    suspend fun capabilities(): Set<String> = status(force = false).capabilities

    /** Whether the plugin reported `capability`. */
    suspend fun supports(capability: String): Boolean = capability in capabilities()

    /** The plugin status, asked again when not known yet for this dashboard (or when `force`). */
    suspend fun status(force: Boolean): PluginStatus {
        val id = dashboardId() ?: return PluginStatus()
        if (!force) cache[id]?.takeIf { it.state != PluginStatus.State.Unknown }?.let { return it }
        return mutex.withLock {
            if (!force) cache[id]?.takeIf { it.state != PluginStatus.State.Unknown }?.let { return@withLock it }
            val fetched = try {
                PluginStatus.parse(hermes.requestJson("GET", PluginStatus.PATH, timeoutSeconds = 12))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginStatus(PluginStatus.stateForHttpError(e.httpCode()))
            }
            // A failed request says nothing new: keep the last real answer.
            if (fetched.state == PluginStatus.State.Unknown) return@withLock cache[id] ?: fetched
            cache[id] = fetched
            if (dashboardId() == id) {
                _status.value = fetched
                clearSettledOutcome(fetched)
            }
            fetched
        }
    }

    // region Update

    /** Why the update can't run right now (English key), or null. */
    fun updateBlocker(): String? = when {
        !hermes.isSignedIn -> "Connect to this dashboard first."
        GatewayRestartController.of(hermes).state().isInProgress -> "Wait for the gateway restart to finish."
        else -> null
    }

    val canUpdate: Boolean get() = updateBlocker() == null && !update.value.isInProgress

    private fun publishUpdate() {
        val (id, state) = _update.value
        _updateState.value = if (id == dashboardId()) state else PluginUpdateState.Idle
    }

    private fun setUpdate(id: String?, state: PluginUpdateState) {
        _update.value = id to state
        publishUpdate()
    }

    /** Updates (or installs) the plugin, then restarts the gateway and the dashboard. */
    fun startUpdate(install: Boolean) {
        if (!canUpdate) return
        updateJob?.cancel()
        val id = dashboardId()
        updateStartedAt = System.currentTimeMillis()
        setUpdate(id, PluginUpdateState.Updating(PluginUpdateStep.Downloading))
        val updater = PluginUpdater(
            request = { method, path, query, body ->
                if (dashboardId() != id) throw IllegalStateException("Another dashboard is in use.")
                // Hermes pulls or clones the plugin before it answers.
                val timeout = if (path.startsWith("api/dashboard/agent-plugins")) 180L else 12L
                hermes.requestJson(method, path, query, body, timeout)
            },
            install = install,
        )
        updateJob = hermes.scope.launch {
            val outcome = updater.run { state ->
                if (dashboardId() == id) setUpdate(id, state) else updateJob?.cancel()
            }
            setUpdate(if (dashboardId() == id && outcome != PluginUpdateState.Idle) id else null, outcome)
            if (dashboardId() != id) return@launch
            // The dashboard restart drops the chat connection.
            if (hermes.link.value != LinkState.CONNECTED) hermes.reconnectNow()
            status(force = true)
        }
    }

    /** Stops following a running update. Hermes finishes whatever step it already took. */
    fun cancelUpdate() {
        if (!update.value.isInProgress) return
        updateJob?.cancel()
        updateJob = null
        setUpdate(null, PluginUpdateState.Idle)
        hermes.scope.launch { status(force = true) }
    }

    fun dismissOutcome() {
        if (update.value.isInProgress) return
        setUpdate(null, PluginUpdateState.Idle)
    }

    /** "Restart to finish" is stale once the plugin reports nothing left to do. */
    private fun clearSettledOutcome(status: PluginStatus) {
        when (val state = update.value) {
            is PluginUpdateState.NeedsDashboardRestart -> if (status.updateAvailable || status.restartPending) return
            is PluginUpdateState.Failed -> {
                val running = status.runtime?.loadedVersion
                if (status.restartPending || running == null || PluginStatus.isOlder(running, PluginStatus.RECOMMENDED_VERSION)) return
            }
            else -> return
        }
        dismissOutcome()
    }

    // endregion

    companion object {
        @Volatile private var instance: PluginStatusRepository? = null

        fun of(hermes: HermesConnection): PluginStatusRepository =
            instance?.takeIf { it.hermes === hermes } ?: synchronized(this) {
                instance?.takeIf { it.hermes === hermes } ?: PluginStatusRepository(hermes).also { instance = it }
            }

        fun of(context: Context): PluginStatusRepository = of(context.hermes)
    }
}

/** Runs one plugin update from the request to the restarted dashboard (iOS `NotifierPluginUpdater`). */
class PluginUpdater(
    private val request: DashboardRequest,
    private val install: Boolean = false,
    private val pollIntervalMs: Long = 2_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun run(progress: (PluginUpdateState) -> Unit): PluginUpdateState {
        // 1. The new plugin onto the host.
        progress(PluginUpdateState.Updating(if (install) PluginUpdateStep.Installing else PluginUpdateStep.Downloading))
        try {
            var installs = install
            var replaces = false
            if (!installs) {
                try {
                    check(request("POST", PluginUpdateRules.UPDATE_PATH, emptyMap(), null))
                } catch (e: DashboardHttpException) {
                    when {
                        PluginUpdateRules.isNotInstalled(e.code, e.detail) -> installs = true
                        PluginUpdateRules.needsReinstall(e.code, e.detail) -> { installs = true; replaces = true }
                        else -> throw e
                    }
                }
            }
            if (installs) {
                progress(PluginUpdateState.Updating(PluginUpdateStep.Installing))
                val body = buildJsonObject {
                    put("identifier", PluginStatus.REPOSITORY)
                    put("enable", true)
                    if (replaces) put("force", true)
                }
                check(request("POST", PluginUpdateRules.INSTALL_PATH, emptyMap(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return outcomeForRequestError(e)
        }

        // 2. The gateway, for the plugin's hooks, tools and skills.
        progress(PluginUpdateState.Updating(PluginUpdateStep.RestartingGateway(null)))
        val gateway = GatewayRestarter(request, pollIntervalMs, sleep, now).run { state ->
            if (state is GatewayRestartState.Restarting) progress(PluginUpdateState.Updating(PluginUpdateStep.RestartingGateway(state.stage)))
        }
        when (gateway) {
            is GatewayRestartState.Restarted, GatewayRestartState.Unsupported -> Unit
            is GatewayRestartState.Failed -> return PluginUpdateState.Failed(gateway.detail)
            else -> return PluginUpdateState.Failed(
                L10n.tr("The plugin is updated, but the gateway didn't come back. Run hermes gateway status on the host."),
            )
        }

        // 3. The dashboard, for the plugin's routes. Right after the gateway restart a read can fail once.
        var before: JsonObject? = null
        for (attempt in 0 until 3) {
            try {
                before = request("GET", PluginStatus.PATH, emptyMap(), null)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                return PluginUpdateState.Failed(e.userMessage())
            } catch (_: Exception) {
            }
            if (attempt < 2) sleep(pollIntervalMs)
        }
        val fetched = before ?: return PluginUpdateState.NeedsDashboardRestart(null)
        val runtime = PluginStatus.runtime(fetched) ?: return PluginUpdateState.NeedsDashboardRestart(null)
        // A first install is mounted as it lands; nothing old is running.
        if (!runtime.restartNeeded) return PluginUpdateState.Updated(runtime.loadedVersion)
        val capabilities = fetched["capabilities"].arr?.mapNotNull { it.str }.orEmpty()
        if (PluginUpdateRules.SELF_RESTART_CAPABILITY !in capabilities) return PluginUpdateState.NeedsDashboardRestart(null)

        val waitStarted = now()
        while (true) {
            try {
                request("POST", PluginUpdateRules.SELF_RESTART_PATH, emptyMap(), null)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e.httpCode()) {
                    409 -> {
                        if (now() - waitStarted >= CHATS_WAIT_LIMIT_MS) return PluginUpdateState.NeedsDashboardRestart(e.hermesDetail())
                        progress(PluginUpdateState.Updating(PluginUpdateStep.WaitingForChats(PluginUpdateRules.runningChats(e.hermesDetail()))))
                        sleep(pollIntervalMs)
                    }
                    404, 405, 501 -> return PluginUpdateState.NeedsDashboardRestart(e.hermesDetail())
                    else -> return PluginUpdateState.Failed(e.hermesDetail() ?: e.userMessage())
                }
            }
        }

        progress(PluginUpdateState.Updating(PluginUpdateStep.RestartingDashboard))
        val restartStarted = now()
        while (now() - restartStarted < COME_BACK_LIMIT_MS) {
            sleep(pollIntervalMs)
            val json = try {
                request("GET", PluginStatus.PATH, emptyMap(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                return PluginUpdateState.Failed(e.userMessage())
            } catch (_: Exception) {
                continue
            }
            val after = PluginStatus.runtime(json) ?: continue
            if (after.bootId == runtime.bootId) continue
            return if (after.restartNeeded) PluginUpdateState.NeedsDashboardRestart(null) else PluginUpdateState.Updated(after.loadedVersion)
        }
        return PluginUpdateState.Failed(L10n.tr("The dashboard hasn't come back yet. Check that Hermes is running on the host."))
    }

    /** Hermes answers some refusals with 200 and `ok: false`. */
    private fun check(response: JsonObject) {
        if (response["ok"].bool != false) return
        throw DashboardHttpException(
            400,
            response["error"].str ?: L10n.tr("Hermes didn't apply the update. Update the plugin from the Hermes dashboard."),
        )
    }

    companion object {
        const val CHATS_WAIT_LIMIT_MS = 600_000L
        const val COME_BACK_LIMIT_MS = 120_000L

        fun outcomeForRequestError(e: Throwable): PluginUpdateState = when (e.httpCode()) {
            404, 405, 501 -> PluginUpdateState.Unsupported
            400 -> PluginUpdateState.Failed(e.hermesDetail())
            else -> PluginUpdateState.Failed(e.hermesDetail() ?: e.userMessage())
        }
    }
}
