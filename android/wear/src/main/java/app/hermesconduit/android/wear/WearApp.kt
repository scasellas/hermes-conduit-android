package app.hermesconduit.android.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WearApp : Application() {
    val scope = MainScope()
    val link by lazy { PhoneLink(this, scope) }
    val log by lazy { WatchCallLog(this) { link } }
    val prefs by lazy { WatchPrefs(this) }
    /** The app's one call, whichever voice runs it. */
    val call by lazy { WatchVoiceCall(this, scope, link, log, prefs) }
    /** Calls from Hermes ringing here too. */
    val hermesCalls by lazy { HermesWatchCalls(this, scope, link, log, prefs) { call } }

    override fun onCreate() {
        super.onCreate()
        L10n.load(this)
        link.start()
        hermesCalls.start()
    }
}

val Context.wearApp: WearApp get() = applicationContext as WearApp

/** The watch's own settings (iOS WatchSettingsView). */
class WatchPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val _callOnOpen = MutableStateFlow(prefs.getBoolean(CALL_ON_OPEN, true))
    /** Opening Conduit starts a call right away. */
    val callOnOpen: StateFlow<Boolean> = _callOnOpen

    fun setCallOnOpen(on: Boolean) {
        prefs.edit().putBoolean(CALL_ON_OPEN, on).apply()
        _callOnOpen.value = on
    }

    private val _engine = MutableStateFlow(WatchVoiceEngine.of(prefs.getString(WatchVoiceEngine.STORAGE_KEY, null)) ?: WatchVoiceEngine.GEMINI_LIVE)
    /** The voice the next call talks to. */
    val engine: StateFlow<WatchVoiceEngine> = _engine

    fun setEngine(engine: WatchVoiceEngine) {
        prefs.edit().putString(WatchVoiceEngine.STORAGE_KEY, engine.raw).apply()
        _engine.value = engine
    }

    companion object {
        const val CALL_ON_OPEN = "startsOnOpen"
    }
}
