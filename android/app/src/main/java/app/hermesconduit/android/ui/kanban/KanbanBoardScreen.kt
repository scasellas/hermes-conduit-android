package app.hermesconduit.android.ui.kanban

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.BulkStagedSelection
import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanBoardFilterPolicy
import app.hermesconduit.android.model.KanbanBulkAction
import app.hermesconduit.android.model.KanbanBulkDestinationPolicy
import app.hermesconduit.android.model.KanbanBulkOperationOutcome
import app.hermesconduit.android.model.KanbanBulkResultPolicy
import app.hermesconduit.android.model.KanbanBulkSelectionPolicy
import app.hermesconduit.android.model.KanbanBulkStagePolicy
import app.hermesconduit.android.model.KanbanBulkTaskRequest
import app.hermesconduit.android.model.KanbanCardLiveness
import app.hermesconduit.android.model.KanbanColumn
import app.hermesconduit.android.model.KanbanLanePolicy
import app.hermesconduit.android.model.KanbanNudgePolicy
import app.hermesconduit.android.model.KanbanRunningGroupPolicy
import app.hermesconduit.android.model.KanbanShortId
import app.hermesconduit.android.model.KanbanStatusPresentation
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.PendingBulkOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PendingCardDelete(val task: KanbanTask, val stamp: KanbanBoardContextStamp)
private data class PendingBoardArchive(val slug: String, val displayName: String, val stamp: KanbanBoardContextStamp)
private data class BulkNotice(val text: String, val warning: Boolean, val token: Int)

/** The Kanban board: lane chips on phones, horizontally scrolling columns on wide screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanBoardScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { KanbanRuntime.get(context) }
    AttachKanbanRuntime(runtime)
    val store = runtime.store
    val state by store.state.collectAsStateWithLifecycle()
    val connected by runtime.connected.collectAsStateWithLifecycle()
    val includeArchived by runtime.includeArchived.collectAsStateWithLifecycle()
    val settings = context.appSettings
    val groupRunning by settings.bool(AppSettings.KANBAN_GROUP_RUNNING, false).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedLane by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var assigneeFilter by remember { mutableStateOf<String?>(null) }
    var tenantFilter by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingCardDelete?>(null) }
    var pendingBoardArchive by remember { mutableStateOf<PendingBoardArchive?>(null) }
    var nudgeNotice by remember { mutableStateOf<String?>(null) }
    var nudgeToken by remember { mutableIntStateOf(0) }

    // Selection / bulk (V3C).
    var selectionContext by remember { mutableStateOf<KanbanBoardContextStamp?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkToken by remember { mutableIntStateOf(0) }
    var bulkStaged by remember { mutableStateOf<BulkStagedSelection?>(null) }
    var pendingBulkArchive by remember { mutableStateOf<PendingBulkOperation?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<BulkStagedSelection?>(null) }
    var showBulkMove by remember { mutableStateOf(false) }
    var showBulkAssign by remember { mutableStateOf(false) }
    var showBulkPriority by remember { mutableStateOf(false) }
    var showBulkFailures by remember { mutableStateOf(false) }
    var lastBulkOutcome by remember { mutableStateOf<KanbanBulkOperationOutcome?>(null) }
    var bulkNotice by remember { mutableStateOf<BulkNotice?>(null) }

    val isSelectionActive = selectionContext != null
    val isSelectionOwned = KanbanBulkSelectionPolicy.isOwned(selectionContext, state.loadedContextStamp, state.isSelectedSnapshotLoaded)
    val canRunBulk = isSelectionOwned && selectedIds.isNotEmpty() && !bulkBusy

    fun exitSelection() {
        selectionContext = null
        selectedIds = emptySet()
        bulkStaged = null
        pendingBulkArchive = null
        pendingBulkDelete = null
        showBulkMove = false
        showBulkAssign = false
        showBulkPriority = false
        showBulkFailures = false
        lastBulkOutcome = null
        bulkNotice = null
    }

    LaunchedEffect(state.loadedBoardSlug) {
        pendingDelete = null
        pendingBoardArchive = null
        nudgeNotice = null
        nudgeToken++
        showFilters = false
        assigneeFilter = null
        tenantFilter = null
        exitSelection()
    }
    LaunchedEffect(state.configurationGeneration) {
        pendingBoardArchive = null
        exitSelection()
    }
    // Prune ghost ids after every authoritative snapshot.
    LaunchedEffect(state.board) {
        val board = state.board ?: return@LaunchedEffect
        if (selectedIds.isEmpty()) return@LaunchedEffect
        val pruned = KanbanBulkSelectionPolicy.prune(selectedIds, board.allTasks.map { it.id }.toSet())
        if (pruned != selectedIds) {
            selectedIds = pruned
            if (pruned.isEmpty()) exitSelection()
        }
    }

    val visibleColumns = state.board?.let { KanbanBoardFilterPolicy.visibleColumns(it, searchText, assigneeFilter, tenantFilter) }.orEmpty()
    val effectiveLane = KanbanLanePolicy.effectiveSelectedLane(selectedLane, visibleColumns)
    val filtersActive = includeArchived || groupRunning || !assigneeFilter.isNullOrEmpty() || !tenantFilter.isNullOrEmpty()

    fun showBulkNotice(text: String, warning: Boolean) {
        val token = ++bulkToken
        bulkNotice = BulkNotice(text, warning, token)
        scope.launch {
            delay(3_500)
            if (bulkNotice?.token == token) bulkNotice = null
        }
    }

    fun runBulk(operation: PendingBulkOperation) {
        val token = ++bulkToken
        bulkBusy = true
        bulkNotice = null
        lastBulkOutcome = null
        showBulkFailures = false
        val archived = includeArchived
        scope.launch {
            val outcome = try {
                when (val action = operation.action) {
                    is KanbanBulkAction.Move -> store.bulkUpdateTasks(operation.ids, KanbanBulkTaskRequest(operation.ids, status = action.status), operation.context, archived)
                    is KanbanBulkAction.Assign -> store.bulkUpdateTasks(
                        operation.ids, KanbanBulkTaskRequest(operation.ids, assignee = action.profile ?: "", reclaimFirst = true), operation.context, archived,
                    )
                    is KanbanBulkAction.Priority -> store.bulkUpdateTasks(operation.ids, KanbanBulkTaskRequest(operation.ids, priority = action.value), operation.context, archived)
                    KanbanBulkAction.Archive -> store.bulkUpdateTasks(operation.ids, KanbanBulkTaskRequest(operation.ids, archive = true), operation.context, archived)
                    KanbanBulkAction.Delete -> store.bulkDeleteTasks(operation.ids, operation.context, archived)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Top-level failure: the store shows the error; the selection stays for a retry.
                if (bulkToken == token) bulkBusy = false
                return@launch
            }
            if (bulkToken == token) bulkBusy = false
            if (!store.isCurrentConfiguration(operation.context.configurationGeneration)) return@launch
            val keep = KanbanBulkResultPolicy.selectionAfterApplying(outcome, operation.ids.toSet())
            val failed = outcome.failures.isNotEmpty()
            if (keep.isEmpty()) {
                exitSelection()
            } else {
                selectedIds = keep
                lastBulkOutcome = outcome
                if (failed) showBulkFailures = true
            }
            showBulkNotice(KanbanBulkResultPolicy.summary(outcome), failed)
        }
    }

    fun stageBulk(action: KanbanBulkAction) {
        val selection = selectionContext ?: return
        val stamp = state.loadedContextStamp ?: return
        if (!canRunBulk || selection != stamp) return
        bulkNotice = null
        lastBulkOutcome = null
        showBulkFailures = false
        val frozen = BulkStagedSelection(selectedIds.sorted(), stamp)
        bulkStaged = frozen
        when (action) {
            KanbanBulkAction.Delete -> pendingBulkDelete = frozen
            KanbanBulkAction.Archive -> pendingBulkArchive = PendingBulkOperation(frozen.ids, frozen.context, KanbanBulkAction.Archive)
            is KanbanBulkAction.Move -> showBulkMove = true
            is KanbanBulkAction.Assign -> showBulkAssign = true
            is KanbanBulkAction.Priority -> showBulkPriority = true
        }
    }

    fun commitBulkFromStage(build: ((BulkStagedSelection) -> PendingBulkOperation?)?) {
        val staged = bulkStaged ?: return
        bulkStaged = null
        showBulkMove = false
        showBulkAssign = false
        showBulkPriority = false
        val operation = build?.invoke(staged) ?: return
        if (operation.context != state.loadedContextStamp || !state.isSelectedSnapshotLoaded) return
        runBulk(operation)
    }

    fun nudge() {
        val stamp = state.loadedContextStamp ?: return
        if (state.isMutating || !state.isSelectedSnapshotLoaded) return
        scope.launch {
            try {
                store.nudgeDispatcher(stamp, includeArchived)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            val now = store.state.value
            if (!KanbanNudgePolicy.shouldShowNotice(stamp, now.loadedContextStamp, now.isSelectedSnapshotLoaded)) return@launch
            val token = ++nudgeToken
            nudgeNotice = kt("Dispatcher nudged")
            delay(2_000)
            if (nudgeToken == token) nudgeNotice = null
        }
    }

    fun move(task: KanbanTask, status: String) {
        if (task.status == status) return
        scope.launch { runCatching { store.updateTask(task.id, KanbanTaskPatch(status = status), includeArchived) } }
    }

    Scaffold(
        topBar = {
            if (isSelectionActive) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t("%d tasks selected", selectedIds.size), maxLines = 1)
                            if (bulkBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!bulkBusy) exitSelection() }, enabled = !bulkBusy) {
                            Icon(Icons.Default.Close, t("Exit selection mode"))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(t("Kanban"))
                            Text(
                                state.selectedBoardMetadata?.name ?: state.selectedBoardMetadata?.slug ?: t("Board"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back")) } },
                    actions = {
                        BoardPickerButton(state.boards, state.currentServerBoardSlug, state.selectedBoardSlug, state.selectedBoardMetadata?.slug, enabled = !bulkBusy) { slug ->
                            scope.launch { store.selectBoard(slug, includeArchived) }
                        }
                        IconButton(
                            onClick = {
                                val stamp = state.loadedContextStamp ?: return@IconButton
                                selectionContext = stamp
                                selectedIds = emptySet()
                            },
                            enabled = !state.isMutating && state.isSelectedSnapshotLoaded && state.loadedBoardMetadata != null,
                        ) { Icon(Icons.Outlined.CheckCircle, t("Select tasks")) }
                        IconButton(
                            onClick = { scope.launch { store.refresh(includeArchived) } },
                            enabled = !state.isLoading && !state.isMutating,
                        ) { Icon(Icons.Default.Refresh, t("Refresh Kanban board")) }
                        BoardActionsMenu(
                            canEditBoard = state.loadedBoardMetadata != null && !state.isMutating && state.isSelectedSnapshotLoaded,
                            canCreateBoard = !state.isMutating && state.isSelectedSnapshotLoaded,
                            canArchiveBoard = state.loadedBoardSlug != null && state.isSelectedSnapshotLoaded && !state.isMutating && state.loadedBoardSlug != "default",
                            canNudge = !state.isMutating && state.isSelectedSnapshotLoaded,
                            onBoardSettings = { navigate("kanban.board/settings") },
                            onNewBoard = { navigate("kanban.board/new") },
                            onArchiveBoard = {
                                val slug = state.loadedBoardSlug
                                val stamp = state.loadedContextStamp
                                if (slug != null && stamp != null && state.isSelectedSnapshotLoaded) {
                                    pendingBoardArchive = PendingBoardArchive(slug, state.loadedBoardMetadata?.name ?: slug, stamp)
                                }
                            },
                            onOrchestration = { navigate("kanban.orchestration") },
                            onProfiles = { navigate("kanban.profiles") },
                            onNudge = { nudge() },
                        )
                    },
                )
            }
        },
        bottomBar = {
            if (isSelectionActive) {
                BottomAppBar {
                    Text(t("%d tasks selected", selectedIds.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                    TextButton(onClick = { stageBulk(KanbanBulkAction.Move("")) }, enabled = canRunBulk) { Text(t("Move")) }
                    TextButton(onClick = { stageBulk(KanbanBulkAction.Assign(null)) }, enabled = canRunBulk) { Text(t("Assign")) }
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { moreOpen = true }, enabled = canRunBulk) { Text(t("More")) }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text(t("Set Priority…")) }, onClick = { moreOpen = false; stageBulk(KanbanBulkAction.Priority(0)) })
                            DropdownMenuItem(text = { Text(t("Archive…")) }, onClick = { moreOpen = false; stageBulk(KanbanBulkAction.Archive) })
                            DropdownMenuItem(
                                text = { Text(t("Delete…"), color = MaterialTheme.colorScheme.error) },
                                onClick = { moreOpen = false; stageBulk(KanbanBulkAction.Delete) },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionActive && state.board != null) {
                FloatingActionButton(onClick = {
                    if (state.isMutating || !state.isSelectedSnapshotLoaded) return@FloatingActionButton
                    navigate("kanban.new/" + Uri.encode(KanbanLanePolicy.newTaskInitialStatus(effectiveLane)))
                }) { Icon(Icons.Default.Add, t("New Kanban task")) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isSelectionActive) {
                SearchAndFilterBar(searchText, { searchText = it }, filtersActive, enabled = state.isSelectedSnapshotLoaded) { showFilters = true }
            }
            val board = state.board
            when {
                !connected && board == null -> EmptyMessage(t("Kanban Unavailable"), t("Connect to a Hermes dashboard to use Kanban."))
                state.isLoading && board == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(t("Loading board…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.errorMessage != null && board == null -> EmptyMessage(t("Kanban Unavailable"), state.errorMessage)
                board == null -> EmptyMessage(t("No Board"), null)
                visibleColumns.isEmpty() -> EmptyMessage(t("No Columns"), null)
                else -> {
                    Notices(
                        nudgeNotice = nudgeNotice,
                        bulkNotice = bulkNotice,
                        mutationError = state.mutationErrorMessage,
                        refreshError = state.errorMessage,
                        onDismissError = store::clearMutationError,
                    )
                    if (!state.isSelectedSnapshotLoaded) {
                        Text(
                            t("Loading %s…", state.selectedBoardMetadata?.name ?: state.resolvedSelectedBoardSlug),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    val cardFor: @Composable (KanbanTask) -> Unit = { task ->
                        val inert = isSelectionActive || bulkBusy
                        KanbanCard(
                            task = task,
                            hasDispatcherFallback = state.hasDispatcherFallback,
                            menusEnabled = !inert && state.isSelectedSnapshotLoaded,
                            selection = if (isSelectionActive) task.id in selectedIds else null,
                            onOpen = {
                                if (isSelectionActive) {
                                    if (isSelectionOwned && !bulkBusy) {
                                        selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id
                                        if (selectedIds.isEmpty()) exitSelection()
                                    }
                                } else {
                                    navigate("kanban.task/" + Uri.encode(task.id))
                                }
                            },
                            onLongPress = {
                                if (!isSelectionActive && !state.isMutating && state.isSelectedSnapshotLoaded) {
                                    state.loadedContextStamp?.let {
                                        selectionContext = it
                                        selectedIds = setOf(task.id)
                                    }
                                }
                            },
                            onMove = { move(task, it) },
                            onArchive = { if (task.status != "archived") move(task, "archived") },
                            onDelete = {
                                val stamp = state.loadedContextStamp
                                if (stamp != null && state.isSelectedSnapshotLoaded) pendingDelete = PendingCardDelete(task, stamp)
                            },
                        )
                    }
                    PullToRefreshBox(
                        isRefreshing = state.isLoading && state.board != null,
                        onRefresh = { scope.launch { store.refresh(includeArchived) } },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            if (maxWidth >= 720.dp) {
                                ColumnBoard(visibleColumns, groupRunning, cardFor)
                            } else {
                                LaneBoard(visibleColumns, effectiveLane, enabled = !bulkBusy, onSelectLane = { selectedLane = it }, groupRunning, cardFor)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        KanbanFilterSheet(
            assignees = state.board?.assignees.orEmpty(),
            tenants = state.board?.tenants.orEmpty(),
            includeArchived = includeArchived,
            assigneeFilter = assigneeFilter,
            tenantFilter = tenantFilter,
            groupRunning = groupRunning,
            onIncludeArchived = { runtime.includeArchived.value = it },
            onAssignee = { assigneeFilter = it },
            onTenant = { tenantFilter = it },
            onGroupRunning = { settings.set(AppSettings.KANBAN_GROUP_RUNNING, it) },
            onReset = {
                assigneeFilter = null
                tenantFilter = null
                runtime.includeArchived.value = false
            },
            onDismiss = { showFilters = false },
        )
    }

    pendingDelete?.let { staged ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(t("Delete this task?")) },
            text = { Text(t("This permanently removes the task from the selected Hermes board.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    // Fail closed: a confirmation staged for another board/server is discarded.
                    if (state.isSelectedSnapshotLoaded && staged.stamp == state.loadedContextStamp) {
                        scope.launch { runCatching { store.deleteTask(staged.task.id, staged.stamp, includeArchived) } }
                    }
                }) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(t("Cancel")) } },
        )
    }

    pendingBoardArchive?.let { staged ->
        AlertDialog(
            onDismissRequest = { pendingBoardArchive = null },
            title = { Text(t("Archive Board?")) },
            text = {
                Text(t("Archive “%s”?\nThe board will be removed from the active board list. Its tasks are not being permanently deleted.", staged.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBoardArchive = null
                    scope.launch { runCatching { store.archiveBoard(staged.slug, staged.stamp, includeArchived) } }
                }) { Text(t("Archive"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBoardArchive = null }) { Text(t("Cancel")) } },
        )
    }

    pendingBulkArchive?.let { staged ->
        AlertDialog(
            onDismissRequest = { pendingBulkArchive = null; bulkStaged = null },
            title = { Text(t("Archive %d Tasks?", staged.ids.size)) },
            text = { Text(t("They will be moved out of the active board view. Tasks are not being permanently deleted.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkArchive = null
                    bulkStaged = null
                    runBulk(PendingBulkOperation(staged.ids, staged.context, KanbanBulkAction.Archive))
                }) { Text(t("Archive %d", staged.ids.size), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBulkArchive = null; bulkStaged = null }) { Text(t("Cancel")) } },
        )
    }

    pendingBulkDelete?.let { staged ->
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = null; bulkStaged = null },
            title = { Text(t("Delete %d Tasks?", staged.ids.size)) },
            text = { Text(t("This will permanently delete the selected tasks. This action cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkDelete = null
                    bulkStaged = null
                    runBulk(PendingBulkOperation(staged.ids, staged.context, KanbanBulkAction.Delete))
                }) { Text(t("Delete %d", staged.ids.size), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingBulkDelete = null; bulkStaged = null }) { Text(t("Cancel")) } },
        )
    }

    if (showBulkMove) {
        ListDialog(
            title = t("Move %d Tasks", bulkStaged?.ids?.size ?: 0),
            onDismiss = { commitBulkFromStage(null) },
        ) {
            KanbanBulkDestinationPolicy.moveDestinations().forEach { destination ->
                ListDialogRow(destination.displayName, kanbanStatusIcon(destination.rawValue), kanbanTint(destination.tint)) {
                    commitBulkFromStage { PendingBulkOperation(it.ids, it.context, KanbanBulkAction.Move(destination.rawValue)) }
                }
            }
        }
    }

    if (showBulkAssign) {
        ListDialog(title = t("Assign %d Tasks", bulkStaged?.ids?.size ?: 0), onDismiss = { commitBulkFromStage(null) }) {
            if (state.profiles.isEmpty()) {
                Text(t("No profiles on this server yet."), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            state.profiles.forEach { profile ->
                ListDialogRow(profile.name) { commitBulkFromStage { PendingBulkOperation(it.ids, it.context, KanbanBulkAction.Assign(profile.name)) } }
            }
            HorizontalDivider()
            ListDialogRow(t("Unassign")) { commitBulkFromStage { PendingBulkOperation(it.ids, it.context, KanbanBulkAction.Assign(null)) } }
        }
    }

    if (showBulkPriority) {
        BulkPriorityDialog(
            count = bulkStaged?.ids?.size ?: 0,
            enabled = bulkStaged != null,
            onCancel = { commitBulkFromStage(null) },
            onApply = { value ->
                commitBulkFromStage { staged ->
                    KanbanBulkStagePolicy.priorityOperation(staged, value, state.loadedContextStamp, state.isSelectedSnapshotLoaded)
                }
            },
        )
    }

    if (showBulkFailures) {
        val outcome = lastBulkOutcome
        AlertDialog(
            onDismissRequest = { showBulkFailures = false },
            title = { Text(t("Failed Tasks")) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outcome?.failures.orEmpty()) { failure ->
                        Column {
                            Text(t("Task %s", failure.id), style = MaterialTheme.typography.titleSmall)
                            Text(failure.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBulkFailures = false }) { Text(t("Done")) } },
        )
    }
}

@Composable
private fun EmptyMessage(title: String, detail: String?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notices(nudgeNotice: String?, bulkNotice: BulkNotice?, mutationError: String?, refreshError: String?, onDismissError: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (nudgeNotice != null) KanbanNotice(nudgeNotice, KanbanGreen, Icons.Default.CheckCircle)
        if (bulkNotice != null) {
            KanbanNotice(bulkNotice.text, if (bulkNotice.warning) KanbanOrange else KanbanGreen, if (bulkNotice.warning) Icons.Default.Warning else Icons.Default.CheckCircle)
        }
        if (mutationError != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mutationError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismissError) { Icon(Icons.Default.Close, t("Dismiss Kanban error"), tint = MaterialTheme.colorScheme.error) }
            }
        } else if (refreshError != null) {
            Text(t("Refresh failed: %s", refreshError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchAndFilterBar(text: String, onText: (String) -> Unit, filtersActive: Boolean, enabled: Boolean, onFilters: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            placeholder = { Text(t("Search tasks")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (text.isNotEmpty()) ({ IconButton(onClick = { onText("") }) { Icon(Icons.Default.Close, t("Cancel")) } }) else null,
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onFilters, enabled = enabled) {
            Box {
                Icon(
                    Icons.Default.FilterList,
                    if (filtersActive) t("Filters active — open filters") else t("Open filters"),
                    tint = if (filtersActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (filtersActive) Badge(Modifier.align(Alignment.TopEnd).size(8.dp))
            }
        }
    }
}

@Composable
private fun BoardPickerButton(
    boards: List<app.hermesconduit.android.model.KanbanBoardMetadata>,
    serverCurrent: String,
    selectedSlug: String,
    resolvedSlug: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Default.Dashboard, t("Choose Kanban board")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(t("Server current (%s)", serverCurrent), fontWeight = if (selectedSlug.isEmpty()) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { open = false; onSelect("") },
            )
            boards.forEach { board ->
                DropdownMenuItem(
                    text = { Text(board.name ?: board.slug, fontWeight = if (selectedSlug.isNotEmpty() && board.slug == resolvedSlug) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (selectedSlug.isNotEmpty() && board.slug == resolvedSlug) ({ Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; onSelect(board.slug) },
                )
            }
        }
    }
}

@Composable
private fun BoardActionsMenu(
    canEditBoard: Boolean,
    canCreateBoard: Boolean,
    canArchiveBoard: Boolean,
    canNudge: Boolean,
    onBoardSettings: () -> Unit,
    onNewBoard: () -> Unit,
    onArchiveBoard: () -> Unit,
    onOrchestration: () -> Unit,
    onProfiles: () -> Unit,
    onNudge: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, t("Kanban board actions")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("Board Settings…")) }, enabled = canEditBoard, onClick = { open = false; onBoardSettings() })
            DropdownMenuItem(text = { Text(t("New Board…")) }, enabled = canCreateBoard, onClick = { open = false; onNewBoard() })
            DropdownMenuItem(text = { Text(t("Archive Board…")) }, enabled = canArchiveBoard, onClick = { open = false; onArchiveBoard() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(t("Orchestration Settings…")) }, onClick = { open = false; onOrchestration() })
            DropdownMenuItem(text = { Text(t("Profiles…")) }, onClick = { open = false; onProfiles() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(t("Nudge Dispatcher")) }, enabled = canNudge, onClick = { open = false; onNudge() })
        }
    }
}

@Composable
private fun LaneChip(column: KanbanColumn, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val presentation = KanbanStatusPresentation.forStatus(column.name)
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(presentation.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(column.tasks.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (presentation.isBackendControlled) Icon(Icons.Default.Lock, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.outline)
            }
        },
        leadingIcon = { Icon(kanbanStatusIcon(column.name), null, Modifier.size(16.dp), tint = kanbanTint(presentation.tint)) },
    )
}

@Composable
private fun LaneBoard(
    columns: List<KanbanColumn>,
    effectiveLane: String?,
    enabled: Boolean,
    onSelectLane: (String) -> Unit,
    groupRunning: Boolean,
    card: @Composable (KanbanTask) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(columns, key = { it.name }) { column -> LaneChip(column, column.name == effectiveLane, enabled) { onSelectLane(column.name) } }
        }
        val column = columns.firstOrNull { it.name == effectiveLane } ?: return@Column
        TaskList(column, groupRunning, card, Modifier.fillMaxSize(), PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp))
    }
}

@Composable
private fun ColumnBoard(columns: List<KanbanColumn>, groupRunning: Boolean, card: @Composable (KanbanTask) -> Unit) {
    Row(
        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        columns.forEach { column ->
            val presentation = KanbanStatusPresentation.forStatus(column.name)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.width(280.dp).fillMaxHeight().padding(vertical = 4.dp),
            ) {
                Column {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(kanbanStatusIcon(column.name), null, Modifier.size(16.dp), tint = kanbanTint(presentation.tint))
                        Text(presentation.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(column.tasks.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (presentation.isBackendControlled) Icon(Icons.Default.Lock, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    TaskList(column, groupRunning, card, Modifier.fillMaxSize(), PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskList(column: KanbanColumn, groupRunning: Boolean, card: @Composable (KanbanTask) -> Unit, modifier: Modifier, padding: PaddingValues) {
    LazyColumn(modifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (KanbanRunningGroupPolicy.shouldApply(column.name, groupRunning)) {
            KanbanRunningGroupPolicy.group(column.tasks).forEach { group ->
                item(key = "group-" + group.key) {
                    Row(Modifier.padding(top = 6.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(group.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(group.tasks.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(group.tasks, key = { it.id }) { card(it) }
            }
        } else {
            items(column.tasks, key = { it.id }) { card(it) }
        }
        if (column.tasks.isEmpty()) {
            item { Text(t("No tasks"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KanbanCard(
    task: KanbanTask,
    hasDispatcherFallback: Boolean,
    menusEnabled: Boolean,
    selection: Boolean?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMove: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val presentation = KanbanStatusPresentation.forStatus(task.status)
    val tint = kanbanTint(presentation.tint)
    val selected = selection == true
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else tint.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(tint))
                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when {
                    selection != null -> Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    menusEnabled -> CardMenu(task, onMove, onArchive, onDelete, onOpen) { text, notice -> kanbanCopy(context, text, notice) }
                }
            }
            val summary = task.latestSummary?.takeIf { it.isNotEmpty() } ?: task.body?.takeIf { it.isNotEmpty() }
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            CardFooter(task, hasDispatcherFallback)
        }
    }
}

@Composable
private fun CardMenu(
    task: KanbanTask,
    onMove: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    copy: (String, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreHoriz, t("Task actions")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("Open")) }, onClick = { open = false; onOpen() })
            HorizontalDivider()
            Text(t("Move to"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            KanbanStatusPresentation.manuallySelectableStatuses.filter { it.rawValue != task.status }.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.displayName) },
                    leadingIcon = { Icon(kanbanStatusIcon(status.rawValue), null, tint = kanbanTint(status.tint)) },
                    onClick = { open = false; onMove(status.rawValue) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(t("Copy Task ID")) }, onClick = { open = false; copy(task.id, kt("Task ID copied")) })
            DropdownMenuItem(text = { Text(t("Copy Task Title")) }, onClick = { open = false; copy(task.title, kt("Title copied")) })
            HorizontalDivider()
            if (task.status != "archived") DropdownMenuItem(text = { Text(t("Archive")) }, onClick = { open = false; onArchive() })
            DropdownMenuItem(text = { Text(t("Delete…"), color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun CardFooter(task: KanbanTask, hasDispatcherFallback: Boolean) {
    val muted = MaterialTheme.colorScheme.outline
    val style = MaterialTheme.typography.labelSmall
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val liveness = KanbanCardLiveness.state(task)
        if (liveness == KanbanCardLiveness.State.STALE) {
            FooterItem(Icons.Outlined.HeartBroken, t("no heartbeat"), KanbanOrange)
        } else if (task.status == "running") {
            KanbanCardLiveness.elapsedText(task.startedAt)?.let { FooterItem(Icons.Default.Timer, it, KanbanGreen) }
        }
        if (task.status == "ready" && task.assignee.isNullOrEmpty() && !hasDispatcherFallback) {
            FooterItem(Icons.Outlined.FlashOff, t("won't run"), KanbanOrange)
        }
        task.progress?.takeIf { it.total > 0 }?.let { FooterItem(Icons.Default.Checklist, "${it.done}/${it.total}", muted) }
        task.commentCount?.takeIf { it > 0 }?.let { FooterItem(Icons.Default.ChatBubbleOutline, it.toString(), muted) }
        val links = (task.linkCounts?.parents ?: 0) + (task.linkCounts?.children ?: 0)
        if (links > 0) FooterItem(Icons.Outlined.AccountTree, links.toString(), muted)
        task.priority?.takeIf { it > 0 }?.let { FooterItem(Icons.Default.Flag, it.toString(), muted) }
        Spacer(Modifier.weight(1f))
        task.warnings?.takeIf { it.count > 0 }?.let { FooterItem(Icons.Default.Error, it.count.toString(), MaterialTheme.colorScheme.error) }
        Text(KanbanShortId.of(task.id), style = style, fontFamily = FontFamily.Monospace, color = muted)
    }
}

@Composable
private fun FooterItem(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ListDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}

@Composable
private fun ListDialogRow(title: String, icon: ImageVector? = null, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Integer stepper for bulk priority: any integer is valid upstream. */
@Composable
private fun BulkPriorityDialog(count: Int, enabled: Boolean, onCancel: () -> Unit, onApply: (Int) -> Unit) {
    var text by remember { mutableStateOf("0") }
    val value = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (count == 1) t("Set Priority") else t("Set Priority (%d tasks)", count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { text = ((value ?: 0) - 1).toString() }, enabled = enabled && (value ?: 0) > Int.MIN_VALUE) {
                        Icon(Icons.Default.Remove, t("Decrease priority"))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '-' } },
                        singleLine = true,
                        label = { Text(t("Priority")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { text = ((value ?: 0) + 1).toString() }, enabled = enabled && (value ?: 0) < Int.MAX_VALUE) {
                        Icon(Icons.Default.Add, t("Increase priority"))
                    }
                }
                Text(t("Any integer is valid; Hermes stores it verbatim (no bounds upstream)."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onApply) }, enabled = enabled && value != null) { Text(t("Apply to %d", count)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(t("Cancel")) } },
    )
}
