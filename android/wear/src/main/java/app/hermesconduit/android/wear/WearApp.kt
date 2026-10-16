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
    val call by lazy { WatchCall(this, scope, link, log) }
    val prefs by lazy { WatchPrefs(this) }

    override fun onCreate() {
        super.onCreate()
        L10n.load(this)
        link.start()
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

    companion object {
        const val CALL_ON_OPEN = "startsOnOpen"
    }
}
