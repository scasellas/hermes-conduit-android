package app.hermesconduit.android.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Keeps a call going with the wrist down: a microphone foreground service
 * with an Ongoing Activity, so the watch face shows the call and a tap
 * returns to it.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, L10n.tr("Calls"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_talk)
            .setContentTitle(L10n.tr("Talk to Hermes"))
            .setContentText(L10n.tr("Call in progress"))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_talk)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(L10n.tr("Talk to Hermes")).build())
            .build()
            .apply(applicationContext)
        val notification = builder.build()
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        runCatching {
            if (micAllowed) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "call"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CallService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}
