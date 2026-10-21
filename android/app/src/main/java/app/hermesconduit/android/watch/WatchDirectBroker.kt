package app.hermesconduit.android.watch

import android.content.Context
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.calls.HermesCalls
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.live.GPTLiveBilling
import app.hermesconduit.android.core.live.GPTLiveClient
import app.hermesconduit.android.core.live.GeminiLiveProtocol
import app.hermesconduit.android.core.live.GeminiLiveSearchMode
import app.hermesconduit.android.core.live.GeminiLiveSearchSource
import app.hermesconduit.android.core.live.GeminiLiveToken
import app.hermesconduit.android.core.live.GrokLiveAvailability
import app.hermesconduit.android.core.live.GrokLiveProtocol
import app.hermesconduit.android.core.live.LiveDashboardRequest
import app.hermesconduit.android.core.live.LiveVoiceStyle
import app.hermesconduit.android.core.live.liveErrorMessage
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.HermesCallOpening
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceTranscriptOutbox
import app.hermesconduit.android.model.VoiceTranscriptSaveRequest
import app.hermesconduit.android.model.VoiceTranscriptTurn
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.live.GPTLiveConversationController
import app.hermesconduit.android.ui.voice.live.GeminiLiveConversationController
import app.hermesconduit.android.ui.voice.live.GeminiLiveToolBridge
import app.hermesconduit.android.ui.voice.live.LiveVoiceCalls
import app.hermesconduit.wearwire.WatchAudioBridgeWire
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchDirectWire
import app.hermesconduit.wearwire.WatchJobAnswer
import app.hermesconduit.wearwire.WatchLiveToken
import app.hermesconduit.wearwire.WatchToolAnswer
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WatchVoiceWire.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import java.util.TimeZone

/**
 * The phone's part in a watch call (port of the iOS `WatchDirectBroker`,
 * designs/apple-watch-voice-direct.md and apple-watch-gpt-live.md). The
 * watch runs the session; this side brokers it and runs its tools:
 * - builds the setup a Gemini Live (or Grok Live) call on this phone gets —
 *   instructions, persona, memory, functions, voice — and, for Gemini, a
 *   single-use token; a fresh token for each later connection;
 * - runs the watch's function calls through the phone's own tool bridge,
 *   answering start_job as soon as Hermes takes the job;
 * - answers the watch's polls while jobs run;
 * - asks Hermes for the call's tool grant (lookups, jobs per the user's
 *   setting, Gemini tokens, the host's audio bridge for Grok and GPT-Live),
 *   renews it and ends it with the call;
 * - for GPT-Live, builds the briefing and makes sure the host's WebRTC
 *   runtime is ready;
 * - saves the finished call in voice history;
 * - opens a call that answers a call from Hermes (its start's `ring`,
 *   designs/hermes-calls-watch.md) with what Hermes called about.
 * A call belongs to the Hermes connection (dashboard and profile) it began
 * on, kept across a restart in [WatchDirectCallLedger]; a switch ends it.
 * Nothing lasting goes to the watch: only single-use tokens, the call's
 * grant, the setup and tool results.
 */
class WatchDirectBroker private constructor(context: Context) {
    private val app = context.applicationContext
    private val hermes = app.hermes
    private val settings = app.appSettings
    private val hub by lazy { VoiceHub.get(app) }
    private val log = WatchPhoneCallLog.get(app)
    private val grantClient = WatchToolGrantClient(hermes)
    private val request: LiveDashboardRequest = { method, path, query, body, timeoutMs ->
        hermes.rest { api -> if (method == "GET") api.getJson(path, query, timeoutMs) else api.sendJson(method, path, body, query, timeoutMs) }
    }
    /** The host's watch bridge starts GPT-Live on the subscription only. */
    private val gptClient = GPTLiveClient({ hermes.profile.value }, { GPTLiveBilling.SUBSCRIPTION }, request)

    private var callId: Long? = null
    private var bridge: GeminiLiveToolBridge? = null
    private var preparing: Pair<Long, Deferred<Message>>? = null
    private var connection: WatchDirectConnection? = null
    private var lastHeardAtMs = 0L
    private var grantWatchdog: Job? = null
    private val ledger = WatchDirectCallLedger(
        read = { settings.getString(WatchDirectCallLedger.STORAGE_KEY, "").ifEmpty { null } },
        write = { settings.set(WatchDirectCallLedger.STORAGE_KEY, it) },
    )
    /** What the bridge gave after its call's answer had gone: sent with the next poll. */
    private var lateOutgoing = mutableListOf<GeminiLiveToolBridge.Outgoing>()
    /** The phone's jobs before each call began, to tell which ones it started. */
    private val jobsBefore = mutableMapOf<Long, Pair<Set<String>, Long>>()
    private var grantTools = listOf<String>()
    private var grantIds = mutableListOf<String>()
    private var grantJobTools = listOf<String>()
    private var grantLiveToken = false
    private var grantMaxJobs = 0
    private var grantJobOptions = mapOf<String, String>()
    private var grantJobProfiles = listOf<WatchVoiceWire.JobProfileName>()
    private var grantVoiceApprovals = false
    private var grantAudio = false
    private var lastGrantError: String? = null
    /** Start_job calls by call and id: one the watch sends again gets the first one's answer instead of a second job. */
    private val jobCalls = LinkedHashMap<String, Deferred<List<GeminiLiveToolBridge.Outgoing>>>()

    init {
        ledger.unreadableBytes?.let { log.note("watchDirectLedgerUnreadable", mapOf("bytes" to it)) }
    }

    /**
     * A watch call is being set up or served here: a call from Hermes
     * doesn't ring over it. One the watch hasn't been heard from in
     * [BUSY_WITHOUT_WORD_MS] (its end was lost) no longer counts.
     */
    val hasCall: Boolean get() = callId != null && System.currentTimeMillis() - lastHeardAtMs < BUSY_WITHOUT_WORD_MS

    /**
     * Answers one watch message. [waiting] is false for a queued message
     * (the watch answered the call itself meanwhile): nothing reads the reply.
     */
    suspend fun handle(message: Message, waiting: Boolean): Message? = when (message) {
        is Message.DirectStart -> start(message.callId, message.version) { prepareDirect(message.callId, message.ring) }
        is Message.BridgeStart -> when {
            message.version != WatchVoiceWire.VERSION || message.engine != WatchAudioBridgeWire.GPT_LIVE ->
                Message.CallRefused(message.callId, WatchVoiceStartFailure.versionMismatch)
            else -> start(message.callId, message.version) { prepareBridge(message.callId, message.ring) }
        }
        is Message.GrokStart -> start(message.callId, message.version) { prepareGrok(message.callId, message.ring) }
        is Message.CallsToken -> {
            // Calls from Hermes ring the watch too, while its voice can start (WatchCallVoices).
            HermesCalls.get(app).watchRing.tokenChanged(message.token, message.engine)
            null
        }
        is Message.DirectTokenRequest -> token(message.callId)
        is Message.DirectTool -> {
            heard(message.callId)
            Message.DirectToolResultIssued(message.callId, runTool(message.call, message.callId, waiting))
        }
        is Message.DirectToolCancel -> {
            heard(message.callId)
            if (message.callId == callId) bridge?.cancelCalls(message.ids)
            null
        }
        is Message.DirectPoll -> {
            heard(message.callId)
            Message.DirectToolResultIssued(message.callId, poll(message.callId))
        }
        is Message.DirectEnd -> {
            ended(message.callId, message.transcript)
            null
        }
        is Message.DirectGrant -> {
            heard(message.callId)
            renewGrant(message.callId, message.carryJobsFrom)
        }
        else -> null
    }

    /** Asked again while preparing (the watch's request timed out): the same answer. */
    private suspend fun start(id: Long, version: Int, prepare: suspend () -> Message): Message {
        if (version != WatchVoiceWire.VERSION) return Message.CallRefused(id, WatchVoiceStartFailure.versionMismatch)
        val task = preparing?.takeIf { it.first == id }?.second
            ?: hermes.scope.async(start = CoroutineStart.LAZY) { prepare() }.also { preparing = id to it }
        val answer = task.await()
        if (preparing?.first == id) preparing = null
        return answer
    }

    // region Connection

    /** The connection a watch call started now would belong to. */
    private val activeConnection: WatchDirectConnection
        get() = WatchDirectConnection(hermes.profile.value, hermes.connection.value?.id ?: "-")

    /** Reconnects to Hermes if the phone's suspension dropped the connection. True once connected. */
    private suspend fun connect(timeoutMs: Long): Boolean {
        if (!hermes.isSignedIn) return false
        if (hermes.link.value == LinkState.CONNECTED) return true
        if (hermes.gateway == null || hermes.link.value == LinkState.OFFLINE) hermes.reconnectNow()
        return withTimeoutOrNull(timeoutMs) { hermes.link.first { it == LinkState.CONNECTED } } != null
    }

    private fun prefs() = hub.preferences()

    /** The voice-job model settings for jobs the watch starts through the relay, as a phone job's session gets them. */
    private fun jobOptions(): Map<String, String> {
        val prefs = prefs()
        val options = linkedMapOf<String, String>()
        prefs.voiceJobModel?.takeIf { it.isNotEmpty() }?.let { model ->
            options["model"] = model
            prefs.voiceJobProvider?.takeIf { it.isNotEmpty() }?.let { options["provider"] = it }
        }
        prefs.voiceJobReasoningEffort?.takeIf { it.isNotEmpty() }?.let { options["reasoning_effort"] = it }
        return options
    }

    /** Every name the user's profiles go by, the call's own profile first (null), as "for Fam, …" resolves on the phone. */
    private fun jobProfileNames(callProfile: String = hermes.profile.value): List<WatchVoiceWire.JobProfileName> {
        val own = buildList {
            add(callProfile)
            if (callProfile == "default") add(L10n.tr("Default profile"))
        }
        val others = hub.knownProfiles.filter { !it.equals(callProfile, ignoreCase = true) }.map { name ->
            WatchVoiceWire.JobProfileName(name, listOf(name, name.replace('_', ' ')).distinct())
        }
        return listOf(WatchVoiceWire.JobProfileName(null, own)) + others
    }

    private fun saveCalls() = prefs().saveVoiceCalls != false

    // endregion

    // region Start

    private class PrepareError(val reason: String) : Exception(reason)

    private data class Plan(
        val systemInstruction: String,
        val functions: List<GeminiLiveProtocol.FunctionDeclaration>,
        val googleSearch: Boolean,
        val voice: String?,
        val openingPrompt: String?,
        val token: GeminiLiveToken?,
        val connection: WatchDirectConnection,
        val memoryIncluded: Boolean,
        val personalityIncluded: Boolean,
    )

    /** Setting up a call drops whatever another one left. */
    private fun beginPreparing(id: Long) {
        if (callId != null && callId != id) endCall()
        callId = id
        bridge = null
        lateOutgoing.clear()
        lastHeardAtMs = System.currentTimeMillis()
    }

    private suspend fun preparePhone(): WatchDirectConnection {
        if (!connect(20_000)) throw PrepareError(WatchVoiceStartFailure.hermesUnreachable)
        // The phone's microphone and the watch's would both feed Hermes.
        if (hub.liveCalls.isCallActive) throw PrepareError(WatchVoiceStartFailure.callRunning)
        return activeConnection
    }

    /** Builds a watch call's Gemini Live setup as the phone's Gemini Live call is built: the same search, memory and persona lookups. */
    private suspend fun directPlan(opening: HermesCallOpening?): Plan = coroutineScope {
        val connection = preparePhone()
        val prefs = prefs()
        val tokens = hub.liveCalls.tokenClient
        val search = async {
            val mode = GeminiLiveSearchMode.of(prefs.geminiLiveSearch)
            mode.resolved(hermesAvailable = mode == GeminiLiveSearchMode.AUTOMATIC && tokens.webSearchAvailable())
        }
        val memory = async { if (prefs.geminiLiveMemory == true) tokens.memoryContext() else null }
        val personality = async { if (prefs.geminiLivePersonality == true) tokens.personality() else null }
        val status = tokens.availability()
        noteVoice(WatchCallVoices.GEMINI_LIVE, status.isAvailable)
        if (!status.isAvailable) {
            search.cancel(); memory.cancel(); personality.cancel()
            throw PrepareError(status.userFacingReason ?: L10n.tr("Gemini Live is not available on this Hermes server."))
        }
        val source = search.await()
        val mem = memory.await()
        val persona = personality.await()
        val token = tokens.freshToken()
        val style = LiveVoiceStyle.of(prefs)
        // A switch while the lookups ran would mix two connections' setup.
        if (activeConnection != connection) throw PrepareError(WatchVoiceStartFailure.connectionChanged)
        Plan(
            systemInstruction = GeminiLiveConversationController.instructions(source, mem, persona, style.answerLength, asksFirst = false, onWatch = true) +
                (opening?.instructionBlock(delegation = false, canAnswer = false) ?: "") + style.instructions,
            functions = GeminiLiveToolBridge.watchDeclarations(webSearch = source == GeminiLiveSearchSource.HERMES, memoryRecall = mem?.canRecall == true, chats = true),
            googleSearch = source == GeminiLiveSearchSource.GOOGLE,
            voice = prefs.geminiLiveVoice,
            openingPrompt = opening?.watchOpeningTurn ?: style.openingPrompt,
            token = token,
            connection = connection,
            memoryIncluded = mem != null,
            personalityIncluded = persona != null,
        )
    }

    /** A watch Grok call's setup, as the phone's Grok Live call is built; xAI's session is on the host, reached through the grant. */
    private suspend fun grokPlan(opening: HermesCallOpening?): Plan = coroutineScope {
        val connection = preparePhone()
        val prefs = prefs()
        val tokens = hub.liveCalls.tokenClient
        val memory = async { if (prefs.grokLiveMemory == true) tokens.memoryContext() else null }
        val personality = async { if (prefs.grokLivePersonality == true) tokens.personality() else null }
        val status = hub.liveCalls.grokClient.availability()
        noteVoice(WatchCallVoices.GROK_LIVE, status.isAvailable)
        if (status !is GrokLiveAvailability.Available) {
            memory.cancel(); personality.cancel()
            throw PrepareError(status.userFacingReason ?: L10n.tr("Grok Live is not available on this Hermes server."))
        }
        val mem = memory.await()
        val persona = personality.await()
        val style = LiveVoiceStyle.of(prefs)
        if (activeConnection != connection) throw PrepareError(WatchVoiceStartFailure.connectionChanged)
        Plan(
            // xAI's own search, as on the phone.
            systemInstruction = GeminiLiveConversationController.instructions(GeminiLiveSearchSource.GROK, mem, persona, style.answerLength, asksFirst = false, onWatch = true) +
                GrokLiveProtocol.grokInstructions(System.currentTimeMillis(), TimeZone.getDefault()) +
                (opening?.instructionBlock(delegation = false, canAnswer = false) ?: "") + style.instructions,
            functions = GeminiLiveToolBridge.watchDeclarations(webSearch = false, memoryRecall = mem?.canRecall == true, chats = true),
            googleSearch = false,
            voice = status.voice,
            openingPrompt = opening?.watchOpeningTurn ?: style.openingPrompt,
            token = null,
            connection = connection,
            memoryIncluded = mem != null,
            personalityIncluded = persona != null,
        )
    }

    private suspend fun prepareDirect(id: Long, ring: String?): Message = prepareSetup(id, grok = false, ring)

    private suspend fun prepareGrok(id: Long, ring: String?): Message = prepareSetup(id, grok = true, ring)

    /**
     * What a watch call answering a call from Hermes opens with; null for
     * any other call, or a ring this phone no longer has (the call then opens
     * as any other).
     */
    private fun hermesCallOpening(ring: String?): HermesCallOpening? {
        ring ?: return null
        val calls = HermesCalls.get(app)
        return calls.answeredOnWatch(ring)?.let(calls::watchOpening)
    }

    /** A Gemini or Grok call: the setup, and a grant for its lookups, jobs and (Gemini) tokens or (Grok) the host's audio bridge. */
    private suspend fun prepareSetup(id: Long, grok: Boolean, ring: String?): Message {
        val kind = if (grok) "watchGrok" else "watchDirect"
        beginPreparing(id)
        hub.jobs.liveCallReadsReplies = true
        val startedAt = System.currentTimeMillis()
        val opening = hermesCallOpening(ring)
        log.note("${kind}Start", mapOf("callID" to id, "connected" to (hermes.link.value == LinkState.CONNECTED), "answersHermesCall" to (ring != null), "opening" to (opening != null)))
        return try {
            val plan = if (grok) grokPlan(opening) else directPlan(opening)
            if (callId != id) return Message.CallRefused(id, WatchVoiceStartFailure.ended)
            connection = plan.connection
            ledger.begin(id, plan.connection, saveCalls())
            jobsBefore[id] = hub.jobs.jobs.value.map { it.id }.toSet() to startedAt
            // Only the lookups and job tools the call declares, jobs only as the user allows.
            val declared = plan.functions.map { it.name }
            grantTools = grantable(declared.filter { it in WatchToolAnswer.tools })
            grantMaxJobs = jobsPerCall()
            grantJobTools = if (grantMaxJobs > 0) declared.filter { it in WatchJobAnswer.tools } else emptyList()
            grantJobOptions = jobOptions()
            grantJobProfiles = jobProfileNames()
            grantVoiceApprovals = settings.getBool(WatchJobSettings.VOICE_APPROVALS, false)
            grantLiveToken = !grok
            grantAudio = grok
            lastGrantError = null
            val grant = requestGrant(id, plan.connection.profile)
            if (callId != id) return Message.CallRefused(id, WatchVoiceStartFailure.ended)
            ledger.setGrant(grantScope(), id)
            if (activeConnection != plan.connection) throw PrepareError(WatchVoiceStartFailure.connectionChanged)
            if (grok && (grant?.audio == null || WatchAudioBridgeWire.GROK !in grant.audio!!.engines)) {
                throw PrepareError(lastGrantError?.let { L10n.tr("Hermes couldn't open Grok for the Watch: %s", it) }
                    ?: L10n.tr("Hermes couldn't open Grok for the Watch. Update the Conduit notifier plugin and the push relay."))
            }
            // The model answers approvals only when the user allowed it and the grant carries jobs.
            val functions = plan.functions + if (grant?.voiceApprovals == true) listOf(WatchJobAnswer.answerApprovalDeclaration) else emptyList()
            val (packed, bytes) = WatchDirectWire.DirectSetup.of(plan.systemInstruction, functions).compressed()
            val endPhrases = prefs().resolvedSpokenEndConversationPhrases
            val reply = if (grok) Message.GrokSessionIssued(id, WatchVoiceWire.GrokSession(packed, bytes, plan.voice, plan.openingPrompt, grant!!, endPhrases))
            else Message.DirectSessionIssued(id, WatchVoiceWire.DirectSession(
                WatchDirectWire.token(plan.token!!), packed, bytes, plan.googleSearch, plan.voice, plan.openingPrompt, grant, endPhrases,
            ))
            val replyBytes = WatchVoiceWire.encode(reply).size
            if (replyBytes > REPLY_LIMIT) throw PrepareError(L10n.tr("The call's briefing (memory and persona) is too large to send to the Watch."))
            log.note("${kind}Prepared", mapOf(
                "callID" to id, "ms" to System.currentTimeMillis() - startedAt, "setupBytes" to bytes, "compressedBytes" to packed.size,
                "replyBytes" to replyBytes, "functions" to functions.map { it.name }, "googleSearch" to plan.googleSearch,
                "memory" to plan.memoryIncluded, "personality" to plan.personalityIncluded, "toolGrant" to (grant != null),
            ))
            if (grok) endWhenGrantDoes(id, grant?.expiresAtMs)
            reply
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? PrepareError)?.reason ?: liveErrorMessage(e)
            log.note("${kind}PrepareFailed", mapOf("callID" to id, "ms" to System.currentTimeMillis() - startedAt, "error" to reason))
            if (callId == id) endCall()
            Message.CallRefused(id, reason)
        }
    }

    /**
     * A GPT-Live call on the watch: the phone's briefing, and a grant that
     * opens the host's audio bridge, with jobs for GPT-Live's delegations as
     * the user allows and web_search for when they don't.
     */
    private suspend fun prepareBridge(id: Long, ring: String?): Message {
        beginPreparing(id)
        val startedAt = System.currentTimeMillis()
        val opening = hermesCallOpening(ring)
        log.note("watchBridgeStart", mapOf(
            "callID" to id, "engine" to WatchAudioBridgeWire.GPT_LIVE, "connected" to (hermes.link.value == LinkState.CONNECTED),
            "answersHermesCall" to (ring != null), "opening" to (opening != null),
        ))
        return try {
            val connection = preparePhone()
            val prefs = prefs()
            val tokens = hub.liveCalls.tokenClient
            val (memory, personality, status) = coroutineScope {
                val memory = async { if (prefs.gptLiveMemory == true) tokens.memoryContext() else null }
                val personality = async { if (prefs.gptLivePersonality == true) tokens.personality() else null }
                val status = gptClient.availability(GPTLiveBilling.SUBSCRIPTION)
                Triple(memory.await(), personality.await(), status)
            }
            noteVoice(WatchCallVoices.GPT_LIVE, status.isAvailable)
            if (!status.isAvailable) throw PrepareError(status.userFacingReason ?: L10n.tr("GPT-Live is not available on this Hermes server."))
            if (callId != id) return Message.CallRefused(id, WatchVoiceStartFailure.ended)
            val chats = runCatching { PluginStatusRepository.of(hermes).status(force = false).reports(WatchToolAnswer.CHAT_CAPABILITY) }.getOrDefault(false)
            val style = LiveVoiceStyle.of(prefs)
            val briefing = GPTLiveConversationController.briefing(memory, personality, style.answerLength, onWatch = true, watchChats = chats) +
                (opening?.instructionBlock(delegation = true, canAnswer = false) ?: "") + style.instructions
            if (activeConnection != connection) throw PrepareError(WatchVoiceStartFailure.connectionChanged)
            this.connection = connection
            ledger.begin(id, connection, saveCalls())
            jobsBefore[id] = hub.jobs.jobs.value.map { it.id }.toSet() to startedAt
            // GPT-Live delegates its lookups and chats to the watch, which runs them all through the grant.
            grantTools = grantable(listOf(WatchToolAnswer.WEB_SEARCH) + WatchToolAnswer.chatTools.sorted())
            grantMaxJobs = jobsPerCall()
            grantJobTools = if (grantMaxJobs > 0) WatchJobAnswer.tools.sorted() else emptyList()
            grantJobOptions = jobOptions()
            grantJobProfiles = jobProfileNames()
            // GPT-Live never answers a job's approval: the user taps it.
            grantVoiceApprovals = false
            grantLiveToken = false
            grantAudio = true
            lastGrantError = null
            val grant = requestGrant(id, connection.profile)
            if (callId != id) return Message.CallRefused(id, WatchVoiceStartFailure.ended)
            ledger.setGrant(grantScope(), id)
            val audio = grant?.audio ?: throw PrepareError(lastGrantError?.let { L10n.tr("Hermes couldn't open GPT-Live for the Watch: %s", it) }
                ?: L10n.tr("Hermes couldn't open GPT-Live for the Watch. Update the Conduit notifier plugin and the push relay."))
            if (WatchAudioBridgeWire.GPT_LIVE !in audio.engines) {
                prepareRuntime(id, connection.profile)
                if (callId != id) return Message.CallRefused(id, WatchVoiceStartFailure.ended)
            }
            if (activeConnection != connection) throw PrepareError(WatchVoiceStartFailure.connectionChanged)
            val (packed, bytes) = WatchVoiceWire.BridgeSession.pack(briefing)
            val reply = Message.BridgeSessionIssued(id, WatchVoiceWire.BridgeSession(
                // A call Hermes made opens with its brief instead of a greeting.
                WatchAudioBridgeWire.GPT_LIVE, grant, packed, bytes, if (opening == null) style.greeting else null, prefs.gptLiveVoice,
                prefs.resolvedSpokenEndConversationPhrases, opening?.watchOpeningTurn,
            ))
            val replyBytes = WatchVoiceWire.encode(reply).size
            if (replyBytes > REPLY_LIMIT) throw PrepareError(L10n.tr("The call's briefing (memory and persona) is too large to send to the Watch."))
            log.note("watchBridgePrepared", mapOf(
                "callID" to id, "ms" to System.currentTimeMillis() - startedAt, "briefingBytes" to bytes, "compressedBytes" to packed.size,
                "replyBytes" to replyBytes, "tools" to grant.tools, "engines" to audio.engines,
                "memory" to (memory != null), "personality" to (personality != null),
            ))
            endWhenGrantDoes(id, grant.expiresAtMs)
            reply
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? PrepareError)?.reason ?: liveErrorMessage(e)
            log.note("watchBridgePrepareFailed", mapOf("callID" to id, "ms" to System.currentTimeMillis() - startedAt, "error" to reason))
            if (callId == id) endCall()
            Message.CallRefused(id, reason)
        }
    }

    /**
     * Asks Hermes to make GPT-Live's WebRTC runtime and waits for it a
     * little; a start that can't wait longer is refused, and the next one
     * finds it ready.
     */
    private suspend fun prepareRuntime(id: Long, profile: String) {
        val startedAt = System.currentTimeMillis()
        var status = grantClient.prepareAudio(profile)
        while (status.runtime == "preparing" && System.currentTimeMillis() - startedAt < RUNTIME_WAIT_MS && callId == id) {
            delay(2_000)
            status = grantClient.audioStatus(profile)
        }
        log.note("watchBridgeRuntime", mapOf("callID" to id, "runtime" to status.runtime, "ms" to System.currentTimeMillis() - startedAt))
        when (status.runtime) {
            "ready" -> return
            "preparing" -> throw PrepareError(L10n.tr("Hermes is setting up GPT-Live for the Watch, which takes a few minutes the first time. Try again shortly."))
            else -> throw PrepareError(status.reason ?: L10n.tr("Hermes couldn't set up GPT-Live for the Watch."))
        }
    }

    /** A bridge call can't outlive its grant: if the watch's end never arrives, the call ends here a minute after the grant does. */
    private fun endWhenGrantDoes(id: Long, expiresAtMs: Long?) {
        val at = expiresAtMs ?: (System.currentTimeMillis() + SILENCE_LIMIT_MS)
        val wait = maxOf(0, at - System.currentTimeMillis()) + 60_000
        grantWatchdog?.cancel()
        grantWatchdog = hermes.scope.launch {
            delay(wait)
            if (callId != id) return@launch
            log.note("watchBridgeOutlivedGrant", mapOf("callID" to id))
            endCall()
        }
    }

    private fun jobsPerCall() = WatchJobSettings.clamped(settings.int(WatchJobSettings.JOBS_PER_CALL, WatchJobSettings.DEFAULT_JOBS_PER_CALL).value)

    /** Which of the watch's voices can start, as this phone last found them: shared with the watch (iOS WatchCallVoices). */
    private fun noteVoice(voice: String, ready: Boolean) {
        WatchVoiceReadiness.note(settings, activeConnection, voice, ready)
        hermes.scope.launch { WatchBridge.get(app).publishContext() }
    }

    // endregion

    // region Tokens

    private suspend fun token(id: Long): Message {
        heard(id)
        if (!onCallsConnection(id)) return Message.CallRefused(id, WatchVoiceStartFailure.connectionChanged)
        val startedAt = System.currentTimeMillis()
        return try {
            if (!connect(CONNECT_WAIT_MS)) throw PrepareError(WatchVoiceStartFailure.hermesUnreachable)
            val token = hub.liveCalls.tokenClient.freshToken()
            // Minted while the phone switched: not this call's.
            if (!onCallsConnection(id)) return Message.CallRefused(id, WatchVoiceStartFailure.connectionChanged)
            log.note("watchDirectToken", mapOf("ok" to true, "ms" to System.currentTimeMillis() - startedAt))
            Message.DirectTokenIssued(id, WatchDirectWire.token(token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? PrepareError)?.reason ?: liveErrorMessage(e)
            log.note("watchDirectToken", mapOf("ok" to false, "error" to reason))
            Message.CallRefused(id, reason)
        }
    }

    // endregion

    // region Tools

    /** The bridge for the watch's call; a call that has ended (a job it queued arriving late) gets one of its own. */
    private fun bridge(id: Long): GeminiLiveToolBridge {
        adoptIfKnown(id)
        if (callId != id) return makeBridge()
        return bridge ?: makeBridge().also { bridge = it }
    }

    /** Answered once each job runs, its outcome later as a text update: a watch message's answer can't stay open for a job. */
    private fun makeBridge() = GeminiLiveToolBridge(hub.jobs, hub.liveCalls.tokenClient, hub.liveCalls.tokenClient, holdsJobCalls = false, opensChats = false)

    private suspend fun runTool(call: WatchVoiceWire.DirectToolCall, id: Long, waiting: Boolean): WatchVoiceWire.DirectToolResult {
        if (!onCallsConnection(id)) return refused(call, waiting)
        val bridge = bridge(id)
        val startedAt = System.currentTimeMillis()
        val isJob = call.name == GeminiLiveToolBridge.Tool.START_JOB.raw
        val jobKey = WatchDirectCallLedger.jobKey(call.id, id)
        jobCalls[jobKey]?.takeIf { isJob }?.let { return repeatedJobCall(call, it, waiting, startedAt) }
        if (isJob && ledger.hasSentJob(call.id, id)) return replayedJobCall(call, waiting)
        val handled = hermes.scope.async {
            val connected = connect(CONNECT_WAIT_MS)
            // The phone may have switched connections while it waited.
            if (!onCallsConnection(id)) {
                return@async listOf(
                    GeminiLiveToolBridge.Outgoing.ToolResponse(call.id, call.name, mapOf("error" to WatchVoiceStartFailure.connectionChanged), null),
                    GeminiLiveToolBridge.Outgoing.EndConversation,
                )
            }
            if (!connected) {
                return@async listOf(GeminiLiveToolBridge.Outgoing.ToolResponse(call.id, call.name, mapOf("error" to "Conduit on the user's phone can't reach Hermes right now, so this isn't available. Tell the user in a few words."), GeminiLiveProtocol.Scheduling.WHEN_IDLE))
            }
            // From here Hermes may get the job: a later repeat isn't sent.
            if (isJob) ledger.recordJob(call.id, id)
            bridge.handle(GeminiLiveProtocol.FunctionCall(call.id, call.name, call.arguments))
        }
        if (isJob) rememberJobCall(handled, jobKey)
        if (!waiting) {
            // Queued while the watch couldn't reach this phone: nothing reads this answer.
            val late = handled.await()
            if (callId == id) lateOutgoing += late.filter { !it.answers(call.id) }
            log.note("watchDirectTool", mapOf("name" to call.name, "ms" to System.currentTimeMillis() - startedAt, "queued" to true) + answerSummary(late, call.id))
            return WatchVoiceWire.DirectToolResult(emptyList(), hub.jobs.activeJobCount)
        }
        val answered = withTimeoutOrNull(if (isJob) JOB_START_WAIT_MS else TOOL_WAIT_MS) { handled.await() }
        val outgoing = answered ?: run {
            // Still running: answer now, and keep whatever it gives later except the answer to this call.
            hermes.scope.launch {
                val late = handled.await()
                if (callId == id) lateOutgoing += late.filter { !it.answers(call.id) }
            }
            listOf(GeminiLiveToolBridge.Outgoing.ToolResponse(
                call.id, call.name,
                if (isJob) WatchJobAnswer.accepted else WatchToolAnswer.tookTooLong,
                if (isJob) null else GeminiLiveProtocol.Scheduling.WHEN_IDLE,
            ))
        }
        log.note("watchDirectTool", mapOf("name" to call.name, "ms" to System.currentTimeMillis() - startedAt, "queued" to false, "outgoing" to outgoing.size) + answerSummary(outgoing, call.id))
        return result(outgoing, bridge)
    }

    private fun rememberJobCall(task: Deferred<List<GeminiLiveToolBridge.Outgoing>>, key: String) {
        jobCalls[key] = task
        while (jobCalls.size > JOB_CALL_LIMIT) jobCalls.remove(jobCalls.keys.first())
    }

    /** A call on a connection the phone has left: refused, and the watch's call ends, as the phone's own call does at a switch. */
    private fun refused(call: WatchVoiceWire.DirectToolCall, waiting: Boolean): WatchVoiceWire.DirectToolResult {
        log.note("watchDirectTool", mapOf("name" to call.name, "connectionChanged" to true, "queued" to !waiting))
        if (!waiting) return WatchVoiceWire.DirectToolResult(emptyList(), 0)
        val answer = GeminiLiveToolBridge.Outgoing.ToolResponse(call.id, call.name, mapOf("error" to WatchVoiceStartFailure.connectionChanged), null)
        return WatchVoiceWire.DirectToolResult(listOf(wire(answer), WatchVoiceWire.DirectOutgoing.EndConversation), 0)
    }

    /** A start_job this phone sent toward Hermes before it lost the first one's answer: Hermes may have the job, so it isn't sent again. */
    private fun replayedJobCall(call: WatchVoiceWire.DirectToolCall, waiting: Boolean): WatchVoiceWire.DirectToolResult {
        val running = hub.jobs.activeJobCount
        log.note("watchDirectTool", mapOf("name" to call.name, "replay" to true, "queued" to !waiting))
        if (!waiting) return WatchVoiceWire.DirectToolResult(emptyList(), running)
        return WatchVoiceWire.DirectToolResult(listOf(wire(GeminiLiveToolBridge.Outgoing.ToolResponse(call.id, call.name, JOB_ALREADY_SENT, null))), running)
    }

    /** A start_job sent again: the first one's answer, nothing started. */
    private suspend fun repeatedJobCall(
        call: WatchVoiceWire.DirectToolCall,
        earlier: Deferred<List<GeminiLiveToolBridge.Outgoing>>,
        waiting: Boolean,
        startedAt: Long,
    ): WatchVoiceWire.DirectToolResult {
        val running = hub.jobs.activeJobCount
        if (!waiting) {
            log.note("watchDirectTool", mapOf("name" to call.name, "repeat" to true, "queued" to true))
            return WatchVoiceWire.DirectToolResult(emptyList(), running)
        }
        val answer = (withTimeoutOrNull(JOB_START_WAIT_MS) { earlier.await() } ?: emptyList()).filter { it.answers(call.id) }
        val outgoing = answer.ifEmpty { listOf(GeminiLiveToolBridge.Outgoing.ToolResponse(call.id, call.name, WatchJobAnswer.accepted, null)) }
        log.note("watchDirectTool", mapOf("name" to call.name, "repeat" to true, "ms" to System.currentTimeMillis() - startedAt) + answerSummary(outgoing, call.id))
        return WatchVoiceWire.DirectToolResult(outgoing.map(::wire), running)
    }

    /** What the answer to [id] said, for the log: its keys, its size and its error, never the results themselves. */
    private fun answerSummary(outgoing: List<GeminiLiveToolBridge.Outgoing>, id: String): Map<String, Any?> {
        val response = outgoing.filterIsInstance<GeminiLiveToolBridge.Outgoing.ToolResponse>().firstOrNull { it.id == id } ?: return mapOf("answered" to false)
        return mapOf(
            "resultKeys" to response.result.keys.sorted(),
            "resultChars" to response.result.values.sumOf { it.length },
            "resultError" to response.result["error"]?.take(160),
            "resultStatus" to response.result["status"],
        )
    }

    private suspend fun poll(id: Long): WatchVoiceWire.DirectToolResult {
        // The supervisor's jobs are another connection's now.
        if (!onCallsConnection(id)) return WatchVoiceWire.DirectToolResult(listOf(WatchVoiceWire.DirectOutgoing.EndConversation), 0)
        // An ended call's poll would take the running call's news.
        if (callId != id) return WatchVoiceWire.DirectToolResult(emptyList(), hub.jobs.activeJobCount)
        val bridge = bridge(id)
        // Settles jobs whose completion the stream missed while the phone slept.
        if (connect(CONNECT_WAIT_MS)) runCatching { hub.jobs.pollOnce() }
        if (!onCallsConnection(id)) return WatchVoiceWire.DirectToolResult(listOf(WatchVoiceWire.DirectOutgoing.EndConversation), 0)
        val updates = bridge.pendingUpdates()
        val news = updates.filterIsInstance<GeminiLiveToolBridge.Outgoing.TextWhenIdle>().map { it.text.length }
        if (news.isNotEmpty()) log.note("watchDirectPoll", mapOf("news" to news.size, "newsChars" to news))
        return result(updates, bridge)
    }

    /** What goes back to the watch, with any late answers first. Handing them over counts as delivered, as a socket send does on the phone. */
    private fun result(outgoing: List<GeminiLiveToolBridge.Outgoing>, bridge: GeminiLiveToolBridge): WatchVoiceWire.DirectToolResult {
        val late = if (bridge === this.bridge) lateOutgoing.toList().also { lateOutgoing.clear() } else emptyList()
        val items = late + outgoing
        for (item in items) {
            when (item) {
                is GeminiLiveToolBridge.Outgoing.ToolResponse -> bridge.outcomeSent(GeminiLiveConversationController.jobId(item.result))
                is GeminiLiveToolBridge.Outgoing.TextWhenIdle -> bridge.textUpdateDelivered(bridge.textUpdateSending(item.text))
                else -> Unit
            }
        }
        return WatchVoiceWire.DirectToolResult(items.map(::wire), hub.jobs.activeJobCount)
    }

    // endregion

    // region Tool grant

    /**
     * A grant for the running call's lookups, jobs and Gemini tokens; null
     * when the host can't give one. A host that refuses jobs (a plugin before
     * 0.7) is asked again for the lookups alone. The log has its outcome and
     * limits, never its keys.
     */
    private suspend fun requestGrant(id: Long, profile: String, carryJobsFrom: String? = null): WatchVoiceWire.DirectToolGrant? {
        if (grantJobTools.isNotEmpty()) {
            val withJobs = requestGrantWithToken(id, profile, withJobs = true, carryJobsFrom)
            withJobs.getOrNull()?.let { return it }
            if (callId != id || (grantTools.isEmpty() && !grantLiveToken && !grantAudio)) return null
            // A plugin before 0.7 refuses job tools (400): renewals stop asking.
            if (isRefusedTool(withJobs.exceptionOrNull())) grantJobTools = emptyList()
        }
        if (grantTools.isEmpty() && !grantLiveToken && !grantAudio) return null
        return requestGrantWithToken(id, profile, withJobs = false, null).getOrNull()
    }

    /** Asks with live_token while the host takes it, and the chat tools while it takes them; an older plugin's refusal drops them. */
    private suspend fun requestGrantWithToken(id: Long, profile: String, withJobs: Boolean, carryJobsFrom: String?): Result<WatchVoiceWire.DirectToolGrant> {
        val asked = grantLiveToken
        var first = requestGrantOnce(id, profile, withJobs, asked, carryJobsFrom)
        val error = first.exceptionOrNull()
        if (error != null && callId == id && isRefusedChatTool(error) && grantTools.any { it in WatchToolAnswer.chatTools }) {
            grantTools = grantTools.filter { it !in WatchToolAnswer.chatTools }
            if (grantTools.isEmpty() && !asked && !(withJobs && grantJobTools.isNotEmpty()) && !grantAudio) return first
            first = requestGrantOnce(id, profile, withJobs, asked, carryJobsFrom)
        }
        if (first.isSuccess || !asked || callId != id || !isRefusedTool(first.exceptionOrNull())) return first
        grantLiveToken = false
        if (grantTools.isEmpty() && !(withJobs && grantJobTools.isNotEmpty())) return first
        return requestGrantOnce(id, profile, withJobs, liveToken = false, carryJobsFrom)
    }

    /** The `tools` less the chat tools when the host's plugin reported its features without them: it would refuse the whole grant. */
    private suspend fun grantable(tools: List<String>): List<String> {
        val might = runCatching { PluginStatusRepository.of(hermes).status(force = false).mightSupport(WatchToolAnswer.CHAT_CAPABILITY) }.getOrDefault(true)
        return if (might) tools else tools.filter { it !in WatchToolAnswer.chatTools }
    }

    private suspend fun requestGrantOnce(id: Long, profile: String, withJobs: Boolean, liveToken: Boolean, carryJobsFrom: String?): Result<WatchVoiceWire.DirectToolGrant> {
        val startedAt = System.currentTimeMillis()
        val dashboard = activeConnection.dashboard
        return try {
            val response = grantClient.grant(
                tools = grantTools + (if (withJobs) grantJobTools else emptyList()) + (if (liveToken) listOf(WatchLiveToken.TOOL) else emptyList()),
                profile = profile,
                maxJobs = if (withJobs) grantMaxJobs else null,
                jobOptions = if (withJobs) grantJobOptions else emptyMap(),
                carryJobsFrom = if (withJobs) carryJobsFrom else null,
                jobProfiles = if (withJobs) grantableJobProfiles(grantJobProfiles) else emptyList(),
                audio = grantAudio,
            )
            val grant = wireGrant(response, grantVoiceApprovals, grantJobProfiles)
            if (callId != id) {
                // The call ended while the host answered: revoked through the dashboard that gave it.
                if (activeConnection.dashboard == dashboard) hermes.scope.launch { grantClient.revoke(grant.grantId, profile) }
                return Result.failure(CancellationException("ended"))
            }
            grantIds += grant.grantId
            log.note("watchToolGrant", mapOf(
                "callID" to id, "ok" to true, "ms" to System.currentTimeMillis() - startedAt, "tools" to grant.tools,
                "maxCalls" to grant.maxCalls, "maxJobs" to grant.maxJobs, "voiceApprovals" to (grant.voiceApprovals == true),
                "askedJobs" to withJobs, "askedToken" to liveToken, "askedCarry" to (carryJobsFrom != null), "askedAudio" to grantAudio,
                "audioEngines" to grant.audio?.engines, "hostCarried" to (grant.jobsCarriedFrom != null),
                "expiresInS" to grant.expiresAtMs?.let { (it - System.currentTimeMillis()) / 1000 },
            ))
            Result.success(grant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastGrantError = liveErrorMessage(e)
            log.note("watchToolGrant", mapOf("callID" to id, "ok" to false, "ms" to System.currentTimeMillis() - startedAt, "askedJobs" to withJobs, "askedToken" to liveToken, "error" to lastGrantError))
            Result.failure(e)
        }
    }

    /** The watch's ask for a new grant, before its grant runs out. Only a grant this phone got for the call can have its jobs carried over. */
    private suspend fun renewGrant(id: Long, carryJobsFrom: String?): Message {
        if (!onCallsConnection(id)) return Message.CallRefused(id, WatchVoiceStartFailure.connectionChanged)
        adoptIfKnown(id)
        val profile = connection?.profile
        if (id != callId || profile == null || (grantTools.isEmpty() && grantJobTools.isEmpty() && !grantLiveToken)) {
            return Message.CallRefused(id, L10n.tr("This call has no Watch lookups to renew."))
        }
        if (!connect(CONNECT_WAIT_MS)) return Message.CallRefused(id, WatchVoiceStartFailure.hermesUnreachable)
        if (!onCallsConnection(id)) return Message.CallRefused(id, WatchVoiceStartFailure.connectionChanged)
        val carry = carryJobsFrom?.takeIf { it in grantIds }
        val renewed = requestGrant(id, profile, carry)
        if (callId == id) ledger.setGrant(grantScope(), id)
        val grant = renewed ?: return Message.CallRefused(id, L10n.tr("Hermes couldn't renew the Watch lookups."))
        if (!onCallsConnection(id)) return Message.CallRefused(id, WatchVoiceStartFailure.connectionChanged)
        return Message.DirectGrantIssued(id, grant)
    }

    /** After a restart: a running call the ledger knows becomes the running call again, with its connection and what its grants cover. */
    private fun adoptIfKnown(id: Long) {
        if (callId != null) return
        val known = ledger.call(id)?.takeIf { !it.ended } ?: return
        callId = id
        connection = known.connection
        known.grant?.let { scope ->
            grantTools = scope.tools
            grantJobTools = scope.jobTools
            grantLiveToken = scope.liveToken
            grantMaxJobs = scope.maxJobs
            grantJobOptions = scope.jobOptions
            grantJobProfiles = scope.jobProfiles ?: jobProfileNames(known.connection.profile)
            grantVoiceApprovals = scope.voiceApprovals
            grantIds = scope.grantIds.toMutableList()
            grantAudio = scope.audio ?: false
        }
        lateOutgoing.clear()
        lastHeardAtMs = System.currentTimeMillis()
        hub.jobs.liveCallReadsReplies = true
    }

    private fun grantScope() = WatchDirectCallLedger.GrantScope(
        grantTools, grantJobTools, grantLiveToken, grantMaxJobs, grantJobOptions, grantJobProfiles, grantVoiceApprovals, grantIds.toList(), grantAudio,
    )

    /** Ends the call's grants on Hermes, which closes them on the relay. Only through the call's own dashboard. */
    private fun revokeGrants() {
        val ids = grantIds.toList()
        grantIds.clear()
        grantTools = emptyList()
        grantJobTools = emptyList()
        grantLiveToken = false
        grantJobOptions = emptyMap()
        grantJobProfiles = emptyList()
        grantAudio = false
        val connection = connection ?: return
        if (ids.isEmpty()) return
        if (connection.dashboard != activeConnection.dashboard) {
            log.note("watchToolGrantsLeft", mapOf("grants" to ids.size))
            return
        }
        hermes.scope.launch { ids.forEach { grantClient.revoke(it, connection.profile) } }
    }

    // endregion

    // region End

    /** The transcript goes to the connection the call began on, whichever is active now. A call this phone has no record of isn't saved. */
    private fun ended(id: Long, transcript: WatchVoiceWire.DirectTranscript) {
        val isCurrent = id == callId
        val known = ledger.call(id)
        ledger.end(id)
        log.note("watchDirectEnd", mapOf("callID" to id, "lines" to transcript.turns.size, "current" to isCurrent, "known" to (known != null)))
        // The phone's jobs the call started, on its own profile.
        val (before, since) = jobsBefore.remove(id) ?: (null to 0L)
        val phoneJobs = if (before == null) emptyList() else hub.jobs.jobs.value
            .filter { it.id !in before && it.startedAtMs >= since && it.profile == null && !it.isThreadTurn }
            .map { job ->
                val session = listOfNotNull(job.storedSessionId, job.runtimeSessionId).firstOrNull { it.isNotEmpty() }
                WatchVoiceWire.DirectJob(job.id, job.title, session, job.startedAtMs)
            }
        if (isCurrent) endCall()
        if (known != null) hermes.scope.launch { save(transcript, known, phoneJobs) }
    }

    /** Saved through the same outbox as the phone's calls: queued first, so a save Hermes can't take now is retried later. Once per call. */
    private suspend fun save(transcript: WatchVoiceWire.DirectTranscript, known: WatchDirectCallLedger.Call, phoneJobs: List<WatchVoiceWire.DirectJob>) {
        if (!known.saveCalls || transcript.turns.isEmpty()) return
        val saved = settings.getString(SAVED_CALLS_KEY, "").split('\n').filter { it.isNotEmpty() }
        if (transcript.callUuid in saved) return
        settings.set(SAVED_CALLS_KEY, (saved + transcript.callUuid).takeLast(50).joinToString("\n"))
        // The watch's relay jobs and the ones the phone ran for the call.
        val jobs = (transcript.jobs.orEmpty() + phoneJobs.filter { job -> transcript.jobs.orEmpty().none { it.jobId == job.jobId } }).sortedBy { it.startedAtMs }
        val request = VoiceTranscriptSaveRequest(
            callId = transcript.callUuid,
            engine = engineStorageId(transcript.engine),
            title = LiveVoiceCalls.fallbackCallTitle(transcript.startedAtMs),
            turns = watchCallTurns(transcript.turns, jobs),
        )
        hub.queueVoiceTranscript(VoiceTranscriptOutbox.Entry(known.connection.dashboard, known.connection.profile, request, System.currentTimeMillis(), jobs.mapNotNull { it.sessionId }))
        // Only the active dashboard's outbox drains; another's waits until it's active again.
        if (known.connection != activeConnection || !connect(20_000)) return
        hub.saveVoiceCallsNow()
    }

    /** Whether [id]'s messages may be answered: it began on the connection active now. A running call whose connection the phone left ends. */
    private fun onCallsConnection(id: Long): Boolean {
        val known = ledger.call(id)
        if (known != null && known.connection == activeConnection) return true
        if (id == callId || (callId == null && known?.ended == false)) {
            log.note("watchDirectConnectionChanged", mapOf("callID" to id, "known" to (known != null)))
            if (id == callId) endCall() else ledger.end(id)
        } else {
            log.note("watchDirectRefused", mapOf("callID" to id, "known" to (known != null), "straggler" to true))
        }
        return false
    }

    /** Unspoken job news goes back to the jobs, which report it as usual. */
    private fun endCall() {
        grantWatchdog?.cancel()
        grantWatchdog = null
        callId?.let(ledger::end)
        bridge?.let { bridge ->
            bridge.returnUnsent(lateOutgoing.filterIsInstance<GeminiLiveToolBridge.Outgoing.TextWhenIdle>().map { it.text })
            bridge.beginEnding()
        }
        bridge = null
        lateOutgoing.clear()
        revokeGrants()
        hub.jobs.liveCallReadsReplies = false
        callId = null
        connection = null
    }

    private fun heard(id: Long) {
        val current = callId ?: return
        if (current == id) {
            lastHeardAtMs = System.currentTimeMillis()
            return
        }
        // Another call's message: a straggler from one that ended, unless the running call has been silent so long it ended without its end arriving.
        if (System.currentTimeMillis() - lastHeardAtMs > SILENCE_LIMIT_MS) {
            log.note("watchDirectStale", mapOf("callID" to current))
            endCall()
        }
    }

    // endregion

    companion object {
        /** A start_job is answered once Hermes takes the job, or after this long whatever happens. */
        const val JOB_START_WAIT_MS = 8_000L
        /** Any other function call is answered within this long. */
        const val TOOL_WAIT_MS = 20_000L
        /** How long a token, tool or poll waits for a Hermes connection the phone's sleep dropped. */
        const val CONNECT_WAIT_MS = 8_000L
        /** A call not heard from this long is over, whatever its end said. */
        const val SILENCE_LIMIT_MS = 30 * 60 * 1000L
        const val BUSY_WITHOUT_WORD_MS = 10 * 60 * 1000L
        /** How long a start waits for Hermes to make GPT-Live's WebRTC runtime. */
        const val RUNTIME_WAIT_MS = 8_000L
        /** Start_job calls kept in memory for their repeats. */
        const val JOB_CALL_LIMIT = 32
        /** Bytes of a start reply, under the Data Layer's request limit (about 100 KB). */
        const val REPLY_LIMIT = 90 * 1024
        /** The plugin's WATCH_JOB_MAX_PROFILES: it refuses a longer ask outright. */
        const val MAX_GRANT_JOB_PROFILES = 32
        private const val SAVED_CALLS_KEY = "watchDirect.savedCalls"

        /** A start_job sent again after this phone lost the first one's answer. Written for the model, not shown. */
        val JOB_ALREADY_SENT = mapOf(
            "status" to "already_sent",
            "message" to "This job was already sent to Hermes before Conduit on the phone lost track of it, so it wasn't sent again. If it started, its result arrives in a Hermes chat. Don't start it again unless the user asks.",
        )

        @Volatile private var shared: WatchDirectBroker? = null
        fun get(context: Context): WatchDirectBroker = shared ?: synchronized(this) { shared ?: WatchDirectBroker(context).also { shared = it } }

        fun wire(item: GeminiLiveToolBridge.Outgoing): WatchVoiceWire.DirectOutgoing = when (item) {
            is GeminiLiveToolBridge.Outgoing.ToolResponse -> {
                // What the phone's call says instead when the call is gone.
                val fallback = if (item.scheduling == GeminiLiveProtocol.Scheduling.SILENT) null else GeminiLiveConversationController.fallbackText(item.result, item.name)
                WatchVoiceWire.DirectOutgoing.ToolResponse(item.id, item.name, item.result, item.scheduling?.raw, fallback)
            }
            is GeminiLiveToolBridge.Outgoing.TextWhenIdle -> WatchVoiceWire.DirectOutgoing.TextWhenIdle(item.text)
            is GeminiLiveToolBridge.Outgoing.ContextWhenIdle -> WatchVoiceWire.DirectOutgoing.ContextWhenIdle(item.text)
            GeminiLiveToolBridge.Outgoing.EndConversation -> WatchVoiceWire.DirectOutgoing.EndConversation
        }

        /** The other profiles a grant may name: those that are Hermes profile names, up to the plugin's cap. */
        fun grantableJobProfiles(names: List<WatchVoiceWire.JobProfileName>): List<String> {
            val profiles = mutableListOf<String>()
            for (profile in names.mapNotNull { it.profile }) {
                if (profiles.size >= MAX_GRANT_JOB_PROFILES) break
                if (Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(profile) && profiles.none { it.equals(profile, ignoreCase = true) }) profiles += profile
            }
            return profiles
        }

        /** A plugin before 0.18 refusing the chat tools: its 400 lists what the watch can be granted, without them. */
        fun isRefusedChatTool(error: Throwable?): Boolean =
            error is DashboardHttpException && error.code == 400 && error.detail.contains("can only be granted") && !error.detail.contains(WatchToolAnswer.FIND_CHAT)

        fun isRefusedTool(error: Throwable?): Boolean = error is DashboardHttpException && error.code == 400

        /** The grant as the watch reads it, with the user's settings the host doesn't decide. */
        fun wireGrant(grant: WatchToolGrantClient.Grant, voiceApprovals: Boolean, jobProfiles: List<WatchVoiceWire.JobProfileName>) = WatchVoiceWire.DirectToolGrant(
            grantId = grant.grantId,
            relayUrl = grant.relayUrl,
            key = grant.key,
            watchKey = grant.watchKey,
            expiresAtMs = grant.expiresAt?.let { text ->
                runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                    ?: text.toDoubleOrNull()?.let { (it * 1000).toLong() }
            },
            tools = grant.tools,
            maxCalls = grant.maxCalls,
            maxJobs = grant.maxJobs,
            voiceApprovals = voiceApprovals && WatchJobAnswer.ANSWER_APPROVAL in grant.tools,
            jobsCarriedFrom = grant.jobsCarriedFrom,
            audio = grant.audio?.let { WatchVoiceWire.AudioBridge(it.url, it.version, it.engines) },
            // Every name the user's profiles go by: the watch tells a profile the host doesn't take from a name that isn't one.
            jobProfiles = if (grant.hostJobProfiles == null) null else jobProfiles,
            hostJobProfiles = grant.hostJobProfiles,
        )

        /** The saved call's engine: a watch call to Grok or GPT-Live, else Gemini Live. */
        fun engineStorageId(engine: String?): String = when (engine) {
            WatchAudioBridgeWire.GROK -> LiveVoiceMode.GROK_LIVE.storageID
            WatchAudioBridgeWire.GPT_LIVE -> LiveVoiceMode.GPT_LIVE.storageID
            else -> LiveVoiceMode.GEMINI_LIVE.storageID
        }

        /** The call's lines with a "Started a background job" line where each job started: after every line said by then. */
        fun watchCallTurns(turns: List<WatchVoiceWire.DirectTurn>, jobs: List<WatchVoiceWire.DirectJob>): List<VoiceTranscriptTurn> {
            val lines = turns.map { Triple(it.atMs, if (it.role == WatchVoiceWire.DirectTurn.Role.USER) "user" else "assistant", it.text) }.toMutableList()
            for (job in jobs) {
                val line = L10n.tr("Started a background job: %s.", job.title)
                val note = job.sessionId?.takeIf { it.isNotEmpty() }?.let { "$line [${L10n.tr("Open job")}](conduit://session/$it)" } ?: line
                val index = lines.indexOfFirst { it.first > job.startedAtMs }.takeIf { it >= 0 } ?: lines.size
                lines.add(index, Triple(job.startedAtMs, "assistant", note))
            }
            return lines.mapIndexed { index, (at, role, text) -> VoiceTranscriptTurn(index, role, text, at / 1000.0) }
        }
    }
}
