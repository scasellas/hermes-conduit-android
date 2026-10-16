package app.hermesconduit.android.ui.device

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle

/** A setting with a fixed set of choices, shown as a menu. */
@Composable
fun <T> ChoiceRow(title: String, value: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title, value = choices.firstOrNull { it.first == value }?.second, onClick = if (enabled) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (choice, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onSelect(choice)
                })
            }
        }
    }
}

@Composable
fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
fun WarningCaption(text: String) = Caption(text, MaterialTheme.colorScheme.tertiary)

@Composable
fun ErrorCaption(text: String) = Caption(text, MaterialTheme.colorScheme.error)

fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** The consent sheet: what the user agrees to before anything leaves the phone. */
@Composable
fun ConsentDialog(title: String, items: List<Pair<ImageVector, String>>, onAccept: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { (icon, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(t("Continue")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(t("Cancel")) } },
    )
}

/** "Turn off and delete from the host" / "keep data on the host" / Cancel. */
@Composable
fun TurnOffDialog(title: String, message: String, onDelete: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onDelete) { Text(t("Turn off and delete from the host"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onKeep) { Text(t("Turn off, keep data on the host")) }
                TextButton(onClick = onCancel) { Text(t("Cancel")) }
            }
        },
    )
}

/** Asks before a live voice mode may read something (its company gets what it reads). */
@Composable
fun VoiceAllowDialog(title: String, mode: LiveVoiceMode, onAllow: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(t("On a call, what it reads goes to %s under its terms. You can turn this off at any time.", mode.company)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(t("Allow")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(t("Cancel")) } },
    )
}

/** Live voice calls: one switch per mode, the explanation, and the last week's reads. */
@Composable
fun VoiceModesSection(
    allowed: (LiveVoiceMode) -> Boolean,
    onToggle: (LiveVoiceMode, Boolean) -> Unit,
    explanation: String,
    reads: VoiceReadLog,
    warning: String? = null,
) {
    SettingsSection(t("Live voice calls")) {
        LiveVoiceMode.entries.forEach { mode ->
            SettingsToggle("${mode.displayName} (${mode.company})", allowed(mode), { onToggle(mode, it) })
        }
        warning?.let { WarningCaption(it) }
        Caption(explanation)
        if (reads.entries.isNotEmpty()) {
            Text(t("Read by voice in the last 7 days"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            reads.entries.takeLast(20).reversed().forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text("${entry.modeName}: ${entry.action}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(relativeTime(entry.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The page's main buttons. */
@Composable
fun PrimaryActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun SecondaryActionButton(text: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}
