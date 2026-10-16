package app.hermesconduit.android.ui.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.GatewayRestartStage
import app.hermesconduit.android.model.PluginFeature
import app.hermesconduit.android.model.PluginStatus
import app.hermesconduit.android.model.PluginUpdateRules
import app.hermesconduit.android.model.PluginUpdateState
import app.hermesconduit.android.model.PluginUpdateStep
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.gateway.StatusRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFF9500)

/** Settings › Conduit plugin (iOS `ConduitPluginSettingsDetail`). Route `settings.plugin`. */
@Composable
fun PluginScreen(onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") navigate: (String) -> Unit) {
    val context = LocalContext.current
    val hermes = context.hermes
    val repository = remember(hermes) { PluginStatusRepository.of(hermes) }
    val status by repository.status.collectAsStateWithLifecycle()
    val update by repository.update.collectAsStateWithLifecycle()
    val connection by hermes.connection.collectAsStateWithLifecycle()
    val dashboards by hermes.dashboards.collectAsStateWithLifecycle()
    val link by hermes.link.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    var showsCommands by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Asked again on every visit and dashboard switch.
    LaunchedEffect(connection?.id) {
        checking = true
        repository.status(force = true)
        checking = false
    }
    val needsManual = needsManualUpdate(status, update)
    LaunchedEffect(needsManual) { if (needsManual) showsCommands = true }

    ScreenScaffold(t("Conduit plugin"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Conduit plugin")) {
                Text(
                    t("The plugin on your Hermes host serves Conduit's notifications, live voice, calls from Hermes and more. Each dashboard's host has its own copy."),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (dashboards.size > 1) connection?.let { SettingsRow(t("Dashboard"), value = it.displayName) }
                SettingsRow(t("Version"), value = status.runningVersion ?: "—")
                Text(
                    statusText(status, checking, link == LinkState.CONNECTED || hermes.isSignedIn),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!update.isInProgress && (status.updateAvailable || status.restartPending)) {
                    SettingsRow(
                        actionTitle(status),
                        icon = Icons.Default.Download,
                        onClick = if (repository.canUpdate) ({ confirming = true }) else null,
                    )
                    repository.updateBlocker()?.let {
                        Text(t(it), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PluginUpdateStatus(update, status, repository.updateStartedAt, onDismiss = repository::dismissOutcome, onCancel = repository::cancelUpdate)
                Row(Modifier.padding(4.dp)) {
                    TextButton(onClick = { scope.launch { checking = true; repository.status(force = true); checking = false } }) { Text(t("Check again")) }
                }
            }

            if (status.state is PluginStatus.State.Reported) {
                SettingsSection(t("What it does here")) {
                    PluginFeature.entries.forEach { feature ->
                        val served = feature.isServed(status)
                        SettingsRow(
                            t(feature.title),
                            trailing = {
                                Icon(
                                    if (served) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                                    if (served) t("Supported") else t("Update needed"),
                                    tint = if (served) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }

            SettingsSection(t("Do it manually")) {
                Row(
                    Modifier.fillMaxWidth().clickable { showsCommands = !showsCommands }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t("Run these on the dashboard's host when Conduit can't update it, such as on an older Hermes."),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(if (showsCommands) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (showsCommands) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (status.isInstall) {
                            SetupCommand(1, t("Install the notifier"), "hermes plugins install ${PluginStatus.REPOSITORY} --enable")
                        } else {
                            SetupCommand(1, t("Update the notifier"), "hermes plugins update conduit_push")
                        }
                        SetupCommand(2, t("Restart the gateway"), "hermes gateway restart")
                        Text(
                            t("Then restart the Hermes dashboard, so it serves the new plugin."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(t("Update the Conduit plugin?")) },
            text = {
                Text(t("Hermes updates the plugin, then restarts its gateway and dashboard once running chats finish. Chats and messaging channels reconnect by themselves."))
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; repository.startUpdate(install = status.isInstall) }) { Text(t("Update and Restart Hermes")) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun SetupCommand(step: Int, title: String, command: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$step. $title", style = MaterialTheme.typography.bodyMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) { Icon(Icons.Default.ContentCopy, t("Copy")) }
            }
        }
    }
}

private fun needsManualUpdate(status: PluginStatus, update: PluginUpdateState): Boolean =
    (update is PluginUpdateState.Failed || update is PluginUpdateState.Unsupported) && status.predatesOneTapUpdate

@Composable
private fun actionTitle(status: PluginStatus): String = when {
    status.isInstall -> t("Install or Update")
    status.restartPending && !status.updateAvailable -> t("Finish Update")
    else -> t("Update to %s", PluginStatus.RECOMMENDED_VERSION)
}

@Composable
private fun statusText(status: PluginStatus, checking: Boolean, connected: Boolean): String = when (status.state) {
    PluginStatus.State.Unknown -> when {
        checking -> t("Checking this dashboard's notifier…")
        connected -> t("Couldn't reach this dashboard's notifier. Open this page again to retry.")
        else -> t("Connect to this dashboard to check its notifier.")
    }
    PluginStatus.State.PredatesCapabilities ->
        t("Missing or out of date on this dashboard's host, so features like taking chats over from Hermes Desktop won't work.")
    is PluginStatus.State.Reported -> when {
        status.updateAvailable -> t("Version %s is available.", PluginStatus.RECOMMENDED_VERSION)
        status.restartPending -> t("The new plugin is on the host. Hermes needs a restart to use it.")
        else -> t("Up to date on this dashboard's host")
    }
}

/** One-line status for other surfaces (iOS `NotifierPluginSummary`). */
@Composable
fun pluginSummary(status: PluginStatus, update: PluginUpdateState): String {
    if (update.isInProgress) return t("Updating…")
    return when (status.state) {
        PluginStatus.State.Unknown -> t("Not checked yet")
        PluginStatus.State.PredatesCapabilities -> t("Not installed or out of date")
        is PluginStatus.State.Reported -> {
            val version = status.runningVersion ?: status.version ?: "?"
            when {
                status.updateAvailable -> t("%s · Update available", version)
                status.restartPending -> t("%s · Restart to finish updating", version)
                else -> t("%s · Up to date", version)
            }
        }
    }
}

/** Where the plugin update stands (iOS `NotifierPluginUpdateStatusView`). */
@Composable
fun PluginUpdateStatus(update: PluginUpdateState, status: PluginStatus, startedAt: Long?, onDismiss: () -> Unit, onCancel: () -> Unit) {
    class UpdateRow(val title: String, val detail: String?, val icon: ImageVector?, val tint: Color)

    val row: UpdateRow = when {
        (update is PluginUpdateState.Failed || update is PluginUpdateState.Unsupported) && status.predatesOneTapUpdate -> {
            val why = status.version?.let {
                t("This dashboard's plugin (version %s) is older than one-tap updates. Run the steps under Do it manually on the host once. After that, Conduit updates it for you.", it)
            } ?: t("This dashboard's plugin is missing or older than one-tap updates. Run the steps under Do it manually on the host once. After that, Conduit updates it for you.")
            val hermesSaid = (update as? PluginUpdateState.Failed)?.detail?.takeIf { it.isNotEmpty() }?.let { t("Hermes said: %s", it) }
            UpdateRow(t("Update the plugin on the host this once"), listOfNotNull(why, hermesSaid).joinToString("\n\n"), Icons.Default.Terminal, Orange)
        }
        update == PluginUpdateState.Idle -> return
        update is PluginUpdateState.Updating -> UpdateRow(stepTitle(update.step), null, null, Color.Unspecified)
        update is PluginUpdateState.Updated -> UpdateRow(
            update.version?.let { t("Conduit plugin %s is ready", it) } ?: t("The Conduit plugin is up to date"),
            null, Icons.Default.CheckCircle, Green,
        )
        update is PluginUpdateState.NeedsDashboardRestart -> UpdateRow(
            t("Restart the Hermes dashboard to finish"),
            update.detail ?: t("The new plugin is on the host, but this dashboard can't restart itself. Restart it on the host once."),
            Icons.Default.Schedule, Orange,
        )
        update is PluginUpdateState.Failed -> UpdateRow(t("Couldn't update the Conduit plugin"), update.detail, Icons.Default.Warning, MaterialTheme.colorScheme.error)
        else -> UpdateRow(
            t("This Hermes can't update plugins from Conduit"),
            t("Update Hermes, or run the commands under Do it manually."),
            Icons.Default.Error, Orange,
        )
    }
    Column {
        StatusRow(row.title, row.detail, false, row.icon, row.tint, onDismiss) {
            if (update is PluginUpdateState.Updating) UpdateProgress(update.step, startedAt)
        }
        if (update.isInProgress) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) { Text(t("Cancel")) }
        }
    }
}

@Composable
private fun UpdateProgress(step: PluginUpdateStep, startedAt: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { step.number.toFloat() / PluginUpdateStep.COUNT },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        val elapsed = PluginUpdateRules.elapsed(((now - (startedAt ?: now)) / 1000))
        Text(
            t("Step %s of %s · %s", step.number.toString(), PluginUpdateStep.COUNT.toString(), elapsed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stepTitle(step: PluginUpdateStep): String = when (step) {
    PluginUpdateStep.Downloading -> t("Downloading the plugin…")
    PluginUpdateStep.Installing -> t("Installing the plugin…")
    is PluginUpdateStep.RestartingGateway ->
        if (step.stage == GatewayRestartStage.DRAINING) t("Letting running tasks finish…") else t("Restarting the gateway…")
    is PluginUpdateStep.WaitingForChats -> t("Waiting for %d running chats to finish…", step.count)
    PluginUpdateStep.RestartingDashboard -> t("Restarting the dashboard…")
}
