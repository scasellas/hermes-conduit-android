package app.hermesconduit.android.ui.kanban

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.CompletedTriageMutation
import app.hermesconduit.android.model.KanbanActivityFormatter
import app.hermesconduit.android.model.KanbanBlockedQuestion
import app.hermesconduit.android.model.KanbanDetailDraftPolicy
import app.hermesconduit.android.model.KanbanDetailIdentityPolicy
import app.hermesconduit.android.model.KanbanDiagnostic
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.model.KanbanLiveUpdatePolicy
import app.hermesconduit.android.model.KanbanModelOverrideSession
import app.hermesconduit.android.model.KanbanModelOverrideSessionPolicy
import app.hermesconduit.android.model.KanbanNoteAndRequeueFlow
import app.hermesconduit.android.model.KanbanPollingPolicy
import app.hermesconduit.android.model.KanbanRun
import app.hermesconduit.android.model.KanbanRunPresentation
import app.hermesconduit.android.model.KanbanShortId
import app.hermesconduit.android.model.KanbanStatusPresentation
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskDetail
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.KanbanTriageActionsPolicy
import app.hermesconduit.android.model.KanbanTriageCompletionPolicy
import app.hermesconduit.android.model.KanbanTriagePolicy
import app.hermesconduit.android.model.PendingTriageAction
import app.hermesconduit.android.model.TaskModelOverride
import app.hermesconduit.android.model.strictString
import app.hermesconduit.android.ui.LocalRouteArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Detail state for one displayed task (dependency taps replace it in place, like iOS). */
class KanbanTaskDetailModel(private val store: KanbanStore, initialId: String) : ViewModel() {
    private val initialTask: KanbanTask? = store.state.value.task(initialId)
    var displayedTaskId by mutableStateOf(initialId)
        private set
    var detail by mutableStateOf<KanbanTaskDetail?>(null)
        private set
    var title by mutableStateOf(initialTask?.title ?: "")
    var body by mutableStateOf(initialTask?.body ?: "")
    var status by mutableStateOf(initialTask?.status ?: "todo")
    private var baseline by mutableStateOf(KanbanDetailDraftPolicy.Fields(title, body, status))
    var remoteChangeNotice by mutableStateOf<String?>(null)
    var comment by mutableStateOf("")
    var isSaving by mutableStateOf(false)
    var isAddingComment by mutableStateOf(false)
    var isRequeuing by mutableStateOf(false)
    var isUnblocking by mutableStateOf(false)
    var isSpecifying by mutableStateOf(false)
    var isDecomposing by mutableStateOf(false)
    var pendingDecompose by mutableStateOf<PendingTriageAction?>(null)
    var completedTriage by mutableStateOf<CompletedTriageMutation?>(null)
    var actionNotice by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var refreshErrorMessage by mutableStateOf<String?>(null)
    var modelSession by mutableStateOf<KanbanModelOverrideSession?>(null)
    var modelDraft by mutableStateOf(TaskModelOverride())
    var showReassign by mutableStateOf(false)
    var showDelete by mutableStateOf(false)
    private var isLoadingDetail = false
    private var initialForIdentity: KanbanTask? = initialTask

    val displayedTask: KanbanTask? get() = KanbanDetailIdentityPolicy.actionableTask(displayedTaskId, detail?.task, initialForIdentity)
    val hasUnsavedChanges: Boolean get() = KanbanDetailDraftPolicy.isDirty(KanbanDetailDraftPolicy.Fields(title, body, status), baseline)
    val isRunning: Boolean get() = displayedTask?.status == "running"
    val isBlocked: Boolean get() = displayedTask?.status == "blocked"
    val blockedQuestion: String? get() = if (isBlocked) detail?.let(KanbanBlockedQuestion::reason) else null
    val anyBusy: Boolean get() = isSaving || isAddingComment || isRequeuing || isUnblocking

    private fun active(expectedId: String) = expectedId == displayedTaskId

    fun open(id: String) {
        if (id == displayedTaskId) return
        displayedTaskId = id
        initialForIdentity = null
        detail = null
        isLoadingDetail = false
        isSaving = false; isAddingComment = false; isRequeuing = false; isUnblocking = false
        isSpecifying = false; isDecomposing = false
        pendingDecompose = null; completedTriage = null; actionNotice = null
        comment = ""; title = ""; body = ""; status = "todo"
        baseline = KanbanDetailDraftPolicy.Fields("", "", "todo")
        remoteChangeNotice = null; errorMessage = null; refreshErrorMessage = null
        modelDraft = TaskModelOverride(); modelSession = null
        showReassign = false; showDelete = false
    }

    suspend fun loadDetail(force: Boolean = false) {
        if ((!force && anyBusy) || isLoadingDetail) return
        val expectedId = displayedTaskId
        isLoadingDetail = true
        try {
            val loaded = store.fetchTaskDetail(expectedId)
            if (!active(expectedId)) return
            if (loaded.task.id != expectedId) {
                if (loaded.task.id.isEmpty() && expectedId.isNotEmpty()) errorMessage = kt("Task detail returned an invalid id.")
                return
            }
            val server = KanbanDetailDraftPolicy.Fields(loaded.task.title, loaded.task.body ?: "", loaded.task.status)
            if (hasUnsavedChanges) {
                // Never overwrite a dirty draft; tell the user the server moved.
                if (KanbanDetailDraftPolicy.serverMovedIndependently(server, baseline) && remoteChangeNotice == null) {
                    remoteChangeNotice = kt("This task changed on the server. Your unsaved edits are preserved.")
                }
            } else {
                title = server.title; body = server.body; status = server.status
                baseline = server
                remoteChangeNotice = null
            }
            if (displayedTask == null) errorMessage = null
            detail = loaded
            if (KanbanTriageCompletionPolicy.shouldClearAfterReconciliation(loaded.task.status)) completedTriage = null
            refreshErrorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!active(expectedId)) return
            if (detail == null) errorMessage = kanbanErrorMessage(e) else refreshErrorMessage = kanbanErrorMessage(e)
        } finally {
            if (active(expectedId)) isLoadingDetail = false
        }
    }

    fun onLiveInvalidation(invalidation: KanbanEventInvalidation?) {
        val st = store.state.value
        if (KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, st.loadedContextStamp, st.isSelectedSnapshotLoaded, displayedTaskId)) {
            viewModelScope.launch { loadDetail() }
        }
    }

    private fun mutate(expectedId: String, setBusy: (Boolean) -> Unit, block: suspend () -> Unit) {
        setBusy(true)
        errorMessage = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active(expectedId)) errorMessage = kanbanErrorMessage(e)
            } finally {
                if (active(expectedId)) setBusy(false)
            }
        }
    }

    fun save() {
        val started = displayedTask ?: return
        val expectedId = displayedTaskId
        val trimmedTitle = title.trim()
        var patch = KanbanTaskPatch()
        if (trimmedTitle != baseline.title) patch = patch.copy(title = trimmedTitle)
        if (body != baseline.body) patch = patch.copy(body = body)
        if (status != baseline.status) {
            if (!KanbanStatusPresentation.canSelectManually(status)) {
                errorMessage = KanbanServiceException.InvalidManualStatus(status).message
                return
            }
            patch = patch.copy(status = status)
        }
        if (patch.isEmpty) {
            title = baseline.title
            return
        }
        mutate(expectedId, { isSaving = it }) {
            val saved = store.updateTask(started.id, patch)
            if (!active(expectedId)) return@mutate
            val server = saved?.takeIf { it.id == started.id } ?: started
            title = server.title
            body = server.body ?: body
            status = server.status
            baseline = KanbanDetailDraftPolicy.Fields(server.title, server.body ?: body, server.status)
            remoteChangeNotice = null
            loadDetail(force = true)
        }
    }

    fun beginModelEdit() {
        val current = displayedTask?.let(TaskModelOverride::of) ?: TaskModelOverride()
        modelSession = KanbanModelOverrideSession(displayedTaskId, current)
        modelDraft = current
    }

    /** Dismissal commits only a real edit, diffed against current server state. */
    fun endModelEdit() {
        val target = KanbanModelOverrideSessionPolicy.commitTarget(modelSession, modelDraft, displayedTask, displayedTaskId)
        modelSession = null
        target ?: return
        val patch = TaskModelOverride.patch(target.startedTask, target.value)
        if (patch.isEmpty) return
        mutate(target.expectedId, { isSaving = it }) {
            store.updateTask(target.startedTask.id, patch)
            if (active(target.expectedId)) loadDetail(force = true)
        }
    }

    fun archive() {
        val started = displayedTask?.takeIf { it.status != "archived" } ?: return
        val expectedId = displayedTaskId
        mutate(expectedId, {}) {
            store.updateTask(started.id, KanbanTaskPatch(status = "archived"))
            if (active(expectedId)) loadDetail(force = true)
        }
    }

    fun addComment() {
        val text = comment.trim()
        if (text.isEmpty() || anyBusy) return
        val expectedId = displayedTaskId
        mutate(expectedId, { isAddingComment = it }) {
            store.addComment(expectedId, text)
            if (!active(expectedId)) return@mutate
            comment = ""
            loadDetail(force = true)
        }
    }

    private fun commentThen(transition: KanbanNoteAndRequeueFlow.Transition, setBusy: (Boolean) -> Unit, action: suspend (String) -> Unit) {
        val text = comment.trim()
        if (text.isEmpty() || anyBusy) return
        val expectedId = displayedTaskId
        setBusy(true)
        errorMessage = null
        viewModelScope.launch {
            try {
                val outcome = KanbanNoteAndRequeueFlow.perform(
                    text,
                    postComment = { store.addComment(expectedId, it) },
                    transition = { action(expectedId) },
                    onCommentPosted = { if (active(expectedId)) comment = "" },
                    message = ::kanbanErrorMessage,
                )
                if (!active(expectedId)) return@launch
                val message = KanbanNoteAndRequeueFlow.message(outcome, transition)
                if (message != null) errorMessage = message
                if (message == null || transition == KanbanNoteAndRequeueFlow.Transition.UNBLOCK) loadDetail(force = true)
            } finally {
                if (active(expectedId)) setBusy(false)
            }
        }
    }

    /** Running task: post the note, then reclaim so the next run sees it. */
    fun noteAndRequeue() = commentThen(KanbanNoteAndRequeueFlow.Transition.REQUEUE, { isRequeuing = it }) { store.reclaimTask(it) }

    /** Blocked task: post the answer, then move it back to Ready. */
    fun replyAndUnblock() = commentThen(KanbanNoteAndRequeueFlow.Transition.UNBLOCK, { isUnblocking = it }) {
        store.updateTask(it, KanbanTaskPatch(status = "ready"))
    }

    fun reclaim() {
        val expectedId = displayedTaskId
        mutate(expectedId, {}) {
            store.reclaimTask(expectedId)
            if (active(expectedId)) loadDetail(force = true)
        }
    }

    fun specify() {
        val st = store.state.value
        val pending = PendingTriageAction.capture(displayedTask, st.isSelectedSnapshotLoaded, st.loadedContextStamp) ?: return
        mutate(pending.taskId, { isSpecifying = it }) {
            store.specifyTask(pending.taskId, pending.context)
            if (!active(pending.taskId)) return@mutate
            completedTriage = CompletedTriageMutation(pending.taskId, pending.context)
            actionNotice = KanbanTriageActionsPolicy.successNoticeWithRefreshFailure(kt("Task specified"), store.state.value.errorMessage)
            loadDetail(force = true)
        }
    }

    fun requestDecompose() {
        val st = store.state.value
        pendingDecompose = PendingTriageAction.capture(displayedTask, st.isSelectedSnapshotLoaded, st.loadedContextStamp)
    }

    fun confirmDecompose() {
        val pending = pendingDecompose ?: return
        pendingDecompose = null
        mutate(pending.taskId, { isDecomposing = it }) {
            val response = store.decomposeTask(pending.taskId, pending.context)
            if (!active(pending.taskId)) return@mutate
            completedTriage = CompletedTriageMutation(pending.taskId, pending.context)
            actionNotice = KanbanTriageActionsPolicy.successNoticeWithRefreshFailure(
                KanbanTriageActionsPolicy.successNotice(response.fanout, response.childIds.size),
                store.state.value.errorMessage,
            )
            loadDetail(force = true)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val started = displayedTask ?: return
        val expectedId = displayedTaskId
        mutate(expectedId, {}) {
            store.deleteTask(started.id)
            if (active(expectedId)) onDeleted()
        }
    }

    fun reassign(profile: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                store.reassignTask(displayedTaskId, profile, reclaimFirst = true)
                onDone(null)
                loadDetail(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(kanbanErrorMessage(e))
            }
        }
    }

    fun onBoardChanged() {
        pendingDecompose = null
        completedTriage = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanTaskDetailScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val taskId = LocalRouteArg.current.orEmpty()
    val model = viewModel(key = "kanban.task.$taskId") { KanbanTaskDetailModel(store, taskId) }
    val state by store.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(model.displayedTaskId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.loadDetail(force = true)
            while (true) {
                delay(KanbanPollingPolicy.DETAIL_INTERVAL_MS)
                model.loadDetail()
            }
        }
    }
    LaunchedEffect(state.liveInvalidation) { model.onLiveInvalidation(state.liveInvalidation) }
    var lastBoard by remember { mutableStateOf(state.loadedBoardSlug) }
    LaunchedEffect(state.loadedBoardSlug) {
        if (state.loadedBoardSlug != lastBoard) {
            lastBoard = state.loadedBoardSlug
            model.onBoardChanged()
        }
    }

    val task = model.displayedTask
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("Task")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back")) } },
                actions = {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, t("Task actions")) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text(t("Copy Task ID")) }, enabled = task != null, onClick = {
                                open = false
                                task?.let { kanbanCopy(context, it.id, kt("Task ID copied")) }
                            })
                            DropdownMenuItem(text = { Text(t("Copy Task Title")) }, enabled = task != null, onClick = {
                                open = false
                                task?.let { kanbanCopy(context, it.title.ifEmpty { it.id }, kt("Title copied")) }
                            })
                            DropdownMenuItem(text = { Text(t("Reassign…")) }, enabled = task != null, onClick = { open = false; model.showReassign = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(t("Archive")) }, enabled = task != null && task.status != "archived", onClick = { open = false; model.archive() })
                            DropdownMenuItem(
                                text = { Text(t("Delete…"), color = MaterialTheme.colorScheme.error) },
                                enabled = task != null,
                                onClick = { open = false; model.showDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (task != null) {
                EditorSection(model)
                AssignmentSection(model, task, state.hasDispatcherFallback)
                val suppressed = KanbanTriageCompletionPolicy.isSuppressed(model.completedTriage, model.displayedTaskId, state.loadedContextStamp)
                if (KanbanTriagePolicy.isEligible(task) && !suppressed) TriageSection(model)
                if (task.status == "ready" && task.assignee.isNullOrEmpty() && !state.hasDispatcherFallback) {
                    KanbanCallout(KanbanOrange) {
                        Text(t("Ready but unassigned"), style = MaterialTheme.typography.titleSmall, color = KanbanOrange)
                        Text(
                            t("No profile is attached and Hermes has no default assignee configured, so this task will not run until someone assigns it."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { model.showReassign = true }) { Text(t("Reassign now")) }
                    }
                }
                if (model.isBlocked) {
                    model.blockedQuestion?.let { question ->
                        val tint = MaterialTheme.colorScheme.error
                        KanbanCallout(tint) {
                            Text(t("Waiting on you"), style = MaterialTheme.typography.titleSmall, color = tint)
                            SelectionContainer { Text(question, style = MaterialTheme.typography.bodyMedium) }
                            Text(
                                t("Reply & unblock posts your answer and moves the task back to Ready, so the next run sees it."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    CommentsSection(model)
                }
                task.diagnostics?.takeIf { it.isNotEmpty() }?.let { DiagnosticsSection(it, model, onCopy = { cmd -> kanbanCopy(context, cmd, kt("Recovery command copied")) }) }
                DependenciesSection(model.detail, state, onOpen = model::open)
                if (!model.isBlocked) CommentsSection(model)
                ActivitySection(model.detail)
                RunsSection(model.detail)
                KanbanSection(null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { navigate("kanban.log/" + Uri.encode(model.displayedTaskId)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("Worker Log"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
                model.actionNotice?.let { KanbanNotice(it, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.CheckCircle) }
                model.remoteChangeNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = KanbanOrange) }
                if (model.hasUnsavedChanges) {
                    Text(t("Unsaved edits"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                }
                model.refreshErrorMessage?.let { Text(t("Refresh failed: %s", it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                model.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } else if (model.errorMessage != null) {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Warning, null, tint = KanbanOrange)
                    Text(t("Couldn't load this task"), style = MaterialTheme.typography.titleSmall)
                    Text(model.errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text(t("Loading task…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (model.showDelete) {
        AlertDialog(
            onDismissRequest = { model.showDelete = false },
            title = { Text(t("Delete this task?")) },
            text = { Text(t("This permanently removes the task from the selected Hermes board.")) },
            confirmButton = {
                TextButton(onClick = { model.showDelete = false; model.delete(onBack) }) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { model.showDelete = false }) { Text(t("Cancel")) } },
        )
    }
    if (model.pendingDecompose != null) {
        AlertDialog(
            onDismissRequest = { model.pendingDecompose = null },
            title = { Text(KanbanTriageActionsPolicy.decomposeConfirmationTitle) },
            text = { Text(KanbanTriageActionsPolicy.decomposeConfirmationMessage) },
            confirmButton = { TextButton(onClick = model::confirmDecompose) { Text(t("Decompose")) } },
            dismissButton = { TextButton(onClick = { model.pendingDecompose = null }) { Text(t("Cancel")) } },
        )
    }
    if (model.showReassign) {
        ReassignDialog(state.profiles.map { it.name }, model.displayedTask?.assignee, onDismiss = { model.showReassign = false }) { profile, done ->
            model.reassign(profile) { error ->
                done(error)
                if (error == null) model.showReassign = false
            }
        }
    }
    if (model.modelSession != null) {
        KanbanModelOverrideDialog(
            value = model.modelDraft,
            onChange = { model.modelDraft = it },
            loadOptions = { store.fetchModelOptions() },
            onDismiss = model::endModelEdit,
        )
    }
}

@Composable
private fun EditorSection(model: KanbanTaskDetailModel) {
    val statusOptions = KanbanStatusPresentation.manuallySelectableStatuses.let { values ->
        if (values.none { it.rawValue == model.status }) listOf(KanbanStatusPresentation.forStatus(model.status)) + values else values
    }
    KanbanSection(null) {
        OutlinedTextField(model.title, { model.title = it }, label = { Text(t("Title")) }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium)
        OutlinedTextField(model.body, { model.body = it }, label = { Text(t("Description")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        KanbanPickerRow(
            label = t("Status"),
            value = KanbanStatusPresentation.forStatus(model.status).displayName,
            options = statusOptions.filter { it.isManuallySelectable }.map { it.rawValue to it.displayName },
            onSelect = { model.status = it },
            isSelected = { it == model.status },
        )
        Button(
            onClick = model::save,
            enabled = !model.anyBusy && model.title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (model.isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("Save changes"))
        }
    }
}

@Composable
private fun AssignmentSection(model: KanbanTaskDetailModel, task: KanbanTask, hasDispatcherFallback: Boolean) {
    KanbanSection(t("Assignment & Execution")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Assignee"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val assignee = task.assignee
            if (!assignee.isNullOrEmpty()) {
                Text(assignee, maxLines = 1)
            } else {
                Text(
                    t("Unassigned") + if (task.status == "ready" && hasDispatcherFallback) " " + t("→ default") else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { model.showReassign = true }) { Text(t("Reassign")) }
        }
        task.priority?.let { KanbanMetricRow(t("Priority"), it.toString()) }
        val path = task.workspacePath
        if (!path.isNullOrEmpty()) {
            val kind = task.workspaceKind
            KanbanMetricRow(t("Workspace"), if (!kind.isNullOrEmpty()) "$kind: $path" else path, maxLines = 2)
        }
        val override = TaskModelOverride.of(task)
        Row(Modifier.fillMaxWidth().clickable(onClick = model::beginModelEdit).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Model"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                override.label(t("Inherit from profile")),
                color = if (override.isInherited) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                textAlign = TextAlign.End,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
        if (model.isRunning) task.workerPid?.let { KanbanMetricRow(t("Worker PID"), it.toString()) }
        task.createdBy?.takeIf { it.isNotEmpty() }?.let { KanbanMetricRow(t("Created by"), it) }
        task.consecutiveFailures?.takeIf { it > 0 }?.let { KanbanMetricRow(t("Consecutive failures"), it.toString()) }
        task.lastFailureError?.takeIf { it.isNotEmpty() }?.let {
            Text(t("Last failure"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TriageSection(model: KanbanTaskDetailModel) {
    KanbanSection(t("Triage Actions")) {
        val busy = model.isSpecifying || model.isDecomposing
        Button(onClick = model::specify, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (model.isSpecifying) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Text("  " + t("Specify Task"))
            }
        }
        OutlinedButton(onClick = model::requestDecompose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (model.isDecomposing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Science, null, Modifier.size(18.dp))
                Text("  " + t("Decompose Into Tasks"))
            }
        }
    }
}

@Composable
private fun CommentsSection(model: KanbanTaskDetailModel) {
    KanbanSection(t("Comments")) {
        val comments = model.detail?.comments.orEmpty()
        if (comments.isEmpty()) {
            Text(t("No comments yet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        comments.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(c.author, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(kanbanRelativeTime(c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                SelectionContainer { Text(c.body, style = MaterialTheme.typography.bodySmall) }
            }
        }
        OutlinedTextField(
            model.comment, { model.comment = it },
            placeholder = { Text(t("New comment")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
        )
        if (model.isRunning) {
            Text(t("Comments reach the live worker between turns as an out-of-band note."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val hasText = model.comment.isNotBlank()
        OutlinedButton(onClick = model::addComment, enabled = hasText && !model.anyBusy, modifier = Modifier.fillMaxWidth()) {
            if (model.isAddingComment) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (model.isRunning) t("Send") else t("Add comment"))
        }
        if (model.isRunning) {
            OutlinedButton(
                onClick = model::noteAndRequeue,
                enabled = hasText && !model.anyBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KanbanOrange),
            ) {
                if (model.isRequeuing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("Note & requeue"))
            }
        }
        if (model.blockedQuestion != null) {
            Button(onClick = model::replyAndUnblock, enabled = hasText && !model.anyBusy, modifier = Modifier.fillMaxWidth()) {
                if (model.isUnblocking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(t("Reply & unblock"))
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(diagnostics: List<KanbanDiagnostic>, model: KanbanTaskDetailModel, onCopy: (String) -> Unit) {
    KanbanSection(t("Diagnostics (%d)", diagnostics.size)) {
        diagnostics.forEach { d ->
            val color = when (d.severity.lowercase()) {
                "critical", "error" -> MaterialTheme.colorScheme.error
                "warning" -> KanbanOrange
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val icon = when (d.severity.lowercase()) {
                "critical", "error" -> Icons.Default.ErrorOutline
                "warning" -> Icons.Default.Warning
                else -> Icons.Default.Info
            }
            KanbanCallout(color) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, null, Modifier.size(18.dp), tint = color)
                    Text(d.title.ifEmpty { d.kind }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (d.count > 1) Text("×${d.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.detail.isNotEmpty()) Text(d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Only the two upstream recovery actions; never invent other mutations.
                d.actions.filter { it.kind == "reclaim" || it.kind == "cli_hint" }.forEach { action ->
                    when (action.kind) {
                        "reclaim" -> OutlinedButton(onClick = model::reclaim) { Text(action.label.ifEmpty { t("Reclaim") }) }
                        "cli_hint" -> OutlinedButton(onClick = { onCopy(action.payload?.get("command").strictString ?: action.label) }) {
                            Text(action.label.ifEmpty { t("Copy command") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DependenciesSection(detail: KanbanTaskDetail?, state: KanbanStoreState, onOpen: (String) -> Unit) {
    val links = detail?.links ?: return
    if (links.parents.isEmpty() && links.children.isEmpty()) return
    KanbanSection(t("Dependencies")) {
        @Composable
        fun group(title: String, ids: List<String>) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ids.forEach { id ->
                val linked = state.task(id)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.outline)
                    Column(Modifier.weight(1f)) {
                        Text(linked?.title?.takeIf { it.isNotEmpty() } ?: KanbanShortId.of(id), maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(KanbanShortId.of(id), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                            linked?.let {
                                Text(KanbanStatusPresentation.forStatus(it.status).displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (links.parents.isNotEmpty()) group(t("Blocked by"), links.parents)
        if (links.children.isNotEmpty()) group(t("Blocks"), links.children)
    }
}

@Composable
private fun ActivitySection(detail: KanbanTaskDetail?) {
    val events = detail?.events.orEmpty()
    if (events.isEmpty()) return
    KanbanSection(t("Activity (%d)", events.size)) {
        events.take(60).forEach { event ->
            val row = KanbanActivityFormatter.row(event)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Circle, null, Modifier.padding(top = 4.dp).size(8.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MaterialTheme.typography.bodySmall)
                    row.detail?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                Text(kanbanRelativeTime(event.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (events.size > 60) {
            Text(t("%d earlier events hidden", events.size - 60), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun RunsSection(detail: KanbanTaskDetail?) {
    val runs = detail?.runs.orEmpty()
    if (runs.isEmpty()) return
    KanbanSection(t("Runs (%d)", runs.size)) { runs.forEach { RunRow(it) } }
}

@Composable
private fun RunRow(run: KanbanRun) {
    val failed = KanbanRunPresentation.isFailed(run)
    val completed = KanbanRunPresentation.isCompleted(run)
    val color: Color = when {
        failed -> MaterialTheme.colorScheme.error
        completed -> KanbanGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (failed) Icons.Default.Cancel else if (completed) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(16.dp), tint = color)
            Text(KanbanRunPresentation.outcomeLabel(run), style = MaterialTheme.typography.labelLarge, color = if (failed) color else MaterialTheme.colorScheme.onSurface)
            run.stepKey?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Box(Modifier.weight(1f))
            KanbanRunPresentation.durationText(run.startedAt, run.endedAt)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            run.profile?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            run.workerPid?.let { Text(t("pid %s", it.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(Modifier.weight(1f))
            Text(kanbanRelativeTime(run.endedAt ?: run.startedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        val error = run.error
        val summary = run.summary
        if (!error.isNullOrEmpty()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4)
        } else if (!summary.isNullOrEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
    }
}

@Composable
private fun ReassignDialog(profiles: List<String>, current: String?, onDismiss: () -> Unit, onReassign: (String?, (String?) -> Unit) -> Unit) {
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (pending == null) onDismiss() },
        title = { Text(t("Reassign")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(t("Profiles"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                profiles.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = pending == null) {
                            pending = name
                            error = null
                            onReassign(name) { pending = null; error = it }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, modifier = Modifier.weight(1f))
                        if (name == current) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        if (pending == name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                if (!current.isNullOrEmpty()) {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = pending == null) {
                            pending = UNASSIGN
                            error = null
                            onReassign(null) { pending = null; error = it }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("Unassign (parked — won't run)"), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        if (pending == UNASSIGN) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = pending == null) { Text(t("Cancel")) } },
    )
}

private const val UNASSIGN = "__unassign__"
