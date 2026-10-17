package app.hermesconduit.android.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.hermesconduit.android.ui.phone.ConduitAlarms

/**
 * Restarts the background connection after a reboot when notifications are
 * on, and sets Conduit's alarms again: the system forgets them at a reboot,
 * and they follow the clock and time zone, an app update, and exact alarms
 * being allowed again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                ConnectionService.sync(context)
                ConduitAlarms.of(context).rescheduleAll()
            }
            Intent.ACTION_TIMEZONE_CHANGED -> ConduitAlarms.of(context).rescheduleAll(zoneChanged = true)
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ConduitAlarms.of(context).rescheduleAll()
        }
    }
}
