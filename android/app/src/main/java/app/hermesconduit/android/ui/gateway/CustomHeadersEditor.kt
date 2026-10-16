package app.hermesconduit.android.ui.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.HeaderRow
import app.hermesconduit.android.model.HeaderRules

/** Editor for the extra proxy headers saved for one server (iOS `CustomHeadersEditorSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomHeadersEditor(serverUrl: String, headers: Map<String, String>, onSave: (List<HeaderRow>) -> Unit, onDismiss: () -> Unit) {
    var nextId by remember { mutableStateOf(headers.size.toLong()) }
    val rows = remember { mutableStateListOf<HeaderRow>().apply { headers.entries.forEachIndexed { i, (k, v) -> add(HeaderRow(i.toLong(), k, v)) } } }
    val revealed = remember { mutableStateListOf<Long>() }
    val origin = HeaderRules.origin(serverUrl)
    val duplicates = HeaderRules.duplicateNames(rows)
    val canSave = origin != null && rows.all { HeaderRules.issue(it) == null } && duplicates.isEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(t("Extra headers")) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Cancel")) } },
                    actions = {
                        TextButton(onClick = { onSave(rows.filter { !it.isBlank }) }, enabled = canSave) { Text(t("Save")) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        t("Sent with every request to this server, for reverse proxies such as Pangolin, Traefik, or nginx that check a header. Values stay encrypted on this device."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (origin != null) {
                        Text(origin, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            t("Extra headers are only sent over HTTPS. Enter an https:// server address first."),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningOrange,
                        )
                    }
                }
                items(rows.toList(), key = { it.id }) { row ->
                    val index = rows.indexOfFirst { it.id == row.id }
                    val problem = HeaderRules.issue(row)?.message?.let { t(it) }
                        ?: if (row.trimmedName.lowercase() in duplicates) t("This header is already in the list.") else null
                    val shown = row.id in revealed
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                row.name,
                                { value -> if (index >= 0) rows[index] = row.copy(name = value) },
                                label = { Text(t("Header name")) },
                                singleLine = true,
                                isError = problem != null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                row.value,
                                { value -> if (index >= 0) rows[index] = row.copy(value = value) },
                                label = { Text(t("Value")) },
                                singleLine = true,
                                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { if (shown) revealed.remove(row.id) else revealed.add(row.id) }) {
                                        Icon(
                                            if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (shown) t("Hide value") else t("Show value"),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { rows.removeAll { it.id == row.id } }) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    Text(t("Remove header"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = { rows.add(HeaderRow(nextId++, "", "")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Text(t("Add header"), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
