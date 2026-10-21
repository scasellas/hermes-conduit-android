package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.KanbanActivityFormatter
import app.hermesconduit.android.model.KanbanAssigneeSelection
import app.hermesconduit.android.model.KanbanBlockedQuestion
import app.hermesconduit.android.model.KanbanBoard
import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanBoardEditorDraft
import app.hermesconduit.android.model.KanbanBoardFilterPolicy
import app.hermesconduit.android.model.KanbanBoardMetadata
import app.hermesconduit.android.model.KanbanBoardPatchPolicy
import app.hermesconduit.android.model.KanbanBoardSlugPolicy
import app.hermesconduit.android.model.KanbanBoardWorkspacePresentation
import app.hermesconduit.android.model.KanbanBulkDestinationPolicy
import app.hermesconduit.android.model.KanbanBulkFailure
import app.hermesconduit.android.model.KanbanBulkOperationOutcome
import app.hermesconduit.android.model.KanbanBulkResultPolicy
import app.hermesconduit.android.model.KanbanBulkSelectionPolicy
import app.hermesconduit.android.model.KanbanBulkStagePolicy
import app.hermesconduit.android.model.KanbanBulkTaskResult
import app.hermesconduit.android.model.BulkStagedSelection
import app.hermesconduit.android.model.KanbanCardLiveness
import app.hermesconduit.android.model.KanbanColumn
import app.hermesconduit.android.model.KanbanCompactDuration
import app.hermesconduit.android.model.KanbanComposerDraft
import app.hermesconduit.android.model.KanbanComposerValidator
import app.hermesconduit.android.model.KanbanDefaultWorkspaceChoice
import app.hermesconduit.android.model.KanbanDetailIdentityPolicy
import app.hermesconduit.android.model.KanbanDraftValidationException
import app.hermesconduit.android.model.KanbanEvent
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.model.KanbanLanePolicy
import app.hermesconduit.android.model.KanbanLiveUpdatePolicy
import app.hermesconduit.android.model.KanbanModelOverrideSession
import app.hermesconduit.android.model.KanbanModelOverrideSessionPolicy
import app.hermesconduit.android.model.KanbanNoteAndRequeueFlow
import app.hermesconduit.android.model.KanbanNudgePolicy
import app.hermesconduit.android.model.KanbanOrchestrationDisplay
import app.hermesconduit.android.model.KanbanProfileDescriptionPolicy
import app.hermesconduit.android.model.KanbanProject
import app.hermesconduit.android.model.KanbanRun
import app.hermesconduit.android.model.KanbanRunPresentation
import app.hermesconduit.android.model.KanbanRunningGroupPolicy
import app.hermesconduit.android.model.KanbanShortId
import app.hermesconduit.android.model.KanbanStatusPresentation
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskDetail
import app.hermesconduit.android.model.KanbanTriageActionsPolicy
import app.hermesconduit.android.model.KanbanTriageCompletionPolicy
import app.hermesconduit.android.model.CompletedTriageMutation
import app.hermesconduit.android.model.KanbanTriagePolicy
import app.hermesconduit.android.model.KanbanWorkerLogPolicy
import app.hermesconduit.android.model.KanbanWorkspaceKind
import app.hermesconduit.android.model.PendingTriageAction
import app.hermesconduit.android.model.TaskModelOverride
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-policy cases ported from ConduitTests/Kanban*Tests.swift. */
class KanbanPoliciesTest {
    private fun json(text: String): JsonElement = ConduitJson.parseToJsonElement(text)
    private fun task(id: String, status: String = "todo", title: String = id, assignee: String? = null, tenant: String? = null, body: String? = null, summary: String? = null) =
        KanbanTask(id = id, title = title, status = status, assignee = assignee, tenant = tenant, body = body, latestSummary = summary)
    private val stampA = KanbanBoardContextStamp("alpha", 1)
    private val stampB = KanbanBoardContextStamp("beta", 1)

    // region Status matrix

    @Test
    fun lockedLanesMatchUpstreamAndAreNotManualDestinations() {
        assertEquals(listOf("review", "running", "scheduled"), KanbanStatusPresentation.lockedDestinations)
        for (status in KanbanStatusPresentation.lockedDestinations) {
            assertFalse(KanbanStatusPresentation.canSelectManually(status))
            assertFalse(KanbanStatusPresentation.canCreateTask(status))
        }
        assertEquals(
            listOf("triage", "todo", "ready", "blocked", "done", "archived"),
            KanbanStatusPresentation.manuallySelectableStatuses.map { it.rawValue },
        )
    }

    @Test
    fun unlockedCreationTargetsMatchUpstreamAddableColumns() {
        assertEquals(listOf("triage", "todo", "ready", "blocked", "done"), KanbanStatusPresentation.taskCreatableStatuses.map { it.rawValue })
        assertFalse("archived is a move target, not a creation target", KanbanStatusPresentation.canCreateTask("archived"))
    }

    @Test
    fun unknownStatusHasSafeLockedFallback() {
        val p = KanbanStatusPresentation.forStatus("waiting_on_human")
        assertEquals("Waiting On Human", p.displayName)
        assertTrue(p.isBackendControlled)
        assertFalse(KanbanStatusPresentation.canSelectManually("waiting_on_human"))
        assertFalse(KanbanStatusPresentation.canCreateTask("waiting_on_human"))
        assertEquals(100, p.sortOrder)
    }

    @Test
    fun columnsOrderBySortOrder() {
        val ordered = KanbanStatusPresentation.orderedColumns(listOf(KanbanColumn("done"), KanbanColumn("zeta"), KanbanColumn("triage"), KanbanColumn("running")))
        assertEquals(listOf("triage", "running", "done", "zeta"), ordered.map { it.name })
    }

    // endregion

    // region Lanes and small helpers

    @Test
    fun globalPlusUsesEffectiveVisibleLaneNotRawSelection() {
        val columns = listOf(KanbanColumn("triage"), KanbanColumn("ready"), KanbanColumn("running"))
        assertEquals("triage", KanbanLanePolicy.effectiveSelectedLane(null, columns))
        assertEquals("ready", KanbanLanePolicy.effectiveSelectedLane("ready", columns))
        assertEquals("missing lane falls back", "triage", KanbanLanePolicy.effectiveSelectedLane("archived", columns))
        assertEquals("locked lane collapses to todo", "todo", KanbanLanePolicy.newTaskInitialStatus("running"))
        assertEquals("ready", KanbanLanePolicy.newTaskInitialStatus("ready"))
        assertEquals("todo", KanbanLanePolicy.newTaskInitialStatus(null))
    }

    @Test
    fun allLockedBoardStillResolvesFirstLane() {
        assertEquals("running", KanbanLanePolicy.effectiveSelectedLane(null, listOf(KanbanColumn("running"), KanbanColumn("review"))))
        assertNull(KanbanLanePolicy.effectiveSelectedLane("x", emptyList()))
    }

    @Test
    fun shortIdHelperMatchesUpstreamSemantics() {
        assertEquals("abcdef", KanbanShortId.of("t_abcdef123456"))
        assertEquals("abcdef", KanbanShortId.of("abcdef123456"))
        assertEquals("ab", KanbanShortId.of("t_ab"))
        assertEquals("", KanbanShortId.of(null))
    }

    // endregion

    // region Composer

    @Test
    fun assigneeSelectionMatchesUpstreamWireSemantics() {
        assertEquals("ops", KanbanAssigneeSelection.InheritDefault.requestValue("ops"))
        assertEquals("default", KanbanAssigneeSelection.InheritDefault.requestValue("  "))
        assertEquals("arch", KanbanAssigneeSelection.Profile("arch").requestValue("ops"))
        assertNull("parked omits the field", KanbanAssigneeSelection.Parked.requestValue("ops"))
    }

    @Test
    fun boardDefaultWorkspaceKindInitializesComposer() {
        assertEquals(KanbanWorkspaceKind.WORKTREE, KanbanWorkspaceKind.initialKind(" Worktree "))
        assertEquals(KanbanWorkspaceKind.SCRATCH, KanbanWorkspaceKind.initialKind("weird"))
        assertEquals(KanbanWorkspaceKind.SCRATCH, KanbanWorkspaceKind.initialKind(null))
    }

    @Test
    fun scratchWithPathIsAnInvalidCombination() {
        assertThrows(KanbanDraftValidationException::class.java) {
            KanbanComposerValidator.validate(KanbanComposerDraft(title = "T", workspacePath = "/x"))
        }
        val request = KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", workspaceKind = KanbanWorkspaceKind.DIR, workspacePath = " /repo "), "ops")
        assertEquals("dir", request.workspaceKind)
        assertEquals("/repo", request.workspacePath)
    }

    @Test
    fun modelOverrideInheritsByOmittingCreateFields() {
        val request = KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T"), "ops")
        assertNull(request.modelOverride)
        assertNull(request.providerOverride)
        assertNull(request.reasoningEffort)
        val custom = KanbanComposerValidator.makeRequest(
            KanbanComposerDraft(title = "T", modelOverride = TaskModelOverride(" anthropic ", " claude ", "high")), "ops",
        )
        assertEquals("claude", custom.modelOverride)
        assertEquals("anthropic", custom.providerOverride)
        assertEquals("high", custom.reasoningEffort)
    }

    @Test
    fun reasoningEffortNoneIsARealValueAndUnknownIsRejected() {
        val ok = KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", modelOverride = TaskModelOverride(reasoningEffort = "none")), "d")
        assertEquals("none", ok.reasoningEffort)
        assertThrows(KanbanDraftValidationException::class.java) {
            KanbanComposerValidator.validate(KanbanComposerDraft(title = "T", modelOverride = TaskModelOverride(reasoningEffort = "turbo")))
        }
    }

    @Test
    fun modelOverridePatchCarriesValuesAndClearFlags() {
        val current = task("t").copy(modelOverride = "old", providerOverride = "p", reasoningEffort = "low")
        val cleared = TaskModelOverride.patch(current, TaskModelOverride())
        assertTrue(cleared.clearModelOverride && cleared.clearReasoningEffort)
        val modelOnly = TaskModelOverride.patch(task("t"), TaskModelOverride(model = "m"))
        assertEquals("m", modelOnly.modelOverride)
        assertNull("model-only omits provider so the backend nulls it", modelOnly.providerOverride)
        assertFalse(modelOnly.clearReasoningEffort)
    }

    @Test
    fun modelOverrideLabel() {
        assertEquals("Inherit", TaskModelOverride().label("Inherit"))
        assertEquals("anthropic: claude · Extra High", TaskModelOverride("anthropic", "claude", "xhigh").label("Inherit"))
        assertEquals("Inherit · Low", TaskModelOverride(reasoningEffort = "low").label("Inherit"))
    }

    @Test
    fun selectedSkillsSerializeTrimmedAndDeduplicated() {
        val request = KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", skills = listOf(" github ", "github", "", "translation")), "d")
        assertEquals(listOf("github", "translation"), request.skills)
        assertNull(KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", skills = listOf(" ")), "d").skills)
    }

    @Test
    fun goalModeSerializesWithOptionalMaxTurnsAndRejectsZero() {
        assertEquals(10, KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", goalMode = true, goalMaxTurns = 10), "d").goalMaxTurns)
        assertNull(KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = "T", goalMode = false, goalMaxTurns = 10), "d").goalMaxTurns)
        assertThrows(KanbanDraftValidationException::class.java) {
            KanbanComposerValidator.validate(KanbanComposerDraft(title = "T", goalMode = true, goalMaxTurns = 0))
        }
    }

    @Test
    fun parentsTitlePriorityValidation() {
        assertThrows(KanbanDraftValidationException::class.java) {
            KanbanComposerValidator.validate(KanbanComposerDraft(title = "T", parents = listOf("a", " a")))
        }
        assertThrows(KanbanDraftValidationException::class.java) { KanbanComposerValidator.validate(KanbanComposerDraft(title = "  ")) }
        val request = KanbanComposerValidator.makeRequest(KanbanComposerDraft(title = " T ", body = "  ", priority = -4, parents = listOf("p1")), "d")
        assertEquals("T", request.title)
        assertNull(request.body)
        assertEquals(0, request.priority)
        assertEquals(listOf("p1"), request.parents)
    }

    // endregion

    // region Activity, blocked question, runs

    private fun event(kind: String, payload: String = "{}", createdAt: Long? = null, id: String = kind) =
        KanbanEvent(id = id, taskId = "t", kind = kind, payload = json(payload), createdAt = createdAt)

    @Test
    fun knownActivityEventsMapToHumanRows() {
        assertEquals("Moved to Ready", KanbanActivityFormatter.row(event("status", """{"status":"ready"}""")).label)
        assertEquals("Assigned to arch", KanbanActivityFormatter.row(event("assigned", """{"assignee":"arch"}""")).label)
        assertEquals("Unassigned", KanbanActivityFormatter.row(event("assigned")).label)
        val spawned = KanbanActivityFormatter.row(event("spawned", """{"pid":4242}"""))
        assertEquals("Worker started", spawned.label)
        assertEquals("pid 4242", spawned.detail)
        assertEquals("Claimed from review", KanbanActivityFormatter.row(event("claimed", """{"source_status":"review"}""")).label)
        assertEquals("as Ready for ops", KanbanActivityFormatter.row(event("created", """{"status":"ready","assignee":"ops"}""")).detail)
    }

    @Test
    fun unknownActivityEventGetsGracefulFallback() {
        val row = KanbanActivityFormatter.row(event("quantum_sync", """{"shard":"a","level":2,"nested":{}}"""))
        assertEquals("Quantum Sync", row.label)
        assertEquals("level=2 shard=a", row.detail)
        val empty = KanbanActivityFormatter.row(event("mystery"))
        assertEquals("Mystery", empty.label)
        assertNull(empty.detail)
    }

    @Test
    fun parentReopenedReasonIsExplained() {
        val row = KanbanActivityFormatter.row(event("status", """{"status":"blocked","reason":"parent_reopened","parent":"t_p"}"""))
        assertEquals("parent t_p reopened", row.detail)
    }

    private fun blocked(events: List<KanbanEvent> = emptyList(), runs: List<KanbanRun> = emptyList()) =
        KanbanTaskDetail(task = task("t", "blocked"), events = events, runs = runs)

    @Test
    fun blockedQuestionIsNewestBlockedEventReason() {
        val d = blocked(listOf(
            event("blocked", """{"reason":"Which API key?"}""", 200, "2"),
            event("blocked", """{"reason":"old question"}""", 100, "1"),
            event("commented", """{"author":"me"}""", 300, "3"),
        ))
        assertEquals("Which API key?", KanbanBlockedQuestion.reason(d))
    }

    @Test
    fun blockedQuestionTieGoesToTheFirstEventAndUntimedIsNewest() {
        assertEquals("new question", KanbanBlockedQuestion.reason(blocked(listOf(
            event("blocked", """{"reason":"new question"}""", 100, "2"),
            event("blocked", """{"reason":"old question"}""", 100, "1"),
        ))))
        assertEquals("untimed question", KanbanBlockedQuestion.reason(blocked(listOf(
            event("blocked", """{"reason":"untimed question"}""", null, "2"),
            event("blocked", """{"reason":"old question"}""", 100, "1"),
        ))))
    }

    @Test
    fun oldQuestionIsStaleOnceANewerTransitionFollows() {
        assertNull(KanbanBlockedQuestion.reason(blocked(listOf(
            event("status", """{"status":"blocked","reason":"parent_reopened"}""", 300, "3"),
            event("unblocked", """{"status":"ready"}""", 200, "2"),
            event("blocked", """{"reason":"old question"}""", 100, "1"),
        ))))
        assertEquals("a same-second status row is the block itself", "Which branch?", KanbanBlockedQuestion.reason(blocked(listOf(
            event("status", """{"status":"blocked"}""", 100, "2"),
            event("blocked", """{"reason":"Which branch?"}""", 100, "1"),
        ))))
        assertEquals("Which branch?", KanbanBlockedQuestion.reason(blocked(listOf(
            event("commented", """{"author":"me"}""", 200, "2"),
            event("blocked", """{"reason":"Which branch?"}""", 100, "1"),
        ))))
    }

    @Test
    fun blockedQuestionRunFallback() {
        val staleRun = KanbanRun(id = "r1", status = "done", outcome = "blocked", summary = "stale run question", endedAt = 50)
        assertNull("manual block without reason hides an older run's question", KanbanBlockedQuestion.reason(blocked(listOf(event("blocked", createdAt = 100)), listOf(staleRun))))
        val runs = listOf(
            KanbanRun(id = "r1", status = "done", outcome = "blocked", summary = "first", endedAt = 100),
            KanbanRun(id = "r2", status = "blocked", summary = "Need the repo URL", error = "traceback", endedAt = 200),
            KanbanRun(id = "r3", status = "done", outcome = "completed", summary = "done", endedAt = 300),
        )
        assertEquals("Need the repo URL", KanbanBlockedQuestion.reason(blocked(runs = runs)))
        val region = listOf(KanbanRun(id = "r1", status = "done", outcome = "blocked", summary = "Which region?", endedAt = 200))
        assertEquals("Which region?", KanbanBlockedQuestion.reason(blocked(listOf(event("claimed", createdAt = 100)), region)))
        assertNull(KanbanBlockedQuestion.reason(blocked(listOf(event("unblocked", createdAt = 300)), region)))
        assertNull("errors are never the question", KanbanBlockedQuestion.reason(blocked(runs = listOf(KanbanRun(id = "r", status = "done", outcome = "blocked", error = "Traceback", endedAt = 100)))))
    }

    @Test
    fun runPresentation() {
        for (outcome in listOf("crashed", "failed", "timed_out", "gave_up")) assertTrue(KanbanRunPresentation.isFailed(KanbanRun(id = "r", outcome = outcome)))
        assertFalse(KanbanRunPresentation.isFailed(KanbanRun(id = "r", outcome = "completed")))
        assertTrue(KanbanRunPresentation.isFailed(KanbanRun(id = "r", status = "failed")))
        assertEquals("30s", KanbanRunPresentation.durationText(100, 130))
        assertEquals("2m", KanbanRunPresentation.durationText(100, 220))
        assertEquals("1h", KanbanRunPresentation.durationText(100, 5000))
        assertEquals("1h", KanbanCompactDuration.text(7_199))
        assertEquals("1d", KanbanCompactDuration.text(90_000))
        assertEquals("0s", KanbanCompactDuration.text(0))
        assertNull(KanbanRunPresentation.durationText(null, 200))
        assertNull(KanbanRunPresentation.durationText(300, 100))
        assertEquals("timed out", KanbanRunPresentation.outcomeLabel(KanbanRun(id = "r", outcome = "timed_out")))
    }

    @Test
    fun cardLiveness() {
        val now = 1_000L
        assertEquals(KanbanCardLiveness.State.STALE, KanbanCardLiveness.state(task("t", "running").copy(lastHeartbeatAt = 800), now))
        assertEquals(KanbanCardLiveness.State.RUNNING, KanbanCardLiveness.state(task("t", "running").copy(lastHeartbeatAt = 950), now))
        assertEquals(KanbanCardLiveness.State.QUEUED, KanbanCardLiveness.state(task("t", "ready", assignee = "ops"), now))
        assertNull(KanbanCardLiveness.state(task("t", "ready"), now))
    }

    @Test
    fun workerLogRenderTailCapsAndKeepsNewest() {
        val lines = (1..20_000).joinToString("\n") { "line $it" }
        val rendered = KanbanWorkerLogPolicy.renderTail(lines)
        assertTrue(rendered.length <= KanbanWorkerLogPolicy.MAX_RENDERED_CHARACTERS)
        assertTrue(rendered.startsWith("[older output omitted]\n"))
        assertTrue(rendered.endsWith("line 20000"))
        val noNewlines = "x".repeat(100_000)
        assertTrue(KanbanWorkerLogPolicy.renderTail(noNewlines).length <= KanbanWorkerLogPolicy.MAX_RENDERED_CHARACTERS)
        assertEquals("short", KanbanWorkerLogPolicy.renderTail("short"))
    }

    // endregion

    // region Detail identity, model sessions, note & requeue

    @Test
    fun dependencyNavigationNeverExposesPreviousTaskAsActionable() {
        val initial = task("a")
        assertEquals(initial, KanbanDetailIdentityPolicy.actionableTask("a", null, initial))
        assertNull(KanbanDetailIdentityPolicy.actionableTask("b", null, initial))
        assertNull("a stale detail for another id is never actionable", KanbanDetailIdentityPolicy.actionableTask("b", task("a"), initial))
        assertEquals("b", KanbanDetailIdentityPolicy.actionableTask("b", task("b"), initial)?.id)
    }

    @Test
    fun modelSheetCommitsOnlyRealEditsForTheSameTask() {
        val server = task("t").copy(modelOverride = "m")
        val baseline = TaskModelOverride.of(server)
        val session = KanbanModelOverrideSession("t", baseline)
        assertNull("no edit, no write", KanbanModelOverrideSessionPolicy.commitTarget(session, baseline, server, "t"))
        assertNull("another task identity never commits", KanbanModelOverrideSessionPolicy.commitTarget(session, TaskModelOverride(model = "x"), task("u"), "u"))
        val moved = server.copy(modelOverride = "x")
        assertNull("edit matching the moved server value writes nothing", KanbanModelOverrideSessionPolicy.commitTarget(session, TaskModelOverride(model = "x"), moved, "t"))
        val target = KanbanModelOverrideSessionPolicy.commitTarget(session, TaskModelOverride(model = "y"), moved, "t")
        assertEquals("y", target?.value?.model)
        assertEquals("diffed against current server", "x", target?.startedTask?.modelOverride)
    }

    @Test
    fun noteAndRequeueFlowOutcomes() = runTest {
        var cleared = false
        val ok = KanbanNoteAndRequeueFlow.perform("hi", {}, {}, { cleared = true }, { it.message ?: "" })
        assertTrue(ok.commentPosted && ok.requeued && cleared)
        assertNull(KanbanNoteAndRequeueFlow.message(ok))

        cleared = false
        val noteFailed = KanbanNoteAndRequeueFlow.perform("hi", { throw IllegalStateException("offline") }, {}, { cleared = true }, { it.message ?: "" })
        assertFalse(cleared)
        assertEquals("offline", KanbanNoteAndRequeueFlow.message(noteFailed))

        val partial = KanbanNoteAndRequeueFlow.perform("hi", {}, { throw IllegalStateException("409") }, {}, { it.message ?: "" })
        assertEquals("The note was posted, but the task could not be requeued. (409)", KanbanNoteAndRequeueFlow.message(partial))
        assertEquals(
            "The reply was posted, but the task could not be unblocked. (409)",
            KanbanNoteAndRequeueFlow.message(partial, KanbanNoteAndRequeueFlow.Transition.UNBLOCK),
        )
    }

    // endregion

    // region V3A

    @Test
    fun triageActionsGateStrictlyOnTriageStatus() {
        assertTrue(KanbanTriagePolicy.isEligible("triage"))
        for (s in KanbanStatusPresentation.knownStatuses - "triage") assertFalse(KanbanTriagePolicy.isEligible(s))
        assertFalse(KanbanTriagePolicy.isEligible(null as KanbanTask?))
    }

    @Test
    fun pendingTriageActionCaptureFreezesIdentityAndStamp() {
        assertNull(PendingTriageAction.capture(task("t"), false, stampA))
        assertNull(PendingTriageAction.capture(task(""), true, stampA))
        assertEquals(PendingTriageAction("t", stampA), PendingTriageAction.capture(task("t"), true, stampA))
    }

    @Test
    fun triageCompletionSuppressionAndClearing() {
        val done = CompletedTriageMutation("t", stampA)
        assertTrue(KanbanTriageCompletionPolicy.isSuppressed(done, "t", stampA))
        assertFalse(KanbanTriageCompletionPolicy.isSuppressed(done, "t", stampB))
        assertFalse(KanbanTriageCompletionPolicy.shouldClearAfterReconciliation("triage"))
        assertTrue(KanbanTriageCompletionPolicy.shouldClearAfterReconciliation("todo"))
    }

    @Test
    fun decomposeNoticesAndPartialSuccessWording() {
        assertEquals("Decomposed into 3 task", KanbanTriageActionsPolicy.successNotice(true, 3))
        assertEquals("Decomposed (single task, no fan-out)", KanbanTriageActionsPolicy.successNotice(false, 0))
        assertEquals("Task specified", KanbanTriageActionsPolicy.successNoticeWithRefreshFailure("Task specified", " "))
        assertEquals(
            "Task specified, but the board could not be refreshed. offline",
            KanbanTriageActionsPolicy.successNoticeWithRefreshFailure("Task specified", "offline"),
        )
    }

    @Test
    fun orchestrationDisplayDistinguishesConfiguredFromResolved() {
        assertEquals("Default", KanbanOrchestrationDisplay.defaultOptionLabel(""))
        assertEquals("Default (ops)", KanbanOrchestrationDisplay.defaultOptionLabel(" ops "))
        assertEquals("Default inherits the active Hermes profile.", KanbanOrchestrationDisplay.resolveFootnote("", ""))
        assertEquals("Default resolves to ops.", KanbanOrchestrationDisplay.resolveFootnote("", "ops"))
        assertEquals("Pinned to arch.", KanbanOrchestrationDisplay.resolveFootnote("arch", "ops"))
    }

    @Test
    fun generateNeverOverwritesDirtyDraftWithoutExplicitDiscard() {
        assertEquals(KanbanProfileDescriptionPolicy.GenerateResolution.REQUIRES_DISCARD, KanbanProfileDescriptionPolicy.resolveGenerate("edited", "base"))
        assertEquals(KanbanProfileDescriptionPolicy.GenerateResolution.ALLOWED, KanbanProfileDescriptionPolicy.resolveGenerate("base", "base"))
    }

    @Test
    fun profileSaveAndGeneratePreserveEditsMadeWhilePending() {
        val clean = KanbanProfileDescriptionPolicy.saveCompletion("new", "new ", "new")
        assertFalse(clean.isDirtyAfter)
        assertEquals("new", clean.draft)
        val raced = KanbanProfileDescriptionPolicy.saveCompletion("new", "newer", "newer")
        assertTrue(raced.isDirtyAfter)
        assertEquals("newer", raced.draft)
        assertEquals("new", raced.baselineDescription)
        val generated = KanbanProfileDescriptionPolicy.generateCompletion("old", "old", "old", "generated")
        assertEquals("generated", generated.draft)
        assertTrue(generated.baselineIsAuto)
        val typed = KanbanProfileDescriptionPolicy.generateCompletion("old", "typing", "typing", "generated")
        assertEquals("typing", typed.draft)
        assertEquals("generated", typed.baselineDescription)
        assertTrue(typed.isDirtyAfter)
    }

    @Test
    fun nudgeNoticeOnlyForUnchangedContext() {
        assertTrue(KanbanNudgePolicy.shouldShowNotice(stampA, stampA, true))
        assertFalse(KanbanNudgePolicy.shouldShowNotice(stampA, stampB, true))
        assertFalse(KanbanNudgePolicy.shouldShowNotice(stampA, stampA, false))
    }

    // endregion

    // region V3B

    @Test
    fun slugDerivationMatchesDesktopAlgorithm() {
        assertEquals("my-board", KanbanBoardSlugPolicy.derivedSlug("  My Board! "))
        assertEquals("a-b-c", KanbanBoardSlugPolicy.derivedSlug("--a__b  c--"))
        assertEquals("caf", KanbanBoardSlugPolicy.derivedSlug("Café"))
        assertEquals("", KanbanBoardSlugPolicy.derivedSlug("日本"))
    }

    @Test
    fun slugValidationMatchesBackendRegex() {
        assertTrue(KanbanBoardSlugPolicy.isValid("a"))
        assertTrue(KanbanBoardSlugPolicy.isValid("a-b_c9"))
        assertFalse(KanbanBoardSlugPolicy.isValid(""))
        assertFalse(KanbanBoardSlugPolicy.isValid("-a"))
        assertFalse(KanbanBoardSlugPolicy.isValid("_a"))
        assertFalse(KanbanBoardSlugPolicy.isValid("A"))
        assertTrue(KanbanBoardSlugPolicy.isValid("a".repeat(64)))
        assertFalse(KanbanBoardSlugPolicy.isValid("a".repeat(65)))
    }

    private val projects = listOf(KanbanProject("p1", "p1", "Project", "/repo"), KanbanProject("p2", "p2", "Two", "/two"))
    private fun board(projectId: String? = null, workdir: String? = null, name: String? = "Alpha", description: String? = null) =
        KanbanBoardMetadata(slug = "alpha", name = name, description = description, projectId = projectId, defaultWorkdir = workdir)

    @Test
    fun nameOnlyPatchOmitsProjectAndWorkdir() {
        val base = board("p1", "/repo")
        val patch = KanbanBoardPatchPolicy.patch(base, KanbanBoardEditorDraft("Renamed", "", "p1", KanbanDefaultWorkspaceChoice.ProjectDefault), projects)
        assertEquals("Renamed", patch.name)
        assertNull(patch.projectId)
        assertNull(patch.defaultWorkdir)
        assertNull(patch.description)
    }

    @Test
    fun patchProjectSetAndClear() {
        val set = KanbanBoardPatchPolicy.patch(board(), KanbanBoardEditorDraft("Alpha", "", "p1", KanbanDefaultWorkspaceChoice.ProjectDefault), projects)
        assertEquals("p1", set.projectId)
        assertNull("server mirrors the primary repo", set.defaultWorkdir)
        val clear = KanbanBoardPatchPolicy.patch(board("p1", "/repo"), KanbanBoardEditorDraft("Alpha", "", null, KanbanDefaultWorkspaceChoice.None), projects)
        assertEquals("", clear.projectId)
        assertEquals("No Project also clears the stale mirror", "", clear.defaultWorkdir)
    }

    @Test
    fun patchWorkdirSetAndClearAndCustomInteraction() {
        val custom = KanbanBoardPatchPolicy.patch(board(), KanbanBoardEditorDraft("Alpha", "", null, KanbanDefaultWorkspaceChoice.Custom(" /x ")), projects)
        assertEquals("/x", custom.defaultWorkdir)
        val unchanged = KanbanBoardPatchPolicy.patch(board(workdir = "/x"), KanbanBoardEditorDraft("Alpha", "", null, KanbanDefaultWorkspaceChoice.Custom("/x")), projects)
        assertTrue(unchanged.isEmpty)
        val reassert = KanbanBoardPatchPolicy.patch(board("p1", "/x"), KanbanBoardEditorDraft("Alpha", "", "p2", KanbanDefaultWorkspaceChoice.Custom("/x")), projects)
        assertEquals("p2", reassert.projectId)
        assertEquals("project change reasserts the same custom path", "/x", reassert.defaultWorkdir)
        val noneWithProjectChange = KanbanBoardPatchPolicy.patch(board(), KanbanBoardEditorDraft("Alpha", "", "p1", KanbanDefaultWorkspaceChoice.None), projects)
        assertEquals("", noneWithProjectChange.defaultWorkdir)
    }

    @Test
    fun patchProjectDefaultRemirrorsDriftedWorkdir() {
        val patch = KanbanBoardPatchPolicy.patch(board("p1", "/drifted"), KanbanBoardEditorDraft("Alpha", "", "p1", KanbanDefaultWorkspaceChoice.ProjectDefault), projects)
        assertEquals("p1", patch.projectId)
        assertNull(patch.defaultWorkdir)
    }

    @Test
    fun workspaceDerivationCases() {
        assertEquals(KanbanDefaultWorkspaceChoice.ProjectDefault, KanbanBoardWorkspacePresentation.derive("p1", "/repo", projects))
        assertEquals(KanbanDefaultWorkspaceChoice.Custom("/other"), KanbanBoardWorkspacePresentation.derive("p1", "/other", projects))
        assertEquals(KanbanDefaultWorkspaceChoice.Custom("/x"), KanbanBoardWorkspacePresentation.derive(null, "/x", projects))
        assertEquals(KanbanDefaultWorkspaceChoice.None, KanbanBoardWorkspacePresentation.derive("", "", projects))
        assertEquals("/repo", KanbanBoardWorkspacePresentation.previewDirectory(KanbanDefaultWorkspaceChoice.ProjectDefault, "p1", projects))
        assertNull(KanbanBoardWorkspacePresentation.previewDirectory(KanbanDefaultWorkspaceChoice.None, "p1", projects))
    }

    @Test
    fun createRequestProjectWorkspaceEncoding() {
        val projectDefault = KanbanBoardPatchPolicy.createRequest("alpha", "Alpha", "", "p1", KanbanDefaultWorkspaceChoice.ProjectDefault)
        assertEquals("p1", projectDefault.projectId)
        assertNull(projectDefault.defaultWorkdir)
        assertNull(projectDefault.description)
        val custom = KanbanBoardPatchPolicy.createRequest("alpha", "Alpha", "d", null, KanbanDefaultWorkspaceChoice.Custom(" /x "))
        assertEquals("/x", custom.defaultWorkdir)
        assertEquals("d", custom.description)
    }

    private val filterBoard = KanbanBoard(
        columns = listOf(
            KanbanColumn("ready", listOf(task("t1", "ready", "Fix login", "ops", "acme"), task("t2", "ready", "Docs", "arch", body = "login copy"))),
            KanbanColumn("running", listOf(task("t3", "running", "Ship", null, "acme", summary = "deploying login"))),
        ),
        tenants = listOf("acme"),
        assignees = listOf("ops", "arch"),
    )

    @Test
    fun filtersCoverSearchFieldsAndExactMatches() {
        val search = KanbanBoardFilterPolicy.visibleColumns(filterBoard, "LOGIN", null, null).flatMap { it.tasks }.map { it.id }
        assertEquals(listOf("t1", "t2", "t3"), search)
        assertEquals(listOf("t1"), KanbanBoardFilterPolicy.visibleColumns(filterBoard, "", "ops", null).flatMap { it.tasks }.map { it.id })
        assertEquals(listOf("t1", "t3"), KanbanBoardFilterPolicy.visibleColumns(filterBoard, "", null, "acme").flatMap { it.tasks }.map { it.id })
        assertEquals(listOf("t1"), KanbanBoardFilterPolicy.visibleColumns(filterBoard, "login", "ops", "acme").flatMap { it.tasks }.map { it.id })
        assertTrue(KanbanBoardFilterPolicy.matchesSearch(task("abc123"), "C12"))
        assertTrue(KanbanBoardFilterPolicy.matchesSearch(task("x", assignee = "Archimedes"), "archi"))
    }

    @Test
    fun staleFilterResetsHarmlesslyAndNeverMutatesBoard() {
        assertEquals(3, KanbanBoardFilterPolicy.visibleColumns(filterBoard, "", "gone", "gone").flatMap { it.tasks }.size)
        KanbanBoardFilterPolicy.visibleColumns(filterBoard, "zzz", null, null)
        assertEquals(2, filterBoard.columns[0].tasks.size)
    }

    @Test
    fun runningGroupingBucketsByAssignee() {
        assertTrue(KanbanRunningGroupPolicy.shouldApply("running", true))
        assertFalse(KanbanRunningGroupPolicy.shouldApply("ready", true))
        assertFalse(KanbanRunningGroupPolicy.shouldApply("running", false))
        val groups = KanbanRunningGroupPolicy.group(listOf(task("1", assignee = "beta"), task("2"), task("3", assignee = "Alpha"), task("4", assignee = "alpha"), task("5", assignee = "")))
        assertEquals(listOf("Alpha", "alpha", "beta", "unassigned"), groups.map { it.key })
        assertEquals("Unassigned", groups.last().displayName)
        assertEquals(listOf("2", "5"), groups.last().tasks.map { it.id })
    }

    // endregion

    // region V3C

    @Test
    fun normalizedBulkIdsDedupeTrimAndOrder() {
        assertEquals(listOf("b", "a"), KanbanBulkResultPolicy.normalizedIds(listOf(" b", "a", "b", "", " ")))
    }

    @Test
    fun reconciliationRules() {
        val outcome = KanbanBulkResultPolicy.reconcile(
            listOf("a", "b", "c", "d"),
            listOf(
                KanbanBulkTaskResult("a", true), KanbanBulkTaskResult("b", false, "archive refused"),
                KanbanBulkTaskResult("a", false, "dup"), KanbanBulkTaskResult("zzz", true), KanbanBulkTaskResult("d", false),
            ),
        )
        assertEquals(listOf("a"), outcome.succeededIds)
        assertEquals(listOf("b", "c", "d"), outcome.failures.map { it.id })
        assertEquals("archive refused", outcome.failures[0].reason)
        assertEquals("Hermes returned no result for this task.", outcome.failures[1].reason)
        assertEquals("Hermes refused the change.", outcome.failures[2].reason)
        assertEquals(setOf("b", "c", "d"), KanbanBulkResultPolicy.selectionAfterApplying(outcome, setOf("a", "b", "c", "d")))
    }

    @Test
    fun summaryWording() {
        assertEquals("No tasks updated", KanbanBulkResultPolicy.summary(KanbanBulkOperationOutcome(emptyList(), emptyList())))
        assertEquals("3 tasks updated", KanbanBulkResultPolicy.summary(KanbanBulkOperationOutcome(listOf("a", "b", "c"), emptyList())))
        assertEquals("1 tasks failed", KanbanBulkResultPolicy.summary(KanbanBulkOperationOutcome(emptyList(), listOf(KanbanBulkFailure("a", "x")))))
        assertEquals("2 updated, 1 failed", KanbanBulkResultPolicy.summary(KanbanBulkOperationOutcome(listOf("a", "b"), listOf(KanbanBulkFailure("c", "x")))))
    }

    @Test
    fun selectionOwnershipPruneAndDestinations() {
        assertTrue(KanbanBulkSelectionPolicy.isOwned(stampA, stampA, true))
        assertFalse(KanbanBulkSelectionPolicy.isOwned(stampA, stampB, true))
        assertFalse(KanbanBulkSelectionPolicy.isOwned(stampA, stampA.copy(configurationGeneration = 2), true))
        assertFalse(KanbanBulkSelectionPolicy.isOwned(stampA, stampA, false))
        assertEquals(setOf("a"), KanbanBulkSelectionPolicy.prune(setOf("a", "ghost"), setOf("a", "b")))
        assertEquals(listOf("triage", "todo", "ready", "blocked", "done"), KanbanBulkDestinationPolicy.moveDestinations().map { it.rawValue })
    }

    @Test
    fun priorityStageIgnoresStaleStage() {
        val staged = BulkStagedSelection(listOf("a"), stampA)
        assertNull(KanbanBulkStagePolicy.priorityOperation(null, 3, stampA, true))
        assertNull(KanbanBulkStagePolicy.priorityOperation(staged, 3, stampB, true))
        assertNotNull(KanbanBulkStagePolicy.priorityOperation(staged, 3, stampA, true))
    }

    // endregion

    // region V3D

    @Test
    fun detailInvalidationPolicyTruthTable() {
        val invalidation = KanbanEventInvalidation(1, stampA, setOf("t1"), true)
        assertTrue(KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, stampA, true, "t1"))
        assertFalse(KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, stampA, true, "t2"))
        assertFalse(KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, stampB, true, "t1"))
        assertFalse(KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, stampA, false, "t1"))
        assertFalse(KanbanLiveUpdatePolicy.shouldRefreshDetail(null, stampA, true, "t1"))
        assertFalse(KanbanLiveUpdatePolicy.shouldRefreshDetail(invalidation, stampA, true, ""))
    }

    @Test
    fun watermarkAndBackoffConstants() {
        assertNull(KanbanLiveUpdatePolicy.initialWatermark(KanbanBoard(emptyList(), latestEventId = -1)))
        assertNull(KanbanLiveUpdatePolicy.initialWatermark(KanbanBoard(emptyList())))
        assertEquals(0L, KanbanLiveUpdatePolicy.initialWatermark(KanbanBoard(emptyList(), latestEventId = 0)))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (0..6).map { KanbanLiveUpdatePolicy.reconnectDelayMs(it) }.distinct())
        assertEquals(1_000L, KanbanLiveUpdatePolicy.reconnectDelayMs(-3))
        assertEquals(8_000L, app.hermesconduit.android.model.KanbanPollingPolicy.BOARD_INTERVAL_MS)
        assertEquals(4_000L, app.hermesconduit.android.model.KanbanPollingPolicy.DETAIL_INTERVAL_MS)
    }

    // endregion
}
