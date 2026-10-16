package app.hermesconduit.android.ui.kanban

import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.KanbanOrchestrationDisplay
import app.hermesconduit.android.model.KanbanOrchestrationPatch
import app.hermesconduit.android.model.KanbanOrchestrationSettings
import app.hermesconduit.android.model.KanbanProfileDescriptionPolicy
import app.hermesconduit.android.model.KanbanWorkerLog
import app.hermesconduit.android.model.KanbanWorkerLogPolicy
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.components.EmptyState
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsToggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// region Orchestration settings

private data class OrchestrationSeed(val autoDecompose: Boolean, val autoPromote: Boolean, val orchestrator: String, val assignee: String)

/** Server-global orchestration settings; "" (Default) clears a pinned profile. */
@Composable
fun KanbanOrchestrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var seed by remember { mutableStateOf<OrchestrationSeed?>(null) }
    var autoDecompose by remember { mutableStateOf(true) }
    var autoPromote by remember { mutableStateOf(true) }
    var orchestrator by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var token by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun adopt(settings: KanbanOrchestrationSettings) {
        autoDecompose = settings.autoDecompose
        autoPromote = settings.autoPromoteChildren ?: KanbanOrchestrationSettings.DEFAULT_AUTO_PROMOTE_CHILDREN
        orchestrator = settings.orchestratorProfile
        assignee = settings.defaultAssignee
        seed = OrchestrationSeed(autoDecompose, autoPromote, orchestrator, assignee)
    }
    LaunchedEffect(state.orchestration) {
        if (seed == null) state.orchestration?.let(::adopt)
    }
    val current = seed
    val hasChanges = current != null && OrchestrationSeed(autoDecompose, autoPromote, orchestrator, assignee) != current

    fun save() {
        val s = seed ?: return
        val stamp = state.loadedContextStamp ?: return
        if (!hasChanges || isSaving || !state.isSelectedSnapshotLoaded) return
        val patch = KanbanOrchestrationPatch(
            orchestratorProfile = orchestrator.takeIf { it != s.orchestrator },
            defaultAssignee = assignee.takeIf { it != s.assignee },
            autoDecompose = autoDecompose.takeIf { it != s.autoDecompose },
            autoPromoteChildren = autoPromote.takeIf { it != s.autoPromote },
        )
        if (patch.isEmpty) return
        val generation = store.configurationGeneration
        val myToken = ++token
        isSaving = true
        notice = null
        errorMessage = null
        scope.launch {
            try {
                val updated = store.updateOrchestration(patch, stamp)
                if (token == myToken) isSaving = false
                if (!store.isCurrentConfiguration(generation)) return@launch
                adopt(updated)
                notice = kt("Orchestration settings saved.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == myToken) isSaving = false
                if (store.isCurrentConfiguration(generation)) errorMessage = kanbanErrorMessage(e)
            }
        }
    }

    ScreenScaffold(
        t("Orchestration Settings"), onBack,
        actions = {
            TextButton(onClick = ::save, enabled = hasChanges && !isSaving && state.isSelectedSnapshotLoaded) {
                if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("Save"))
            }
        },
    ) { padding ->
        val settings = state.orchestration
        if (settings == null) {
            Column(Modifier.padding(padding)) {
                EmptyState(t("Load a Kanban board to read the server's orchestration settings."))
            }
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanSection(
                null,
                footer = t("Auto Decompose lets the dispatcher decompose eligible triage tasks. Auto Promote Children moves parent-free children to Ready instead of leaving them in To Do."),
            ) {
                SettingsToggle(t("Auto Decompose"), autoDecompose, { autoDecompose = it }, enabled = !isSaving)
                SettingsToggle(t("Auto Promote Children"), autoPromote, { autoPromote = it }, enabled = !isSaving)
            }
            KanbanSection(t("Orchestrator Profile")) {
                val label = orchestrator.ifEmpty { KanbanOrchestrationDisplay.defaultOptionLabel(settings.resolvedOrchestratorProfile) }
                KanbanPickerRow(
                    t("Orchestrator Profile"), label,
                    listOf("" to KanbanOrchestrationDisplay.defaultOptionLabel(settings.resolvedOrchestratorProfile)) + state.profiles.map { it.name to it.name },
                    onSelect = { orchestrator = it }, enabled = !isSaving, isSelected = { it == orchestrator },
                )
                Text(
                    KanbanOrchestrationDisplay.resolveFootnote(orchestrator, settings.resolvedOrchestratorProfile),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            KanbanSection(t("Default Assignee")) {
                val label = assignee.ifEmpty { KanbanOrchestrationDisplay.defaultOptionLabel(settings.resolvedDefaultAssignee) }
                KanbanPickerRow(
                    t("Default Assignee"), label,
                    listOf("" to KanbanOrchestrationDisplay.defaultOptionLabel(settings.resolvedDefaultAssignee)) + state.profiles.map { it.name to it.name },
                    onSelect = { assignee = it }, enabled = !isSaving, isSelected = { it == assignee },
                )
                Text(
                    KanbanOrchestrationDisplay.resolveFootnote(assignee, settings.resolvedDefaultAssignee),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { KanbanNotice(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.CheckCircle) }
        }
    }
}

// endregion

// region Profile routing

@Composable
fun KanbanProfileRoutingScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val state by runtime.store.state.collectAsStateWithLifecycle()
    ScreenScaffold(t("Profiles"), onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanSection(
                null,
                footer = t("Hermes routes triage decomposition across these profiles by their routing description. A clear description helps the decomposer pick the right specialist."),
            ) {
                if (state.profiles.isEmpty()) {
                    Text(t("No profiles on this Hermes server yet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.profiles.forEachIndexed { index, profile ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { navigate("kanban.profile/" + Uri.encode(profile.name)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                                if (profile.isDefault) Text(t("default"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (profile.descriptionAuto) Text(t("auto"), style = MaterialTheme.typography.labelSmall, color = KanbanOrange)
                                if (profile.description.isBlank()) Text(t("undescribed"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            profile.description.trim().takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** Routing description editor: manual save, or "Generate Automatically" (never silently overwrites a dirty draft). */
@Composable
fun KanbanProfileDescriptionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val state by store.state.collectAsStateWithLifecycle()
    val profileName = LocalRouteArg.current.orEmpty()
    val profile = state.profiles.firstOrNull { it.name == profileName }
    val scope = rememberCoroutineScope()
    var baseline by remember { mutableStateOf<KanbanProfileDescriptionPolicy.Snapshot?>(null) }
    var draft by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var token by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        if (baseline == null && profile != null) {
            baseline = KanbanProfileDescriptionPolicy.Snapshot(profile.name, profile.description, profile.descriptionAuto)
            draft = profile.description
        }
    }
    val base = baseline
    val trimmed = draft.trim()
    val isDirty = base != null && KanbanProfileDescriptionPolicy.isDirty(trimmed, base.description)

    fun save() {
        val stamp = state.loadedContextStamp ?: return
        if (!isDirty || isSaving || isGenerating || !state.isSelectedSnapshotLoaded) return
        val submitted = trimmed
        val generation = store.configurationGeneration
        val myToken = ++token
        isSaving = true
        errorMessage = null
        notice = null
        scope.launch {
            try {
                store.updateProfileDescription(profileName, submitted, stamp)
                if (token == myToken) isSaving = false
                if (!store.isCurrentConfiguration(generation)) return@launch
                val outcome = KanbanProfileDescriptionPolicy.saveCompletion(submitted, draft, draft.trim())
                baseline = KanbanProfileDescriptionPolicy.Snapshot(profileName, outcome.baselineDescription, outcome.baselineIsAuto)
                draft = outcome.draft
                notice = outcome.notice
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == myToken) isSaving = false
                if (store.isCurrentConfiguration(generation)) errorMessage = kanbanErrorMessage(e)
            }
        }
    }

    fun generate() {
        val stamp = state.loadedContextStamp ?: return
        if (isSaving || isGenerating || !state.isSelectedSnapshotLoaded) return
        val submitted = draft.trim()
        val generation = store.configurationGeneration
        val myToken = ++token
        isGenerating = true
        errorMessage = null
        notice = null
        scope.launch {
            try {
                val outcome = store.autoDescribeProfile(profileName, overwrite = true, expectedContext = stamp)
                if (token == myToken) isGenerating = false
                if (!store.isCurrentConfiguration(generation)) return@launch
                if (outcome.ok) {
                    val completion = KanbanProfileDescriptionPolicy.generateCompletion(submitted, draft, draft.trim(), outcome.description ?: "")
                    baseline = KanbanProfileDescriptionPolicy.Snapshot(profileName, completion.baselineDescription, completion.baselineIsAuto)
                    draft = completion.draft
                    notice = completion.notice
                } else {
                    errorMessage = outcome.reason ?: kt("Hermes could not generate a description.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == myToken) isGenerating = false
                if (store.isCurrentConfiguration(generation)) errorMessage = kanbanErrorMessage(e)
            }
        }
    }

    ScreenScaffold(profileName, onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KanbanSection(
                t("Routing Description"),
                footer = t("The decomposer reads these descriptions to route child tasks to the best specialist profile. Saving an empty description clears it (Hermes then falls back to name matching)."),
            ) {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), enabled = !isSaving && !isGenerating)
                if (base?.isAuto == true) KanbanNotice(t("Automatically generated — review recommended"), KanbanOrange, Icons.Default.AutoAwesome)
            }
            Button(onClick = ::save, enabled = isDirty && !isSaving && !isGenerating && state.isSelectedSnapshotLoaded, modifier = Modifier.fillMaxWidth()) {
                if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("Save"))
            }
            OutlinedButton(
                onClick = {
                    val b = baseline ?: return@OutlinedButton
                    when (KanbanProfileDescriptionPolicy.resolveGenerate(draft.trim(), b.description)) {
                        KanbanProfileDescriptionPolicy.GenerateResolution.ALLOWED -> generate()
                        KanbanProfileDescriptionPolicy.GenerateResolution.REQUIRES_DISCARD -> confirmDiscard = true
                    }
                },
                enabled = !isSaving && !isGenerating && state.isSelectedSnapshotLoaded && base != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Text("  " + t("Generate Automatically"))
                }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { KanbanNotice(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.CheckCircle) }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(t("Discard unsaved changes?")) },
            text = { Text(t("Generating replaces the current text. Your unsaved edits will be lost.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    baseline?.let { draft = it.description }
                    generate()
                }) { Text(t("Discard & Generate"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(t("Cancel")) } },
        )
    }
}

// endregion

// region Worker log

@Composable
fun KanbanWorkerLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val taskId = LocalRouteArg.current.orEmpty()
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf<KanbanWorkerLog?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    suspend fun load() {
        isLoading = true
        errorMessage = null
        try {
            log = store.fetchTaskLog(taskId, KanbanWorkerLogPolicy.TAIL_BYTES)
            refreshError = null
            scroll.scrollTo(scroll.maxValue)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (log == null) errorMessage = kanbanErrorMessage(e) else refreshError = kanbanErrorMessage(e)
        } finally {
            isLoading = false
        }
    }
    LaunchedEffect(taskId) { load() }

    ScreenScaffold(
        t("Worker Log"), onBack,
        actions = { IconButton(onClick = { scope.launch { load() } }, enabled = !isLoading) { Icon(Icons.Default.Refresh, t("Refresh worker log")) } },
    ) { padding ->
        val current = log
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                current == null && errorMessage != null -> {
                    Text(t("Log unavailable"), style = MaterialTheme.typography.titleMedium)
                    Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(t("Loading worker log…"))
                }
                else -> {
                    refreshError?.let { Text(t("Refresh failed: %s", it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!current.exists) {
                        Text(t("No worker output yet"), style = MaterialTheme.typography.titleMedium)
                        Text(t("This task has not spawned a worker, so there is no log."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Formatter.formatShortFileSize(context, current.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (current.truncated) {
                                Text(t("· showing last %s KiB", (KanbanWorkerLogPolicy.TAIL_BYTES / 1024).toString()), style = MaterialTheme.typography.labelSmall, color = KanbanOrange)
                            }
                        }
                        val rendered = KanbanWorkerLogPolicy.renderTail(current.content)
                        if (rendered.isEmpty()) {
                            Text(t("(log is empty)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        } else {
                            SelectionContainer {
                                Text(
                                    rendered,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion
