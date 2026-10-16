package app.hermesconduit.android.ui.kanban

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.KanbanAssigneeSelection
import app.hermesconduit.android.model.KanbanComposerDraft
import app.hermesconduit.android.model.KanbanComposerValidator
import app.hermesconduit.android.model.KanbanDraftValidationException
import app.hermesconduit.android.model.KanbanModelProviderOption
import app.hermesconduit.android.model.KanbanShortId
import app.hermesconduit.android.model.KanbanStatusPresentation
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanWorkspaceKind
import app.hermesconduit.android.model.TaskModelOverride
import app.hermesconduit.android.ui.LocalRouteArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** New task form: Basics / Assignment / Execution / Dependencies / Lands in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanComposerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val state by store.state.collectAsStateWithLifecycle()
    val initialStatus = LocalRouteArg.current ?: "todo"
    val presentation = KanbanStatusPresentation.forStatus(initialStatus)
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(KanbanComposerDraft()) }
    var seeded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var didCreate by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showModel by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var showParent by remember { mutableStateOf(false) }

    val resolvedDefault = state.orchestration?.resolvedDefaultAssignee?.trim().orEmpty().ifEmpty { "default" }
    val metadata = state.selectedBoardMetadata
    val boardDefaultKind = KanbanWorkspaceKind.initialKind(metadata?.defaultWorkspaceKind)
    val boardDefaultDir = metadata?.defaultWorkdir?.trim()?.takeIf { it.isNotEmpty() }

    // Workspace kind starts from the board's default (fallback scratch), once.
    LaunchedEffect(metadata?.defaultWorkspaceKind) {
        if (!seeded && metadata != null) {
            draft = draft.copy(workspaceKind = KanbanWorkspaceKind.initialKind(metadata.defaultWorkspaceKind))
            seeded = true
        }
    }

    fun create() {
        if (isSaving || didCreate) return
        val request = try {
            KanbanComposerValidator.makeRequest(draft, resolvedDefault)
        } catch (e: KanbanDraftValidationException) {
            errorMessage = e.message
            return
        }
        isSaving = true
        errorMessage = null
        scope.launch {
            try {
                store.createTask(request, initialStatus, runtime.includeArchived.value)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = kanbanErrorMessage(e)
                if (e is KanbanServiceException.TaskCreatedButMoveFailed) didCreate = true
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (didCreate) t("Task created") else t("New task")) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) { Icon(Icons.Default.Close, if (didCreate) t("Close") else t("Cancel")) }
                },
                actions = {
                    TextButton(onClick = { if (didCreate) onBack() else create() }, enabled = (draft.trimmedTitle.isNotEmpty() || didCreate) && !isSaving) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (didCreate) t("Done") else t("Create"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanSection(t("Basics")) {
                OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text(t("Title")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(draft.body, { draft = draft.copy(body = it) }, label = { Text(t("Task description")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                val priorities = listOf(0 to t("Normal"), 1 to t("High"), 2 to t("Urgent"), 3 to t("Critical"))
                KanbanPickerRow(
                    label = t("Priority"),
                    value = priorities.firstOrNull { it.first == draft.priority }?.second ?: draft.priority.toString(),
                    options = priorities,
                    onSelect = { draft = draft.copy(priority = it) },
                    isSelected = { it == draft.priority },
                )
            }
            KanbanSection(
                t("Assignment"),
                footer = t("Default uses Hermes' resolved default assignee (%s). Parked leaves the task unassigned so no worker claims it.", resolvedDefault),
            ) {
                val options = listOf<Pair<KanbanAssigneeSelection, String>>(KanbanAssigneeSelection.InheritDefault to t("Default (%s)", resolvedDefault)) +
                    state.profiles.filter { it.name != resolvedDefault }.map { KanbanAssigneeSelection.Profile(it.name) to it.name } +
                    listOf(KanbanAssigneeSelection.Parked to t("Parked (won't run)"))
                KanbanPickerRow(
                    label = t("Assigned to"),
                    value = options.firstOrNull { it.first == draft.assignee }?.second ?: "",
                    options = options,
                    onSelect = { draft = draft.copy(assignee = it) },
                    isSelected = { it == draft.assignee },
                )
            }
            KanbanSection(
                t("Execution"),
                footer = if (boardDefaultDir != null && !draft.workspaceKind.allowsPathOverride) t("The board's project directory (%s) applies to this task.", boardDefaultDir) else null,
            ) {
                KanbanPickerRow(
                    label = t("Workspace"),
                    value = draft.workspaceKind.displayName,
                    options = KanbanWorkspaceKind.entries.map { it to it.displayName + if (it == boardDefaultKind) t(" · board default") else "" },
                    onSelect = { draft = draft.copy(workspaceKind = it, workspacePath = if (it.allowsPathOverride) draft.workspacePath else "") },
                    isSelected = { it == draft.workspaceKind },
                )
                if (draft.workspaceKind.allowsPathOverride) {
                    OutlinedTextField(
                        draft.workspacePath, { draft = draft.copy(workspacePath = it) },
                        placeholder = { Text(if (boardDefaultDir != null) t("Leave empty to inherit %s", boardDefaultDir) else t("Optional path override")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (boardDefaultDir != null) t("Leave empty to inherit the board's project directory: %s", boardDefaultDir)
                        else t("Leave empty to inherit the board's project directory."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NavRow(t("Model"), draft.modelOverride.label(t("Inherit from profile")), muted = draft.modelOverride.isInherited) { showModel = true }
                NavRow(t("Skills"), if (draft.skills.isEmpty()) t("None") else t("%d selected", draft.skills.size), muted = draft.skills.isEmpty()) { showSkills = true }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Goal Mode"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(draft.goalMode, { draft = draft.copy(goalMode = it) })
                }
                if (draft.goalMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Max turns: %s", draft.goalMaxTurns?.toString() ?: t("Unlimited")), modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val current = draft.goalMaxTurns ?: return@IconButton
                            draft = draft.copy(goalMaxTurns = if (current <= 5) null else current - 5)
                        }) { Icon(Icons.Default.KeyboardArrowDown, t("Decrease")) }
                        IconButton(onClick = { draft = draft.copy(goalMaxTurns = minOf(10_000, (draft.goalMaxTurns ?: 0) + 5)) }) {
                            Icon(Icons.Default.KeyboardArrowUp, t("Increase"))
                        }
                    }
                    Text(
                        t("The worker loops until a judge agrees the goal is met. Leave unlimited only with caution."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            KanbanSection(t("Dependencies"), footer = t("The new task stays blocked until its parent completes.")) {
                if (draft.parents.isEmpty()) {
                    NavRow(t("Depends on"), t("None"), muted = true) { showParent = true }
                } else {
                    draft.parents.forEach { parentId ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.task(parentId)?.title?.takeIf { it.isNotEmpty() } ?: KanbanShortId.of(parentId))
                                Text(KanbanShortId.of(parentId), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { draft = draft.copy(parents = draft.parents - parentId) }) {
                                Icon(Icons.Default.RemoveCircle, t("Remove dependency %s", parentId), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    TextButton(onClick = { showParent = true }) { Text(t("Change…")) }
                }
            }
            KanbanSection(t("Lands in")) {
                Text(presentation.displayName, color = kanbanTint(presentation.tint), style = MaterialTheme.typography.titleSmall)
                Text(
                    t("The board selection is local to this device; creating this task will not switch Hermes' global board."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showModel) {
        KanbanModelOverrideDialog(draft.modelOverride, { draft = draft.copy(modelOverride = it) }, { store.fetchModelOptions() }) { showModel = false }
    }
    if (showSkills) {
        KanbanSkillsDialog(draft.skills, { draft = draft.copy(skills = it) }, { runtime.fetchSkills() }) { showSkills = false }
    }
    if (showParent) {
        KanbanParentDialog(state.board?.allTasks.orEmpty(), draft.parents.firstOrNull(), excludedTaskId = null, onSelect = {
            draft = draft.copy(parents = listOfNotNull(it))
        }) { showParent = false }
    }
}

@Composable
private fun NavRow(label: String, value: String, muted: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Per-task model / provider / reasoning override picker over the curated `/model-options` catalog. */
@Composable
fun KanbanModelOverrideDialog(
    value: TaskModelOverride,
    onChange: (TaskModelOverride) -> Unit,
    loadOptions: suspend () -> List<KanbanModelProviderOption>,
    onDismiss: () -> Unit,
) {
    var providers by remember { mutableStateOf<List<KanbanModelProviderOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var customProvider by remember { mutableStateOf(value.provider ?: "") }
    var customModel by remember { mutableStateOf(value.model ?: "") }
    LaunchedEffect(Unit) {
        try {
            providers = loadOptions().filter { it.models.isNotEmpty() }
            expanded = providers.firstOrNull { it.slug == value.provider }?.slug
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }
    val inheritCopy = t("Inherit from profile")
    KanbanFullScreenDialog(t("Model override"), onDismiss, actions = { TextButton(onClick = onDismiss) { Text(t("Done")) } }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanSection(null, footer = t("An unset override runs the task on the assigned profile's own model, provider, and reasoning effort.")) {
                Row(Modifier.fillMaxWidth().clickable(enabled = !value.isInherited) { onChange(TaskModelOverride()) }.padding(vertical = 6.dp)) {
                    Text(inheritCopy, modifier = Modifier.weight(1f))
                    if (value.isInherited) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            KanbanSection(t("Reasoning effort")) {
                val efforts = listOf("" to t("Inherit")) + TaskModelOverride.validReasoningEfforts.map {
                    it to if (it == "none") t("None (thinking off)") else TaskModelOverride.effortLabel(it)
                }
                KanbanPickerRow(
                    label = t("Effort"),
                    value = efforts.firstOrNull { it.first == (value.reasoningEffort ?: "") }?.second ?: value.reasoningEffort.orEmpty(),
                    options = efforts,
                    onSelect = { onChange(value.copy(reasoningEffort = it.ifEmpty { null })) },
                    isSelected = { it == (value.reasoningEffort ?: "") },
                )
            }
            KanbanSection(t("Catalog"), footer = t("Curated by Hermes so a worker can always spawn what you pick.")) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(t("Search models")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(t("Loading models…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val q = query.trim().lowercase()
                val filtered = if (q.isEmpty()) providers else providers.mapNotNull { p ->
                    if (p.slug.lowercase().contains(q) || p.displayName.lowercase().contains(q)) p
                    else p.models.filter { it.lowercase().contains(q) }.takeIf { it.isNotEmpty() }?.let { p.copy(models = it) }
                }
                filtered.forEach { provider ->
                    val open = expanded == provider.slug || q.isNotEmpty()
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == provider.slug) null else provider.slug }.padding(vertical = 8.dp)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                    }
                    if (open) {
                        provider.models.forEach { model ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onChange(TaskModelOverride(provider.slug, model, value.reasoningEffort)) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                Text(model, modifier = Modifier.weight(1f))
                                if (value.provider == provider.slug && value.model == model) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (!loading && providers.isEmpty()) {
                    Text(
                        t("No curated model catalog available from Hermes — enter a provider and model below."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loadFailed || (!loading && providers.isEmpty())) {
                KanbanSection(t("Custom (free text)")) {
                    OutlinedTextField(customProvider, { customProvider = it }, label = { Text(t("Provider")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(customModel, { customModel = it }, label = { Text(t("Model")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        onChange(TaskModelOverride(customProvider.trim().ifEmpty { null }, customModel.trim().ifEmpty { null }, value.reasoningEffort))
                    }, enabled = customModel.isNotBlank()) { Text(t("Use this override")) }
                }
            }
        }
    }
}

/** Multi-select over the active profile's skills plus manual entry; serialized as a trimmed list. */
@Composable
fun KanbanSkillsDialog(selected: List<String>, onChange: (List<String>) -> Unit, load: suspend () -> List<KanbanSkillOption>, onDismiss: () -> Unit) {
    var skills by remember { mutableStateOf<List<KanbanSkillOption>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        skills = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun toggle(name: String) = onChange(if (name in selected) selected - name else selected + name)
    KanbanFullScreenDialog(t("Skills"), onDismiss, actions = { TextButton(onClick = onDismiss) { Text(t("Done")) } }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(t("Search skills")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (selected.isNotEmpty()) {
                KanbanSection(t("Selected")) {
                    selected.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onChange(selected - name) }) { Icon(Icons.Default.Close, t("Remove skill %s", name)) }
                        }
                    }
                }
            }
            KanbanSection(t("Available skills")) {
                val q = query.trim().lowercase()
                val filtered = if (q.isEmpty()) skills else skills.filter { it.name.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
                filtered.forEach { skill ->
                    Row(Modifier.fillMaxWidth().clickable { toggle(skill.name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name)
                            skill.description?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                        if (skill.name in selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (filtered.isEmpty()) Text(t("No skills match."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            KanbanSection(t("Add manually")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        custom, { custom = it },
                        placeholder = { Text("skill-name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val name = custom.trim()
                        if (name.isNotEmpty() && name !in selected) onChange(selected + name)
                        custom = ""
                    }, enabled = custom.isNotBlank()) { Text(t("Add")) }
                }
            }
        }
    }
}

/** Single-parent picker over the board's tasks (desktop parity). */
@Composable
fun KanbanParentDialog(tasks: List<KanbanTask>, selectedId: String?, excludedTaskId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    KanbanFullScreenDialog(t("Parent task"), onDismiss, actions = { TextButton(onClick = onDismiss) { Text(t("Done")) } }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(t("Search tasks")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 10.dp)) {
                Text(t("— no parent —"), modifier = Modifier.weight(1f))
                if (selectedId == null) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
            val q = query.trim().lowercase()
            val candidates = tasks.filter { it.id != excludedTaskId }.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.id.lowercase().contains(q) }
            candidates.forEach { task ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(task.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(KanbanShortId.of(task.id), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            Text(KanbanStatusPresentation.forStatus(task.status).displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selectedId == task.id) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
            if (candidates.isEmpty()) Text(t("No other tasks on this board."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
