package app.hermesconduit.android.wear

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import app.hermesconduit.android.core.live.GPTLiveProtocol
import app.hermesconduit.wearwire.VoiceCommands
import app.hermesconduit.android.core.live.VoiceChatToolText
import app.hermesconduit.wearwire.WatchAudioBridgeStream
import app.hermesconduit.wearwire.WatchAudioBridgeWire
import app.hermesconduit.wearwire.WatchBridgeDelegation
import app.hermesconduit.wearwire.WatchJobAnswer
import app.hermesconduit.wearwire.WatchJobProfiles
import app.hermesconduit.wearwire.WatchToolAnswer
import app.hermesconduit.wearwire.WatchToolRelayClient
import app.hermesconduit.wearwire.WatchToolSeal
import app.hermesconduit.wearwire.WatchVoiceActivity
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WatchVoiceWire.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * A GPT-Live call on the watch through the Hermes host's audio bridge (port
 * of the iOS `WatchBridgeCallModel`, designs/apple-watch-gpt-live.md).
 * GPT-Live on the ChatGPT subscription speaks WebRTC only, so the
 * conduit_push plugin holds the WebRTC call and the watch streams to it: one
 * WebSocket through the push relay, every message sealed with the call's
 * grant ([WatchAudioBridgeWire]). The phone only builds the call's briefing
 * and asks Hermes for the grant; after that the call runs without it.
 *
 * GPT-Live hands real work to the app as delegations. As on the phone, each
 * becomes a Hermes job, here through the grant's relay route, and its outcome
 * goes back on the delegation once nobody is speaking. A job that asks for
 * approval shows Approve and Deny on the watch.
 *
 * The bridge sends GPT-Live's audio as one continuous stream, silence
 * included: the watch plays only stretches of speech, so the speaker (and
 * with it the microphone hold) follows what GPT-Live says. Half duplex, with
 * a tap to stop GPT-Live.
 */
class WatchBridgeLiveCall(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: PhoneLink,
    private val log: WatchCallLog,
) {
    private enum class Phase { IDLE, PREPARING, CONNECTING, LISTENING, SPEAKING, RECONNECTING, NEEDS_TAP, ENDING, ENDED }

    private data class Pending(val text: String, val channel: GPTLiveProtocol.Channel, val delegationId: String?)

    private val _state = MutableStateFlow(WatchCall.State())
    val state: StateFlow<WatchCall.State> = _state

    private val http = OkHttpClient()
    private val relayHttp = WatchToolRelayClient.httpClient(http)
    private val audio = WatchAudio(context)
    var callId: Long = 0
        private set
    private var callUuid = UUID.randomUUID().toString()
    private var callStartedWallMs = 0L
    private var callStartedAt = 0L
    private val timers = mutableListOf<Job>()
    private var phase = Phase.IDLE
    private var endReason: String? = null
    private var liveSinceWallMs: Long? = null
    private var endedWallMs: Long? = null
    private var muted = false
    private var caption: String? = null
    private var relayJobsRunning = 0
    private var sessionRequests = 0

    // The call from the phone
    private var bridgeUrl: String? = null
    private var grantId: String? = null
    private var grantRoot: ByteArray? = null
    private var watchKey: String? = null
    private var briefing: String? = null
    private var greeting: String? = null
    private var openingTurn: String? = null
    private var voice: String? = null
    private var relay: WatchToolRelayClient? = null

    // The connection
    private var socket: WatchBridgeSocket? = null
    private var stream: WatchAudioBridgeStream? = null
    private var connection = 0
    private var streamsOpened = 0
    private var live = false
    private var socketOpenedAt: Long? = null
    private var startSentAt: Long? = null
    private var firstStartedAt: Long? = null
    private var hostOfflineTries = 0
    private var rejoins = 0
    private var drops = 0
    private var hostError: String? = null
    private var unopened = 0
    private var microphoneStopped = false

    // Uplink
    private var pending = ShortArray(0)
    private var chunksUp = 0
    private var chunksDropped = 0
    private var sendFailures = 0
    private val activity = WatchVoiceActivity()
    private var lastPlaybackEndedAt: Long? = null

    // Downlink
    private var modelSpeaking = false
    private var suppressingTurn = false
    private var lastLoudAt: Long? = null
    private var leadIn = ShortArray(0)
    private var speechStretches = 0

    // Conversation
    private val transcript = mutableListOf<WatchVoiceWire.DirectTurn>()
    /** Lines passed on with a delegation already. */
    private var handledLines = 0
    private var userLine = ""
    private var assistantLine = ""
    private var endPhrases = listOf<String>()
    /** When the user said one of them; the microphone stays closed after. */
    private var goodbyeHeardAt: Long? = null
    /** A delegation already took the user's words still being heard: their line counts as handled when it lands. */
    private var openLineDelegated = false
    private var endRequestedAt: Long? = null

    // Delegations and jobs
    private val pendingSends = mutableListOf<Pending>()
    private val seenDelegations = mutableSetOf<String>()
    /** Each delegation's connection: a rejoined session can't take an answer on the old one's delegations. */
    private val delegationConnection = mutableMapOf<String, Int>()
    /** How many of the user's lines each delegation was made after, to tell when they kept talking before its result. */
    private val delegationUserLines = mutableMapOf<String, Int>()
    private val jobDelegations = mutableMapOf<String, String>()
    /** Jobs this call started that still run, with their titles and statuses: what GPT-Live is told. */
    private val runningJobs = linkedMapOf<String, Pair<String, String>>()
    /** The host's job ids by their number in this call, as GPT-Live names them in a correction ("Job 2: …"). */
    private val jobNumbers = sortedMapOf<Int, String>()
    private var delegations = 0
    private var relayJobsStarted = 0
    private val jobLog = WatchVoiceWire.DirectJobLog()
    private var lookupsInstead = 0
    private var followUps = 0
    private var followingJobs = false
    private var newsInFlight = false
    private var nextNewsAt = 0L
    private val approvals = mutableListOf<WatchJobAnswer.Approval>()
    private var approvalsShown = 0

    private val now: Long get() = SystemClock.uptimeMillis()

    val callActive: Boolean get() = phase != Phase.IDLE && phase != Phase.ENDED

    init {
        audio.onCapture = ::captured
        audio.onDrained = ::playbackDrained
        audio.onInterruption = ::audioInterrupted
    }

    // region Controls

    fun start() {
        if (callActive) return
        reset()
        phase = Phase.PREPARING
        publish()
        CallService.start(context)
        try {
            audio.start(PLAYBACK_RATE)
        } catch (e: Exception) {
            finish(e.message ?: L10n.tr("The microphone didn't start: %s", e.javaClass.simpleName))
            return
        }
        log.note("bridgeCallStart", mapOf("callID" to callId, "engine" to ENGINE, "reachable" to link.reachable.value))
        timers += scope.launch { while (callActive) { delay(100); flushUplink() } }
        timers += scope.launch { while (callActive) { delay(1_000); tick() } }
        requestSession()
    }

    fun end() {
        if (!callActive) return
        if (!live || endRequestedAt != null) {
            finish(WatchVoiceCall.END_BY_USER)
            return
        }
        endRequestedAt = now
        phase = Phase.ENDING
        audio.stopPlayback()
        send(WatchAudioBridgeWire.end, WatchAudioBridgeWire.Kind.CONTROL)
        publish()
    }

    /** The orb: stops GPT-Live while it speaks, brings the microphone back after another app took it. */
    fun tap() {
        if (phase == Phase.NEEDS_TAP || microphoneStopped) {
            restartAudio()
            return
        }
        if (!modelSpeaking && !audio.isPlaying) return
        // Playback stops now and the rest of this stretch of speech is dropped; GPT-Live stops when it hears the user.
        audio.stopPlayback()
        suppressingTurn = true
        modelSpeaking = false
        lastPlaybackEndedAt = now
        if (phase == Phase.SPEAKING) phase = Phase.LISTENING
        log.note("bridgeInterrupt")
        publish()
    }

    fun toggleMute() {
        if (!callActive) return
        muted = !muted
        pending = ShortArray(0)
        activity.reset()
        publish()
    }

    fun dismissEnded() {
        if (phase != Phase.ENDED) return
        phase = Phase.IDLE
        _state.value = WatchCall.State()
    }

    /** The watch's Approve or Deny for the request on screen. */
    fun answerApproval(approval: WatchJobAnswer.Approval, approve: Boolean) {
        // A second quick tap lands after the card moved on: it answers nothing rather than the next command.
        val relay = relay
        if (approvals.firstOrNull() != approval || relay == null) return
        approvals.remove(approval)
        publish()
        val choice = if (approve) WatchJobAnswer.APPROVE else WatchJobAnswer.DENY
        val id = callId
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.ANSWER_APPROVAL, buildJsonObject {
                put("job_id", approval.jobId)
                put("request_id", approval.requestId)
                put("choice", choice)
            })
            if (callId != id || !callActive) return@launch
            // The grant ran out (it isn't renewed: the bridge belongs to it), so no card can be answered from the watch any more.
            if (outcome is WatchToolRelayClient.Outcome.Unavailable && WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, false)) {
                log.note("bridgeApproval", mapOf("choice" to choice, "taken" to false, "outcome" to outcome.logLabel, "reason" to outcome.reason))
                approvals.clear()
                stopFollowingJobs("approvalsRanOut")
                caption = L10n.tr("This call's access to Hermes ran out, so it can't answer the job's request.")
                publish()
                return@launch
            }
            var taken = false
            var refused = false
            if (outcome is WatchToolRelayClient.Outcome.Answered) {
                val result = WatchJobAnswer.result(outcome.body)
                taken = result["error"] == null && result["status"] != "failed"
                refused = !taken
            }
            log.note("bridgeApproval", mapOf("choice" to choice, "taken" to taken, "outcome" to outcome.logLabel))
            if (taken) {
                val what = if (approve) "approved" else "denied"
                pendingSends += Pending("[On their Watch, the user $what the command background job \"${approval.title}\" asked to run.]", GPTLiveProtocol.Channel.COMMENTARY, null)
            } else if (!refused && approval !in approvals) {
                approvals.add(0, approval)
            }
            nextNewsAt = now
            flushPending()
            publish()
        }
    }

    // endregion

    // region The call from the phone

    private fun requestSession() {
        val id = callId
        scope.launch {
            while (callId == id && phase == Phase.PREPARING) {
                sessionRequests++
                val answer = try {
                    link.request(Message.BridgeStart(id, WatchVoiceWire.VERSION, ENGINE), SESSION_REQUEST_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (callId != id || phase != Phase.PREPARING) return@launch
                    log.note("bridgeStartFailed", mapOf("error" to e.message, "reachable" to link.reachable.value, "attempt" to sessionRequests))
                    if (now - callStartedAt >= SESSION_WAIT_MS) {
                        finish(L10n.tr("Couldn't reach Conduit on your phone."))
                        return@launch
                    }
                    delay(2_000)
                    continue
                }
                if (callId != id || phase != Phase.PREPARING) return@launch
                when (answer) {
                    is Message.BridgeSessionIssued -> begin(answer.session)
                    is Message.CallRefused -> {
                        log.note("bridgeRefused", mapOf("reason" to answer.reason))
                        finish(answer.reason)
                    }
                    else -> finish(L10n.tr("Conduit on the phone sent something this Watch app can't read. Update both."))
                }
                return@launch
            }
        }
    }

    private fun begin(session: WatchVoiceWire.BridgeSession) {
        val bridge = session.grant.audio
        val root = WatchToolSeal.data(session.grant.key)?.takeIf { it.size == 32 }
        // Delegations, jobs and approvals all go through the grant.
        val relayClient = WatchToolRelayClient.of(session.grant, relayHttp)
        if (bridge == null || !bridge.url.startsWith("wss://") || root == null || relayClient == null) {
            finish(L10n.tr("The phone's GPT-Live call couldn't be read. Update Conduit on both devices."))
            return
        }
        if (bridge.version != WatchAudioBridgeWire.VERSION) {
            finish(L10n.tr("Update the Hermes notifier plugin and Conduit to matching versions."))
            return
        }
        bridgeUrl = bridge.url
        grantId = session.grant.grantId
        grantRoot = root
        watchKey = session.grant.watchKey
        briefing = session.briefingText
        greeting = session.greeting
        openingTurn = session.openingTurn
        voice = session.voice
        endPhrases = session.endPhrases.orEmpty()
        relay = relayClient
        log.note("bridgeSession", mapOf(
            "afterTapMs" to now - callStartedAt, "requests" to sessionRequests, "briefingBytes" to session.briefingBytes,
            "compressedBytes" to session.briefing.size, "briefingRead" to (briefing != null), "greeting" to (greeting != null),
            "openingTurn" to (openingTurn != null), "engines" to bridge.engines, "relayTools" to relayClient.tools.sorted(), "maxJobs" to relayClient.maxJobs,
        ))
        phase = Phase.CONNECTING
        publish()
        connect()
    }

    // endregion

    // region Connection

    private fun connect() {
        val url = bridgeUrl ?: return
        val grant = grantId ?: return
        val root = grantRoot ?: return
        val key = watchKey ?: return
        if (!callActive) return
        // A stream id is taken once per grant, and a grant takes 32.
        val stream = if (streamsOpened < 30) WatchAudioBridgeStream.of(grant, root) else null
        if (stream == null) {
            finish(L10n.tr("Couldn't reconnect to Hermes."))
            return
        }
        closeSocket()
        streamsOpened++
        connection++
        val number = connection
        // A host error explains only its own connection's failure.
        hostError = null
        this.stream = stream
        live = false
        socketOpenedAt = null
        startSentAt = null
        modelSpeaking = false
        suppressingTurn = false
        leadIn = ShortArray(0)
        val socket = WatchBridgeSocket(url, key, http)
        socket.onEvent = { kind, fields -> socketEvent(number, kind, fields) }
        socket.onMessage = { data -> received(data, number) }
        this.socket = socket
    }

    private fun socketEvent(number: Int, kind: String, fields: Map<String, Any?>) {
        // A socket already given up on reports its close too.
        if (number != connection || socket == null || !callActive) return
        when (kind) {
            "open" -> {
                socketOpenedAt = now
                log.note("bridgeSocketOpen", mapOf("connection" to number))
                sendStart()
            }
            "close", "failure" -> {
                log.note("bridgeSocketClosed", fields + mapOf("connection" to number, "event" to kind, "live" to live))
                connectionLost(fields["code"] as? Int ?: socket?.closeCode, socketOpenedAt != null)
            }
        }
    }

    /** The hello in clear, then the sealed start. A rejoined session gets the conversation so far, and no second greeting. */
    private fun sendStart() {
        val stream = stream ?: return
        socket?.send(WatchAudioBridgeWire.hello(stream.streamId))
        val rejoining = firstStartedAt != null
        val history = if (rejoining) transcript.takeLast(HISTORY_TURNS).map { GPTLiveProtocol.historyItem(it.role.raw, it.text) } else emptyList()
        send(WatchAudioBridgeWire.start(ENGINE, voice, briefing, if (rejoining) null else greeting, !rejoining && openingTurn != null, history), WatchAudioBridgeWire.Kind.CONTROL)
        startSentAt = now
    }

    private fun closeSocket() {
        socket?.close()
        socket = null
        startSentAt = null
    }

    private fun connectionLost(code: Int?, opened: Boolean) {
        val wasLive = live
        live = false
        closeSocket()
        if (endRequestedAt != null) {
            finish(WatchVoiceCall.END_BY_USER)
            return
        }
        // The call's grant ended: closed on Hermes or the relay, or expired.
        if (code == 4010) {
            finish((if (wasLive) null else hostError) ?: L10n.tr("The call's access to Hermes ended."))
            return
        }
        // Turned away until the host's bridge reaches the relay.
        if (code == 4503 && !wasLive && firstStartedAt == null && hostOfflineTries < HOST_OFFLINE_RETRIES) {
            hostOfflineTries++
            retry(1_000)
            return
        }
        val error = hostError
        if (error != null && !wasLive) {
            finish(error)
            return
        }
        drops++
        if (rejoins >= MAX_REJOINS) {
            finish(L10n.tr("Lost the connection to Hermes."))
            return
        }
        rejoins++
        phase = Phase.RECONNECTING
        audio.stopPlayback()
        publish()
        retry(1_000)
    }

    private fun retry(afterMs: Long) {
        val id = callId
        val number = connection
        scope.launch {
            delay(afterMs)
            if (callId == id && connection == number && callActive && endRequestedAt == null) connect()
        }
    }

    private fun received(data: ByteArray, number: Int) {
        if (number != connection || socket == null || !callActive) return
        // The relay's notices go to the host only.
        if (WatchAudioBridgeWire.notice(data) != null) return
        val stream = stream ?: return
        val (kind, plain) = try {
            stream.open(data)
        } catch (e: Exception) {
            unopened++
            if (unopened <= 5) log.note("bridgeUnopened", mapOf("error" to e.message, "bytes" to data.size))
            return
        }
        when (kind) {
            WatchAudioBridgeWire.Kind.CONTROL -> WatchAudioBridgeWire.control(plain)?.let(::handle)
            WatchAudioBridgeWire.Kind.EVENT -> handleEvent(plain.toString(Charsets.UTF_8))
            WatchAudioBridgeWire.Kind.AUDIO -> handleAudio(WatchAudioBridgeWire.samples(plain))
        }
    }

    private fun handle(control: WatchAudioBridgeWire.Control) {
        when (control) {
            is WatchAudioBridgeWire.Control.StartedControl -> {
                val info = control.started
                live = true
                hostOfflineTries = 0
                phase = if (audio.isPlaying) Phase.SPEAKING else Phase.LISTENING
                val rejoining = firstStartedAt != null
                if (!rejoining) {
                    firstStartedAt = now
                    liveSinceWallMs = System.currentTimeMillis()
                }
                log.note("bridgeStarted", mapOf(
                    "connection" to connection, "rejoin" to rejoining, "afterTapMs" to now - callStartedAt,
                    "afterStartMs" to startSentAt?.let { now - it }, "inputRate" to info.inputRate, "outputRate" to info.outputRate,
                    "briefingApplied" to info.briefingApplied, "greetingApplied" to info.greetingApplied,
                ))
                // The host's rates are fixed for wire version 1; other rates would play at the wrong speed.
                if (info.inputRate != WatchAudio.CAPTURE_RATE || info.outputRate != PLAYBACK_RATE) {
                    finish(L10n.tr("Hermes sends GPT-Live's audio in a format this Watch build can't play. Update Conduit and the conduit_push plugin together."))
                    return
                }
                // A call from Hermes: the model says what it called about. Once, never on a rejoin, and only when the host didn't already.
                val opening = openingTurn
                if (opening != null && WatchAudioBridgeWire.sendsOpeningTurn(rejoining, info)) pendingSends.add(0, Pending(opening, GPTLiveProtocol.Channel.SPEAKABLE, null))
                flushPending()
            }
            is WatchAudioBridgeWire.Control.Ended -> {
                log.note("bridgeEnded", mapOf("reason" to control.reason, "asked" to (endRequestedAt != null), "connection" to connection))
                // GPT-Live or the host ended the session: a fresh one, seeded with the conversation, unless the host said why it can't.
                if (endRequestedAt != null) finish(null) else connectionLost(null, true)
            }
            is WatchAudioBridgeWire.Control.Error -> {
                log.note("bridgeHostError", mapOf("code" to control.code, "message" to control.message.take(200), "live" to live))
                val text = control.message.ifEmpty { L10n.tr("GPT-Live failed on Hermes.") }
                if (control.code == "unreachable" || control.code == "failed") {
                    // Worth another try once it was working; "ended" follows.
                    if (firstStartedAt == null) hostError = text
                } else {
                    hostError = text
                }
                if (!live && firstStartedAt == null) finish(text)
            }
        }
        publish()
    }

    // endregion

    // region GPT-Live's events

    private fun handleEvent(text: String) {
        when (val event = GPTLiveProtocol.decode(text) ?: return) {
            is GPTLiveProtocol.ServerEvent.InputTranscript -> {
                userLine += event.text
                caption = userLine.takeLast(120)
            }
            is GPTLiveProtocol.ServerEvent.OutputTranscript -> {
                assistantLine += event.text
                caption = assistantLine.takeLast(120)
            }
            is GPTLiveProtocol.ServerEvent.TurnDone -> {
                val words = event.transcript.trim()
                if (event.role == "user") userLine = "" else assistantLine = ""
                if (words.isNotEmpty()) {
                    transcript += WatchVoiceWire.DirectTurn(
                        if (event.role == "user") WatchVoiceWire.DirectTurn.Role.USER else WatchVoiceWire.DirectTurn.Role.ASSISTANT,
                        words, System.currentTimeMillis(),
                    )
                    caption = words.takeLast(120)
                }
                if (event.role == "user" && goodbyeHeardAt == null && endRequestedAt == null && VoiceCommands.asksToEndCall(words, endPhrases)) {
                    goodbyeHeardAt = now
                    log.note("bridgeGoodbyeHeard")
                }
                if (event.role == "user" && openLineDelegated) {
                    openLineDelegated = false
                    if (words.isNotEmpty()) handledLines = transcript.size
                }
            }
            is GPTLiveProtocol.ServerEvent.Delegation -> delegated(event.id, event.text)
            is GPTLiveProtocol.ServerEvent.Error -> log.note("bridgeEngineError", mapOf("code" to event.code, "message" to event.message.take(200)))
            is GPTLiveProtocol.ServerEvent.SessionClosed -> log.note("bridgeSessionClosed", mapOf("reason" to event.reason))
            is GPTLiveProtocol.ServerEvent.SessionStarted -> Unit
        }
        publish()
    }

    // endregion

    // region Audio

    private fun handleAudio(samples: ShortArray) {
        if (samples.isEmpty() || endRequestedAt != null || phase == Phase.NEEDS_TAP) return
        val loud = samples.any { abs(it.toInt()) > SPEECH_PEAK }
        if (loud) lastLoudAt = now
        val quietFor = lastLoudAt?.let { now - it } ?: Long.MAX_VALUE
        if (suppressingTurn) {
            // Dropped until this stretch of speech ends.
            if (quietFor > SPEECH_GAP_MS) suppressingTurn = false
            return
        }
        if (modelSpeaking) {
            audio.enqueue(samples, PLAYBACK_RATE)
            if (quietFor > SPEECH_GAP_MS) modelSpeaking = false
            return
        }
        if (!loud) {
            leadIn += samples
            val keep = (PLAYBACK_RATE * LEAD_IN_S).toInt()
            if (leadIn.size > keep) leadIn = leadIn.copyOfRange(leadIn.size - keep, leadIn.size)
            return
        }
        // A new stretch of speech.
        modelSpeaking = true
        speechStretches++
        audio.enqueue(leadIn + samples, PLAYBACK_RATE)
        leadIn = ShortArray(0)
        phase = Phase.SPEAKING
        publish()
    }

    /** A stretch of speech whose audio stopped coming: with no chunks at all it would read as speaking, with the microphone held. */
    private fun endSilentStretchIfDue() {
        val loudAt = lastLoudAt ?: return
        if (!modelSpeaking || audio.isPlaying || now - loudAt < SILENT_STRETCH_MS) return
        modelSpeaking = false
        log.note("bridgeSpeechStopped", mapOf("quietMs" to now - loudAt))
        playbackDrained()
    }

    /** The user said an end phrase: once GPT-Live's goodbye has played (or none came), the call ends as if End was tapped. */
    private fun endAfterGoodbyeIfDue() {
        val heard = goodbyeHeardAt ?: return
        if (endRequestedAt != null) return
        val quietSince = maxOf(heard, lastLoudAt ?: 0, lastPlaybackEndedAt ?: 0)
        val quiet = !modelSpeaking && !audio.isPlaying && now - quietSince >= GOODBYE_GRACE_MS
        if (quiet || now - heard >= GOODBYE_TIMEOUT_MS) end()
    }

    private fun playbackDrained() {
        lastPlaybackEndedAt = now
        if (!callActive || phase != Phase.SPEAKING || modelSpeaking) return
        phase = Phase.LISTENING
        flushPending()
        publish()
    }

    private fun captured(samples: ShortArray, at: Long) {
        if (!callActive || !live || endRequestedAt != null || goodbyeHeardAt != null || muted || phase == Phase.NEEDS_TAP || isMicrophoneHeld) return
        activity.process(samples, WatchAudio.CAPTURE_RATE, at)
        pending += samples
    }

    /** Half duplex: GPT-Live's voice from the speaker must not read as the user. The host sends silence meanwhile. */
    private val isMicrophoneHeld: Boolean
        get() = audio.isPlaying || modelSpeaking || lastPlaybackEndedAt?.let { now - it < ECHO_TAIL_MS } == true

    private fun flushUplink() {
        if (!live || endRequestedAt != null || pending.isEmpty()) return
        val samples = pending
        pending = ShortArray(0)
        chunksUp++
        if (!send(WatchAudioBridgeWire.pcm(samples), WatchAudioBridgeWire.Kind.AUDIO)) sendFailures++
    }

    private fun send(plain: ByteArray, kind: WatchAudioBridgeWire.Kind): Boolean {
        val socket = socket ?: return false
        val stream = stream ?: return false
        return try {
            socket.send(stream.seal(plain, kind))
        } catch (e: Exception) {
            log.note("bridgeSealFailed", mapOf("error" to e.message, "bytes" to plain.size))
            false
        }
    }

    private fun sendEvent(message: JsonObject) {
        send(GPTLiveProtocol.encode(message).toByteArray(Charsets.UTF_8), WatchAudioBridgeWire.Kind.EVENT)
    }

    private fun audioInterrupted(began: Boolean) {
        if (!callActive || endRequestedAt != null) return
        log.note("bridgeAudioInterruption", mapOf("began" to began))
        if (began) {
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
        try {
            audio.start(PLAYBACK_RATE)
            microphoneStopped = false
            if (endRequestedAt == null) phase = if (live) Phase.LISTENING else Phase.RECONNECTING
        } catch (e: Exception) {
            log.note("bridgeAudioRestartFailed", mapOf("error" to e.message))
        }
        publish()
    }

    // endregion

    // region Delegations

    /**
     * GPT-Live handed work to the app: a job through the grant, the user's
     * words into a running job, a chat found or read, the end of the call, or
     * a web lookup when the user allows no jobs from the watch.
     */
    private fun delegated(id: String, itemText: String) {
        // Once a goodbye is heard the call is closing: no new work starts, as on the phone.
        if (!seenDelegations.add(id) || endRequestedAt != null || goodbyeHeardAt != null) return
        delegations++
        delegationConnection[id] = connection
        val lines = transcript.mapIndexed { index, turn -> WatchBridgeDelegation.Line(turn.role, turn.text, index < handledLines) }.toMutableList()
        val open = userLine.trim()
        if (open.isNotEmpty()) lines += WatchBridgeDelegation.Line(WatchVoiceWire.DirectTurn.Role.USER, open, false)
        handledLines = transcript.size
        openLineDelegated = open.isNotEmpty()
        delegationUserLines[id] = lines.count { it.role == WatchVoiceWire.DirectTurn.Role.USER }
        val request = WatchBridgeDelegation.request(itemText, lines)
        val userWords = lines.filter { it.role == WatchVoiceWire.DirectTurn.Role.USER && !it.handled }.joinToString(" ") { it.text }
        val ownWords = if (itemText.trim().isEmpty()) userWords else itemText
        log.note("bridgeDelegation", mapOf("hasText" to itemText.isNotEmpty(), "requestChars" to request.length, "jobs" to (relay?.hasJobs == true)))
        // "End call:" (or the user's own words asking to end): the call ends once the goodbye has played. Never a job for Hermes.
        if (VoiceCommands.isEndCallMarker(ownWords) || VoiceCommands.asksToEndCall(ownWords, endPhrases)) {
            if (endPhrases.isNotEmpty()) {
                goodbyeHeardAt = now
                log.note("bridgeEndCallDelegated")
            }
            return
        }
        if (request.isEmpty()) return answer(id, WatchBridgeDelegation.noRequest, GPTLiveProtocol.Channel.SPEAKABLE)
        val relay = relay ?: return answer(id, WatchBridgeDelegation.notStarted("the Watch has no way to reach Hermes for this call."), GPTLiveProtocol.Channel.SPEAKABLE)
        // "Find chat: GPU setup" and "Read chat: 2": the user's earlier chats, found and read on the host through the grant.
        VoiceChatToolText.findChatMarker(ownWords)?.let { query ->
            runChatTool(id, WatchToolAnswer.FIND_CHAT, WatchBridgeDelegation.clipped(query.ifEmpty { userWords }, 300), relay)
            return
        }
        VoiceChatToolText.readChatMarker(ownWords)?.let { number ->
            runChatTool(id, WatchToolAnswer.READ_CHAT, number, relay)
            return
        }
        if (VoiceChatToolText.openChatMarker(ownWords) != null) return answer(id, WatchToolAnswer.DELEGATED_CANT_OPEN, GPTLiveProtocol.Channel.COMMENTARY)
        // "Job 2: make it Alex" puts the user's words into job 2 while it runs. A number with no job is just new work.
        WatchBridgeDelegation.jobMarker(ownWords)?.let { (number, rest) ->
            val jobId = jobNumbers[number]
            if (jobId != null) {
                val words = WatchBridgeDelegation.followUpWords(userWords, rest)
                if (words.isEmpty()) return answer(id, WatchBridgeDelegation.noRequest, GPTLiveProtocol.Channel.SPEAKABLE)
                followUp(id, jobId, words, relay)
                return
            }
            // A bare "Job 9:" asks for nothing, as on the phone.
            val marked = if (rest.isEmpty()) "" else WatchBridgeDelegation.request(rest, lines)
            if (marked.isEmpty()) return answer(id, WatchBridgeDelegation.noRequest, GPTLiveProtocol.Channel.SPEAKABLE)
            startNewWork(id, marked, rest, relay)
            return
        }
        startNewWork(id, request, ownWords, relay)
    }

    private fun startNewWork(id: String, request: String, ownWords: String, relay: WatchToolRelayClient) {
        val tool = if (relay.hasJobs) WatchJobAnswer.START_JOB else WatchToolAnswer.WEB_SEARCH
        val speak = GPTLiveProtocol.Channel.SPEAKABLE
        if (tool !in relay.tools) return answer(id, WatchBridgeDelegation.notStarted("jobs from the Watch are off in Conduit's Watch settings."), speak)
        // The grant's half hour or its calls ran out. Renewing it wouldn't keep the call: the audio bridge belongs to the grant.
        if (!relay.canRun(tool)) return answer(id, WatchBridgeDelegation.notStarted(WatchBridgeDelegation.GRANT_RAN_OUT), speak)
        if (!relay.hasJobs) {
            lookUp(id, WatchBridgeDelegation.clipped(ownWords, 300), relay)
            return
        }
        // Too few calls left to follow a new job's news to its result.
        if (relay.callsLeft < CALLS_FOR_NEW_JOB) return answer(id, WatchBridgeDelegation.notStarted(WatchBridgeDelegation.GRANT_NEARLY_OUT), speak)
        if (relayJobsStarted >= relay.maxJobs) {
            return answer(id, WatchBridgeDelegation.notStarted("this call has started ${relay.maxJobs} jobs, the most the user allows per call. They can raise it in Conduit's Watch settings."), speak)
        }
        val (arguments, refusal) = startArguments(request, relay)
        if (arguments == null) return answer(id, WatchBridgeDelegation.notStarted(refusal), speak)
        startJob(id, arguments, relay)
    }

    /** A delegation's start_job: "for Fam, …" runs on Fam where the host runs jobs on the user's other profiles. */
    private fun startArguments(request: String, relay: WatchToolRelayClient): Pair<JsonObject?, String> {
        val task = WatchBridgeDelegation.removingQuickMarker(request)
        return when (val route = relay.jobRoute(task, null)) {
            null -> buildJsonObject { put("instructions", task) } to ""
            is WatchJobProfiles.RelayRoute.Relay -> buildJsonObject {
                put("instructions", route.instructions)
                route.profile?.let { put("profile", it) }
            } to ""
            is WatchJobProfiles.RelayRoute.ViaPhone -> null to WatchJobProfiles.viaPhoneReply(route.label)
            // Only a spoken profile can be unknown; a delegation names none.
            is WatchJobProfiles.RelayRoute.Unknown -> null to WatchJobProfiles.unknownProfileReply(route.name)
        }
    }

    private fun startJob(delegationId: String, arguments: JsonObject, relay: WatchToolRelayClient) {
        val id = callId
        val sentAt = now
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.START_JOB, arguments)
            if (callId != id || !callActive) return@launch
            val fields = mutableMapOf<String, Any?>("outcome" to outcome.logLabel, "ms" to now - sentAt)
            when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> {
                    val result = WatchJobAnswer.result(outcome.body)
                    val status = result["status"].orEmpty()
                    fields["status"] = status.ifEmpty { if (result["error"] == null) "" else "error" }
                    if (status == "started" || status == "accepted") {
                        relayJobsStarted++
                        var number: Int? = null
                        result["job_id"]?.let { jobId ->
                            jobLog.started(jobId, result["title"].orEmpty())
                            jobDelegations[jobId] = delegationId
                            // Numbered whatever the host: on an older plugin a correction then hears that it needs an update.
                            val next = jobNumbers.size + 1
                            jobNumbers[next] = jobId
                            number = next
                            runningJobs[jobId] = jobTitle(result["title"]) to "running"
                        }
                        answer(delegationId, WatchBridgeDelegation.working(result["title"] ?: WatchVoiceWire.DirectJobLog.DEFAULT_TITLE, number), GPTLiveProtocol.Channel.COMMENTARY)
                        if (number != null) sendJobStatus()
                        followJobs()
                    } else {
                        answer(delegationId, WatchBridgeDelegation.notStarted(result["message"] ?: result["error"] ?: "Hermes didn't take the job."), GPTLiveProtocol.Channel.SPEAKABLE)
                    }
                }
                WatchToolRelayClient.Outcome.TimedOut -> {
                    // Hermes has it: it may be running.
                    relayJobsStarted++
                    answer(delegationId, WatchJobAnswer.accepted["message"].orEmpty(), GPTLiveProtocol.Channel.COMMENTARY)
                    followJobs()
                }
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    fields["reason"] = outcome.reason
                    when {
                        WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, outcome.sent) ->
                            answer(delegationId, WatchBridgeDelegation.notStarted(WatchBridgeDelegation.GRANT_RAN_OUT), GPTLiveProtocol.Channel.SPEAKABLE)
                        outcome.sent -> {
                            relayJobsStarted++
                            followJobs()
                            answer(delegationId, WatchBridgeDelegation.relay("Hermes didn't confirm the job. Tell the user it may or may not have started."), GPTLiveProtocol.Channel.SPEAKABLE)
                        }
                        else -> answer(delegationId, WatchBridgeDelegation.notStarted("Hermes couldn't be reached from the Watch."), GPTLiveProtocol.Channel.SPEAKABLE)
                    }
                }
            }
            log.note("bridgeJobStart", fields)
        }
    }

    /** A correction to a job this call started, into the job on the host. */
    private fun followUp(delegationId: String, jobId: String, words: String, relay: WatchToolRelayClient) {
        followUps++
        val speak = GPTLiveProtocol.Channel.SPEAKABLE
        if (WatchJobAnswer.INTERRUPT_JOB !in relay.tools) return answer(delegationId, WatchBridgeDelegation.followUpsUnavailable, speak)
        if (!relay.canRun(WatchJobAnswer.INTERRUPT_JOB)) return answer(delegationId, WatchBridgeDelegation.relay("Hermes didn't get that (${WatchBridgeDelegation.grantRanOutClause})"), speak)
        val arguments = WatchJobAnswer.arguments(WatchJobAnswer.INTERRUPT_JOB, mapOf("job_id" to jobId, "message" to words))
            ?: return answer(delegationId, WatchBridgeDelegation.noRequest, speak)
        val id = callId
        val sentAt = now
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.INTERRUPT_JOB, arguments)
            if (callId != id || !callActive) return@launch
            val result = when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> WatchJobAnswer.FollowUp.of(outcome.body)
                WatchToolRelayClient.Outcome.TimedOut -> WatchJobAnswer.FollowUp.Failed("it took too long to answer")
                is WatchToolRelayClient.Outcome.Unavailable -> WatchJobAnswer.FollowUp.Failed(
                    if (WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, outcome.sent)) WatchBridgeDelegation.grantRanOutClause
                    else if (outcome.sent) "Hermes didn't confirm it got the words" else "Hermes couldn't be reached from the Watch",
                )
            }
            val (text, speakable) = WatchBridgeDelegation.followUpReply(result)
            log.note("bridgeFollowUp", mapOf("outcome" to outcome.logLabel, "result" to result.javaClass.simpleName, "ms" to now - sentAt))
            answer(delegationId, text, if (speakable) GPTLiveProtocol.Channel.SPEAKABLE else GPTLiveProtocol.Channel.COMMENTARY)
        }
    }

    /** A chat search or read through the grant, answered on the delegation as quiet context, as the phone answers it. */
    private fun runChatTool(delegationId: String, name: String, query: String, relay: WatchToolRelayClient) {
        val failed = { reason: String -> if (name == WatchToolAnswer.FIND_CHAT) VoiceChatToolText.delegatedSearchFailed(reason) else VoiceChatToolText.delegatedReadFailed(reason) }
        val quiet = GPTLiveProtocol.Channel.COMMENTARY
        if (query.isEmpty()) return answer(delegationId, failed(if (name == WatchToolAnswer.FIND_CHAT) "it had no words to search for." else "it had no chat number."), quiet)
        if (name !in relay.tools) return answer(delegationId, failed("the Hermes host's Conduit plugin needs an update (0.18) for chats on the Watch."), quiet)
        if (!relay.canRun(name)) return answer(delegationId, failed(WatchBridgeDelegation.GRANT_RAN_OUT), quiet)
        val id = callId
        val sentAt = now
        scope.launch {
            val outcome = relay.run(name, query)
            if (callId != id || !callActive) return@launch
            val text = when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> WatchToolAnswer.delegatedChatReply(name, outcome.body)
                WatchToolRelayClient.Outcome.TimedOut -> failed("that took too long.")
                is WatchToolRelayClient.Outcome.Unavailable -> failed(
                    if (WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, outcome.sent)) WatchBridgeDelegation.GRANT_RAN_OUT
                    else if (outcome.sent) "Hermes didn't answer." else "Hermes couldn't be reached from the Watch.",
                )
            }
            log.note("bridgeChat", mapOf("tool" to name, "outcome" to outcome.logLabel, "ms" to now - sentAt))
            answer(delegationId, text, quiet)
        }
    }

    private fun lookUp(delegationId: String, query: String, relay: WatchToolRelayClient) {
        val id = callId
        val sentAt = now
        lookupsInstead++
        scope.launch {
            val outcome = relay.run(WatchToolAnswer.WEB_SEARCH, query)
            if (callId != id || !callActive) return@launch
            val text = when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> WatchToolAnswer.fallbackText(WatchToolAnswer.result(WatchToolAnswer.WEB_SEARCH, outcome.body))
                    ?: WatchBridgeDelegation.relay("The lookup found nothing.")
                WatchToolRelayClient.Outcome.TimedOut -> WatchBridgeDelegation.relay("The lookup took too long.")
                is WatchToolRelayClient.Outcome.Unavailable -> when {
                    WatchBridgeDelegation.grantRanOut(outcome.reason, outcome.grantGone, outcome.sent) -> WatchBridgeDelegation.notStarted(WatchBridgeDelegation.GRANT_RAN_OUT)
                    outcome.sent -> WatchBridgeDelegation.relay("The lookup got no answer from Hermes.")
                    else -> WatchBridgeDelegation.relay("Hermes couldn't be reached from the Watch for the lookup.")
                }
            }
            log.note("bridgeLookup", mapOf("outcome" to outcome.logLabel, "ms" to now - sentAt))
            answer(delegationId, text, GPTLiveProtocol.Channel.SPEAKABLE)
        }
    }

    /** Quiet notes go at once; anything GPT-Live should say waits for a quiet moment, as on the phone. */
    private fun answer(delegationId: String?, text: String, channel: GPTLiveProtocol.Channel) {
        if (text.isEmpty()) return
        pendingSends += Pending(text, channel, delegationId)
        flushPending()
    }

    private fun flushPending() {
        if (!live || endRequestedAt != null || pendingSends.isEmpty()) return
        val quiet = isQuiet
        val kept = mutableListOf<Pending>()
        for (item in pendingSends) {
            if (item.channel != GPTLiveProtocol.Channel.COMMENTARY && !quiet) {
                kept += item
                continue
            }
            // A rejoined session doesn't know the old one's delegations.
            val delegationId = item.delegationId?.takeIf { delegationConnection[it] == connection }
            var text = item.text
            val asked = item.delegationId
            if (item.channel == GPTLiveProtocol.Channel.SPEAKABLE && asked != null) {
                if (userSpokeAfter(asked)) text = WatchBridgeDelegation.RESULT_AFTER_USER_NOTE + text
                // Only its first result waited on the user, as on the phone.
                delegationUserLines.remove(asked)
            }
            GPTLiveProtocol.contextAppendMessages(text, item.channel, delegationId).forEach(::sendEvent)
        }
        pendingSends.clear()
        pendingSends += kept
    }

    private fun userSpokeAfter(delegationId: String): Boolean {
        val known = delegationUserLines[delegationId]?.takeIf { it > 0 } ?: return false
        return transcript.count { it.role == WatchVoiceWire.DirectTurn.Role.USER } > known
    }

    private val isQuiet: Boolean
        get() {
            if (modelSpeaking || audio.isPlaying || userLine.isNotEmpty()) return false
            activity.lastVoicedAtMs?.let { if (now - it < USER_QUIET_MS) return false }
            lastPlaybackEndedAt?.let { if (now - it < MODEL_QUIET_MS) return false }
            return true
        }

    // endregion

    // region Jobs

    private fun followJobs() {
        followingJobs = true
        nextNewsAt = now
    }

    /** While jobs started through the relay may run, asks Hermes for their news: one ask at a time. */
    private fun fetchJobNewsIfDue() {
        val relay = relay ?: return
        if (!followingJobs || newsInFlight || endRequestedAt != null || now < nextNewsAt) return
        // Job news stops before it spends the calls an approval on screen needs; job results still come as notifications.
        if (relay.isGone || relay.expiresWithin(WatchToolRelayClient.EXPIRY_MARGIN_MS) || relay.callsLeft <= CALLS_KEPT_FOR_APPROVALS) {
            stopFollowingJobs(if (relay.isGone) "grantGone" else "callsOrTimeLow")
            return
        }
        newsInFlight = true
        val id = callId
        scope.launch {
            val outcome = relay.run(WatchJobAnswer.JOB_NEWS, buildJsonObject { put("wait_s", JOB_NEWS_WAIT_S) })
            if (callId != id || !callActive) return@launch
            newsInFlight = false
            // News stopped while this was out: its cards can't be answered.
            if (!followingJobs) return@launch
            when (outcome) {
                is WatchToolRelayClient.Outcome.Answered -> {
                    val news = WatchJobAnswer.news(outcome.body, relay.grantId)
                    if (news == null) {
                        nextNewsAt = now + newsPause(JOB_NEWS_PAUSE_MS, relay)
                        return@launch
                    }
                    jobNews(news)
                    nextNewsAt = now + if (news.items.isEmpty() && !news.more) newsPause(JOB_NEWS_PAUSE_MS, relay) else JOB_NEWS_RETRY_MS
                }
                WatchToolRelayClient.Outcome.TimedOut -> nextNewsAt = now + newsPause(JOB_NEWS_RETRY_MS, relay)
                is WatchToolRelayClient.Outcome.Unavailable -> {
                    log.note("bridgeJobNewsFailed", mapOf("reason" to outcome.reason, "grantGone" to outcome.grantGone))
                    if (outcome.grantGone) stopFollowingJobs("grantGone")
                    nextNewsAt = now + newsPause(JOB_NEWS_PAUSE_MS, relay)
                }
            }
        }
    }

    private fun jobTitle(title: String?) = title?.takeIf { it.isNotEmpty() } ?: WatchVoiceWire.DirectJobLog.DEFAULT_TITLE

    /** The phone's job status context: GPT-Live answers "how's my job going?" from it instead of delegating or guessing. */
    private fun sendJobStatus() {
        val jobs = jobNumbers.mapNotNull { (number, jobId) -> runningJobs[jobId]?.let { (title, status) -> Triple(number, title, status) } }
        answer(null, WatchBridgeDelegation.statusContext(jobs), GPTLiveProtocol.Channel.COMMENTARY)
    }

    private fun stopFollowingJobs(reason: String) {
        if (!followingJobs) return
        followingJobs = false
        relayJobsRunning = 0
        log.note("bridgeJobNewsStopped", mapOf("reason" to reason, "callsLeft" to relay?.callsLeft))
        publish()
    }

    /** At least [baseMs], and spread so the grant's calls last as long as the grant does. */
    private fun newsPause(baseMs: Long, relay: WatchToolRelayClient): Long {
        val spare = relay.callsLeft - CALLS_KEPT_FOR_APPROVALS
        val expiresAt = relay.expiresAtMs ?: return baseMs
        if (spare <= 0) return baseMs
        return maxOf(baseMs, (expiresAt - System.currentTimeMillis()) / spare)
    }

    private fun jobNews(news: WatchJobAnswer.News) {
        relayJobsRunning = news.running
        val open = news.openApprovals.map { "${it.jobId}\n${it.requestId}" }.toSet()
        approvals.removeAll { "${it.jobId}\n${it.requestId}" !in open }
        var shown = 0
        var statusChanged = false
        for (item in news.items) {
            jobLog.heard(item.jobId, item.title, item.sessionId)
            if (item.status in SETTLED) {
                statusChanged = runningJobs.remove(item.jobId) != null || statusChanged
            } else {
                val known = runningJobs[item.jobId]
                if (known != null) {
                    if (known.second != item.status) {
                        runningJobs[item.jobId] = known.first to item.status
                        statusChanged = true
                    }
                } else {
                    // A start whose answer was lost: numbered and counted from its first news, so GPT-Live hears of it.
                    if (item.jobId !in jobNumbers.values) jobNumbers[jobNumbers.size + 1] = item.jobId
                    runningJobs[item.jobId] = jobTitle(item.title) to item.status
                    statusChanged = true
                }
            }
            item.approval?.let { approval ->
                approvals.removeAll { it.jobId == item.jobId }
                approvals += approval
                approvalsShown++
                shown++
            }
            // GPT-Live can't answer an approval here: the user taps it.
            val text = WatchJobAnswer.notice(item, voiceApprovals = false) ?: continue
            val delegationId = if (item.status in SETTLED) jobDelegations.remove(item.jobId) else null
            pendingSends += Pending(text, GPTLiveProtocol.Channel.SPEAKABLE, delegationId)
        }
        if (statusChanged) sendJobStatus()
        if (shown > 0) vibrate()
        if (news.items.isNotEmpty()) {
            log.note("bridgeJobNews", mapOf("statuses" to news.items.map { it.status }, "resultChars" to news.items.map { it.result?.length ?: 0 }, "running" to news.running, "approvalsOnScreen" to approvals.size))
        }
        if (news.running == 0 && !news.more && approvals.isEmpty()) followingJobs = false
        flushPending()
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
        val sent = startSentAt
        if (!live && sent != null && now - sent > STARTED_WAIT_MS) {
            log.note("bridgeStartTimedOut", mapOf("connection" to connection))
            startSentAt = null
            connectionLost(null, true)
            return
        }
        endRequestedAt?.let {
            if (now - it > END_TIMEOUT_MS) {
                finish(null)
                return
            }
        }
        endAfterGoodbyeIfDue()
        endSilentStretchIfDue()
        fetchJobNewsIfDue()
        flushPending()
        publish()
    }

    private fun reset() {
        timers.forEach { it.cancel() }
        timers.clear()
        callId = Random.nextLong(1, 0xFFFF_FFFFL)
        callUuid = UUID.randomUUID().toString()
        callStartedWallMs = System.currentTimeMillis()
        callStartedAt = now
        endReason = null
        liveSinceWallMs = null
        endedWallMs = null
        muted = false
        caption = null
        relayJobsRunning = 0
        sessionRequests = 0
        bridgeUrl = null
        grantId = null
        grantRoot = null
        watchKey = null
        briefing = null
        greeting = null
        openingTurn = null
        voice = null
        relay?.close()
        relay = null
        closeSocket()
        stream = null
        connection = 0
        streamsOpened = 0
        live = false
        socketOpenedAt = null
        firstStartedAt = null
        hostOfflineTries = 0
        rejoins = 0
        drops = 0
        hostError = null
        unopened = 0
        microphoneStopped = false
        pending = ShortArray(0)
        chunksUp = 0
        chunksDropped = 0
        sendFailures = 0
        activity.reset()
        lastPlaybackEndedAt = null
        modelSpeaking = false
        suppressingTurn = false
        lastLoudAt = null
        leadIn = ShortArray(0)
        speechStretches = 0
        transcript.clear()
        handledLines = 0
        userLine = ""
        assistantLine = ""
        endPhrases = emptyList()
        goodbyeHeardAt = null
        openLineDelegated = false
        endRequestedAt = null
        pendingSends.clear()
        seenDelegations.clear()
        delegationConnection.clear()
        delegationUserLines.clear()
        jobDelegations.clear()
        runningJobs.clear()
        jobNumbers.clear()
        delegations = 0
        relayJobsStarted = 0
        jobLog.reset()
        lookupsInstead = 0
        followUps = 0
        followingJobs = false
        newsInFlight = false
        nextNewsAt = 0
        approvals.clear()
        approvalsShown = 0
    }

    private fun finish(reason: String?) {
        if (!callActive) return
        timers.forEach { it.cancel() }
        timers.clear()
        closeSocket()
        stream = null
        live = false
        // Ends the grant on the relay and so on Hermes, which ends the bridge. Jobs still running keep running in Hermes.
        relay?.close()
        relay = null
        approvals.clear()
        audio.stop()
        CallService.stop(context)
        phase = Phase.ENDED
        endReason = reason
        endedWallMs = System.currentTimeMillis()
        val saved = WatchVoiceWire.DirectTranscript.capped(transcript.filter { it.text.isNotBlank() })
        val queued = link.queue(Message.DirectEnd(callId, WatchVoiceWire.DirectTranscript(
            callUuid, callStartedWallMs, System.currentTimeMillis(), saved, ENGINE, jobLog.jobs.ifEmpty { null },
        )))
        log.note("bridgeCallSummary", mapOf(
            "callID" to callId, "engine" to ENGINE, "reason" to reason, "durationS" to (now - callStartedAt) / 1000,
            "firstStartedMs" to firstStartedAt?.let { it - callStartedAt }, "sessionRequests" to sessionRequests,
            "streamsOpened" to streamsOpened, "drops" to drops, "rejoins" to rejoins, "turns" to speechStretches,
            "delegations" to delegations, "followUps" to followUps, "jobsStarted" to relayJobsStarted, "lookupsInstead" to lookupsInstead,
            "approvalsShown" to approvalsShown, "unsentAnswers" to pendingSends.size, "chunksUp" to chunksUp, "chunksDropped" to chunksDropped,
            "sendFailures" to sendFailures, "unopened" to unopened, "transcriptLines" to saved.size, "transcriptQueued" to queued,
        ))
        publish()
    }

    // endregion

    private fun vibrate() {
        runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
    }

    private fun publish() {
        _state.value = WatchCall.State(
            phase = when (phase) {
                Phase.IDLE -> WatchCall.Phase.IDLE
                Phase.PREPARING -> WatchCall.Phase.PREPARING
                Phase.CONNECTING -> WatchCall.Phase.CONNECTING
                Phase.LISTENING -> WatchCall.Phase.LISTENING
                Phase.SPEAKING -> WatchCall.Phase.SPEAKING
                Phase.RECONNECTING -> WatchCall.Phase.RECONNECTING
                Phase.NEEDS_TAP -> WatchCall.Phase.NEEDS_TAP
                Phase.ENDING -> WatchCall.Phase.ENDING
                Phase.ENDED -> WatchCall.Phase.ENDED
            },
            muted = muted,
            caption = caption,
            transcript = transcript.map { WatchCall.Line(it.role == WatchVoiceWire.DirectTurn.Role.USER, it.text) },
            jobApproval = approvals.firstOrNull(),
            jobsRunning = relayJobsRunning,
            liveSinceMs = liveSinceWallMs,
            endedAtMs = endedWallMs,
            endReason = endReason?.takeIf { !WatchVoiceCall.isNormalEnd(it) },
            chatTitle = "GPT-Live",
        )
    }

    companion object {
        const val ENGINE = WatchAudioBridgeWire.GPT_LIVE
        const val PLAYBACK_RATE = 24_000
        const val SESSION_WAIT_MS = 30_000L
        const val SESSION_REQUEST_TIMEOUT_MS = 30_000L
        const val ECHO_TAIL_MS = 350L
        /** GPT-Live's start on the host: its WebRTC offer (ICE gathering included) and the connection after the answer, with one retry. */
        const val STARTED_WAIT_MS = 45_000L
        /** The relay turns the watch away until the host's bridge is there. */
        const val HOST_OFFLINE_RETRIES = 8
        const val MAX_REJOINS = 3
        /** Model audio louder than this (PCM16 peak) is speech. */
        const val SPEECH_PEAK = 600
        /** This much quiet ends a stretch of GPT-Live's speech. */
        const val SPEECH_GAP_MS = 600L
        /** No loud audio for this long ends a stretch, chunks arriving or not. */
        const val SILENT_STRETCH_MS = 2_000L
        /** Quiet audio kept before speech starts, so its first sound isn't cut. */
        const val LEAD_IN_S = 0.1
        const val USER_QUIET_MS = 2_000L
        const val MODEL_QUIET_MS = 1_000L
        const val JOB_NEWS_WAIT_S = 15
        const val JOB_NEWS_PAUSE_MS = 15_000L
        const val JOB_NEWS_RETRY_MS = 2_000L
        /** A new job starts only with this many grant calls left, so its news can follow it to a result. */
        const val CALLS_FOR_NEW_JOB = 10
        /** Grant calls job news leaves for answering approvals. */
        const val CALLS_KEPT_FOR_APPROVALS = 2
        /** Conversation a rejoined session is seeded with. */
        const val HISTORY_TURNS = 40
        const val END_TIMEOUT_MS = 4_000L
        const val GOODBYE_GRACE_MS = 1_500L
        const val GOODBYE_TIMEOUT_MS = 10_000L
        private val SETTLED = setOf("finished", "failed", "cancelled")
    }
}
