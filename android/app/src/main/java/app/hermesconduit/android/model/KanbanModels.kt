package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

// Wire models for the Hermes dashboard Kanban plugin (`api/plugins/kanban`),
// ported from Conduit/Models/KanbanModels.swift. Every parser is tolerant:
// a hostile or partial row degrades field by field instead of throwing.

// region Lossy JSON helpers

/** A JSON string only (Swift `decode(String.self)`): numbers and bools are not coerced. */
internal val JsonElement?.strictString: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || !p.isString) return null
        return p.content
    }

/** String, or a number rendered as text (Swift `decodeLossyString`). Bools are rejected. */
internal val JsonElement?.lossyString: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        if (p.booleanOrNull != null) return null
        return p.content
    }

/**
 * Exact integers only: integral numbers, or numeric strings. Fractional,
 * non-finite and overflowing values decode as null instead of truncating.
 */
internal val JsonElement?.lossyLong: Long?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content.trim().toLongOrNull()
        if (p.booleanOrNull != null) return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        if (!d.isFinite() || d != floor(d) || abs(d) >= 9.2e18) return null
        return d.toLong()
    }

internal val JsonElement?.lossyInt: Int?
    get() = lossyLong?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal val JsonElement?.strictBool: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.booleanOrNull
    }

internal val JsonElement?.stringList: List<String>
    get() = (this as? JsonArray)?.mapNotNull { it.strictString }.orEmpty()

// endregion

// region Board and task

data class KanbanTaskWarnings(val count: Int = 0, val highestSeverity: String? = null)
data class KanbanLinkCounts(val parents: Int = 0, val children: Int = 0)
data class KanbanProgress(val done: Int = 0, val total: Int = 0)

data class KanbanDiagnosticAction(
    val kind: String,
    val label: String,
    val payload: JsonObject? = null,
    val suggested: Boolean? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanDiagnosticAction? {
            val o = element as? JsonObject ?: return null
            return KanbanDiagnosticAction(
                kind = o["kind"].lossyString ?: "",
                label = o["label"].lossyString ?: "",
                payload = o["payload"] as? JsonObject,
                suggested = o["suggested"].strictBool,
            )
        }
    }
}

data class KanbanDiagnostic(
    val kind: String,
    val severity: String,
    val title: String,
    val detail: String,
    val actions: List<KanbanDiagnosticAction>,
    val count: Int,
    val lastSeenAt: Long? = null,
    val data: JsonObject? = null,
) {
    companion object {
        /** A partially hostile diagnostic still renders: identity degrades to "", count to 1. */
        fun parse(element: JsonElement?): KanbanDiagnostic? {
            val o = element as? JsonObject ?: return null
            return KanbanDiagnostic(
                kind = o["kind"].lossyString ?: "",
                severity = o["severity"].lossyString ?: "",
                title = o["title"].lossyString ?: "",
                detail = o["detail"].lossyString ?: "",
                actions = (o["actions"] as? JsonArray)?.mapNotNull { KanbanDiagnosticAction.parse(it) }.orEmpty(),
                count = o["count"].lossyInt ?: 1,
                lastSeenAt = o["last_seen_at"].lossyLong,
                data = o["data"] as? JsonObject,
            )
        }
    }
}

/** The UI-facing slice of a Kanban task (board card and detail share one shape). */
data class KanbanTask(
    val id: String,
    val title: String,
    val body: String? = null,
    val status: String,
    val assignee: String? = null,
    val priority: Int? = null,
    val tenant: String? = null,
    val createdAt: Long? = null,
    val latestSummary: String? = null,
    val commentCount: Int? = null,
    val linkCounts: KanbanLinkCounts? = null,
    val progress: KanbanProgress? = null,
    val warnings: KanbanTaskWarnings? = null,
    val startedAt: Long? = null,
    val workerPid: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val result: String? = null,
    val createdBy: String? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val reasoningEffort: String? = null,
    val completedAt: Long? = null,
    val lastFailureError: String? = null,
    val workspaceKind: String? = null,
    val workspacePath: String? = null,
    val branchName: String? = null,
    val consecutiveFailures: Int? = null,
    val diagnostics: List<KanbanDiagnostic>? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanTask? {
            val o = element as? JsonObject ?: return null
            return KanbanTask(
                id = o["id"].lossyString ?: "",
                title = o["title"].strictString ?: "Untitled task",
                body = o["body"].strictString,
                status = o["status"].strictString ?: "todo",
                assignee = o["assignee"].strictString,
                priority = o["priority"].lossyInt,
                tenant = o["tenant"].strictString,
                createdAt = o["created_at"].lossyLong,
                latestSummary = o["latest_summary"].strictString,
                commentCount = o["comment_count"].lossyInt,
                linkCounts = (o["link_counts"] as? JsonObject)?.let {
                    KanbanLinkCounts(it["parents"].lossyInt ?: 0, it["children"].lossyInt ?: 0)
                },
                progress = (o["progress"] as? JsonObject)?.let { KanbanProgress(it["done"].lossyInt ?: 0, it["total"].lossyInt ?: 0) },
                warnings = (o["warnings"] as? JsonObject)?.let {
                    KanbanTaskWarnings(it["count"].lossyInt ?: 0, it["highest_severity"].strictString)
                },
                startedAt = o["started_at"].lossyLong,
                workerPid = o["worker_pid"].lossyLong,
                lastHeartbeatAt = o["last_heartbeat_at"].lossyLong,
                result = o["result"].strictString,
                createdBy = o["created_by"].strictString,
                modelOverride = o["model_override"].strictString,
                providerOverride = o["provider_override"].strictString,
                reasoningEffort = o["reasoning_effort"].strictString,
                completedAt = o["completed_at"].lossyLong,
                lastFailureError = o["last_failure_error"].strictString,
                workspaceKind = o["workspace_kind"].strictString,
                workspacePath = o["workspace_path"].strictString,
                branchName = o["branch_name"].strictString,
                consecutiveFailures = o["consecutive_failures"].lossyInt,
                diagnostics = (o["diagnostics"] as? JsonArray)?.mapNotNull { KanbanDiagnostic.parse(it) },
            )
        }
    }
}

data class KanbanColumn(val name: String, val tasks: List<KanbanTask> = emptyList()) {
    companion object {
        /** Rows without an identity are dropped: they can never be actionable. */
        fun parse(element: JsonElement?): KanbanColumn? {
            val o = element as? JsonObject ?: return null
            val tasks = (o["tasks"] as? JsonArray)?.mapNotNull { KanbanTask.parse(it) }?.filter { it.id.isNotEmpty() }.orEmpty()
            return KanbanColumn(o["name"].strictString ?: "unknown", tasks)
        }
    }
}

data class KanbanBoard(
    val columns: List<KanbanColumn>,
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val latestEventId: Long? = null,
    val now: Long? = null,
) {
    val allTasks: List<KanbanTask> get() = columns.flatMap { it.tasks }

    companion object {
        fun parse(o: JsonObject): KanbanBoard = KanbanBoard(
            columns = (o["columns"] as? JsonArray)?.mapNotNull { KanbanColumn.parse(it) }.orEmpty(),
            tenants = o["tenants"].stringList,
            assignees = o["assignees"].stringList,
            latestEventId = o["latest_event_id"].lossyLong,
            now = o["now"].lossyLong,
        )
    }
}

data class KanbanBoardMetadata(
    val slug: String,
    val name: String? = null,
    val description: String? = null,
    val isCurrent: Boolean? = null,
    val total: Int? = null,
    val defaultWorkdir: String? = null,
    val defaultWorkspaceKind: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val archived: Boolean? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanBoardMetadata? {
            val o = element as? JsonObject ?: return null
            return KanbanBoardMetadata(
                slug = o["slug"].lossyString ?: "default",
                name = o["name"].strictString,
                description = o["description"].strictString,
                isCurrent = o["is_current"].strictBool,
                total = o["total"].lossyInt,
                defaultWorkdir = o["default_workdir"].strictString,
                defaultWorkspaceKind = o["default_workspace_kind"].strictString,
                projectId = o["project_id"].strictString,
                projectName = o["project_name"].strictString,
                icon = o["icon"].strictString,
                color = o["color"].strictString,
                archived = o["archived"].strictBool,
            )
        }
    }
}

data class KanbanBoardsResponse(val boards: List<KanbanBoardMetadata> = emptyList(), val current: String = "default") {
    companion object {
        fun parse(o: JsonObject): KanbanBoardsResponse {
            val boards = o["boards"] as? JsonArray ?: throw KanbanDecodeException()
            return KanbanBoardsResponse(
                boards.mapNotNull { KanbanBoardMetadata.parse(it) },
                o["current"].strictString ?: throw KanbanDecodeException(),
            )
        }
    }
}

// endregion

// region Detail collections

data class KanbanComment(val id: String, val taskId: String?, val author: String, val body: String, val createdAt: Long?) {
    companion object {
        fun parse(element: JsonElement?): KanbanComment? {
            val o = element as? JsonObject ?: return null
            return KanbanComment(
                id = o["id"].lossyString ?: UUID.randomUUID().toString(),
                taskId = o["task_id"].strictString,
                author = o["author"].strictString ?: "Hermes",
                body = o["body"].strictString ?: "",
                createdAt = o["created_at"].lossyLong,
            )
        }
    }
}

data class KanbanEvent(
    val id: String,
    val taskId: String? = null,
    val kind: String,
    val payload: JsonElement? = null,
    val createdAt: Long? = null,
    val runId: String? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanEvent? {
            val o = element as? JsonObject ?: return null
            return KanbanEvent(
                id = o["id"].lossyString ?: UUID.randomUUID().toString(),
                taskId = o["task_id"].strictString,
                kind = o["kind"].strictString ?: "event",
                payload = o["payload"]?.takeIf { it !is JsonNull },
                createdAt = o["created_at"].lossyLong,
                runId = o["run_id"].lossyString,
            )
        }
    }
}

data class KanbanRun(
    val id: String,
    val taskId: String? = null,
    val profile: String? = null,
    val stepKey: String? = null,
    val status: String = "unknown",
    val outcome: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val workerPid: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanRun? {
            val o = element as? JsonObject ?: return null
            return KanbanRun(
                id = o["id"].lossyString ?: UUID.randomUUID().toString(),
                taskId = o["task_id"].lossyString,
                profile = o["profile"].strictString,
                stepKey = o["step_key"].strictString,
                status = o["status"].strictString ?: "unknown",
                outcome = o["outcome"].strictString,
                summary = o["summary"].strictString,
                error = o["error"].strictString,
                workerPid = o["worker_pid"].lossyLong,
                startedAt = o["started_at"].lossyLong,
                endedAt = o["ended_at"].lossyLong,
            )
        }
    }
}

data class KanbanTaskLinks(val parents: List<String> = emptyList(), val children: List<String> = emptyList())

data class KanbanChildResult(val id: String, val title: String?, val status: String?, val latestSummary: String?, val result: String?)

data class KanbanTaskDetail(
    val task: KanbanTask,
    val comments: List<KanbanComment> = emptyList(),
    val events: List<KanbanEvent> = emptyList(),
    val links: KanbanTaskLinks = KanbanTaskLinks(),
    val childResults: List<KanbanChildResult> = emptyList(),
    val runs: List<KanbanRun> = emptyList(),
) {
    companion object {
        fun parse(o: JsonObject): KanbanTaskDetail = KanbanTaskDetail(
            task = KanbanTask.parse(o["task"]) ?: KanbanTask(id = "", title = "Untitled task", status = "todo"),
            comments = (o["comments"] as? JsonArray)?.mapNotNull { KanbanComment.parse(it) }.orEmpty(),
            events = (o["events"] as? JsonArray)?.mapNotNull { KanbanEvent.parse(it) }.orEmpty(),
            links = (o["links"] as? JsonObject)?.let { KanbanTaskLinks(it["parents"].stringList, it["children"].stringList) }
                ?: KanbanTaskLinks(),
            childResults = (o["child_results"] as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let {
                    KanbanChildResult(
                        it["id"].lossyString ?: UUID.randomUUID().toString(),
                        it["title"].strictString, it["status"].strictString,
                        it["latest_summary"].strictString, it["result"].strictString,
                    )
                }
            }.orEmpty(),
            runs = (o["runs"] as? JsonArray)?.mapNotNull { KanbanRun.parse(it) }.orEmpty(),
        )
    }
}

// endregion

// region Auxiliary data

data class KanbanProfile(
    val name: String,
    val isDefault: Boolean = false,
    val description: String = "",
    val descriptionAuto: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val skillCount: Int? = null,
) {
    companion object {
        fun parse(element: JsonElement?): KanbanProfile? {
            val o = element as? JsonObject ?: return null
            val name = o["name"].strictString ?: return null
            return KanbanProfile(
                name = name,
                isDefault = o["is_default"].strictBool ?: false,
                description = o["description"].strictString ?: "",
                descriptionAuto = o["description_auto"].strictBool ?: false,
                model = o["model"].strictString,
                provider = o["provider"].strictString,
                skillCount = o["skill_count"].lossyInt,
            )
        }
    }
}

data class KanbanProject(val id: String, val slug: String, val name: String, val primaryPath: String? = null) {
    companion object {
        fun parse(element: JsonElement?): KanbanProject? {
            val o = element as? JsonObject ?: return null
            val id = o["id"].lossyString ?: return null
            return KanbanProject(id, o["slug"].strictString ?: id, o["name"].strictString ?: o["slug"].strictString ?: id, o["primary_path"].strictString)
        }
    }
}

data class KanbanOrchestrationSettings(
    val orchestratorProfile: String = "",
    val defaultAssignee: String = "",
    val autoDecompose: Boolean = true,
    val autoPromoteChildren: Boolean? = null,
    val resolvedOrchestratorProfile: String = "",
    val resolvedDefaultAssignee: String = "",
) {
    companion object {
        /** Backend default for `auto_promote_children` when config omits it. */
        const val DEFAULT_AUTO_PROMOTE_CHILDREN = true

        fun parse(o: JsonObject): KanbanOrchestrationSettings {
            // The configured/resolved pairs are required by the contract.
            val orchestrator = o["orchestrator_profile"].strictString ?: throw KanbanDecodeException()
            val assignee = o["default_assignee"].strictString ?: throw KanbanDecodeException()
            return KanbanOrchestrationSettings(
                orchestratorProfile = orchestrator,
                defaultAssignee = assignee,
                autoDecompose = o["auto_decompose"].strictBool ?: throw KanbanDecodeException(),
                autoPromoteChildren = o["auto_promote_children"].strictBool,
                resolvedOrchestratorProfile = o["resolved_orchestrator_profile"].strictString ?: "",
                resolvedDefaultAssignee = o["resolved_default_assignee"].strictString ?: "",
            )
        }
    }
}

data class KanbanWorkerLog(val exists: Boolean, val sizeBytes: Long, val content: String, val truncated: Boolean) {
    companion object {
        fun parse(o: JsonObject): KanbanWorkerLog = KanbanWorkerLog(
            exists = o["exists"].strictBool ?: throw KanbanDecodeException(),
            sizeBytes = o["size_bytes"].lossyLong ?: 0,
            content = o["content"].strictString ?: "",
            truncated = o["truncated"].strictBool ?: false,
        )
    }
}

/** GET /model-options provider row. */
data class KanbanModelProviderOption(val slug: String, val label: String? = null, val models: List<String> = emptyList()) {
    val displayName: String get() = (label ?: slug).ifEmpty { slug }

    companion object {
        fun parseList(o: JsonObject): List<KanbanModelProviderOption> =
            (o["providers"] as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let {
                    KanbanModelProviderOption(it["slug"].lossyString ?: "", it["label"].strictString, it["models"].stringList.filter { m -> m.isNotEmpty() })
                }
            }.orEmpty()
    }
}

// endregion

// region Request bodies

data class KanbanCreateTaskRequest(
    val title: String,
    val body: String? = null,
    val assignee: String? = null,
    val tenant: String? = null,
    val priority: Int = 0,
    val workspaceKind: String = "scratch",
    val workspacePath: String? = null,
    val parents: List<String> = emptyList(),
    val triage: Boolean = false,
    val skills: List<String>? = null,
    val goalMode: Boolean = false,
    val goalMaxTurns: Int? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val reasoningEffort: String? = null,
    val projectId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        body?.let { put("body", it) }
        assignee?.let { put("assignee", it) }
        tenant?.let { put("tenant", it) }
        put("priority", priority)
        put("workspace_kind", workspaceKind)
        workspacePath?.let { put("workspace_path", it) }
        putJsonArray("parents") { parents.forEach { add(JsonPrimitive(it)) } }
        put("triage", triage)
        skills?.let { list -> putJsonArray("skills") { list.forEach { add(JsonPrimitive(it)) } } }
        put("goal_mode", goalMode)
        goalMaxTurns?.let { put("goal_max_turns", it) }
        modelOverride?.let { put("model_override", it) }
        providerOverride?.let { put("provider_override", it) }
        reasoningEffort?.let { put("reasoning_effort", it) }
        projectId?.let { put("project_id", it) }
    }
}

/** PATCH /tasks/{id}: null fields are omitted; clear flags are only sent when true. */
data class KanbanTaskPatch(
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val result: String? = null,
    val blockReason: String? = null,
    val summary: String? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val clearModelOverride: Boolean = false,
    val reasoningEffort: String? = null,
    val clearReasoningEffort: Boolean = false,
) {
    val isEmpty: Boolean
        get() = status == null && assignee == null && priority == null && title == null && body == null && result == null &&
            blockReason == null && summary == null && modelOverride == null && providerOverride == null &&
            !clearModelOverride && reasoningEffort == null && !clearReasoningEffort

    fun toJson(): JsonObject = buildJsonObject {
        status?.let { put("status", it) }
        assignee?.let { put("assignee", it) }
        priority?.let { put("priority", it) }
        title?.let { put("title", it) }
        body?.let { put("body", it) }
        result?.let { put("result", it) }
        blockReason?.let { put("block_reason", it) }
        summary?.let { put("summary", it) }
        modelOverride?.let { put("model_override", it) }
        providerOverride?.let { put("provider_override", it) }
        if (clearModelOverride) put("clear_model_override", true)
        reasoningEffort?.let { put("reasoning_effort", it) }
        if (clearReasoningEffort) put("clear_reasoning_effort", true)
    }
}

/** PUT /orchestration: only present fields are written; "" clears an override (= Default). */
data class KanbanOrchestrationPatch(
    val orchestratorProfile: String? = null,
    val defaultAssignee: String? = null,
    val autoDecompose: Boolean? = null,
    val autoPromoteChildren: Boolean? = null,
) {
    val isEmpty: Boolean get() = orchestratorProfile == null && defaultAssignee == null && autoDecompose == null && autoPromoteChildren == null

    fun toJson(): JsonObject = buildJsonObject {
        orchestratorProfile?.let { put("orchestrator_profile", it) }
        defaultAssignee?.let { put("default_assignee", it) }
        autoDecompose?.let { put("auto_decompose", it) }
        autoPromoteChildren?.let { put("auto_promote_children", it) }
    }
}

/** POST /boards. `switch` is always false: Conduit never moves the server's current-board pointer. */
data class KanbanCreateBoardRequest(
    val slug: String,
    val name: String? = null,
    val description: String? = null,
    val defaultWorkdir: String? = null,
    val projectId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("slug", slug)
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        defaultWorkdir?.let { put("default_workdir", it) }
        projectId?.let { put("project_id", it) }
        put("switch", false)
    }
}

/** PATCH /boards/{slug}: null = omitted (unchanged), "" = clear, value = set. */
data class KanbanUpdateBoardPatch(
    val name: String? = null,
    val description: String? = null,
    val defaultWorkdir: String? = null,
    val projectId: String? = null,
) {
    val isEmpty: Boolean get() = name == null && description == null && defaultWorkdir == null && projectId == null

    fun toJson(): JsonObject = buildJsonObject {
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        defaultWorkdir?.let { put("default_workdir", it) }
        projectId?.let { put("project_id", it) }
    }
}

/** POST /tasks/bulk — only the fields Conduit exposes. */
data class KanbanBulkTaskRequest(
    val ids: List<String>,
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
    val archive: Boolean? = null,
    val reclaimFirst: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
        status?.let { put("status", it) }
        assignee?.let { put("assignee", it) }
        priority?.let { put("priority", it) }
        archive?.let { put("archive", it) }
        reclaimFirst?.let { put("reclaim_first", it) }
    }
}

// endregion

// region Responses

/** HTTP 200 with `ok:false` is a semantic refusal: the reason is shown verbatim. */
data class KanbanSpecifyResponse(val ok: Boolean, val taskId: String, val reason: String? = null, val newTitle: String? = null) {
    companion object {
        fun parse(o: JsonObject) = KanbanSpecifyResponse(
            ok = o["ok"].strictBool ?: false,
            taskId = o["task_id"].lossyString ?: "",
            reason = o["reason"].strictString,
            newTitle = o["new_title"].strictString,
        )
    }
}

data class KanbanDecomposeResponse(
    val ok: Boolean,
    val taskId: String,
    val reason: String? = null,
    val fanout: Boolean = false,
    val childIds: List<String> = emptyList(),
    val newTitle: String? = null,
) {
    companion object {
        fun parse(o: JsonObject) = KanbanDecomposeResponse(
            ok = o["ok"].strictBool ?: false,
            taskId = o["task_id"].lossyString ?: "",
            reason = o["reason"].strictString,
            fanout = o["fanout"].strictBool ?: false,
            childIds = o["child_ids"].stringList,
            newTitle = o["new_title"].strictString,
        )
    }
}

data class KanbanAutoDescribeResponse(val ok: Boolean, val profile: String, val reason: String? = null, val description: String? = null) {
    companion object {
        fun parse(o: JsonObject) = KanbanAutoDescribeResponse(
            ok = o["ok"].strictBool ?: false,
            profile = o["profile"].lossyString ?: "",
            reason = o["reason"].strictString,
            description = o["description"].strictString,
        )
    }
}

data class KanbanDeleteBoardResult(val action: String?, val slug: String?, val newPath: String?)

data class KanbanBulkTaskResult(val id: String, val ok: Boolean, val error: String? = null) {
    companion object {
        fun parseList(o: JsonObject): List<KanbanBulkTaskResult> =
            (o["results"] as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let { KanbanBulkTaskResult(it["id"].lossyString ?: "", it["ok"].strictBool ?: false, it["error"].strictString) }
            }.orEmpty()
    }
}

data class KanbanBulkFailure(val id: String, val reason: String)
data class KanbanBulkOperationOutcome(val succeededIds: List<String>, val failures: List<KanbanBulkFailure>)

// endregion

// region Live events (invalidation only)

/** Only what invalidation needs; kinds and payloads are deliberately ignored. */
data class KanbanLiveEvent(val id: Long? = null, val taskId: String? = null)

data class KanbanEventFrame(val events: List<KanbanLiveEvent> = emptyList(), val cursor: Long? = null) {
    companion object {
        /** null for unreadable frames; malformed elements are skipped, never block later ones. */
        fun parse(text: String): KanbanEventFrame? {
            val o = runCatching { ConduitJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val events = (o["events"] as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.let { KanbanLiveEvent((it["id"] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull, it["task_id"].strictString) }
            }.orEmpty()
            val cursor = (o["cursor"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            return KanbanEventFrame(events, cursor)
        }
    }
}

// endregion

class KanbanDecodeException : IllegalStateException(
    L10n.tr("Hermes sent a Kanban reply Conduit didn't understand. Updating Hermes and Conduit usually fixes this."),
)
