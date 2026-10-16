package app.hermesconduit.android.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.R
import app.hermesconduit.android.core.L10n

/** Channels and builders for Conduit's local notifications. */
object ConduitNotifications {
    const val CHANNEL_ATTENTION = "attention"
    const val CHANNEL_ATTENTION_QUIET = "attention_quiet"
    const val CHANNEL_COMPLETION = "completion"
    const val CHANNEL_COMPLETION_QUIET = "completion_quiet"
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_SERVICE = "service"

    const val EXTRA_SESSION = "open_session"
    const val EXTRA_TITLE = "open_title"
    const val EXTRA_DASHBOARD = "open_dashboard"
    const val EXTRA_PROFILE = "open_profile"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: String, importance: Int, silent: Boolean = false) =
            NotificationChannel(id, name, importance).apply { if (silent) setSound(null, null) }
        manager.createNotificationChannels(
            listOf(
                channel(CHANNEL_ATTENTION, L10n.tr("Approval & input"), NotificationManager.IMPORTANCE_HIGH),
                channel(CHANNEL_ATTENTION_QUIET, L10n.tr("Approval & input (silent)"), NotificationManager.IMPORTANCE_HIGH, silent = true),
                channel(CHANNEL_COMPLETION, L10n.tr("Responses"), NotificationManager.IMPORTANCE_DEFAULT),
                channel(CHANNEL_COMPLETION_QUIET, L10n.tr("Responses (silent)"), NotificationManager.IMPORTANCE_DEFAULT, silent = true),
                channel(CHANNEL_PROGRESS, L10n.tr("Progress"), NotificationManager.IMPORTANCE_LOW, silent = true),
                channel(CHANNEL_SERVICE, L10n.tr("Background connection"), NotificationManager.IMPORTANCE_MIN, silent = true),
            ),
        )
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openIntent(context: Context, sessionId: String?, title: String?, dashboardId: String?, profile: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SESSION, sessionId)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_DASHBOARD, dashboardId)
            .putExtra(EXTRA_PROFILE, profile)
        return PendingIntent.getActivity(context, (sessionId ?: "app").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun builder(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFFE0AB47.toInt())

    @Suppress("MissingPermission")
    fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun idFor(kind: String, sessionId: String) = (kind + sessionId).hashCode()
}
