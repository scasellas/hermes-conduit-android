package app.hermesconduit.android.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.GatewayRestartStage
import app.hermesconduit.android.model.GatewayRestartState

internal val SuccessGreen = Color(0xFF34C759)
internal val WarningOrange = Color(0xFFFF9500)

/** Asks before Restart Gateway runs. */
@Composable
fun GatewayRestartConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Restart the gateway?")) },
        text = {
            Text(t("Hermes restarts its gateway process. Messaging channels, running sessions and any profiles that share this gateway reconnect once it's back."))
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(t("Restart Gateway"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}

/** Where a restart stands; nothing until one starts (iOS `GatewayRestartStatusView`). */
@Composable
fun GatewayRestartStatusRow(state: GatewayRestartState, onDismiss: () -> Unit) {
    val row = restartRow(state) ?: return
    StatusRow(row.title, row.detail, row.logDetail, row.icon, row.tint, onDismiss)
}

@Composable
internal fun StatusRow(
    title: String,
    detail: String?,
    monospaceDetail: Boolean,
    icon: ImageVector?,
    tint: Color,
    onDismiss: (() -> Unit)?,
    extra: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        else CircularProgressIndicator(Modifier.size(18.dp).padding(top = 2.dp), strokeWidth = 2.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            extra()
            if (!detail.isNullOrEmpty()) {
                SelectionContainer {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (monospaceDetail) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (icon != null && onDismiss != null) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Dismiss")) }
        }
    }
}

private class RestartRow(val title: String, val detail: String?, val logDetail: Boolean, val icon: ImageVector?, val tint: Color)

@Composable
private fun restartRow(state: GatewayRestartState): RestartRow? = when (state) {
    GatewayRestartState.Idle -> null
    GatewayRestartState.Requesting -> RestartRow(t("Asking Hermes to restart the gateway…"), null, false, null, Color.Unspecified)
    is GatewayRestartState.Restarting -> RestartRow(stageTitle(state.stage), state.detail, true, null, Color.Unspecified)
    is GatewayRestartState.Restarted -> RestartRow(
        t("Gateway restarted"),
        state.sharedProfiles.takeIf { it.isNotEmpty() }?.let { t("Profiles on this gateway: %s", it.joinToString(", ")) },
        false, Icons.Default.CheckCircle, SuccessGreen,
    )
    is GatewayRestartState.Failed -> RestartRow(t("Couldn't restart the gateway"), state.detail, true, Icons.Default.Warning, MaterialTheme.colorScheme.error)
    GatewayRestartState.FailedToStart -> RestartRow(
        t("The gateway didn't start"), t("Run hermes gateway status on the host to see why."), false, Icons.Default.Warning, MaterialTheme.colorScheme.error,
    )
    GatewayRestartState.NotBackYet -> RestartRow(
        t("The gateway isn't back yet"),
        t("It may still be finishing running tasks. Run hermes gateway status on the host to check."),
        false, Icons.Default.Schedule, WarningOrange,
    )
    GatewayRestartState.Unsupported -> RestartRow(
        t("This Hermes can't restart its gateway from Conduit"),
        t("Update Hermes, or run hermes gateway restart on the host."),
        false, Icons.Default.Error, WarningOrange,
    )
}

@Composable
internal fun stageTitle(stage: GatewayRestartStage): String = when (stage) {
    GatewayRestartStage.DRAINING -> t("Letting running tasks finish…")
    GatewayRestartStage.STOPPING -> t("Stopping the gateway…")
    GatewayRestartStage.STARTING -> t("Starting the gateway…")
}
