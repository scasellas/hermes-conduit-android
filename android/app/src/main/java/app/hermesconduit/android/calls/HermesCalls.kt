package app.hermesconduit.android.calls

import android.content.Context
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.HermesCallDetector
import app.hermesconduit.android.model.HermesCallHoldAnswer
import app.hermesconduit.android.model.HermesCallOpening
import app.hermesconduit.android.model.HermesCallOutcome
import app.hermesconduit.android.model.HermesCallOutcomeOutbox
import app.hermesconduit.android.model.HermesCallRequest
import app.hermesconduit.android.model.HermesCallSettings
import app.hermesconduit.android.model.HermesCallTarget
import app.hermesconduit.android.model.HermesCallWatchAnswer
import app.hermesconduit.android.model.HermesCallerName
import app.hermesconduit.android.model.HermesCallsMalformedException
import app.hermesconduit.android.model.HermesCallsStatus
import app.hermesconduit.android.model.HermesCallsWire
import app.hermesconduit.android.model.HermesNativeCallPlan
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.VoiceLaunch
import app.hermesconduit.android.watch.WatchBridge
import app.hermesconduit.android.watch.WatchDirectBroker
import app.hermesconduit.android.watch.WatchDirectConnection
import app.hermesconduit.wearwire.HermesRingDevice
import app.hermesconduit.wearwire.HermesRingOutcome
import app.hermesconduit.wearwire.HermesRingSettleResult
import app.hermesconduit.wearwire.WearMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Calls from Hermes on Android (#449; iOS AppState+HermesCalls and
 * HermesNativeCalls). Calls arrive from the gateway socket
 * ([HermesCallDetector]) instead of the VoIP push relay, ring as a
 * full-screen CallStyle notification ([HermesCallNotifications]), open the
 * call's chat in classic voice when answered, and a declined or missed call
 * is reported to the host as iOS reports it. A call that rings here rings
 * the Wear OS watch too ([HermesWatchRing], iOS's ring with the Apple
 * Watch): answering or declining on either stops the other. One per process.
 */
class HermesCalls private constructor(private val context: Context) {
    private val hermes = context.hermes
    private val settings = context.appSettings
    private val scope = hermes.scope
    val detector = HermesCallDetector()

    private val _status = MutableStateFlow<HermesCallsStatus?>(null)
    /** The active profile's call settings, once read from this dashboard. */
    val status: StateFlow<HermesCallsStatus?> = _status
    private var statusKey: String? = null

    /** The final reply each chat's last turn ended with, for the call's opening. */
    private val lastReplies = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    /** [ring]: the ring it shares with the watch, when it rings there too. */
    private class Ringing(val target: HermesCallTarget, val callerName: String, var answered: Boolean = false, var unanswered: Job? = null, var ring: String? = null)
    private var ringing: Ringing? = null
    private var started = false

    private val _ringingSession = MutableStateFlow<String?>(null)
    /** The chat of the call ringing here, if one is: the call screen closes once it stops. */
    val ringingSession: StateFlow<String?> = _ringingSession

    val watchRing = HermesWatchRing(context)

    private val dashboardId get() = hermes.connection.value?.id
    private fun key(profile: String = hermes.profile.value) = (dashboardId ?: "") + "|" + profile

    fun start() {
        if (started) return
        started = true
        HermesCallTrace.sink = { hermes.log(it) }
        HermesCallNotifications.ensureChannel(context)
        scope.launch { hermes.events.collect(::onEvent) }
        scope.launch { hermes.rawEvents.collect(::onRawEvent) }
        scope.launch {
            combine(hermes.connection, hermes.profile) { connection, profile -> connection?.id to profile }
                .distinctUntilChanged()
                .collect {
                    detector.reset()
                    detector.settings = null
                    _status.value = null
                    statusKey = null
                }
        }
        scope.launch {
            hermes.connected.collect {
                refreshStatus()
                deliverOutcomes()
            }
        }
        // The watch reads whether calls ring here from the phone's context.
        scope.launch {
            settings.bool(RINGS_KEY, true).collect { WatchBridge.get(context).publishContext() }
        }
        scope.launch {
            while (isActive) {
                delay(15_000)
                detector.tick(System.currentTimeMillis()).forEach(::place)
            }
        }
    }

    // region Detection

    private fun onEvent(event: StreamEvent) {
        if (event is StreamEvent.MessageComplete) event.content?.takeIf { it.isNotBlank() }?.let { lastReplies[event.sessionId] = it }
        detector.observe(event, System.currentTimeMillis()).forEach(::place)
    }

    /** A `call.requested` on the socket: the push's routing object, as the relay would send it. */
    private fun onRawEvent(params: JsonObject) {
        if (params["type"].str?.trim() != HermesCallRequest.TYPE) return
        val payload = params["payload"].obj
        val routing = JsonObject(buildMap {
            payload?.let { putAll(it) }
            params.forEach { (k, v) -> if (k != "payload" && k !in this) put(k, v) }
        })
        val target = HermesCallTarget.parse(routing) ?: return
        val withDashboard = if (target.dashboardID == null) target.copy(dashboardID = dashboardId) else target
        if (!detector.socketCall(withDashboard.sessionId, System.currentTimeMillis())) return
        incoming(withDashboard, source = "socket")
    }

    /** The sweep saw a session go idle (chats this socket isn't attached to). */
    fun sessionIdle(sessionId: String) {
        detector.turnEnded(sessionId, System.currentTimeMillis())?.let(::place)
    }

    private fun place(call: HermesCallDetector.LocalCall) {
        val target = HermesCallTarget(
            profile = hermes.profile.value,
            sessionId = call.sessionId,
            durableSessionID = null,
            dashboardID = dashboardId,
            call = HermesCallRequest(call.callId, call.kind, call.title, listOf(call.sessionId), call.reason),
            sentAtMs = System.currentTimeMillis(),
        )
        incoming(target, source = "this device")
    }

    // endregion

    // region Ringing

    val ringingWanted: Boolean get() = settings.getBool(RINGS_KEY, true)

    val isVoiceInUse: Boolean get() = VoiceHub.peek()?.let { it.conversationOpen.value || it.liveCalls.isCallActive } == true

    /** Calls ring here (for the watch, which says its own voice keeps them off it only then). */
    val callsRing: Boolean get() = ringingWanted && HermesCallNotifications.canRing(context)

    private val connection get() = WatchDirectConnection(hermes.profile.value, dashboardId ?: "-")

    private fun callerName(target: HermesCallTarget): String {
        val main = settings.getString(AppSettings.displayNameKey(target.dashboardID ?: dashboardId, "default"), "").ifEmpty { null }
        return HermesCallerName.name(target.profile, main)
    }

    fun incoming(target: HermesCallTarget, source: String) {
        val now = System.currentTimeMillis()
        // A watch call this phone serves is voice in use too.
        val busy = isVoiceInUse || ringing != null || WatchDirectBroker.get(context).hasCall
        val plan = HermesNativeCallPlan.plan(target.sentAtMs, now, busy, replayed = false, ringingWanted = ringingWanted && HermesCallNotifications.canRing(context))
        if (ringing == null) HermesCallTrace.begin("Call received from $source: ${plan.traceLabel} (voice busy: $busy)")
        val name = callerName(target)
        when (plan) {
            HermesNativeCallPlan.Ring -> {
                val call = Ringing(target, name)
                ringing = call
                _ringingSession.value = target.sessionId
                val line = HermesCallerName.line(name, target.call.title)
                call.ring = watchRing.ring(target, line, connection)
                HermesCallNotifications.postRinging(context, target, line, localOnly = call.ring != null)
                HermesCallTrace.note(if (call.ring != null) "Ringing (and on the watch)" else "Ringing")
                call.unanswered = scope.launch {
                    delay(UNANSWERED_AFTER_MS)
                    if (ringing === call && !call.answered) finish(missed = true, declined = false)
                }
            }
            is HermesNativeCallPlan.EndAtOnce -> {
                post(plan.notice, target, name)
                if (plan.notice == HermesNativeCallPlan.Notice.MISSED) report(HermesCallOutcome.MISSED, target)
            }
        }
    }

    private fun post(notice: HermesNativeCallPlan.Notice, target: HermesCallTarget, name: String) {
        when (notice) {
            HermesNativeCallPlan.Notice.NONE -> Unit
            HermesNativeCallPlan.Notice.MISSED -> HermesCallNotifications.postNotice(context, target, missed = true, callerName = name)
            HermesNativeCallPlan.Notice.TALK -> HermesCallNotifications.postNotice(context, target, missed = false, callerName = name)
        }
    }

    /** The ringing call ended unanswered: rang out, or declined (here, or on the watch: [elsewhere]). */
    private fun finish(missed: Boolean, declined: Boolean, elsewhere: Boolean = false) {
        val call = ringing ?: return HermesCallNotifications.cancelRinging(context)
        ringing = null
        _ringingSession.value = null
        call.unanswered?.cancel()
        HermesCallNotifications.cancelRinging(context)
        HermesCallTrace.note(if (declined) (if (elsewhere) "Declined on the Watch" else "Declined") else "Call ended (unanswered)")
        // Declined here: the watch stops ringing too.
        if (declined && !elsewhere) call.ring?.let { watchRing.settle(it, HermesRingOutcome.DECLINED) }
        if (missed || declined) post(HermesNativeCallPlan.Notice.MISSED, call.target, call.callerName)
        HermesNativeCallPlan.outcome(declined, call.answered, if (missed) HermesNativeCallPlan.Notice.MISSED else HermesNativeCallPlan.Notice.NONE)
            ?.let { report(it, call.target) }
    }

    /** Decline from the notification or the call screen. */
    fun decline() = finish(missed = false, declined = true)

    /** The watch answered the ringing call: it stops ringing here, and nothing is reported (iOS `.elsewhere(.answered)`). */
    private fun answeredElsewhere(call: Ringing) {
        if (ringing !== call) return
        ringing = null
        _ringingSession.value = null
        call.unanswered?.cancel()
        HermesCallNotifications.cancelRinging(context)
        HermesCallTrace.note("Answered on the Watch")
    }

    /**
     * The watch answered or declined a call that rang on both (its
     * [WearMessage.RingSettle], answered with how the ring stands). The
     * first word stands: declined there, it leaves the same missed call and
     * outcome a decline here does.
     */
    fun watchSettled(request: WearMessage.RingSettle): HermesRingSettleResult {
        val result = watchRing.watchSettle(request)
        val call = ringing?.takeIf { it.ring == request.id && !it.answered }
        if (result == HermesRingSettleResult.Settled && call != null) {
            when (request.outcome) {
                HermesRingOutcome.ANSWERED -> answeredElsewhere(call)
                HermesRingOutcome.DECLINED -> finish(missed = false, declined = true, elsewhere = true)
            }
        }
        return result
    }

    /**
     * The call a watch call answers (its start's `ring`), kept from its
     * ring. A call still ringing here for it stops now: the watch's settle
     * was lost on the way (iOS waits for the relay's stop instead).
     */
    fun answeredOnWatch(ringId: String): HermesCallTarget? {
        val target = watchRing.target(ringId) ?: return null
        ringing?.takeIf { it.ring == ringId && !it.answered }?.let { call ->
            HermesCallTrace.note("Answered on the Watch, no settle from it")
            watchRing.watchSettle(WearMessage.RingSettle(ringId, HermesRingDevice.WATCH, HermesRingOutcome.ANSWERED))
            answeredElsewhere(call)
        }
        return target
    }

    /**
     * What a watch call that answers a call from Hermes opens with: the news
     * the phone's own answer gives, with the reply this phone last saw in the
     * call's chat (iOS AppState.watchHermesCallOpening). The call tells the
     * user how the job went, so no voice conversation announces it again.
     */
    fun watchOpening(target: HermesCallTarget): HermesCallOpening {
        VoiceHub.get(context).jobs.noteCallAnswered(target.call.sessionIDs)
        val sameDashboard = target.dashboardID == null || target.dashboardID == dashboardId
        val result = if (sameDashboard) lastReplies[target.sessionId] ?: target.durableSessionID?.let { lastReplies[it] } else null
        return HermesCallOpening(target.call.kind, target.call.title, result, target.call.reason, target.call.sessionIDs)
    }

    /**
     * Answer (or Talk on a notice): the call stops ringing and voice opens in
     * the call's chat with what came of the job. Returns the target to open.
     */
    fun answer(routing: HermesCallTarget?): HermesCallTarget? {
        val call = ringing
        val target = routing ?: call?.target ?: return null
        if (call != null && call.target.sessionId == target.sessionId) {
            call.answered = true
            call.unanswered?.cancel()
            ringing = null
            _ringingSession.value = null
            HermesCallNotifications.cancelRinging(context)
            HermesCallTrace.note("Answered")
            // The watch stops ringing; answered there first, the call is there.
            val ring = call.ring
            if (ring != null && watchRing.settle(ring, HermesRingOutcome.ANSWERED) is HermesRingSettleResult.AlreadySettled) {
                HermesCallTrace.note("Answered on the Watch first")
                return null
            }
        } else if (watchRing.answeredOnWatch(target.sessionId)) {
            // The call is on the watch: the phone's own answer opens nothing.
            HermesCallTrace.note("Answered on the Watch first")
            HermesCallNotifications.cancelNotice(context, target)
            return null
        } else {
            HermesCallTrace.note("Talk tapped")
        }
        HermesCallNotifications.cancelNotice(context, target)
        return target
    }

    /**
     * Readies voice for an answered call: classic voice says its brief at
     * the first listening window; a live mode opens with
     * [HermesCallOpening.instructionBlock] and its opening turn.
     */
    fun prepareVoice(target: HermesCallTarget): HermesCallOpening? {
        val hub = VoiceHub.get(context)
        val inUse = hub.conversationOpen.value || hub.liveCalls.isCallActive
        if (hermes.link.value != LinkState.CONNECTED || inUse) {
            HermesCallTrace.note("Voice not opened (connected: ${hermes.link.value == LinkState.CONNECTED}, voice in use: $inUse)")
            return null
        }
        hub.jobs.noteCallAnswered(target.call.sessionIDs)
        val opening = HermesCallOpening(
            kind = target.call.kind,
            title = target.call.title,
            result = lastReplies[target.sessionId] ?: target.durableSessionID?.let { lastReplies[it] },
            reason = target.call.reason,
            sessionIDs = target.call.sessionIDs,
        )
        HermesCallTrace.note("Opening voice (${VoiceLaunch.route(context)})")
        // A live mode takes the call with its own brief (instructions and an opening turn); classic voice speaks it.
        if (hub.liveCalls.enabledMode() != null) hub.liveCalls.pendingHermesCall = opening
        else hub.jobs.queueCallOpening(opening.spokenBrief)
        return opening
    }

    /**
     * After an answer: waits for Hermes' connection, then readies voice.
     * False when voice can't open; the call's news then waits in "Hermes
     * wants to talk".
     */
    suspend fun awaitVoice(target: HermesCallTarget): Boolean {
        val started = System.currentTimeMillis()
        val wasConnected = hermes.link.value == LinkState.CONNECTED
        val connected = wasConnected || kotlinx.coroutines.withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { hermes.link.first { it == LinkState.CONNECTED } } != null
        HermesCallTrace.note(if (connected) (if (wasConnected) "Already connected" else "Connected") else "Not connected in time", sinceMs = started)
        val opened = connected && prepareVoice(target) != null
        if (!opened) post(HermesNativeCallPlan.Notice.TALK, target, callerName(target))
        return opened
    }

    // endregion

    // region Outcomes

    private fun loadOutbox() = HermesCallOutcomeOutbox.parse(settings.getString(HermesCallOutcomeOutbox.STORAGE_KEY, "").ifEmpty { null })
    private fun storeOutbox(outbox: HermesCallOutcomeOutbox) = settings.set(HermesCallOutcomeOutbox.STORAGE_KEY, if (outbox.entries.isEmpty()) "" else outbox.toJson())

    private fun report(outcome: HermesCallOutcome, target: HermesCallTarget) {
        val entry = HermesCallOutcomeOutbox.Entry.of(outcome, target, target.sentAtMs ?: System.currentTimeMillis()) ?: return
        storeOutbox(loadOutbox().pruned(System.currentTimeMillis()).adding(entry))
        scope.launch { deliverOutcomes() }
    }

    private var delivering = false

    /** Sends what waits for this dashboard, oldest first; offline or a busy host keeps the rest for later. */
    suspend fun deliverOutcomes() {
        if (delivering || !hermes.isSignedIn) return
        val plugin = runCatching { PluginStatusRepository.of(context).status(force = false) }.getOrNull() ?: return
        if (plugin.state !is app.hermesconduit.android.model.PluginStatus.State.Reported) return
        val takesOutcomes = plugin.reports("hermes-call-outcomes")
        delivering = true
        try {
            val dashboard = dashboardId
            storeOutbox(loadOutbox().pruned(System.currentTimeMillis()))
            for (entry in loadOutbox().pending(dashboard)) {
                if (takesOutcomes) {
                    try {
                        val profile = entry.profile ?: hermes.profile.value
                        val response = hermes.rest { it.sendJson("POST", HermesCallsWire.OUTCOMES_PATH, entry.payload(System.currentTimeMillis()), query(profile)) }
                        if (response["ok"].bool != true) throw HermesCallsMalformedException()
                    } catch (e: Exception) {
                        val refused = HermesCallsWire.outcomeRefusedForGood((e as? DashboardHttpException)?.code, e is HermesCallsMalformedException)
                        if (!refused) return
                    }
                }
                storeOutbox(loadOutbox().removing(entry))
            }
        } finally {
            delivering = false
        }
    }

    // endregion

    // region Host settings

    private fun query(profile: String) = mapOf("profile" to profile.takeIf { it != "default" })

    suspend fun supportsCalls(): Boolean = runCatching { PluginStatusRepository.of(context).supports("hermes-calls") }.getOrDefault(false)

    /** Reads the active profile's call settings; false when the host couldn't answer. */
    suspend fun refreshStatus(): Boolean {
        if (!hermes.isSignedIn || !supportsCalls()) return false
        val profile = hermes.profile.value
        val key = key(profile)
        return try {
            val response = hermes.rest { it.getJson(HermesCallsWire.PATH, query(profile), timeoutMs = 12_000) }
            val parsed = HermesCallsStatus.parse(response) ?: throw HermesCallsMalformedException()
            if (key == key()) {
                _status.value = parsed
                statusKey = key
                detector.settings = parsed.settings
            }
            true
        } catch (e: Exception) {
            hermes.log("Call settings not read: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Saves a profile's call settings; false when the host didn't take them. */
    suspend fun save(settings: HermesCallSettings, profile: String, dashboard: String?): Boolean {
        if (dashboard != dashboardId) return false
        return try {
            val response = hermes.rest { it.sendJson("PUT", HermesCallsWire.PATH, buildJsonObject { put("settings", settings.payload) }, query(profile), timeoutMs = 12_000) }
            val saved = HermesCallSettings.parse(response["settings"])
            if (response["ok"].bool != true || saved == null) throw HermesCallsMalformedException()
            if (key(profile) == key()) {
                _status.value = _status.value?.copy(settings = saved)
                detector.settings = saved
                if (_status.value == null) refreshStatus()
            }
            true
        } catch (e: Exception) {
            hermes.log("Call settings not saved: ${e.javaClass.simpleName}")
            false
        }
    }

    // endregion

    // region Watches and presence (for the live voice modes' "call me when it's done")

    suspend fun watch(sessionIDs: List<String>, title: String, profile: String, holdSeconds: Int, endedWithinSeconds: Int?): HermesCallWatchAnswer =
        HermesCallsWire.watchAnswer(hermes.rest { it.sendJson("POST", HermesCallsWire.WATCHES_PATH, HermesCallsWire.watchBody(sessionIDs, title, holdSeconds, endedWithinSeconds), query(profile)) })

    suspend fun hold(watchID: String, profile: String, seconds: Int): HermesCallHoldAnswer =
        HermesCallsWire.holdAnswer(hermes.rest { it.sendJson("PUT", HermesCallsWire.watchPath(watchID), HermesCallsWire.holdBody(seconds), query(profile)) })

    suspend fun cancelWatch(watchID: String, profile: String) {
        val response = hermes.rest { it.sendJson("DELETE", HermesCallsWire.watchPath(watchID), null, query(profile)) }
        if (response["ok"].bool != true) throw HermesCallsMalformedException()
    }

    private var presence: Job? = null

    /** A live call is on: nothing Hermes calls about rings over it (plugin 0.14+). Renewed while voice is in use. */
    fun beginPresence() {
        if (presence != null) return
        val profile = hermes.profile.value
        presence = scope.launch {
            if (!runCatching { PluginStatusRepository.of(context).supports("hermes-call-presence") }.getOrDefault(false)) return@launch
            try {
                while (isActive) {
                    runCatching { hermes.rest { it.sendJson("PUT", HermesCallsWire.PRESENCE_PATH, HermesCallsWire.holdBody(PRESENCE_SECONDS), query(profile)) } }
                    delay(PRESENCE_RENEWAL_MS)
                }
            } finally {
                scope.launch { runCatching { hermes.rest { it.sendJson("PUT", HermesCallsWire.PRESENCE_PATH, HermesCallsWire.holdBody(0), query(profile)) } } }
            }
        }
    }

    fun endPresence() {
        presence?.cancel()
        presence = null
    }

    // endregion

    companion object {
        /** "Ring like a phone call", on this device (iOS `hermesCalls.ringsLikeCall`). */
        const val RINGS_KEY = "hermesCalls.ringsLikeCall"
        const val UNANSWERED_AFTER_MS = 45_000L
        const val CONNECTION_TIMEOUT_MS = 20_000L
        const val PRESENCE_SECONDS = 90
        const val PRESENCE_RENEWAL_MS = 30_000L

        @Volatile private var instance: HermesCalls? = null

        fun get(context: Context): HermesCalls = instance ?: synchronized(this) {
            instance ?: HermesCalls(context.applicationContext).also { instance = it }
        }

        val unavailableText: String get() = L10n.tr("Couldn't read Hermes' call settings.")
    }
}
