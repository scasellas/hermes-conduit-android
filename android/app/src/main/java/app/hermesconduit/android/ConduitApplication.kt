package app.hermesconduit.android

import android.app.Application
import android.content.Context
import app.hermesconduit.android.core.HermesConnection

class ConduitApplication : Application() {
    val connection: HermesConnection by lazy { HermesConnection(this) }
}

val Context.hermes: HermesConnection get() = (applicationContext as ConduitApplication).connection
