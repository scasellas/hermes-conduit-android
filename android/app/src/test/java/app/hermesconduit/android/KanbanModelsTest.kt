package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.KanbanBoard
import app.hermesconduit.android.model.KanbanBulkTaskRequest
import app.hermesconduit.android.model.KanbanBulkTaskResult
import app.hermesconduit.android.model.KanbanCreateBoardRequest
import app.hermesconduit.android.model.KanbanCreateTaskRequest
import app.hermesconduit.android.model.KanbanDecomposeResponse
import app.hermesconduit.android.model.KanbanDiagnostic
import app.hermesconduit.android.model.KanbanEventFrame
import app.hermesconduit.android.model.KanbanModelProviderOption
import app.hermesconduit.android.model.KanbanOrchestrationPatch
import app.hermesconduit.android.model.KanbanOrchestrationSettings
import app.hermesconduit.android.model.KanbanSpecifyResponse
import app.hermesconduit.android.model.KanbanTask
import app.hermesconduit.android.model.KanbanTaskDetail
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.KanbanUpdateBoardPatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the decoding/encoding cases of ConduitTests/Kanban*Tests.swift. */
class KanbanModelsTest {
    private fun obj(text: String): JsonObject = ConduitJson.parseToJsonElement(text).jsonObject

    @Test
    fun lossyIntDecodingHandlesExtremeAndMalformedValues() {
        val task = KanbanTask.parse(obj("""{"id": 42, "title": "T", "priority": 2.5, "comment_count": "7", "created_at": 1e300, "worker_pid": 12.0}"""))!!
        assertEquals("42", task.id)
        assertNull("fractional values never truncate", task.priority)
        assertEquals(7, task.commentCount)
        assertNull("overflow decodes as null", task.createdAt)
        assertEquals(12L, task.workerPid)
    }

    @Test
    fun taskDefaultsForMissingIdentityFields() {
        val task = KanbanTask.parse(obj("""{"id": "t1", "title": 5, "status": null}"""))!!
        assertEquals("Untitled task", task.title)
        assertEquals("todo", task.status)
    }

    @Test
    fun boardDropsIdlessRowsAndToleratesJunk() {
        val board = KanbanBoard.parse(obj("""{"columns": [{"name": "ready", "tasks": [{"title": "no id"}, {"id": "t1", "title": "ok"}, 3]}, "junk"],
            "assignees": ["a", 1], "latest_event_id": "17"}"""))
        assertEquals(1, board.columns.size)
        assertEquals(listOf("t1"), board.columns[0].tasks.map { it.id })
        assertEquals(listOf("a"), board.assignees)
        assertEquals(17L, board.latestEventId)
    }

    @Test
    fun malformedDiagnosticsDoNotCrashDecoding() {
        val d = KanbanDiagnostic.parse(obj("""{"kind": 3, "title": null, "actions": [{"kind": "reclaim", "label": 1}, "x", {"kind": "cli_hint", "payload": {"command": "hermes kanban reclaim t1"}}], "count": "x"}"""))!!
        assertEquals("3", d.kind)
        assertEquals("", d.title)
        assertEquals(1, d.count)
        assertEquals(listOf("reclaim", "cli_hint"), d.actions.map { it.kind })
        assertEquals("1", d.actions[0].label)
    }

    @Test
    fun dependencyLinksDecodeIntoParentsAndChildren() {
        val detail = KanbanTaskDetail.parse(obj("""{"task": {"id": "t", "title": "T", "status": "todo"}, "links": {"parents": ["p1"], "children": ["c1", "c2"]}}"""))
        assertEquals(listOf("p1"), detail.links.parents)
        assertEquals(listOf("c1", "c2"), detail.links.children)
    }

    @Test
    fun orchestrationResponseDecodesConfiguredAndResolvedSeparately() {
        val s = KanbanOrchestrationSettings.parse(obj("""{"orchestrator_profile": "", "default_assignee": "ops", "auto_decompose": false,
            "resolved_orchestrator_profile": "default", "resolved_default_assignee": "ops", "active_profile": "default"}"""))
        assertEquals("", s.orchestratorProfile)
        assertEquals("default", s.resolvedOrchestratorProfile)
        assertNull(s.autoPromoteChildren)
        assertTrue(KanbanOrchestrationSettings.DEFAULT_AUTO_PROMOTE_CHILDREN)
    }

    @Test
    fun orchestrationPatchEncodesDefaultAsEmptyStringAndOmitsUnset() {
        val json = KanbanOrchestrationPatch(orchestratorProfile = "", autoDecompose = true).toJson()
        assertEquals("""{"orchestrator_profile":"","auto_decompose":true}""", json.toString())
        assertTrue(KanbanOrchestrationPatch().isEmpty)
    }

    @Test
    fun specifyAndDecomposeResponsesDecodeTolerantly() {
        val s = KanbanSpecifyResponse.parse(obj("""{"ok": "yes", "task_id": 9, "reason": "task is not in triage (status=todo)"}"""))
        assertFalse(s.ok)
        assertEquals("9", s.taskId)
        val d = KanbanDecomposeResponse.parse(obj("""{"ok": true, "task_id": "t", "fanout": true, "child_ids": ["a", "b", 3]}"""))
        assertTrue(d.ok && d.fanout)
        assertEquals(listOf("a", "b"), d.childIds)
    }

    @Test
    fun createTaskRequestEncodesUpstreamFieldNames() {
        val json = KanbanCreateTaskRequest(
            title = "T", assignee = "ops", workspaceKind = "dir", workspacePath = "/repo", parents = listOf("p"),
            skills = listOf("github"), goalMode = true, goalMaxTurns = 10, modelOverride = "m", providerOverride = "p", reasoningEffort = "none",
        ).toJson()
        assertEquals("dir", json["workspace_kind"].toString().trim('"'))
        assertEquals("/repo", json["workspace_path"].toString().trim('"'))
        assertEquals("[\"p\"]", json["parents"].toString())
        assertEquals("[\"github\"]", json["skills"].toString())
        assertEquals("true", json["goal_mode"].toString())
        assertEquals("10", json["goal_max_turns"].toString())
        assertEquals("\"none\"", json["reasoning_effort"].toString())
        assertFalse("parked omits the assignee", KanbanCreateTaskRequest(title = "T").toJson().containsKey("assignee"))
    }

    @Test
    fun patchClearFlagsOnlyWhenSet() {
        assertEquals("""{"status":"archived"}""", KanbanTaskPatch(status = "archived").toJson().toString())
        assertEquals("""{"clear_model_override":true,"clear_reasoning_effort":true}""", KanbanTaskPatch(clearModelOverride = true, clearReasoningEffort = true).toJson().toString())
        assertTrue(KanbanTaskPatch().isEmpty)
    }

    @Test
    fun createBoardRequestAlwaysSendsSwitchFalse() {
        val json = KanbanCreateBoardRequest(slug = "alpha", name = "Alpha", projectId = "p1").toJson()
        assertEquals("""{"slug":"alpha","name":"Alpha","project_id":"p1","switch":false}""", json.toString())
    }

    @Test
    fun updateBoardPatchIsTriState() {
        assertEquals("""{"default_workdir":"","project_id":""}""", KanbanUpdateBoardPatch(defaultWorkdir = "", projectId = "").toJson().toString())
        assertEquals("""{"name":"N"}""", KanbanUpdateBoardPatch(name = "N").toJson().toString())
    }

    @Test
    fun bulkRequestEncodesOnlyExposedFields() {
        assertEquals(
            """{"ids":["a","b"],"assignee":"","reclaim_first":true}""",
            KanbanBulkTaskRequest(listOf("a", "b"), assignee = "", reclaimFirst = true).toJson().toString(),
        )
        assertEquals("""{"ids":["a"],"priority":-3}""", KanbanBulkTaskRequest(listOf("a"), priority = -3).toJson().toString())
        val results = KanbanBulkTaskResult.parseList(obj("""{"results": [{"id": "a", "ok": true}, {"id": "b", "ok": false, "error": "not found"}, 7]}"""))
        assertEquals(2, results.size)
        assertEquals("not found", results[1].error)
    }

    @Test
    fun modelOptionsDecodeTolerantly() {
        val providers = KanbanModelProviderOption.parseList(obj("""{"providers": [{"slug": "anthropic", "label": "Anthropic", "models": ["claude", ""]}, {"slug": "x", "models": "bad"}]}"""))
        assertEquals(listOf("claude"), providers[0].models)
        assertEquals("Anthropic", providers[0].displayName)
        assertTrue(providers[1].models.isEmpty())
    }

    @Test
    fun eventFrameToleratesMalformedElementsAndUnknownFields() {
        val frame = KanbanEventFrame.parse("""{"events": ["junk", {"id": "nope", "task_id": 5}, {"id": 4, "task_id": "t1", "kind": "quantum", "extra": {}}], "cursor": 4, "x": 1}""")!!
        assertEquals(2, frame.events.size)
        assertNull(frame.events[0].id)
        assertNull(frame.events[0].taskId)
        assertEquals("t1", frame.events[1].taskId)
        assertEquals(4L, frame.cursor)
        assertNull(KanbanEventFrame.parse("not json"))
        assertTrue(KanbanEventFrame.parse("""{"events": [], "cursor": 9}""")!!.events.isEmpty())
    }
}
