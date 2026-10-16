package app.hermesconduit.android.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.WorkspaceEntry
import app.hermesconduit.android.model.WorkspaceParser
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.artifacts.HostFile
import app.hermesconduit.android.ui.artifacts.HostFiles
import app.hermesconduit.android.ui.artifacts.formatBytes
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.gateway.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route `workspace` (last folder, or one typed in) and `workspace/<folder>` (that folder on the host). */
@Composable
fun WorkspaceScreen(onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") navigate: (String) -> Unit) {
    val rootArg = LocalRouteArg.current
    val viewModel: WorkspaceViewModel = viewModel()
    LaunchedEffect(rootArg) { viewModel.start(rootArg) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val selected = state.selected
    if (selected != null) {
        BackHandler { viewModel.closePreview() }
        FilePreviewScreen(viewModel, selected, onBack = viewModel::closePreview)
        return
    }

    val title = state.root.trimEnd('/').substringAfterLast('/').ifEmpty { t("Workspace") }
    ScreenScaffold(
        title,
        onBack,
        actions = {
            if (state.loadingPath != null && state.loadingPath == state.root) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = viewModel::refresh, enabled = state.root.isNotEmpty()) { Icon(Icons.Default.Refresh, t("Refresh workspace")) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            var draft by rememberSaveable(state.root) { mutableStateOf(state.root) }
            OutlinedTextField(
                draft, { draft = it },
                label = { Text(t("Folder on the host")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.open(draft) }),
                trailingIcon = {
                    IconButton(onClick = { viewModel.open(draft) }, enabled = draft.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, t("Open")) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ErrorText(state.error)
            val rows = WorkspaceParser.flatten(state.root, state.entries, state.expanded)
            when {
                state.root.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("Workspace unavailable"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("Enter the folder on your Hermes host to browse, such as a chat's working directory."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                rows.isEmpty() && state.loadingPath == state.root -> LoadingBox()
                rows.isEmpty() && state.error == null -> Text(
                    t("This folder is empty."),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(rows, key = { it.first.path }) { (entry, depth) ->
                        EntryRow(entry, depth, expanded = entry.path in state.expanded, loading = state.loadingPath == entry.path) {
                            if (entry.isDirectory) viewModel.toggle(entry) else viewModel.preview(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: WorkspaceEntry, depth: Int, expanded: Boolean, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp + 18.dp * depth, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else if (entry.isDirectory) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilePreviewScreen(viewModel: WorkspaceViewModel, entry: WorkspaceEntry, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<HostFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } }
            } catch (e: Exception) {
                viewModel.reportError(e.userMessage())
            }
        }
    }

    ScreenScaffold(
        entry.name,
        onBack,
        actions = {
            if (state.downloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = {
                scope.launch {
                    val file = viewModel.download(entry) ?: return@launch
                    pendingSave = file
                    saver.launch(file.name)
                }
            }, enabled = !state.downloading) { Icon(Icons.Default.Save, t("Save to Files")) }
            IconButton(onClick = {
                scope.launch {
                    val file = viewModel.download(entry) ?: return@launch
                    HostFiles.share(context, HostFiles.stage(context, file), file.mimeType)
                }
            }, enabled = !state.downloading) { Icon(Icons.Default.Share, t("Share")) }
        },
    ) { padding ->
        val preview = state.preview
        when {
            state.previewLoading -> LoadingBox(Modifier.padding(padding))
            state.previewError != null && preview == null -> Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("Couldn’t preview file"), style = MaterialTheme.typography.titleMedium)
                Text(state.previewError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = { viewModel.preview(entry) }, modifier = Modifier.padding(top = 12.dp)) { Text(t("Try Again")) }
            }
            preview != null -> SettingsColumn(padding) {
                SettingsSection(t("File")) {
                    SettingsRow(t("Type"), value = preview.language)
                    SettingsRow(t("Size"), value = formatBytes(preview.byteSize))
                    if (preview.truncated) {
                        Text(
                            t("Preview is truncated; save the file for its full contents."),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ErrorText(state.previewError)
                if (preview.binary) {
                    Text(t("Binary file"), style = MaterialTheme.typography.titleMedium)
                    Text(t("Use Save to Files to download this file."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                preview.text.ifEmpty { t("This file is empty.") },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()))
        }
    }
}
