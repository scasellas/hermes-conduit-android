package app.hermesconduit.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.SavedConnection
import app.hermesconduit.android.hermes
import app.hermesconduit.android.ui.appViewModel
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection

/** iOS SavedDashboardsView: every Hermes this device signs in to. */
@Composable
fun SavedDashboardsPage(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val app = appViewModel()
    val dashboards by context.hermes.dashboards.collectAsStateWithLifecycle()
    val active by context.hermes.connection.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedConnection?>(null) }
    ScreenScaffold(t("Saved Dashboards"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(null, footer = t("Each dashboard keeps its own sign-in, profiles, pins and notifications.")) {
                dashboards.forEachIndexed { index, d ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(d.displayName) },
                        supportingContent = { Text(listOfNotNull(d.baseUrl, if (d.authMode == "oauth") t("Browser sign-in") else d.username.ifEmpty { null }).joinToString(" · ")) },
                        leadingContent = { if (d.id == active?.id) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { IconButton(onClick = { renaming = d }) { Icon(Icons.Default.Edit, t("Rename")) } },
                        modifier = Modifier.clickable { app.switchDashboard(d.id); navigate("home") },
                    )
                }
            }
            SettingsSection(null) {
                SettingsRow(t("Add a dashboard"), icon = Icons.Default.Add, onClick = { app.addDashboard() })
            }
        }
    }
    renaming?.let { d ->
        var name by remember(d.id) { mutableStateOf(d.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("Rename dashboard")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(d.baseUrl.substringAfter("://")) }) },
            confirmButton = { TextButton(onClick = { context.hermes.renameDashboard(d.id, name.trim()); renaming = null }) { Text(t("Save")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("Cancel")) } },
        )
    }
}
