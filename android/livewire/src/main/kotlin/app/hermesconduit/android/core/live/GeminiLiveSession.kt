package app.hermesconduit.android.core.live

import android.util.Log
import app.hermesconduit.android.core.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A live session's connection state, shared by Gemini Live and Grok Live. */
sealed interface LiveSessionState {
    data object Idle : LiveSessionState
    data object Connecting : LiveSessionState
    data object Ready : LiveSessionState
    /** Switching to a new connection after the old one dropped. A GoAway handoff stays Ready. */
    data object Reconnecting : LiveSessionState
    data class Failed(val message: String) : LiveSessionState
    data object Stopped : LiveSessionState
}

/**
 * The seam a live voice conversation drives its session through: Gemini
 * Live's own, or Grok Live's (xAI's events read as Gemini Live's).
 */
interface LiveSessionControlling {
    var onEvent: ((GeminiLiveProtocol.ServerEvent) -> Unit)?
    var onStateChange: ((LiveSessionState) -> Unit)?
    var onConnectionReplaced: (() -> Unit)?
    /** A new server session replaced one Google wouldn't resume, so the model lost the conversation. */
    var onFreshSession: ((heardDropout: Boolean) -> Unit)?
    val isReady: Boolean
    /** Changes whenever a new connection takes over; calls made before can't be answered after it. */
    val connectionGeneration: Int
    fun start()
    fun stop()
    /** `onSent` runs once the socket took the message; `onFailure` when it never reached the socket. */
    fun send(message: LiveVoiceClientMessage, onSent: (() -> Unit)? = null, onFailure: (() -> Unit)? = null)
}

private fun logLive(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}

/**
 * One logical Gemini Live conversation over one or more WebSockets. Every
 * connection gets a fresh single-use token from Hermes. A GoAway or a lost
 * socket reconnects with the latest session-resumption handle, so the
 * conversation carries over. On a GoAway the old connection keeps carrying
 * the conversation, both ways, until the new one is set up. Runs on
 * [scope]'s (main) dispatcher.
 */
class GeminiLiveSession(
    private val scope: CoroutineScope,
    private val tokens: GeminiLiveTokenProviding,
    private val systemInstruction: String,
    private val functions: List<GeminiLiveProtocol.FunctionDeclaration>,
    /** Whether this session's lookups use Google Search at all. */
    private val usesGoogleSearch: Boolean = true,
    /** The prebuilt voice; null is Gemini's default. */
    private val voice: String? = null,
    private val openSocket: (url: String) -> LiveSocket,
    private val reconnectDelay: suspend (Int) -> Unit = liveReconnectDelay,
) : LiveSessionControlling {
    var state: LiveSessionState = LiveSessionState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange?.invoke(value)
        }

    /** Latest resumable handle from the server; null until the first update. */
    var resumptionHandle: String? = null
        private set

    override var onEvent: ((GeminiLiveProtocol.ServerEvent) -> Unit)? = null
    override var onStateChange: ((LiveSessionState) -> Unit)? = null
    override var onConnectionReplaced: (() -> Unit)? = null
    override var onFreshSession: ((Boolean) -> Unit)? = null
    override var connectionGeneration = 0
        private set

    /** Null: no fresh start pending; otherwise whether the user heard a dropout. */
    private var startingFresh: Boolean? = null

    private var socket: LiveSocket? = null
    /** Identity of the connection whose frames are current. */
    private var connectionId = 0L
    private var nextId = 0L
    private var receiveJob: Job? = null
    private var isHandingOff = false
    private var handoffSocket: LiveSocket? = null
    private var handoffId: Long? = null
    private var handoffReceiveJob: Job? = null
    private var connectJob: Job? = null
    private var hasConnectedOnce = false
    /** The connection still waiting for setupComplete, and which attempt it is. */
    private var awaitingSetup: Pair<Long, Int>? = null
    private var lastSetupClose: LiveServerClose? = null
    /** Whether setup asks for Google Search; dropped once Google refuses for quota. */
    private var googleSearch = usesGoogleSearch

    override val isReady: Boolean get() = state == LiveSessionState.Ready
    private val isFailed: Boolean get() = state is LiveSessionState.Failed
    val stateDescription: String get() = state.toString()

    private fun newId(): Long = ++nextId

    override fun start() {
        if (!(state == LiveSessionState.Idle || state == LiveSessionState.Stopped || isFailed)) return
        resumptionHandle = null
        startingFresh = null
        lastSetupClose = null
        googleSearch = usesGoogleSearch
        hasConnectedOnce = false
        state = LiveSessionState.Connecting
        connectJob = scope.launch { connect(0) }
    }

    override fun stop() {
        connectJob?.cancel()
        connectJob = null
        closeAllConnections()
        state = LiveSessionState.Stopped
    }

    private fun fail(message: String) {
        closeAllConnections()
        state = LiveSessionState.Failed(message)
    }

    private fun closeAllConnections() {
        connectJob?.cancel()
        awaitingSetup = null
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        dropHandoff()
        connectionId = newId()
    }

    private fun dropHandoff() {
        isHandingOff = false
        handoffReceiveJob?.cancel()
        handoffReceiveJob = null
        handoffSocket?.close()
        handoffSocket = null
        handoffId = null
    }

    override fun send(message: LiveVoiceClientMessage, onSent: (() -> Unit)?, onFailure: (() -> Unit)?) {
        val socket = socket
        if (state != LiveSessionState.Ready || socket == null) {
            onFailure?.invoke()
            return
        }
        val text = GeminiLiveProtocol.encode(GeminiLiveProtocol.message(message))
        val id = connectionId
        scope.launch {
            try {
                socket.send(text)
                onSent?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure?.invoke()
                connectionFailed(id, e)
            }
        }
    }

    // region Connection

    private suspend fun connect(attempt: Int) {
        val token = try {
            tokens.freshToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state == LiveSessionState.Stopped) return
            // A missing plugin or key will not fix itself on retry.
            if (e is GeminiLiveTokenException) {
                fail(liveErrorMessage(e))
                return
            }
            retry(attempt, e)
            return
        }
        if (state == LiveSessionState.Stopped) return
        val socket = openSocket(token.connectUrl)
        val id = newId()
        val setup = GeminiLiveProtocol.setupMessage(token.model, systemInstruction, functions, googleSearch, voice, resumptionHandle)
        try {
            socket.send(GeminiLiveProtocol.encode(setup))
        } catch (e: CancellationException) {
            socket.close()
            throw e
        } catch (e: Exception) {
            // A refused WebSocket upgrade surfaces here, on the first send.
            val close = socket.serverClose(400)
            socket.close()
            if (state == LiveSessionState.Stopped || isFailed) return
            if (close != null && close.isRefusal) {
                refused(close, attempt)
                return
            }
            if (close != null) lastSetupClose = close
            retry(attempt, e)
            return
        }
        if (state == LiveSessionState.Stopped) {
            socket.close()
            return
        }
        awaitingSetup = id to attempt
        if (isHandingOff && this.socket != null) {
            // The current connection keeps carrying the conversation until this one is set up.
            handoffSocket?.close()
            handoffReceiveJob?.cancel()
            handoffSocket = socket
            handoffId = id
            handoffReceiveJob = scope.launch { receiveLoop(socket, id) }
            return
        }
        isHandingOff = false
        receiveJob?.cancel()
        this.socket?.close()
        this.socket = socket
        connectionId = id
        receiveJob = scope.launch { receiveLoop(socket, id) }
    }

    private suspend fun receiveLoop(socket: LiveSocket, id: Long) {
        while (scope.isActive) {
            val text = try {
                socket.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A close before setup decides between failing and retrying: give its close frame a moment.
                val beforeSetup = awaitingSetup?.first == id
                val close = socket.serverClose(if (beforeSetup) 400 else 100)
                connectionFailed(id, e, close)
                return
            }
            if (id != connectionId && id != handoffId) return
            for (event in GeminiLiveProtocol.decode(text)) {
                // An event can retire this connection mid-frame.
                if (id != connectionId && id != handoffId) return
                handle(event, id)
            }
        }
    }

    private fun handle(event: GeminiLiveProtocol.ServerEvent, id: Long) {
        when (event) {
            GeminiLiveProtocol.ServerEvent.SetupComplete -> {
                val next = handoffSocket
                if (id == handoffId && next != null) {
                    // The handoff connection takes over; the old one closes.
                    val oldReceive = receiveJob
                    socket?.close()
                    socket = next
                    receiveJob = handoffReceiveJob
                    connectionId = id
                    handoffSocket = null
                    handoffReceiveJob = null
                    handoffId = null
                    oldReceive?.cancel()
                }
                isHandingOff = false
                val replaced = hasConnectedOnce
                hasConnectedOnce = true
                awaitingSetup = null
                lastSetupClose = null
                // Calls opened on the previous connection are gone before anyone sees Ready.
                if (replaced) {
                    connectionGeneration += 1
                    onConnectionReplaced?.invoke()
                }
                startingFresh?.let { heardDropout ->
                    startingFresh = null
                    onFreshSession?.invoke(heardDropout)
                }
                if (state == LiveSessionState.Ready) {
                    // A handoff never left Ready; say it again so listeners treat the new connection as ready.
                    onStateChange?.invoke(LiveSessionState.Ready)
                } else {
                    state = LiveSessionState.Ready
                }
            }
            is GeminiLiveProtocol.ServerEvent.ResumptionUpdate -> if (event.resumable && event.handle != null) resumptionHandle = event.handle
            is GeminiLiveProtocol.ServerEvent.GoAway -> {
                // Only the current connection's GoAway starts a handoff.
                if (id != connectionId) return
                logLive(TAG, "GoAway received (timeLeft=${event.timeLeftSeconds ?: -1}s); reconnecting with resumption")
                beginHandoff()
                return
            }
            else -> Unit
        }
        onEvent?.invoke(event)
    }

    private fun beginHandoff() {
        if (state != LiveSessionState.Ready || isHandingOff) return
        isHandingOff = true
        connectJob?.cancel()
        connectJob = scope.launch { connect(0) }
    }

    private fun connectionFailed(id: Long, error: Throwable, serverClose: LiveServerClose? = null) {
        if (state == LiveSessionState.Stopped || isFailed) return
        if (id == handoffId) {
            handoffFailed(error, serverClose)
            return
        }
        if (id != connectionId) return
        // Never the error's text: it can carry the connection URL, whose access_token is a live credential.
        logLive(TAG, "Gemini Live connection lost: ${error.javaClass.simpleName} closeCode=${serverClose?.code ?: 0}")
        socket?.close()
        socket = null
        receiveJob = null
        connectionId = newId()
        val pendingSetup = awaitingSetup?.takeIf { it.first == id }
        if (pendingSetup != null) awaitingSetup = null
        // A refusal before setup won't change on retry. On a live connection only a spent quota is handled that way.
        if (serverClose != null && (if (pendingSetup != null) serverClose.isRefusal else serverClose.isQuotaExhausted)) {
            dropHandoff()
            connectJob?.cancel()
            refused(serverClose, pendingSetup?.second ?: 0)
            return
        }
        if (pendingSetup == null) {
            state = LiveSessionState.Reconnecting
            // The connection a GoAway handoff is already setting up carries on and takes over once ready.
            if (isHandingOff) return
            connectJob?.cancel()
            connectJob = scope.launch { connect(0) }
            return
        }
        connectJob?.cancel()
        // Closed before setupComplete: a failed attempt, not a lost connection.
        if (serverClose != null) lastSetupClose = serverClose
        connectJob = scope.launch { retry(pendingSetup.second, error) }
    }

    /** The connection a GoAway handoff was setting up closed before its setupComplete. */
    private fun handoffFailed(error: Throwable, serverClose: LiveServerClose?) {
        logLive(TAG, "Gemini Live handoff connection lost: ${error.javaClass.simpleName} closeCode=${serverClose?.code ?: 0}")
        val attempt = awaitingSetup?.takeIf { it.first == handoffId }?.second ?: 0
        awaitingSetup = null
        handoffReceiveJob = null
        handoffSocket?.close()
        handoffSocket = null
        handoffId = null
        connectJob?.cancel()
        if (serverClose != null && serverClose.isQuotaExhausted && googleSearch) {
            // Search's own quota: the next attempt goes without it, still handing off.
            googleSearch = false
            connectJob = scope.launch {
                reconnectDelay(attempt)
                if (state == LiveSessionState.Stopped || isFailed) return@launch
                connect(attempt)
            }
            return
        }
        if (serverClose != null && serverClose.isRefusal) {
            if (startFreshIfResumptionRefused(serverClose, attempt, heardDropout = false)) return
            fail(refusalMessage(serverClose))
            return
        }
        if (serverClose != null) lastSetupClose = serverClose
        connectJob = scope.launch { retry(attempt, error) }
    }

    /**
     * Google refused the connection. A spent quota while Search is on is most
     * likely Search's own, so the same attempt runs again without it. A lost
     * resumption starts fresh once; any other refusal is final.
     */
    private fun refused(close: LiveServerClose, attempt: Int) {
        if (close.isQuotaExhausted && googleSearch) {
            googleSearch = false
            if (state == LiveSessionState.Ready) state = LiveSessionState.Reconnecting
            connectJob?.cancel()
            connectJob = scope.launch {
                reconnectDelay(attempt)
                if (state == LiveSessionState.Stopped || isFailed) return@launch
                connect(attempt)
            }
            return
        }
        // A GoAway handoff whose setup was refused: the old connection still serves.
        val heardDropout = !(isHandingOff && socket != null)
        if (startFreshIfResumptionRefused(close, attempt, heardDropout)) return
        fail(refusalMessage(close))
    }

    /**
     * A setup that carried a resumption handle was refused because Google lost
     * or expired that session: the handle is dropped and the same attempt runs
     * again as a new session, once.
     */
    private fun startFreshIfResumptionRefused(close: LiveServerClose, attempt: Int, heardDropout: Boolean): Boolean {
        if (onFreshSession == null || resumptionHandle == null || !close.isResumptionLost) return false
        logLive(TAG, "Gemini Live refused to resume (closeCode=${close.code}); starting a fresh session")
        resumptionHandle = null
        startingFresh = heardDropout
        if (heardDropout && state == LiveSessionState.Ready) state = LiveSessionState.Reconnecting
        connectJob?.cancel()
        connectJob = scope.launch { connect(attempt) }
        return true
    }

    private suspend fun retry(attempt: Int, @Suppress("UNUSED_PARAMETER") error: Throwable) {
        if (attempt >= MAXIMUM_RECONNECT_ATTEMPTS) {
            val close = lastSetupClose
            fail(if (close != null) L10n.tr("Couldn't connect to Gemini Live: %s", close.summary) else L10n.tr("Couldn't connect to Gemini Live."))
            return
        }
        // A handoff's current connection is still serving: stay ready.
        if (state != LiveSessionState.Connecting && !(isHandingOff && socket != null)) state = LiveSessionState.Reconnecting
        reconnectDelay(attempt)
        if (state == LiveSessionState.Stopped || isFailed) return
        connect(attempt + 1)
    }

    // endregion

    companion object {
        private const val TAG = "GeminiLive"
        /** Retries after a failed attempt (so at most 1 + 3 attempts in a row, plus one fresh start). */
        const val MAXIMUM_RECONNECT_ATTEMPTS = 3

        fun refusalMessage(close: LiveServerClose): String =
            if (close.isResumptionLost) L10n.tr("Gemini Live lost this conversation on its side. Tap Try again to start a new one.")
            else L10n.tr("Gemini Live refused the connection: %s", close.summary)
    }
}
