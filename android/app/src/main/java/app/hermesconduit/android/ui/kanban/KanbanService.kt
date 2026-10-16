package app.hermesconduit.android.ui.kanban

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.KanbanAutoDescribeResponse
import app.hermesconduit.android.model.KanbanBoard
import app.hermesconduit.android.model.KanbanBoardMetadata
import app.hermesconduit.android.model.KanbanBoardsResponse
import app.hermesconduit.android.model.KanbanBulkTaskRequest
import app.hermesconduit.android.model.KanbanBulkTaskResult
import app.hermesconduit.android.model.KanbanCreateBoardRequest
import app.hermesconduit.android.model.KanbanCreateTaskRequest
import app.hermesconduit.android.model.KanbanDecodeException
import app.hermesconduit.android.model.KanbanDecomposeResponse
import app.hermesconduit.android.model.KanbanDeleteBoardResult
import app.hermesconduit.android.model.KanbanModelProviderOption
import app.hermesconduit.android.model.KanbanOrchestrationPatch
import app.hermesconduit.android.model.KanbanOrchestrationSettings
import app.hermesconduit.android.model.KanbanProfile
import app.hermesconduit.android.model.KanbanProject
import app.hermesconduit.android.model.KanbanSpecifyResponse
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskDetail
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.KanbanUpdateBoardPatch
import app.hermesconduit.android.model.KanbanWorkerLog
import app.hermesconduit.android.model.strictString
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** The dashboard's JSON transport, abstracted so the service and store are unit-testable. */
interface KanbanTransport {
    suspend fun get(path: String, query: Map<String, String?> = emptyMap()): JsonObject
    suspend fun send(method: String, path: String, body: JsonElement?, query: Map<String, String?> = emptyMap()): JsonObject
}

/** Production transport: the shared [HermesConnection] REST client (re-logs in on an expired session). */
class HermesKanbanTransport(private val hermes: HermesConnection) : KanbanTransport {
    override suspend fun get(path: String, query: Map<String, String?>) = hermes.rest { it.getJson(path, query) }
    override suspend fun send(method: String, path: String, body: JsonElement?, query: Map<String, String?>) =
        hermes.rest { it.sendJson(method, path, body, query) }
}

sealed class KanbanServiceException(message: String) : IOException(message) {
    class EmptyTaskId : KanbanServiceException(L10n.tr("Hermes returned a Kanban task without an ID."))
    class InvalidManualStatus(val status: String) : KanbanServiceException(
        if (status == "running") L10n.tr("Hermes controls Running; use the dispatcher/claim path instead of setting it manually.")
        else L10n.tr("Hermes does not allow %s as a manual Kanban destination.", status),
    )
    class TaskCreatedButMoveFailed(val taskId: String?, val targetStatus: String, reason: String) : KanbanServiceException(
        L10n.tr(
            "The task was created%s, but Hermes could not move it to %s. It was not duplicated; close this form and refresh the board. %s",
            taskId?.let { L10n.tr(" (task %s)", it) } ?: "", targetStatus, reason,
        ),
    )
    class MutationInProgress : KanbanServiceException(L10n.tr("Another Kanban change is still being saved."))
    class BoardNavigationInProgress : KanbanServiceException(L10n.tr("Still switching boards. Try again once the new board finishes loading."))
    class InvalidQueryParameter(name: String) : KanbanServiceException(
        L10n.tr("Could not build a safe Hermes Kanban request (invalid %s). The operation was cancelled before any data changed.", name),
    )
    class NotConnected : KanbanServiceException(L10n.tr("Kanban is not connected."))
    class Invalid(message: String) : KanbanServiceException(message)

    /** HTTP 200 with `{ok:false, reason}`: shown verbatim, never translated into a generic failure. */
    class ActionDeclined(val reason: String) : KanbanServiceException(reason.ifEmpty { L10n.tr("Hermes declined the action.") })
}

/** A user-facing message for any error (the iOS `UserFacingError.message`). */
fun kanbanErrorMessage(error: Throwable): String = when (error) {
    is DashboardHttpException -> error.message ?: L10n.tr("Something went wrong")
    else -> error.message?.takeIf { it.isNotBlank() } ?: L10n.tr("Something went wrong")
}

/**
 * Typed client for the dashboard Kanban plugin. Hermes Kanban is a shared,
 * cross-profile board, so no profile query is ever sent. Board selection is a
 * `?board=` query and never moves the server-wide current-board pointer.
 */
class KanbanService(
    private val transport: KanbanTransport,
    private val nudgeScope: CoroutineScope? = null,
    private val nudgeDebounceMs: Long = DISPATCHER_NUDGE_DEBOUNCE_MS,
) {
    private var pendingNudge: Job? = null

    suspend fun fetchBoards(): KanbanBoardsResponse = decode { KanbanBoardsResponse.parse(transport.get("$NS/boards")) }

    suspend fun fetchBoard(slug: String?, includeArchived: Boolean): KanbanBoard {
        val params = if (includeArchived) mapOf("include_archived" to "true") else emptyMap()
        return decode { KanbanBoard.parse(transport.get("$NS/board", withBoard(slug, params))) }
    }

    suspend fun fetchTask(id: String, board: String?): KanbanTaskDetail {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        return decode { KanbanTaskDetail.parse(transport.get("$NS/tasks/${segment(id)}", withBoard(board))) }
    }

    suspend fun fetchTaskLog(id: String, board: String?, tailBytes: Int = 16_384): KanbanWorkerLog {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val query = withBoard(board, mapOf("tail" to maxOf(1, tailBytes).toString()))
        return decode { KanbanWorkerLog.parse(transport.get("$NS/tasks/${segment(id)}/log", query)) }
    }

    suspend fun fetchProfiles(): List<KanbanProfile> = decode {
        val rows = transport.get("$NS/profiles")["profiles"] as? JsonArray ?: throw KanbanDecodeException()
        rows.mapNotNull { KanbanProfile.parse(it) }
    }

    suspend fun fetchProjects(): List<KanbanProject> = decode {
        val rows = transport.get("$NS/projects")["projects"] as? JsonArray ?: throw KanbanDecodeException()
        rows.mapNotNull { KanbanProject.parse(it) }
    }

    suspend fun fetchOrchestration(): KanbanOrchestrationSettings = decode { KanbanOrchestrationSettings.parse(transport.get("$NS/orchestration")) }

    /** Unusable rows (empty slug or no models) are dropped so every caller sees an offerable catalog. */
    suspend fun fetchModelOptions(): List<KanbanModelProviderOption> =
        decode { KanbanModelProviderOption.parseList(transport.get("$NS/model-options")) }.filter { it.slug.isNotEmpty() && it.models.isNotEmpty() }

    suspend fun createTask(request: KanbanCreateTaskRequest, board: String?): KanbanTask? {
        val response = transport.send("POST", "$NS/tasks", request.toJson(), withBoard(board))
        return KanbanTask.parse(response["task"])
    }

    suspend fun updateTask(id: String, board: String?, patch: KanbanTaskPatch): KanbanTask? {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val response = transport.send("PATCH", "$NS/tasks/${segment(id)}", patch.toJson(), withBoard(board))
        return KanbanTask.parse(response["task"])
    }

    suspend fun deleteTask(id: String, board: String?) {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        transport.send("DELETE", "$NS/tasks/${segment(id)}", null, withBoard(board))
    }

    suspend fun addComment(taskId: String, board: String?, body: String, author: String = "conduit") {
        if (taskId.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        transport.send(
            "POST", "$NS/tasks/${segment(taskId)}/comments",
            buildJsonObject { put("author", author); put("body", trimmed) }, withBoard(board),
        )
    }

    suspend fun reassignTask(taskId: String, board: String?, profile: String?, reclaimFirst: Boolean = true, reason: String? = null) {
        if (taskId.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val body = buildJsonObject {
            profile?.let { put("profile", it) }
            put("reclaim_first", reclaimFirst)
            reason?.let { put("reason", it) }
        }
        transport.send("POST", "$NS/tasks/${segment(taskId)}/reassign", body, withBoard(board))
    }

    suspend fun reclaimTask(taskId: String, board: String?, reason: String? = null) {
        if (taskId.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        transport.send("POST", "$NS/tasks/${segment(taskId)}/reclaim", buildJsonObject { reason?.let { put("reason", it) } }, withBoard(board))
    }

    // Server-global endpoints (no board query).

    suspend fun updateOrchestration(patch: KanbanOrchestrationPatch): KanbanOrchestrationSettings =
        decode { KanbanOrchestrationSettings.parse(transport.send("PUT", "$NS/orchestration", patch.toJson())) }

    suspend fun updateProfileDescription(profile: String, description: String) {
        transport.send("PATCH", "$NS/profiles/${segment(profile)}", buildJsonObject { put("description", description) })
    }

    /** Persists immediately; a non-ok outcome is not an HTTP error (reason rendered inline). */
    suspend fun autoDescribeProfile(profile: String, overwrite: Boolean = true): KanbanAutoDescribeResponse =
        decode { KanbanAutoDescribeResponse.parse(transport.send("POST", "$NS/profiles/${segment(profile)}/describe-auto", buildJsonObject { put("overwrite", overwrite) })) }

    suspend fun specifyTask(id: String, board: String?, author: String = "conduit"): KanbanSpecifyResponse {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val outcome = decode {
            KanbanSpecifyResponse.parse(transport.send("POST", "$NS/tasks/${segment(id)}/specify", buildJsonObject { put("author", author) }, withBoard(board)))
        }
        if (!outcome.ok) throw KanbanServiceException.ActionDeclined(outcome.reason ?: L10n.tr("Hermes declined to specify this task."))
        return outcome
    }

    suspend fun decomposeTask(id: String, board: String?, author: String = "conduit"): KanbanDecomposeResponse {
        if (id.isEmpty()) throw KanbanServiceException.EmptyTaskId()
        val outcome = decode {
            KanbanDecomposeResponse.parse(transport.send("POST", "$NS/tasks/${segment(id)}/decompose", buildJsonObject { put("author", author) }, withBoard(board)))
        }
        if (!outcome.ok) throw KanbanServiceException.ActionDeclined(outcome.reason ?: L10n.tr("Hermes declined to decompose this task."))
        return outcome
    }

    /** Immediate POST /dispatch for the captured board (board menu action). */
    suspend fun nudgeDispatcher(board: String?) {
        transport.send("POST", "$NS/dispatch", JsonObject(emptyMap()), withBoard(board))
    }

    /**
     * Fire-and-forget acceleration hint after task writes: debounced (400 ms),
     * coalesced, board captured now, failures swallowed (the backend tick is the fallback).
     */
    fun scheduleDispatcherNudge(board: String?) {
        val scope = nudgeScope ?: return
        pendingNudge?.cancel()
        val captured = board
        pendingNudge = scope.launch {
            delay(nudgeDebounceMs)
            try {
                transport.send("POST", "$NS/dispatch", JsonObject(emptyMap()), withBoard(captured))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Test seam: waits for a scheduled nudge without cancelling it. */
    suspend fun awaitPendingNudge() {
        pendingNudge?.join()
    }

    suspend fun createBoard(request: KanbanCreateBoardRequest): KanbanBoardMetadata =
        decode { KanbanBoardMetadata.parse(transport.send("POST", "$NS/boards", request.toJson())["board"]) ?: throw KanbanDecodeException() }

    suspend fun updateBoard(slug: String, patch: KanbanUpdateBoardPatch): KanbanBoardMetadata =
        decode { KanbanBoardMetadata.parse(transport.send("PATCH", "$NS/boards/${segment(slug)}", patch.toJson())["board"]) ?: throw KanbanDecodeException() }

    /** DELETE without `?delete=true` archives the board (recoverable). Hard delete is never sent. */
    suspend fun archiveBoard(slug: String): KanbanDeleteBoardResult {
        val response = transport.send("DELETE", "$NS/boards/${segment(slug)}", null)
        val result = response["result"] as? JsonObject ?: throw KanbanDecodeException()
        return KanbanDeleteBoardResult(result["action"].strictString, result["slug"].strictString, result["new_path"].strictString)
    }

    suspend fun bulkUpdateTasks(request: KanbanBulkTaskRequest, board: String?): List<KanbanBulkTaskResult> =
        KanbanBulkTaskResult.parseList(transport.send("POST", "$NS/tasks/bulk", request.toJson(), withBoard(board)))

    /** No bulk-delete route exists upstream: one DELETE per id (at most 8 in flight); every id settles. */
    suspend fun deleteTasksFanout(ids: List<String>, board: String?): List<KanbanBulkTaskResult> {
        val collected = mutableListOf<KanbanBulkTaskResult>()
        for (chunk in ids.chunked(8)) {
            collected += coroutineScope {
                chunk.map { id ->
                    async {
                        try {
                            transport.send("DELETE", "$NS/tasks/${segment(id)}", null, withBoard(board))
                            KanbanBulkTaskResult(id, true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            KanbanBulkTaskResult(id, false, kanbanErrorMessage(e))
                        }
                    }
                }.awaitAll()
            }
        }
        return collected
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: KanbanDecodeException) {
        throw KanbanServiceException.Invalid(e.message ?: "")
    }

    private fun withBoard(slug: String?, params: Map<String, String> = emptyMap()): Map<String, String?> {
        val values = LinkedHashMap<String, String?>(params)
        if (!slug.isNullOrEmpty()) values["board"] = slug
        return values
    }

    /** Dot segments or slashes would retarget the request at another route. */
    private fun segment(value: String): String {
        if (value == "." || value == ".." || '/' in value || value.isEmpty()) throw KanbanServiceException.InvalidQueryParameter("id")
        return value
    }

    companion object {
        const val NS = "api/plugins/kanban"
        const val DISPATCHER_NUDGE_DEBOUNCE_MS = 400L
    }
}
