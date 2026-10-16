package app.hermesconduit.android.model

import app.hermesconduit.android.net.ConnectionUrlPolicy
import app.hermesconduit.android.net.CustomHeaders
import kotlinx.serialization.json.JsonObject
import java.net.URI

// region Restart Gateway (port of iOS GatewayRestart.swift)

enum class GatewayRestartStage {
    /** The old gateway is finishing running work before it stops. */
    DRAINING,
    /** The old gateway is still up. */
    STOPPING,
    /** The old gateway is gone and Hermes is starting a new one. */
    STARTING,
}

sealed interface GatewayRestartState {
    data object Idle : GatewayRestartState
    data object Requesting : GatewayRestartState
    data class Restarting(val stage: GatewayRestartStage, val detail: String?) : GatewayRestartState
    data class Restarted(val sharedProfiles: List<String>) : GatewayRestartState
    data class Failed(val detail: String?) : GatewayRestartState
    data object FailedToStart : GatewayRestartState
    data object NotBackYet : GatewayRestartState
    data object Unsupported : GatewayRestartState

    val isInProgress: Boolean get() = this is Requesting || this is Restarting
}

/** The gateway fields of `/api/status` a restart watches. */
data class GatewayRunStatus(
    val running: Boolean,
    val state: String? = null,
    /** Only reported on a loopback or `--insecure` dashboard. */
    val pid: Long? = null,
    val sharedProfiles: List<String> = emptyList(),
) {
    /** Up and serving: running, and neither starting, failed, nor draining. */
    val isServing: Boolean
        get() = running && state?.lowercase() !in setOf("starting", "stopped", "startup_failed", "draining")

    companion object {
        fun parse(json: JsonObject): GatewayRunStatus? {
            val running = (json["gateway_running"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { !it.isString }?.bool ?: return null
            return GatewayRunStatus(
                running = running,
                state = json["gateway_state"].str,
                pid = json["gateway_pid"].long,
                sharedProfiles = sharedProfiles(json["gateway_shared_with"].arr?.mapNotNull { it.descriptive }),
            )
        }

        /** Unique names, default first, the rest sorted; empty unless two or more share it. */
        fun sharedProfiles(raw: List<String>?): List<String> {
            val names = raw.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.size < 2) return emptyList()
            return names.sortedWith(compareBy<String> { it != "default" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })
        }
    }
}

/** `/api/actions/gateway-restart/status`: the restart child and its log. */
data class GatewayRestartAction(val running: Boolean, val exitCode: Long? = null, val lines: List<String> = emptyList()) {
    /** The newest log line worth showing, skipping blanks and `=== … ===` banners. */
    val latestLine: String?
        get() {
            for (line in lines.asReversed()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("===")) continue
                return if (trimmed.length > 240) trimmed.take(240) + "…" else trimmed
            }
            return null
        }

    companion object {
        fun parse(json: JsonObject): GatewayRestartAction? {
            val running = json["running"].bool ?: return null
            return GatewayRestartAction(running, json["exit_code"].long, json["lines"].arr?.mapNotNull { it.descriptive }.orEmpty())
        }
    }
}

/** Decides, poll by poll, whether a restarted gateway is back (iOS `GatewayRestartMonitor`). */
class GatewayRestartMonitor(private val before: GatewayRunStatus?) {
    sealed interface Verdict {
        data class Waiting(val stage: GatewayRestartStage) : Verdict
        data class Back(val status: GatewayRunStatus) : Verdict
        data class Failed(val detail: String?) : Verdict
        data object FailedToStart : Verdict
        data object NotBackYet : Verdict
    }

    var sawDown = false
        private set
    var childFinishedAt: Double? = null
        private set

    /** `elapsed` in seconds since Hermes took the request. */
    fun observe(action: GatewayRestartAction?, gateway: GatewayRunStatus?, elapsed: Double): Verdict {
        if (action != null && !action.running && action.exitCode != null) {
            if (action.exitCode != 0L) return Verdict.Failed(action.latestLine)
            if (childFinishedAt == null) childFinishedAt = elapsed
        }
        if (before?.running == false || gateway?.running == false) sawDown = true

        if (gateway != null && gateway.isServing) {
            if (childFinishedAt != null || sawDown || hasNewProcess(gateway) || elapsed >= RUNNING_CHILD_GRACE) {
                return Verdict.Back(gateway)
            }
        }
        val outOfTime = elapsed >= WATCH_LIMIT || childFinishedAt?.let { elapsed - it >= COME_BACK_WINDOW } == true
        if (outOfTime) {
            return if (gateway?.state?.lowercase() == "startup_failed") Verdict.FailedToStart else Verdict.NotBackYet
        }
        if (sawDown || childFinishedAt != null) return Verdict.Waiting(GatewayRestartStage.STARTING)
        return Verdict.Waiting(if (gateway?.state?.lowercase() == "draining") GatewayRestartStage.DRAINING else GatewayRestartStage.STOPPING)
    }

    private fun hasNewProcess(gateway: GatewayRunStatus): Boolean {
        val old = before?.pid ?: return false
        val new = gateway.pid ?: return false
        return old != new
    }

    companion object {
        const val RUNNING_CHILD_GRACE = 30.0
        const val COME_BACK_WINDOW = 90.0
        const val WATCH_LIMIT = 600.0
    }
}

// endregion

// region Diagnostics

data class GatewayConnector(
    val id: String,
    val name: String,
    val state: String,
    val error: String?,
    val configured: Boolean?,
    val enabled: Boolean?,
)

data class GatewayDiagnostics(
    val gatewayRunning: Boolean,
    val gatewayState: String?,
    val version: String?,
    val pid: Long?,
    val connectors: List<GatewayConnector>,
    val logs: List<String>,
    /** English message key, or null. */
    val error: String?,
)

object GatewayParser {
    fun connectors(platforms: JsonObject?): List<GatewayConnector> =
        platforms?.get("platforms").arr.orEmpty().mapIndexedNotNull { index, element ->
            val item = element.obj ?: return@mapIndexedNotNull null
            GatewayConnector(
                id = item["id"].str ?: item["name"].str ?: "connector-$index",
                name = item["name"].str ?: item["platform"].str ?: "Connector",
                state = item["state"].str ?: item["status"].str ?: "unknown",
                error = item["error"].str,
                configured = item["configured"].bool,
                enabled = item["enabled"].bool,
            )
        }

    fun logLines(logs: JsonObject?): List<String> = logs?.get("lines").arr.orEmpty().mapNotNull { it.descriptive }

    fun diagnostics(status: JsonObject?, platforms: JsonObject?, logs: JsonObject?, connected: Boolean) = GatewayDiagnostics(
        gatewayRunning = status?.get("gateway_running").bool ?: connected,
        gatewayState = status?.get("gateway_state").str,
        version = status?.get("version").str,
        pid = status?.get("gateway_pid").long,
        connectors = connectors(platforms),
        logs = logLines(logs),
        error = if (status == null) "Could not refresh dashboard status." else null,
    )

    /** Dot colour bucket for a connector state. */
    fun connectorHealth(state: String): Int = when (state.lowercase()) {
        "connected", "running", "ready", "online" -> 0
        "connecting", "starting" -> 1
        else -> 2
    }
}

// endregion

// region Extra headers (port of iOS CustomHeaderPolicy)

enum class HeaderIssue(val message: String) {
    EMPTY_NAME("Enter a header name."),
    INVALID_NAME("This isn’t a valid header name. Use letters, digits, and dashes."),
    RESERVED_NAME("Conduit manages this header itself, so it can't be set here."),
    INVALID_VALUE("Header values can't contain line breaks."),
}

data class HeaderRow(val id: Long, val name: String, val value: String) {
    val trimmedName: String get() = name.trim()
    val isBlank: Boolean get() = trimmedName.isEmpty() && value.isEmpty()
}

object HeaderRules {
    private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun issue(name: String, value: String): HeaderIssue? {
        val n = name.trim()
        if (n.isEmpty()) return HeaderIssue.EMPTY_NAME
        if (!token.matches(n)) return HeaderIssue.INVALID_NAME
        val lower = n.lowercase()
        if (lower in CustomHeaders.forbidden || lower.startsWith("sec-") || !CustomHeaders.isValidName(n)) {
            return HeaderIssue.RESERVED_NAME
        }
        if (value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7F } || !CustomHeaders.isValidValue(value)) {
            return HeaderIssue.INVALID_VALUE
        }
        return null
    }

    /** A row's problem: blank rows are dropped on save, so they never block it. */
    fun issue(row: HeaderRow): HeaderIssue? = if (row.isBlank) null else issue(row.name, row.value)

    /** Lower-cased names that appear more than once. */
    fun duplicateNames(rows: List<HeaderRow>): Set<String> {
        val seen = mutableSetOf<String>()
        val dupes = mutableSetOf<String>()
        rows.filter { it.trimmedName.isNotEmpty() }.forEach { if (!seen.add(it.trimmedName.lowercase())) dupes += it.trimmedName.lowercase() }
        return dupes
    }

    /** Valid headers with trimmed names; the first entry wins when a name repeats. */
    fun sendable(rows: List<HeaderRow>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            if (issue(row.name, row.value) == null && seen.add(row.trimmedName.lowercase())) out[row.trimmedName] = row.value
        }
        return out
    }

    /** The secure origin headers are bound to, or null for a plain-HTTP server. */
    fun origin(serverUrl: String): String? {
        val normalized = runCatching { ConnectionUrlPolicy.normalizedBaseUrl(serverUrl) }.getOrDefault(serverUrl.trim())
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "wss") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return if (uri.port != -1 && uri.port != 443) "https://$hostPart:${uri.port}" else "https://$hostPart"
    }
}

// endregion

// region Logs

object GatewayLogs {
    /** Components the logs viewer offers; null reads every component. */
    val components: List<String?> = listOf("gateway", "agent", "tools", "cron", null)
    val lineCounts = listOf(80, 200, 500)

    fun query(component: String?, lines: Int): Map<String, String?> =
        mapOf("lines" to lines.toString(), "component" to component)
}

// endregion
