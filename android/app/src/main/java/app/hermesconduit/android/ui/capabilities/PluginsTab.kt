package app.hermesconduit.android.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.AgentPluginRow
import app.hermesconduit.android.model.CapabilityRestartState
import app.hermesconduit.android.model.PluginCatalogEntry
import app.hermesconduit.android.model.PluginUpdateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun tierPill(tier: String): PillModel =
    if (tier == "official") PillModel(t("Official"), MaterialTheme.colorScheme.tertiary) else PillModel(t("Community"))

/** Where an installed plugin came from: its catalog tier, else its source. */
@Composable
private fun sourcePill(plugin: AgentPluginRow, entry: PluginCatalogEntry?): PillModel? {
    if (entry != null) return tierPill(entry.tier)
    return when (plugin.source) {
        "bundled" -> PillModel(t("Built-in"))
        "git" -> PillModel(t("Git"))
        "user" -> PillModel(t("Local"))
        else -> null
    }
}

private fun version(plugin: AgentPluginRow): String? = plugin.version?.let { "v$it" }

/**
 * Settings > Capabilities > Plugins: the agent plugins installed for the
 * profile with their switches, each with a page to update or uninstall it,
 * then Hermes' plugin catalog. A change applied on the gateway's next start
 * offers Restart Gateway.
 */
@Composable
fun PluginsTab(vm: CapabilitiesViewModel, state: CapabilitiesState, query: String, open: (CapabilityPage) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PluginCatalogEntry?>(null) }
    val canManage = state.canManagePlugins
    val installed = state.plugins.filter {
        query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    val catalog = PluginCatalogEntry.available(state.pluginCatalog, query)

    LaunchedEffect(state.profile) {
        if (!state.pluginsLoaded) vm.loadPlugins()
    }

    fun install(entry: PluginCatalogEntry) {
        message = null
        scope.launch {
            message = try {
                val outcome = vm.installPlugin(entry)
                when {
                    outcome.missingEnv.isNotEmpty() ->
                        L10n.tr("%s is installed but needs a key before it works: %s.", outcome.name, outcome.missingEnv.joinToString(", "))
                    outcome.restartRequired -> L10n.tr("%s is installed. Restart the gateway to load it.", outcome.name)
                    else -> L10n.tr("%s is installed and loaded.", outcome.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
        }
    }

    CapabilityTabList(refresh = { vm.loadPlugins() }) {
        when {
            !state.pluginsLoaded -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.pluginsError != null && state.plugins.isEmpty() -> item {
                CapabilityEmptyState(
                    title = t("Couldn't load agent plugins"),
                    message = state.pluginsError,
                    retry = { scope.launch { vm.loadPlugins() } },
                )
            }
            else -> {
                state.pluginsError?.let { item { CapabilityRefreshBanner(it) } }
                if (!canManage) item { FootnoteText(t("Hermes installs plugins for the host's default profile. Switch to it to change them.")) }
                if (state.pluginsNeedRestart || state.restart != CapabilityRestartState.Idle) item { PluginRestartCard(vm, state) }
                message?.let { item { FootnoteText(it) } }
                item(key = "installed") {
                    CapabilitySection(t("Installed"), footer = t("Plugins extend the agent with tools, hooks and providers.")) {
                        if (installed.isEmpty()) {
                            Text(
                                if (query.isEmpty()) t("No agent plugins installed for this profile.") else t("Nothing matches “%s”.", query),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        } else {
                            installed.forEachIndexed { index, plugin ->
                                if (index > 0) CapabilityRowDivider()
                                PluginListRow(vm, state, plugin, canManage, open) { message = it }
                            }
                        }
                    }
                }
                if (catalog.isNotEmpty()) {
                    item(key = "catalog") {
                        CapabilitySection(t("Plugin catalog"), footer = t("Reviewed plugins from Hermes' catalog.")) {
                            catalog.forEachIndexed { index, entry ->
                                if (index > 0) CapabilityRowDivider()
                                CapabilityRow(
                                    icon = Icons.Default.Inventory2,
                                    active = false,
                                    title = entry.displayName,
                                    pills = listOf(tierPill(entry.tier)),
                                    detail = entry.description,
                                ) {
                                    if (canManage) {
                                        CapabilityRowButton(t("Install"), busy = entry.name in state.pluginBusy) { pendingInstall = entry }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingInstall?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text(t("Install %s?", entry.displayName)) },
            text = { Text(entry.capabilitySummary.ifEmpty { entry.description }) },
            confirmButton = {
                TextButton(onClick = {
                    pendingInstall = null
                    install(entry)
                }) { Text(t("Install")) }
            },
            dismissButton = { TextButton(onClick = { pendingInstall = null }) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun PluginListRow(
    vm: CapabilitiesViewModel,
    state: CapabilitiesState,
    plugin: AgentPluginRow,
    canManage: Boolean,
    open: (CapabilityPage) -> Unit,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entry = PluginCatalogEntry.entryFor(plugin, state.pluginCatalog)
    val pills = buildList {
        sourcePill(plugin, entry)?.let(::add)
        if (entry?.updateAvailable == true) add(PillModel(t("Update available"), MaterialTheme.colorScheme.primary))
        if (plugin.authRequired) add(PillModel(t("Needs sign-in"), CapabilityColors.orange))
    }
    CapabilityRow(
        icon = Icons.Default.Extension,
        active = plugin.isEnabled,
        title = plugin.name,
        subtitle = version(plugin),
        pills = pills,
        detail = plugin.description,
        onClick = { open(CapabilityPage.Plugin(plugin.name)) },
    ) {
        if (plugin.name in state.pluginBusy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            CapabilitySwitch(plugin.isEnabled, { enabled ->
                onError(null)
                scope.launch {
                    try {
                        vm.setPluginEnabled(plugin, enabled)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e.userMessage())
                    }
                }
            }, enabled = canManage)
        }
    }
}

/** Restart Gateway, offered after a plugin change Hermes applies on the gateway's next start. */
@Composable
fun PluginRestartCard(vm: CapabilitiesViewModel, state: CapabilitiesState) {
    var confirming by remember { mutableStateOf(false) }
    CapabilityCard {
        if (state.pluginsNeedRestart) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Sync, null, tint = CapabilityColors.orange)
                Text(t("Restart the gateway to apply plugin changes."), style = MaterialTheme.typography.titleSmall)
            }
            CapabilityWideButton(t("Restart Gateway"), Icons.Default.RestartAlt, enabled = !state.restart.isInProgress) { confirming = true }
        }
        RestartStatus(state.restart, onDismiss = vm::dismissRestartOutcome)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(t("Restart the gateway?")) },
            text = {
                Text(t("Hermes restarts its gateway process. Messaging channels, running sessions and any profiles that share this gateway reconnect once it's back."))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.restartGateway()
                }) { Text(t("Restart Gateway"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(t("Cancel")) } },
        )
    }
}

/** Where Restart Gateway stands: what Hermes is doing, then how it went. */
@Composable
private fun RestartStatus(restart: CapabilityRestartState, onDismiss: () -> Unit) {
    val (title, detail) = when (restart) {
        CapabilityRestartState.Idle -> return
        CapabilityRestartState.Requesting -> t("Asking Hermes to restart the gateway…") to null
        is CapabilityRestartState.Restarting -> when (restart.stage) {
            CapabilityRestartState.Stage.DRAINING -> t("Letting running tasks finish…")
            CapabilityRestartState.Stage.STOPPING -> t("Stopping the gateway…")
            CapabilityRestartState.Stage.STARTING -> t("Starting the gateway…")
        } to restart.detail
        is CapabilityRestartState.Restarted -> t("Gateway restarted") to
            restart.sharedProfiles.takeIf { it.isNotEmpty() }?.let { t("Profiles on this gateway: %s", it.joinToString(", ")) }
        is CapabilityRestartState.Failed -> t("Couldn't restart the gateway") to restart.detail
        CapabilityRestartState.FailedToStart -> t("The gateway didn't start") to t("Run hermes gateway status on the host to see why.")
        CapabilityRestartState.NotBackYet -> t("The gateway isn't back yet") to
            t("It may still be finishing running tasks. Run hermes gateway status on the host to check.")
        CapabilityRestartState.Unsupported -> t("This Hermes can't restart its gateway from Conduit") to
            t("Update Hermes, or run hermes gateway restart on the host.")
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (restart.isInProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (!restart.isInProgress) TextButton(onClick = onDismiss) { Text(t("OK")) }
    }
}

private data class PluginConsent(val plugin: AgentPluginRow, val lines: List<String>)

/** A plugin's page: what it is, its switch, Update and Uninstall. */
@Composable
fun PluginDetailPage(vm: CapabilitiesViewModel, state: CapabilitiesState, name: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val plugin = state.plugins.firstOrNull { it.name == name }
    val canManage = state.canManagePlugins
    var message by remember(name) { mutableStateOf<String?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf<PluginConsent?>(null) }

    fun update(target: AgentPluginRow, accept: Boolean) {
        message = null
        scope.launch {
            try {
                when (val outcome = vm.updatePlugin(target, accept)) {
                    PluginUpdateOutcome.UpToDate -> message = L10n.tr("%s is up to date.", target.name)
                    is PluginUpdateOutcome.Updated -> message = if (outcome.restartRequired) {
                        L10n.tr("%s is updated. Restart the gateway to apply it.", target.name)
                    } else {
                        L10n.tr("%s is updated.", target.name)
                    }
                    is PluginUpdateOutcome.NeedsConsent -> consent = PluginConsent(target, outcome.lines)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.userMessage()
            }
        }
    }

    CapabilityDetailScaffold(name, onBack) {
        if (plugin != null) {
            val entry = PluginCatalogEntry.entryFor(plugin, state.pluginCatalog)
            val busy = plugin.name in state.pluginBusy
            CapabilityCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleLarge)
                    version(plugin)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (plugin.description.isNotEmpty()) {
                    Text(plugin.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sourcePill(plugin, entry)?.let { CapabilityPill(it.text, it.tint) }
                if (plugin.authRequired) {
                    SelectionContainer {
                        Text(
                            plugin.authCommand?.let { t("It needs a sign-in on your host: %s", it) } ?: t("It needs a sign-in on your host."),
                            style = MaterialTheme.typography.bodySmall,
                            color = CapabilityColors.orange,
                        )
                    }
                }
                TurnedOnRow(plugin.isEnabled, enabled = canManage && !busy) { enabled ->
                    message = null
                    scope.launch {
                        try {
                            vm.setPluginEnabled(plugin, enabled)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = e.userMessage()
                        }
                    }
                }
            }
            if (state.pluginsNeedRestart || state.restart != CapabilityRestartState.Idle) PluginRestartCard(vm, state)
            message?.let { FootnoteText(it) }
            if (canManage && plugin.canUpdate) {
                val label = when {
                    busy -> t("Updating…")
                    entry?.updateAvailable == true -> t("Update")
                    else -> t("Check for Update")
                }
                CapabilityWideButton(label, Icons.Default.Download, enabled = !busy) { update(plugin, accept = false) }
            }
            if (canManage && plugin.canRemove) {
                CapabilityWideButton(t("Uninstall"), Icons.Default.Delete, enabled = !busy, destructive = true) { confirmingRemove = true }
            }
            if (!canManage) FootnoteText(t("Hermes installs plugins for the host's default profile. Switch to it to change them."))
        } else {
            FootnoteText(t("This plugin isn't installed anymore."))
        }
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(t("Uninstall %s?", name)) },
            text = { Text(t("This deletes the plugin's files from your host. Restart the gateway to unload it. You can install it again from the catalog.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    val target = plugin ?: return@TextButton
                    scope.launch {
                        try {
                            vm.removePlugin(target)
                            onBack()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = e.userMessage()
                        }
                    }
                }) { Text(t("Uninstall"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(t("Cancel")) } },
        )
    }

    consent?.let { pending ->
        val added = pending.lines.joinToString("\n")
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text(t("%s asks for more", name)) },
            text = {
                Text(
                    if (added.isEmpty()) t("The new version adds what the installed one doesn't have. Apply it only if you trust it.")
                    else t("The new version adds what the installed one doesn't have. Apply it only if you trust it:\n\n%s", added),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    consent = null
                    update(pending.plugin, accept = true)
                }) { Text(t("Apply Update")) }
            },
            dismissButton = { TextButton(onClick = { consent = null }) { Text(t("Cancel")) } },
        )
    }
}
