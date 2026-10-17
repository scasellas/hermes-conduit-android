package app.hermesconduit.android.core.live

import android.util.Log
import app.hermesconduit.android.core.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One Grok Live conversation through the Hermes host's relay socket. The
 * first frame is the session.update; the session is ready once xAI confirms
 * it. xAI has no session resumption, so a dropped connection reconnects as
 * a new xAI session and the controller hears about it through
 * `onConnectionReplaced`. Speaks the same seam as Gemini Live's session, so
 * the Gemini Live conversation controller drives Grok Live unchanged.
 */
class GrokLiveSession(
    private val scope: CoroutineScope,
    private val client: GrokLiveConnecting,
    private val instructions: String,
    private val functions: List<GeminiLiveProtocol.FunctionDeclaration>,
    private val voice: String? = null,
    private val openSocket: (LiveSocketRequest) -> LiveSocket,
    private val setupTimeoutMs: Long = 15_000,
    private val responseTimeoutMs: Long = 30_000,
    private val reconnectDelay: suspend (Int) -> Unit = liveReconnectDelay,
) : LiveSessionControlling {
    var state: LiveSessionState = LiveSessionState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange?.invoke(value)
        }

    override var onEvent: ((GeminiLiveProtocol.ServerEvent) -> Unit)? = null
    override var onStateChange: ((LiveSessionState) -> Unit)? = null
    override var onConnectionReplaced: (() -> Unit)? = null
    /** Sessions that never resume never start one fresh: setting this does nothing, on purpose. */
    override var onFreshSession: ((Boolean) -> Unit)? = null
    override var connectionGeneration = 0
        private set

    private var socket: LiveSocket? = null
    private var connectionId = 0L
    private var nextId = 0L
    private var receiveJob: Job? = null
    private var connectJob: Job? = null
    private var hasConnectedOnce = false
    private var awaitingSetup: Pair<Long, Int>? = null
    private var lastSetupClose: LiveServerClose? = null
    /** Whether xAI is producing a response: a `response.create` while one runs is refused. */
    private var responseActive = false
    private var responseRequested = false
    /** Whether xAI confirmed (response.created) the response counted as running. */
    private var responseConfirmed = false

    private class Turn(val sent: (() -> Unit)?, val failed: (() -> Unit)?)

    /** Turns sent while a response ran, waiting for their response.create. */
    private var queuedTurns = mutableListOf<Turn>()
    /** Turns whose response.create went out, until xAI starts the response or refuses it. */
    private var unconfirmedTurns = mutableListOf<Turn>()
    private var responseWatchdog: Job? = null
    private var setupTimeoutJob: Job? = null
    private var responseHadTranscript = false
    /** The longest input transcript snapshot seen per xAI item on this connection. */
    private val inputSnapshots = mutableMapOf<String, String>()
    private val inputSnapshotOrder = mutableListOf<String>()

    override val isReady: Boolean get() = state == LiveSessionState.Ready
    private val isFailed: Boolean get() = state is LiveSessionState.Failed

    private fun newId() = ++nextId

    override fun start() {
        if (!(state == LiveSessionState.Idle || state == LiveSessionState.Stopped || isFailed)) return
        lastSetupClose = null
        hasConnectedOnce = false
        state = LiveSessionState.Connecting
        connectJob = scope.launch { connect(0) }
    }

    override fun stop() {
        closeConnection()
        state = LiveSessionState.Stopped
    }

    override fun send(message: LiveVoiceClientMessage, onSent: (() -> Unit)?, onFailure: (() -> Unit)?) {
        val (frames, wantsResponse) = GrokLiveProtocol.frames(message)
        val socket = socket
        if (state != LiveSessionState.Ready || socket == null) {
            onFailure?.invoke()
            return
        }
        val texts = frames.map(GrokLiveProtocol::encode)
        val id = connectionId
        scope.launch {
            try {
                for (text in texts) socket.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure?.invoke()
                connectionFailed(id, e)
                return@launch
            }
            if (!wantsResponse) {
                onSent?.invoke()
                return@launch
            }
            // A turn the model should answer is delivered once xAI confirms the response.
            when (val request = takeResponseRequest(id, Turn(onSent, onFailure))) {
                is ResponseRequest.Send -> try {
                    socket.send(request.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    connectionFailed(id, e)
                }
                ResponseRequest.Queued -> Unit
                ResponseRequest.ConnectionGone -> onFailure?.invoke()
            }
        }
    }

    // region Responses

    private sealed interface ResponseRequest {
        data class Send(val text: String) : ResponseRequest
        data object Queued : ResponseRequest
        data object ConnectionGone : ResponseRequest
    }

    private fun takeResponseRequest(id: Long, turn: Turn): ResponseRequest {
        if (id != connectionId || state != LiveSessionState.Ready) return ResponseRequest.ConnectionGone
        if (responseActive) {
            responseRequested = true
            queuedTurns += turn
            return ResponseRequest.Queued
        }
        beginRequest(listOf(turn))
        return ResponseRequest.Send(GrokLiveProtocol.encode(GrokLiveProtocol.responseCreate))
    }

    private fun beginRequest(turns: List<Turn>) {
        responseRequested = false
        responseActive = true
        responseConfirmed = false
        unconfirmedTurns.addAll(turns)
        armResponseWatchdog()
    }

    private fun sendResponseCreate() {
        val socket = socket ?: return
        val turns = queuedTurns
        queuedTurns = mutableListOf()
        beginRequest(turns)
        val id = connectionId
        val text = GrokLiveProtocol.encode(GrokLiveProtocol.responseCreate)
        scope.launch {
            try {
                socket.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectionFailed(id, e)
            }
        }
    }

    private fun confirmRequest() {
        responseConfirmed = true
        val turns = unconfirmedTurns
        unconfirmedTurns = mutableListOf()
        turns.forEach { it.sent?.invoke() }
    }

    private fun failUnconfirmedTurns() {
        val turns = unconfirmedTurns
        unconfirmedTurns = mutableListOf()
        turns.forEach { it.failed?.invoke() }
    }

    /** (Re)starts the wait for the running response's next event. */
    private fun armResponseWatchdog() {
        responseWatchdog?.cancel()
        val id = connectionId
        responseWatchdog = scope.launch {
            delay(responseTimeoutMs)
            if (id != connectionId || !responseActive) return@launch
            runCatching { Log.w(TAG, "Grok Live response stalled; no longer waiting for it") }
            responseActive = false
            failUnconfirmedTurns()
            if (responseRequested) sendResponseCreate()
        }
    }

    private fun dropResponseState() {
        responseWatchdog?.cancel()
        responseWatchdog = null
        responseActive = false
        responseRequested = false
        failUnconfirmedTurns()
        val turns = queuedTurns
        queuedTurns = mutableListOf()
        turns.forEach { it.failed?.invoke() }
    }

    // endregion

    // region Connection

    private fun fail(message: String) {
        closeConnection()
        state = LiveSessionState.Failed(message)
    }

    private fun closeConnection() {
        connectJob?.cancel()
        connectJob = null
        awaitingSetup = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        connectionId = newId()
        dropResponseState()
    }

    private suspend fun connect(attempt: Int) {
        val request = try {
            client.socketRequest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state == LiveSessionState.Stopped || isFailed) return
            retry(attempt, e)
            return
        }
        if (state == LiveSessionState.Stopped || isFailed) return
        val socket = openSocket(request)
        val update = GrokLiveProtocol.sessionUpdate(instructions, functions, voice)
        try {
            socket.send(GrokLiveProtocol.encode(update))
        } catch (e: CancellationException) {
            socket.close()
            throw e
        } catch (e: Exception) {
            val close = socket.serverClose(400)
            socket.close()
            if (state == LiveSessionState.Stopped || isFailed) return
            if (close != null && isRefusal(close)) {
                fail(refusalMessage(close))
                return
            }
            if (close != null) lastSetupClose = close
            retry(attempt, e)
            return
        }
        if (state == LiveSessionState.Stopped || isFailed) {
            socket.close()
            return
        }
        val id = newId()
        receiveJob?.cancel()
        this.socket?.close()
        this.socket = socket
        connectionId = id
        awaitingSetup = id to attempt
        responseActive = false
        responseRequested = false
        // Item ids belong to the xAI session, which a new connection replaces.
        inputSnapshots.clear()
        inputSnapshotOrder.clear()
        receiveJob = scope.launch { receiveLoop(socket, id) }
        // A server that neither confirms the session nor closes would leave the call connecting forever.
        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope.launch {
            delay(setupTimeoutMs)
            if (awaitingSetup?.first != id) return@launch
            runCatching { Log.w(TAG, "Grok Live session setup timed out") }
            connectionFailed(id, IOException("timed out"))
        }
    }

    private suspend fun receiveLoop(socket: LiveSocket, id: Long) {
        while (scope.isActive) {
            val text = try {
                socket.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val beforeSetup = awaitingSetup?.first == id
                val close = socket.serverClose(if (beforeSetup) 400 else 100)
                connectionFailed(id, e, close)
                return
            }
            for (frame in GrokLiveProtocol.decode(text)) {
                if (id != connectionId) return
                handle(frame, id)
            }
        }
    }

    private fun handle(frame: GrokLiveProtocol.ServerFrame, id: Long) {
        when (frame) {
            GrokLiveProtocol.ServerFrame.SessionUpdated -> {
                if (awaitingSetup?.first != id) return
                awaitingSetup = null
                setupTimeoutJob?.cancel()
                setupTimeoutJob = null
                lastSetupClose = null
                val replaced = hasConnectedOnce
                hasConnectedOnce = true
                if (replaced) {
                    connectionGeneration += 1
                    onConnectionReplaced?.invoke()
                }
                state = LiveSessionState.Ready
            }
            is GrokLiveProtocol.ServerFrame.Error -> {
                // Before the session is set up, xAI refused it: a retry would send the same.
                if (awaitingSetup?.first == id) {
                    fail(
                        if (frame.message.isEmpty()) L10n.tr("Grok Live refused the connection.")
                        else L10n.tr("Grok Live refused the connection: %s", clipped(frame.message)),
                    )
                    return
                }
                // A refused response.create or similar: the conversation goes on.
                if (responseActive && !responseConfirmed) {
                    responseActive = false
                    responseWatchdog?.cancel()
                    failUnconfirmedTurns()
                    if (responseRequested) sendResponseCreate()
                }
            }
            GrokLiveProtocol.ServerFrame.ResponseStarted -> {
                responseActive = true
                confirmRequest()
                responseHadTranscript = false
                armResponseWatchdog()
            }
            GrokLiveProtocol.ServerFrame.ResponseDone -> {
                responseActive = false
                responseWatchdog?.cancel()
                // A response that ended without ever starting answered nothing.
                failUnconfirmedTurns()
                if (responseRequested) sendResponseCreate()
            }
            is GrokLiveProtocol.ServerFrame.OutputTranscriptDone ->
                if (!responseHadTranscript) onEvent?.invoke(GeminiLiveProtocol.ServerEvent.OutputTranscription(frame.text))
            is GrokLiveProtocol.ServerFrame.InputTranscriptSnapshot -> {
                val itemId = frame.itemId
                if (itemId == null) {
                    onEvent?.invoke(GeminiLiveProtocol.ServerEvent.InputTranscription(frame.text))
                    return
                }
                val previous = inputSnapshots[itemId].orEmpty()
                // The longest snapshot is the baseline: a shorter revision adds nothing.
                if (frame.text.length > previous.length) rememberInputSnapshot(frame.text, itemId)
                GrokLiveProtocol.inputTranscriptDelta(previous, frame.text)?.let {
                    onEvent?.invoke(GeminiLiveProtocol.ServerEvent.InputTranscription(it))
                }
            }
            is GrokLiveProtocol.ServerFrame.Event -> {
                if (frame.event is GeminiLiveProtocol.ServerEvent.OutputTranscription) responseHadTranscript = true
                if (responseActive) armResponseWatchdog()
                onEvent?.invoke(frame.event)
            }
        }
    }

    private fun rememberInputSnapshot(text: String, itemId: String) {
        if (!inputSnapshots.containsKey(itemId)) {
            inputSnapshotOrder += itemId
            while (inputSnapshotOrder.size > REMEMBERED_INPUT_ITEMS) inputSnapshots.remove(inputSnapshotOrder.removeAt(0))
        }
        inputSnapshots[itemId] = text
    }

    private fun connectionFailed(id: Long, error: Throwable, serverClose: LiveServerClose? = null) {
        if (id != connectionId || state == LiveSessionState.Stopped || isFailed) return
        runCatching { Log.w(TAG, "Grok Live connection lost: ${error.javaClass.simpleName} closeCode=${serverClose?.code ?: 0}") }
        val pendingSetup = awaitingSetup?.takeIf { it.first == id }
        socket?.close()
        socket = null
        receiveJob?.cancel()
        receiveJob = null
        awaitingSetup = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        connectionId = newId()
        dropResponseState()
        if (serverClose != null && isRefusal(serverClose)) {
            fail(refusalMessage(serverClose))
            return
        }
        connectJob?.cancel()
        if (pendingSetup == null) {
            // A live conversation dropped: start a new xAI session.
            state = LiveSessionState.Reconnecting
            connectJob = scope.launch { connect(0) }
            return
        }
        if (serverClose != null) lastSetupClose = serverClose
        connectJob = scope.launch { retry(pendingSetup.second, error) }
    }

    private suspend fun retry(attempt: Int, error: Throwable) {
        if (attempt >= MAXIMUM_RECONNECT_ATTEMPTS) {
            val close = lastSetupClose
            // The dashboard's own reason (signed out, no ticket…), never a network error's.
            val dashboardReason = error.takeIf {
                it is app.hermesconduit.android.net.DashboardHttpException || it is app.hermesconduit.android.net.AuthExpiredException || it is IllegalStateException
            }?.message?.takeIf { it.isNotBlank() }
            fail(
                when {
                    close != null -> L10n.tr("Couldn't connect to Grok Live: %s", close.summary)
                    dashboardReason != null -> dashboardReason
                    else -> L10n.tr("Couldn't connect to Grok Live.")
                },
            )
            return
        }
        if (state != LiveSessionState.Connecting) state = LiveSessionState.Reconnecting
        reconnectDelay(attempt)
        if (state == LiveSessionState.Stopped || isFailed) return
        connect(attempt + 1)
    }

    // endregion

    companion object {
        private const val TAG = "GrokLive"
        const val MAXIMUM_RECONNECT_ATTEMPTS = 3
        private const val REMEMBERED_INPUT_ITEMS = 4

        /** The relay's 4502 (xAI unreachable or dropped) is worth retrying; any other 4xxx is a refusal. */
        fun isRefusal(close: LiveServerClose): Boolean {
            if (close.isHttpStatus) return close.code in setOf(400, 401, 403, 404)
            if (close.code == 4502) return false
            return close.code in setOf(1003, 1007, 1008, 1009) || close.code in 4000..4999
        }

        fun refusalMessage(close: LiveServerClose): String {
            if (close.isHttpStatus && close.code == 404) return GrokLiveAvailability.PluginMissing.userFacingReason ?: close.summary
            return L10n.tr("Grok Live refused the connection: %s", close.summary)
        }

        fun clipped(text: String): String {
            val line = text.lines().firstOrNull() ?: text
            return if (line.length <= 300) line else line.take(300) + "…"
        }
    }
}
