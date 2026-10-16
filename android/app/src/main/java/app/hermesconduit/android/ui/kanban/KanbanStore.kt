package app.hermesconduit.android.ui.kanban

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.KanbanAutoDescribeResponse
import app.hermesconduit.android.model.KanbanBoard
import app.hermesconduit.android.model.KanbanBoardAdminMessage
import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanBoardMetadata
import app.hermesconduit.android.model.KanbanBoardSlugPolicy
import app.hermesconduit.android.model.KanbanBulkOperationOutcome
import app.hermesconduit.android.model.KanbanBulkResultPolicy
import app.hermesconduit.android.model.KanbanBulkTaskRequest
import app.hermesconduit.android.model.KanbanCreateBoardRequest
import app.hermesconduit.android.model.KanbanCreateTaskRequest
import app.hermesconduit.android.model.KanbanDecomposeResponse
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.model.KanbanModelProviderOption
import app.hermesconduit.android.model.KanbanOrchestrationPatch
import app.hermesconduit.android.model.KanbanOrchestrationSettings
import app.hermesconduit.android.model.KanbanProfile
import app.hermesconduit.android.model.KanbanProject
import app.hermesconduit.android.model.KanbanSpecifyResponse
import app.hermesconduit.android.model.KanbanStatusPresentation
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskDetail
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.KanbanUpdateBoardPatch
import app.hermesconduit.android.model.KanbanWorkerLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.util.Base64

/** Device-local key/value storage for the selected board (scoped per server). */
interface KanbanPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class MemoryKanbanPrefs : KanbanPrefs {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

data class KanbanStoreState(
    val boards: List<KanbanBoardMetadata> = emptyList(),
    val currentServerBoardSlug: String = "default",
    val board: KanbanBoard? = null,
    val profiles: List<KanbanProfile> = emptyList(),
    val projects: List<KanbanProject> = emptyList(),
    val orchestration: KanbanOrchestrationSettings? = null,
    /** "" = follow the server's current board. */
    val selectedBoardSlug: String = "",
    /** Exactly the slug the displayed snapshot was fetched with. */
    val loadedBoardSlug: String? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val mutationErrorMessage: String? = null,
    val liveInvalidation: KanbanEventInvalidation? = null,
    val configurationGeneration: Int = 0,
) {
    val resolvedSelectedBoardSlug: String get() = selectedBoardSlug.ifEmpty { currentServerBoardSlug }
    val isSelectedSnapshotLoaded: Boolean get() = loadedBoardSlug == resolvedSelectedBoardSlug
    val loadedContextStamp: KanbanBoardContextStamp? get() = loadedBoardSlug?.let { KanbanBoardContextStamp(it, configurationGeneration) }
    val selectedBoardMetadata: KanbanBoardMetadata? get() = boards.firstOrNull { it.slug == resolvedSelectedBoardSlug }
    val loadedBoardMetadata: KanbanBoardMetadata? get() = loadedBoardSlug?.let { slug -> boards.firstOrNull { it.slug == slug } }
    val hasDispatcherFallback: Boolean get() = !orchestration?.resolvedDefaultAssignee.isNullOrEmpty()
    fun task(id: String): KanbanTask? = board?.allTasks?.firstOrNull { it.id == id }
}

/**
 * Board state and mutation ownership, ported from KanbanStore.swift.
 *
 * - Every write binds to the LOADED snapshot's board (never the mutable selection).
 * - `configure()` bumps a generation: completions from an older bridge/server are inert.
 * - One mutation owns the UI at a time; each success or failure ends with ONE
 *   superseding reload so partial successes become visible.
 *
 * Calls are expected on one thread (the main dispatcher); suspension points may interleave.
 */
class KanbanStore(
    private val prefs: KanbanPrefs = MemoryKanbanPrefs(),
    private val makeService: (KanbanTransport) -> KanbanService = { KanbanService(it) },
) {
    private val _state = MutableStateFlow(KanbanStoreState())
    val state: StateFlow<KanbanStoreState> = _state
    private val s: KanbanStoreState get() = _state.value

    private var service: KanbanService? = null
    private var transportIdentity: Any? = null
    private var serverIdentity = ""
    private var persistenceKey: String? = null
    private var loadGeneration = 0
    private var activeMutationGeneration: Int? = null

    val configurationGeneration: Int get() = s.configurationGeneration
    fun isCurrentConfiguration(generation: Int) = s.configurationGeneration == generation

    private inline fun set(block: (KanbanStoreState) -> KanbanStoreState) = _state.update(block)

    fun configure(transport: KanbanTransport?, serverIdentity: String = "", identity: Any? = transport) {
        if (transport == null) {
            loadGeneration++
            activeMutationGeneration = null
            service = null
            transportIdentity = null
            set { it.copy(configurationGeneration = it.configurationGeneration + 1, isLoading = false, isMutating = false, board = null, boards = emptyList(), loadedBoardSlug = null) }
            return
        }
        val normalized = serverIdentity.trim('/')
        if (transportIdentity === identity && this.serverIdentity == normalized) return
        loadGeneration++
        activeMutationGeneration = null
        service = makeService(transport)
        transportIdentity = identity
        this.serverIdentity = normalized
        val key = scopedBoardKey(normalized)
        persistenceKey = key
        val selected = prefs.getString(key).orEmpty()
        set {
            KanbanStoreState(
                configurationGeneration = it.configurationGeneration + 1,
                selectedBoardSlug = selected,
                liveInvalidation = it.liveInvalidation,
            )
        }
    }

    suspend fun reload(includeArchived: Boolean = false, superseding: Boolean = false) {
        if (!superseding && s.isLoading) return
        val generation = ++loadGeneration
        val service = service ?: run {
            if (s.board == null) set { it.copy(errorMessage = L10n.tr("Connect to a Hermes dashboard to use Kanban.")) }
            return
        }
        set { it.copy(isLoading = true, errorMessage = if (superseding) null else it.errorMessage) }
        try {
            val boards = service.fetchBoards()
            if (generation != loadGeneration) return
            set { it.copy(boards = boards.boards, currentServerBoardSlug = boards.current) }
            if (s.selectedBoardSlug.isNotEmpty() && boards.boards.none { it.slug == s.selectedBoardSlug }) {
                set { it.copy(selectedBoardSlug = "") }
                persistenceKey?.let(prefs::remove)
            }
            // Pin ONE concrete identity for this load.
            val resolvedSlug = s.selectedBoardSlug.ifEmpty { boards.current }
            coroutineScope {
                val board = async { service.fetchBoard(resolvedSlug, includeArchived) }
                val profiles = async { runCatchingNonCancel { service.fetchProfiles() } }
                val projects = async { runCatchingNonCancel { service.fetchProjects() } }
                val orchestration = async { runCatchingNonCancel { service.fetchOrchestration() } }
                val loaded = board.await()
                val p = profiles.await()
                val pr = projects.await()
                val o = orchestration.await()
                if (generation != loadGeneration) return@coroutineScope
                set {
                    it.copy(
                        board = loaded,
                        loadedBoardSlug = resolvedSlug,
                        profiles = p ?: it.profiles,
                        projects = pr ?: it.projects,
                        orchestration = o ?: it.orchestration,
                        errorMessage = null,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == loadGeneration) set { it.copy(errorMessage = kanbanErrorMessage(e)) }
        } finally {
            if (generation == loadGeneration) set { it.copy(isLoading = false) }
        }
    }

    suspend fun poll(includeArchived: Boolean = false) {
        if (s.isMutating || s.isLoading) return
        reload(includeArchived)
    }

    suspend fun refresh(includeArchived: Boolean = false) {
        if (s.isMutating) return
        reload(includeArchived)
    }

    suspend fun selectBoard(slug: String, includeArchived: Boolean = false) {
        set { it.copy(selectedBoardSlug = slug) }
        persistenceKey?.let { key -> if (slug.isEmpty()) prefs.remove(key) else prefs.putString(key, slug) }
        reload(includeArchived, superseding = true)
    }

    private fun requireService() = service ?: throw KanbanServiceException.NotConnected()
    private val readBoard: String? get() = s.loadedBoardSlug ?: s.selectedBoardSlug.ifEmpty { null }

    suspend fun fetchTaskDetail(id: String): KanbanTaskDetail = requireService().fetchTask(id, readBoard)
    suspend fun fetchTaskLog(id: String, tailBytes: Int = 16_384): KanbanWorkerLog = requireService().fetchTaskLog(id, readBoard, tailBytes)
    suspend fun fetchModelOptions(): List<KanbanModelProviderOption> = requireService().fetchModelOptions()

    // region Task mutations

    suspend fun createTask(request: KanbanCreateTaskRequest, initialStatus: String? = null, includeArchived: Boolean = false): KanbanTask? {
        val context = beginGuards()
        val target = initialStatus ?: if (request.triage) "triage" else "todo"
        return performMutation(context, includeArchived) {
            // Locked lanes are rejected before any POST so no partial task can exist.
            if (!KanbanStatusPresentation.canCreateTask(target)) throw KanbanServiceException.InvalidManualStatus(target)
            val body = if (target == "triage") request.copy(triage = true) else request
            var task = context.service.createTask(body, context.boardSlug)
            if (target != "todo" && target != "triage" && task?.status != target) {
                val taskId = task?.id?.takeIf { it.isNotEmpty() }
                if (taskId == null) {
                    context.service.scheduleDispatcherNudge(context.boardSlug)
                    throw KanbanServiceException.TaskCreatedButMoveFailed(null, target, L10n.tr("Hermes did not return the created task ID."))
                }
                try {
                    task = context.service.updateTask(taskId, context.boardSlug, KanbanTaskPatch(status = target)) ?: task
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    context.service.scheduleDispatcherNudge(context.boardSlug)
                    throw KanbanServiceException.TaskCreatedButMoveFailed(taskId, target, kanbanErrorMessage(e))
                }
            }
            context.service.scheduleDispatcherNudge(context.boardSlug)
            task
        }
    }

    suspend fun updateTask(id: String, patch: KanbanTaskPatch, includeArchived: Boolean = false): KanbanTask? {
        val context = beginGuards()
        return performMutation(context, includeArchived) {
            patch.status?.let { if (!KanbanStatusPresentation.canSelectManually(it)) throw KanbanServiceException.InvalidManualStatus(it) }
            val task = context.service.updateTask(id, context.boardSlug, patch)
            context.service.scheduleDispatcherNudge(context.boardSlug)
            task
        }
    }

    suspend fun deleteTask(id: String, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false) {
        if (expectedContext != null && (!s.isSelectedSnapshotLoaded || s.loadedContextStamp != expectedContext)) {
            throw record(KanbanServiceException.BoardNavigationInProgress())
        }
        val context = beginGuards()
        performMutation(context, includeArchived) {
            context.service.deleteTask(id, context.boardSlug)
            context.service.scheduleDispatcherNudge(context.boardSlug)
        }
    }

    suspend fun addComment(taskId: String, body: String) {
        val context = beginGuards()
        performMutation(context, false) { context.service.addComment(taskId, context.boardSlug, body) }
    }

    suspend fun reassignTask(taskId: String, profile: String?, reclaimFirst: Boolean = true) {
        val context = beginGuards()
        performMutation(context, false) {
            context.service.reassignTask(taskId, context.boardSlug, profile, reclaimFirst)
            context.service.scheduleDispatcherNudge(context.boardSlug)
        }
    }

    suspend fun reclaimTask(taskId: String, reason: String? = null) {
        val context = beginGuards()
        performMutation(context, false) {
            context.service.reclaimTask(taskId, context.boardSlug, reason)
            context.service.scheduleDispatcherNudge(context.boardSlug)
        }
    }

    // endregion

    // region V3A

    /** Server-global: the stamp is validated on its generation only, so a same-server board switch never aborts a save. */
    suspend fun updateOrchestration(patch: KanbanOrchestrationPatch, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false): KanbanOrchestrationSettings {
        rejectIfMutating()
        validateServerContext(expectedContext)
        val context = makeContext()
        return performMutation(context, includeArchived, serverScope = true) {
            val settings = context.service.updateOrchestration(patch)
            if (isCurrentConfiguration(context.generation)) set { it.copy(orchestration = settings) }
            settings
        }
    }

    suspend fun updateProfileDescription(profile: String, description: String, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false) {
        rejectIfMutating()
        validateServerContext(expectedContext)
        val context = makeContext()
        performMutation(context, includeArchived, serverScope = true) { context.service.updateProfileDescription(profile, description) }
    }

    suspend fun autoDescribeProfile(profile: String, overwrite: Boolean = true, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false): KanbanAutoDescribeResponse {
        rejectIfMutating()
        validateServerContext(expectedContext)
        val context = makeContext()
        return performMutation(context, includeArchived, serverScope = true) {
            val outcome = context.service.autoDescribeProfile(profile, overwrite)
            val generated = outcome.description
            if (outcome.ok && generated != null && isCurrentConfiguration(context.generation)) {
                set { st -> st.copy(profiles = st.profiles.map { if (it.name == outcome.profile) it.copy(description = generated, descriptionAuto = true) else it }) }
            }
            outcome
        }
    }

    suspend fun specifyTask(id: String, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false): KanbanSpecifyResponse {
        rejectIfMutating()
        validateContext(expectedContext)
        val context = makeContext()
        return performMutation(context, includeArchived) { context.service.specifyTask(id, context.boardSlug) }
    }

    suspend fun decomposeTask(id: String, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false): KanbanDecomposeResponse {
        rejectIfMutating()
        validateContext(expectedContext)
        val context = makeContext()
        return performMutation(context, includeArchived) { context.service.decomposeTask(id, context.boardSlug) }
    }

    suspend fun nudgeDispatcher(expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false) {
        rejectIfMutating()
        validateContext(expectedContext)
        val context = makeContext()
        performMutation(context, includeArchived) { context.service.nudgeDispatcher(context.boardSlug) }
    }

    // endregion

    // region V3B: boards

    suspend fun createBoard(request: KanbanCreateBoardRequest, expectedContext: KanbanBoardContextStamp? = null, includeArchived: Boolean = false): KanbanBoardMetadata {
        rejectIfMutating()
        validateServerContext(expectedContext)
        if (!KanbanBoardSlugPolicy.isValid(request.slug)) {
            throw record(KanbanServiceException.ActionDeclined(L10n.tr("Invalid board slug — use 1-64 lowercase letters or numbers with hyphens/underscores.")))
        }
        val context = makeContext()
        return performMutation(context, includeArchived, serverScope = true) {
            val created = context.service.createBoard(request)
            // Local selection only: never switch the server pointer.
            if (isCurrentConfiguration(context.generation) && created.slug.isNotEmpty()) {
                set { it.copy(selectedBoardSlug = created.slug) }
                persistenceKey?.let { prefs.putString(it, created.slug) }
            }
            created
        }
    }

    suspend fun updateBoard(slug: String, patch: KanbanUpdateBoardPatch, expectedContext: KanbanBoardContextStamp, includeArchived: Boolean = false): KanbanBoardMetadata {
        rejectIfMutating()
        validateBoardTarget(slug, expectedContext)
        if (patch.isEmpty) {
            return s.boards.firstOrNull { it.slug == slug } ?: throw record(KanbanServiceException.Invalid(L10n.tr("No board changes to save.")))
        }
        val context = makeContext()
        return performMutation(context, includeArchived) {
            val updated = context.service.updateBoard(slug, patch)
            if (isCurrentConfiguration(context.generation)) {
                set { st -> st.copy(boards = st.boards.map { if (it.slug == updated.slug) updated else it }) }
            }
            updated
        }
    }

    /** Archive (never hard delete). One owned reconciliation afterwards; a failed refresh reads as partial success. */
    suspend fun archiveBoard(slug: String, expectedContext: KanbanBoardContextStamp, includeArchived: Boolean = false): Boolean {
        rejectIfMutating()
        if (slug == "default") throw record(KanbanServiceException.ActionDeclined(L10n.tr("Hermes does not allow archiving the default board.")))
        validateBoardTarget(slug, expectedContext)
        val context = makeContext()
        val generation = context.generation
        if (activeMutationGeneration != null) throw record(KanbanServiceException.MutationInProgress())
        activeMutationGeneration = generation
        set { it.copy(isMutating = true, mutationErrorMessage = null) }
        try {
            val result = context.service.archiveBoard(slug)
            if (result.action != "archived") {
                throw KanbanServiceException.Invalid(
                    result.action?.let { L10n.tr("Hermes answered the board delete with an unexpected result: %s", it) }
                        ?: L10n.tr("Hermes answered the board delete without a result."),
                )
            }
            if (isCurrentConfiguration(generation)) {
                reconcileAfterBoardRemoval(generation, includeArchived)?.let { failure ->
                    set { it.copy(errorMessage = KanbanBoardAdminMessage.archiveRefreshFailureMessage(failure)) }
                }
            }
            endMutation(generation)
            return true
        } catch (e: CancellationException) {
            endMutation(generation)
            throw e
        } catch (e: Exception) {
            if (isCurrentConfiguration(generation) && activeMutationGeneration == generation) {
                reload(includeArchived, superseding = true)
                set { it.copy(mutationErrorMessage = kanbanErrorMessage(e)) }
            }
            endMutation(generation)
            throw e
        }
    }

    private suspend fun reconcileAfterBoardRemoval(generation: Int, includeArchived: Boolean): String? {
        val service = service ?: return null
        if (!isCurrentConfiguration(generation)) return null
        return try {
            val response = service.fetchBoards()
            if (!isCurrentConfiguration(generation)) return null
            set { it.copy(boards = response.boards, currentServerBoardSlug = response.current, errorMessage = null) }
            if (s.selectedBoardSlug.isNotEmpty() && response.boards.none { it.slug == s.selectedBoardSlug }) {
                set { it.copy(selectedBoardSlug = "") }
                persistenceKey?.let(prefs::remove)
            }
            reload(includeArchived, superseding = true)
            if (isCurrentConfiguration(generation)) s.errorMessage else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrentConfiguration(generation)) kanbanErrorMessage(e) else null
        }
    }

    // endregion

    // region V3C: bulk

    suspend fun bulkUpdateTasks(ids: List<String>, patch: KanbanBulkTaskRequest, expectedContext: KanbanBoardContextStamp, includeArchived: Boolean = false): KanbanBulkOperationOutcome {
        rejectIfMutating()
        val requested = KanbanBulkResultPolicy.normalizedIds(ids)
        if (requested.isEmpty()) throw record(KanbanServiceException.Invalid(L10n.tr("No tasks selected.")))
        validateContext(expectedContext)
        patch.status?.let { if (!KanbanStatusPresentation.canSelectManually(it)) throw record(KanbanServiceException.InvalidManualStatus(it)) }
        val context = makeContext()
        val request = patch.copy(ids = requested)
        return performMutation(context, includeArchived) {
            KanbanBulkResultPolicy.reconcile(requested, context.service.bulkUpdateTasks(request, context.boardSlug))
        }
    }

    suspend fun bulkDeleteTasks(ids: List<String>, expectedContext: KanbanBoardContextStamp, includeArchived: Boolean = false): KanbanBulkOperationOutcome {
        rejectIfMutating()
        val requested = KanbanBulkResultPolicy.normalizedIds(ids)
        if (requested.isEmpty()) throw record(KanbanServiceException.Invalid(L10n.tr("No tasks selected.")))
        validateContext(expectedContext)
        val context = makeContext()
        return performMutation(context, includeArchived) {
            KanbanBulkResultPolicy.reconcile(requested, context.service.deleteTasksFanout(requested, context.boardSlug))
        }
    }

    // endregion

    // region V3D: live-event refresh boundary

    enum class EventRefreshDisposition { REFRESHED, DEFERRED, STALE }

    /** STALE (zero requests) when the frame's context is no longer the actionable snapshot; DEFERRED while busy. */
    suspend fun refreshFromEvent(expectedContext: KanbanBoardContextStamp, includeArchived: Boolean): EventRefreshDisposition {
        if (!s.isSelectedSnapshotLoaded || s.loadedContextStamp != expectedContext) return EventRefreshDisposition.STALE
        if (s.isMutating || s.isLoading) return EventRefreshDisposition.DEFERRED
        val previousError = s.errorMessage
        val previousMutationError = s.mutationErrorMessage
        try {
            refresh(includeArchived)
        } catch (e: CancellationException) {
            set { it.copy(errorMessage = previousError, mutationErrorMessage = previousMutationError) }
            throw e
        }
        if (!currentCoroutineContext().isActive) {
            set { it.copy(errorMessage = previousError, mutationErrorMessage = previousMutationError) }
            return EventRefreshDisposition.STALE
        }
        return EventRefreshDisposition.REFRESHED
    }

    fun publishLiveInvalidation(invalidation: KanbanEventInvalidation) = set { it.copy(liveInvalidation = invalidation) }

    // endregion

    fun clearMutationError() = set { it.copy(mutationErrorMessage = null) }
    fun showMutationError(error: Throwable) = set { it.copy(mutationErrorMessage = kanbanErrorMessage(error)) }

    suspend fun awaitPendingNudge() {
        service?.awaitPendingNudge()
    }

    // region Ownership plumbing

    private class OperationContext(val service: KanbanService, val boardSlug: String, val generation: Int)

    private fun rejectIfMutating() {
        if (s.isMutating) throw record(KanbanServiceException.MutationInProgress())
    }

    private fun beginGuards(): OperationContext {
        rejectIfMutating()
        return makeContext()
    }

    /** Binds to the LOADED snapshot identity, never the mutable selection. */
    private fun makeContext(): OperationContext {
        val service = service ?: throw record(KanbanServiceException.NotConnected())
        val slug = s.loadedBoardSlug ?: throw record(KanbanServiceException.NotConnected())
        return OperationContext(service, slug, s.configurationGeneration)
    }

    private fun validateContext(expected: KanbanBoardContextStamp?) {
        if (expected == null) return
        if (!s.isSelectedSnapshotLoaded || s.loadedContextStamp != expected) throw record(KanbanServiceException.BoardNavigationInProgress())
    }

    private fun validateBoardTarget(slug: String, expected: KanbanBoardContextStamp) {
        validateContext(expected)
        if (expected.boardSlug != slug) throw record(KanbanServiceException.BoardNavigationInProgress())
    }

    private fun validateServerContext(expected: KanbanBoardContextStamp?) {
        if (expected == null) return
        if (s.configurationGeneration != expected.configurationGeneration) throw record(KanbanServiceException.BoardNavigationInProgress())
    }

    private suspend fun <T> performMutation(
        context: OperationContext,
        includeArchived: Boolean,
        serverScope: Boolean = false,
        operation: suspend () -> T,
    ): T {
        val generation = context.generation
        if (activeMutationGeneration != null) throw record(KanbanServiceException.MutationInProgress())
        // Board-scoped writes refuse to act while the visible snapshot belongs to the previous board.
        if (!serverScope && !s.isSelectedSnapshotLoaded) throw record(KanbanServiceException.BoardNavigationInProgress())
        activeMutationGeneration = generation
        set { it.copy(isMutating = true, mutationErrorMessage = null) }
        try {
            val result = operation()
            if (isCurrentConfiguration(generation)) reload(includeArchived, superseding = true)
            endMutation(generation)
            return result
        } catch (e: CancellationException) {
            endMutation(generation)
            throw e
        } catch (e: Exception) {
            if (isCurrentConfiguration(generation) && activeMutationGeneration == generation) {
                reload(includeArchived, superseding = true)
                set { it.copy(mutationErrorMessage = kanbanErrorMessage(e)) }
            }
            endMutation(generation)
            throw e
        }
    }

    private fun endMutation(generation: Int) {
        if (activeMutationGeneration != generation) return
        activeMutationGeneration = null
        if (isCurrentConfiguration(generation)) set { it.copy(isMutating = false) }
    }

    private fun <E : Exception> record(error: E): E {
        set { it.copy(mutationErrorMessage = kanbanErrorMessage(error)) }
        return error
    }

    // endregion

    companion object {
        const val SELECTED_BOARD_KEY = "conduit.kanbanBoardSlug"

        fun scopedBoardKey(serverIdentity: String): String {
            val token = Base64.getEncoder().encodeToString(serverIdentity.trim('/').toByteArray())
                .replace('+', '-').replace('/', '_').replace("=", "")
            return "$SELECTED_BOARD_KEY.$token"
        }

        private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
