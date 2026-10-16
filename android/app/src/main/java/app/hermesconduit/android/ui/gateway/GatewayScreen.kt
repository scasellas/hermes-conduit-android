package app.hermesconduit.android.ui.gateway

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.GatewayLogs
import app.hermesconduit.android.model.GatewayParser
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.nav.activityAppViewModel

/** Route `settings.gateway`; `settings.gateway/diagnostics` and `settings.gateway/logs` are its sub-pages. */
@Composable
fun GatewayScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val viewModel: GatewayViewModel = viewModel()
    when (LocalRouteArg.current) {
        "diagnostics" -> GatewayDiagnosticsScreen(viewModel, onBack, navigate)
        "logs" -> GatewayLogsScreen(viewModel, onBack)
        else -> GatewaySettingsScreen(viewModel, onBack, navigate)
    }
}

@Composable
private fun GatewaySettingsScreen(viewModel: GatewayViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val hermes = viewModel.hermes
    val connection by hermes.connection.collectAsStateWithLifecycle()
    val link by hermes.link.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val slot by viewModel.restarts.slot.collectAsStateWithLifecycle()
    val restart = slot.state(connection?.id)
    val app = activityAppViewModel()
    var confirmingRestart by remember { mutableStateOf(false) }
    var editingHeaders by remember { mutableStateOf(false) }
    val connected = link == LinkState.CONNECTED
    val server = connection?.baseUrl

    ScreenScaffold(t("Gateway"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Connection"), footer = t("Reconnect only reconnects Conduit. Restart Gateway restarts Hermes' gateway on your host.")) {
                SettingsRow(t("Server"), value = server ?: "—")
                SettingsRow(t("Status"), trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        val color = if (connected) SuccessGreen else MaterialTheme.colorScheme.error
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                        Text(if (connected) t("Connected") else t("Disconnected"), color = color)
                    }
                })
                SettingsRow(
                    if (reconnecting) t("Reconnecting…") else t("Reconnect"),
                    icon = Icons.Default.Refresh,
                    onClick = if (reconnecting) null else viewModel::reconnect,
                )
                SettingsRow(
                    t("Restart Gateway"),
                    icon = Icons.Default.RestartAlt,
                    onClick = if (server != null && viewModel.restarts.canRestart) ({ confirmingRestart = true }) else null,
                )
                GatewayRestartStatusRow(restart, viewModel.restarts::dismiss)
                SettingsRow(
                    t("Diagnostics & logs"),
                    icon = Icons.Default.Description,
                    onClick = if (server != null) ({ navigate("settings.gateway/diagnostics") }) else null,
                )
            }

            CloudflareSection(
                initialId = connection?.cfClientId.orEmpty(),
                enabled = connection?.cfClientId != null,
                onSave = viewModel::saveCloudflare,
                onRemove = viewModel::removeCloudflare,
            )

            SettingsSection(t("Extra headers"), footer = t("Reconnect after changing them.")) {
                Text(
                    t("For reverse proxies such as Pangolin, Traefik, or nginx that check a header. Sent only to this server, over HTTPS."),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val configured = connection?.customHeaders.orEmpty()
                configured.keys.forEach { name -> SettingsRow(name, value = "••••••") }
                SettingsRow(
                    if (configured.isEmpty()) t("Add headers") else t("Edit headers"),
                    onClick = if (server != null) ({ editingHeaders = true }) else null,
                )
            }

            Button(
                onClick = { navigate("home"); app?.signOut() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Text(t("Sign Out of This Dashboard"), Modifier.padding(start = 8.dp))
            }
        }
    }

    if (confirmingRestart) GatewayRestartConfirmation(onConfirm = viewModel.restarts::restart, onDismiss = { confirmingRestart = false })
    if (editingHeaders && server != null) {
        CustomHeadersEditor(
            serverUrl = server,
            headers = connection?.customHeaders.orEmpty(),
            onSave = { viewModel.saveHeaders(it); editingHeaders = false },
            onDismiss = { editingHeaders = false },
        )
    }
}

@Composable
private fun CloudflareSection(initialId: String, enabled: Boolean, onSave: (String, String) -> Unit, onRemove: () -> Unit) {
    var on by rememberSaveable(enabled) { mutableStateOf(enabled) }
    var clientId by rememberSaveable(initialId) { mutableStateOf(initialId) }
    var clientSecret by rememberSaveable { mutableStateOf("") }
    SettingsSection("Cloudflare Access") {
        SettingsToggle(t("Use service token"), checked = on, onChange = { value ->
            on = value
            if (!value) { clientSecret = ""; if (enabled) onRemove() }
        })
        if (on) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(clientId, { clientId = it }, label = { Text(t("Client ID")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    clientSecret, { clientSecret = it },
                    label = { Text(t("Client Secret")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSave(clientId, clientSecret); clientSecret = "" },
                    enabled = clientId.isNotBlank() && (clientSecret.isNotBlank() || enabled),
                ) { Text(t("Save token")) }
                Text(
                    t("The secret is stored encrypted on this device. Saving reconnects."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GatewayDiagnosticsScreen(viewModel: GatewayViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val loading by viewModel.diagnosticsLoading.collectAsStateWithLifecycle()
    val link by viewModel.hermes.link.collectAsStateWithLifecycle()
    val connection by viewModel.hermes.connection.collectAsStateWithLifecycle()
    val slot by viewModel.restarts.slot.collectAsStateWithLifecycle()
    val restart = slot.state(connection?.id)
    var confirmingRestart by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshDiagnostics() }
    // The page shows what the gateway did once a restart ends.
    LaunchedEffect(restart.isInProgress) { if (!restart.isInProgress && restart != app.hermesconduit.android.model.GatewayRestartState.Idle) viewModel.refreshDiagnostics() }

    ScreenScaffold(
        t("Diagnostics & logs"),
        onBack,
        actions = {
            IconButton(onClick = { confirmingRestart = true }, enabled = viewModel.restarts.canRestart) {
                Icon(Icons.Default.RestartAlt, t("Restart Gateway"))
            }
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = viewModel::refreshDiagnostics) { Icon(Icons.Default.Refresh, t("Refresh gateway diagnostics")) }
            }
        },
    ) { padding ->
        SettingsColumn(padding) {
            val d = diagnostics
            val running = d?.gatewayRunning ?: (link == LinkState.CONNECTED)
            SettingsSection(if (running) t("Connected") else t("Disconnected")) {
                SettingsRow(t("Gateway"), value = d?.gatewayState ?: if (running) t("Online") else t("Unavailable"))
                d?.version?.let { SettingsRow(t("Version"), value = it) }
                d?.pid?.let { SettingsRow(t("Process"), value = t("PID %s", it.toString())) }
                ErrorText(d?.error?.let { t(it) })
                GatewayRestartStatusRow(restart, viewModel.restarts::dismiss)
                if (!running && !restart.isInProgress) {
                    SettingsRow(t("Retry connection"), icon = Icons.Default.Refresh, onClick = viewModel::reconnect)
                }
            }
            SettingsSection(t("Connectors")) {
                val connectors = d?.connectors.orEmpty().filter { it.configured != false }
                if (connectors.isEmpty()) {
                    Text(t("No configured connectors were reported."), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                connectors.forEach { connector ->
                    val color = when (GatewayParser.connectorHealth(connector.state)) {
                        0 -> SuccessGreen
                        1 -> WarningOrange
                        else -> MaterialTheme.colorScheme.error
                    }
                    SettingsRow(
                        connector.name,
                        subtitle = listOfNotNull(connector.state, connector.error).joinToString(" · "),
                        trailing = { Box(Modifier.size(9.dp).background(color, CircleShape)) },
                    )
                }
            }
            SettingsSection(t("Recent gateway logs")) {
                val lines = d?.logs.orEmpty()
                if (lines.isEmpty()) {
                    Text(t("No gateway log lines were returned."), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LogText(lines.joinToString("\n"), Modifier.padding(12.dp))
                }
                SettingsRow(t("All logs"), icon = Icons.Default.Description, onClick = { navigate("settings.gateway/logs") })
            }
        }
    }
    if (confirmingRestart) GatewayRestartConfirmation(onConfirm = viewModel.restarts::restart, onDismiss = { confirmingRestart = false })
}

@Composable
private fun LogText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.horizontalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GatewayLogsScreen(viewModel: GatewayViewModel, onBack: () -> Unit) {
    val state by viewModel.logs.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadLogs() }
    val shown = state.rows.filter { state.filter.isBlank() || it.contains(state.filter, ignoreCase = true) }
    val text = shown.joinToString("\n")

    ScreenScaffold(
        t("Logs"),
        onBack,
        actions = {
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty()) { Icon(Icons.Default.ContentCopy, t("Copy")) }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            }, enabled = text.isNotEmpty()) { Icon(Icons.Default.Share, t("Share")) }
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = viewModel::loadLogs) { Icon(Icons.Default.Refresh, t("Refresh")) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GatewayLogs.components.forEach { component ->
                    FilterChip(
                        selected = state.component == component,
                        onClick = { viewModel.setLogComponent(component) },
                        label = { Text(component ?: t("All")) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GatewayLogs.lineCounts.forEach { count ->
                    FilterChip(selected = state.lines == count, onClick = { viewModel.setLogLines(count) }, label = { Text(t("%d lines", count)) })
                }
            }
            OutlinedTextField(
                state.filter, viewModel::setLogFilter,
                placeholder = { Text(t("Filter")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error)
            Box(Modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                if (shown.isEmpty() && !state.loading) {
                    Text(t("No gateway log lines were returned."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LogText(text)
                }
            }
        }
    }
}
