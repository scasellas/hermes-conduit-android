package app.hermesconduit.android.wear

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import app.hermesconduit.android.core.live.GeminiLiveAvailability
import app.hermesconduit.android.core.live.GeminiLiveProtocol
import app.hermesconduit.android.core.live.GeminiLiveSession
import app.hermesconduit.android.core.live.GeminiLiveToken
import app.hermesconduit.android.core.live.GrokLiveAvailability
import app.hermesconduit.android.core.live.GrokLiveConnecting
import app.hermesconduit.android.core.live.GrokLiveProtocol
import app.hermesconduit.android.core.live.GrokLiveSession
import app.hermesconduit.android.core.live.LiveSessionControlling
import app.hermesconduit.android.core.live.LiveSessionState
import app.hermesconduit.android.core.live.LiveSocketRequest
import app.hermesconduit.android.core.live.LiveVoiceClientMessage
import app.hermesconduit.android.core.live.OkHttpLiveSocket
import app.hermesconduit.wearwire.VoiceCommands
import app.hermesconduit.wearwire.WatchAudioBridgeStream
import app.hermesconduit.wearwire.WatchAudioBridgeWire
import app.hermesconduit.wearwire.WatchBridgeDelegation
import app.hermesconduit.wearwire.WatchDirectTokens
import app.hermesconduit.wearwire.WatchDirectWire
import app.hermesconduit.wearwire.WatchJobAnswer
import app.hermesconduit.wearwire.WatchJobProfiles
import app.hermesconduit.wearwire.WatchLiveToken
import app.hermesconduit.wearwire.WatchRejoin
import app.hermesconduit.wearwire.WatchStall
import app.hermesconduit.wearwire.WatchToolAnswer
import app.hermesconduit.wearwire.WatchToolRelayClient
import app.hermesconduit.wearwire.WatchToolSeal
import app.hermesconduit.wearwire.WatchVoiceActivity
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WatchVoiceWire.DirectOutgoing
import app.hermesconduit.wearwire.WatchVoiceWire.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * A live call the watch makes itself (port of the iOS `WatchDirectCallModel`,
 * designs/apple-watch-voice-direct.md): Gemini Live straight from the watch,
 * with the phone's own [GeminiLiveSession] (the same setup, resumption,
 * transcription, GoAway handoff and retries as a call on the phone), or Grok
 * with the phone's [GrokLiveSession], whose xAI session the Hermes host holds
 * and the watch reaches through the call grant's audio bridge.
 *
 * The phone builds the session (instructions, persona, memory, functions,
 * voice) and, for Gemini, hands over a single-use token; after that it only
 * runs the tools: each function call goes to it as a Data Layer request, and
 * while its jobs run the watch asks it for their news. With the call's grant,
 * lookups go through the push relay while the phone can't be reached, chats
 * and jobs always do: the watch asks Hermes for their news itself and shows a
 * job's approval request with Approve and Deny.
 *
 * Half duplex: the microphone isn't sent while the model speaks, nor for a
 * short echo tail after, and a tap stops the model.
 */
class WatchDirectCall(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: PhoneLink,
    private val log: WatchCallLog,
) {
    enum class Engine { GEMINI, GROK }

    private enum class Phase { IDLE, PREPARING, CONNECTING, LISTENING, SPEAKING, RECONNECTING, LOST, NEEDS_TAP, ENDING, ENDED }

    private val _state = MutableStateFlow(WatchCall.State())
    val state: StateFlow<WatchCall.State> = _state

    private val http = OkHttpClient()
    private val relayHttp = WatchToolRelayClient.httpClient(http)
    private val audio = WatchAudio(context)
    private var session: LiveSessionControlling? = null
    private var tokens: WatchDirectTokens? = null
    var engine = Engine.GEMINI
        private set
    var callId: Long = 0
        private set
    private var callUuid = UUID.randomUUID().toString()
    private var callStartedWallMs = 0L
    private var callStartedAt = 0L
    private var timers = mutableListOf<Job>()
    private var phase = Phase.IDLE
    private var endReason: String? = null
    private var liveSinceWallMs: Long? = null
    private var endedWallMs: Long? = null
    private var muted = false
    private var caption: String? = null
    private var runningJobs = 0
    private var relayJobsRunning = 0
    private var grokStreams = 0
    private var openingPrompt: String? = null
    private var endPhrases = listOf<String>()
    private var hasSentOpening = false
    private var sessionRequests = 0

    // Connection
    private var firstReadyAt: Long? = null
    private var reconnectStartedAt: Long? = null
    private var rejoinWaitingSince: Long? = null
    private var rejoinReason: String? = null
    private var rejoinCause = WatchRejoin.Cause.BROKE
    private var rejoinStartedAt: Long? = null
    private var rejoinWithoutPhone = false
    private var phonelessRejoinFailed = false
    private var rejoins = 0
    private var rejoinsHung = 0
    private var samplesDroppedWhileDown = 0
    /** Added to the session's connection generation: calls made on a broken session can't be answered on the fresh one. */
    private var generationOffset = 0
    private var drops = 0
    private var goAways = 0
    private var tokensFromPhone = 0
    private var tokensReused = 0
    private var tokensFromRelay = 0
    private var tokenFailures = 0

    // Conversation
    private var suppressingModelTurn = false
    private var modelTurnActive = false
    private var lastUserSpeechAt: Long? = null
    private var lastModelTurnEndedAt: Long? = null
    private var lastModelAudioAt: Long? = null
    private var awaitingReplySince: Long? = null
    private var awaitingAnswerSince: Long? = null
    private var unansweredTurns = 0
    private var replyOwedSince: Long? = null
    private var silentTurnSinceUser = false
    private var stallPromptedAt: Long? = null
    private var stallPrompts = 0
    private var stalls = 0
    private var turnStarted = false
    private var turnEndedByInterruption = false
    private var endRequestedAt: Long? = null
    private var transcript = mutableListOf<WatchVoiceWire.DirectTurn>()
    private var openUserLine: Int? = null
    private var openAssistantLine: Int? = null
    private var exchangeUserLine: Int? = null
    private var turns = 0

    // Tools
    private val toolsInFlight = mutableSetOf<String>()
    private val toolCalledAt = mutableMapOf<String, Long>()
    private val toolCallsSpokenFor = mutableSetOf<String>()
    private val withdrawnToolIds = mutableSetOf<String>()
    private val quietQueue = mutableListOf<DirectOutgoing>()
    private var toolCalls = 0
    private var toolsAnsweredLive = 0
    private var toolsUnreachable = 0
    private var jobsQueued = 0
    private var startJobsTakenSilently = 0
    private var toolRelay: WatchToolRelayClient? = null
    private var hadToolGrant = false
    private var grantRequestInFlight = false
    private var lastGrantRequestAt = 0L
    private var grantRequests = 0
    private var grantsRenewed = 0
    private var toolsViaRelay = 0
    private var relayErrorAnswers = 0
    private var relayTimeouts = 0
    private var relayFallbacks = 0
    private var pollInFlight = false
    private var lastPollAt = 0L
    private var polls = 0
    private var textUpdatesSent = 0
    /** Grants whose jobs may still run, asked for job news in turn. */
    private val jobRelays = mutableListOf<WatchToolRelayClient>()
    /** The newest grant with jobs: a renewal asks Hermes to move its jobs to the new grant. */
    private var jobsGrant: WatchToolRelayClient? = null
    private val jobsMovedTo = mutableMapOf<String, WatchToolRelayClient>()
    private val relayRunning = mutableMapOf<String, Int>()
    private val approvals = mutableListOf<WatchJobAnswer.Approval>()
    private var newsInFlight = false
    private var nextNewsAt = 0L
    private var relayJobsStarted = 0
    private val jobLog = WatchVoiceWire.DirectJobLog()
    private var approvalsShown = 0

    // Audio
    private var pending = ShortArray(0)
    private var sendsInFlight = 0
    private var lastPlaybackEndedAt: Long? = null
    private val activity = WatchVoiceActivity()
    private var chunksUp = 0
    private var chunksDropped = 0
    private var sendFailures = 0
    private var microphoneStopped = false

    private val now: Long get() = SystemClock.uptimeMillis()

    val callActive: Boolean get() = phase != Phase.IDLE && phase != Phase.ENDED

    init {
        audio.onCapture = ::captured
        audio.onDrained = ::playbackDrained
        audio.onInterruption = ::audioInterrupted
    }

    // region Controls

    fun start(engine: Engine) {
        if (callActive) return
        reset()
        this.engine = engine
        phase = Phase.PREPARING
        publish()
        CallService.start(context)
        try {
            audio.start(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
        } catch (e: Exception) {
            finish(e.message ?: L10n.tr("The microphone didn't start: %s", e.javaClass.simpleName))
            return
        }
        log.note("directCallStart", mapOf("callID" to callId, "reachable" to link.reachable.value, "engine" to engine.name.lowercase()))
        timers += scope.launch { while (callActive) { delay(FLUSH_INTERVAL_MS); flushUplink() } }
        timers += scope.launch { while (callActive) { delay(1_000); tick() } }
        requestSession()
    }

    fun end() {
        if (callActive) finish(WatchVoiceCall.END_BY_USER)
    }

    /** The orb: stops the model while it speaks, brings the microphone back after another app took it. */
    fun tap() {
        if (phase == Phase.NEEDS_TAP || microphoneStopped) {
            restartAudio()
            return
        }
        if (!modelTurnActive && !audio.isPlaying) return
        // As the phone's Interrupt button: playback stops now, and the rest of this turn is dropped until the model ends or interrupts it.
        audio.stopPlayback()
        suppressingModelTurn = true
        modelTurnActive = false
        lastModelTurnEndedAt = now
        lastPlaybackEndedAt = now
        openAssistantLine = null
        if (phase == Phase.SPEAKING) phase = Phase.LISTENING
        log.note("directInterrupt", mapOf("callID" to callId))
        publish()
    }

    fun toggleMute() {
        if (!callActive) return
        muted = !muted
        pending = ShortArray(0)
        activity.reset()
        // The server ends the user's turn now instead of waiting for audio.
        if (muted) session?.send(LiveVoiceClientMessage.AudioStreamEnd)
        publish()
    }

    fun dismissEnded() {
        if (phase != Phase.ENDED) return
        phase = Phase.IDLE
        _state.value = WatchCall.State()
    }

    // endregion

    // region Session from the phone

    private fun startRequest(): Message =
        if (engine == Engine.GROK) Message.GrokStart(callId, WatchVoiceWire.VERSION) else Message.DirectStart(callId, WatchVoiceWire.VERSION)

    private fun requestSession() {
        val id = callId
        scope.launch {
            while (callId == id && phase == Phase.PREPARING) {
                sessionRequests += 1
                val answer = try {
                    link.request(startRequest(), SESSION_REQUEST_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (callId != id || phase != Phase.PREPARING) return@launch
                    log.note("directStartFailed", mapOf("error" to e.message, "reachable" to link.reachable.value, "attempt" to sessionRequests))
                    if (now - callStartedAt >= SESSION_WAIT_MS) {
                        finish(L10n.tr("Couldn't reach Conduit on your phone."))
                        return@launch
                    }
                    delay(SESSION_RETRY_DELAY_MS)
                    continue
                }
                if (callId != id || phase != Phase.PREPARING) return@launch
                sessionAnswered(answer)
                return@launch
            }
        }
    }

    private fun sessionAnswered(answer: Message?) {
        when {
            answer is Message.DirectSessionIssued && engine == Engine.GEMINI -> begin(answer.session)
            answer is Message.GrokSessionIssued && engine == Engine.GROK -> begin(answer.session)
            answer is Message.CallRefused -> {
                log.note("directRefused", mapOf("reason" to answer.reason))
                finish(answer.reason)
            }
            else -> finish(L10n.tr("Conduit on the phone sent something this Watch app can't read. Update both."))
        }
    }

    private fun begin(direct: WatchVoiceWire.DirectSession) {
        val setup = WatchDirectWire.DirectSetup.fromCompressed(direct.setup)
        val functions = setup?.declarations
        val token = WatchDirectWire.geminiToken(direct.token)
        if (setup == null || functions == null || token == null) {
            finish(L10n.tr("The phone's session couldn't be read. Update Conduit on both devices."))
            return
        }
        openingPrompt = direct.openingPrompt
        endPhrases = direct.endPhrases.orEmpty()
        val tokens = WatchDirectTokens(token)
        tokens.fetch = ::requestToken
        tokens.relayFetch = ::requestRelayToken
        tokens.onIssued = ::tokenIssued
        val session = GeminiLiveSession(
            scope = scope,
            tokens = tokens,
            systemInstruction = setup.systemInstruction,
            functions = functions,
            usesGoogleSearch = direct.googleSearch,
            voice = direct.voice,
            openSocket = { url -> OkHttpLiveSocket(http, LiveSocketRequest(url)) },
        )
        // A reconnect the phone can't serve resumes on the token in hand.
        tokens.canResume = { session.resumptionHandle != null }
        attach(session)
        this.tokens = tokens
        direct.toolGrant?.let { grant ->
            hadToolGrant = true
            toolRelay = WatchToolRelayClient.of(grant, relayHttp)
            if (toolRelay?.hasJobs == true) jobsGrant = toolRelay
        }
        log.note("directSession", mapOf(
            "afterTapMs" to now - callStartedAt, "requests" to sessionRequests, "setupBytes" to direct.setupBytes,
            "compressedBytes" to direct.setup.size, "model" to token.model, "functions" to functions.map { it.name },
            "googleSearch" to direct.googleSearch, "opening" to (direct.openingPrompt != null),
            "toolGrant" to when { direct.toolGrant == null -> "none"; toolRelay == null -> "unreadable"; else -> "ok" },
            "relayTools" to toolRelay?.tools?.sorted(),
        ))
        phase = Phase.CONNECTING
        publish()
        session.start()
    }

    /** A Grok call: the same setup and tools as Gemini's, xAI's session on the Hermes host through the grant's audio bridge. */
    private fun begin(grok: WatchVoiceWire.GrokSession) {
        val setup = WatchDirectWire.DirectSetup.fromCompressed(grok.setup)
        val functions = setup?.declarations
        val bridge = grok.grant.audio
        val root = WatchToolSeal.data(grok.grant.key)?.takeIf { it.size == 32 }
        val relay = WatchToolRelayClient.of(grok.grant, relayHttp)
        if (setup == null || functions == null || bridge == null || !bridge.url.startsWith("wss://") || root == null || relay == null) {
            finish(L10n.tr("The phone's session couldn't be read. Update Conduit on both devices."))
            return
        }
        if (bridge.version != WatchAudioBridgeWire.VERSION) {
            finish(L10n.tr("Update the Hermes notifier plugin and Conduit to matching versions."))
            return
        }
        openingPrompt = grok.openingPrompt
        endPhrases = grok.endPhrases.orEmpty()
        val grantId = grok.grant.grantId
        val session = GrokLiveSession(
            scope = scope,
            // The host already said Grok is available when the grant opened its bridge.
            client = object : GrokLiveConnecting {
                override suspend fun availability(): GrokLiveAvailability = GrokLiveAvailability.Available(GrokLiveProtocol.DEFAULT_MODEL, null, null)
                override suspend fun socketRequest() = LiveSocketRequest(bridge.url)
            },
            instructions = setup.systemInstruction,
            functions = functions,
            voice = grok.voice,
            openSocket = {
                // A stream id is taken once per grant, and a grant takes 32.
                val stream = if (grokStreams < MAX_GROK_STREAMS) {
                    grokStreams += 1
                    WatchAudioBridgeStream.of(grantId, root)
                } else null
                val number = grokStreams
                WatchGrokBridgeSocket(bridge.url, grantId, grok.grant.watchKey, grok.voice, stream, http) { kind, fields ->
                    log.note("directSocket", fields + mapOf("api" to "grokBridge", "socket" to number, "kind" to kind))
                }
            },
        )
        attach(session)
        // The bridge belongs to the grant, so the call keeps it: no renewal.
        hadToolGrant = true
        toolRelay = relay
        if (relay.hasJobs) jobsGrant = relay
        log.note("directSession", mapOf(
            "engine" to "grok", "afterTapMs" to now - callStartedAt, "requests" to sessionRequests, "setupBytes" to grok.setupBytes,
            "functions" to functions.map { it.name }, "opening" to (grok.openingPrompt != null), "engines" to bridge.engines,
            "relayTools" to relay.tools.sorted(),
        ))
        phase = Phase.CONNECTING
        publish()
        session.start()
    }

    private fun attach(session: LiveSessionControlling) {
        session.onEvent = ::handle
        session.onStateChange = ::sessionStateChanged
        session.onConnectionReplaced = ::connectionReplaced
        this.session = session
    }

    /** A new single-use token for the next connection, from the phone. */
    private suspend fun requestToken(): GeminiLiveToken {
        val id = callId
        val answer = link.request(Message.DirectTokenRequest(id), TOKEN_WAIT_MS)
        return when (answer) {
            is Message.DirectTokenIssued -> WatchDirectWire.geminiToken(answer.token) ?: throw IOException(L10n.tr("Conduit on the phone sent something this Watch app can't read."))
            is Message.CallRefused -> throw IOException(answer.reason)
            else -> throw IOException(L10n.tr("Conduit on the phone sent something this Watch app can't read."))
        }
    }

    /** A new single-use token from Hermes through the grant's relay. The log has how it went, never the token. */
    private suspend fun requestRelayToken(): GeminiLiveToken {
        val relay = toolRelay?.takeIf { it.canRun(WatchLiveToken.TOOL) } ?: throw IOException(L10n.tr("This call can't get a Gemini token through the relay."))
        val id = callId
        val sentAt = now
        val outcome = relay.run(WatchLiveToken.TOOL, JsonObject(emptyMap()))
        if (callId != id || !callActive) throw IOException(L10n.tr("The call ended."))
        val fields = mutableMapOf<String, Any?>("ms" to now - sentAt, "outcome" to outcome.logLabel, "grantCalls" to relay.callsSent)
        try {
            return when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> WatchLiveToken.token(outcome.body) ?: run {
                    val detail = (outcome.body["detail"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
                    throw IOException(L10n.tr("No Gemini token through the relay: %s", detail ?: L10n.tr("Hermes sent no usable Gemini token.")))
                }
                WatchToolRelayClient.Outcome.TimedOut -> throw IOException(L10n.tr("No Gemini token through the relay: %s", L10n.tr("Hermes didn't answer in time.")))
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    fields["reason"] = outcome.reason
                    if (outcome.grantGone && toolRelay === relay) {
                        toolRelay = null
                        closeIfUnused(relay)
                    }
                    throw IOException(L10n.tr("No Gemini token through the relay: %s", L10n.tr("The relay couldn't reach Hermes (%s).", outcome.reason)))
                }
            }
        } finally {
            log.note("directRelayToken", fields)
        }
    }

    private fun tokenIssued(source: WatchDirectTokens.Source, error: String?) {
        when (source) {
            WatchDirectTokens.Source.PHONE -> tokensFromPhone++
            WatchDirectTokens.Source.REUSED -> tokensReused++
            WatchDirectTokens.Source.RELAY -> tokensFromRelay++
            WatchDirectTokens.Source.FAILED -> tokenFailures++
            WatchDirectTokens.Source.FIRST -> Unit
        }
        log.note("directToken", mapOf("source" to source.raw, "reachable" to link.reachable.value, "error" to error))
    }

    // endregion

    // region Connection

    private fun sessionStateChanged(state: LiveSessionState) {
        if (!callActive) return
        when (state) {
            LiveSessionState.Ready -> {
                val at = now
                if (firstReadyAt == null) {
                    firstReadyAt = at
                    liveSinceWallMs = System.currentTimeMillis()
                    log.note("directReady", mapOf("sinceTapMs" to at - callStartedAt))
                }
                reconnectStartedAt?.let {
                    reconnectStartedAt = null
                    log.note("directReconnected", mapOf("ms" to at - it))
                }
                // A later reconnect may ask the relay once again.
                tokens?.allowRelayTry()
                if (phase == Phase.CONNECTING || phase == Phase.RECONNECTING) phase = restingPhase
                rejoinStartedAt?.let { since ->
                    rejoinStartedAt = null
                    phonelessRejoinFailed = false
                    log.note("directRejoined", mapOf(
                        "ms" to at - since, "lines" to transcript.size, "withoutPhone" to rejoinWithoutPhone,
                        // What the user said meanwhile, never sent: the fresh session is told it wasn't heard.
                        "discardedAudioMs" to (samplesDroppedWhileDown + pending.size) * 1000L / WatchAudio.CAPTURE_RATE,
                    ))
                    sendRejoinContext()
                }
                samplesDroppedWhileDown = 0
                sendOpeningIfNeeded()
                flushQuietQueue()
            }
            LiveSessionState.Reconnecting -> {
                if (phase == Phase.LISTENING || phase == Phase.SPEAKING || phase == Phase.CONNECTING) phase = Phase.RECONNECTING
                if (reconnectStartedAt == null) {
                    reconnectStartedAt = now
                    if (firstReadyAt != null) drops++
                }
                // The answer the drop cut off stops playing and its turn is over, as on the phone.
                val cutAnswer = modelTurnActive || audio.isPlaying
                if (audio.isPlaying) {
                    audio.stopPlayback()
                    lastPlaybackEndedAt = now
                }
                suppressingModelTurn = false
                if (modelTurnActive) modelTurnEnded()
                stallPromptedAt = null
                log.note("directReconnecting", mapOf("cutAnswer" to cutAnswer, "reachable" to link.reachable.value))
            }
            is LiveSessionState.Failed -> {
                log.note("directFailed", mapOf("message" to state.message, "rejoins" to rejoins))
                // Once the call has been live, a fresh session carries it on; not for Grok once its grant is over, which its bridge was.
                val grantOver = engine == Engine.GROK && (toolRelay?.let { it.isGone || it.expiresWithin(GRANT_END_MARGIN_MS) } ?: true)
                if (firstReadyAt != null && endRequestedAt == null && rejoins < MAX_REJOINS && !grantOver) waitToRejoin(state.message)
                else finish(state.message)
            }
            else -> Unit
        }
        publish()
    }

    /** The connection a tool call is answered on. */
    private val liveGeneration: Int? get() = session?.let { it.connectionGeneration + generationOffset }

    /**
     * The session broke past resuming. A fresh one needs a new token: from
     * the phone if it can be reached, or from Hermes through the grant.
     * Otherwise, or once a phoneless rejoin has failed, it waits for the
     * phone, and a chime says so.
     */
    private fun waitToRejoin(reason: String, cause: WatchRejoin.Cause = WatchRejoin.Cause.BROKE) {
        if (rejoinStartedAt != null && rejoinWithoutPhone) phonelessRejoinFailed = true
        rejoinStartedAt = null
        if (audio.isPlaying) {
            audio.stopPlayback()
            lastPlaybackEndedAt = now
        }
        suppressingModelTurn = false
        if (modelTurnActive) modelTurnEnded()
        stallPromptedAt = null
        replyOwedSince = null
        awaitingReplySince = null
        silentTurnSinceUser = false
        reconnectStartedAt = null
        rejoinWaitingSince = now
        rejoinReason = reason
        rejoinCause = cause
        samplesDroppedWhileDown += pending.size
        pending = ShortArray(0)
        phase = Phase.LOST
        val relayToken = !phonelessRejoinFailed && canMintThroughRelay
        log.note("directRejoinWaiting", mapOf("reason" to reason, "rejoins" to rejoins, "reachable" to link.reachable.value, "relayToken" to relayToken))
        // Grok's bridge goes through the relay: it never needs the phone.
        if (link.reachable.value || relayToken || engine == Engine.GROK) {
            rejoin()
        } else {
            // The chime tells the user the call went, so the new session says it's back.
            rejoinCause = WatchRejoin.Cause.BROKE
            audio.enqueue(WatchAudio.lostChime(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE), GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
        }
        publish()
    }

    private val canMintThroughRelay: Boolean get() = toolRelay?.canRun(WatchLiveToken.TOOL) == true

    /** From the tick: a rejoin that hasn't come back gives up, as a failed one does. */
    private fun rejoinHungIfDue() {
        val since = rejoinStartedAt ?: return
        val session = session ?: return
        if (now - since < REJOIN_HANG_LIMIT_MS) return
        rejoinsHung++
        log.note("directRejoinHung", mapOf("ms" to now - since, "rejoin" to rejoins, "waitingOnRelay" to (tokens?.relayInFlight == true)))
        session.stop()
        val reason = if (engine == Engine.GROK) L10n.tr("Grok didn't come back after the connection broke.") else L10n.tr("Gemini didn't come back after the connection broke.")
        if (endRequestedAt == null && rejoins < MAX_REJOINS) waitToRejoin(reason) else finish(reason)
    }

    /** From the tick: the phone came back, or the wait ran out. */
    private fun rejoinIfDue() {
        val since = rejoinWaitingSince ?: return
        if (endRequestedAt != null) {
            rejoinWaitingSince = null
            finish(WatchVoiceCall.GOODBYE)
            return
        }
        if (link.reachable.value || engine == Engine.GROK) rejoin()
        else if (now - since >= REJOIN_WAIT_MS) finish(rejoinReason)
    }

    private fun rejoin() {
        val session = session ?: run {
            rejoinWaitingSince = null
            finish(rejoinReason)
            return
        }
        rejoinWaitingSince = null
        // Each rejoin may ask Hermes for one token through the relay.
        tokens?.allowRelayTry()
        rejoins++
        rejoinStartedAt = now
        rejoinWithoutPhone = !link.reachable.value
        // Calls made on the broken session can't be answered on the fresh one: their answers go out as text updates instead.
        generationOffset++
        phase = Phase.RECONNECTING
        log.note("directRejoin", mapOf("rejoin" to rejoins, "withoutPhone" to rejoinWithoutPhone))
        // Starts over without the resumption handle, on a token from the phone or the relay.
        session.start()
        publish()
    }

    /** A fresh session knows nothing of the call: it's told the conversation so far, and says it's back (or, after a freeze, just answers). */
    private fun sendRejoinContext() {
        val session = session ?: return
        pending = ShortArray(0)
        val text = WatchRejoin.prompt(transcript, rejoinCause)
        awaitingAnswerSince = now
        textUpdatesSent++
        val id = callId
        session.send(LiveVoiceClientMessage.TextTurn(text), onFailure = {
            if (callId == id) quietQueue.add(0, DirectOutgoing.TextWhenIdle(text))
        })
    }

    /** A new connection took over: calls opened on the old one can't be answered on it. xAI has no resumption: told the conversation once ready. */
    private fun connectionReplaced() {
        log.note("directConnectionReplaced", mapOf("openCalls" to toolsInFlight.size))
        if (engine == Engine.GROK && rejoinStartedAt == null) {
            rejoinStartedAt = now
            rejoinWithoutPhone = !link.reachable.value
            rejoinCause = WatchRejoin.Cause.BROKE
        }
    }

    private val restingPhase: Phase get() = if (endRequestedAt != null) Phase.ENDING else if (audio.isPlaying) Phase.SPEAKING else Phase.LISTENING

    private fun sendOpeningIfNeeded() {
        val prompt = openingPrompt ?: return
        val session = session ?: return
        if (hasSentOpening || !session.isReady) return
        hasSentOpening = true
        awaitingAnswerSince = now
        val id = callId
        session.send(LiveVoiceClientMessage.TextTurn(prompt), onFailure = { if (callId == id) hasSentOpening = false })
    }

    // endregion

    // region Server events

    private fun handle(event: GeminiLiveProtocol.ServerEvent) {
        if (!callActive) return
        when (event) {
            is GeminiLiveProtocol.ServerEvent.Audio -> {
                if (suppressingModelTurn) return
                lastModelAudioAt = now
                modelActed()
                modelTurnActive = true
                awaitingReplySince = null
                awaitingAnswerSince = null
                toolCallsSpokenFor += toolsInFlight
                audio.enqueue(WatchAudioBridgeWire.samples(event.pcm), event.sampleRate)
                if (phase == Phase.LISTENING) phase = Phase.SPEAKING
            }
            is GeminiLiveProtocol.ServerEvent.InputTranscription -> {
                lastUserSpeechAt = now
                if (openUserLine == null && awaitingReplySince == null) awaitingReplySince = now
                // Words still coming in while the model answers are that turn's.
                if (!modelTurnActive && replyOwedSince == null) {
                    replyOwedSince = now
                    silentTurnSinceUser = false
                }
                appendTranscript(WatchVoiceWire.DirectTurn.Role.USER, event.text)
            }
            is GeminiLiveProtocol.ServerEvent.OutputTranscription -> {
                if (suppressingModelTurn) return
                modelActed()
                modelTurnActive = true
                awaitingReplySince = null
                awaitingAnswerSince = null
                appendTranscript(WatchVoiceWire.DirectTurn.Role.ASSISTANT, event.text)
                openAssistantLine?.let { caption = transcript[it].text.takeLast(160) }
            }
            GeminiLiveProtocol.ServerEvent.Interrupted -> {
                audio.stopPlayback()
                lastPlaybackEndedAt = now
                suppressingModelTurn = false
                modelTurnEnded()
                turnEndedByInterruption = true
            }
            GeminiLiveProtocol.ServerEvent.TurnComplete -> {
                // A turn the orb stopped isn't a silent one.
                val wasSuppressed = suppressingModelTurn
                suppressingModelTurn = false
                if (!turnStarted && !turnEndedByInterruption && !wasSuppressed) silentTurnEnded()
                turnEndedByInterruption = false
                modelTurnEnded()
                endIfUserSaidGoodbye(finished = true)
            }
            is GeminiLiveProtocol.ServerEvent.ToolCall -> {
                modelActed()
                event.calls.forEach(::toolCalled)
            }
            is GeminiLiveProtocol.ServerEvent.ToolCallCancellation -> {
                // Answers still coming for these are dropped.
                withdrawnToolIds += toolsInFlight.intersect(event.ids.toSet())
                val id = callId
                scope.launch { runCatching { link.request(Message.DirectToolCancel(id, event.ids), 5_000) } }
            }
            is GeminiLiveProtocol.ServerEvent.GoAway -> {
                goAways++
                log.note("directGoAway", mapOf("timeLeftS" to event.timeLeftSeconds))
            }
            else -> Unit
        }
        publish()
    }

    private fun modelTurnEnded() {
        if (modelTurnActive || openAssistantLine != null) turns++
        turnStarted = false
        modelTurnActive = false
        lastModelTurnEndedAt = now
        openUserLine = null
        openAssistantLine = null
        activity.reset()
        flushQuietQueue()
    }

    /** The model spoke or called a tool: whatever it owed, it's working. */
    private fun modelActed() {
        turnEndedByInterruption = false
        turnStarted = true
        replyOwedSince = null
        silentTurnSinceUser = false
        if (stallPromptedAt != null) {
            stallPromptedAt = null
            log.note("directStallPromptAnswered", mapOf("silent" to false))
        }
    }

    /** A turn ended with nothing from the model: it chose silence, and the session works. */
    private fun silentTurnEnded() {
        if (replyOwedSince != null) silentTurnSinceUser = true
        replyOwedSince = null
        if (stallPromptedAt != null) {
            stallPromptedAt = null
            log.note("directStallPromptAnswered", mapOf("silent" to true))
        }
    }

    private fun appendTranscript(role: WatchVoiceWire.DirectTurn.Role, text: String) {
        val open = if (role == WatchVoiceWire.DirectTurn.Role.USER) openUserLine else openAssistantLine
        if (open != null && open in transcript.indices) {
            val line = transcript[open]
            transcript[open] = line.copy(text = if (role == WatchVoiceWire.DirectTurn.Role.ASSISTANT) joinTranscriptChunk(line.text, text) else line.text + text)
            return
        }
        transcript += WatchVoiceWire.DirectTurn(role, text, System.currentTimeMillis())
        if (role == WatchVoiceWire.DirectTurn.Role.USER) {
            openUserLine = transcript.size - 1
            exchangeUserLine = openUserLine
            openAssistantLine = null
        } else {
            openAssistantLine = transcript.size - 1
            openUserLine = null
        }
    }

    // endregion

    // region Tools

    private fun toolCalled(call: GeminiLiveProtocol.FunctionCall) {
        toolCalls++
        // The phone's bridge leaves end_conversation unanswered and the call closes once the goodbye has played.
        if (call.name == "end_conversation") {
            log.note("directTool", mapOf("name" to call.name, "local" to true))
            requestEnd()
            return
        }
        toolCalledAt.keys.retainAll(toolsInFlight)
        toolCallsSpokenFor.retainAll(toolsInFlight)
        toolCalledAt[call.id] = now
        if (modelTurnActive) toolCallsSpokenFor += call.id
        val generation = liveGeneration
        val wire = WatchVoiceWire.DirectToolCall(call.id, call.name, call.arguments)
        val whenIdle = GeminiLiveProtocol.Scheduling.WHEN_IDLE.raw
        if (call.name == WatchJobAnswer.JOB_NEWS) {
            // The watch app's own call, never the model's.
            answer(DirectOutgoing.ToolResponse(call.id, call.name, mapOf("error" to "unknown function"), whenIdle, null), generation)
            return
        }
        if (call.name == WatchJobAnswer.ANSWER_APPROVAL) {
            answerApprovalByVoice(call, generation)
            return
        }
        // Jobs go to Hermes through the relay: their news and approvals come that way too.
        if (call.name in WatchJobAnswer.tools || call.name == WatchJobAnswer.INTERRUPT_JOB) {
            jobRelay(call)?.let { relay ->
                runJobThroughRelay(wire, relay, generation)
                return
            }
        }
        if (call.name == WatchJobAnswer.INTERRUPT_JOB) {
            val relay = toolRelay?.takeIf { it.hasJobs } ?: jobRelays.lastOrNull { it.hasJobs }
            if (relay != null) {
                // The jobs run on the host, which can't take the words just now: the phone doesn't know them.
                val reason = when {
                    WatchJobAnswer.INTERRUPT_JOB !in relay.tools -> WatchJobAnswer.FOLLOW_UPS_NEED_NEWER_PLUGIN
                    relay.isGone -> WatchBridgeDelegation.GRANT_RAN_OUT
                    else -> "this call's access to Hermes is renewing or has run out. Try again in a moment."
                }
                answer(DirectOutgoing.ToolResponse(call.id, call.name, WatchJobAnswer.followUpResult(WatchJobAnswer.FollowUp.Failed(reason)), whenIdle, null), generation)
                return
            }
        }
        // Chats go through the grant when it has them: the host keeps the numbered matches read_chat names.
        val relay = toolRelay
        if (relay != null && relay.canRun(call.name) && call.name in WatchToolAnswer.tools &&
            (!link.reachable.value || call.name in WatchToolAnswer.chatTools)
        ) {
            runThroughRelay(wire, relay, generation, phoneTried = false)
            return
        }
        sendToPhone(wire, generation, relayTried = false)
    }

    /** The call through the phone's bridge; if the phone doesn't answer, through the relay when it hasn't been tried yet. */
    private fun sendToPhone(wire: WatchVoiceWire.DirectToolCall, generation: Int?, relayTried: Boolean) {
        val id = callId
        val sentAt = now
        toolsInFlight += wire.id
        scope.launch {
            val result = runCatching { link.request(Message.DirectTool(id, wire), TOOL_REQUEST_TIMEOUT_MS) }
            if (callId != id || !callActive) return@launch
            toolsInFlight -= wire.id
            val withdrawn = withdrawnToolIds.remove(wire.id)
            val answer = result.getOrNull() as? Message.DirectToolResultIssued
            if (answer == null) {
                phoneFailed(wire, generation, result.exceptionOrNull()?.message ?: "unreadable answer", withdrawn, relayTried)
                return@launch
            }
            toolsAnsweredLive++
            log.note("directTool", mapOf("name" to wire.name, "live" to true, "ms" to now - sentAt, "outgoing" to answer.result.outgoing.size, "runningJobs" to answer.result.runningJobs, "withdrawn" to withdrawn))
            apply(answer.result, wire.id, generation, withdrawn)
        }
    }

    private fun phoneFailed(wire: WatchVoiceWire.DirectToolCall, generation: Int?, error: String, withdrawn: Boolean, relayTried: Boolean) {
        val relay = toolRelay
        if (!withdrawn && !relayTried && wire.name in WatchToolAnswer.tools && relay != null && relay.canRun(wire.name)) {
            log.note("directTool", mapOf("name" to wire.name, "live" to false, "error" to error, "next" to "relay"))
            runThroughRelay(wire, relay, generation, phoneTried = true)
            return
        }
        toolUnreachable(wire, generation, error, withdrawn)
    }

    /**
     * A lookup through the push relay, answered as the phone's bridge would
     * answer it. Whatever doesn't get Hermes' answer this way goes the phone's
     * way, unless that was tried first. The log carries the tool, the time
     * and how it went: never the query or the answer.
     */
    private fun runThroughRelay(wire: WatchVoiceWire.DirectToolCall, relay: WatchToolRelayClient, generation: Int?, phoneTried: Boolean) {
        val query = WatchToolAnswer.query(wire.name, wire.arguments)
        if (query == null) {
            answer(WatchToolAnswer.outgoing(wire.id, wire.name, WatchToolAnswer.missingQuery(wire.name)), generation)
            return
        }
        val id = callId
        val sentAt = now
        toolsInFlight += wire.id
        scope.launch {
            val outcome = relay.run(wire.name, query)
            if (callId != id || !callActive) return@launch
            toolsInFlight -= wire.id
            val withdrawn = withdrawnToolIds.remove(wire.id)
            val fields = mutableMapOf<String, Any?>("name" to wire.name, "ms" to now - sentAt, "withdrawn" to withdrawn, "reachable" to link.reachable.value, "grantCalls" to relay.callsSent, "outcome" to outcome.logLabel)
            when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> {
                    val result = WatchToolAnswer.result(wire.name, outcome.body)
                    if (result["error"] != null) relayErrorAnswers++ else toolsViaRelay++
                    log.note("directToolRelay", fields)
                    if (!withdrawn) answer(WatchToolAnswer.outgoing(wire.id, wire.name, result), generation)
                }
                WatchToolRelayClient.Outcome.TimedOut -> {
                    // Hermes has the call: answered as the phone's broker answers one that outlasts its wait.
                    relayTimeouts++
                    log.note("directToolRelay", fields)
                    if (!withdrawn) answer(WatchToolAnswer.outgoing(wire.id, wire.name, WatchToolAnswer.tookTooLong), generation)
                }
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    relayFallbacks++
                    if (outcome.grantGone && toolRelay === relay) {
                        toolRelay = null
                        closeIfUnused(relay)
                    }
                    fields["reason"] = outcome.reason
                    fields["grantGone"] = outcome.grantGone
                    log.note("directToolRelay", fields)
                    if (withdrawn) return@launch
                    if (phoneTried) toolUnreachable(wire, generation, "relay: ${outcome.reason}", false)
                    else sendToPhone(wire, generation, relayTried = true)
                }
            }
        }
    }

    /**
     * The phone didn't take the call: a job is queued for it (the phone
     * starts it once it takes it), anything else is answered with why it
     * can't be done. Function results are written for the model, not shown.
     */
    private fun toolUnreachable(call: WatchVoiceWire.DirectToolCall, generation: Int?, error: String, withdrawn: Boolean) {
        toolsUnreachable++
        var queued = false
        val result = if (call.name == WatchJobAnswer.START_JOB && !withdrawn) {
            queued = link.queue(Message.DirectTool(callId, call))
            if (queued) {
                jobsQueued++
                runningJobs = maxOf(runningJobs, 1)
                mapOf("status" to "queued", "message" to "Conduit on the user's phone can't be reached right now. The job is queued and starts on Hermes once the phone takes it; its result comes as a Conduit notification. Tell the user in a sentence.")
            } else {
                mapOf("status" to "not_started", "message" to "Conduit on the user's phone can't be reached right now, so the job didn't start. Tell the user in a sentence.")
            }
        } else {
            mapOf("error" to "Conduit on the user's phone can't be reached right now, so this isn't available. Tell the user in a few words.")
        }
        log.note("directTool", mapOf("name" to call.name, "live" to false, "queued" to queued, "error" to error, "withdrawn" to withdrawn, "reachable" to link.reachable.value))
        if (withdrawn) return
        val scheduling = if (call.name in WHEN_IDLE_TOOLS) GeminiLiveProtocol.Scheduling.WHEN_IDLE.raw else null
        answer(DirectOutgoing.ToolResponse(call.id, call.name, result, scheduling, null), generation)
    }

    // endregion

    // region Jobs through the relay

    /** The grant to run a job tool through: the call's own, when it carries jobs. A job on another profile starts from the phone unless the host runs it. */
    private fun jobRelay(call: GeminiLiveProtocol.FunctionCall): WatchToolRelayClient? {
        // A correction can still reach its job through another grant this call follows while the current one renews.
        val candidate = if (call.name == WatchJobAnswer.INTERRUPT_JOB && toolRelay?.let { it.hasJobs && it.canRun(call.name) } != true)
            jobRelays.lastOrNull { it.hasJobs && it.canRun(call.name) } else toolRelay
        val relay = candidate?.takeIf { it.hasJobs && it.canRun(call.name) } ?: return null
        if (call.name == WatchJobAnswer.START_JOB) {
            val route = relay.jobRoute(WatchBridgeDelegation.removingQuickMarker(call.arguments["instructions"].orEmpty()), call.arguments["profile"])
            if (route != null) return if (route is WatchJobProfiles.RelayRoute.Relay) relay else null
        }
        return if (call.arguments["profile"]?.trim().isNullOrEmpty()) relay else null
    }

    /**
     * A job call through the push relay, answered as the phone's bridge
     * answers it. A start_job that may have reached Hermes never goes the
     * phone's way as well: that could start it twice.
     */
    private fun runJobThroughRelay(wire: WatchVoiceWire.DirectToolCall, relay: WatchToolRelayClient, generation: Int?) {
        val isStart = wire.name == WatchJobAnswer.START_JOB
        val isFollowUp = wire.name == WatchJobAnswer.INTERRUPT_JOB
        val whenIdle = GeminiLiveProtocol.Scheduling.WHEN_IDLE.raw
        var arguments = WatchJobAnswer.arguments(wire.name, wire.arguments) ?: run {
            answer(DirectOutgoing.ToolResponse(wire.id, wire.name, WatchJobAnswer.missingArguments(wire.name, wire.arguments), whenIdle, null), generation)
            return
        }
        val task = (arguments["instructions"] as? JsonPrimitive)?.content
        if (isStart && task != null) {
            when (val route = relay.jobRoute(task, wire.arguments["profile"])) {
                // "for Fam, …" runs on Fam, as the phone's jobs do.
                is WatchJobProfiles.RelayRoute.Relay -> arguments = buildJsonObject {
                    put("instructions", route.instructions)
                    route.profile?.let { put("profile", it) }
                }
                is WatchJobProfiles.RelayRoute.ViaPhone, is WatchJobProfiles.RelayRoute.Unknown -> {
                    val message = if (route is WatchJobProfiles.RelayRoute.Unknown) WatchJobProfiles.unknownProfileReply(route.name)
                    else WatchJobProfiles.viaPhoneReply((route as WatchJobProfiles.RelayRoute.ViaPhone).label)
                    answer(DirectOutgoing.ToolResponse(wire.id, wire.name, mapOf("status" to "not_started", "message" to message), whenIdle, null), generation)
                    return
                }
                null -> Unit
            }
        }
        if (isStart && relayJobsStarted >= relay.maxJobs) {
            log.note("directJobRelay", mapOf("name" to wire.name, "outcome" to "capped", "maxJobs" to relay.maxJobs))
            answer(DirectOutgoing.ToolResponse(wire.id, wire.name, mapOf(
                "status" to "not_started",
                "message" to "This call has started ${relay.maxJobs} jobs, the most the user allows per call. Tell them; they can raise it in Conduit's Watch settings or start more from their phone.",
            ), whenIdle, null), generation)
            return
        }
        val id = callId
        val sentAt = now
        toolsInFlight += wire.id
        scope.launch {
            val outcome = relay.run(wire.name, arguments)
            if (callId != id || !callActive) return@launch
            toolsInFlight -= wire.id
            val withdrawn = withdrawnToolIds.remove(wire.id)
            val fields = mutableMapOf<String, Any?>("name" to wire.name, "ms" to now - sentAt, "withdrawn" to withdrawn, "grantCalls" to relay.callsSent, "outcome" to outcome.logLabel)
            val result: Map<String, String> = when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> {
                    val r = if (isFollowUp) WatchJobAnswer.followUpResult(WatchJobAnswer.FollowUp.of(outcome.body)) else WatchJobAnswer.result(outcome.body)
                    fields["status"] = r["status"] ?: if (r["error"] == null) "" else "error"
                    // "accepted": Hermes is still taking it, and its outcome comes as news.
                    if (isStart && (r["status"] == "started" || r["status"] == "accepted")) {
                        relayJobsStarted++
                        r["job_id"]?.let { jobLog.started(it, r["title"].orEmpty()) }
                        followJobs(jobsHolder(relay))
                    } else if (wire.name == WatchJobAnswer.CANCEL_JOB) {
                        nextNewsAt = now
                    }
                    r
                }
                WatchToolRelayClient.Outcome.TimedOut -> {
                    relayTimeouts++
                    when {
                        isStart -> {
                            relayJobsStarted++
                            followJobs(jobsHolder(relay))
                            WatchJobAnswer.accepted
                        }
                        isFollowUp -> WatchJobAnswer.followUpResult(WatchJobAnswer.FollowUp.Failed("Hermes took too long to answer"))
                        else -> WatchToolAnswer.tookTooLong
                    }
                }
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    relayFallbacks++
                    if (outcome.grantGone && toolRelay === relay) {
                        toolRelay = null
                        closeIfUnused(relay)
                    }
                    fields["reason"] = outcome.reason
                    log.note("directJobRelay", fields)
                    if (isStart && outcome.sent) {
                        // It may have reached Hermes.
                        relayJobsStarted++
                        if (!outcome.grantGone || mayBeCarried(relay)) followJobs(jobsHolder(relay))
                        if (!withdrawn) answer(DirectOutgoing.ToolResponse(wire.id, wire.name, mapOf(
                            "status" to "unknown",
                            "message" to "Hermes didn't confirm the job. Tell the user it may or may not have started; they can ask you to list their jobs.",
                        ), whenIdle, null), generation)
                        return@launch
                    }
                    if (withdrawn) return@launch
                    if (isFollowUp) {
                        // The job runs on the host: the phone doesn't know it.
                        val message = if (WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, outcome.sent)) WatchBridgeDelegation.GRANT_RAN_OUT
                        else if (outcome.sent) "Hermes didn't confirm it got the words." else "Hermes couldn't be reached from the Watch."
                        answer(DirectOutgoing.ToolResponse(wire.id, wire.name, WatchJobAnswer.followUpResult(WatchJobAnswer.FollowUp.Failed(message)), whenIdle, null), generation)
                        return@launch
                    }
                    // Not sent, or a list or cancel: the phone's way, as without jobs in the grant.
                    sendToPhone(wire, generation, relayTried = true)
                    return@launch
                }
            }
            log.note("directJobRelay", fields)
            if (withdrawn) return@launch
            answer(DirectOutgoing.ToolResponse(wire.id, wire.name, result, WatchJobAnswer.scheduling(wire.name, result), WatchJobAnswer.fallbackText(wire.name, result)), generation)
        }
    }

    private fun followJobs(relay: WatchToolRelayClient) {
        if (jobRelays.none { it === relay }) jobRelays += relay
        if (relay.hasJobs && relay !== jobsGrant && jobRelays.none { it === jobsGrant }) jobsGrant = relay
        nextNewsAt = now
    }

    private fun stopFollowing(relay: WatchToolRelayClient) {
        jobRelays.removeAll { it === relay }
        if (relay === jobsGrant && relay !== toolRelay) jobsGrant = toolRelay?.takeIf { it.hasJobs }
        relayRunning.remove(relay.grantId)
        relayJobsRunning = relayRunning.values.sum()
        approvals.removeAll { it.grantId == relay.grantId }
        closeIfUnused(relay)
        publish()
    }

    /** Ends a grant on the relay once nothing needs it. Closing leaves its jobs running in Hermes, but Hermes then stops following them for the watch. */
    private fun closeIfUnused(relay: WatchToolRelayClient) {
        if (relay === toolRelay || relay === jobsGrant || jobRelays.any { it === relay }) return
        relay.close()
    }

    private fun jobsHolder(relay: WatchToolRelayClient): WatchToolRelayClient {
        var holder = relay
        var hops = 0
        while (hops < MAX_GRANT_REQUESTS) {
            holder = jobsMovedTo[holder.grantId] ?: break
            hops++
        }
        return holder
    }

    private fun mayBeCarried(relay: WatchToolRelayClient) =
        relay === jobsGrant && endRequestedAt == null && grantRequests < MAX_GRANT_REQUESTS && !relay.expiresWithin(WatchToolRelayClient.EXPIRY_MARGIN_MS)

    private fun moveJobs(old: WatchToolRelayClient, new: WatchToolRelayClient) {
        jobsMovedTo[old.grantId] = new
        val index = jobRelays.indexOfFirst { it === old }
        if (index >= 0) {
            jobRelays[index] = new
            nextNewsAt = now
        } else if (relayJobsStarted > 0) {
            followJobs(new)
        }
        relayRunning.remove(old.grantId)?.let { relayRunning[new.grantId] = it }
        for (i in approvals.indices) if (approvals[i].grantId == old.grantId) approvals[i] = approvals[i].copy(grantId = new.grantId)
    }

    /** While jobs started through the relay may run, asks Hermes for their news: one ask at a time, each grant in turn. */
    private fun fetchJobNewsIfDue() {
        if (newsInFlight || endRequestedAt != null || now < nextNewsAt || jobRelays.isEmpty()) return
        for (relay in jobRelays.toList()) {
            if ((relay.isGone || relay.expiresWithin(WatchToolRelayClient.EXPIRY_MARGIN_MS)) && !mayBeCarried(relay)) {
                log.note("directJobNewsStopped", mapOf("gone" to relay.isGone, "running" to relayRunning[relay.grantId]))
                stopFollowing(relay)
            }
        }
        val relay = jobRelays.firstOrNull { !it.isGone } ?: return
        newsInFlight = true
        val id = callId
        val sentAt = now
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.JOB_NEWS, buildJsonObject { put("wait_s", JOB_NEWS_WAIT_S) })
            if (callId != id || !callActive) return@launch
            newsInFlight = false
            // The next ask goes to the next grant.
            val index = jobRelays.indexOfFirst { it === relay }
            if (index >= 0) jobRelays += jobRelays.removeAt(index)
            when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> {
                    val holder = jobsHolder(relay)
                    val news = WatchJobAnswer.news(outcome.body, holder.grantId)
                    if (news == null) {
                        log.note("directJobNewsFailed", mapOf("reason" to "unreadable", "ms" to now - sentAt))
                        nextNewsAt = now + JOB_NEWS_PAUSE_MS
                        return@launch
                    }
                    jobNews(news, holder)
                    nextNewsAt = now + if (news.items.isEmpty() && !news.more) JOB_NEWS_PAUSE_MS else JOB_NEWS_RETRY_MS
                }
                WatchToolRelayClient.Outcome.TimedOut -> nextNewsAt = now + JOB_NEWS_RETRY_MS
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    log.note("directJobNewsFailed", mapOf("reason" to outcome.reason, "grantGone" to outcome.grantGone))
                    if (outcome.grantGone && !mayBeCarried(relay)) stopFollowing(relay)
                    nextNewsAt = now + JOB_NEWS_PAUSE_MS
                }
            }
        }
    }

    /** A grant's job news: results and failures for the model at the next quiet moment, approval requests on screen with a buzz. */
    private fun jobNews(news: WatchJobAnswer.News, relay: WatchToolRelayClient) {
        relayRunning[relay.grantId] = news.running
        relayJobsRunning = relayRunning.values.sum()
        val open = news.openApprovals.map { "${it.jobId}\n${it.requestId}" }.toSet()
        approvals.removeAll { it.grantId == relay.grantId && "${it.jobId}\n${it.requestId}" !in open }
        var shown = 0
        for (item in news.items) {
            jobLog.heard(item.jobId, item.title, item.sessionId)
            item.approval?.let { approval ->
                approvals.removeAll { it.grantId == relay.grantId && it.jobId == item.jobId }
                approvals += approval
                approvalsShown++
                shown++
            }
            WatchJobAnswer.notice(item, relay.voiceApprovals)?.let { quietQueue += DirectOutgoing.TextWhenIdle(it) }
        }
        if (shown > 0) vibrate()
        if (news.items.isNotEmpty()) {
            log.note("directJobNews", mapOf("statuses" to news.items.map { it.status }, "resultChars" to news.items.map { it.result?.length ?: 0 }, "running" to news.running, "more" to news.more, "approvalsOnScreen" to approvals.size))
        }
        if (news.running == 0 && !news.more && approvals.none { it.grantId == relay.grantId }) stopFollowing(relay)
        flushQuietQueue()
        publish()
    }

    /** The watch's Approve or Deny for the request on screen. */
    fun answerApproval(approval: WatchJobAnswer.Approval, approve: Boolean) {
        // Only the card the user read; a late tap never answers the next one.
        if (approvals.firstOrNull() != approval) return
        val choice = if (approve) WatchJobAnswer.APPROVE else WatchJobAnswer.DENY
        resolveApproval(approval, choice, byVoice = false) { result ->
            if (result["error"] != null || result["status"] == "failed") return@resolveApproval
            // The model hears it without replying, so it doesn't ask again.
            val what = if (approve) "approved" else "denied"
            quietQueue += DirectOutgoing.ContextWhenIdle("[On their Watch, the user $what the command background job \"${approval.title}\" asked to run.]")
            flushQuietQueue()
        }
    }

    /** The model's answer to an approval request, when the user allowed voice approvals for this call; only ever once or deny. */
    private fun answerApprovalByVoice(call: GeminiLiveProtocol.FunctionCall, generation: Int?) {
        val jobId = call.arguments["job_id"]?.trim().orEmpty()
        val choice = call.arguments["choice"]?.trim()?.lowercase().orEmpty()
        val candidates = approvals.filter { jobId.isEmpty() || it.jobId == jobId }
        val refusal = when {
            candidates.size != 1 -> "No job is waiting for that approval."
            jobRelays.firstOrNull { it.grantId == candidates[0].grantId }?.voiceApprovals != true -> "The user answers approvals on their Watch screen. Ask them to tap Approve or Deny."
            choice != WatchJobAnswer.APPROVE && choice != WatchJobAnswer.DENY -> "choice must be once or deny"
            else -> null
        }
        if (refusal != null) {
            log.note("directApproval", mapOf("byVoice" to true, "refused" to refusal))
            answer(DirectOutgoing.ToolResponse(call.id, call.name, mapOf("error" to refusal), null, null), generation)
            return
        }
        toolsInFlight += call.id
        resolveApproval(candidates[0], choice, byVoice = true) { result ->
            toolsInFlight -= call.id
            if (!withdrawnToolIds.remove(call.id)) answer(DirectOutgoing.ToolResponse(call.id, call.name, result, null, null), generation)
        }
    }

    /** Sends an approval's answer to Hermes. The card leaves the screen at once and comes back if Hermes didn't take the answer. */
    private fun resolveApproval(approval: WatchJobAnswer.Approval, choice: String, byVoice: Boolean, done: (Map<String, String>) -> Unit) {
        approvals.remove(approval)
        publish()
        val relay = jobRelays.firstOrNull { it.grantId == approval.grantId } ?: run {
            done(mapOf("status" to "not_pending", "message" to "That job isn't waiting for an approval any more."))
            return
        }
        val id = callId
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.ANSWER_APPROVAL, buildJsonObject {
                put("job_id", approval.jobId)
                put("request_id", approval.requestId)
                put("choice", choice)
            })
            if (callId != id || !callActive) return@launch
            var taken = false
            var refused = false
            val result = when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> WatchJobAnswer.result(outcome.body).also {
                    taken = it["error"] == null && it["status"] != "failed"
                    refused = !taken
                }
                WatchToolRelayClient.Outcome.TimedOut -> WatchToolAnswer.tookTooLong
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    if (outcome.grantGone && !mayBeCarried(relay)) stopFollowing(relay)
                    mapOf("error" to "The answer didn't reach Hermes. Tell the user to try again on their Watch screen or in Conduit.")
                }
            }
            if (!taken) {
                // Back on screen, through the grant its job is on now.
                val holder = jobsHolder(relay)
                val again = approval.copy(grantId = holder.grantId)
                if (!refused && jobRelays.any { it === holder } && again !in approvals) approvals.add(0, again)
            }
            log.note("directApproval", mapOf("choice" to choice, "byVoice" to byVoice, "outcome" to outcome.logLabel, "taken" to taken))
            // The job moves on: its news is worth asking for now.
            nextNewsAt = now
            done(result)
            publish()
        }
    }

    /** While the phone can be reached, a new tool grant for one that ends soon or ended early. The old one is closed once nothing waits on it. */
    private fun renewGrantIfDue() {
        if (!hadToolGrant || engine == Engine.GROK || endRequestedAt != null || grantRequestInFlight || !link.reachable.value) return
        if (grantRequests >= MAX_GRANT_REQUESTS || now - lastGrantRequestAt < GRANT_REQUEST_INTERVAL_MS) return
        toolRelay?.let { if (!it.isGone && it.callsLeft > GRANT_RENEW_CALL_HEADROOM && !it.expiresWithin(GRANT_RENEW_MARGIN_MS)) return }
        grantRequestInFlight = true
        grantRequests++
        lastGrantRequestAt = now
        val id = callId
        // Its jobs move to the new grant, if Hermes still has it open.
        val carry = jobsGrant
        scope.launch {
            val answer = runCatching { link.request(Message.DirectGrant(id, carry?.grantId), GRANT_REQUEST_TIMEOUT_MS) }
            if (callId != id || !callActive) {
                // Nothing will use a grant it brought.
                ((answer.getOrNull() as? Message.DirectGrantIssued)?.grant)?.let { WatchToolRelayClient.of(it, relayHttp)?.close() }
                return@launch
            }
            grantRequestInFlight = false
            when (val reply = answer.getOrNull()) {
                is Message.DirectGrantIssued -> {
                    val relay = WatchToolRelayClient.of(reply.grant, relayHttp) ?: run {
                        log.note("directGrantRenewed", mapOf("ok" to false, "error" to "unreadable grant"))
                        return@launch
                    }
                    val carried = relay.hasJobs && carry != null && reply.grant.jobsCarriedFrom == carry.grantId
                    if (carried && carry != null) moveJobs(carry, relay)
                    val previous = toolRelay
                    toolRelay = relay
                    // Hermes kept the old grant's jobs: later renewals name it again until they're done or move.
                    val keptOld = !carried && carry != null && jobRelays.any { it === carry }
                    jobsGrant = if (keptOld) carry else relay.takeIf { it.hasJobs }
                    listOfNotNull(previous, carry).forEach(::closeIfUnused)
                    grantsRenewed++
                    log.note("directGrantRenewed", mapOf("ok" to true, "carried" to carried))
                }
                is Message.CallRefused -> log.note("directGrantRenewed", mapOf("ok" to false, "error" to reply.reason))
                else -> log.note("directGrantRenewed", mapOf("ok" to false, "error" to (answer.exceptionOrNull()?.message ?: "unreadable answer")))
            }
        }
    }

    private fun apply(result: WatchVoiceWire.DirectToolResult, answering: String?, generation: Int?, withdrawn: Boolean) {
        runningJobs = result.runningJobs
        for (item in result.outgoing) {
            when (item) {
                DirectOutgoing.EndConversation -> requestEnd()
                is DirectOutgoing.ToolResponse -> if (!(withdrawn && item.id == answering)) answer(item, generation)
                is DirectOutgoing.TextWhenIdle, is DirectOutgoing.ContextWhenIdle -> quietQueue += item
            }
        }
        flushQuietQueue()
        publish()
    }

    /** Answers a call on the connection it was made on; once another took over, its fallback goes out as a text update, as on the phone. */
    private fun answer(response: DirectOutgoing, generation: Int?) {
        var item = response as? DirectOutgoing.ToolResponse ?: return
        // A started job the model already said it's starting goes in silently.
        val silent = GeminiLiveProtocol.Scheduling.SILENT.raw
        if (item.scheduling == null && WatchJobAnswer.scheduling(item.name, item.result, item.id in toolCallsSpokenFor) == silent) {
            startJobsTakenSilently++
            item = item.copy(scheduling = silent)
        }
        val message = WatchDirectWire.clientMessage(item) ?: return
        val session = session
        if (session == null || !session.isReady || (generation != null && liveGeneration != generation)) {
            item.fallback?.let { quietQueue += DirectOutgoing.TextWhenIdle(it) }
            return
        }
        // The model talks about it next: job news waits for that.
        if (item.scheduling != silent) awaitingAnswerSince = now
        val id = callId
        val fallback = item.fallback
        session.send(message, onFailure = { if (callId == id && fallback != null) quietQueue += DirectOutgoing.TextWhenIdle(fallback) })
    }

    /** The model heard the user and has said nothing since, with nothing else owed: prompted once for that turn, with their words. */
    private fun promptIfStalled() {
        val session = session ?: return
        if (stallPromptedAt != null || endRequestedAt != null || phase != Phase.LISTENING || !session.isReady) return
        if (modelTurnActive || audio.isPlaying || toolsInFlight.isNotEmpty()) return
        if (!WatchStall.isDue(replyOwedSince, lastUserSpeechAt, now)) return
        awaitingAnswerSince?.let { if (now - it < REPLY_WAIT_MS) return }
        val at = now
        val text = WatchStall.prompt(transcript.lastOrNull { it.role == WatchVoiceWire.DirectTurn.Role.USER }?.text)
        log.note("directStallPrompt", mapOf("owedMs" to replyOwedSince?.let { at - it }, "silentTurn" to silentTurnSinceUser, "reachable" to link.reachable.value))
        val owed = replyOwedSince
        replyOwedSince = null
        stallPromptedAt = at
        stallPrompts++
        awaitingAnswerSince = at
        val id = callId
        session.send(LiveVoiceClientMessage.TextTurn(text), onFailure = {
            if (callId != id || stallPromptedAt != at) return@send
            stallPromptedAt = null
            // Never sent: the user's words still wait for a reply.
            if (replyOwedSince == null) replyOwedSince = owed
        })
    }

    /** Nothing at all came back from the model for the prompt: the session stopped working, and a fresh one carries the call on. */
    private fun rejoinIfStallUnanswered() {
        val prompted = stallPromptedAt ?: return
        if (now - prompted < WatchStall.ANSWER_WAIT_MS) return
        stallPromptedAt = null
        val session = session ?: return
        if (!session.isReady || endRequestedAt != null) return
        stalls++
        log.note("directStalled", mapOf("rejoins" to rejoins, "reachable" to link.reachable.value, "relayToken" to canMintThroughRelay))
        session.stop()
        val reason = if (engine == Engine.GROK) L10n.tr("Grok stopped answering.") else L10n.tr("Gemini stopped answering.")
        if (rejoins < MAX_REJOINS) waitToRejoin(reason, WatchRejoin.Cause.FROZE) else finish(reason)
    }

    /** Job news and the like go out only while nobody is speaking. */
    private fun flushQuietQueue() {
        val session = session ?: return
        if (quietQueue.isEmpty() || !session.isReady || !isQuiet) return
        val item = quietQueue.removeAt(0)
        val message = WatchDirectWire.clientMessage(item) ?: return
        textUpdatesSent++
        // A text turn is answered; the next update waits for that.
        if (item is DirectOutgoing.TextWhenIdle) awaitingAnswerSince = now
        val id = callId
        session.send(message, onFailure = { if (callId == id) quietQueue.add(0, item) })
    }

    private val isQuiet: Boolean
        get() {
            if (endRequestedAt != null || phase == Phase.NEEDS_TAP || modelTurnActive || audio.isPlaying) return false
            val at = now
            // The model owes an answer: to the user's turn, to a tool it called and is waiting on, or to what just went to it.
            if (awaitingReplySince != null) return false
            toolsInFlight.mapNotNull { toolCalledAt[it] }.minOrNull()?.let { if (at - it < TOOL_HOLD_LIMIT_MS) return false }
            awaitingAnswerSince?.let { if (at - it < REPLY_WAIT_MS) return false }
            lastUserSpeechAt?.let { if (at - it < USER_QUIET_MS) return false }
            activity.lastVoicedAtMs?.let { if (at - it < USER_QUIET_MS) return false }
            lastModelTurnEndedAt?.let { if (at - it < MODEL_QUIET_MS) return false }
            return true
        }

    /** While this call's jobs run on the phone, asks it for their news. None is made while no job runs. */
    private fun pollIfNeeded() {
        if (runningJobs <= 0 || pollInFlight || !link.reachable.value || now - lastPollAt < POLL_INTERVAL_MS) return
        pollInFlight = true
        lastPollAt = now
        polls++
        val id = callId
        scope.launch {
            val answer = runCatching { link.request(Message.DirectPoll(id), TOOL_REQUEST_TIMEOUT_MS) }.getOrNull()
            if (callId != id || !callActive) return@launch
            pollInFlight = false
            val result = (answer as? Message.DirectToolResultIssued)?.result ?: return@launch
            if (result.outgoing.isNotEmpty() || result.runningJobs != runningJobs) {
                log.note("directPoll", mapOf("runningJobs" to result.runningJobs, "outgoing" to result.outgoing.size))
            }
            apply(result, null, null, false)
        }
    }

    // endregion

    // region Audio

    private fun captured(samples: ShortArray, at: Long) {
        if (!callActive || endRequestedAt != null || muted || phase == Phase.NEEDS_TAP || isMicrophoneHeld) return
        activity.process(samples, WatchAudio.CAPTURE_RATE, at)
        pending += samples
        // Before the session is ready, keep the newest few seconds only.
        val limit = (WatchAudio.CAPTURE_RATE * PRE_ROLL_S).toInt()
        if (session?.isReady != true && pending.size > limit) {
            if (firstReadyAt != null) samplesDroppedWhileDown += pending.size - limit
            pending = pending.copyOfRange(pending.size - limit, pending.size)
        }
    }

    /** Half duplex: the model's voice from the speaker must not read as the user. */
    private val isMicrophoneHeld: Boolean
        get() = audio.isPlaying || lastPlaybackEndedAt?.let { now - it < ECHO_TAIL_MS } == true

    private fun flushUplink() {
        val session = session ?: return
        if (!session.isReady || pending.isEmpty()) return
        val samples = pending
        pending = ShortArray(0)
        if (sendsInFlight >= MAX_SENDS_IN_FLIGHT) {
            chunksDropped++
            return
        }
        chunksUp++
        sendsInFlight++
        val id = callId
        session.send(
            LiveVoiceClientMessage.Audio(WatchAudioBridgeWire.pcm(samples)),
            onSent = { if (callId == id) sendsInFlight = maxOf(0, sendsInFlight - 1) },
            onFailure = {
                if (callId == id) {
                    sendsInFlight = maxOf(0, sendsInFlight - 1)
                    sendFailures++
                }
            },
        )
    }

    private fun playbackDrained() {
        lastPlaybackEndedAt = now
        if (!callActive || phase != Phase.SPEAKING) return
        phase = Phase.LISTENING
        flushQuietQueue()
        publish()
    }

    private fun audioInterrupted(began: Boolean) {
        if (!callActive || endRequestedAt != null) return
        log.note("directAudioInterruption", mapOf("began" to began))
        if (began) {
            if (session == null) {
                finish(L10n.tr("The microphone was interrupted before the call connected. Try again."))
                return
            }
            pending = ShortArray(0)
            activity.reset()
            microphoneStopped = true
            phase = Phase.NEEDS_TAP
        } else {
            restartAudio()
        }
        publish()
    }

    private fun restartAudio() {
        if (endRequestedAt != null) return
        try {
            audio.start(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
            microphoneStopped = false
            phase = if (session?.isReady == true) restingPhase else Phase.RECONNECTING
            log.note("directAudioRestarted")
        } catch (e: Exception) {
            log.note("directAudioRestartFailed", mapOf("error" to e.message))
        }
        publish()
    }

    // endregion

    // region Lifecycle

    private fun tick() {
        if (!callActive) return
        audio.checkPlayback()
        if (microphoneStopped && endRequestedAt == null && (phase == Phase.LISTENING || phase == Phase.SPEAKING)) phase = Phase.NEEDS_TAP
        if (phase == Phase.PREPARING && now - callStartedAt >= SESSION_WAIT_MS + 15_000) {
            finish(L10n.tr("Conduit on your phone didn't answer."))
            return
        }
        awaitingReplySince?.let { since ->
            if (now - since >= UNANSWERED_AFTER_MS && !modelTurnActive) {
                awaitingReplySince = null
                unansweredTurns++
                log.note("directUnanswered", mapOf("ready" to (session?.isReady == true), "toolsRunning" to toolsInFlight.size, "prompted" to (stallPromptedAt != null)))
            }
        }
        promptIfStalled()
        rejoinIfStallUnanswered()
        endIfUserSaidGoodbye(finished = false)
        endRequestedAt?.let { endAt ->
            val lastSound = maxOf(endAt, lastModelAudioAt ?: 0)
            if ((!audio.isPlaying && now - lastSound >= END_GRACE_MS) || now - endAt >= END_TIMEOUT_MS) {
                finish(WatchVoiceCall.GOODBYE)
                return
            }
        }
        rejoinHungIfDue()
        rejoinIfDue()
        renewGrantIfDue()
        fetchJobNewsIfDue()
        pollIfNeeded()
        flushQuietQueue()
        publish()
    }

    /**
     * The user's latest utterance is one of the profile's end phrases, as on
     * the phone. Checked when the model's turn completes, and once the words
     * went quiet with the model not mid-turn; a quiet utterance must end like
     * a sentence.
     */
    private fun endIfUserSaidGoodbye(finished: Boolean) {
        if (!callActive || endRequestedAt != null || endPhrases.isEmpty()) return
        val line = exchangeUserLine?.takeIf { it in transcript.indices } ?: return
        val text = transcript[line].text
        if (!finished) {
            val heard = lastUserSpeechAt ?: return
            if (modelTurnActive || now - heard < QUIET_GOODBYE_DELAY_MS || !endsAnUtterance(text)) return
        }
        if (!VoiceCommands.matchesSpokenCommand(text, endPhrases)) return
        exchangeUserLine = null
        log.note("directGoodbyeHeard", mapOf("afterTurn" to finished))
        requestEnd()
    }

    /** Hermes said goodbye, or the user did: the microphone closes and the call ends once the goodbye has played. */
    private fun requestEnd() {
        if (endRequestedAt != null || !callActive) return
        endRequestedAt = now
        pending = ShortArray(0)
        phase = Phase.ENDING
        publish()
    }

    private fun reset() {
        timers.forEach { it.cancel() }
        timers.clear()
        session?.stop()
        session = null
        tokens = null
        callId = Random.nextLong(1, 0xFFFF_FFFFL)
        callUuid = UUID.randomUUID().toString()
        callStartedWallMs = System.currentTimeMillis()
        callStartedAt = now
        endReason = null
        liveSinceWallMs = null
        endedWallMs = null
        muted = false
        caption = null
        runningJobs = 0
        relayJobsRunning = 0
        grokStreams = 0
        openingPrompt = null
        endPhrases = emptyList()
        hasSentOpening = false
        sessionRequests = 0
        firstReadyAt = null
        reconnectStartedAt = null
        rejoinWaitingSince = null
        rejoinReason = null
        rejoinCause = WatchRejoin.Cause.BROKE
        rejoinStartedAt = null
        rejoinWithoutPhone = false
        phonelessRejoinFailed = false
        rejoins = 0
        rejoinsHung = 0
        samplesDroppedWhileDown = 0
        generationOffset = 0
        drops = 0
        goAways = 0
        tokensFromPhone = 0
        tokensReused = 0
        tokensFromRelay = 0
        tokenFailures = 0
        suppressingModelTurn = false
        modelTurnActive = false
        lastUserSpeechAt = null
        lastModelTurnEndedAt = null
        lastModelAudioAt = null
        awaitingReplySince = null
        awaitingAnswerSince = null
        unansweredTurns = 0
        replyOwedSince = null
        silentTurnSinceUser = false
        stallPromptedAt = null
        stallPrompts = 0
        stalls = 0
        turnStarted = false
        turnEndedByInterruption = false
        endRequestedAt = null
        transcript = mutableListOf()
        openUserLine = null
        openAssistantLine = null
        exchangeUserLine = null
        turns = 0
        toolsInFlight.clear()
        toolCalledAt.clear()
        toolCallsSpokenFor.clear()
        withdrawnToolIds.clear()
        quietQueue.clear()
        toolCalls = 0
        toolsAnsweredLive = 0
        toolsUnreachable = 0
        jobsQueued = 0
        startJobsTakenSilently = 0
        toolRelay?.close()
        toolRelay = null
        hadToolGrant = false
        grantRequestInFlight = false
        lastGrantRequestAt = 0
        grantRequests = 0
        grantsRenewed = 0
        toolsViaRelay = 0
        relayErrorAnswers = 0
        relayTimeouts = 0
        relayFallbacks = 0
        pollInFlight = false
        lastPollAt = 0
        polls = 0
        textUpdatesSent = 0
        jobRelays.forEach { it.close() }
        jobRelays.clear()
        jobsGrant?.close()
        jobsGrant = null
        jobsMovedTo.clear()
        relayRunning.clear()
        approvals.clear()
        newsInFlight = false
        nextNewsAt = 0
        relayJobsStarted = 0
        jobLog.reset()
        approvalsShown = 0
        pending = ShortArray(0)
        sendsInFlight = 0
        lastPlaybackEndedAt = null
        activity.reset()
        chunksUp = 0
        chunksDropped = 0
        sendFailures = 0
        microphoneStopped = false
    }

    private fun finish(reason: String?) {
        if (!callActive) return
        timers.forEach { it.cancel() }
        timers.clear()
        session?.stop()
        session = null
        tokens = null
        // Ends the grants on the relay and so on Hermes; the phone revokes them too once the call's end reaches it.
        // Jobs still running keep running in Hermes, and their results come as notifications.
        toolRelay?.close()
        toolRelay = null
        val relayJobsLeft = relayJobsRunning
        jobRelays.forEach { it.close() }
        jobRelays.clear()
        jobsGrant?.close()
        jobsGrant = null
        jobsMovedTo.clear()
        approvals.clear()
        audio.stop()
        CallService.stop(context)
        val liveMs = firstReadyAt?.let { now - it } ?: 0
        phase = Phase.ENDED
        endReason = reason
        endedWallMs = System.currentTimeMillis()
        // Queued, not sent: the phone saves the call whenever Conduit next runs there.
        val saved = WatchVoiceWire.DirectTranscript.capped(transcript.filter { it.text.isNotBlank() })
        val queued = link.queue(Message.DirectEnd(callId, WatchVoiceWire.DirectTranscript(
            callUuid, callStartedWallMs, System.currentTimeMillis(), saved,
            engine = if (engine == Engine.GROK) WatchAudioBridgeWire.GROK else null,
            jobs = jobLog.jobs.ifEmpty { null },
        )))
        log.note("directCallSummary", mapOf(
            "callID" to callId, "engine" to engine.name.lowercase(), "reason" to reason, "durationS" to (now - callStartedAt) / 1000, "liveS" to liveMs / 1000,
            "sessionRequests" to sessionRequests, "turns" to turns, "unansweredTurns" to unansweredTurns, "stallPrompts" to stallPrompts, "stalls" to stalls,
            "drops" to drops, "rejoins" to rejoins, "rejoinsHung" to rejoinsHung, "goAways" to goAways,
            "tokensFromPhone" to tokensFromPhone, "tokensReused" to tokensReused, "tokensFromRelay" to tokensFromRelay, "tokenFailures" to tokenFailures,
            "toolCalls" to toolCalls, "toolsAnsweredLive" to toolsAnsweredLive, "toolsUnreachable" to toolsUnreachable, "jobsQueued" to jobsQueued,
            "startJobsTakenSilently" to startJobsTakenSilently, "toolGrant" to hadToolGrant, "toolsViaRelay" to toolsViaRelay,
            "relayErrorAnswers" to relayErrorAnswers, "relayTimeouts" to relayTimeouts, "relayFallbacks" to relayFallbacks,
            "grantRequests" to grantRequests, "grantsRenewed" to grantsRenewed, "polls" to polls, "jobsViaRelay" to relayJobsStarted,
            "relayJobsStillRunning" to relayJobsLeft, "approvalsShown" to approvalsShown, "textUpdatesSent" to textUpdatesSent,
            "chunksUp" to chunksUp, "chunksDropped" to chunksDropped, "sendFailures" to sendFailures,
            "transcriptLines" to saved.size, "transcriptQueued" to queued,
        ))
        publish()
    }

    // endregion

    private fun vibrate() {
        runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
    }

    private fun publish() {
        val approval = approvals.firstOrNull()
        _state.value = WatchCall.State(
            phase = when (phase) {
                Phase.IDLE -> WatchCall.Phase.IDLE
                Phase.PREPARING -> WatchCall.Phase.PREPARING
                Phase.CONNECTING -> WatchCall.Phase.CONNECTING
                Phase.LISTENING -> WatchCall.Phase.LISTENING
                Phase.SPEAKING -> WatchCall.Phase.SPEAKING
                Phase.RECONNECTING -> WatchCall.Phase.RECONNECTING
                Phase.LOST -> WatchCall.Phase.LOST
                Phase.NEEDS_TAP -> WatchCall.Phase.NEEDS_TAP
                Phase.ENDING -> WatchCall.Phase.ENDING
                Phase.ENDED -> WatchCall.Phase.ENDED
            },
            muted = muted,
            caption = caption,
            notice = if (phase == Phase.LOST) L10n.tr("Waiting for your phone") else null,
            transcript = transcript.filter { it.text.isNotBlank() }.map { WatchCall.Line(it.role == WatchVoiceWire.DirectTurn.Role.USER, it.text.trim()) },
            jobApproval = approval,
            jobsRunning = runningJobs + relayJobsRunning,
            liveSinceMs = liveSinceWallMs,
            endedAtMs = endedWallMs,
            endReason = endReason?.takeIf { !WatchVoiceCall.isNormalEnd(it) },
            chatTitle = if (engine == Engine.GROK) "Grok" else "Gemini Live",
        )
    }

    companion object {
        const val SESSION_WAIT_MS = 30_000L
        const val SESSION_RETRY_DELAY_MS = 2_000L
        const val SESSION_REQUEST_TIMEOUT_MS = 30_000L
        const val TOKEN_WAIT_MS = 10_000L
        const val TOOL_REQUEST_TIMEOUT_MS = 35_000L
        const val GRANT_REQUEST_TIMEOUT_MS = 15_000L
        const val FLUSH_INTERVAL_MS = 100L
        /** After the model stops playing, the microphone stays closed this long so the speaker's echo can't read as the user. */
        const val ECHO_TAIL_MS = 350L
        /** Speech captured while the call connects, sent once it's ready. */
        const val PRE_ROLL_S = 3.0
        const val MAX_SENDS_IN_FLIGHT = 20
        const val USER_QUIET_MS = 2_000L
        const val MODEL_QUIET_MS = 1_000L
        /** After a tool's answer or a text turn goes to the model, job news waits this long at most for the model's reply to start. */
        const val REPLY_WAIT_MS = 8_000L
        /** The oldest tool call still running holds job news back this long at most. */
        const val TOOL_HOLD_LIMIT_MS = 40_000L
        /** Without the phone or a relay token, a broken session waits this long for the phone, a few times a call at most. */
        const val REJOIN_WAIT_MS = 120_000L
        const val MAX_REJOINS = 3
        const val REJOIN_HANG_LIMIT_MS = SESSION_WAIT_MS + WatchToolRelayClient.REQUEST_TIMEOUT_MS
        const val END_GRACE_MS = 1_000L
        const val END_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 20_000L
        const val UNANSWERED_AFTER_MS = 15_000L
        const val GRANT_END_MARGIN_MS = 5_000L
        const val GRANT_RENEW_MARGIN_MS = 10 * 60_000L
        const val GRANT_RENEW_CALL_HEADROOM = 10
        const val GRANT_REQUEST_INTERVAL_MS = 60_000L
        const val MAX_GRANT_REQUESTS = 6
        const val JOB_NEWS_WAIT_S = 15
        const val JOB_NEWS_PAUSE_MS = 15_000L
        const val JOB_NEWS_RETRY_MS = 2_000L
        const val QUIET_GOODBYE_DELAY_MS = 1_500L
        /** A stream id is taken once per grant, and a grant takes 32. */
        const val MAX_GROK_STREAMS = 30
        /** Calls the phone answers once nobody speaks (NON_BLOCKING lookups). */
        val WHEN_IDLE_TOOLS = WatchToolAnswer.chatTools + setOf(WatchToolAnswer.WEB_SEARCH, WatchToolAnswer.RECALL_MEMORY)

        fun endsAnUtterance(text: String): Boolean = text.trim().lastOrNull()?.let { it in ".!?。！？" } ?: false

        /** The phone's seam repair for the model's streamed transcript: Gemini sometimes drops the space between two chunks. */
        fun joinTranscriptChunk(existing: String, chunk: String): String {
            val last = existing.lastOrNull() ?: return existing + chunk
            val first = chunk.firstOrNull() ?: return existing + chunk
            if (last.isWhitespace() || first.isWhitespace()) return existing + chunk
            val needsSpace = when {
                spaced(last) -> spaced(first)
                last in ".,!?;:" -> first.isLetter() && spaced(first)
                else -> false
            }
            return if (needsSpace) "$existing $chunk" else existing + chunk
        }

        private fun spaced(c: Char): Boolean {
            if (!c.isLetterOrDigit()) return false
            return when (c.code) {
                in 0x0E00..0x0EFF, in 0x1000..0x109F, in 0x1780..0x17FF, in 0x3040..0x30FF,
                in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, in 0xFF66..0xFF9F -> false
                else -> true
            }
        }
    }
}
