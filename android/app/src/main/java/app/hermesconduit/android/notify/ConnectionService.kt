package app.hermesconduit.android.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes

/**
 * Keeps the gateway socket alive while Conduit is in the background so
 * notifications arrive (Settings > Notifications > Stay connected). The iOS
 * app gets these through the push relay, which only serves APNs devices.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ConduitNotifications.ensureChannels(this)
        val dashboard = hermes.connection.value?.displayName ?: L10n.tr("Hermes")
        val notification = ConduitNotifications.builder(this, ConduitNotifications.CHANNEL_SERVICE)
            .setContentTitle(L10n.tr("Connected to %s", dashboard))
            .setContentText(L10n.tr("Conduit tells you when Hermes needs you or finishes."))
            .setOngoing(true)
            .setContentIntent(ConduitNotifications.openIntent(this, null, null, null, null))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, 41, notification, type)
        if (hermes.gateway == null && hermes.isSignedIn) hermes.start()
        return START_STICKY
    }

    companion object {
        fun sync(context: Context) {
            val settings = (context.applicationContext as app.hermesconduit.android.ConduitApplication).settings
            val want = settings.getBool(NotificationPrefs.ENABLED, false) &&
                settings.getBool(app.hermesconduit.android.data.AppSettings.BACKGROUND_CONNECTION, true) &&
                context.hermes.isSignedIn
            val intent = Intent(context, ConnectionService::class.java)
            if (want) runCatching { ContextCompat.startForegroundService(context, intent) } else context.stopService(intent)
        }
    }
}
