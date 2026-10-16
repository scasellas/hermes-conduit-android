package app.hermesconduit.android

import android.app.Application
import android.content.Context
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.AppSettings

class ConduitApplication : Application() {
    val connection: HermesConnection by lazy { HermesConnection(this) }
    val settings: AppSettings by lazy { AppSettings(this) }

    override fun onCreate() {
        super.onCreate()
        L10n.load(this, settings.getString(AppSettings.LANGUAGE, "system"))
    }
}

val Context.hermes: HermesConnection get() = (applicationContext as ConduitApplication).connection
val Context.appSettings: AppSettings get() = (applicationContext as ConduitApplication).settings
