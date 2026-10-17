package app.hermesconduit.android.ui.phone

import android.Manifest
import android.app.UiModeManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.PhoneAlarmRecord
import app.hermesconduit.android.model.PhoneAlarmRules
import app.hermesconduit.android.model.PhoneCalendarAccess
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangeKind
import app.hermesconduit.android.model.PhoneDates
import app.hermesconduit.android.model.PhoneFocusSnapshot
import app.hermesconduit.android.model.PhoneSyncLimits
import app.hermesconduit.android.model.PhoneSyncRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class PhoneSourceException(message: String) : Exception(message)

private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }
private fun Cursor.int(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getInt(it) }

/**
 * Reads the user's calendars for Hermes through CalendarContract, and
 * applies the changes Hermes asked for (iOS EventKitPhoneSource). Ids are
 * hashed: an event's from its id and the start of that occurrence, a
 * calendar's from its id, so the host never sees the provider's own ids.
 * Android has no system reminders: they come from a tasks app with the
 * OpenTasks provider ([TaskProviderReminders]), when one is installed.
 */
class CalendarContractSource(private val context: Context, val tasks: TaskProviderReminders = TaskProviderReminders(context)) : PhoneCalendarSource {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override val eventAccess: PhoneCalendarAccess
        get() = when {
            granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR) -> PhoneCalendarAccess.FULL
            granted(Manifest.permission.WRITE_CALENDAR) -> PhoneCalendarAccess.WRITE_ONLY
            else -> PhoneCalendarAccess.OFF
        }

    override val remindersAuthorized: Boolean get() = tasks.authorized

    private data class CalendarRow(val id: Long, val title: String, val color: Int, val account: String, val owner: String?, val writable: Boolean, val primary: Boolean)

    private fun calendarRows(): List<CalendarRow> {
        if (!granted(Manifest.permission.READ_CALENDAR)) return emptyList()
        val rows = mutableListOf<CalendarRow>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val access = cursor.int(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) ?: 0
                    rows += CalendarRow(
                        id = cursor.long(CalendarContract.Calendars._ID) ?: continue,
                        title = cursor.string(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME).orEmpty(),
                        color = cursor.int(CalendarContract.Calendars.CALENDAR_COLOR) ?: 0x888888,
                        account = cursor.string(CalendarContract.Calendars.ACCOUNT_NAME).orEmpty(),
                        owner = cursor.string(CalendarContract.Calendars.OWNER_ACCOUNT),
                        writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        primary = cursor.int(CalendarContract.Calendars.IS_PRIMARY) == 1,
                    )
                }
            }
        }
        return rows
    }

    private fun defaultCalendar(rows: List<CalendarRow> = calendarRows()): CalendarRow? =
        rows.firstOrNull { it.primary && it.writable } ?: rows.firstOrNull { it.writable }

    override fun calendars(eventsReadable: Boolean, reminders: Boolean): List<JsonObject> {
        val events = if (eventsReadable) eventCalendars() else emptyList()
        return if (reminders) events + tasks.listJSON(100 - events.size) else events
    }

    private fun eventCalendars(): List<JsonObject> {
        val rows = calendarRows()
        val defaultID = defaultCalendar(rows)?.id
        return rows.take(100).map { row ->
            buildJsonObject {
                put("id", calendarID(row.id))
                put("title", row.title.take(100))
                put("kind", "event")
                put("color", "#%06X".format(row.color and 0xFFFFFF))
                put("source", row.account.take(60))
                put("writable", row.writable)
                put("shared", false)
                put("default", row.id == defaultID)
            }
        }
    }

    override fun platformCalendarID(forShared: String): String? =
        calendarRows().firstOrNull { calendarID(it.id) == forShared }?.id?.toString() ?: tasks.platformListID(forShared)

    override fun calendarTitle(id: String?, forEvents: Boolean): String? {
        if (!forEvents) return if (tasks.authorized) tasks.listTitle(id) else null
        val rows = calendarRows()
        return (id?.let { shared -> rows.firstOrNull { calendarID(it.id) == shared } } ?: defaultCalendar(rows))?.title
    }

    private data class Instance(
        val eventID: Long, val begin: Long, val end: Long, val title: String, val allDay: Boolean, val zone: String?,
        val calendarID: Long, val location: String?, val description: String?, val availability: Int?, val status: Int?,
        val recurring: Boolean, val organizer: String?, val selfStatus: Int?,
    )

    private fun instances(from: Instant, to: Instant, calendars: Set<Long>?): List<Instance> {
        if (!granted(Manifest.permission.READ_CALENDAR)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val found = mutableListOf<Instance>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_TIMEZONE,
                    CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.STATUS, CalendarContract.Instances.RRULE,
                    CalendarContract.Instances.ORGANIZER, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val calendarID = cursor.long(CalendarContract.Instances.CALENDAR_ID) ?: continue
                    if (calendars != null && calendarID !in calendars) continue
                    found += Instance(
                        eventID = cursor.long(CalendarContract.Instances.EVENT_ID) ?: continue,
                        begin = cursor.long(CalendarContract.Instances.BEGIN) ?: continue,
                        end = cursor.long(CalendarContract.Instances.END) ?: continue,
                        title = cursor.string(CalendarContract.Instances.TITLE).orEmpty(),
                        allDay = cursor.int(CalendarContract.Instances.ALL_DAY) == 1,
                        zone = cursor.string(CalendarContract.Instances.EVENT_TIMEZONE),
                        calendarID = calendarID,
                        location = cursor.string(CalendarContract.Instances.EVENT_LOCATION),
                        description = cursor.string(CalendarContract.Instances.DESCRIPTION),
                        availability = cursor.int(CalendarContract.Instances.AVAILABILITY),
                        status = cursor.int(CalendarContract.Instances.STATUS),
                        recurring = !cursor.string(CalendarContract.Instances.RRULE).isNullOrEmpty(),
                        organizer = cursor.string(CalendarContract.Instances.ORGANIZER),
                        selfStatus = cursor.int(CalendarContract.Instances.SELF_ATTENDEE_STATUS),
                    )
                }
            }
        }
        return found
    }

    override suspend fun events(from: Instant, to: Instant, hidden: Set<String>, details: Boolean, emails: Boolean, limit: Int): List<JsonObject> =
        withContext(Dispatchers.IO) {
            val rows = calendarRows()
            val shown = rows.filter { it.id.toString() !in hidden }
            if (shown.isEmpty()) return@withContext emptyList()
            val owners = rows.associate { it.id to (it.owner ?: it.account) }
            val list = instances(from, to, shown.map { it.id }.toSet())
            val alarms = reminderMinutes(list.map { it.eventID }.toSet())
            list.take(limit).map { json(it, owners[it.calendarID], alarms[it.eventID].orEmpty(), details, emails) }
        }

    private fun reminderMinutes(events: Set<Long>): Map<Long, List<Int>> {
        if (events.isEmpty()) return emptyMap()
        val map = mutableMapOf<Long, MutableList<Int>>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} IN (${events.joinToString(",")})", null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val event = cursor.long(CalendarContract.Reminders.EVENT_ID) ?: continue
                    val minutes = cursor.int(CalendarContract.Reminders.MINUTES) ?: continue
                    map.getOrPut(event) { mutableListOf() } += maxOf(0, minutes)
                }
            }
        }
        return map
    }

    private data class Attendee(val name: String, val email: String?, val status: Int?, val type: Int?, val relationship: Int?)

    private fun attendees(eventID: Long): List<Attendee> {
        val list = mutableListOf<Attendee>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_STATUS,
                    CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                ),
                "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventID.toString()), null,
            )?.use { cursor ->
                while (cursor.moveToNext() && list.size < 30) {
                    list += Attendee(
                        cursor.string(CalendarContract.Attendees.ATTENDEE_NAME).orEmpty(),
                        cursor.string(CalendarContract.Attendees.ATTENDEE_EMAIL),
                        cursor.int(CalendarContract.Attendees.ATTENDEE_STATUS),
                        cursor.int(CalendarContract.Attendees.ATTENDEE_TYPE),
                        cursor.int(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP),
                    )
                }
            }
        }
        return list
    }

    private fun json(event: Instance, owner: String?, alarms: List<Int>, details: Boolean, emails: Boolean): JsonObject {
        val zone = if (event.allDay) ZoneOffset.UTC else event.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val organizerIsMe = event.organizer == null || event.organizer.equals(owner, ignoreCase = true)
        return buildJsonObject {
            put("id", eventID(event.eventID, event.begin))
            put("calendar_id", calendarID(event.calendarID))
            put("title", event.title.take(200))
            put("start", PhoneDates.format(Instant.ofEpochMilli(event.begin), zone))
            put("end", PhoneDates.format(Instant.ofEpochMilli(event.end), zone))
            put("all_day", event.allDay)
            put("time_zone", zone.id)
            put("availability", when (event.availability) {
                CalendarContract.Events.AVAILABILITY_BUSY -> "busy"
                CalendarContract.Events.AVAILABILITY_FREE -> "free"
                CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
                else -> "none"
            })
            put("status", when (event.status) {
                CalendarContract.Events.STATUS_CONFIRMED -> "confirmed"
                CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
                CalendarContract.Events.STATUS_CANCELED -> "canceled"
                else -> "none"
            })
            put("recurring", event.recurring)
            put("organizer_is_me", organizerIsMe)
            put("my_status", participantStatus(event.selfStatus))
            event.location?.takeIf { it.isNotEmpty() }?.let { put("location", it.take(300)) }
            if (alarms.isNotEmpty()) put("alarms_min", JsonArray(alarms.take(5).map(::JsonPrimitive)))
            if (details) {
                event.description?.takeIf { it.isNotEmpty() }?.let { put("notes", it.take(PhoneSyncLimits.NOTES_CHARS)) }
                val people = attendees(event.eventID)
                if (people.isNotEmpty()) {
                    put("attendees", JsonArray(people.map { person ->
                        buildJsonObject {
                            put("name", person.name.take(100))
                            put("status", participantStatus(person.status))
                            put("role", when {
                                person.relationship == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "chair"
                                person.type == CalendarContract.Attendees.TYPE_REQUIRED -> "required"
                                person.type == CalendarContract.Attendees.TYPE_OPTIONAL -> "optional"
                                person.type == CalendarContract.Attendees.TYPE_RESOURCE -> "non_participant"
                                else -> "unknown"
                            })
                            if (emails && !person.email.isNullOrEmpty()) put("email", person.email.take(200))
                        }
                    }))
                }
            }
        }
    }

    private fun participantStatus(status: Int?): String = when (status) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "pending"
        else -> "none"
    }

    override suspend fun reminders(hidden: Set<String>, details: Boolean, completedSince: Instant, limit: Int): List<JsonObject> =
        withContext(Dispatchers.IO) { tasks.reminders(hidden, details, completedSince, limit) }

    // region Writing

    private fun findEvent(id: String, window: Pair<Instant, Instant>): Instance {
        if (eventAccess != PhoneCalendarAccess.FULL) throw PhoneSourceException(L10n.tr("Conduit can only add events, so it can't find existing ones."))
        // Wider than what's shared: the agent may have read it a day ago.
        val from = window.first.minusSeconds(30L * 86_400)
        val to = window.second.plusSeconds(30L * 86_400)
        return instances(from, to, null).firstOrNull { eventID(it.eventID, it.begin) == id }
            ?: throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
    }

    private fun writableCalendar(id: Long): Boolean = calendarRows().firstOrNull { it.id == id }?.writable ?: false

    override suspend fun apply(change: PhoneChange, window: Pair<Instant, Instant>): String? = withContext(Dispatchers.IO) {
        val fields = change.fields
        val resolver = context.contentResolver
        when (change.kind) {
            PhoneChangeKind.ADD_EVENT -> {
                if (eventAccess == PhoneCalendarAccess.OFF) throw PhoneSourceException(denied())
                val rows = calendarRows()
                val target = fields.calendarID?.let { shared -> rows.firstOrNull { calendarID(it.id) == shared } } ?: defaultCalendar(rows)
                    ?: throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
                if (!target.writable) throw PhoneSourceException(L10n.tr("That calendar can't be changed."))
                val values = eventValues(fields, isNew = true, existingStart = null, existingEnd = null, existingAllDay = false)
                values.put(CalendarContract.Events.CALENDAR_ID, target.id)
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw PhoneSourceException(L10n.tr("That calendar can't be changed."))
                val eventID = ContentUris.parseId(uri)
                fields.alarmsMinutes?.take(3)?.forEach { minutes ->
                    resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventID)
                        put(CalendarContract.Reminders.MINUTES, minutes)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
                }
                if (eventAccess == PhoneCalendarAccess.FULL) eventID(eventID, values.getAsLong(CalendarContract.Events.DTSTART)) else null
            }
            PhoneChangeKind.UPDATE_EVENT -> {
                val event = findEvent(change.eventID.orEmpty(), window)
                if (!writableCalendar(event.calendarID)) throw PhoneSourceException(L10n.tr("That calendar can't be changed."))
                val values = eventValues(fields, isNew = false, existingStart = event.begin, existingEnd = event.end, existingAllDay = event.allDay)
                fields.calendarID?.let { shared -> calendarRows().firstOrNull { calendarID(it.id) == shared && it.writable } }?.let {
                    values.put(CalendarContract.Events.CALENDAR_ID, it.id)
                }
                if (event.recurring && change.span != "future") {
                    // This occurrence only: an exception to the series.
                    values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin)
                    val uri = resolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, event.eventID), values)
                        ?: throw PhoneSourceException(L10n.tr("That calendar can't be changed."))
                    eventID(ContentUris.parseId(uri), values.getAsLong(CalendarContract.Events.DTSTART) ?: event.begin)
                } else {
                    resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventID), values, null, null)
                    eventID(event.eventID, values.getAsLong(CalendarContract.Events.DTSTART) ?: event.begin)
                }
            }
            PhoneChangeKind.DELETE_EVENT -> {
                val event = findEvent(change.eventID.orEmpty(), window)
                if (!writableCalendar(event.calendarID)) throw PhoneSourceException(L10n.tr("That calendar can't be changed."))
                val id = eventID(event.eventID, event.begin)
                if (event.recurring && change.span != "future") {
                    resolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, event.eventID), ContentValues().apply {
                        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.begin)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                    })
                } else {
                    resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventID), null, null)
                }
                id
            }
            PhoneChangeKind.ADD_REMINDER, PhoneChangeKind.UPDATE_REMINDER, PhoneChangeKind.COMPLETE_REMINDER -> tasks.apply(change)
            else -> throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
        }
    }

    private fun denied() = L10n.tr("Conduit doesn't have access to your calendar. Allow it in the Settings app.")

    private fun eventValues(fields: app.hermesconduit.android.model.PhoneChangeFields, isNew: Boolean, existingStart: Long?, existingEnd: Long?, existingAllDay: Boolean): ContentValues {
        val values = ContentValues()
        fields.title?.let { values.put(CalendarContract.Events.TITLE, it) }
        val allDay = fields.allDay ?: (if (isNew) false else existingAllDay)
        values.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        var start = fields.start?.toEpochMilli() ?: existingStart
        val length = if (existingStart != null && existingEnd != null) existingEnd - existingStart else 3_600_000L
        var end = fields.end?.toEpochMilli() ?: when {
            fields.start != null && start != null -> start + if (allDay) maxOf(length, 86_400_000L) else maxOf(length, 0)
            else -> existingEnd
        }
        if (isNew && end == null && start != null) end = start + if (allDay) 86_400_000L else 3_600_000L
        if (allDay) {
            // All-day events are stored at UTC midnight of the local date.
            start = start?.let { localMidnightUtc(it) }
            end = end?.let { localMidnightUtc(it) }?.let { e -> if (start != null && e <= start) start + 86_400_000L else e }
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        start?.let { values.put(CalendarContract.Events.DTSTART, it) }
        end?.let { values.put(CalendarContract.Events.DTEND, it) }
        fields.location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        val notes = listOfNotNull(fields.notes, fields.url).joinToString("\n\n").ifEmpty { null }
        notes?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        return values
    }

    private fun localMidnightUtc(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    override suspend fun removeAdded(itemID: String, isEvent: Boolean, window: Pair<Instant, Instant>) = withContext(Dispatchers.IO) {
        if (!isEvent) return@withContext tasks.remove(itemID)
        val event = findEvent(itemID, window)
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventID), null, null)
        Unit
    }

    // endregion

    companion object {
        fun eventID(eventID: Long, begin: Long): String = PhoneSyncRules.hashedID("e_", eventID.toString(), (begin / 1000).toString())
        fun calendarID(id: Long): String = PhoneSyncRules.hashedID("c_", id.toString())
    }
}

/**
 * The phone's current state for Hermes (iOS DevicePhoneStatusSource):
 * battery and charging, Battery Saver, whether Android Auto is connected,
 * where audio plays, Wi-Fi or cellular. Latest values only. Motion activity
 * needs a separate permission and Play Services, so it goes as "unknown".
 */
class AndroidPhoneStatusSource(private val context: Context) : PhoneStatusSource {
    override suspend fun snapshot(): JsonObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val power = context.getSystemService(PowerManager::class.java)
        val car = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        return buildJsonObject {
            put("at", PhoneDates.format(Instant.now()))
            put("low_power", power?.isPowerSaveMode == true)
            put("carplay", car)
            put("audio_route", audioRoute(car))
            put("network", network())
            if (level >= 0 && scale > 0) put("battery", Math.round(level * 100.0 / scale) / 100.0)
            when (plugged) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> put("charging", true)
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> put("charging", false)
            }
            put("activity", "unknown")
        }
    }

    private fun audioRoute(car: Boolean): String {
        if (car) return "car"
        val audio = context.getSystemService(AudioManager::class.java) ?: return "other"
        val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
        return when {
            outputs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "bluetooth"
            outputs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "headphones"
            else -> "speaker"
        }
    }

    private fun network(): String = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "wifi"
        }
    }.getOrDefault("none")

    /** Do Not Disturb stands in for iOS Focus, through the user's "Tell Hermes" filters ([PhoneModes]). */
    override fun focus(): PhoneFocusSnapshot? = runCatching { PhoneModes.snapshot(context) }.getOrNull()
}

/**
 * Alarms and timers Hermes sets, through the Clock app's AlarmClock
 * intents (iOS PhoneAlarmSource with AlarmKit). Android can't list alarms
 * back, so Conduit remembers the ones it set: a one-off goes from the list
 * once its time has passed.
 */
class AlarmClockSource(private val context: Context) : PhoneAlarmSource {
    private val settings = context.appSettings

    override val isSupported: Boolean
        get() = Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null

    override val authorized: Boolean get() = isSupported

    private fun records(): Map<String, PhoneAlarmRecord> = PhoneAlarmRecord.decodeAll(settings.getString(PhoneAlarmRecord.STORAGE_KEY, "").ifEmpty { null })
    private fun save(records: Map<String, PhoneAlarmRecord>) = settings.set(PhoneAlarmRecord.STORAGE_KEY, if (records.isEmpty()) "" else PhoneAlarmRecord.encodeAll(records))

    override fun alarms(now: Instant, zone: ZoneId): List<JsonObject>? {
        if (!authorized) return emptyList()
        val all = records()
        // Forget the ones that rang and went.
        val live = all.filter { (_, record) -> record.repeats != null || (record.firesAt ?: Long.MAX_VALUE) > now.toEpochMilli() }
        if (live.size != all.size) save(live)
        return live.entries.sortedBy { it.value.setAt }.take(PhoneAlarmRecord.MAXIMUM).map { (id, record) ->
            buildJsonObject {
                put("id", id)
                put("kind", record.kind)
                record.label?.let { put("label", it) }
                val ring = record.firesAt?.let(Instant::ofEpochMilli) ?: record.time?.let { PhoneAlarmRules.nextRing(it, record.repeats, now, zone) }
                ring?.let { put("fires_at", PhoneDates.format(it, zone)) }
                record.repeats?.let { days -> put("repeats", JsonArray(days.map(::JsonPrimitive))) }
                put("state", "scheduled")
            }
        }
    }

    override suspend fun apply(change: PhoneChange, now: Instant): String {
        if (!authorized) throw PhoneSourceException(L10n.tr("Conduit isn't allowed to set alarms. Allow it in the Settings app."))
        val fields = change.fields
        val id = java.util.UUID.randomUUID().toString().lowercase()
        when (change.kind) {
            PhoneChangeKind.CANCEL_ALARM -> {
                val target = change.alarmID?.lowercase().orEmpty()
                cancel(target)
                return target
            }
            PhoneChangeKind.SET_TIMER -> {
                val seconds = fields.seconds?.takeIf { it in 5..86_400 } ?: throw PhoneSourceException(L10n.tr("That alarm isn't on this phone any more."))
                start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, fields.label ?: L10n.tr("Timer"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                })
                remember(id, PhoneAlarmRecord("timer", fields.label, firesAt = now.plusSeconds(seconds.toLong()).toEpochMilli(), setAt = now.toEpochMilli()))
            }
            PhoneChangeKind.SET_ALARM -> {
                val zone = ZoneId.systemDefault()
                val at = fields.at
                val time = fields.time
                val local = when {
                    at != null -> {
                        if (at <= now) throw PhoneSourceException(L10n.tr("That alarm time has already passed."))
                        // The Clock app takes a time of day; within a day it rings at the right moment.
                        if (at.toEpochMilli() - now.toEpochMilli() > 24L * 3600 * 1000) {
                            throw PhoneSourceException(L10n.tr("The Clock app sets alarms for the next 24 hours only; ask again closer to the day."))
                        }
                        at.atZone(zone).toLocalTime()
                    }
                    time != null -> java.time.LocalTime.of(time.hour, time.minute)
                    else -> throw PhoneSourceException(L10n.tr("That alarm isn't on this phone any more."))
                }
                start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, local.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, local.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, fields.label ?: L10n.tr("Alarm"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    fields.repeats?.takeIf { at == null }?.let { days ->
                        // Calendar weekdays run Sunday = 1 … Saturday = 7.
                        putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { (app.hermesconduit.android.model.PhoneChangeFields.WEEKDAYS.indexOf(it) + 1) % 7 + 1 }))
                    }
                })
                remember(id, PhoneAlarmRecord("alarm", fields.label, repeats = fields.repeats.takeIf { at == null },
                    firesAt = at?.toEpochMilli(), time = if (at == null) time else null, setAt = now.toEpochMilli()))
            }
            else -> throw PhoneSourceException(L10n.tr("That alarm isn't on this phone any more."))
        }
        return id
    }

    private fun remember(id: String, record: PhoneAlarmRecord) {
        val records = records().toMutableMap()
        records[id] = record
        save(records.entries.sortedBy { it.value.setAt }.takeLast(PhoneAlarmRecord.MAXIMUM).associate { it.key to it.value })
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw PhoneSourceException(L10n.tr("Alarms couldn't be reached just now. Try again."))
        }
    }

    override fun cancel(id: String) {
        val records = records()
        val record = records[id.lowercase()] ?: throw PhoneSourceException(L10n.tr("That alarm isn't on this phone any more."))
        if (record.kind == "timer") {
            // The Clock app can't dismiss one timer by name; the record goes, the user stops it in Clock.
        } else {
            start(Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, record.label ?: L10n.tr("Alarm"))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            })
        }
        save(records - id.lowercase())
    }
}
