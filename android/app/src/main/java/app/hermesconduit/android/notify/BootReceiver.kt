package app.hermesconduit.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the background connection after a reboot when notifications are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ConnectionService.sync(context)
    }
}
