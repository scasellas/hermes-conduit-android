package app.hermesconduit.android.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HermesCallCopy
import app.hermesconduit.android.model.HermesCallTarget
import app.hermesconduit.android.notify.ConduitNotifications
import kotlinx.serialization.json.JsonObject

/**
 * The ringing call (a full-screen `CallStyle.forIncomingCall` notification
 * on a high-importance calls channel) and the "Hermes wants to talk" and
 * missed-call notices with their Talk button (iOS HermesCallNotifications).
 */
object HermesCallNotifications {
    const val CHANNEL_CALLS = "hermes_calls"
    const val RINGING_ID = 7310

    /** MainActivity: answer the call this routing names (or Talk on a notice). */
    const val EXTRA_CALL = "hermes_call"
    const val ACTION_DECLINE = "app.hermesconduit.android.HERMES_CALL_DECLINE"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_CALLS) != null) return
        val channel = NotificationChannel(CHANNEL_CALLS, L10n.tr("Calls from Hermes"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** Calls can be shown: notifications allowed and the calls channel not turned off. */
    fun canRing(context: Context): Boolean {
        if (!ConduitNotifications.canPost(context)) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_CALLS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Whether the system lets the ring take the full screen (Android 14+ asks the user). */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun routing(target: HermesCallTarget): String = target.toRouting().toString()

    fun target(routing: String?): HermesCallTarget? =
        routing?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }?.let(HermesCallTarget::parse)

    /** Opens Conduit on the call: its chat, then classic voice. */
    fun answerIntent(context: Context, target: HermesCallTarget, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CALL, routing(target))
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun declineIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RINGING_ID,
        Intent(context, HermesCallReceiver::class.java).setAction(ACTION_DECLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun fullScreenIntent(context: Context, target: HermesCallTarget, callerLine: String): PendingIntent = PendingIntent.getActivity(
        context, RINGING_ID + 1,
        Intent(context, HermesCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(EXTRA_CALL, routing(target))
            .putExtra(HermesCallActivity.EXTRA_CALLER, callerLine),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [localOnly]: the watch rings the call itself, so it isn't bridged there too. */
    fun postRinging(context: Context, target: HermesCallTarget, callerLine: String, localOnly: Boolean = false) {
        ensureChannel(context)
        val caller = Person.Builder().setName(callerLine).setKey("hermes").setImportant(true).setBot(true).build()
        val summary = HermesCallCopy.summary(target.call.kind, target.call.title, target.call.reason)
        val notification = ConduitNotifications.builder(context, CHANNEL_CALLS)
            .setContentTitle(callerLine)
            .setContentText(summary)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent(context), answerIntent(context, target, RINGING_ID + 2)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent(context, target, callerLine), true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setLocalOnly(localOnly)
            .setTimeoutAfter(HermesCalls.UNANSWERED_AFTER_MS + 5_000)
            .build()
        // Rings until answered, declined or timed out.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        ConduitNotifications.post(context, RINGING_ID, notification)
    }

    fun cancelRinging(context: Context) = ConduitNotifications.cancel(context, RINGING_ID)

    private fun noticeId(target: HermesCallTarget) = ConduitNotifications.idFor("hermes-call-", target.sessionId)

    /** "Hermes wants to talk", or "Missed call from …", with a Talk button that opens the call. */
    fun postNotice(context: Context, target: HermesCallTarget, missed: Boolean, callerName: String) {
        val id = noticeId(target)
        val open = answerIntent(context, target, id)
        val notification = ConduitNotifications.builder(context, ConduitNotifications.CHANNEL_ATTENTION)
            .setContentTitle(if (missed) HermesCallCopy.missedCallTitle(callerName) else HermesCallCopy.notificationTitle)
            .setContentText(HermesCallCopy.summary(target.call.kind, target.call.title, target.call.reason))
            .setCategory(if (missed) NotificationCompat.CATEGORY_MISSED_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(0, HermesCallCopy.talkAction, open)
            .setAutoCancel(true)
            .build()
        ConduitNotifications.ensureChannels(context)
        ConduitNotifications.post(context, id, notification)
    }

    fun cancelNotice(context: Context, target: HermesCallTarget) = ConduitNotifications.cancel(context, noticeId(target))
}

/** Decline from the ringing notification. */
class HermesCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HermesCallNotifications.ACTION_DECLINE) HermesCalls.get(context).decline()
    }
}
