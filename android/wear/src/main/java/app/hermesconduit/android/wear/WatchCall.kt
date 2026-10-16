package app.hermesconduit.android.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import app.hermesconduit.wearwire.ReplyAssembler
import app.hermesconduit.wearwire.ReplyEvent
import app.hermesconduit.wearwire.SpeechChunker
import app.hermesconduit.wearwire.VoiceCommands
import app.hermesconduit.wearwire.WearApproval
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A watch call with Hermes through the phone (iOS WatchBridgeCallModel's
 * role, on the Wear Data Layer): the watch listens, each utterance goes to
 * Conduit on the phone as a turn, the reply streams back and the watch
 * speaks it sentence by sentence. A tap on the orb while Hermes talks
 * interrupts it (barge-in) and listens again.
 */
class WatchCall(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: PhoneLink,
    private val log: WatchCallLog,
) {
    enum class Phase { IDLE, PREPARING, CONNECTING, LISTENING, THINKING, SPEAKING, NEEDS_TAP, LOST, ENDING, ENDED }

    data class Line(val fromUser: Boolean, val text: String)

    data class State(
        val phase: Phase = Phase.IDLE,
        val muted: Boolean = false,
        /** What's being heard, or the newest words of the reply. */
        val caption: String? = null,
        /** A hint or failure under the status. */
        val notice: String? = null,
        val transcript: List<Line> = emptyList(),
        val pendingApproval: WearApproval? = null,
        val liveSinceMs: Long? = null,
        val endedAtMs: Long? = null,
        val endReason: String? = null,
        val chatTitle: String? = null,
        /** Bumped when the call wants the system voice input screen (no recognizer service here). */
        val voiceScreenRequest: Int = 0,
    ) {
        val isActive: Boolean get() = phase != Phase.IDLE && phase != Phase.ENDED && phase != Phase.ENDING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val speech = SpeechInput(context)
    private val speaker = Speaker(context, onStart = ::onSpeakingStarted, onIdle = ::onSpeakingDone)
    private var callId = ""
    private var turnId = 0
    private var sessionId: String? = null
    private var turnDone = true
    private var silences = 0
    private var failures = 0
    private var turnSentAt = 0L
    private var firstReplyLogged = false
    private val chunker = SpeechChunker()
    private var assembler = ReplyAssembler()
    private var watchdog: Job? = null
    private var useVoiceScreen = false

    init {
        scope.launch { link.incoming.collect(::onMessage) }
    }

    // region Lifecycle

    /** Starts a call; [chatId] continues one of the phone's chats. */
    fun start(source: String, chatId: String? = null, chatTitle: String? = null) {
        if (_state.value.isActive) return
        callId = UUID.randomUUID().toString()
        turnId = 0
        sessionId = chatId
        turnDone = true
        silences = 0
        failures = 0
        _state.value = State(phase = Phase.PREPARING, chatTitle = chatTitle)
        log.note("callStart", mapOf("call" to callId.take(8), "source" to source, "continues" to (chatId != null)))
        scope.launch {
            _state.update { it.copy(phase = Phase.CONNECTING) }
            if (!link.refresh()) {
                log.note("callRefused", mapOf("call" to callId.take(8), "reason" to "phoneUnreachable"))
                finish(L10n.tr("Calls need Conduit on your phone to start."))
                return@launch
            }
            useVoiceScreen = !speech.available
            CallService.start(context)
            _state.update { it.copy(liveSinceMs = System.currentTimeMillis()) }
            log.note("callLive", mapOf("call" to callId.take(8), "input" to if (useVoiceScreen) "voiceScreen" else "recognizer"))
            listen()
        }
    }

    fun end() {
        val s = _state.value
        if (!s.isActive) return
        log.note("callEnd", mapOf("call" to callId.take(8), "turns" to turnId, "seconds" to s.liveSinceMs?.let { (System.currentTimeMillis() - it) / 1000 }))
        val id = callId
        scope.launch { link.send(WearMessage.Stop(id, end = true)) }
        finish(null)
    }

    private fun finish(reason: String?) {
        watchdog?.cancel()
        speech.cancel()
        speaker.stop()
        CallService.stop(context)
        _state.update { it.copy(phase = Phase.ENDED, endReason = reason, endedAtMs = System.currentTimeMillis(), pendingApproval = null, caption = null) }
    }

    fun dismissEnded() {
        if (_state.value.phase == Phase.ENDED) _state.value = State()
    }

    // endregion

    // region Listening

    private fun listen() {
        val s = _state.value
        if (!s.isActive) return
        if (s.muted) {
            _state.update { it.copy(phase = Phase.LISTENING, caption = null) }
            return
        }
        _state.update { it.copy(phase = Phase.LISTENING, caption = null, notice = null) }
        if (useVoiceScreen) {
            _state.update { it.copy(voiceScreenRequest = it.voiceScreenRequest + 1) }
            return
        }
        speech.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) {
                if (_state.value.phase == Phase.LISTENING) _state.update { it.copy(caption = text) }
            }
            override fun onFinal(text: String) {
                silences = 0
                failures = 0
                heard(text)
            }
            override fun onSilence() {
                if (_state.value.phase != Phase.LISTENING) return
                // A quiet stretch is normal in a call; after a few the call waits for a tap.
                if (++silences >= MAX_SILENCES) pause() else listen()
            }
            override fun onFailure(code: Int) {
                if (_state.value.phase != Phase.LISTENING) return
                log.note("recognizerFailed", mapOf("call" to callId.take(8), "code" to code))
                if (code == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    useVoiceScreen = true
                    listen()
                    return
                }
                if (++failures >= MAX_FAILURES) pause() else scope.launch { delay(400); listen() }
            }
        })
    }

    private fun pause() {
        speech.cancel()
        silences = 0
        failures = 0
        _state.update { it.copy(phase = Phase.NEEDS_TAP, caption = null) }
    }

    /** The system voice input screen's answer (null when the user backed out). */
    fun voiceScreenResult(text: String?) {
        if (!_state.value.isActive) return
        if (text.isNullOrBlank()) pause() else heard(text)
    }

    private fun heard(text: String) {
        speech.cancel()
        val s = _state.value
        if (!s.isActive) return
        when {
            VoiceCommands.asksToEndCall(text) -> {
                log.note("spokenEnd", mapOf("call" to callId.take(8)))
                end()
            }
            s.pendingApproval != null && link.phoneContext.value?.voiceApprovals == true && VoiceCommands.approvalAnswer(text) != null -> {
                answerApproval(s.pendingApproval, VoiceCommands.approvalAnswer(text) == WearWire.APPROVE_ONCE, byVoice = true)
                listen()
            }
            VoiceCommands.asksToStop(text) -> listen()
            else -> sendTurn(text)
        }
    }

    // endregion

    // region Turns

    private fun sendTurn(text: String) {
        turnId++
        turnDone = false
        firstReplyLogged = false
        chunker.reset()
        assembler = ReplyAssembler()
        turnSentAt = System.currentTimeMillis()
        _state.update { it.copy(phase = Phase.THINKING, caption = null, notice = null, transcript = it.transcript + Line(true, text)) }
        val turn = WearMessage.Turn(callId, turnId, text, sessionId)
        log.note("turnSent", mapOf("call" to callId.take(8), "turn" to turnId))
        scope.launch {
            if (!link.send(turn)) {
                turnDone = true
                log.note("turnNotSent", mapOf("call" to callId.take(8), "turn" to turnId))
                _state.update { it.copy(phase = Phase.LOST, notice = L10n.tr("Your phone isn't reachable right now.")) }
            }
        }
        watchdog?.cancel()
        val waitingFor = turnId
        watchdog = scope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (turnId == waitingFor && !turnDone) {
                turnDone = true
                log.note("turnTimedOut", mapOf("call" to callId.take(8), "turn" to waitingFor))
                _state.update { it.copy(phase = Phase.NEEDS_TAP, notice = L10n.tr("Hermes took too long to answer.")) }
            }
        }
    }

    private fun onMessage(message: WearMessage) {
        when (message) {
            is WearMessage.Reply -> if (message.callId == callId && _state.value.isActive) onReply(message)
            is WearMessage.ApprovalResult -> if (!message.ok && _state.value.isActive) {
                _state.update { it.copy(notice = message.error ?: L10n.tr("The approval didn't go through.")) }
            }
            else -> Unit
        }
    }

    private fun onReply(reply: WearMessage.Reply) {
        when (val event = reply.event) {
            is ReplyEvent.Started -> sessionId = event.sessionId.ifEmpty { sessionId }
            // Approvals belong to the chat, not the turn: shown even after a barge-in.
            is ReplyEvent.Approval -> {
                vibrate()
                log.note("approvalShown", mapOf("call" to callId.take(8)))
                _state.update { it.copy(pendingApproval = event.approval) }
            }
            is ReplyEvent.ApprovalCleared -> _state.update { s ->
                if (s.pendingApproval?.requestId == event.requestId) s.copy(pendingApproval = null) else s
            }
            else -> if (reply.turnId == turnId && !turnDone) onTurnEvent(event)
        }
    }

    private fun onTurnEvent(event: ReplyEvent) {
        if (!firstReplyLogged && (event is ReplyEvent.Text || event is ReplyEvent.Done)) {
            firstReplyLogged = true
            log.note("firstReply", mapOf("call" to callId.take(8), "turn" to turnId, "ms" to System.currentTimeMillis() - turnSentAt))
        }
        when (event) {
            is ReplyEvent.Text -> if (assembler.apply(event)) {
                chunker.update(assembler.text).forEach(speaker::speak)
                _state.update { it.copy(caption = tail(assembler.text)) }
            }
            is ReplyEvent.Done -> {
                watchdog?.cancel()
                assembler.finish(event.text)
                chunker.finish(assembler.text).forEach(speaker::speak)
                turnDone = true
                log.note("turnDone", mapOf("call" to callId.take(8), "turn" to turnId, "ms" to System.currentTimeMillis() - turnSentAt, "interrupted" to event.interrupted))
                _state.update { s ->
                    s.copy(caption = tail(assembler.text), transcript = if (assembler.text.isBlank()) s.transcript else s.transcript + Line(false, assembler.text))
                }
                if (!speaker.speaking) onSpeakingDone()
            }
            is ReplyEvent.Failed -> {
                watchdog?.cancel()
                turnDone = true
                log.note("turnFailed", mapOf("call" to callId.take(8), "turn" to turnId))
                speaker.stop()
                _state.update { it.copy(phase = Phase.NEEDS_TAP, caption = null, notice = event.message.ifBlank { L10n.tr("Call didn't work") }) }
            }
            else -> Unit
        }
    }

    private fun onSpeakingStarted() {
        if (_state.value.phase == Phase.THINKING) _state.update { it.copy(phase = Phase.SPEAKING) }
    }

    private fun onSpeakingDone() {
        val phase = _state.value.phase
        if (!turnDone || (phase != Phase.SPEAKING && phase != Phase.THINKING)) return
        listen()
    }

    // endregion

    // region Controls

    /** The orb: interrupts Hermes, continues a paused call, or ends what's being said. */
    fun tap() {
        when (_state.value.phase) {
            Phase.SPEAKING, Phase.THINKING -> interrupt()
            Phase.NEEDS_TAP, Phase.LOST -> {
                silences = 0
                failures = 0
                listen()
            }
            Phase.LISTENING -> if (!useVoiceScreen) speech.finish() else _state.update { it.copy(voiceScreenRequest = it.voiceScreenRequest + 1) }
            else -> Unit
        }
    }

    /** Barge-in: Hermes stops talking (here and on the host) and the call listens. */
    private fun interrupt() {
        log.note("interrupted", mapOf("call" to callId.take(8), "turn" to turnId, "speaking" to speaker.speaking))
        speaker.stop()
        watchdog?.cancel()
        val running = !turnDone
        turnDone = true
        if (assembler.text.isNotBlank()) _state.update { it.copy(transcript = it.transcript + Line(false, assembler.text + " …")) }
        turnId++ // what's still on its way belongs to the turn that was cut off
        val id = callId
        if (running) scope.launch { link.send(WearMessage.Stop(id)) }
        listen()
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        if (muted) speech.cancel()
        if (_state.value.phase == Phase.LISTENING) listen()
    }

    fun answerApproval(approval: WearApproval, approve: Boolean, byVoice: Boolean = false) {
        log.note("approvalAnswered", mapOf("call" to callId.take(8), "approve" to approve, "voice" to byVoice))
        _state.update { s -> if (s.pendingApproval?.key == approval.key) s.copy(pendingApproval = null) else s }
        scope.launch {
            link.send(WearMessage.ApprovalRespond(approval.sessionId, approval.requestId, if (approve) WearWire.APPROVE_ONCE else WearWire.DENY))
        }
    }

    // endregion

    private fun vibrate() {
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }

    private fun tail(text: String): String = app.hermesconduit.wearwire.SpeakableText.clean(text).takeLast(140)

    companion object {
        const val MAX_SILENCES = 3
        const val MAX_FAILURES = 3
        const val REPLY_TIMEOUT_MS = 200_000L
    }
}
