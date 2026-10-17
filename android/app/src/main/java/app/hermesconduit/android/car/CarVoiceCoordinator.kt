package app.hermesconduit.android.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.VoiceTranscriptionMode
import app.hermesconduit.android.ui.voice.VoiceConversationState
import app.hermesconduit.android.ui.voice.VoiceHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * The bridge between one Android Auto session and the app-wide Voice stack
 * (port of the iOS CarPlayVoiceCoordinator, classic mode). A presentation and
 * lifecycle seam, not a second Voice owner: it observes the shared classic
 * conversation and forwards deduplicated, mapped states to the car (never
 * microphone levels or transcript text), plays the status sounds, and routes
 * the car's buttons and chat picks into the same listen, mute and close paths
 * the phone uses. The car's microphone records while the car runs the
 * conversation.
 */
class CarVoiceCoordinator(private val carContext: CarContext) {
    private val hub = VoiceHub.get(carContext)
    private val hermes = carContext.hermes
    private val settings = carContext.appSettings
    private val job = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    private val earcons = CarEarconPlayer()

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

    /** The chat Listen talks in, picked from the car's list; null starts a new chat. */
    var chosenChat: CarChatRow? = null
        private set
    private var host: CarVoiceHost? = null
    private var lastActivated: CarVoiceState? = null
    /** Bumped by every start, pick and End: an older start that is still waiting stops there. */
    private var startRequest = 0L

    /**
     * A conversation is listening or talking (one started on the phone, say):
     * the session shows the voice screen rather than a list the driver has to
     * back out of first.
     */
    var onConversationShown: (() -> Unit)? = null

    init {
        scope.launch { hub.conversation.state.collect { handleControllerState(it) } }
        scope.launch { hub.conversation.isMicrophonePaused.collect { paused -> _controls.update { it.copy(isMicrophoneMuted = paused) } } }
        scope.launch { hermes.events.collect { event -> host?.observe(event) } }
    }

    // region State

    fun handleControllerState(state: VoiceConversationState) = forward(CarVoiceState.map(state))

    private fun forward(target: CarVoiceState, issue: CarVoiceIssue? = null) {
        // The Error title names what to fix, worked out from the app's own state.
        if (target == CarVoiceState.ERROR) _controls.update { it.copy(errorIssue = issue ?: setupIssue()) }
        val activated = CarVoiceStateActivation.activationTarget(lastActivated, target) ?: return
        val previous = lastActivated
        lastActivated = activated
        _shownState.value = activated
        if (CarPreferences.playsSounds(settings)) CarEarcon.forTransition(previous, activated)?.let(earcons::play)
        // Thinking alone doesn't count: a conversation that is ending passes through it.
        if (activated == CarVoiceState.LISTENING || activated == CarVoiceState.RESPONDING) onConversationShown?.invoke()
    }

    /** What stops classic Voice, as far as the app can tell (iOS `setupIssue`). */
    private fun setupIssue(): CarVoiceIssue? {
        val capability = hub.capability.value
        val transcribesOnHermes = hub.preferences().resolvedTranscriptionMode == VoiceTranscriptionMode.HERMES
        return CarVoiceIssue.of(
            isConnected = hermes.isSignedIn && hermes.link.value == LinkState.CONNECTED,
            isVoiceEnabled = hub.isVoiceEnabled(),
            isMicrophoneDenied = !hasMicrophonePermission(),
            speechToTextReady = capability?.let { !transcribesOnHermes || it.supportsTranscription },
            textToSpeechReady = capability?.supportsSpeech,
        )
    }

    // endregion

    // region Controls

    /** Listen (Ready and Error): continues the conversation, or starts one in the chosen chat. */
    fun listen() {
        val request = ++startRequest
        scope.launch { performListen(request) }
    }

    /** New Chat, and New voice chat from the list: listening starts in a new chat. */
    fun startNewChat() {
        closeConversation()
        chosenChat = null
        _controls.update { it.copy(chatTitle = null) }
        listen()
    }

    /** A chat from the list: Voice starts in that chat at once, with no Listen tap. */
    fun openChat(row: CarChatRow) {
        closeConversation()
        chosenChat = row
        _controls.update { it.copy(chatTitle = row.title) }
        listen()
    }

    /** Mute or Listen while a conversation runs: the same microphone pause the phone's controls use. */
    fun toggleMicrophone() {
        val controller = hub.conversation
        if (controller.isMicrophonePaused.value) {
            hub.onConversationForegrounded()
            // A failed resume settles the controller into FAILED, which the state observation forwards.
            controller.resumeMicrophone()
        } else {
            controller.pauseMicrophone()
        }
    }

    /** End: the authoritative close, whoever started the conversation. */
    fun end() {
        startRequest++
        closeConversation()
        // An Error shown without the controller failing (no microphone, no Hermes) goes back to Ready too.
        forward(CarVoiceState.READY)
    }

    private suspend fun performListen(request: Long) {
        fun isWanted() = request == startRequest && job.isActive
        if (!requestMicrophone()) {
            if (isWanted()) forward(CarVoiceState.ERROR, CarVoiceIssue.MICROPHONE_DENIED)
            return
        }
        // Hermes may still be reconnecting (the phone is usually locked in a car), so the start waits for it.
        if (!awaitHermes()) {
            if (isWanted()) forward(CarVoiceState.ERROR, CarVoiceIssue.NOT_CONNECTED)
            return
        }
        if (!isWanted()) return
        if (hub.capability.value == null) hub.refreshCapabilities()
        if (!isWanted()) return
        val controller = hub.conversation
        if (hub.conversationOpen.value && controller.hasLiveVoiceSession) {
            // A conversation is open (the car's, or one started on the phone): Listen continues it.
            hub.onConversationForegrounded()
            when {
                controller.isMicrophonePaused.value -> controller.resumeMicrophone()
                controller.currentState == VoiceConversationState.IDLE || controller.currentState == VoiceConversationState.FAILED -> controller.startListening()
                else -> Unit
            }
            return
        }
        hub.useExternalMicrophone(if (CarMicrophone.isAvailable(carContext)) CarMicrophone(carContext) else null)
        val carHost = CarVoiceHost(hub, chosenChat) { conversationEnded() }
        if (hub.openConversation(carHost) != null) {
            hub.useExternalMicrophone(null)
            forward(CarVoiceState.ERROR)
            return
        }
        host = carHost
        hub.onConversationForegrounded()
        controller.startListening()
    }

    /**
     * Closes the open conversation, the car's or the phone's, before one
     * starts in another chat: the shared controller speaks into one chat.
     */
    private fun closeConversation() {
        if (hub.conversationOpen.value) hub.closeConversation()
        conversationEnded()
    }

    /** Closes a conversation the car started; one started on the phone keeps running. */
    private fun closeCarConversation() {
        val carHost = host ?: return
        if (hub.conversation.host === carHost) hub.closeConversation()
        conversationEnded()
    }

    private fun conversationEnded() {
        host = null
        hub.useExternalMicrophone(null)
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

    private suspend fun awaitHermes(): Boolean {
        if (!hermes.isSignedIn) return false
        if (hermes.link.value == LinkState.CONNECTED) return true
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

    // endregion

    /** The car disconnected: a conversation the car started ends with it; one from the phone keeps running. */
    fun release() {
        startRequest++
        onConversationShown = null
        closeCarConversation()
        earcons.release()
        CarVoiceArtwork.clearCache()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
