package app.hermesconduit.android.core.live

import android.util.Log
import app.hermesconduit.android.core.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The transport a GPT-Live session drives: one WebRTC peer connection
 * carrying the microphone out and the model's voice back as audio tracks,
 * plus the `oai-events` data channel. A seam so the session and its
 * controller are testable without WebRTC or audio hardware. Callbacks
 * arrive on the session's dispatcher.
 */
interface GPTLivePeer {
    var onMessage: ((String) -> Unit)?
    /** The connection failed or closed underneath the session. */
    var onDisconnected: (() -> Unit)?
    /** ICE lost the path for now (`true`), or got it back (`false`). */
    var onConnectionInterrupted: ((Boolean) -> Unit)?
    /** The call's audio paused for another sound (`true`) or came back (`false`). */
    var onAudioPaused: ((Boolean) -> Unit)?
    /** Opens the microphone and the data channel and returns the local SDP offer, ICE candidates gathered. */
    suspend fun makeOffer(): String
    suspend fun acceptAnswer(sdp: String)
    /** False when the channel isn't open or refused the message. */
    fun send(text: String): Boolean
    /** Mute is the local track disabled: the subscription protocol has no mute event. */
    fun setMicrophoneEnabled(enabled: Boolean)
    /** Speaker mute (#487) is the model's incoming track disabled. */
    fun setSpeakerEnabled(enabled: Boolean)
    fun close()
}

/** A GPT-Live session's state. */
sealed interface GPTLiveSessionState {
    data object Idle : GPTLiveSessionState
    data object Connecting : GPTLiveSessionState
    data object Ready : GPTLiveSessionState
    data class Failed(val message: String) : GPTLiveSessionState
    data object Stopped : GPTLiveSessionState
}

/** The seam the GPT-Live controller drives its session through. */
interface GPTLiveSessionControlling {
    var onEvent: ((GPTLiveProtocol.ServerEvent) -> Unit)?
    var onStateChange: ((GPTLiveSessionState) -> Unit)?
    var onAudioPaused: ((Boolean) -> Unit)?
    val isReady: Boolean
    /** Set when the host didn't use the voice the user chose. */
    val voiceNote: String?
    val briefingApplied: Boolean
    val greetingApplied: Boolean
    val billing: GPTLiveBilling
    fun start()
    fun stop()
    /** True once any of `text` went out (never resend it); false when none did. */
    fun appendContext(text: String, channel: GPTLiveProtocol.Channel, delegationId: String?): Boolean
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setSpeakerEnabled(enabled: Boolean)
}

/**
 * One GPT-Live call: the WebRTC offer goes to the Hermes host, which starts
 * the call on the path the profile chose and returns GPT-Live's answer; the
 * session is ready once GPT-Live reports `session.started`. A call that
 * drops is not resumed: the session fails and the user can start again.
 */
class GPTLiveSession(
    private val scope: CoroutineScope,
    private val client: GPTLiveSessionProviding,
    private val history: List<JsonObject> = emptyList(),
    private val voice: String? = null,
    private val briefing: String? = null,
    private val greeting: String? = null,
    private val callOpening: Boolean = false,
    private val makePeer: () -> GPTLivePeer,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val reconnectGraceMs: Long = RECONNECT_GRACE_MS,
) : GPTLiveSessionControlling {
    var state: GPTLiveSessionState = GPTLiveSessionState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange?.invoke(value)
        }

    var sessionId: String? = null
        private set
    override var voiceNote: String? = null
        private set
    override var briefingApplied = false
        private set
    override var greetingApplied = false
        private set
    override var billing = GPTLiveBilling.SUBSCRIPTION
        private set
    private val dialect get() = GPTLiveProtocol.Dialect.of(billing)
    private var turns = GPTLiveProtocol.TurnAssembler()
    private var quietJob: Job? = null

    override var onEvent: ((GPTLiveProtocol.ServerEvent) -> Unit)? = null
    override var onStateChange: ((GPTLiveSessionState) -> Unit)? = null
    override var onAudioPaused: ((Boolean) -> Unit)? = null

    private var peer: GPTLivePeer? = null
    private var connectJob: Job? = null
    private var startWatchdog: Job? = null
    private var reconnectWatchdog: Job? = null

    override val isReady: Boolean get() = state == GPTLiveSessionState.Ready
    private val isFailed get() = state is GPTLiveSessionState.Failed

    override fun start() {
        if (!(state == GPTLiveSessionState.Idle || state == GPTLiveSessionState.Stopped || isFailed)) return
        sessionId = null
        billing = GPTLiveBilling.SUBSCRIPTION
        turns = GPTLiveProtocol.TurnAssembler()
        state = GPTLiveSessionState.Connecting
        val peer = makePeer()
        this.peer = peer
        peer.onMessage = { text -> if (this.peer === peer) received(text) }
        peer.onDisconnected = { if (this.peer === peer) connectionLost() }
        peer.onConnectionInterrupted = { interrupted -> if (this.peer === peer) connectionInterrupted(interrupted, peer) }
        peer.onAudioPaused = { paused ->
            if (this.peer === peer && (state == GPTLiveSessionState.Ready || state == GPTLiveSessionState.Connecting)) onAudioPaused?.invoke(paused)
        }
        connectJob = scope.launch { connect(peer) }
    }

    /** Ends the call now: GPT-Live is told (`session.close`, best effort) and the peer closes. */
    override fun stop() {
        if (state == GPTLiveSessionState.Stopped) return
        val peer = peer
        if (peer != null && state == GPTLiveSessionState.Ready) peer.send(GPTLiveProtocol.encode(GPTLiveProtocol.sessionCloseMessage()))
        tearDown()
        state = GPTLiveSessionState.Stopped
    }

    override fun appendContext(text: String, channel: GPTLiveProtocol.Channel, delegationId: String?): Boolean {
        val peer = peer
        if (state != GPTLiveSessionState.Ready || peer == null) return false
        val messages = GPTLiveProtocol.contextAppendMessages(text, channel, delegationId, dialect)
        if (messages.isEmpty()) return true
        messages.forEachIndexed { index, message ->
            if (!peer.send(GPTLiveProtocol.encode(message))) {
                if (index > 0) runCatching { Log.w(TAG, "GPT-Live context append cut short after $index of ${messages.size} chunks") }
                return index > 0
            }
        }
        return true
    }

    override fun setMicrophoneEnabled(enabled: Boolean) { peer?.setMicrophoneEnabled(enabled) }
    override fun setSpeakerEnabled(enabled: Boolean) { peer?.setSpeakerEnabled(enabled) }

    private fun isCurrent(peer: GPTLivePeer) = this.peer === peer && state == GPTLiveSessionState.Connecting

    private suspend fun connect(peer: GPTLivePeer) {
        try {
            if (!isCurrent(peer)) return
            val offer = peer.makeOffer()
            if (!isCurrent(peer)) return
            val answer = client.createSession(offer, history, voice, briefing, greeting, callOpening)
            if (!isCurrent(peer)) return
            sessionId = answer.sessionId
            billing = answer.billing
            briefingApplied = answer.briefingApplied
            greetingApplied = answer.greetingApplied
            voiceNote = voiceNote(voice, answer.voice)
            peer.acceptAnswer(answer.sdp)
            if (!isCurrent(peer)) return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCurrent(peer)) return
            fail(liveErrorMessage(e))
            return
        }
        startWatchdog = scope.launch {
            delay(startTimeoutMs)
            if (!isCurrent(peer)) return@launch
            runCatching { Log.w(TAG, "GPT-Live never reported session.started") }
            fail(L10n.tr("GPT-Live didn't start the conversation. Try again."))
        }
    }

    private fun received(text: String) {
        val event = GPTLiveProtocol.decode(text) ?: return
        if (dialect != GPTLiveProtocol.Dialect.API) {
            process(event)
            return
        }
        val current = peer
        for (assembled in turns.receive(event)) {
            process(assembled)
            // A close or failure tore the call down: nothing after it is heard.
            if (peer == null || peer !== current) return
        }
        scheduleQuietFlush()
    }

    private fun scheduleQuietFlush() {
        quietJob?.cancel()
        quietJob = null
        val current = peer ?: return
        if (!turns.hasOpenTurn) return
        val quiet = turns.openTurnQuietIntervalMs
        quietJob = scope.launch {
            delay(quiet)
            if (peer !== current) return@launch
            quietJob = null
            turns.flush()?.let(::process)
        }
    }

    private fun process(event: GPTLiveProtocol.ServerEvent) {
        when (event) {
            is GPTLiveProtocol.ServerEvent.SessionStarted -> {
                event.id?.let { sessionId = it }
                if (state == GPTLiveSessionState.Connecting) {
                    startWatchdog?.cancel()
                    startWatchdog = null
                    state = GPTLiveSessionState.Ready
                }
            }
            is GPTLiveProtocol.ServerEvent.Error -> {
                // A late append after a close is expected noise.
                if (event.code == "context_injection_incomplete") return
                runCatching { Log.w(TAG, "GPT-Live error: code=${event.code.orEmpty()}") }
                if (state == GPTLiveSessionState.Connecting) {
                    fail(
                        if (event.message.isEmpty()) L10n.tr("GPT-Live didn't start the conversation. Try again.")
                        else L10n.tr("GPT-Live refused the conversation: %s", event.message),
                    )
                    return
                }
            }
            is GPTLiveProtocol.ServerEvent.SessionClosed -> {
                onEvent?.invoke(event)
                if (state != GPTLiveSessionState.Stopped && !isFailed) {
                    tearDown()
                    state = GPTLiveSessionState.Stopped
                }
                return
            }
            else -> Unit
        }
        onEvent?.invoke(event)
    }

    /** WebRTC dropped to "disconnected", which usually recovers: kept for the grace and only then counted as lost. */
    private fun connectionInterrupted(interrupted: Boolean, peer: GPTLivePeer) {
        reconnectWatchdog?.cancel()
        reconnectWatchdog = null
        if (!interrupted || !(state == GPTLiveSessionState.Ready || state == GPTLiveSessionState.Connecting)) return
        reconnectWatchdog = scope.launch {
            delay(reconnectGraceMs)
            if (this@GPTLiveSession.peer !== peer) return@launch
            reconnectWatchdog = null
            connectionLost()
        }
    }

    private fun connectionLost() {
        if (!(state == GPTLiveSessionState.Ready || state == GPTLiveSessionState.Connecting)) return
        fail(L10n.tr("The GPT-Live connection was lost."))
    }

    private fun fail(message: String) {
        tearDown()
        state = GPTLiveSessionState.Failed(message)
    }

    private fun tearDown() {
        connectJob?.cancel()
        connectJob = null
        startWatchdog?.cancel()
        startWatchdog = null
        reconnectWatchdog?.cancel()
        reconnectWatchdog = null
        quietJob?.cancel()
        quietJob = null
        peer?.let {
            it.onMessage = null
            it.onDisconnected = null
            it.onConnectionInterrupted = null
            it.onAudioPaused = null
            it.close()
        }
        peer = null
    }

    companion object {
        private const val TAG = "GPTLive"
        /** How long GPT-Live has, after the answer, to start the session. */
        const val START_TIMEOUT_MS = 15_000L
        /** How long a call may stay in WebRTC's temporary disconnected state before it counts as lost. */
        const val RECONNECT_GRACE_MS = 6_000L

        /** Explains a chosen voice the host didn't use; null when none was asked for, or it was used. */
        fun voiceNote(requested: String?, applied: String?): String? {
            if (requested.isNullOrEmpty()) return null
            if (applied == null) return L10n.tr("The Conduit plugin on your Hermes server didn't confirm the voice you chose. Update the plugin to pick a voice.")
            if (applied.lowercase() == requested.lowercase()) return null
            return L10n.tr("Your Hermes server used the voice %s instead of %s.", applied, requested)
        }
    }
}
