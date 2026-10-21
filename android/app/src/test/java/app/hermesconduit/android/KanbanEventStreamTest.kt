package app.hermesconduit.android

import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.ui.kanban.KanbanEventBatchOutcome
import app.hermesconduit.android.ui.kanban.KanbanEventSocket
import app.hermesconduit.android.ui.kanban.KanbanEventStreamCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted socket: frames are pushed by the test; [close] ends the connection. */
class ScriptedSocket : KanbanEventSocket {
    private val frames = Channel<String>(Channel.UNLIMITED)
    var cancelled = false
    fun push(text: String) { frames.trySend(text) }
    fun close() { frames.close() }
    override suspend fun receive(): String? = frames.receiveCatching().getOrNull()
    override fun cancel() {
        cancelled = true
        frames.close()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KanbanEventStreamTest {
    private val stamp = KanbanBoardContextStamp("alpha", 3)

    private class Harness(val coordinator: KanbanEventStreamCoordinator, val sockets: MutableList<ScriptedSocket>, val batches: MutableList<KanbanEventInvalidation>)

    private fun TestScope.harness(
        initialCursor: Long = 10,
        outcomes: ArrayDeque<KanbanEventBatchOutcome> = ArrayDeque(),
        mintFailures: Int = 0,
        scope: CoroutineScope = backgroundScope,
    ): Harness {
        val sockets = mutableListOf<ScriptedSocket>()
        val batches = mutableListOf<KanbanEventInvalidation>()
        var mints = 0
        var failuresLeft = mintFailures
        val coordinator = KanbanEventStreamCoordinator(
            stamp = stamp,
            boardSlug = "alpha",
            initialCursor = initialCursor,
            baseUrl = "https://hermes.example/dash",
            scope = scope,
            socketFactory = { ScriptedSocket().also { sockets += it } },
            ticketMinter = {
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("mint failed")
                }
                "ticket-${++mints}"
            },
            onBatch = { invalidation ->
                batches += invalidation
                outcomes.removeFirstOrNull() ?: KanbanEventBatchOutcome.COMPLETED
            },
        )
        return Harness(coordinator, sockets, batches)
    }

    @Test
    fun connectUsesEventsPathConcreteBoardWatermarkAndFreshTicket() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        assertEquals(listOf("wss://hermes.example/dash/api/plugins/kanban/events?ticket=ticket-1&board=alpha&since=10"), h.coordinator.issuedUrls)
        h.sockets.single().close()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("ticket-1", "ticket-2"), h.coordinator.mintedTickets)
        assertTrue("never reuses a ticket", h.coordinator.issuedUrls[1].contains("ticket=ticket-2"))
        h.coordinator.stop()
    }

    @Test
    fun cursorAdvancesResumesAcrossReconnectAndNeverMovesBackwards() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        h.sockets[0].push("""{"events": [{"id": 12, "task_id": "t1"}], "cursor": 12}""")
        h.sockets[0].push("""{"events": [], "cursor": 11}""")
        runCurrent()
        assertEquals(12L, h.coordinator.cursor)
        h.sockets[0].close()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(h.coordinator.issuedUrls.last().endsWith("since=12"))
        h.coordinator.stop()
    }

    @Test
    fun coalescingWindowUnionsTouchedIdsIntoOneRefresh() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        val socket = h.sockets.single()
        socket.push("""{"events": [{"id": 11, "task_id": "t1"}], "cursor": 11}""")
        socket.push("""{"events": [{"id": 12, "task_id": "t2"}, {"id": 13}], "cursor": 13}""")
        runCurrent()
        assertTrue(h.batches.isEmpty())
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, h.batches.size)
        assertEquals(setOf("t1", "t2"), h.batches.single().taskIds)
        assertTrue(h.batches.single().boardInvalidated)
        assertEquals(stamp, h.batches.single().context)
        h.coordinator.stop()
    }

    @Test
    fun emptyEventsFrameCausesNoInvalidationButAdvancesCursor() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        h.sockets.single().push("""{"events": [], "cursor": 40}""")
        h.sockets.single().push("not json at all")
        h.sockets.single().push("""{"events": "nope"}""")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(h.batches.isEmpty())
        assertEquals(40L, h.coordinator.cursor)
        assertTrue("the loop survives malformed frames", h.coordinator.isRunning && !h.sockets.single().cancelled)
        h.coordinator.stop()
    }

    @Test
    fun malformedLeadingElementConsumedAndLaterEventsDelivered() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        h.sockets.single().push("""{"events": [42, {"id": 11, "task_id": "t9", "kind": "brand_new_kind"}], "cursor": 11}""")
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(setOf("t9"), h.batches.single().taskIds)
        h.coordinator.stop()
    }

    @Test
    fun deferredRetryBoundedToOneShotWithFreshRevision() = runTest {
        val h = harness(outcomes = ArrayDeque(listOf(KanbanEventBatchOutcome.RETRY_SOON, KanbanEventBatchOutcome.RETRY_SOON)))
        h.coordinator.start()
        runCurrent()
        h.sockets.single().push("""{"events": [{"id": 11, "task_id": "t1"}], "cursor": 11}""")
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, h.batches.size)
        advanceTimeBy(751)
        runCurrent()
        assertEquals(2, h.batches.size)
        assertTrue(h.batches[1].revision > h.batches[0].revision)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("one retry only", 2, h.batches.size)
        h.coordinator.stop()
    }

    @Test
    fun eventDuringInFlightRefreshRunsExactlyOneFollowUp() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sockets = mutableListOf<ScriptedSocket>()
        val batches = mutableListOf<Set<String>>()
        val coordinator = KanbanEventStreamCoordinator(
            stamp, "alpha", 10, "https://h.example", backgroundScope,
            socketFactory = { ScriptedSocket().also { sockets += it } },
            ticketMinter = { "t" },
            onBatch = {
                batches += it.taskIds
                if (batches.size == 1) gate.await()
                KanbanEventBatchOutcome.COMPLETED
            },
        )
        coordinator.start()
        runCurrent()
        sockets.single().push("""{"events": [{"id": 11, "task_id": "a"}], "cursor": 11}""")
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        sockets.single().push("""{"events": [{"id": 12, "task_id": "b"}], "cursor": 12}""")
        sockets.single().push("""{"events": [{"id": 13, "task_id": "c"}], "cursor": 13}""")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(setOf("a"), setOf("b", "c")), batches)
        coordinator.stop()
    }

    @Test
    fun ticketMintFailureRetriesQuietlyWithBackoff() = runTest {
        val h = harness(mintFailures = 2)
        h.coordinator.start()
        runCurrent()
        assertTrue(h.sockets.isEmpty())
        advanceTimeBy(1_001)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, h.sockets.size)
        assertEquals(listOf(1_000L, 2_000L), h.coordinator.recordedBackoffs)
        h.coordinator.stop()
    }

    @Test
    fun stopCancelsSocketAndSilencesLateFrames() = runTest {
        val h = harness()
        h.coordinator.start()
        runCurrent()
        val socket = h.sockets.single()
        socket.push("""{"events": [{"id": 11, "task_id": "t1"}], "cursor": 11}""")
        runCurrent()
        h.coordinator.stop()
        assertTrue(socket.cancelled)
        assertFalse(h.coordinator.isRunning)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("a retired generation publishes nothing", h.batches.isEmpty())
        assertEquals(1, h.sockets.size)
    }
}
