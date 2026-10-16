package app.hermesconduit.android.ui.projects

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.ProjectLane
import app.hermesconduit.android.model.ProjectSummary
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.appViewModel
import app.hermesconduit.android.ui.components.EmptyState
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class FolderEntry(val name: String, val path: String, val isDirectory: Boolean)

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val _projects = MutableStateFlow<List<ProjectSummary>?>(null)
    val projects: StateFlow<List<ProjectSummary>?> = _projects
    private val _lanes = MutableStateFlow<List<ProjectLane>?>(null)
    val lanes: StateFlow<List<ProjectLane>?> = _lanes
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun refresh() {
        viewModelScope.launch {
            runCatching { hermes.rpc("projects.tree", buildJsonObject { put("preview_limit", 3) }) }
                .onSuccess { _projects.value = MessageNormalizer.projects(it); _error.value = null }
                .onFailure {
                    _projects.value = _projects.value ?: emptyList()
                    _error.value = if (it.message?.contains("not found", true) == true || it.message?.contains("unknown method", true) == true) {
                        app.hermesconduit.android.core.L10n.tr("This Hermes doesn't support projects yet.")
                    } else it.message
                }
        }
    }

    fun loadLanes(projectId: String) {
        _lanes.value = null
        viewModelScope.launch {
            runCatching { hermes.rpc("projects.project_sessions", buildJsonObject { put("project_id", projectId) }) }
                .onSuccess { _lanes.value = MessageNormalizer.projectLanes(it) }
                .onFailure { _lanes.value = emptyList(); _error.value = it.message }
        }
    }

    fun create(name: String, folders: List<String>) = run {
        hermes.rpc("projects.create", buildJsonObject {
            put("name", name)
            put("folders", buildJsonArray { folders.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("primary_path", folders.firstOrNull().orEmpty())
            put("use", true)
        })
    }

    fun rename(id: String, name: String) = run { hermes.rpc("projects.update", buildJsonObject { put("id", id); put("name", name) }) }

    fun delete(id: String) = run { hermes.rpc("projects.delete", buildJsonObject { put("id", id) }) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _error.value = it.message }
            refresh()
        }
    }

    suspend fun listFolder(path: String): List<FolderEntry> {
        val result = hermes.rest { it.getJson("api/fs/list", mapOf("path" to path, "profile" to hermes.profile.value.takeIf { p -> p != "default" })) }
        val rows = result["entries"].arr ?: result["items"].arr ?: result["_array"].arr ?: return emptyList()
        return rows.mapNotNull { e ->
            val o = e.obj ?: return@mapNotNull null
            val name = o["name"].str ?: return@mapNotNull null
            FolderEntry(name, o["path"].str ?: "$path/$name", o["is_dir"].bool ?: o["isDirectory"].bool ?: (o["type"].str == "dir" || o["type"].str == "directory"))
        }
    }
}

@Composable
fun ProjectsScreen(onBack: () -> Unit, navigate: (String) -> Unit, model: ProjectsViewModel = viewModel()) {
    val projects by model.projects.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refresh() }
    ScreenScaffold(
        t("Projects"),
        onBack,
        floating = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(t("New Project")) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ErrorText(error)
            val list = projects
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(t("No projects yet"))
                else -> LazyColumn {
                    items(list, key = { it.id }) { project ->
                        ListItem(
                            leadingContent = { Icon(if (project.isHome) Icons.Default.Home else Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(project.title) },
                            supportingContent = { Text(listOfNotNull(project.primaryPath, t("%d chats", project.sessionCount)).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { navigate("projects.detail/${Uri.encode(project.id)}") },
                        )
                    }
                }
            }
        }
    }
    if (creating) CreateProjectDialog(model, onDismiss = { creating = false })
}

@Composable
fun ProjectDetailScreen(onBack: () -> Unit, navigate: (String) -> Unit, model: ProjectsViewModel = viewModel()) {
    val projectId = LocalRouteArg.current ?: return
    val projects by model.projects.collectAsStateWithLifecycle()
    val lanes by model.lanes.collectAsStateWithLifecycle()
    val app = appViewModel()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        model.refresh()
        model.loadLanes(projectId)
    }
    val project = projects?.firstOrNull { it.id == projectId }
    ScreenScaffold(
        project?.title ?: t("Project"),
        onBack,
        actions = {
            if (project != null && !project.isAuto && !project.isHome) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, t("Rename")) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, t("Delete")) }
            }
        },
        floating = {
            project?.primaryPath?.let { path ->
                ExtendedFloatingActionButton(
                    onClick = { app.newChatIn(path); navigate("home") },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(t("New Chat")) },
                )
            }
        },
    ) { padding ->
        val list = lanes
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(t("No chats in this project yet"), Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding)) {
                list.forEach { lane ->
                    item(key = "lane-" + lane.id) {
                        Text(lane.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    }
                    items(lane.sessions, key = { lane.id + it.id }) { session ->
                        ListItem(
                            headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = session.model?.let { { Text(it) } },
                            modifier = Modifier.clickable { app.openSession(session); navigate("home") },
                        )
                    }
                }
            }
        }
    }
    if (renaming && project != null) {
        var name by remember { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(t("Rename Project")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.rename(project.id, name.trim()); renaming = false }) { Text(t("Save")) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(t("Cancel")) } },
        )
    }
    if (deleting && project != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(t("Delete Project?")) },
            text = { Text(t("The project is removed from Hermes. Its folders, files and conversations stay where they are.")) },
            confirmButton = { TextButton(onClick = { model.delete(project.id); deleting = false; onBack() }) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun CreateProjectDialog(model: ProjectsViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val folders = remember { mutableStateListOf<String>() }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("New Project")) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(t("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                folders.forEach { f ->
                    Row { Text(f, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); TextButton(onClick = { folders.remove(f) }) { Text(t("Remove")) } }
                }
                TextButton(onClick = { picking = true }) { Text(t("Add Folder")) }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.create(name.trim(), folders.toList()); onDismiss() }) { Text(t("Create")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
    if (picking) FolderPickerDialog(model, onPick = { folders += it; picking = false }, onDismiss = { picking = false })
}

/** Browses the Hermes host's folders through `/api/fs/list`. */
@Composable
fun FolderPickerDialog(model: ProjectsViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("~") }
    var entries by remember { mutableStateOf<List<FolderEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        entries = null
        runCatching { model.listFolder(path) }.onSuccess { entries = it.filter { e -> e.isDirectory }; error = null }.onFailure { entries = emptyList(); error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row {
                if (path != "~" && path != "/") IconButton(onClick = { path = path.substringBeforeLast('/').ifEmpty { "/" } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back")) }
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                ErrorText(error)
                val list = entries
                if (list == null) LoadingBox(Modifier.heightIn(max = 120.dp)) else LazyColumn {
                    items(list, key = { it.path }) { e ->
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            headlineContent = { Text(e.name) },
                            modifier = Modifier.clickable { path = e.path },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(t("Use This Folder")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}
