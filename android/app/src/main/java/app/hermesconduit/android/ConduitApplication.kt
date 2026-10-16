package app.hermesconduit.android

import android.app.Application
import android.content.Context
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.AppSettings

class ConduitApplication : Application() {
    val connection: HermesConnection by lazy { HermesConnection(this) }
    val settings: AppSettings by lazy { AppSettings(this) }
    val notifications by lazy { app.hermesconduit.android.notify.NotificationMonitor(this) }

    override fun onCreate() {
        super.onCreate()
        L10n.load(this, settings.getString(AppSettings.LANGUAGE, "system"))
        app.hermesconduit.android.core.Haptics.init(this)
        app.hermesconduit.android.notify.ForegroundState.install()
        notifications.start()
        app.hermesconduit.android.notify.ConnectionService.sync(this)
        app.hermesconduit.android.ui.voice.VoiceHub.install(this)
        app.hermesconduit.android.ui.device.DeviceSharing.start(this)
        app.hermesconduit.android.wake.WakePhrase.get(this).start()
    }
}

val Context.hermes: HermesConnection get() = (applicationContext as ConduitApplication).connection
private var fallbackSettings: AppSettings? = null

/** App preferences; previews and screenshot tests (no ConduitApplication) get a standalone store. */
val Context.appSettings: AppSettings
    get() = (applicationContext as? ConduitApplication)?.settings
        ?: fallbackSettings ?: AppSettings(applicationContext ?: this).also { fallbackSettings = it }
