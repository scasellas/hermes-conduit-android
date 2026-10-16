package app.hermesconduit.android.ui.capabilities

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.CapabilityInstallState
import app.hermesconduit.android.model.ConnectorCatalogEntry
import app.hermesconduit.android.model.ConnectorGrouping
import app.hermesconduit.android.model.ConnectorServer
import app.hermesconduit.android.model.ConnectorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** MCP · Plugin x, MCP · Catalog or MCP · Custom, as Desktop says it. */
private fun kind(server: ConnectorServer, inCatalog: Boolean): String {
    server.plugin?.let { plugin -> return if (plugin.isEmpty()) L10n.tr("MCP · Plugin") else L10n.tr("MCP · Plugin %s", plugin) }
    return if (inCatalog) L10n.tr("MCP · Catalog") else L10n.tr("MCP · Custom")
}

@Composable
private fun word(state: ConnectorState): String? = when (state) {
    ConnectorState.Off -> t("Off")
    ConnectorState.Checking -> t("Checking…")
    ConnectorState.Unknown -> null
    is ConnectorState.On -> if (state.tools > 0) t("%d tools", state.tools) else t("On")
    ConnectorState.NeedsSignIn -> t("Needs sign-in")
    is ConnectorState.Failed -> t("Error")
}

@Composable
private fun dot(state: ConnectorState): Color? = when (state) {
    ConnectorState.Off, ConnectorState.Unknown -> null
    ConnectorState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
    is ConnectorState.On -> CapabilityColors.green
    ConnectorState.NeedsSignIn -> CapabilityColors.orange
    is ConnectorState.Failed -> CapabilityColors.red
}

/** The line under the name: why it's broken, or what it is. */
@Composable
private fun detail(server: ConnectorServer, state: ConnectorState, entry: ConnectorCatalogEntry?): String? = when {
    state is ConnectorState.Failed && state.error.isNotEmpty() -> state.error
    state == ConnectorState.NeedsSignIn -> t("Sign in to let this server answer.")
    !entry?.description.isNullOrEmpty() -> entry?.description
    else -> server.url ?: server.command
}

private fun CapabilitiesState.stateOf(server: ConnectorServer) =
    ConnectorState.of(server, probes[server.name], server.name in probing)

/**
 * Settings > Capabilities > Connectors: the MCP servers that are on (tested
 * live, broken ones first), the catalog's servers to install, and the ones
 * that are off.
 */
@Composable
fun ConnectorsTab(vm: CapabilitiesViewModel, state: CapabilitiesState, query: String, open: (CapabilityPage) -> Unit) {
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf<ConnectorCatalogEntry?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val states = state.servers.associate { it.name to state.stateOf(it) }
    val groups = ConnectorGrouping.groups(state.servers, state.connectorCatalog, states, query)

    LaunchedEffect(state.profile) {
        if (!state.connectorsLoaded) vm.loadConnectors()
        vm.probeServers()
    }

    fun install(entry: ConnectorCatalogEntry) {
        vm.clearConnectorInstallFailure(entry.name)
        if (entry.requiredEnv.isEmpty()) vm.installConnector(entry, emptyMap()) else installing = entry
    }

    @Composable
    fun serverRows(servers: List<ConnectorServer>) {
        servers.forEachIndexed { index, server ->
            if (index > 0) CapabilityRowDivider()
            val serverState = states[server.name] ?: ConnectorState.Unknown
            val entry = state.connectorCatalog.firstOrNull { it.name == server.name }
            CapabilityRow(
                icon = CapabilityIcons.transport(server.transport, server.isFromPlugin),
                active = server.enabled,
                title = server.name,
                subtitle = kind(server, entry != null),
                detail = detail(server, serverState, entry),
                meta = word(serverState),
                metaDot = dot(serverState),
                onClick = { open(CapabilityPage.Connector(server.name)) },
            ) {
                // A plugin's server is changed from Plugins.
                if (!server.isFromPlugin) {
                    CapabilitySwitch(server.enabled, { enabled ->
                        actionError = null
                        scope.launch {
                            try {
                                vm.setServerEnabled(server.name, enabled)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                actionError = e.userMessage()
                            }
                        }
                    })
                }
            }
        }
    }

    CapabilityTabList(refresh = {
        vm.loadConnectors()
        vm.probeServers(force = true)
    }) {
        when {
            !state.connectorsLoaded -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.connectorsError != null && state.servers.isEmpty() -> item {
                CapabilityEmptyState(
                    title = t("Couldn't read the MCP servers"),
                    message = state.connectorsError,
                    retry = {
                        scope.launch {
                            vm.loadConnectors()
                            vm.probeServers()
                        }
                    },
                )
            }
            groups.connected.isEmpty() && groups.available.isEmpty() && groups.off.isEmpty() -> item {
                CapabilityEmptyState(
                    title = if (query.isEmpty()) t("No connectors yet") else t("No matching connectors"),
                    message = if (query.isEmpty()) {
                        t("Ask Hermes to add an MCP server, or install one from the catalog when it lists some.")
                    } else {
                        t("Nothing matches “%s”.", query)
                    },
                    icon = Icons.Default.Hub,
                )
            }
            else -> {
                state.connectorsError?.let { item { CapabilityRefreshBanner(it) } }
                actionError?.let { item { FootnoteText(it, MaterialTheme.colorScheme.error) } }
                if (groups.connected.isNotEmpty()) {
                    item(key = "connected") {
                        CapabilitySection(t("Connected"), footer = t("Each server is tested when this page opens. Changes apply to new sessions.")) {
                            serverRows(groups.connected)
                        }
                    }
                }
                if (groups.available.isNotEmpty()) {
                    item(key = "available") {
                        CapabilitySection(t("Available"), footer = t("From Hermes' MCP catalog.")) {
                            groups.available.forEachIndexed { index, entry ->
                                if (index > 0) CapabilityRowDivider()
                                val install = state.connectorInstalls[entry.name]
                                CapabilityRow(
                                    icon = CapabilityIcons.transport(if (entry.transport == "http") "http" else "stdio"),
                                    active = false,
                                    title = entry.name,
                                    subtitle = t("MCP · Catalog"),
                                    pills = if (entry.authType == "oauth") listOf(PillModel(t("Sign-in"))) else emptyList(),
                                    detail = entry.description,
                                ) {
                                    CapabilityRowButton(t("Install"), busy = install == CapabilityInstallState.Running) { install(entry) }
                                }
                                if (install is CapabilityInstallState.Failed) InstallFailure(install.reason)
                            }
                        }
                    }
                }
                if (groups.off.isNotEmpty()) {
                    item(key = "off") {
                        CapabilitySection(t("Turned off"), footer = t("Sign-ins and keys are kept.")) {
                            serverRows(groups.off)
                        }
                    }
                }
            }
        }
    }

    installing?.let { entry ->
        ConnectorInstallDialog(entry, onDismiss = { installing = null }) { env ->
            installing = null
            vm.installConnector(entry, env)
        }
    }
}

/** Install a catalog server: what it runs, and the keys it asks for. */
@Composable
private fun ConnectorInstallDialog(entry: ConnectorCatalogEntry, onDismiss: () -> Unit, install: (Map<String, String>) -> Unit) {
    val values = remember(entry.name) { mutableStateMapOf<String, String>() }
    val canInstall = entry.requiredEnv.filter { it.required }.all { values[it.name].orEmpty().isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry.description.isNotEmpty()) Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                Text(t("Runs").uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entry.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        entry.command?.let {
                            Text((listOf(it) + entry.args).joinToString(" "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Text(t("Check what it runs before you install it."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t("Keys").uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.requiredEnv.forEach { field ->
                    OutlinedTextField(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values[field.name] = it },
                        label = { Text(if (field.required) field.prompt else t("%s (optional)", field.prompt)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    t("Saved to this profile on your host. Conduit never reads them back."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canInstall, onClick = {
                install(values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() })
            }) { Text(t("Install")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}

/** A server's page: its state and switch, where it runs, test and sign-in, its tools, and Remove. */
@Composable
fun ConnectorDetailPage(vm: CapabilitiesViewModel, state: CapabilitiesState, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = state.servers.firstOrNull { it.name == name }
    val probe = state.probes[name]
    val entry = state.connectorCatalog.firstOrNull { it.name == name }
    var signInFlow by remember(name) { mutableStateOf<String?>(null) }
    var signInJob by remember(name) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var message by remember(name) { mutableStateOf<String?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        if (server != null && server.enabled && probe == null && name !in state.probing) vm.probeLater(name)
    }

    fun setEnabled(enabled: Boolean) {
        message = null
        scope.launch {
            try {
                vm.setServerEnabled(name, enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.userMessage()
            }
        }
    }

    /** Starts Hermes' OAuth flow, opens its page in a Custom Tab, and waits for the host to receive the callback. */
    fun signIn() {
        message = null
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                val (flow, url) = vm.startSignIn(name)
                signInFlow = flow
                message = L10n.tr("Finish the sign-in in your browser.")
                runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                    .onFailure { message = it.userMessage() }
                repeat(150) {
                    delay(2_000)
                    if (signInFlow != flow) return@launch
                    val (status, error) = vm.signInStatus(flow)
                    if (status == "approved") {
                        signInFlow = null
                        message = L10n.tr("Signed in.")
                        vm.probe(name)
                        return@launch
                    }
                    if (status == "error") {
                        signInFlow = null
                        message = error ?: L10n.tr("The sign-in didn't finish.")
                        return@launch
                    }
                }
                signInFlow = null
                message = L10n.tr("The sign-in timed out. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInFlow = null
                message = e.userMessage()
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        val flow = signInFlow ?: return
        signInFlow = null
        message = null
        vm.cancelSignIn(flow)
    }

    CapabilityDetailScaffold(name, onBack) {
        if (server != null) {
            val serverState = state.stateOf(server)
            CapabilityCard {
                Text(server.name, style = MaterialTheme.typography.titleLarge)
                Text(kind(server, entry != null), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry?.description?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                word(serverState)?.let { text ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dot(serverState)?.let { Box(Modifier.size(7.dp).background(it, CircleShape)) }
                        Text(text, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (serverState is ConnectorState.Failed && serverState.error.isNotEmpty()) {
                    SelectionContainer { Text(serverState.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                val plugin = server.plugin
                if (plugin != null) {
                    Text(
                        if (plugin.isEmpty()) t("A plugin provides this server. Change it from Plugins.")
                        else t("The %s plugin provides this server. Change it from Plugins.", plugin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TurnedOnRow(server.enabled) { setEnabled(it) }
                }
            }
            CapabilityDetailSection(t("Connection")) {
                server.url?.let { CapabilityMetric(t("Address"), it, maxLines = 2) }
                server.command?.let { CapabilityMetric(t("Command"), (listOf(it) + server.args).joinToString(" "), maxLines = 3) }
                if (server.enabled) {
                    val checking = serverState == ConnectorState.Checking
                    CapabilityWideButton(if (checking) t("Testing…") else t("Test Connection"), Icons.Default.Refresh, enabled = !checking) {
                        message = null
                        vm.probeLater(name)
                    }
                    if (server.canSignIn && (serverState == ConnectorState.NeedsSignIn || server.auth == "oauth")) {
                        if (signInFlow != null) {
                            CapabilityWideButton(t("Stop Waiting"), Icons.Default.Cancel) { cancelSignIn() }
                        } else {
                            CapabilityWideButton(t("Sign In"), Icons.Default.Key) { signIn() }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            CapabilityDetailSection(t("Tools")) {
                when {
                    !server.enabled -> SmallNote(t("%s is off. Turn it on to read the tools it brings.", server.name))
                    serverState == ConnectorState.Checking || (probe == null && serverState == ConnectorState.Unknown) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            SmallNote(t("Reading the tool list…"))
                        }
                    probe != null && probe.ok -> if (probe.tools.isEmpty()) {
                        SmallNote(t("This server brings no tools."))
                    } else {
                        probe.tools.forEach { tool ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(tool.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                if (tool.description.isNotEmpty()) {
                                    Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                }
                            }
                        }
                    }
                    serverState == ConnectorState.NeedsSignIn -> SmallNote(t("Sign in to %s to read its tools.", server.name))
                    else -> SmallNote(t("Tool list unavailable."))
                }
            }
            if (!server.isFromPlugin) {
                CapabilityWideButton(t("Remove Server"), Icons.Default.Delete, enabled = !removing, destructive = true) { confirmingRemove = true }
            }
        } else {
            FootnoteText(t("This server isn't configured anymore."))
        }
        FootnoteText(t("Changes apply to new sessions."))
        Box(Modifier.heightIn(min = 8.dp))
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(t("Remove %s?", name)) },
            text = { Text(t("The server leaves this profile's config on your host. Its keys stay in .env.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    removing = true
                    scope.launch {
                        try {
                            vm.removeServer(name)
                            removing = false
                            onBack()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = e.userMessage()
                            removing = false
                        }
                    }
                }) { Text(t("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun SmallNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
