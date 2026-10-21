package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonObject

/**
 * What the host's Conduit notifier plugin serves, from its capabilities
 * route (plugin 0.4+). Port of iOS `NotifierPluginStatus`.
 */
data class PluginStatus(
    val state: State = State.Unknown,
    /** Nil until a plugin 0.24+ reports it. */
    val runtime: Runtime? = null,
) {
    sealed interface State {
        /** Not asked yet, or the host couldn't answer. */
        data object Unknown : State
        /** The route is missing: the plugin is older than 0.4 or isn't installed. */
        data object PredatesCapabilities : State
        data class Reported(val version: String?, val capabilities: Set<String>) : State
    }

    data class Runtime(
        /** The version the dashboard process imported. */
        val loadedVersion: String?,
        /** The files on disk are another version than the running one. */
        val restartNeeded: Boolean,
        /** New with every dashboard process. */
        val bootId: String?,
    )

    /** The version installed on disk. */
    val version: String? get() = (state as? State.Reported)?.version

    /** The version the dashboard is serving. */
    val runningVersion: String? get() = runtime?.loadedVersion ?: (if (restartPending) null else version)

    /** The plugin was updated on disk but the dashboard still runs the old one. */
    val restartPending: Boolean
        get() {
            runtime?.let { return it.restartNeeded }
            val reported = state as? State.Reported ?: return false
            return reported.version != null && !isOlder(reported.version, RUNTIME_REPORTING_VERSION)
        }

    /** Missing or older than one-tap updates (0.24): the first update is a manual one. */
    val predatesOneTapUpdate: Boolean
        get() {
            if (runtime != null) return false
            return when (val s = state) {
                State.Unknown -> false
                State.PredatesCapabilities -> true
                is State.Reported -> isOlder(s.version, RUNTIME_REPORTING_VERSION)
            }
        }

    /** Something Conduit uses is missing, or the plugin is older than the recommended version. */
    val updateAvailable: Boolean
        get() = when (val s = state) {
            State.Unknown -> false
            State.PredatesCapabilities -> true
            is State.Reported -> {
                val older = isOlder(s.version, RECOMMENDED_VERSION)
                if (restartPending) older else (needsUpdate || older)
            }
        }

    val needsUpdate: Boolean
        get() = when (val s = state) {
            State.Unknown -> false
            State.PredatesCapabilities -> true
            is State.Reported -> USED_CAPABILITIES.any { it !in s.capabilities }
        }

    val capabilities: Set<String> get() = (state as? State.Reported)?.capabilities.orEmpty()

    /** True only when the plugin reported this capability. */
    fun reports(capability: String): Boolean = capability in capabilities

    /** False only when the plugin reported its capabilities without this one. */
    fun mightSupport(capability: String): Boolean = (state as? State.Reported)?.let { capability in it.capabilities } ?: true

    val isInstall: Boolean get() = state == State.PredatesCapabilities

    companion object {
        const val PATH = "api/plugins/conduit_push/capabilities"
        const val RECOMMENDED_VERSION = "0.41.0"
        const val RUNTIME_REPORTING_VERSION = "0.24.0"
        const val REPOSITORY = "kaishi00/hermes-conduit-notifier"

        /** Plugin features Conduit uses (iOS `usedCapabilities`). */
        val USED_CAPABILITIES = listOf(
            "session-takeover", "voice-sessions", "voice-tags", "voice-summary", "personality", "memory",
            "web-search", "gemini-live", "gpt-live", "grok-live", "e2e-notifications", "desktop-views", "hermes-calls",
        )

        fun state(response: JsonObject): State {
            if (response["ok"].bool != true) return State.Unknown
            val caps = response["capabilities"].arr ?: return State.Unknown
            return State.Reported(response["version"].str, caps.mapNotNull { it.str }.toSet())
        }

        fun runtime(response: JsonObject): Runtime? {
            if (response["ok"].bool != true || !response.containsKey("loaded_version")) return null
            return Runtime(
                loadedVersion = response["loaded_version"].str,
                restartNeeded = response["restart_needed"].bool ?: false,
                bootId = response["boot_id"].str,
            )
        }

        fun parse(response: JsonObject) = PluginStatus(state(response), runtime(response))

        /** A 404/405 means the route predates capability reporting. */
        fun stateForHttpError(code: Int?): State =
            if (code == 404 || code == 405) State.PredatesCapabilities else State.Unknown

        /** Dotted numeric versions compared part by part ("0.9.1" < "0.10.0"). Unreadable counts as older. */
        fun isOlder(version: String?, other: String): Boolean {
            val lhs = version?.let(::numericParts) ?: return true
            val rhs = numericParts(other) ?: return true
            for (i in 0 until maxOf(lhs.size, rhs.size)) {
                val l = lhs.getOrElse(i) { 0 }
                val r = rhs.getOrElse(i) { 0 }
                if (l != r) return l < r
            }
            return false
        }

        private fun numericParts(version: String): List<Int>? {
            val core = version.split("-", limit = 2).first()
            val parts = core.split(".").map { it.toIntOrNull() }
            if (parts.isEmpty() || parts.any { it == null }) return null
            return parts.filterNotNull()
        }
    }
}

/** The features Conduit uses from the plugin, with the English titles iOS shows. */
enum class PluginFeature(val title: String, val capabilities: List<String>) {
    CHAT_TAKEOVER("Taking chats over from Hermes Desktop", listOf("session-takeover", "desktop-views")),
    SAVED_CALLS("Saved voice calls", listOf("voice-sessions", "voice-tags", "voice-summary")),
    LIVE_VOICE_CONTEXT("Memory, personality and web search in live voice", listOf("memory", "personality", "web-search")),
    GEMINI_LIVE("Gemini Live", listOf("gemini-live")),
    GPT_LIVE("GPT-Live", listOf("gpt-live")),
    GROK_LIVE("Grok Live", listOf("grok-live")),
    ENCRYPTED_NOTIFICATIONS("Encrypted notifications", listOf("e2e-notifications")),
    PAIRING("Pairing from Conduit", listOf("pairing")),
    HERMES_CALLS("Calls from Hermes", listOf("hermes-calls")),
    AGENT_SETTINGS("Let Hermes change settings", listOf("conduit-settings")),
    HEALTH_SYNC("Health data", listOf("health-sync")),
    UPDATES("Updates from Conduit", listOf("self-restart"));

    fun isServed(status: PluginStatus) = capabilities.all { status.reports(it) }
}

/** Where a plugin update stands (iOS `NotifierPluginUpdateState`). */
sealed interface PluginUpdateState {
    data object Idle : PluginUpdateState
    data class Updating(val step: PluginUpdateStep) : PluginUpdateState
    data class Updated(val version: String?) : PluginUpdateState
    data class NeedsDashboardRestart(val detail: String?) : PluginUpdateState
    data class Failed(val detail: String?) : PluginUpdateState
    data object Unsupported : PluginUpdateState

    val isInProgress: Boolean get() = this is Updating
}

sealed interface PluginUpdateStep {
    data object Downloading : PluginUpdateStep
    data object Installing : PluginUpdateStep
    data class RestartingGateway(val stage: GatewayRestartStage?) : PluginUpdateStep
    data class WaitingForChats(val count: Int) : PluginUpdateStep
    data object RestartingDashboard : PluginUpdateStep

    /** Where the step falls in the update, for "Step 2 of 3". */
    val number: Int
        get() = when (this) {
            Downloading, Installing -> 1
            is RestartingGateway -> 2
            is WaitingForChats, RestartingDashboard -> 3
        }

    companion object {
        const val COUNT = 3
    }
}

object PluginUpdateRules {
    const val UPDATE_PATH = "api/dashboard/agent-plugins/conduit_push/update"
    const val INSTALL_PATH = "api/dashboard/agent-plugins/install"
    const val SELF_RESTART_PATH = "api/plugins/conduit_push/self/restart"
    const val SELF_RESTART_CAPABILITY = "self-restart"

    /** Hermes' update answers 400 "Plugin 'conduit_push' was not found…" when it isn't installed. */
    fun isNotInstalled(code: Int?, detail: String?) = code == 400 && detail?.contains("not found", ignoreCase = true) == true

    /** A copy Hermes didn't clone answers 400 "…has no owned Git checkout; reinstall from its source." */
    fun needsReinstall(code: Int?, detail: String?) = code == 400 && detail?.contains("no owned Git checkout", ignoreCase = true) == true

    /** "2 chat turns still running…" → 2; 1 when the count can't be read. */
    fun runningChats(detail: String?): Int = detail?.split(" ")?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: 1

    /** "Step 1 of 3 · 0:42". */
    fun elapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
