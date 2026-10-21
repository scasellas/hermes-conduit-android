package app.hermesconduit.android.ui.kanban

import app.hermesconduit.android.model.KanbanBoardContextStamp
import app.hermesconduit.android.model.KanbanEventFrame
import app.hermesconduit.android.model.KanbanEventInvalidation
import app.hermesconduit.android.model.KanbanLiveUpdatePolicy
import app.hermesconduit.android.net.CloudflareAccess
import app.hermesconduit.android.net.ConnectionUrlPolicy
import app.hermesconduit.android.net.CustomHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One open events socket. [receive] returns null once the socket closed. */
interface KanbanEventSocket {
    suspend fun receive(): String?
    fun cancel()
}

enum class KanbanEventBatchOutcome { COMPLETED, RETRY_SOON, DISCARD }

/**
 * Live invalidation over `WS api/plugins/kanban/events?ticket=&board=&since=`
 * (port of KanbanEventStreamCoordinator). Frames never carry state: every
 * non-empty batch only asks the store for an ordinary REST refresh.
 *
 * - A fresh ticket is minted for EVERY connect; the board is pinned per stream.
 * - The cursor starts at the snapshot's `latest_event_id`, never moves backwards
 *   and is kept across reconnects within this coordinator only.
 * - Touched ids coalesce over a 300 ms window; an event during an in-flight
 *   refresh produces exactly one follow-up; a deferred batch retries once.
 */
class KanbanEventStreamCoordinator(
    private val stamp: KanbanBoardContextStamp,
    private val boardSlug: String,
    initialCursor: Long,
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val socketFactory: suspend (url: String) -> KanbanEventSocket,
    private val ticketMinter: suspend () -> String,
    private val coalescingWindowMs: Long = KanbanLiveUpdatePolicy.COALESCING_WINDOW_MS,
    private val deferredRetryMs: Long = KanbanLiveUpdatePolicy.DEFERRED_RETRY_MS,
    private val onBatch: suspend (KanbanEventInvalidation) -> KanbanEventBatchOutcome,
) {
    var cursor: Long = initialCursor
        private set
    val issuedUrls = mutableListOf<String>()
    val mintedTickets = mutableListOf<String>()
    val recordedBackoffs = mutableListOf<Long>()
    var cancelledSockets = 0
        private set

    private var runId = 0
    private var loopJob: Job? = null
    private var batchRunId = 0
    private var batchJob: Job? = null
    private var currentSocket: KanbanEventSocket? = null
    private val pendingTaskIds = LinkedHashSet<String>()
    private var pendingBoardInvalidation = false
    private var consecutiveFailures = 0
    private var revisionCounter = 0

    val isRunning: Boolean get() = loopJob != null

    fun start() {
        if (loopJob != null) return
        val myRun = ++runId
        consecutiveFailures = 0
        loopJob = scope.launch { runLoop(myRun) }
    }

    /** Retires this generation: socket cancelled, pending batches dropped, late completions inert. */
    fun stop() {
        runId++
        batchRunId++
        loopJob?.cancel()
        loopJob = null
        batchJob?.cancel()
        batchJob = null
        currentSocket?.let {
            it.cancel()
            cancelledSockets++
        }
        currentSocket = null
        pendingTaskIds.clear()
        pendingBoardInvalidation = false
    }

    private fun isCurrent(myRun: Int) = myRun == runId

    fun eventsUrl(ticket: String): String = ConnectionUrlPolicy.webSocketUrl(
        baseUrl,
        "api/plugins/kanban/events",
        linkedMapOf("ticket" to ticket, "board" to boardSlug, "since" to cursor.toString()),
    )

    private suspend fun runLoop(myRun: Int) {
        while (isCurrent(myRun)) {
            val ticket = try {
                ticketMinter()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!isCurrent(myRun)) return
                backoff()
                continue
            }
            if (!isCurrent(myRun)) return
            mintedTickets += ticket
            if (mintedTickets.size > 50) mintedTickets.removeAt(0)
            val url = try {
                eventsUrl(ticket)
            } catch (_: Exception) {
                backoff()
                continue
            }
            issuedUrls += url
            if (issuedUrls.size > 50) issuedUrls.removeAt(0)
            val socket = try {
                socketFactory(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!isCurrent(myRun)) return
                backoff()
                continue
            }
            if (!isCurrent(myRun)) {
                socket.cancel()
                cancelledSockets++
                return
            }
            currentSocket = socket
            consecutiveFailures = 0
            try {
                while (isCurrent(myRun)) {
                    val message = socket.receive() ?: break
                    if (!isCurrent(myRun)) break
                    ingest(message, myRun)
                }
            } catch (e: CancellationException) {
                retire(socket)
                throw e
            } catch (_: Exception) {
            }
            retire(socket)
            if (!isCurrent(myRun)) return
            backoff()
        }
    }

    private fun retire(socket: KanbanEventSocket) {
        if (currentSocket !== socket) return
        socket.cancel()
        cancelledSockets++
        currentSocket = null
    }

    private suspend fun backoff() {
        val delayMs = KanbanLiveUpdatePolicy.reconnectDelayMs(consecutiveFailures)
        recordedBackoffs += delayMs
        if (recordedBackoffs.size > 50) recordedBackoffs.removeAt(0)
        consecutiveFailures++
        delay(delayMs)
    }

    /** Test seam: feeds one frame as if it arrived on the live socket. */
    fun injectForTesting(text: String) {
        if (isRunning) ingest(text, runId)
    }

    private fun ingest(text: String, myRun: Int) {
        val frame = KanbanEventFrame.parse(text) ?: return
        frame.cursor?.let { if (it > cursor) cursor = it }
        if (frame.events.isEmpty()) return
        frame.events.mapNotNullTo(pendingTaskIds) { it.taskId?.takeIf(String::isNotEmpty) }
        pendingBoardInvalidation = true
        scheduleFlushIfIdle(myRun)
    }

    private fun scheduleFlushIfIdle(myRun: Int) {
        if (batchJob != null) return
        val myBatch = ++batchRunId
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runBatchLifecycle(myRun, myBatch)
            } finally {
                if (myBatch == batchRunId) batchJob = null
            }
        }
        batchJob = job
        job.start()
    }

    private suspend fun runBatchLifecycle(myRun: Int, batchRun: Int) {
        delay(coalescingWindowMs)
        fun live() = isCurrent(myRun) && batchRun == batchRunId
        while (live()) {
            var ids: Set<String> = pendingTaskIds.toSet()
            var boardInvalidated = pendingBoardInvalidation
            pendingTaskIds.clear()
            pendingBoardInvalidation = false
            if (!boardInvalidated && ids.isEmpty()) return
            var retryAvailable = true
            while (boardInvalidated || ids.isNotEmpty()) {
                if (!live()) return
                revisionCounter++
                val outcome = try {
                    onBatch(KanbanEventInvalidation(revisionCounter, stamp, ids, boardInvalidated))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return // handler failure: polling converges
                }
                if (!live()) return
                if (outcome != KanbanEventBatchOutcome.RETRY_SOON || !retryAvailable) break
                retryAvailable = false
                delay(deferredRetryMs)
                ids = ids + pendingTaskIds
                pendingTaskIds.clear()
                boardInvalidated = boardInvalidated || pendingBoardInvalidation
                pendingBoardInvalidation = false
            }
        }
    }
}

/** OkHttp adapter: opens the socket and waits (≤10 s) for the upgrade; OkHttp pings keep it honest. */
class OkHttpKanbanEventSocket private constructor() : KanbanEventSocket {
    private val messages = Channel<String>(Channel.UNLIMITED)
    private var socket: WebSocket? = null

    override suspend fun receive(): String? = messages.receiveCatching().getOrNull()

    override fun cancel() {
        socket?.close(1001, null)
        messages.close()
    }

    companion object {
        suspend fun open(
            http: OkHttpClient,
            url: String,
            cloudflare: CloudflareAccess?,
            customHeaders: Map<String, String>,
        ): OkHttpKanbanEventSocket {
            val adapter = OkHttpKanbanEventSocket()
            val opened = CompletableDeferred<Unit>()
            val builder = Request.Builder().url(url).header("X-Conduit-Client", "conduit")
            if (url.startsWith("wss://")) {
                cloudflare?.apply(builder)
                CustomHeaders.apply(builder, customHeaders)
            }
            val client = http.newBuilder()
                .pingInterval(KanbanLiveUpdatePolicy.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            adapter.socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    adapter.messages.trySend(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    adapter.messages.trySend(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    adapter.messages.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    adapter.messages.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                    adapter.messages.close()
                }
            })
            try {
                withTimeout(10_000) { opened.await() }
            } catch (e: Exception) {
                adapter.cancel()
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                throw IOException("The Kanban event stream didn't open.", e)
            }
            return adapter
        }
    }
}
