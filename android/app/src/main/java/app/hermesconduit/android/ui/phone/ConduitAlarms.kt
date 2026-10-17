package app.hermesconduit.android.ui.phone

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.PhoneAlarmBook
import app.hermesconduit.android.model.PhoneAlarmFailure
import app.hermesconduit.android.model.PhoneAlarmRecord
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangeKind
import app.hermesconduit.android.notify.ConduitNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Alarms and timers Hermes sets on this phone, which Conduit owns and rings
 * itself (iOS PhoneAlarmSource with AlarmKit). Each is an exact
 * `setAlarmClock` alarm, shown in the status bar and kept through Doze; it
 * rings as a full-screen notification on the Alarms channel, which plays the
 * default alarm sound on the alarm stream (so it rings in Silent mode) and
 * bypasses Do Not Disturb. Hermes sees only these, as the `alarms` list,
 * with the names Conduit remembers for them.
 */
class ConduitAlarmSource(context: Context) : PhoneAlarmSource {
    private val alarms = ConduitAlarms.of(context)

    override val isSupported: Boolean get() = true

    /** Exact alarms allowed (Android 12+ asks) and notifications allowed (Android 13+), without which nothing rings. */
    override val authorized: Boolean get() = alarms.canScheduleExact() && alarms.canRing()

    override fun alarms(now: Instant, zone: ZoneId): List<JsonObject>? {
        if (!authorized) return emptyList()
        return PhoneAlarmBook.listing(alarms.records(now), now, zone)
    }

    override suspend fun apply(change: PhoneChange, now: Instant): String {
        if (!authorized) throw PhoneSourceException(L10n.tr("Conduit isn't allowed to set alarms. Allow it in the Settings app."))
        if (change.kind == PhoneChangeKind.CANCEL_ALARM) {
            val id = change.alarmID?.lowercase().orEmpty()
            cancel(id)
            return id
        }
        val record = try {
            PhoneAlarmBook.record(change, now, ZoneId.systemDefault())
        } catch (e: PhoneAlarmFailure) {
            throw PhoneSourceException(
                L10n.tr(if (e.reason == PhoneAlarmFailure.Reason.PAST) "That alarm time has already passed." else "That alarm isn't on this phone any more."),
            )
        }
        return alarms.add(record)
    }

    override fun cancel(id: String) {
        if (!alarms.remove(id.lowercase())) throw PhoneSourceException(L10n.tr("That alarm isn't on this phone any more."))
    }
}

/** Conduit's alarms on Android: kept in settings, scheduled with AlarmManager, rung and stopped. */
class ConduitAlarms private constructor(private val context: Context) {
    private val settings = context.appSettings
    private val manager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val lock = Any()
    private val _ringing = MutableStateFlow(ringingNow(load(), Instant.now()))
    /** The ids ringing now, for the alarm screen. */
    val ringing: StateFlow<Set<String>> = _ringing

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    fun canRing(): Boolean = ConduitNotifications.canPost(context)

    // region Records

    private fun load(): Map<String, PhoneAlarmRecord> {
        if (settings.getString(PhoneAlarmRecord.LEGACY_STORAGE_KEY, "").isNotEmpty()) settings.set(PhoneAlarmRecord.LEGACY_STORAGE_KEY, "")
        return PhoneAlarmRecord.decodeAll(settings.getString(PhoneAlarmRecord.STORAGE_KEY, "").ifEmpty { null })
    }

    private fun save(records: Map<String, PhoneAlarmRecord>, now: Instant = Instant.now()) {
        settings.set(PhoneAlarmRecord.STORAGE_KEY, if (records.isEmpty()) "" else PhoneAlarmRecord.encodeAll(records))
        _ringing.value = ringingNow(records, now)
    }

    private fun ringingNow(records: Map<String, PhoneAlarmRecord>, now: Instant) =
        records.filterValues { PhoneAlarmBook.isRinging(it, now) }.keys

    /** The alarms still on the phone, after forgetting the ones that rang and went. */
    fun records(now: Instant = Instant.now()): Map<String, PhoneAlarmRecord> = synchronized(lock) {
        val all = load()
        val kept = PhoneAlarmBook.tidied(all, now)
        if (kept != all) {
            save(kept, now)
            (all.keys - kept.keys).forEach { cancelRing(it) }
        }
        kept
    }

    fun record(id: String): PhoneAlarmRecord? = records()[id]

    /** Keeps and schedules a new alarm; its id. */
    fun add(record: PhoneAlarmRecord): String = synchronized(lock) {
        val id = java.util.UUID.randomUUID().toString().lowercase()
        val now = Instant.now()
        save(records(now) + (id to record), now)
        try {
            schedule(id, record, now)
        } catch (e: Exception) {
            save(load() - id, now)
            throw PhoneSourceException(L10n.tr("Alarms couldn't be reached just now. Try again."))
        }
        id
    }

    /** Takes an alarm away, ringing or not; false when it isn't Conduit's any more. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val records = records()
        if (id !in records) return false
        manager.cancel(fireIntent(id))
        cancelRing(id)
        save(records - id)
        true
    }

    // endregion

    // region Scheduling

    /**
     * Sets every alarm again: after a reboot (the system forgets them), an
     * app update, the clock or time zone changing, or exact alarms being
     * allowed again. A one-off clock-time alarm follows the clock to a new
     * time zone.
     */
    fun rescheduleAll(zoneChanged: Boolean = false) = synchronized(lock) {
        val now = Instant.now()
        var records = records(now)
        if (zoneChanged) {
            val moved = PhoneAlarmBook.rezoned(records, now, ZoneId.systemDefault())
            if (moved != records) save(moved, now)
            records = moved
        }
        for ((id, record) in records) runCatching { schedule(id, record, now) }
    }

    private fun schedule(id: String, record: PhoneAlarmRecord, now: Instant) {
        val at = PhoneAlarmBook.nextFire(record, now, ZoneId.systemDefault()) ?: return
        val operation = fireIntent(id)
        if (canScheduleExact()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), showIntent()), operation)
        } else {
            // Exact alarms were taken back: ring as close as Android allows until they're allowed again.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }

    private fun broadcast(action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ConduitAlarmReceiver::class.java).setAction(action).setData("conduit-alarm:$id".toUri()).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun fireIntent(id: String): PendingIntent = broadcast(ACTION_FIRE, id)

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, SHOW_REQUEST, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // endregion

    // region Ringing

    /** The system alarm went off: ring, and set a repeating alarm's next day. */
    fun fire(id: String) = synchronized(lock) {
        val now = Instant.now()
        val records = PhoneAlarmBook.fired(load(), id, now)
        val record = records[id] ?: return@synchronized
        save(records, now)
        runCatching { schedule(id, record, now) }
        val silenceAt = now.toEpochMilli() + PhoneAlarmBook.AUTO_SILENCE_MS
        runCatching {
            if (canScheduleExact()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, silenceAt, broadcast(ACTION_SILENCE, id))
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, silenceAt, broadcast(ACTION_SILENCE, id))
        }
        ConduitAlarmNotifications.postRinging(context, id, record, now)
    }

    /** Stop, or the ring ran out: a one-off is done, a repeating alarm waits for its next day. */
    fun stop(id: String) = synchronized(lock) {
        cancelRing(id)
        val records = load()
        val record = records[id] ?: return@synchronized
        if (record.ringingSince == null) return@synchronized
        save(PhoneAlarmBook.stopped(records, id))
    }

    private fun cancelRing(id: String) {
        manager.cancel(broadcast(ACTION_SILENCE, id))
        ConduitNotifications.cancel(context, ConduitAlarmNotifications.notificationID(id))
    }

    // endregion

    companion object {
        const val ACTION_FIRE = "app.hermesconduit.android.ALARM_FIRE"
        const val ACTION_STOP = "app.hermesconduit.android.ALARM_STOP"
        const val ACTION_SILENCE = "app.hermesconduit.android.ALARM_SILENCE"
        const val EXTRA_ID = "alarm_id"
        private const val SHOW_REQUEST = 7_400

        @Volatile private var instance: ConduitAlarms? = null

        fun of(context: Context): ConduitAlarms = instance ?: synchronized(this) {
            instance ?: ConduitAlarms(context.applicationContext).also { instance = it }
        }
    }
}

/** The ringing alarm: a full-screen notification on the Alarms channel, with Stop. */
object ConduitAlarmNotifications {
    const val CHANNEL_ALARMS = "conduit_alarms"

    fun notificationID(alarmID: String) = ConduitNotifications.idFor("alarm-", alarmID)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ALARMS) != null) return
        val channel = NotificationChannel(CHANNEL_ALARMS, L10n.tr("Alarms"), NotificationManager.IMPORTANCE_HIGH).apply {
            // The alarm stream rings in Silent mode, at the alarm volume, like the Clock app's.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** "Wake up", "Timer": the name it was given, or what it is. */
    fun title(record: PhoneAlarmRecord): String = record.label ?: L10n.tr(if (record.kind == "timer") "Timer" else "Alarm")

    fun timeLine(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.from(at.atZone(zone)))

    fun ringIntent(context: Context, alarmID: String): PendingIntent = PendingIntent.getActivity(
        context, notificationID(alarmID),
        Intent(context, AlarmRingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .setData("conduit-alarm:$alarmID".toUri())
            .putExtra(ConduitAlarms.EXTRA_ID, alarmID),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(context: Context, alarmID: String): PendingIntent = PendingIntent.getBroadcast(
        context, notificationID(alarmID),
        Intent(context, ConduitAlarmReceiver::class.java).setAction(ConduitAlarms.ACTION_STOP)
            .setData("conduit-alarm:$alarmID".toUri()).putExtra(ConduitAlarms.EXTRA_ID, alarmID),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun postRinging(context: Context, alarmID: String, record: PhoneAlarmRecord, now: Instant) {
        ensureChannel(context)
        val ring = ringIntent(context, alarmID)
        val stop = stopIntent(context, alarmID)
        val notification = ConduitNotifications.builder(context, CHANNEL_ALARMS)
            .setContentTitle(title(record))
            .setContentText(timeLine(now))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(ring, true)
            .setContentIntent(ring)
            .addAction(0, L10n.tr("Stop"), stop)
            .setDeleteIntent(stop)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(now.toEpochMilli())
            .setShowWhen(true)
            .setTimeoutAfter(PhoneAlarmBook.AUTO_SILENCE_MS)
            .build()
        // The sound and vibration loop until Stop or the timeout.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        ConduitNotifications.post(context, notificationID(alarmID), notification)
    }
}

/** An alarm going off, Stop on its notification, and the ring running out. */
class ConduitAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ConduitAlarms.EXTRA_ID) ?: return
        val alarms = ConduitAlarms.of(context)
        when (intent.action) {
            ConduitAlarms.ACTION_FIRE -> alarms.fire(id)
            ConduitAlarms.ACTION_STOP, ConduitAlarms.ACTION_SILENCE -> alarms.stop(id)
        }
    }
}
