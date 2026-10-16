package app.hermesconduit.android.ui.agentsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection

@Composable
fun AgentSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val mode by context.appSettings.string(AgentSettingsMode.KEY, "ask").collectAsStateWithLifecycle()
    val modes = listOf(
        "off" to (t("Off") to t("Hermes can explain Conduit's settings but can't change them.")),
        "ask" to (t("Ask me every time") to t("When you ask Hermes to change a setting, Conduit shows the change and waits for you to approve it.")),
        "small" to (t("Small changes without asking") to t("Changes to how voice sounds apply right away with an Undo. Anything that costs money, shares more with a voice model or keeps the microphone on still asks.")),
    )
    ScreenScaffold(t("Let Hermes change settings"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(null) {
                modes.forEach { (id, text) ->
                    Row(Modifier.fillMaxWidth().clickable { context.appSettings.set(AgentSettingsMode.KEY, id); AgentSettingsSync.sync(context) }.padding(12.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = mode == id, onClick = { context.appSettings.set(AgentSettingsMode.KEY, id); AgentSettingsSync.sync(context) })
                        Column {
                            Text(text.first, style = MaterialTheme.typography.titleSmall)
                            Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Review dialog for changes Hermes proposed, and the applied notice with Undo. */
@Composable
fun AgentSettingsHost() {
    val context = LocalContext.current
    val review by AgentSettingsSync.review.collectAsStateWithLifecycle()
    val applied by AgentSettingsSync.applied.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AgentSettingsSync.sync(context) }
    if (review.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(t("Hermes wants to change settings")) },
            text = {
                Column {
                    review.forEach { item ->
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text("${item.oldValue} → ${item.newValue}", style = MaterialTheme.typography.bodyMedium)
                        if (item.request.reason.isNotEmpty()) Text(item.request.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { AgentSettingsSync.resolve(context, review, apply = true) }) { Text(t("Apply")) } },
            dismissButton = { TextButton(onClick = { AgentSettingsSync.resolve(context, review, apply = false) }) { Text(t("Not now")) } },
        )
    }
    applied?.let { notice ->
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                action = { TextButton(onClick = { AgentSettingsSync.undo(context, notice) }) { Text(t("Undo")) } },
                dismissAction = { TextButton(onClick = AgentSettingsSync::dismissApplied) { Text(t("OK")) } },
            ) { Text(notice.message) }
        }
    }
}
