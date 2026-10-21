package app.hermesconduit.android.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceTranscriptionMode
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.ui.ProfileSwitching
import app.hermesconduit.android.ui.voice.VoiceConversationState
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.VoiceThreadTarget
import app.hermesconduit.android.ui.voice.live.LiveCallPhase
import app.hermesconduit.android.ui.voice.live.LiveCallView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * The bridge between one Android Auto session and the app-wide Voice stack
 * (port of the iOS CarPlayVoiceCoordinator). A presentation and lifecycle
 * seam, not a second Voice owner: it observes the controller of the
 * profile's voice mode (the classic conversation, or the Gemini Live, GPT-Live
 * or Grok Live call) and forwards deduplicated, mapped states to the car
 * (never microphone levels or transcript text), plays the classic mode's
 * status sounds, and routes the car's buttons, chat picks, shortcuts and
 * Voice options into the same listen, mute and close paths the phone uses.
 * While the car is connected every voice mode records from the car's
 * microphone, and the car counts as a surface presenting Voice, so the phone
 * locking doesn't take a conversation from the driver.
 */
class CarVoiceCoordinator(private val carContext: CarContext) {
    private val hub = VoiceHub.get(carContext)
    private val calls get() = hub.liveCalls
    private val hermes = carContext.hermes
    private val settings = carContext.appSettings
    private val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    private val earcons = CarEarconPlayer()
    private val microphone: CarMicrophone? = if (CarMicrophone.isAvailable(carContext)) CarMicrophone(carContext) else null

    private val _shownState = MutableStateFlow(CarVoiceState.READY)
    /** The state the voice screen shows. */
    val shownState: StateFlow<CarVoiceState> = _shownState
    private val _controls = MutableStateFlow(CarVoiceControls())
    /** What the voice screen's buttons and titles depend on besides the state. */
    val controls: StateFlow<CarVoiceControls> = _controls
    private val _chats = MutableStateFlow(CarChatList(emptyList(), emptyList()))
    val chats: StateFlow<CarChatList> = _chats
    private val _isLoadingChats = MutableStateFlow(true)
    val isLoadingChats: StateFlow<Boolean> = _isLoadingChats
    private val _jobs = MutableStateFlow<List<CarJobRow>>(emptyList())
    /** The Voice Jobs list, kept current. */
    val jobs: StateFlow<List<CarJobRow>> = _jobs
    private val _agents = MutableStateFlow<List<String>>(emptyList())
    /** The profiles the Voice list offers, read when it opens. */
    val agents: StateFlow<List<String>> = _agents

    /** The chat Listen talks in, picked from the car's list; null starts a new chat. */
    var chosenChat: CarChatRow? = null
        private set
    private var host: CarVoiceHost? = null
    private var lastActivated: CarVoiceState? = null
    /** Bumped by every start, pick and End: an older start that is still waiting stops there. */
    private var startRequest = 0L
    /** Which mode's controller the car follows. */
    var observedMode: CarVoiceMode = CarVoiceMode.CLASSIC
        private set
    private var modeObservation: Job? = null

    /**
     * A conversation is starting or listening or talking (one started on the
     * phone, say): the session shows the voice screen rather than a list the
     * driver has to back out of first.
     */
    var onConversationShown: (() -> Unit)? = null
    /** End with "Choose a chat first" on: the chat list comes back so the driver can go on in another chat. */
    var onChatsWanted: (() -> Unit)? = null
    private val conversationStarting: () -> Unit = { onConversationShown?.invoke() }

    init {
        hub.setCarSurfaceActive(true)
        hub.onConversationStarting = conversationStarting
        if (hasMicrophonePermission()) useCarMicrophone()
        // The voice mode can change on the phone (or from the car) while the car is connected, and with the profile.
        scope.launch { combine(hub.preferencesVersion, hermes.profile) { _, _ -> Unit }.collect { observeCurrentMode() } }
        scope.launch { hub.jobs.jobs.collect { _jobs.value = CarBrowse.jobRows(it) } }
        scope.launch { hermes.events.collect { event -> host?.observe(event) } }
    }

    private fun currentMode(): CarVoiceMode = CarVoiceMode.of(hub.preferences())

    /** The car's microphone for every voice mode, once Conduit may record. */
    private fun useCarMicrophone() {
        if (microphone != null) hub.useExternalMicrophone(microphone)
    }

    // region State

    /** Follows the controller for the profile's current voice mode. */
    private fun observeCurrentMode() {
        val mode = currentMode()
        if (modeObservation != null && mode == observedMode) return
        observedMode = mode
        modeObservation?.cancel()
        _controls.update { it.copy(isClassic = mode == CarVoiceMode.CLASSIC, chatTitle = if (mode == CarVoiceMode.CLASSIC) it.chatTitle else chosenChat?.title) }
        modeObservation = scope.launch {
            val live = mode.live
            if (live == null) {
                launch { hub.conversation.state.collect { handleControllerState(it) } }
                hub.conversation.isMicrophonePaused.collect { paused -> _controls.update { it.copy(isMicrophoneMuted = paused) } }
            } else {
                val call = LiveCallView.of(calls, live)
                launch {
                    call.phase.collect { phase ->
                        // The controller records why a start failed right after it reports the failure.
                        if (phase is LiveCallPhase.Failed) yield()
                        forward(CarVoiceState.map(phase))
                    }
                }
                call.microphoneMuted.collect { muted -> _controls.update { it.copy(isMicrophoneMuted = muted) } }
            }
        }
    }

    fun handleControllerState(state: VoiceConversationState) = forward(CarVoiceState.map(state))

    private fun forwardCurrentState() {
        val live = observedMode.live
        forward(if (live == null) CarVoiceState.map(hub.conversation.currentState) else CarVoiceState.map(LiveCallView.of(calls, live).phase.value))
    }

    private fun forward(target: CarVoiceState, issue: CarVoiceIssue? = null) {
        // The Error title names what to fix, worked out from the app's own state.
        if (target == CarVoiceState.ERROR) _controls.update { it.copy(errorIssue = issue ?: setupIssue()) }
        val activated = CarVoiceStateActivation.activationTarget(lastActivated, target) ?: return
        val previous = lastActivated
        lastActivated = activated
        _shownState.value = activated
        // Status sounds belong to the classic mode, whose microphone is the car's own turn by turn.
        if (observedMode == CarVoiceMode.CLASSIC && CarPreferences.playsSounds(settings)) {
            CarEarcon.forTransition(previous, activated)?.let(earcons::play)
        }
        // Thinking alone doesn't count: a conversation that is ending passes through it.
        if (activated == CarVoiceState.LISTENING || activated == CarVoiceState.RESPONDING) onConversationShown?.invoke()
    }

    /** What stops Voice in the mode the car follows, as far as the app can tell (iOS `setupIssue`). */
    private fun setupIssue(): CarVoiceIssue? {
        val isConnected = hermes.isSignedIn && hermes.link.value == LinkState.CONNECTED
        observedMode.live?.let { live ->
            return CarVoiceIssue.ofLive(isConnected, live, hostIssue(live), isMicrophoneDenied = !hasMicrophonePermission())
        }
        val capability = hub.capability.value
        val transcribesOnHermes = hub.preferences().resolvedTranscriptionMode == VoiceTranscriptionMode.HERMES
        return CarVoiceIssue.of(
            isConnected = isConnected,
            isVoiceEnabled = hub.isVoiceEnabled(),
            isMicrophoneDenied = !hasMicrophonePermission(),
            speechToTextReady = capability?.let { !transcribesOnHermes || it.supportsTranscription },
            textToSpeechReady = capability?.supportsSpeech,
        )
    }

    private fun hostIssue(mode: LiveVoiceMode) = when (mode) {
        LiveVoiceMode.GEMINI_LIVE -> calls.gemini.hostIssue
        LiveVoiceMode.GPT_LIVE -> calls.gpt.hostIssue
        LiveVoiceMode.GROK_LIVE -> calls.grok.hostIssue
    }

    // endregion

    // region Controls

    /** Whether the profile's voice mode has a conversation going, which the car shows rather than asking for a chat. */
    fun hasRunningConversation(): Boolean = when (val live = currentMode().live) {
        null -> hub.conversationOpen.value && hub.conversation.hasLiveVoiceSession
        else -> calls.isActive(live)
    }

    /**
     * What the car opening does (iOS `establishVoice`). A conversation already
     * open, on the phone say, is shown as it is (no restart, no new chat, no
     * listening start: the phone may be mid-turn), with a live call's
     * microphone opened for the driver, who just got in and expects to be
     * heard. Otherwise Voice starts.
     */
    fun establish() {
        observeCurrentMode()
        val live = observedMode.live
        if (live != null && calls.isActive(live)) {
            LiveCallView.of(calls, live).setMicrophoneMuted(false)
            return
        }
        if (live == null && hub.conversationOpen.value && hub.conversation.hasLiveVoiceSession) {
            hub.onCarConversationForegrounded()
            return
        }
        listen()
    }

    /** Listen (Ready and Error): continues the conversation, or starts one in the chosen chat. */
    fun listen() {
        val request = ++startRequest
        scope.launch { performListen(request) }
    }

    /**
     * New Chat (classic, Ready and Error): listening starts in a new chat. A
     * live call has no chat to continue, so it falls back to Listen.
     */
    fun startNewChat() {
        if (currentMode() != CarVoiceMode.CLASSIC) {
            listen()
            return
        }
        endConversation()
        chosenChat = null
        _controls.update { it.copy(chatTitle = null) }
        listen()
    }

    /** New voice chat, from the chat list: no chat is picked any more. */
    fun startNewVoiceChat() {
        chosenChat = null
        _controls.update { it.copy(chatTitle = null) }
        startNewChat()
    }

    /**
     * A chat from the list: Voice starts in that chat at once, with no Listen
     * tap. A live call is attached to it, as a call started from that chat on
     * the phone is.
     */
    fun openChat(row: CarChatRow) {
        endConversation()
        chosenChat = row
        _controls.update { it.copy(chatTitle = row.title) }
        listen()
    }

    /** Mute or Listen while a conversation runs: the same microphone mute the phone's controls use. */
    fun toggleMicrophone() {
        val live = currentMode().live
        if (live != null) {
            val call = LiveCallView.of(calls, live)
            call.setMicrophoneMuted(!call.microphoneMuted.value)
            return
        }
        val controller = hub.conversation
        if (controller.isMicrophonePaused.value) {
            hub.onCarConversationForegrounded()
            // A failed resume settles the controller into FAILED, which the state observation forwards.
            controller.resumeMicrophone()
        } else {
            controller.pauseMicrophone()
        }
    }

    /**
     * End: the authoritative close, whoever started the conversation, and of
     * any start still waiting for Hermes. With "Choose a chat first" on, the
     * chat list comes back. The picked chat is kept: Listen after End goes on
     * in it, as the driver picked it.
     */
    fun end() {
        startRequest++
        endConversation()
        // An Error shown without the controller failing (no microphone, no Hermes) goes back to Ready too.
        forward(CarVoiceState.READY)
        if (CarPreferences.choosesChatFirst(settings)) onChatsWanted?.invoke()
    }

    private suspend fun performListen(request: Long) {
        fun isWanted() = request == startRequest && job.isActive
        if (!requestMicrophone()) {
            if (isWanted()) forward(CarVoiceState.ERROR, CarVoiceIssue.MICROPHONE_DENIED)
            return
        }
        useCarMicrophone()
        // Hermes may still be reconnecting (the phone is usually locked in a car), so the start waits for it.
        if (!awaitHermes(::isWanted)) {
            if (isWanted()) forward(CarVoiceState.ERROR, CarVoiceIssue.NOT_CONNECTED)
            return
        }
        if (!isWanted()) return
        observeCurrentMode()
        // Thinking stood in while Hermes was reached: the controller's own state takes over again.
        forwardCurrentState()
        val live = observedMode.live
        if (live != null) {
            listenLive(live, chosenChat?.thread)
            return
        }
        if (hub.capability.value == null) hub.refreshCapabilities()
        if (!isWanted()) return
        val controller = hub.conversation
        if (hub.conversationOpen.value && controller.hasLiveVoiceSession) {
            // A conversation is open (the car's, or one started on the phone): Listen continues it.
            hub.onCarConversationForegrounded()
            when {
                controller.isMicrophonePaused.value -> controller.resumeMicrophone()
                controller.currentState == VoiceConversationState.IDLE || controller.currentState == VoiceConversationState.FAILED -> controller.startListening()
                else -> Unit
            }
            return
        }
        val carHost = CarVoiceHost(hub, chosenChat) { conversationEnded() }
        if (hub.openConversation(carHost) != null) {
            forward(CarVoiceState.ERROR)
            return
        }
        host = carHost
        hub.onCarConversationForegrounded()
        controller.startListening()
    }

    /** Listen in a live mode, by its phase (iOS `performStartListeningTurn`). */
    private fun listenLive(mode: LiveVoiceMode, thread: VoiceThreadTarget?) {
        val call = LiveCallView.of(calls, mode)
        val phase = call.phase.value
        if (mode == LiveVoiceMode.GPT_LIVE) {
            when (CarGPTLiveListenAction.forPhase(phase)) {
                CarGPTLiveListenAction.START -> calls.open(mode, thread, shownOnPhone = false)
                CarGPTLiveListenAction.NOTHING -> Unit
            }
            return
        }
        when (CarGeminiLiveListenAction.forPhase(phase)) {
            CarGeminiLiveListenAction.START -> calls.open(mode, thread, shownOnPhone = false)
            CarGeminiLiveListenAction.INTERRUPT -> call.interrupt?.invoke()
            CarGeminiLiveListenAction.NOTHING -> Unit
        }
    }

    /**
     * Ends the conversation of the mode in use, the car's or the phone's,
     * before one starts in another chat: the shared controller speaks into
     * one chat. The phone's voice screen goes with it.
     */
    private fun endConversation() {
        if (hub.conversationOpen.value) hub.closeConversation()
        conversationEnded()
        if (calls.mode.value != null) calls.close()
    }

    /** Closes a conversation the car started; one started on the phone keeps running. */
    private fun closeCarConversation() {
        val carHost = host ?: return
        if (hub.conversation.host === carHost) hub.closeConversation()
        conversationEnded()
    }

    private fun conversationEnded() {
        host = null
    }

    private fun hasMicrophonePermission() =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Asks for the microphone through the car (the prompt shows on the phone). */
    private suspend fun requestMicrophone(): Boolean {
        if (hasMicrophonePermission()) return true
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                    if (continuation.isActive) continuation.resume(Manifest.permission.RECORD_AUDIO in granted)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(false) }
        }
    }

    /** Waits (bounded) for Hermes when it isn't connected, showing Thinking meanwhile. */
    private suspend fun awaitHermes(isWanted: () -> Boolean = { true }): Boolean {
        if (!hermes.isSignedIn) return false
        if (hermes.link.value == LinkState.CONNECTED) return true
        if (isWanted()) forward(CarVoiceState.PROCESSING)
        if (hermes.gateway == null) hermes.start()
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { hermes.link.first { it == LinkState.CONNECTED } } != null
    }

    // endregion

    // region Chats

    /** Loads the active profile's chats for the list. */
    fun loadChats() {
        if (!hermes.isSignedIn) {
            _isLoadingChats.value = false
            return
        }
        scope.launch {
            _isLoadingChats.value = true
            try {
                val page = hermes.rest { it.sessions(hermes.profile.value) }
                _chats.value = CarBrowse.chatList(page.sessions)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The list keeps what it had; the next visit asks again.
            } finally {
                _isLoadingChats.value = false
            }
        }
    }

    val isConnected: Boolean get() = hermes.isSignedIn && hermes.link.value == LinkState.CONNECTED

    /** A typed or dictated reply from the car's chat list: a plain message to that chat. */
    fun reply(row: CarChatRow, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            if (!awaitHermes()) return@launch
            runCatching {
                val resumed = hermes.rpc("session.resume", buildJsonObject {
                    put("session_id", row.sessionId); put("cols", 96); put("source", "desktop"); put("omit_messages", true)
                })
                val runtime = ((resumed as? JsonObject)?.get("session_id") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: row.sessionId
                hermes.rpc("prompt.submit", buildJsonObject { put("session_id", runtime); put("text", trimmed) })
            }
        }
    }

    /**
     * The car's Mark as read: the chat is read on Hermes, as marking it read
     * on the phone does. The list shows it read at once and goes back if
     * Hermes refuses.
     */
    fun markRead(row: CarChatRow) {
        if (!row.unread || !hermes.isSignedIn) return
        val profile = hermes.profile.value
        _chats.update { it.markingRead(row.sessionId) }
        scope.launch {
            val marked = try {
                hermes.rest { it.setUnread(row.sessionId, profile, false) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!marked && hermes.profile.value == profile) _chats.update { it.markingRead(row.sessionId, unread = true) }
        }
    }

    // endregion

    // region More: Voice Jobs, Shortcuts, Voice options

    /** A settled Voice Job: its outcome is spoken again in the conversation this starts (or the one already open). */
    fun replayJob(row: CarJobRow) {
        if (!row.canReplay) return
        val request = ++startRequest
        scope.launch {
            // The conversation opens first, so the replayed notice meets a session that can speak it.
            performListen(request)
            awaitConversationStart(request)
            // A conversation that did not open shows its error; the outcome isn't queued for whichever chat Voice opens next.
            if (request != startRequest || !hasOpenConversation() || _shownState.value == CarVoiceState.ERROR) return@launch
            hub.jobs.replayOutcome(row.id)
        }
    }

    /** A shortcut: its prompt starts as a Voice Job, and the conversation this opens speaks the result when the job is done. */
    fun runShortcut(shortcut: CarShortcut) {
        val request = ++startRequest
        scope.launch {
            if (!awaitHermes { request == startRequest }) {
                if (request == startRequest) forward(CarVoiceState.ERROR, CarVoiceIssue.NOT_CONNECTED)
                return@launch
            }
            var created: String? = null
            hub.jobs.startJob(shortcut.prompt) { created = it }
            if (request != startRequest) return@launch
            // A refused job (too many running) has no outcome to wait for, so the car shows Error instead.
            if (created == null) {
                forward(CarVoiceState.ERROR)
                return@launch
            }
            performListen(request)
        }
    }

    /** A live call connects after its start returns: the replay waits for it to leave Connecting. */
    private suspend fun awaitConversationStart(request: Long) {
        val live = observedMode.live ?: return
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            LiveCallView.of(calls, live).phase.first { request != startRequest || it != LiveCallPhase.Connecting }
        }
    }

    /** Whether the current mode's conversation is open (or connecting), so it can take a notice. */
    private fun hasOpenConversation(): Boolean = when (val live = observedMode.live) {
        null -> hub.conversationOpen.value && hub.conversation.hasLiveVoiceSession
        else -> when (LiveCallView.of(calls, live).phase.value) {
            LiveCallPhase.Connecting, LiveCallPhase.Reconnecting, LiveCallPhase.Listening, LiveCallPhase.Speaking, LiveCallPhase.Paused -> true
            LiveCallPhase.Idle, is LiveCallPhase.Failed, LiveCallPhase.Ending -> false
        }
    }

    /** The shortcuts the car's grid offers, from Settings > Android Auto. */
    fun shortcuts(): List<CarShortcut> = CarPreferences.shortcuts(settings)

    fun modeRows(): List<CarModeRow> = CarBrowse.modeRows(currentMode())

    fun agentRows(): List<CarOptionRow> = CarBrowse.agentRows(_agents.value, hermes.profile.value)

    /** Reads the profiles for the Voice list. */
    fun loadAgents() {
        if (!hermes.isSignedIn) return
        scope.launch {
            runCatching { hermes.rest { it.profiles() } }.onSuccess { profiles ->
                _agents.value = profiles
                hub.knownProfiles = profiles
            }
        }
    }

    /** Switches the profile's voice mode from the car. The running conversation ends first, as switching on the phone does. */
    fun selectVoiceMode(mode: CarVoiceMode) {
        if (mode == currentMode() || !hermes.isSignedIn) return
        startRequest++
        endConversation()
        hub.updatePreferences { CarVoiceMode.applying(mode, it) }
        observeCurrentMode()
        forward(CarVoiceState.READY)
    }

    /** Switches the agent (Hermes profile) from the car; the conversation ends first, as switching on the phone does. */
    fun selectAgent(profile: String) {
        if (profile == hermes.profile.value || profile !in _agents.value) return
        startRequest++
        endConversation()
        forward(CarVoiceState.READY)
        // The picked chat belongs to the agent left behind.
        chosenChat = null
        _controls.update { it.copy(chatTitle = null) }
        _chats.value = CarChatList(emptyList(), emptyList())
        ProfileSwitching.select(hermes, profile)
    }

    // endregion

    /**
     * The car disconnected. A conversation the car started ends with it; one
     * from the phone keeps running on the phone's microphone, suspended if
     * the phone isn't showing it. A live call goes on only where the phone
     * shows it (its call screen or minimised bar, with Conduit on screen),
     * as iOS does.
     */
    fun release() {
        startRequest++
        onConversationShown = null
        onChatsWanted = null
        if (hub.onConversationStarting === conversationStarting) hub.onConversationStarting = null
        closeCarConversation()
        if (calls.mode.value != null && !CarSurfaces.keepsLiveCall(ForegroundState.visible, calls.screenVisible, calls.minimised.value)) calls.close()
        hub.useExternalMicrophone(null)
        hub.setCarSurfaceActive(false)
        earcons.release()
        CarVoiceArtwork.clearCache()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
