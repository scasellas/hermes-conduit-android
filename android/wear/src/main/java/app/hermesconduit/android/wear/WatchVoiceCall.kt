package app.hermesconduit.android.wear

import android.content.Context
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchJobAnswer
import app.hermesconduit.wearwire.WearApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The voice a watch call talks to, picked on the watch (iOS `WatchVoiceEngine`). */
enum class WatchVoiceEngine(val raw: String) {
    GEMINI_LIVE("geminiLive"),
    GPT_LIVE("gptLive"),
    GROK_LIVE("grokLive"),
    /** Android's own: each turn through Conduit on the phone, spoken by the watch (the only voice that continues a chat). */
    PHONE("phone");

    val title: String
        get() = when (this) {
            GEMINI_LIVE -> L10n.tr("Gemini Live")
            GPT_LIVE -> L10n.tr("GPT-Live")
            GROK_LIVE -> L10n.tr("Grok")
            PHONE -> L10n.tr("Hermes through your phone")
        }

    val detail: String
        get() = when (this) {
            GEMINI_LIVE -> L10n.tr("Runs on your Watch")
            GPT_LIVE, GROK_LIVE -> L10n.tr("Runs on your Hermes host")
            PHONE -> L10n.tr("Each turn runs through Conduit on your phone")
        }

    /** This voice as the phone names it ([WatchCallVoices]); null for one the phone doesn't check. */
    val callVoice: String?
        get() = when (this) {
            GEMINI_LIVE -> WatchCallVoices.GEMINI_LIVE
            GPT_LIVE -> WatchCallVoices.GPT_LIVE
            GROK_LIVE -> WatchCallVoices.GROK_LIVE
            PHONE -> null
        }

    companion object {
        const val STORAGE_KEY = "watchVoice.engine"
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * The one call the watch app shows, whichever engine runs it (iOS
 * `WatchVoiceCall`): Gemini Live on the watch itself or Grok on the Hermes
 * host ([WatchDirectCall], which runs both conversations), GPT-Live through
 * the Hermes host's audio bridge ([WatchBridgeLiveCall]), or a turn-by-turn
 * call through the phone ([WatchCall]). The screens read the call from here
 * and send its controls here, so they never depend on the engine.
 */
class WatchVoiceCall(
    context: Context,
    scope: CoroutineScope,
    link: PhoneLink,
    log: WatchCallLog,
    private val prefs: WatchPrefs,
) {
    val phone = WatchCall(context, scope, link, log)
    private val direct = WatchDirectCall(context, scope, link, log)
    private val bridge = WatchBridgeLiveCall(context, scope, link, log)
    private val callEngine = MutableStateFlow(prefs.engine.value)

    /** The call on screen. A change of voice during a call doesn't move it. */
    val state: StateFlow<WatchCall.State> = combine(callEngine, phone.state, direct.state, bridge.state) { engine, p, d, b ->
        when (engine) {
            WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> d
            WatchVoiceEngine.GPT_LIVE -> b
            WatchVoiceEngine.PHONE -> p
        }
    }.stateIn(scope, SharingStarted.Eagerly, WatchCall.State())

    /** A call is starting, running or ending. */
    val isActive: Boolean
        get() = when (callEngine.value) {
            WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> direct.callActive
            WatchVoiceEngine.GPT_LIVE -> bridge.callActive
            WatchVoiceEngine.PHONE -> phone.state.value.isActive
        }

    /** Starts a call on the chosen voice; continuing one of the phone's chats runs through the phone. */
    fun start(source: String, chatId: String? = null, chatTitle: String? = null, engine: WatchVoiceEngine = prefs.engine.value) {
        if (isActive) return
        val chosen = if (chatId != null) WatchVoiceEngine.PHONE else engine
        dismissEnded()
        callEngine.value = chosen
        when (chosen) {
            WatchVoiceEngine.GEMINI_LIVE -> direct.start(WatchDirectCall.Engine.GEMINI)
            WatchVoiceEngine.GROK_LIVE -> direct.start(WatchDirectCall.Engine.GROK)
            WatchVoiceEngine.GPT_LIVE -> bridge.start()
            WatchVoiceEngine.PHONE -> phone.start(source, chatId, chatTitle)
        }
    }

    fun end() = when (callEngine.value) {
        WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> direct.end()
        WatchVoiceEngine.GPT_LIVE -> bridge.end()
        WatchVoiceEngine.PHONE -> phone.end()
    }

    /** Stops the voice while it speaks; brings the microphone back after another app took it. */
    fun tap() = when (callEngine.value) {
        WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> direct.tap()
        WatchVoiceEngine.GPT_LIVE -> bridge.tap()
        WatchVoiceEngine.PHONE -> phone.tap()
    }

    fun toggleMute() = when (callEngine.value) {
        WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> direct.toggleMute()
        WatchVoiceEngine.GPT_LIVE -> bridge.toggleMute()
        WatchVoiceEngine.PHONE -> phone.toggleMute()
    }

    /** Back to the home screen after a call's summary. */
    fun dismissEnded() {
        phone.dismissEnded()
        direct.dismissEnded()
        bridge.dismissEnded()
    }

    /** The phone call's answer from the system voice input screen. */
    fun voiceScreenResult(text: String?) = phone.voiceScreenResult(text)

    /** A chat's approval request on the phone call's card. */
    fun answerApproval(approval: WearApproval, approve: Boolean) = phone.answerApproval(approval, approve)

    /** A live call's job approval, answered through the call's grant; never one queued behind it. */
    fun answerJobApproval(approval: WatchJobAnswer.Approval, approve: Boolean) = when (callEngine.value) {
        WatchVoiceEngine.GEMINI_LIVE, WatchVoiceEngine.GROK_LIVE -> direct.answerApproval(approval, approve)
        WatchVoiceEngine.GPT_LIVE -> bridge.answerApproval(approval, approve)
        WatchVoiceEngine.PHONE -> Unit
    }

    companion object {
        /** Why a call ended, when nothing went wrong: the screens don't show these as errors. */
        const val END_BY_USER = "ended on the Watch"
        const val GOODBYE = "Hermes said goodbye."

        fun isNormalEnd(reason: String?) = reason == null || reason == END_BY_USER || reason == GOODBYE
    }
}
