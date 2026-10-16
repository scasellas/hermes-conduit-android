package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

// Pure Kanban policies ported from Conduit/Models/KanbanStatus.swift and
// Conduit/Views/Kanban/*Support.swift. No Android dependencies: unit-tested on the JVM.

// region Status presentation

enum class KanbanTint { SECONDARY, PURPLE, BLUE, GREEN, RED, ORANGE }

/**
 * Presentation and mutation metadata for backend workflow statuses. Mirrors the
 * desktop `LOCKED_COLUMNS = ['review', 'running', 'scheduled']`: locked lanes are
 * visible but never offered as manual move/create destinations.
 */
data class KanbanStatusPresentation(
    val rawValue: String,
    val displayName: String,
    val tint: KanbanTint,
    val sortOrder: Int,
    val isManuallySelectable: Boolean,
    val isTaskCreatable: Boolean,
    val isBackendControlled: Boolean,
) {
    companion object {
        val knownStatuses = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done", "archived")
        val lockedDestinations = listOf("review", "running", "scheduled")

        val manuallySelectableStatuses: List<KanbanStatusPresentation>
            get() = knownStatuses.map(::forStatus).filter { it.isManuallySelectable }

        val taskCreatableStatuses: List<KanbanStatusPresentation>
            get() = knownStatuses.map(::forStatus).filter { it.isTaskCreatable }

        fun canSelectManually(raw: String) = raw in knownStatuses && forStatus(raw).isManuallySelectable
        fun canCreateTask(raw: String) = raw in knownStatuses && forStatus(raw).isTaskCreatable
        fun isLockedDestination(raw: String) = raw in lockedDestinations

        fun forStatus(raw: String): KanbanStatusPresentation = when (raw) {
            "triage" -> KanbanStatusPresentation(raw, L10n.tr("Triage"), KanbanTint.SECONDARY, 0, true, true, false)
            "todo" -> KanbanStatusPresentation(raw, L10n.tr("To Do"), KanbanTint.SECONDARY, 1, true, true, false)
            "scheduled" -> KanbanStatusPresentation(raw, L10n.tr("Scheduled"), KanbanTint.PURPLE, 2, false, false, true)
            "ready" -> KanbanStatusPresentation(raw, L10n.tr("Ready"), KanbanTint.BLUE, 3, true, true, false)
            "running" -> KanbanStatusPresentation(raw, L10n.tr("Running"), KanbanTint.GREEN, 4, false, false, true)
            "blocked" -> KanbanStatusPresentation(raw, L10n.tr("Blocked"), KanbanTint.RED, 5, true, true, false)
            "review" -> KanbanStatusPresentation(raw, L10n.tr("Review"), KanbanTint.ORANGE, 6, false, false, true)
            "done" -> KanbanStatusPresentation(raw, L10n.tr("Done"), KanbanTint.SECONDARY, 7, true, true, false)
            "archived" -> KanbanStatusPresentation(raw, L10n.tr("Archived"), KanbanTint.SECONDARY, 8, true, false, false)
            else -> {
                val name = capitalizeWords(raw.replace('_', ' '))
                KanbanStatusPresentation(raw, name.ifEmpty { L10n.tr("Other") }, KanbanTint.SECONDARY, 100, false, false, true)
            }
        }

        fun orderedColumns(columns: List<KanbanColumn>): List<KanbanColumn> = columns.sortedWith { a, b ->
            val l = forStatus(a.name)
            val r = forStatus(b.name)
            if (l.sortOrder != r.sortOrder) l.sortOrder.compareTo(r.sortOrder)
            else l.displayName.compareTo(r.displayName, ignoreCase = true)
        }
    }
}

/** Swift `String.capitalized`: first letter of every word upper, the rest lower. */
internal fun capitalizeWords(value: String): String =
    value.split(' ').joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

// endregion

// region Small helpers

object KanbanShortId {
    fun of(id: String?): String {
        val value = id ?: ""
        return value.removePrefix("t_").take(6)
    }
}

object KanbanLanePolicy {
    fun effectiveSelectedLane(selected: String?, columns: List<KanbanColumn>): String? {
        if (columns.isEmpty()) return null
        if (!selected.isNullOrEmpty() && columns.any { it.name == selected }) return selected
        return columns.firstOrNull { !KanbanStatusPresentation.isLockedDestination(it.name) }?.name ?: columns.first().name
    }

    fun newTaskInitialStatus(effectiveLane: String?): String =
        if (effectiveLane != null && KanbanStatusPresentation.canCreateTask(effectiveLane)) effectiveLane else "todo"
}

/** Upstream board-context identity: a mutation is owned by the board + connection it was read from. */
data class KanbanBoardContextStamp(val boardSlug: String, val configurationGeneration: Int)

object KanbanPollingPolicy {
    const val BOARD_INTERVAL_MS = 8_000L
    const val DETAIL_INTERVAL_MS = 4_000L
}

// endregion

// region Composer

data class TaskModelOverride(val provider: String? = null, val model: String? = null, val reasoningEffort: String? = null) {
    val isInherited: Boolean
        get() = model.orEmpty().isBlank() && provider.orEmpty().isBlank() && reasoningEffort.orEmpty().isBlank()

    fun label(inheritCopy: String): String {
        if (isInherited) return inheritCopy
        val modelName = model?.trim().orEmpty()
        val providerName = provider?.trim().orEmpty()
        val base = when {
            modelName.isEmpty() -> inheritCopy
            providerName.isEmpty() -> modelName
            else -> "$providerName: $modelName"
        }
        val effort = reasoningEffort?.trim().orEmpty()
        return if (effort.isNotEmpty()) "$base · ${effortLabel(effort)}" else base
    }

    /** Untouched fields are omitted on create (the profile's own model applies). */
    fun applyTo(request: KanbanCreateTaskRequest): KanbanCreateTaskRequest {
        val modelName = model?.trim().orEmpty()
        val providerName = provider?.trim().orEmpty()
        val effort = reasoningEffort?.trim().orEmpty()
        var next = request
        if (modelName.isNotEmpty()) {
            next = next.copy(modelOverride = modelName, providerOverride = providerName.ifEmpty { null })
        }
        if (effort.isNotEmpty()) next = next.copy(reasoningEffort = effort)
        return next
    }

    companion object {
        val validReasoningEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

        fun of(task: KanbanTask) = TaskModelOverride(task.providerOverride, task.modelOverride, task.reasoningEffort)

        fun effortLabel(value: String): String = if (value == "xhigh") L10n.tr("Extra High") else capitalizeWords(value)

        fun patch(current: KanbanTask, next: TaskModelOverride): KanbanTaskPatch {
            var patch = KanbanTaskPatch()
            val nextModel = next.model?.trim().orEmpty()
            val nextProvider = next.provider?.trim().orEmpty()
            val nextEffort = next.reasoningEffort?.trim().orEmpty()
            val currentHasModel = current.modelOverride.orEmpty().isNotBlank() || current.providerOverride.orEmpty().isNotBlank()
            if (nextModel.isNotEmpty()) {
                patch = patch.copy(modelOverride = nextModel, providerOverride = nextProvider.ifEmpty { null })
            } else if (currentHasModel) {
                patch = patch.copy(clearModelOverride = true)
            }
            if (nextEffort.isNotEmpty()) {
                patch = patch.copy(reasoningEffort = nextEffort)
            } else if (!current.reasoningEffort.isNullOrEmpty()) {
                patch = patch.copy(clearReasoningEffort = true)
            }
            return patch
        }
    }
}

enum class KanbanWorkspaceKind(val raw: String) {
    SCRATCH("scratch"), WORKTREE("worktree"), DIR("dir");

    val displayName: String
        get() = when (this) {
            SCRATCH -> L10n.tr("Scratch")
            WORKTREE -> L10n.tr("Worktree")
            DIR -> L10n.tr("Directory")
        }
    val allowsPathOverride: Boolean get() = this != SCRATCH

    companion object {
        fun initialKind(boardDefault: String?): KanbanWorkspaceKind {
            val raw = boardDefault?.trim()?.lowercase() ?: return SCRATCH
            return entries.firstOrNull { it.raw == raw } ?: SCRATCH
        }
    }
}

sealed interface KanbanAssigneeSelection {
    data object InheritDefault : KanbanAssigneeSelection
    data class Profile(val name: String) : KanbanAssigneeSelection
    data object Parked : KanbanAssigneeSelection

    /** Desktop: `PARKED ? undefined : (assignee || resolvedDefault)`. */
    fun requestValue(resolvedDefaultAssignee: String): String? = when (this) {
        InheritDefault -> resolvedDefaultAssignee.trim().ifEmpty { "default" }
        is Profile -> name
        Parked -> null
    }
}

data class KanbanComposerDraft(
    val title: String = "",
    val body: String = "",
    val priority: Int = 0,
    val assignee: KanbanAssigneeSelection = KanbanAssigneeSelection.InheritDefault,
    val workspaceKind: KanbanWorkspaceKind = KanbanWorkspaceKind.SCRATCH,
    val workspacePath: String = "",
    val skills: List<String> = emptyList(),
    val parents: List<String> = emptyList(),
    val goalMode: Boolean = false,
    val goalMaxTurns: Int? = null,
    val modelOverride: TaskModelOverride = TaskModelOverride(),
) {
    val trimmedTitle: String get() = title.trim()
}

class KanbanDraftValidationException(message: String) : IllegalArgumentException(message)

object KanbanComposerValidator {
    fun sanitized(values: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { seen.add(it) }
        return seen.toList()
    }

    fun validate(draft: KanbanComposerDraft) {
        if (draft.trimmedTitle.isEmpty()) throw KanbanDraftValidationException(L10n.tr("A title is required."))
        if (!draft.workspaceKind.allowsPathOverride && draft.workspacePath.trim().isNotEmpty()) {
            throw KanbanDraftValidationException(
                L10n.tr("A workspace path can only be set for Worktree and Directory tasks. Hermes scratch tasks resolve their own directory."),
            )
        }
        val effort = draft.modelOverride.reasoningEffort?.trim().orEmpty()
        if (effort.isNotEmpty() && effort !in TaskModelOverride.validReasoningEfforts) {
            throw KanbanDraftValidationException(L10n.tr("\"%s\" is not a valid reasoning effort for a Hermes worker.", effort))
        }
        val parents = draft.parents.map { it.trim() }.filter { it.isNotEmpty() }
        parents.firstOrNull { candidate -> parents.count { it == candidate } > 1 }?.let {
            throw KanbanDraftValidationException(L10n.tr("Task %s is already listed as a parent.", it))
        }
        val turns = draft.goalMaxTurns
        if (draft.goalMode && turns != null && turns < 1) {
            throw KanbanDraftValidationException(L10n.tr("Goal Mode max turns must be at least 1 (got %s).", turns.toString()))
        }
    }

    fun makeRequest(draft: KanbanComposerDraft, resolvedDefaultAssignee: String): KanbanCreateTaskRequest {
        validate(draft)
        val skills = sanitized(draft.skills)
        val body = draft.body.trim()
        val path = draft.workspacePath.trim()
        val request = KanbanCreateTaskRequest(
            title = draft.trimmedTitle,
            body = body.ifEmpty { null },
            assignee = draft.assignee.requestValue(resolvedDefaultAssignee),
            priority = maxOf(0, draft.priority),
            workspaceKind = draft.workspaceKind.raw,
            workspacePath = if (draft.workspaceKind.allowsPathOverride && path.isNotEmpty()) path else null,
            parents = sanitized(draft.parents),
            triage = false,
            skills = skills.ifEmpty { null },
            goalMode = draft.goalMode,
            goalMaxTurns = if (draft.goalMode) draft.goalMaxTurns else null,
        )
        return draft.modelOverride.applyTo(request)
    }
}

// endregion

// region Task detail presentation

object KanbanActivityFormatter {
    data class Row(val label: String, val detail: String?)

    fun row(event: KanbanEvent): Row {
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        fun string(key: String): String? = payload[key].strictString?.takeIf { it.isNotEmpty() }
        fun status(key: String): String? = string(key)?.let { KanbanStatusPresentation.forStatus(it).displayName }
        return when (event.kind) {
            "created" -> {
                val parts = listOfNotNull(status("status")?.let { "as $it" }, string("assignee")?.let { "for $it" })
                Row(L10n.tr("Task created"), parts.joinToString(" ").ifEmpty { null })
            }
            "status" -> {
                val reason = string("reason")
                val detail = if (reason == "parent_reopened") L10n.tr("parent %s reopened", string("parent") ?: "") else reason
                Row(L10n.tr("Moved to %s", status("status") ?: "?"), detail)
            }
            "assigned" -> string("assignee")?.let { Row(L10n.tr("Assigned to %s", it), null) } ?: Row(L10n.tr("Unassigned"), null)
            "commented" -> Row(L10n.tr("Comment from %s", string("author") ?: L10n.tr("someone")), null)
            "claimed" -> Row(if (string("source_status") == "review") L10n.tr("Claimed from review") else L10n.tr("Claimed by worker"), null)
            "spawned" -> Row(L10n.tr("Worker started"), payload["pid"].lossyLong?.let { L10n.tr("pid %s", it.toString()) })
            "completed" -> Row(L10n.tr("Completed"), null)
            "blocked" -> Row(L10n.tr("Blocked"), string("reason"))
            "unblocked" -> Row(L10n.tr("Unblocked → %s", status("status") ?: ""), null)
            "reclaimed" -> Row(L10n.tr("Reclaimed"), string("reason"))
            "specified" -> Row(L10n.tr("Fleshed out by triage specifier"), null)
            "promoted" -> Row(L10n.tr("Promoted"), null)
            "scheduled" -> Row(L10n.tr("Scheduled"), string("reason"))
            "archived" -> Row(L10n.tr("Archived"), null)
            "reprioritized" -> Row(L10n.tr("Priority set to %s", payload["priority"].lossyLong?.toString() ?: "?"), null)
            "edited" -> Row(L10n.tr("Details edited"), null)
            else -> {
                val scalars = payload.mapNotNull { (key, value) ->
                    val p = value as? JsonPrimitive ?: return@mapNotNull null
                    when {
                        p is JsonNull -> null
                        p.isString -> "$key=${p.content}"
                        p.booleanOrNull != null -> "$key=${p.content}"
                        else -> {
                            val d = p.doubleOrNull ?: return@mapNotNull null
                            if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.2e18) "$key=${d.toLong()}" else "$key=$d"
                        }
                    }
                }.sorted().joinToString(" ")
                Row(capitalizeWords(event.kind.replace('_', ' ')), scalars.ifEmpty { null })
            }
        }
    }
}

/** The worker's open question on a blocked task, if the newest block carries one. */
object KanbanBlockedQuestion {
    private val transitionKinds = setOf("blocked", "unblocked", "status", "claimed", "completed", "reclaimed", "scheduled", "promoted", "archived")

    fun reason(detail: KanbanTaskDetail): String? {
        val transitions = detail.events.withIndex().filter { it.value.kind in transitionKinds }
        val question: String?
        val blockTime: Long
        // Events arrive newest first: on a timestamp tie the earlier index wins.
        val block = transitions.filter { it.value.kind == "blocked" }.reduceOrNull { best, next ->
            val b = best.value.createdAt ?: Long.MAX_VALUE
            val n = next.value.createdAt ?: Long.MAX_VALUE
            if (n > b) next else best
        }
        if (block != null) {
            question = nonEmpty((block.value.payload as? JsonObject)?.get("reason").strictString)
            blockTime = block.value.createdAt ?: Long.MAX_VALUE
        } else {
            val run = detail.runs.filter { (it.outcome ?: it.status) == "blocked" }
                .maxByOrNull { it.endedAt ?: it.startedAt ?: 0 } ?: return null
            question = nonEmpty(run.summary)
            blockTime = run.endedAt ?: run.startedAt ?: Long.MAX_VALUE
        }
        val superseded = transitions.any { it.value.kind != "blocked" && (it.value.createdAt ?: Long.MAX_VALUE) > blockTime }
        return if (superseded) null else question
    }

    private fun nonEmpty(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
}

object KanbanCompactDuration {
    /** Whole largest unit: 7199 s → "1h". */
    fun text(seconds: Long): String {
        val steps = listOf(86_400L to "d", 3_600L to "h", 60L to "m", 1L to "s")
        val (size, unit) = steps.firstOrNull { seconds >= it.first } ?: steps.last()
        return "${seconds / size}$unit"
    }
}

object KanbanRunPresentation {
    val failedOutcomes = listOf("crashed", "failed", "timed_out", "gave_up")
    fun isFailed(run: KanbanRun) = (run.outcome ?: run.status) in failedOutcomes
    fun isCompleted(run: KanbanRun) = run.outcome == "completed" || run.status == "completed"
    fun durationText(start: Long?, end: Long?): String? {
        if (start == null || end == null || end < start || start <= 0) return null
        return KanbanCompactDuration.text(end - start)
    }
    fun outcomeLabel(run: KanbanRun) = (run.outcome ?: run.status).replace('_', ' ')
}

object KanbanCardLiveness {
    enum class State { RUNNING, STALE, QUEUED }
    const val STALE_HEARTBEAT_SECONDS = 120L

    fun state(task: KanbanTask, nowSeconds: Long = System.currentTimeMillis() / 1000): State? {
        if (task.status == "running") {
            val heartbeat = task.lastHeartbeatAt
            if (heartbeat != null && nowSeconds - heartbeat > STALE_HEARTBEAT_SECONDS) return State.STALE
            return State.RUNNING
        }
        val queued = task.status == "triage" || task.status == "review" || (task.status == "ready" && !task.assignee.isNullOrEmpty())
        return if (queued) State.QUEUED else null
    }

    fun elapsedText(startedAt: Long?, nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        if (startedAt == null || startedAt <= 0) return null
        return KanbanCompactDuration.text(maxOf(0, nowSeconds - startedAt))
    }
}

object KanbanDetailDraftPolicy {
    data class Fields(val title: String, val body: String, val status: String)

    fun isDirty(draft: Fields, baseline: Fields) = draft != baseline
    fun serverMovedIndependently(server: Fields, baseline: Fields) = server != baseline
}

object KanbanDetailIdentityPolicy {
    fun actionableTask(displayedId: String, detailTask: KanbanTask?, initialTask: KanbanTask?): KanbanTask? {
        if (detailTask != null && detailTask.id == displayedId) return detailTask
        return initialTask?.takeIf { it.id == displayedId }
    }
}

data class KanbanModelOverrideSession(val taskId: String, val baseline: TaskModelOverride)

object KanbanModelOverrideSessionPolicy {
    data class CommitTarget(val value: TaskModelOverride, val startedTask: KanbanTask, val expectedId: String)

    /** Dismissal only writes when the user changed the draft for the same task, diffed against current server state. */
    fun commitTarget(session: KanbanModelOverrideSession?, draft: TaskModelOverride, displayedTask: KanbanTask?, displayedTaskId: String): CommitTarget? {
        if (session == null || session.taskId != displayedTaskId || draft == session.baseline) return null
        val server = displayedTask?.takeIf { it.id == displayedTaskId } ?: return null
        if (draft == TaskModelOverride.of(server)) return null
        return CommitTarget(draft, server, displayedTaskId)
    }
}

/** Post a comment, then transition (reclaim or unblock). A failed transition is an explicit partial success. */
object KanbanNoteAndRequeueFlow {
    data class Outcome(val commentPosted: Boolean, val requeued: Boolean, val failureDetail: String?)
    enum class Transition { REQUEUE, UNBLOCK }

    suspend fun perform(
        text: String,
        postComment: suspend (String) -> Unit,
        transition: suspend () -> Unit,
        onCommentPosted: () -> Unit,
        message: (Throwable) -> String,
    ): Outcome {
        try {
            postComment(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome(false, false, message(e))
        }
        onCommentPosted()
        try {
            transition()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome(true, false, message(e))
        }
        return Outcome(true, true, null)
    }

    fun message(outcome: Outcome, transition: Transition = Transition.REQUEUE): String? {
        val detail = outcome.failureDetail ?: return null
        return when {
            !outcome.commentPosted -> detail
            !outcome.requeued -> when (transition) {
                Transition.REQUEUE -> L10n.tr("The note was posted, but the task could not be requeued. (%s)", detail)
                Transition.UNBLOCK -> L10n.tr("The reply was posted, but the task could not be unblocked. (%s)", detail)
            }
            else -> null
        }
    }
}

object KanbanWorkerLogPolicy {
    const val TAIL_BYTES = 65_536
    const val MAX_RENDERED_CHARACTERS = 60_000

    /** Keeps the newest output within budget, resuming at a line boundary when possible. */
    fun renderTail(content: String): String {
        if (content.length <= MAX_RENDERED_CHARACTERS) return content
        val marker = L10n.tr("[older output omitted]")
        val suffix = content.takeLast(maxOf(0, MAX_RENDERED_CHARACTERS - marker.length))
        val newline = suffix.indexOf('\n')
        return marker + if (newline >= 0) suffix.substring(newline) else suffix
    }
}

// endregion

// region V3A: orchestration, profiles, triage

object KanbanTriagePolicy {
    fun isEligible(status: String) = status == "triage"
    fun isEligible(task: KanbanTask?) = task != null && isEligible(task.status)
}

data class PendingTriageAction(val taskId: String, val context: KanbanBoardContextStamp) {
    companion object {
        fun capture(task: KanbanTask?, isSnapshotActionable: Boolean, stamp: KanbanBoardContextStamp?): PendingTriageAction? {
            if (task == null || stamp == null || !isSnapshotActionable || task.id.isEmpty()) return null
            return PendingTriageAction(task.id, stamp)
        }
    }
}

data class CompletedTriageMutation(val taskId: String, val context: KanbanBoardContextStamp)

object KanbanTriageCompletionPolicy {
    fun isSuppressed(completed: CompletedTriageMutation?, displayedTaskId: String, context: KanbanBoardContextStamp?): Boolean {
        if (completed == null || context == null) return false
        return completed.taskId == displayedTaskId && completed.context == context
    }

    fun shouldClearAfterReconciliation(reconciledStatus: String) = reconciledStatus != "triage"
}

object KanbanTriageActionsPolicy {
    val decomposeConfirmationTitle: String get() = L10n.tr("Decompose this task?")
    val decomposeConfirmationMessage: String get() = L10n.tr("Hermes may create and assign multiple dependent tasks.")

    fun successNotice(fanout: Boolean, childCount: Int): String =
        if (fanout) L10n.tr("Decomposed into %d task", childCount) else L10n.tr("Decomposed (single task, no fan-out)")

    fun successNoticeWithRefreshFailure(base: String, storeRefreshError: String?): String {
        if (storeRefreshError.isNullOrBlank()) return base
        return L10n.tr("%s, but the board could not be refreshed. %s", base, storeRefreshError)
    }
}

object KanbanOrchestrationDisplay {
    fun defaultOptionLabel(resolved: String): String {
        val name = resolved.trim()
        return if (name.isEmpty()) L10n.tr("Default") else L10n.tr("Default (%s)", name)
    }

    fun resolveFootnote(configured: String, resolved: String): String {
        val resolvedName = resolved.trim()
        val configuredName = configured.trim()
        if (configuredName.isEmpty()) {
            return if (resolvedName.isEmpty()) L10n.tr("Default inherits the active Hermes profile.")
            else L10n.tr("Default resolves to %s.", resolvedName)
        }
        return L10n.tr("Pinned to %s.", configuredName)
    }
}

/** Routing-description editor ownership: generating never silently replaces an unsaved draft. */
object KanbanProfileDescriptionPolicy {
    data class Snapshot(val profile: String, val description: String, val isAuto: Boolean)
    data class CompletionOutcome(
        val baselineDescription: String,
        val baselineIsAuto: Boolean,
        val draft: String,
        val isDirtyAfter: Boolean,
        val notice: String?,
    )
    enum class GenerateResolution { ALLOWED, REQUIRES_DISCARD }

    fun isDirty(draft: String, baseline: String) = draft != baseline
    fun resolveGenerate(draft: String, baseline: String) =
        if (isDirty(draft, baseline)) GenerateResolution.REQUIRES_DISCARD else GenerateResolution.ALLOWED

    fun saveCompletion(submitted: String, currentRawDraft: String, currentTrimmedDraft: String): CompletionOutcome =
        if (currentTrimmedDraft == submitted) {
            CompletionOutcome(submitted, false, submitted, false, L10n.tr("Description saved."))
        } else {
            CompletionOutcome(submitted, false, currentRawDraft, true, L10n.tr("Description saved. Your newer edits are still unsaved."))
        }

    fun generateCompletion(submittedDraft: String, currentRawDraft: String, currentTrimmedDraft: String, generated: String): CompletionOutcome =
        if (currentTrimmedDraft == submittedDraft) {
            CompletionOutcome(generated, true, generated, false, L10n.tr("Generated automatically — review recommended."))
        } else {
            CompletionOutcome(
                generated, true, currentRawDraft, true,
                L10n.tr("Hermes generated a new routing description. Your newer local edits were preserved and are still unsaved."),
            )
        }
}

object KanbanNudgePolicy {
    fun shouldShowNotice(captured: KanbanBoardContextStamp, current: KanbanBoardContextStamp?, isSnapshotActionable: Boolean) =
        isSnapshotActionable && current == captured
}

// endregion

// region V3B: board administration, filters, grouping

object KanbanBoardSlugPolicy {
    /** Desktop: `name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')`. */
    fun derivedSlug(name: String): String {
        val lowered = name.trim().lowercase(Locale.ROOT)
        val out = StringBuilder()
        var pendingDash = false
        for (c in lowered) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (pendingDash && out.isNotEmpty()) out.append('-')
                pendingDash = false
                out.append(c)
            } else {
                pendingDash = true
            }
        }
        return out.toString()
    }

    /** Backend regex `^[a-z0-9][a-z0-9\-_]{0,63}$`. */
    fun isValid(slug: String): Boolean = Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(slug)
}

sealed interface KanbanDefaultWorkspaceChoice {
    data object ProjectDefault : KanbanDefaultWorkspaceChoice
    data object None : KanbanDefaultWorkspaceChoice
    data class Custom(val path: String) : KanbanDefaultWorkspaceChoice
}

data class KanbanBoardEditorDraft(
    val name: String,
    val description: String,
    val projectId: String?,
    val workspace: KanbanDefaultWorkspaceChoice,
)

object KanbanBoardPatchPolicy {
    fun primaryPath(projectId: String?, projects: List<KanbanProject>): String? {
        if (projectId.isNullOrEmpty()) return null
        return projects.firstOrNull { it.id == projectId }?.primaryPath?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun patch(baseline: KanbanBoardMetadata, draft: KanbanBoardEditorDraft, projects: List<KanbanProject>): KanbanUpdateBoardPatch {
        var patch = KanbanUpdateBoardPatch()
        val baselineProject = baseline.projectId?.takeIf { it.isNotEmpty() }
        val baselineWorkdir = baseline.defaultWorkdir?.takeIf { it.isNotEmpty() }
        val trimmedName = draft.name.trim()
        if (trimmedName != (baseline.name ?: "")) patch = patch.copy(name = trimmedName)
        if (draft.description != (baseline.description ?: "")) patch = patch.copy(description = draft.description)
        val draftProject = draft.projectId?.takeIf { it.isNotEmpty() }
        val projectChanged = draftProject != baselineProject
        if (projectChanged) patch = patch.copy(projectId = draftProject ?: "")
        when (val workspace = draft.workspace) {
            KanbanDefaultWorkspaceChoice.ProjectDefault -> {
                // Re-sending the same project re-mirrors its primary repo into the workdir.
                if (!projectChanged && draftProject != null && baselineWorkdir != primaryPath(draftProject, projects)) {
                    patch = patch.copy(projectId = draftProject)
                }
            }
            KanbanDefaultWorkspaceChoice.None -> {
                if (projectChanged || baselineWorkdir != null) patch = patch.copy(defaultWorkdir = "")
            }
            is KanbanDefaultWorkspaceChoice.Custom -> {
                val path = workspace.path.trim()
                if (projectChanged || path != (baselineWorkdir ?: "")) patch = patch.copy(defaultWorkdir = path)
            }
        }
        return patch
    }

    fun createRequest(slug: String, name: String, description: String, projectId: String?, workspace: KanbanDefaultWorkspaceChoice): KanbanCreateBoardRequest {
        val workdir = (workspace as? KanbanDefaultWorkspaceChoice.Custom)?.path?.trim()?.takeIf { it.isNotEmpty() }
        return KanbanCreateBoardRequest(
            slug = slug,
            name = name.ifEmpty { null },
            description = description.ifEmpty { null },
            defaultWorkdir = workdir,
            projectId = projectId?.takeIf { it.isNotEmpty() },
        )
    }
}

object KanbanBoardWorkspacePresentation {
    fun derive(projectId: String?, defaultWorkdir: String?, projects: List<KanbanProject>): KanbanDefaultWorkspaceChoice {
        val project = projectId?.takeIf { it.isNotEmpty() }
        val workdir = defaultWorkdir?.takeIf { it.isNotEmpty() }
        if (project != null) {
            val primary = KanbanBoardPatchPolicy.primaryPath(project, projects)
            return if (workdir != null && workdir != primary) KanbanDefaultWorkspaceChoice.Custom(workdir) else KanbanDefaultWorkspaceChoice.ProjectDefault
        }
        return if (workdir != null) KanbanDefaultWorkspaceChoice.Custom(workdir) else KanbanDefaultWorkspaceChoice.None
    }

    fun derive(board: KanbanBoardMetadata, projects: List<KanbanProject>) = derive(board.projectId, board.defaultWorkdir, projects)

    fun previewDirectory(workspace: KanbanDefaultWorkspaceChoice, projectId: String?, projects: List<KanbanProject>): String? = when (workspace) {
        is KanbanDefaultWorkspaceChoice.Custom -> workspace.path.trim().ifEmpty { null }
        KanbanDefaultWorkspaceChoice.ProjectDefault -> KanbanBoardPatchPolicy.primaryPath(projectId?.takeIf { it.isNotEmpty() }, projects)
        KanbanDefaultWorkspaceChoice.None -> null
    }
}

object KanbanBoardFilterPolicy {
    fun matchesSearch(task: KanbanTask, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return task.title.lowercase().contains(q) || task.body.orEmpty().lowercase().contains(q) ||
            task.id.lowercase().contains(q) || task.latestSummary.orEmpty().lowercase().contains(q) ||
            task.assignee.orEmpty().lowercase().contains(q)
    }

    fun matchesAssignee(task: KanbanTask, assignee: String?) = assignee.isNullOrEmpty() || task.assignee == assignee
    fun matchesTenant(task: KanbanTask, tenant: String?) = tenant.isNullOrEmpty() || task.tenant == tenant
    fun matches(task: KanbanTask, search: String, assignee: String?, tenant: String?) =
        matchesSearch(task, search) && matchesAssignee(task, assignee) && matchesTenant(task, tenant)

    /** A filter whose value left the roster resets harmlessly to "All". */
    fun validatedFilter(value: String?, available: List<String>): String? =
        value?.takeIf { it.isNotEmpty() && it in available }

    fun visibleColumns(board: KanbanBoard, search: String, assignee: String?, tenant: String?): List<KanbanColumn> {
        val a = validatedFilter(assignee, board.assignees)
        val t = validatedFilter(tenant, board.tenants)
        val columns = KanbanStatusPresentation.orderedColumns(board.columns)
        if (search.isBlank() && a == null && t == null) return columns
        return columns.map { column -> column.copy(tasks = column.tasks.filter { matches(it, search, a, t) }) }
    }
}

object KanbanRunningGroupPolicy {
    data class Group(val key: String, val displayName: String, val tasks: List<KanbanTask>)

    const val UNASSIGNED_KEY = "unassigned"

    fun shouldApply(lane: String, enabled: Boolean) = lane == "running" && enabled

    fun group(tasks: List<KanbanTask>): List<Group> {
        val buckets = LinkedHashMap<String, MutableList<KanbanTask>>()
        tasks.forEach { task -> buckets.getOrPut(task.assignee?.takeIf { it.isNotEmpty() } ?: UNASSIGNED_KEY) { mutableListOf() }.add(task) }
        val keys = buckets.keys.sortedWith { l, r ->
            val order = l.compareTo(r, ignoreCase = true)
            if (order != 0) order else l.compareTo(r)
        }
        return keys.map { key -> Group(key, if (key == UNASSIGNED_KEY) L10n.tr("Unassigned") else key, buckets.getValue(key)) }
    }
}

object KanbanBoardAdminMessage {
    fun archiveRefreshFailureMessage(detail: String) =
        L10n.tr("The board was archived, but the board list could not be refreshed. %s", detail)
}

// endregion

// region V3C: bulk operations

sealed interface KanbanBulkAction {
    data class Move(val status: String) : KanbanBulkAction
    data class Assign(val profile: String?) : KanbanBulkAction
    data class Priority(val value: Int) : KanbanBulkAction
    data object Archive : KanbanBulkAction
    data object Delete : KanbanBulkAction
}

data class PendingBulkOperation(val ids: List<String>, val context: KanbanBoardContextStamp, val action: KanbanBulkAction)

data class BulkStagedSelection(val ids: List<String>, val context: KanbanBoardContextStamp)

object KanbanBulkSelectionPolicy {
    fun isOwned(selectionContext: KanbanBoardContextStamp?, currentStamp: KanbanBoardContextStamp?, isSnapshotActionable: Boolean) =
        selectionContext != null && currentStamp != null && isSnapshotActionable && selectionContext == currentStamp

    fun prune(selected: Set<String>, aliveTaskIds: Set<String>) = selected intersect aliveTaskIds
}

object KanbanBulkStagePolicy {
    fun priorityOperation(staged: BulkStagedSelection?, value: Int, currentStamp: KanbanBoardContextStamp?, isSnapshotActionable: Boolean): PendingBulkOperation? {
        if (staged == null || staged.context != currentStamp || !isSnapshotActionable) return null
        return PendingBulkOperation(staged.ids, staged.context, KanbanBulkAction.Priority(value))
    }
}

object KanbanBulkResultPolicy {
    fun normalizedIds(ids: List<String>): List<String> = KanbanComposerValidator.sanitized(ids)

    /** Reconcile strictly by id: a missing outcome is a failure; duplicates — first wins; extras ignored. */
    fun reconcile(requestedIds: List<String>, results: List<KanbanBulkTaskResult>): KanbanBulkOperationOutcome {
        val byId = LinkedHashMap<String, KanbanBulkTaskResult>()
        results.filter { it.id.isNotEmpty() }.forEach { byId.putIfAbsent(it.id, it) }
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<KanbanBulkFailure>()
        requestedIds.filter { it.isNotEmpty() }.forEach { id ->
            val result = byId[id]
            when {
                result == null -> failures += KanbanBulkFailure(id, L10n.tr("Hermes returned no result for this task."))
                result.ok -> succeeded += id
                else -> failures += KanbanBulkFailure(id, result.error ?: L10n.tr("Hermes refused the change."))
            }
        }
        return KanbanBulkOperationOutcome(succeeded, failures)
    }

    fun summary(outcome: KanbanBulkOperationOutcome): String {
        val updated = outcome.succeededIds.size
        val failed = outcome.failures.size
        return when {
            updated == 0 && failed == 0 -> L10n.tr("No tasks updated")
            updated == 0 -> L10n.tr("%d tasks failed", failed)
            failed == 0 -> L10n.tr("%d tasks updated", updated)
            else -> L10n.tr("%d updated", updated) + ", " + L10n.tr("%d failed", failed)
        }
    }

    /** Successful ids leave the selection; failed ones stay for a retry. */
    fun selectionAfterApplying(outcome: KanbanBulkOperationOutcome, originalSelection: Set<String>): Set<String> =
        originalSelection intersect outcome.failures.map { it.id }.toSet()
}

object KanbanBulkDestinationPolicy {
    /** Bulk archive is its own control: move never offers locked lanes or Archived. */
    fun moveDestinations() = KanbanStatusPresentation.manuallySelectableStatuses.filter { it.rawValue != "archived" }
}

// endregion

// region V3D: live updates

data class KanbanEventInvalidation(
    val revision: Int,
    val context: KanbanBoardContextStamp,
    val taskIds: Set<String>,
    val boardInvalidated: Boolean,
)

object KanbanLiveUpdatePolicy {
    const val COALESCING_WINDOW_MS = 300L
    const val DEFERRED_RETRY_MS = 750L
    val reconnectBackoffMs = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    fun reconnectDelayMs(consecutiveFailures: Int): Long =
        reconnectBackoffMs[consecutiveFailures.coerceIn(0, reconnectBackoffMs.size - 1)]

    fun isValidInitialWatermark(value: Long?) = value != null && value >= 0

    fun initialWatermark(board: KanbanBoard?): Long? = board?.latestEventId?.takeIf { isValidInitialWatermark(it) }

    /** Only invalidations for the current actionable context that touch the displayed task wake a detail screen. */
    fun shouldRefreshDetail(
        invalidation: KanbanEventInvalidation?,
        currentStamp: KanbanBoardContextStamp?,
        isSnapshotActionable: Boolean,
        displayedTaskId: String?,
    ): Boolean {
        if (invalidation == null || currentStamp == null || displayedTaskId.isNullOrEmpty()) return false
        if (!isSnapshotActionable || invalidation.context != currentStamp) return false
        return displayedTaskId in invalidation.taskIds
    }
}

// endregion

