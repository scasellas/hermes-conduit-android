package app.hermesconduit.android.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Settings > Watch (iOS Voice settings > Apple Watch): whether Conduit is on
 * a Wear OS watch, whether the watch may take an approval's answer by voice,
 * a check of the host's Watch tool grants, and the call log to share for a
 * support question.
 */
@Composable
fun WatchSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.appSettings
    val log = remember { WatchPhoneCallLog.get(context) }
    val scope = rememberCoroutineScope()
    val voiceApprovals by settings.bool(WatchSettings.VOICE_APPROVALS, false).collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var logBytes by remember { mutableLongStateOf(log.sizeBytes) }
    var confirmingClear by remember { mutableStateOf(false) }
    var grantCheck by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = watchStatus(context)
        WatchBridge.get(context).publishContext()
    }

    ScreenScaffold(t("Watch"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(
                t("Watch"),
                footer = t("Open Conduit on your watch to talk to Hermes: the call starts right away, or with a tap if you turn off Call on open in the watch's settings. Each turn runs through Conduit on this phone, and calls are saved to your chats."),
            ) {
                SettingsRow(status ?: t("Checking for a watch…"), icon = Icons.Default.Watch)
            }

            SettingsSection(
                t("Approve from the Watch"),
                footer = if (voiceApprovals) t("An approval request shows on the watch with Approve once and Deny, and during a call you can also answer it by saying “approve” or “deny”. Voice only ever approves once.")
                else t("A job's approval request shows on the Watch with Approve once and Deny."),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !voiceApprovals, onClick = { setVoiceApprovals(context, false) }, label = { Text(t("Tap")) })
                    FilterChip(selected = voiceApprovals, onClick = { setVoiceApprovals(context, true) }, label = { Text(t("Tap or voice")) })
                }
            }

            SettingsSection(
                t("Watch tools on your Hermes host"),
                footer = t("Checks that your Hermes host can grant a watch call web search and memory through the push relay. The test grant is revoked right away."),
            ) {
                SettingsRow(
                    t("Check"),
                    icon = Icons.Default.Build,
                    value = grantCheck,
                    onClick = if (checking) null else ({
                        checking = true
                        grantCheck = null
                        scope.launch {
                            grantCheck = checkGrant(context)
                            checking = false
                        }
                    }),
                    trailing = if (checking) ({ CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) }) else null,
                )
            }

            SettingsSection(t("Call log"), footer = t("Timings and connection events from Watch calls, without what was said. Share it when reporting a problem.")) {
                SettingsRow(t("Share call log"), icon = Icons.Default.Share, value = sizeLabel(logBytes), onClick = {
                    runCatching { context.startActivity(log.shareIntent()) }
                })
                SettingsRow(t("Clear Log"), onClick = { confirmingClear = true }, trailing = {})
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(t("Clear the Watch call log?")) },
            confirmButton = {
                TextButton(onClick = {
                    log.clear()
                    logBytes = log.sizeBytes
                    confirmingClear = false
                }) { Text(t("Clear Log"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(t("Cancel")) } },
        )
    }
}

private fun setVoiceApprovals(context: android.content.Context, on: Boolean) {
    context.appSettings.set(WatchSettings.VOICE_APPROVALS, on)
    context.hermes.scope.launch { WatchBridge.get(context).publishContext() }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> L10n.tr("Empty")
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
}

/** iOS statusLine: paired, installed, or what to do. */
private suspend fun watchStatus(context: android.content.Context): String = try {
    val nodes = Wearable.getNodeClient(context).connectedNodes.await()
    if (nodes.isEmpty()) L10n.tr("No Wear OS watch is connected to this phone.")
    else {
        val installed = Wearable.getCapabilityClient(context).getCapability(WearWire.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await().nodes
        if (installed.isNotEmpty()) L10n.tr("Conduit is on your watch.") else L10n.tr("Install Conduit on your watch from the Play Store on the watch.")
    }
} catch (_: Exception) {
    L10n.tr("No Wear OS watch is connected to this phone.")
}

/** Asks for a short grant and revokes it at once: shows whether the host's watch-tools routes work. */
private suspend fun checkGrant(context: android.content.Context): String {
    val hermes = context.hermes
    if (!hermes.isSignedIn) return L10n.tr("Connect to Hermes to check.")
    val client = WatchToolGrantClient(hermes)
    val profile = hermes.profile.value
    return try {
        val grant = client.grant(listOf("web_search", "recall_memory"), profile)
        client.revoke(grant.grantId, profile)
        L10n.tr("Ready")
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: L10n.tr("Your Hermes host doesn't offer this. Update the Conduit notifier plugin.")
    }
}
