package app.hermesconduit.android.integration

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.notify.NotificationPrefs
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

/** Notification plumbing shared by the notification and call tests. */
object Notifications {
    val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    val shadow: ShadowNotificationManager get() = shadowOf(manager)

    /** Notifications allowed (Android 13+ permission) and turned on in Settings > Notifications. */
    fun allow() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.settings.set(NotificationPrefs.ENABLED, true)
    }

    /** Opens Conduit, then sends it to the background the way Home does: started, then stopped. */
    fun launchAndBackground(): ActivityScenario<MainActivity> {
        Integration.runStartupInitializers()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        pump(message = "Conduit in the foreground") { ForegroundState.visible }
        scenario.moveToState(Lifecycle.State.CREATED)
        // ProcessLifecycleOwner reports the stop after its 700 ms debounce.
        pump(message = "Conduit in the background") { !ForegroundState.visible }
        return scenario
    }

    fun posted(id: Int): Notification? = shadow.getNotification(id)

    fun awaitPosted(id: Int, what: String): Notification {
        pump(message = what) { posted(id) != null }
        return posted(id)!!
    }

    /** Fires a notification action's PendingIntent the way the system does, with an optional fill-in. */
    fun fire(pendingIntent: PendingIntent, fillIn: Intent? = null) {
        if (fillIn == null) pendingIntent.send() else pendingIntent.send(app, 0, fillIn)
    }
}
