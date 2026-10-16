package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanBulkTaskRequest
import app.hermesconduit.android.model.KanbanCreateBoardRequest
import app.hermesconduit.android.model.KanbanCreateTaskRequest
import app.hermesconduit.android.model.KanbanOrchestrationPatch
import app.hermesconduit.android.model.KanbanTaskPatch
import app.hermesconduit.android.model.KanbanUpdateBoardPatch
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.kanban.KanbanService
import app.hermesconduit.android.ui.kanban.KanbanServiceException
import app.hermesconduit.android.ui.kanban.KanbanStore
import app.hermesconduit.android.ui.kanban.KanbanTransport
import app.hermesconduit.android.ui.kanban.MemoryKanbanPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Scripted stand-in for the dashboard's Kanban plugin routes. */
class FakeKanbanTransport : KanbanTransport {
    data class Call(val method: String, val path: String, val query: Map<String, String?>, val body: JsonElement?) {
        val bodyObject: JsonObject? get() = body as? JsonObject
    }

    val calls = mutableListOf<Call>()
    var current = "default"
    var boards = mutableListOf("default", "alpha", "beta")
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, (Call) -> JsonObject>()
    var latestEventId = 5
    var orchestration = """{"orchestrator_profile": "", "default_assignee": "", "auto_decompose": true, "resolved_orchestrator_profile": "default", "resolved_default_assignee": "default"}"""

    fun obj(text: String): JsonObject = ConduitJson.parseToJsonElement(text).jsonObject
    fun calls(method: String, pathSuffix: String) = calls.filter { it.method == method && it.path.endsWith(pathSuffix) }

    override suspend fun get(path: String, query: Map<String, String?>): JsonObject = handle(Call("GET", path, query, null))
    override suspend fun send(method: String, path: String, body: JsonElement?, query: Map<String, String?>): JsonObject = handle(Call(method, path, query, body))

    private suspend fun handle(call: Call) = handleRecorded(call.also { calls += it })

    private suspend fun handleRecorded(call: Call): JsonObject {
        val route = call.path.removePrefix("api/plugins/kanban")
        gates["${call.method} $route"]?.await()
        responses["${call.method} $route"]?.let { return it(call) }
        return when {
            call.method == "GET" && route == "/boards" ->
                obj("""{"boards": [${boards.joinToString(",") { """{"slug": "$it", "name": "${it.replaceFirstChar(Char::uppercase)}"}""" }}], "current": "$current"}""")
            call.method == "GET" && route == "/board" -> {
                val slug = call.query["board"]
                obj("""{"columns": [{"name": "ready", "tasks": [{"id": "t1", "title": "On $slug", "status": "ready"}, {"id": "t2", "title": "Two", "status": "ready"}]},
                    {"name": "running", "tasks": []}], "assignees": ["ops"], "latest_event_id": $latestEventId}""")
            }
            call.method == "GET" && route == "/profiles" -> obj("""{"profiles": [{"name": "default", "is_default": true, "description": "", "description_auto": false}, {"name": "ops", "is_default": false, "description": "Ops", "description_auto": false}]}""")
            call.method == "GET" && route == "/projects" -> obj("""{"projects": []}""")
            call.method == "GET" && route == "/orchestration" -> obj(orchestration)
            call.method == "POST" && route == "/tasks" -> obj("""{"task": {"id": "new1", "title": "N", "status": "ready"}}""")
            call.method == "PATCH" && route.startsWith("/tasks/") -> obj("""{"task": {"id": "${route.removePrefix("/tasks/")}", "title": "T", "status": "${(call.bodyObject?.get("status")?.toString()?.trim('"')) ?: "ready"}"}}""")
            call.method == "POST" && route == "/tasks/bulk" -> {
                val ids = (call.bodyObject?.get("ids") as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
                obj("""{"results": [${ids.joinToString(",") { """{"id": "$it", "ok": true}""" }}]}""")
            }
            call.method == "DELETE" && route.startsWith("/boards/") ->
                obj("""{"result": {"slug": "${route.removePrefix("/boards/")}", "action": "archived", "new_path": "/x"}, "current": "default"}""").also {
                    boards.remove(route.removePrefix("/boards/"))
                }
            call.method == "POST" && route == "/boards" -> {
                val slug = call.bodyObject?.get("slug").toString().trim('"')
                if (slug !in boards) boards.add(slug)
                obj("""{"board": {"slug": "$slug", "name": "New"}, "current": "default"}""")
            }
            call.method == "PATCH" && route.startsWith("/boards/") -> obj("""{"board": {"slug": "${route.removePrefix("/boards/")}", "name": "Renamed"}}""")
            call.method == "PUT" && route == "/orchestration" -> {
                orchestration = """{"orchestrator_profile": "ops", "default_assignee": "", "auto_decompose": false, "resolved_orchestrator_profile": "ops", "resolved_default_assignee": "default"}"""
                obj(orchestration)
            }
            else -> obj("{}")
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KanbanStoreTest {
    private fun TestScope.makeStore(transport: FakeKanbanTransport, prefs: MemoryKanbanPrefs = MemoryKanbanPrefs(), server: String = "https://a.example"): KanbanStore {
        val nudgeScope: CoroutineScope = backgroundScope
        val store = KanbanStore(prefs) { KanbanService(it, nudgeScope) }
        store.configure(transport, server)
        return store
    }

    private suspend inline fun <reified E : Throwable> expectThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun boardSelectionIsQueryScopedAndNeverSwitchesServerBoard() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        assertEquals("default", t.calls("GET", "/board").last().query["board"])
        store.selectBoard("alpha")
        assertEquals("alpha", t.calls("GET", "/board").last().query["board"])
        assertEquals("alpha", store.state.value.loadedBoardSlug)
        assertTrue(store.state.value.isSelectedSnapshotLoaded)
        assertTrue("never calls the switch route", t.calls.none { it.path.endsWith("/switch") })
        assertTrue("no fabricated profile scope", t.calls.none { it.query.containsKey("profile") })
    }

    @Test
    fun invalidPersistedSelectionFallsBackToServerCurrent() = runTest {
        val t = FakeKanbanTransport()
        val prefs = MemoryKanbanPrefs()
        prefs.putString(KanbanStore.scopedBoardKey("https://a.example"), "gone")
        val store = makeStore(t, prefs)
        assertEquals("gone", store.state.value.selectedBoardSlug)
        store.reload()
        assertEquals("", store.state.value.selectedBoardSlug)
        assertEquals("default", store.state.value.loadedBoardSlug)
        assertNull(prefs.getString(KanbanStore.scopedBoardKey("https://a.example")))
    }

    @Test
    fun boardSelectionPersistenceIsScopedToServerIdentity() = runTest {
        val prefs = MemoryKanbanPrefs()
        val store = makeStore(FakeKanbanTransport(), prefs, "https://a.example/")
        store.selectBoard("alpha")
        assertEquals("alpha", prefs.getString(KanbanStore.scopedBoardKey("https://a.example")))
        assertNull(prefs.getString(KanbanStore.scopedBoardKey("https://b.example")))
        val other = FakeKanbanTransport()
        store.configure(other, "https://b.example")
        assertEquals("", store.state.value.selectedBoardSlug)
    }

    @Test
    fun lockedLaneCreationIsRejectedBeforeAnyPost() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        expectThrows<KanbanServiceException.InvalidManualStatus> { store.createTask(KanbanCreateTaskRequest(title = "T"), "running") }
        assertTrue(t.calls("POST", "/tasks").isEmpty())
        assertNotNull(store.state.value.mutationErrorMessage)
        assertFalse(store.state.value.isMutating)
    }

    @Test
    fun creationTargetsTriageNativelyAndMovesOtherLanes() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        store.createTask(KanbanCreateTaskRequest(title = "T"), "triage")
        assertEquals("true", t.calls("POST", "/tasks").last().bodyObject!!["triage"].toString())
        assertTrue("triage lands natively", t.calls.none { it.method == "PATCH" })
        t.responses["POST /tasks"] = { t.obj("""{"task": {"id": "n2", "title": "N", "status": "ready"}}""") }
        store.createTask(KanbanCreateTaskRequest(title = "T"), "blocked")
        val patch = t.calls("PATCH", "/tasks/n2").single()
        assertEquals("\"blocked\"", patch.bodyObject!!["status"].toString())
        assertEquals("default", patch.query["board"])
    }

    @Test
    fun twoStepCreationFailureSurfacesPartialSuccessWithoutDuplicatePost() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        t.responses["PATCH /tasks/new1"] = { throw DashboardHttpException(409, "transition refused") }
        val e = expectThrows<KanbanServiceException.TaskCreatedButMoveFailed> { store.createTask(KanbanCreateTaskRequest(title = "T"), "done") }
        assertEquals("new1", e.taskId)
        assertEquals(1, t.calls("POST", "/tasks").size)
        assertTrue(store.state.value.mutationErrorMessage!!.contains("(task new1)"))
        assertTrue(store.state.value.mutationErrorMessage!!.contains("transition refused"))
    }

    @Test
    fun moveToRunningIsRefusedBeforeAnyTransport() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        expectThrows<KanbanServiceException.InvalidManualStatus> { store.updateTask("t1", KanbanTaskPatch(status = "running")) }
        assertTrue(t.calls.none { it.method == "PATCH" })
        assertTrue(store.state.value.mutationErrorMessage!!.contains("Running"))
    }

    @Test
    fun successfulMutationReloadsOnceAndArchiveIsAPlainPatch() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        val before = t.calls("GET", "/board").size
        store.updateTask("t1", KanbanTaskPatch(status = "archived"))
        assertEquals(before + 1, t.calls("GET", "/board").size)
        assertEquals("\"archived\"", t.calls("PATCH", "/tasks/t1").single().bodyObject!!["status"].toString())
        store.deleteTask("t2")
        assertEquals(1, t.calls("DELETE", "/tasks/t2").size)
    }

    @Test
    fun inFlightMutationKeepsCapturedBoardAcrossSwitch() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val gate = CompletableDeferred<Unit>().also { t.gates["PATCH /tasks/t1"] = it }
        val job = launch { runCatching { store.updateTask("t1", KanbanTaskPatch(title = "x")) } }
        runCurrent()
        assertTrue(store.state.value.isMutating)
        val switch = launch { store.selectBoard("beta") }
        runCurrent()
        gate.complete(Unit)
        job.join()
        switch.join()
        assertEquals("alpha", t.calls("PATCH", "/tasks/t1").single().query["board"])
        assertEquals("beta", store.state.value.loadedBoardSlug)
        assertFalse(store.state.value.isMutating)
    }

    @Test
    fun staleStateAfterServerReconfigureIsDiscarded() = runTest {
        val a = FakeKanbanTransport()
        val store = makeStore(a)
        store.reload()
        val gate = CompletableDeferred<Unit>().also { a.gates["PATCH /tasks/t1"] = it }
        val job = launch { runCatching { store.updateTask("t1", KanbanTaskPatch(title = "x")) } }
        runCurrent()
        val b = FakeKanbanTransport()
        store.configure(b, "https://b.example")
        assertFalse("configure revokes mutation ownership", store.state.value.isMutating)
        gate.complete(Unit)
        job.join()
        assertTrue("the stale completion never reloads the new server", b.calls.isEmpty())
        assertNull(store.state.value.board)
        assertNull(store.state.value.mutationErrorMessage)
    }

    @Test
    fun contextBoundDeleteFailsClosedAcrossBoardSwitchWithCollidingId() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val staged = store.state.value.loadedContextStamp!!
        store.selectBoard("beta")
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.deleteTask("t1", staged) }
        assertTrue(t.calls.none { it.method == "DELETE" })
        store.deleteTask("t1", store.state.value.loadedContextStamp!!)
        assertEquals("beta", t.calls("DELETE", "/tasks/t1").single().query["board"])
    }

    @Test
    fun mutationFailureStaysVisibleAndRefreshFailureKeepsBoard() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        t.responses["PATCH /tasks/t1"] = { throw DashboardHttpException(500, "boom") }
        expectThrows<DashboardHttpException> { store.updateTask("t1", KanbanTaskPatch(title = "x")) }
        assertEquals("boom", store.state.value.mutationErrorMessage)
        t.responses["GET /board"] = { throw DashboardHttpException(502, "gateway down") }
        store.refresh()
        assertNotNull("the last good board stays visible", store.state.value.board)
        assertEquals("gateway down", store.state.value.errorMessage)
    }

    @Test
    fun dispatcherNudgeIsDebouncedCoalescedAndUsesCapturedBoard() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        store.updateTask("t1", KanbanTaskPatch(title = "a"))
        store.updateTask("t2", KanbanTaskPatch(title = "b"))
        assertTrue("mutations return before the nudge fires", t.calls("POST", "/dispatch").isEmpty())
        store.selectBoard("beta")
        advanceUntilIdle()
        store.awaitPendingNudge()
        val nudges = t.calls("POST", "/dispatch")
        assertEquals(1, nudges.size)
        assertEquals("alpha", nudges.single().query["board"])
    }

    @Test
    fun commentsAreNotDispatcherRelevant() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        store.addComment("t1", "  hello  ")
        advanceUntilIdle()
        assertEquals("\"hello\"", t.calls("POST", "/tasks/t1/comments").single().bodyObject!!["body"].toString())
        assertEquals("\"conduit\"", t.calls("POST", "/tasks/t1/comments").single().bodyObject!!["author"].toString())
        assertTrue(t.calls("POST", "/dispatch").isEmpty())
    }

    @Test
    fun reassignAndReclaimUseDedicatedEndpoints() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        store.reassignTask("t1", "ops")
        val reassign = t.calls("POST", "/tasks/t1/reassign").single()
        assertEquals("""{"profile":"ops","reclaim_first":true}""", reassign.body.toString())
        assertEquals("alpha", reassign.query["board"])
        store.reassignTask("t1", null)
        assertEquals("""{"reclaim_first":true}""", t.calls("POST", "/tasks/t1/reassign").last().body.toString())
        store.reclaimTask("t1", "stuck")
        assertEquals("""{"reason":"stuck"}""", t.calls("POST", "/tasks/t1/reclaim").single().body.toString())
    }

    @Test
    fun workerLogUsesLoadedSnapshotBoardAndTail() = runTest {
        val t = FakeKanbanTransport()
        t.responses["GET /tasks/t1/log"] = { t.obj("""{"exists": true, "size_bytes": 12, "content": "hello", "truncated": false}""") }
        val store = makeStore(t)
        store.selectBoard("alpha")
        val log = store.fetchTaskLog("t1", 65_536)
        assertEquals("hello", log.content)
        val call = t.calls("GET", "/tasks/t1/log").single()
        assertEquals("65536", call.query["tail"])
        assertEquals("alpha", call.query["board"])
    }

    @Test
    fun unsafePathSegmentsFailClosed() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        expectThrows<KanbanServiceException.InvalidQueryParameter> { store.deleteTask("..") }
        expectThrows<KanbanServiceException.InvalidQueryParameter> { store.deleteTask("a/b") }
        assertTrue(t.calls.none { it.method == "DELETE" })
    }

    // V3A

    @Test
    fun updateOrchestrationPostsOnlyChangedFieldsAndAdoptsEcho() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        val updated = store.updateOrchestration(KanbanOrchestrationPatch(orchestratorProfile = "ops", autoDecompose = false), store.state.value.loadedContextStamp)
        val put = t.calls("PUT", "/orchestration").single()
        assertEquals("""{"orchestrator_profile":"ops","auto_decompose":false}""", put.body.toString())
        assertFalse("server-global: no board query", put.query.containsKey("board"))
        assertEquals("ops", updated.orchestratorProfile)
        assertEquals("ops", store.state.value.orchestration?.resolvedOrchestratorProfile)
    }

    @Test
    fun orchestrationSaveProceedsAfterSameServerBoardSwitchButNotAfterReconfigure() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        val stamp = store.state.value.loadedContextStamp!!
        store.selectBoard("alpha")
        store.updateOrchestration(KanbanOrchestrationPatch(autoDecompose = false), stamp)
        assertEquals(1, t.calls("PUT", "/orchestration").size)
        val b = FakeKanbanTransport()
        store.configure(b, "https://b.example")
        store.reload()
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.updateOrchestration(KanbanOrchestrationPatch(autoDecompose = true), stamp) }
        assertTrue(b.calls("PUT", "/orchestration").isEmpty())
    }

    @Test
    fun profileDescriptionSaveAndAutoDescribe() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /profiles/ops/describe-auto"] = { t.obj("""{"ok": true, "profile": "ops", "description": "Runs deploys."}""") }
        val store = makeStore(t)
        store.reload()
        store.updateProfileDescription("ops", "Handles ops", store.state.value.loadedContextStamp)
        assertEquals("""{"description":"Handles ops"}""", t.calls("PATCH", "/profiles/ops").single().body.toString())
        advanceUntilIdle()
        assertTrue("no nudge for profile writes", t.calls("POST", "/dispatch").isEmpty())
        t.responses["GET /profiles"] = { t.obj("""{"profiles": [{"name": "ops", "description": "stale"}]}""") }
        t.gates["GET /profiles"] = CompletableDeferred()
        val job = launch { store.autoDescribeProfile("ops") }
        runCurrent()
        assertEquals("""{"overwrite":true}""", t.calls("POST", "/profiles/ops/describe-auto").single().body.toString())
        val adopted = store.state.value.profiles.first { it.name == "ops" }
        assertEquals("Runs deploys.", adopted.description)
        assertTrue(adopted.descriptionAuto)
        t.gates.remove("GET /profiles")!!.complete(Unit)
        job.join()
    }

    @Test
    fun autoDescribeSemanticRefusalReturnsReasonWithoutThrowing() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /profiles/ops/describe-auto"] = { t.obj("""{"ok": false, "profile": "ops", "reason": "no auxiliary client configured"}""") }
        val store = makeStore(t)
        store.reload()
        val outcome = store.autoDescribeProfile("ops")
        assertFalse(outcome.ok)
        assertEquals("no auxiliary client configured", outcome.reason)
        assertNull(store.state.value.mutationErrorMessage)
    }

    @Test
    fun specifySemanticFailureSurfacesBackendReason() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /tasks/t1/specify"] = { t.obj("""{"ok": false, "task_id": "t1", "reason": "task is not in triage (status=todo)"}""") }
        val store = makeStore(t)
        store.reload()
        val e = expectThrows<KanbanServiceException.ActionDeclined> { store.specifyTask("t1", store.state.value.loadedContextStamp) }
        assertEquals("task is not in triage (status=todo)", e.reason)
        assertEquals("task is not in triage (status=todo)", store.state.value.mutationErrorMessage)
        assertEquals("""{"author":"conduit"}""", t.calls("POST", "/tasks/t1/specify").single().body.toString())
    }

    @Test
    fun specifyStaleCapturedContextFailsClosedWithoutAnyRequest() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        val stale = store.state.value.loadedContextStamp!!
        store.selectBoard("alpha")
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.specifyTask("t1", stale) }
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.decomposeTask("t1", stale) }
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.nudgeDispatcher(stale) }
        assertTrue(t.calls.none { it.path.endsWith("/specify") || it.path.endsWith("/decompose") || it.path.endsWith("/dispatch") })
    }

    @Test
    fun decomposeSuccessReconcilesFromAuthoritativeBoardReload() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /tasks/t1/decompose"] = { t.obj("""{"ok": true, "task_id": "t1", "fanout": true, "child_ids": ["c1", "c2"]}""") }
        val store = makeStore(t)
        store.selectBoard("alpha")
        val before = t.calls("GET", "/board").size
        val response = store.decomposeTask("t1", store.state.value.loadedContextStamp)
        assertEquals(listOf("c1", "c2"), response.childIds)
        assertEquals(before + 1, t.calls("GET", "/board").size)
        assertEquals("alpha", t.calls("POST", "/tasks/t1/decompose").single().query["board"])
    }

    @Test
    fun manualNudgePostsToCapturedBoardOnly() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("beta")
        store.nudgeDispatcher(store.state.value.loadedContextStamp)
        assertEquals("beta", t.calls("POST", "/dispatch").single().query["board"])
    }

    // V3B

    @Test
    fun createBoardSelectsReturnedSlugLocallyAndRefreshes() = runTest {
        val t = FakeKanbanTransport()
        val prefs = MemoryKanbanPrefs()
        val store = makeStore(t, prefs)
        store.reload()
        store.createBoard(KanbanCreateBoardRequest(slug = "gamma", name = "Gamma"), store.state.value.loadedContextStamp)
        assertEquals("""{"slug":"gamma","name":"Gamma","switch":false}""", t.calls("POST", "/boards").single().body.toString())
        assertEquals("gamma", store.state.value.selectedBoardSlug)
        assertEquals("gamma", store.state.value.loadedBoardSlug)
        assertEquals("gamma", prefs.getString(KanbanStore.scopedBoardKey("https://a.example")))
    }

    @Test
    fun createInvalidSlugRejectedBeforeAnyRequest() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        expectThrows<KanbanServiceException.ActionDeclined> { store.createBoard(KanbanCreateBoardRequest(slug = "Bad Slug")) }
        assertTrue(t.calls("POST", "/boards").isEmpty())
    }

    @Test
    fun updateBoardEmptyPatchShortCircuitsAndTargetMustMatchStamp() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val stamp = store.state.value.loadedContextStamp!!
        store.updateBoard("alpha", KanbanUpdateBoardPatch(), stamp)
        assertTrue(t.calls.none { it.method == "PATCH" })
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.updateBoard("beta", KanbanUpdateBoardPatch(name = "x"), stamp) }
        assertTrue(t.calls.none { it.method == "PATCH" })
        val updated = store.updateBoard("alpha", KanbanUpdateBoardPatch(name = "Renamed"), stamp)
        assertEquals("Renamed", updated.name)
        assertTrue("PATCH /boards/{slug} carries no board query", t.calls("PATCH", "/boards/alpha").single().query.isEmpty())
    }

    @Test
    fun archiveNeverHardDeletesAndReconcilesSelectedBoardToFallback() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        assertTrue(store.archiveBoard("alpha", store.state.value.loadedContextStamp!!))
        val delete = t.calls("DELETE", "/boards/alpha").single()
        assertFalse(delete.query.containsKey("delete"))
        assertEquals("", store.state.value.selectedBoardSlug)
        assertEquals("default", store.state.value.loadedBoardSlug)
        assertTrue(store.state.value.boards.none { it.slug == "alpha" })
    }

    @Test
    fun archiveRefusalsFailClosed() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        expectThrows<KanbanServiceException.ActionDeclined> { store.archiveBoard("default", store.state.value.loadedContextStamp!!) }
        store.selectBoard("alpha")
        val stampA = store.state.value.loadedContextStamp!!
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.archiveBoard("beta", stampA) }
        t.responses["DELETE /boards/alpha"] = { t.obj("""{"result": {"action": "deleted"}}""") }
        expectThrows<KanbanServiceException.Invalid> { store.archiveBoard("alpha", stampA) }
        assertTrue(store.state.value.mutationErrorMessage!!.contains("deleted"))
        assertFalse(store.state.value.isMutating)
        t.responses["DELETE /boards/alpha"] = { throw DashboardHttpException(400, "board does not exist") }
        expectThrows<DashboardHttpException> { store.archiveBoard("alpha", stampA) }
        assertEquals("board does not exist", store.state.value.mutationErrorMessage)
    }

    @Test
    fun archivePartialSuccessMessageSurvivesFailedBoardsRefresh() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val stamp = store.state.value.loadedContextStamp!!
        t.responses["GET /boards"] = { throw DashboardHttpException(503, "offline") }
        assertTrue(store.archiveBoard("alpha", stamp))
        assertEquals("The board was archived, but the board list could not be refreshed. offline", store.state.value.errorMessage)
        assertNull(store.state.value.mutationErrorMessage)
    }

    // V3C

    @Test
    fun bulkWireContracts() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val stamp = store.state.value.loadedContextStamp!!
        store.bulkUpdateTasks(listOf("t2", "t1", "t2"), KanbanBulkTaskRequest(emptyList(), status = "done"), stamp)
        assertEquals("""{"ids":["t2","t1"],"status":"done"}""", t.calls("POST", "/tasks/bulk").last().body.toString())
        assertEquals("alpha", t.calls("POST", "/tasks/bulk").last().query["board"])
        store.bulkUpdateTasks(listOf("t1"), KanbanBulkTaskRequest(emptyList(), assignee = "", reclaimFirst = true), stamp)
        assertEquals("""{"ids":["t1"],"assignee":"","reclaim_first":true}""", t.calls("POST", "/tasks/bulk").last().body.toString())
        store.bulkUpdateTasks(listOf("t1"), KanbanBulkTaskRequest(emptyList(), archive = true), stamp)
        assertEquals("""{"ids":["t1"],"archive":true}""", t.calls("POST", "/tasks/bulk").last().body.toString())
    }

    @Test
    fun bulkGuardsRejectBeforeAnyRequest() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.selectBoard("alpha")
        val stamp = store.state.value.loadedContextStamp!!
        expectThrows<KanbanServiceException.Invalid> { store.bulkUpdateTasks(listOf(" "), KanbanBulkTaskRequest(emptyList(), status = "done"), stamp) }
        expectThrows<KanbanServiceException.InvalidManualStatus> { store.bulkUpdateTasks(listOf("t1"), KanbanBulkTaskRequest(emptyList(), status = "review"), stamp) }
        store.selectBoard("beta")
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.bulkUpdateTasks(listOf("t1"), KanbanBulkTaskRequest(emptyList(), status = "done"), stamp) }
        expectThrows<KanbanServiceException.BoardNavigationInProgress> { store.bulkDeleteTasks(listOf("t1"), stamp) }
        assertTrue(t.calls.none { it.path.endsWith("/tasks/bulk") || it.method == "DELETE" })
    }

    @Test
    fun bulkPartialFailureReconcilesById() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /tasks/bulk"] = { t.obj("""{"results": [{"id": "t1", "ok": true}, {"id": "t2", "ok": false, "error": "assign refused"}]}""") }
        val store = makeStore(t)
        store.reload()
        val outcome = store.bulkUpdateTasks(listOf("t1", "t2", "t3"), KanbanBulkTaskRequest(emptyList(), assignee = "ops", reclaimFirst = true), store.state.value.loadedContextStamp!!)
        assertEquals(listOf("t1"), outcome.succeededIds)
        assertEquals(listOf("t2", "t3"), outcome.failures.map { it.id })
    }

    @Test
    fun bulkDeleteFansOutPerTaskWithOneReconciliation() = runTest {
        val t = FakeKanbanTransport()
        t.responses["DELETE /tasks/t2"] = { throw DashboardHttpException(404, "not found") }
        val store = makeStore(t)
        store.reload()
        val before = t.calls("GET", "/board").size
        val outcome = store.bulkDeleteTasks(listOf("t1", "t2", "t3"), store.state.value.loadedContextStamp!!)
        assertEquals(listOf("t1", "t3"), outcome.succeededIds)
        assertEquals("not found", outcome.failures.single().reason)
        assertEquals(3, t.calls.count { it.method == "DELETE" })
        assertEquals("exactly ONE post-op reconciliation", before + 1, t.calls("GET", "/board").size)
    }

    @Test
    fun bulkTopLevelTransportFailureThrows() = runTest {
        val t = FakeKanbanTransport()
        t.responses["POST /tasks/bulk"] = { throw DashboardHttpException(500, "down") }
        val store = makeStore(t)
        store.reload()
        expectThrows<DashboardHttpException> { store.bulkUpdateTasks(listOf("t1"), KanbanBulkTaskRequest(emptyList(), priority = 2), store.state.value.loadedContextStamp!!) }
        assertEquals("down", store.state.value.mutationErrorMessage)
    }

    // V3D

    @Test
    fun refreshFromEventDispositions() = runTest {
        val t = FakeKanbanTransport()
        val store = makeStore(t)
        store.reload()
        val stamp = store.state.value.loadedContextStamp!!
        assertEquals(KanbanStore.EventRefreshDisposition.STALE, store.refreshFromEvent(KanbanBoardContextStamp("alpha", stamp.configurationGeneration), false))
        val before = t.calls("GET", "/board").size
        assertEquals(KanbanStore.EventRefreshDisposition.REFRESHED, store.refreshFromEvent(stamp, false))
        assertEquals(before + 1, t.calls("GET", "/board").size)
        val gate = CompletableDeferred<Unit>().also { t.gates["PATCH /tasks/t1"] = it }
        val job = launch { store.updateTask("t1", KanbanTaskPatch(title = "x")) }
        runCurrent()
        assertEquals(KanbanStore.EventRefreshDisposition.DEFERRED, store.refreshFromEvent(stamp, false))
        gate.complete(Unit)
        job.join()
        store.configure(FakeKanbanTransport(), "https://b.example")
        assertEquals("stale generation frames produce zero requests", KanbanStore.EventRefreshDisposition.STALE, store.refreshFromEvent(stamp, false))
    }

    @Test
    fun generationFlipsOnConfigure() = runTest {
        val store = makeStore(FakeKanbanTransport())
        val g = store.configurationGeneration
        store.configure(FakeKanbanTransport(), "https://b.example")
        assertEquals(g + 1, store.configurationGeneration)
        store.configure(null)
        assertEquals(g + 2, store.configurationGeneration)
    }
}
