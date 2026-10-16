package app.hermesconduit.android.ui.kanban

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.KanbanBoardEditorDraft
import app.hermesconduit.android.model.KanbanBoardMetadata
import app.hermesconduit.android.model.KanbanBoardPatchPolicy
import app.hermesconduit.android.model.KanbanBoardSlugPolicy
import app.hermesconduit.android.model.KanbanBoardWorkspacePresentation
import app.hermesconduit.android.model.KanbanDefaultWorkspaceChoice
import app.hermesconduit.android.ui.LocalRouteArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class WorkspaceRow { PROJECT_DEFAULT, NONE, CUSTOM }

/** Create (`kanban.board/new`) or edit (`kanban.board/settings`) a board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanBoardEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val state by store.state.collectAsStateWithLifecycle()
    val isCreate = LocalRouteArg.current != "settings"
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var descriptionText by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<String?>(null) }
    var workspaceRow by remember { mutableStateOf(WorkspaceRow.NONE) }
    var customPath by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var manualSlug by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<KanbanBoardMetadata?>(null) }
    var seedDraft by remember { mutableStateOf<KanbanBoardEditorDraft?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var liveness by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun apply(choice: KanbanDefaultWorkspaceChoice) {
        when (choice) {
            KanbanDefaultWorkspaceChoice.ProjectDefault -> workspaceRow = WorkspaceRow.PROJECT_DEFAULT
            KanbanDefaultWorkspaceChoice.None -> workspaceRow = WorkspaceRow.NONE
            is KanbanDefaultWorkspaceChoice.Custom -> { workspaceRow = WorkspaceRow.CUSTOM; customPath = choice.path }
        }
    }

    val resolvedSlug = if (manualSlug) slug.trim() else KanbanBoardSlugPolicy.derivedSlug(name)
    val hasValidSlug = KanbanBoardSlugPolicy.isValid(resolvedSlug)
    val trimmedName = name.trim()
    val effectiveChoice: KanbanDefaultWorkspaceChoice = when {
        workspaceRow == WorkspaceRow.PROJECT_DEFAULT && projectId.isNullOrEmpty() -> KanbanDefaultWorkspaceChoice.None
        workspaceRow == WorkspaceRow.PROJECT_DEFAULT -> KanbanDefaultWorkspaceChoice.ProjectDefault
        workspaceRow == WorkspaceRow.NONE -> KanbanDefaultWorkspaceChoice.None
        else -> KanbanDefaultWorkspaceChoice.Custom(customPath.trim())
    }
    val currentDraft = KanbanBoardEditorDraft(trimmedName, descriptionText, projectId, effectiveChoice)
    val canSubmit = if (isCreate) hasValidSlug && trimmedName.isNotEmpty() else seedDraft != null && currentDraft != seedDraft

    // Settings mode seeds once from the loaded board; a board switch closes the editor.
    val seedBoard = if (isCreate) null else state.loadedBoardMetadata
    LaunchedEffect(seedBoard?.slug) {
        if (isCreate || seedDraft != null) return@LaunchedEffect
        val board = seedBoard ?: return@LaunchedEffect
        baseline = board
        apply(KanbanBoardWorkspacePresentation.derive(board, state.projects))
        name = board.name ?: ""
        descriptionText = board.description ?: ""
        projectId = board.projectId?.takeIf { it.isNotEmpty() }
        slug = board.slug
        seedDraft = KanbanBoardEditorDraft(name.trim(), descriptionText, projectId, KanbanBoardWorkspacePresentation.derive(board, state.projects).let {
            if (it == KanbanDefaultWorkspaceChoice.ProjectDefault && projectId.isNullOrEmpty()) KanbanDefaultWorkspaceChoice.None else it
        })
    }
    LaunchedEffect(state.loadedBoardSlug) {
        val base = baseline
        if (!isCreate && base != null && state.loadedBoardSlug != base.slug) onBack()
    }

    fun submit() {
        val stamp = state.loadedContextStamp ?: return
        if (!canSubmit || isSaving) return
        val generation = store.configurationGeneration
        val token = ++liveness
        isSaving = true
        errorMessage = null
        notice = null
        val draft = currentDraft
        scope.launch {
            try {
                if (isCreate) {
                    store.createBoard(
                        KanbanBoardPatchPolicy.createRequest(resolvedSlug, trimmedName, descriptionText, draft.projectId, draft.workspace),
                        stamp, runtime.includeArchived.value,
                    )
                    if (liveness == token) isSaving = false
                    if (store.isCurrentConfiguration(generation)) onBack()
                } else {
                    val base = baseline ?: return@launch
                    val patch = KanbanBoardPatchPolicy.patch(base, draft, state.projects)
                    if (patch.isEmpty) return@launch
                    val updated = store.updateBoard(base.slug, patch, stamp, runtime.includeArchived.value)
                    if (!store.isCurrentConfiguration(generation)) return@launch
                    baseline = updated
                    val derived = KanbanBoardWorkspacePresentation.derive(updated, store.state.value.projects)
                    apply(derived)
                    name = updated.name ?: ""
                    descriptionText = updated.description ?: ""
                    projectId = updated.projectId?.takeIf { it.isNotEmpty() }
                    seedDraft = KanbanBoardEditorDraft(
                        name.trim(), descriptionText, projectId,
                        if (derived == KanbanDefaultWorkspaceChoice.ProjectDefault && projectId.isNullOrEmpty()) KanbanDefaultWorkspaceChoice.None else derived,
                    )
                    notice = kt("Board settings saved.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.isCurrentConfiguration(generation)) errorMessage = kanbanErrorMessage(e)
            } finally {
                if (liveness == token) isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isCreate) t("New Board") else t("Board Settings")) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !isSaving) { Icon(Icons.Default.Close, if (isCreate) t("Cancel") else t("Close")) } },
                actions = {
                    TextButton(onClick = ::submit, enabled = canSubmit && !isSaving) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (isCreate) t("Create") else t("Save"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val basicsFooter = when {
                isCreate && trimmedName.isNotEmpty() && !hasValidSlug ->
                    t("The slug must be 1-64 lowercase letters or numbers, with hyphens or underscores after the first character.")
                isCreate -> t("The slug is generated from the name (like Desktop) and cannot be changed after creation.")
                else -> t("The slug is immutable after creation.")
            }
            KanbanSection(t("Basics"), footer = basicsFooter) {
                OutlinedTextField(name, { name = it }, label = { Text(t("Name")) }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                if (isCreate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Edit slug manually"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(manualSlug, { manualSlug = it; if (it && slug.isEmpty()) slug = KanbanBoardSlugPolicy.derivedSlug(name) })
                    }
                    if (manualSlug) {
                        OutlinedTextField(
                            slug, { slug = it },
                            label = { Text(t("Slug")) },
                            singleLine = true,
                            isError = slug.isNotEmpty() && !hasValidSlug,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(t("Slug: %s", resolvedSlug), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    KanbanMetricRow(t("Slug"), baseline?.slug ?: slug)
                }
                OutlinedTextField(
                    descriptionText, { descriptionText = it },
                    label = { Text(t("Description")) },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
            }
            KanbanSection(t("Project")) {
                KanbanPickerRow(
                    label = t("Project"),
                    value = state.projects.firstOrNull { it.id == projectId }?.name ?: t("No Project"),
                    options = listOf<Pair<String?, String>>(null to t("No Project")) + state.projects.map { it.id to it.name },
                    onSelect = { value ->
                        projectId = value
                        if (value != null) {
                            if (workspaceRow != WorkspaceRow.CUSTOM || customPath.isBlank()) workspaceRow = WorkspaceRow.PROJECT_DEFAULT
                        } else if (workspaceRow == WorkspaceRow.PROJECT_DEFAULT) {
                            workspaceRow = WorkspaceRow.NONE
                        }
                    },
                    enabled = !isSaving,
                    isSelected = { it == projectId },
                )
                if (state.projects.isEmpty()) {
                    Text(
                        t("No projects on this server yet — the board stays unscoped (scratch workspaces)."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val workspaceFooter = when {
                projectId != null && workspaceRow == WorkspaceRow.PROJECT_DEFAULT -> t("New tasks mirror the project's primary repository.")
                workspaceRow == WorkspaceRow.NONE -> t("New tasks use scratch workspaces.")
                else -> null
            }
            KanbanSection(t("Default Workspace"), footer = workspaceFooter) {
                val rows = buildList {
                    if (projectId != null) add(WorkspaceRow.PROJECT_DEFAULT to t("Project Default")) else add(WorkspaceRow.NONE to t("None / Scratch"))
                    add(WorkspaceRow.CUSTOM to t("Custom Directory…"))
                }
                KanbanPickerRow(
                    label = t("Default Workspace"),
                    value = rows.firstOrNull { it.first == workspaceRow }?.second ?: rows.first().second,
                    options = rows,
                    onSelect = { workspaceRow = it },
                    enabled = !isSaving,
                    isSelected = { it == workspaceRow },
                )
                if (workspaceRow == WorkspaceRow.CUSTOM) {
                    OutlinedTextField(
                        customPath, { customPath = it },
                        placeholder = { Text(t("Absolute directory path")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            KanbanBoardWorkspacePresentation.previewDirectory(effectiveChoice, projectId, state.projects)?.let { dir ->
                KanbanSection(t("Effective Directory")) { KanbanNotice(dir, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Folder) }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { KanbanNotice(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.CheckCircle) }
        }
    }
}
