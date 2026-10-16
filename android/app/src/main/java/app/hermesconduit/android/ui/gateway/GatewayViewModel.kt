package app.hermesconduit.android.ui.gateway

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.GatewayDiagnostics
import app.hermesconduit.android.model.GatewayLogs
import app.hermesconduit.android.model.GatewayParser
import app.hermesconduit.android.model.HeaderRules
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class GatewayLogsState(
    val component: String? = "gateway",
    val lines: Int = GatewayLogs.lineCounts.first(),
    val filter: String = "",
    val rows: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Settings › Gateway, its diagnostics and the logs viewer. */
class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    val hermes = application.hermes
    val restarts = GatewayRestartController.of(hermes)

    private val _diagnostics = MutableStateFlow<GatewayDiagnostics?>(null)
    val diagnostics: StateFlow<GatewayDiagnostics?> = _diagnostics
    private val _diagnosticsLoading = MutableStateFlow(false)
    val diagnosticsLoading: StateFlow<Boolean> = _diagnosticsLoading

    private val _logs = MutableStateFlow(GatewayLogsState())
    val logs: StateFlow<GatewayLogsState> = _logs

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting

    /** Reloads what the diagnostics page shows (iOS `refreshGatewayDiagnostics`). */
    fun refreshDiagnostics() {
        if (_diagnosticsLoading.value) return
        _diagnosticsLoading.value = true
        viewModelScope.launch {
            try {
                val status = async { runCatching { hermes.rest { it.getJson("api/status") } }.getOrNull() }
                val platforms = async { runCatching { hermes.rest { it.getJson("api/messaging/platforms") } }.getOrNull() }
                val logs = async { runCatching { hermes.rest { it.getJson("api/logs", GatewayLogs.query("gateway", 80)) } }.getOrNull() }
                _diagnostics.value = GatewayParser.diagnostics(status.await(), platforms.await(), logs.await(), hermes.link.value == LinkState.CONNECTED)
            } finally {
                _diagnosticsLoading.value = false
            }
        }
    }

    fun setLogComponent(component: String?) {
        _logs.update { it.copy(component = component) }
        loadLogs()
    }

    fun setLogLines(lines: Int) {
        _logs.update { it.copy(lines = lines) }
        loadLogs()
    }

    fun setLogFilter(filter: String) = _logs.update { it.copy(filter = filter) }

    fun loadLogs() {
        val request = _logs.value
        _logs.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = hermes.rest { it.getJson("api/logs", GatewayLogs.query(request.component, request.lines)) }
                if (_logs.value.component == request.component && _logs.value.lines == request.lines) {
                    _logs.update { it.copy(rows = GatewayParser.logLines(result), loading = false) }
                }
            } catch (e: Exception) {
                _logs.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    /** Reconnects Conduit's socket, then reports whether it came back. */
    fun reconnect() {
        if (_reconnecting.value) return
        _reconnecting.value = true
        hermes.reconnectNow()
        viewModelScope.launch {
            withTimeoutOrNull(15_000) {
                // Give the old socket a moment to drop, then wait for the comeback.
                kotlinx.coroutines.delay(400)
                hermes.link.first { it == LinkState.CONNECTED }
            }
            _reconnecting.value = false
        }
    }

    // region Connection settings

    /** Saves the change and rebuilds the dashboard client so REST and the socket both use it. */
    private fun apply(transform: (app.hermesconduit.android.data.SavedConnection) -> app.hermesconduit.android.data.SavedConnection) {
        hermes.updateConnection(transform)
        hermes.connection.value?.id?.let(hermes::switchDashboard)
    }

    fun saveCloudflare(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        apply { it.copy(cfClientId = id.ifEmpty { null }, cfClientSecret = secret.ifEmpty { it.cfClientSecret }) }
    }

    fun removeCloudflare() = apply { it.copy(cfClientId = null, cfClientSecret = null) }

    fun saveHeaders(rows: List<app.hermesconduit.android.model.HeaderRow>) = apply { it.copy(customHeaders = HeaderRules.sendable(rows)) }

    // endregion
}
