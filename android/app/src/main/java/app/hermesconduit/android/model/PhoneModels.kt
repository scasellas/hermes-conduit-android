package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/**
 * Calendar, phone status, Do Not Disturb, alarms and people for Hermes (iOS
 * PhoneSyncModels). Each is its own opt-in; what the user turns on goes
 * straight from this phone to one Hermes profile, sealed end to end. The
 * notifier keeps it for `conduit_calendar` and `conduit_phone`; Hermes can
 * ask for changes, which this phone applies after the user says yes (or at
 * once for new items with "Add without asking").
 */
enum class PhoneCalendarAccess(val raw: String, val title: String) {
    FULL("full", "Read and add"),
    WRITE_ONLY("write_only", "Add only"),
    OFF("off", "Off");

    companion object {
        fun of(raw: String?): PhoneCalendarAccess = entries.firstOrNull { it.raw == raw } ?: OFF
    }
}

enum class PhoneCalendarWindow(val days: Int, val title: String) {
    DAYS30(30, "Next 30 days"),
    DAYS60(60, "Next 60 days"),
    DAYS180(180, "Next 6 months");

    companion object {
        val DEFAULT = DAYS60
        const val PAST_DAYS = 7
        fun of(days: Int?): PhoneCalendarWindow = entries.firstOrNull { it.days == days } ?: DEFAULT
    }
}

@Serializable
data class PhoneSyncPreferences(
    val destination: SyncDestination? = null,
    val calendar: String = PhoneCalendarAccess.OFF.raw,
    val reminders: Boolean = false,
    /** Event notes, attendees and links. */
    val details: Boolean = false,
    val attendeeEmails: Boolean = false,
    val status: Boolean = false,
    /** Do Not Disturb (iOS Focus). */
    val focus: Boolean = false,
    /** New events and alarms Hermes asks for go in without a card. Changes and deletions always ask. */
    val autoAdd: Boolean = false,
    val window: Int = PhoneCalendarWindow.DEFAULT.days,
    /** Calendars the user left out, by Android calendar id. */
    val hiddenCalendars: Set<String> = emptySet(),
    val alarmsChoice: Boolean? = null,
    val contactsChoice: Boolean? = null,
    val voiceReaders: List<String>? = null,
) {
    val calendarAccess: PhoneCalendarAccess get() = PhoneCalendarAccess.of(calendar)
    val calendarWindow: PhoneCalendarWindow get() = PhoneCalendarWindow.of(window)
    val alarms: Boolean get() = alarmsChoice ?: false
    val contacts: Boolean get() = contactsChoice ?: false

    fun withAlarms(on: Boolean) = copy(alarmsChoice = if (on) true else null)
    fun withContacts(on: Boolean) = copy(contactsChoice = if (on) true else null)

    val sharesAnything: Boolean get() = calendarAccess != PhoneCalendarAccess.OFF || reminders || status || focus || alarms || contacts
    val isOn: Boolean get() = destination != null && sharesAnything

    fun letsVoiceUse(mode: LiveVoiceMode): Boolean = isOn && mode.storageID in voiceReaders.orEmpty()

    fun withVoiceUse(mode: LiveVoiceMode, allowed: Boolean): PhoneSyncPreferences {
        val readers = voiceReaders.orEmpty().toMutableSet()
        if (allowed) readers += mode.storageID else readers -= mode.storageID
        return copy(voiceReaders = readers.sorted().ifEmpty { null })
    }

    /** The `sharing` object every upload carries. */
    val sharingJSON: JsonObject
        get() = buildJsonObject {
            put("calendar", calendarAccess.raw)
            put("reminders", reminders)
            put("details", details)
            put("attendee_emails", details && attendeeEmails)
            put("status", status)
            put("focus", focus)
            put("auto_add", autoAdd)
            put("alarms", alarms)
            put("contacts", contacts)
            put("past_days", PhoneCalendarWindow.PAST_DAYS)
            put("future_days", calendarWindow.days)
        }

    /** What decides the shape of what's sent: a change sends everything again. */
    val fingerprint: String
        get() = listOf(calendarAccess.raw, reminders.toString(), details.toString(), attendeeEmails.toString(), calendarWindow.days.toString(),
            hiddenCalendars.sorted().joinToString(",")).joinToString("|")

    fun encode(): String = ConduitJson.encodeToString(serializer(), this)

    companion object {
        const val STORAGE_KEY = "conduit.phoneSync.preferences.v1"
        fun decode(text: String?): PhoneSyncPreferences =
            text?.let { runCatching { ConduitJson.decodeFromString(serializer(), it) }.getOrNull() } ?: PhoneSyncPreferences()

        /** What the page starts with before sharing is on. */
        val SUGGESTED = PhoneSyncPreferences(calendar = PhoneCalendarAccess.FULL.raw, status = true, focus = true)
    }
}

/** The three keys from one agreement. */
@Serializable
data class PhoneSessionKey(val kid: String, val ingest: String, val request: String, val answer: String) {
    val ingestBytes: ByteArray get() = SealCrypto.base64UrlDecoded(ingest) ?: ByteArray(0)
    val requestBytes: ByteArray get() = SealCrypto.base64UrlDecoded(request) ?: ByteArray(0)
    val answerBytes: ByteArray get() = SealCrypto.base64UrlDecoded(answer) ?: ByteArray(0)
}

/** iOS PhoneSyncCrypto: must match phone_store.py in the notifier. */
object PhoneSyncCrypto {
    const val SALT = "conduit-phone-v1"
    const val INGEST_INFO = "conduit-phone-v1 ingest device-to-host"
    const val EXCHANGE_REQUEST_INFO = "conduit-phone-v1 exchange device-to-host"
    const val EXCHANGE_ANSWER_INFO = "conduit-phone-v1 exchange host-to-device"
    const val AAD_TAG = "conduit-phone/1"
    const val KID_LABEL = "conduit-phone-kid\n"

    fun keyID(devicePublic: ByteArray, hostPublic: ByteArray) = SealCrypto.keyID(KID_LABEL, devicePublic, hostPublic)

    fun agree(devicePrivate: ByteArray, deviceID: String, hostPublicKey: String, kid: String): PhoneSessionKey {
        val (ingest, request, answer) = SealCrypto.agree(
            devicePrivate, deviceID, hostPublicKey, kid, KID_LABEL, SALT, listOf(INGEST_INFO, EXCHANGE_REQUEST_INFO, EXCHANGE_ANSWER_INFO),
        )
        return PhoneSessionKey(kid, SealCrypto.base64Url(ingest), SealCrypto.base64Url(request), SealCrypto.base64Url(answer))
    }

    fun ingestAAD(kid: String, deviceID: String, batchID: String) = SealCrypto.aad(AAD_TAG, "ingest", "kid=$kid", "dev=$deviceID", "batch=$batchID")

    fun exchangeAAD(direction: String, op: String, kid: String, deviceID: String, callID: String) =
        SealCrypto.aad(AAD_TAG, "exchange-$direction", "op=$op", "kid=$kid", "dev=$deviceID", "call=$callID")

    fun envelope(plaintext: ByteArray, key: PhoneSessionKey, deviceID: String, batchID: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val sealed = SealCrypto.seal(plaintext, key.ingestBytes, ingestAAD(key.kid, deviceID, batchID), nonce)
        return buildJsonObject {
            put("v", 1); put("device_id", deviceID); put("kid", key.kid); put("batch_id", batchID); put("nonce", sealed.nonce); put("ct", sealed.ct)
        }
    }

    fun exchangeEnvelope(plaintext: ByteArray, op: String, key: PhoneSessionKey, deviceID: String, callID: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val sealed = SealCrypto.seal(plaintext, key.requestBytes, exchangeAAD("request", op, key.kid, deviceID, callID), nonce)
        return buildJsonObject {
            put("v", 1); put("device_id", deviceID); put("kid", key.kid); put("call_id", callID); put("op", op); put("nonce", sealed.nonce); put("ct", sealed.ct)
        }
    }

    fun openAnswer(answer: JsonObject, op: String, key: PhoneSessionKey, deviceID: String, callID: String): ByteArray =
        SealCrypto.open(answer, key.answerBytes, exchangeAAD("answer", op, key.kid, deviceID, callID))
}

// region Changes from Hermes

@Serializable
data class PhoneChangeResult(val id: String, val outcome: String, val itemID: String? = null, val note: String? = null) {
    val json: JsonObject get() = buildJsonObject {
        put("id", id)
        put("outcome", outcome)
        itemID?.let { put("item_id", it) }
        note?.let { put("note", it.take(200)) }
    }

    companion object {
        const val APPLIED = "applied"
        const val DISMISSED = "dismissed"
        const val FAILED = "failed"
    }
}

@Serializable
data class PhoneClockTime(val hour: Int, val minute: Int) {
    val text: String get() = "%02d:%02d".format(hour, minute)
}

data class PhoneChangeFields(
    val title: String? = null,
    val start: Instant? = null,
    val end: Instant? = null,
    val allDay: Boolean? = null,
    val calendarID: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val url: String? = null,
    val alarmsMinutes: List<Int>? = null,
    val due: Instant? = null,
    val dueAllDay: Boolean? = null,
    val dueText: String? = null,
    val priority: Int? = null,
    val listID: String? = null,
    val completed: Boolean? = null,
    val locationAlarmName: String? = null,
    val label: String? = null,
    val at: Instant? = null,
    val time: PhoneClockTime? = null,
    val repeats: List<String>? = null,
    val seconds: Int? = null,
) {
    companion object {
        val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun parse(json: JsonObject): PhoneChangeFields {
            val days = json["repeats"].arr?.mapNotNull { it.str?.lowercase() }
            val dueText = json["due"].str
            return PhoneChangeFields(
                title = json["title"].str?.take(200),
                start = json["start"].str?.let(PhoneDates::parse),
                end = json["end"].str?.let(PhoneDates::parse),
                allDay = json["all_day"].strictBool,
                calendarID = json["calendar_id"].str,
                location = json["location"].str?.take(300),
                notes = json["notes"].str?.take(2000),
                url = json["url"].str?.takeIf { val scheme = it.substringBefore(':', "").lowercase(); scheme == "http" || scheme == "https" },
                alarmsMinutes = json["alarms_min"].arr?.mapNotNull { it.long?.toInt() }?.filter { it in 0..10_080 },
                due = dueText?.let(PhoneDates::parse),
                dueAllDay = json["due_all_day"].strictBool,
                dueText = dueText,
                priority = json["priority"].long?.toInt(),
                listID = json["list_id"].str,
                completed = json["completed"].strictBool,
                locationAlarmName = json["location_alarm"].obj?.get("name").str,
                label = json["label"].str?.trim()?.take(60)?.ifEmpty { null },
                at = json["at"].str?.let(PhoneDates::parse),
                time = json["time"].str?.let(::clockTime),
                repeats = days?.let { list -> WEEKDAYS.filter { it in list } }?.ifEmpty { null },
                seconds = json["seconds"].long?.toInt(),
            )
        }

        /** `HH:MM` on a 24-hour clock. */
        fun clockTime(text: String): PhoneClockTime? {
            val parts = text.split(':')
            if (parts.size != 2 || parts.any { it.length != 2 }) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return PhoneClockTime(hour, minute)
        }
    }
}


enum class PhoneChangeKind(val raw: String) {
    ADD_EVENT("add_event"), UPDATE_EVENT("update_event"), DELETE_EVENT("delete_event"),
    ADD_REMINDER("add_reminder"), UPDATE_REMINDER("update_reminder"), COMPLETE_REMINDER("complete_reminder"),
    SET_ALARM("set_alarm"), SET_TIMER("set_timer"), CANCEL_ALARM("cancel_alarm");

    val isAddition: Boolean get() = this == ADD_EVENT || this == ADD_REMINDER
    val isEvent: Boolean get() = this == ADD_EVENT || this == UPDATE_EVENT || this == DELETE_EVENT
    val isAlarm: Boolean get() = this == SET_ALARM || this == SET_TIMER || this == CANCEL_ALARM
    /** What "Add without asking" lets in: new items, never changes or cancellations. */
    val addsWithoutAsking: Boolean get() = isAddition || this == SET_ALARM || this == SET_TIMER

    companion object {
        fun of(raw: String?): PhoneChangeKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** A change Hermes asked for, as the host sends it. */
data class PhoneChange(
    val id: String,
    val kind: PhoneChangeKind,
    val auto: Boolean,
    val reason: String?,
    val sessionTitle: String?,
    val eventID: String?,
    val reminderID: String?,
    val alarmID: String?,
    val span: String?,
    val fields: PhoneChangeFields,
    val createdAt: Instant?,
) {
    companion object {
        fun parse(json: JsonObject): PhoneChange? {
            val id = json["id"].str?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
            val kind = PhoneChangeKind.of(json["kind"].str) ?: return null
            val target = json["target"].obj
            val fieldsJSON = json["fields"].obj ?: JsonObject(emptyMap())
            val fields = PhoneChangeFields.parse(fieldsJSON)
            val change = PhoneChange(
                id = id,
                kind = kind,
                auto = json["auto"].strictBool ?: false,
                reason = json["reason"].str?.take(200),
                sessionTitle = json["session_title"].str?.take(80),
                eventID = target?.get("event_id").str,
                reminderID = target?.get("reminder_id").str,
                alarmID = target?.get("alarm_id").str,
                span = fieldsJSON["span"].str ?: target?.get("span").str,
                fields = fields,
                createdAt = json["created_at"].str?.let(PhoneDates::parse),
            )
            val valid = when (kind) {
                PhoneChangeKind.UPDATE_EVENT, PhoneChangeKind.DELETE_EVENT -> change.eventID != null
                PhoneChangeKind.UPDATE_REMINDER, PhoneChangeKind.COMPLETE_REMINDER -> change.reminderID != null
                PhoneChangeKind.ADD_EVENT -> fields.title != null && fields.start != null
                PhoneChangeKind.ADD_REMINDER -> fields.title != null
                PhoneChangeKind.SET_ALARM -> fields.at != null || fields.time != null
                PhoneChangeKind.SET_TIMER -> fields.seconds?.let { it in 5..86_400 } == true
                PhoneChangeKind.CANCEL_ALARM -> change.alarmID != null
            }
            return change.takeIf { valid }
        }
    }
}

/** A change waiting for the user, with what the card shows. */
data class PhoneChangePrompt(val change: PhoneChange, val calendarTitle: String?, val alarmTitle: String?) {
    val id: String get() = change.id
}

/** An addition made without asking, for the banner's Undo. */
data class PhoneAutoAdded(
    val changeID: String,
    val itemID: String,
    val title: String,
    val calendarTitle: String?,
    val isEvent: Boolean,
    val isAlarm: Boolean = false,
)

// endregion

/** ISO 8601 with the phone's local offset, as the host expects. */
object PhoneDates {
    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = HealthDateFormat.format(instant, zone)

    fun day(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = instant.atZone(zone).toLocalDate().toString()

    /** A time with an offset (fractional seconds allowed), or a bare `YYYY-MM-DD` (midnight, local). */
    fun parse(text: String): Instant? {
        parseIsoInstant(text)?.let { return it }
        if (text.length != 10) return null
        return runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }
}

/** What Do Not Disturb said at the last upload (iOS Focus snapshot). */
data class PhoneFocusSnapshot(val active: Boolean, val label: String?, val holdCalls: Boolean, val at: Instant) {
    val json: JsonObject get() = buildJsonObject {
        put("at", PhoneDates.format(at))
        put("active", active)
        put("hold_calls", holdCalls)
        label?.takeIf { it.isNotEmpty() }?.let { put("label", it.take(40)) }
    }
}

object PhoneSyncLimits {
    const val MAX_EVENTS = 4_000
    const val NOTES_CHARS = 2_000
    /** Everything goes whole again at least this often. */
    const val FULL_SYNC_INTERVAL_MS = 24L * 3600 * 1000
    /** Status goes again after this long even when nothing changed. */
    const val STATUS_REFRESH_MS = 15L * 60 * 1000
}

/** What a pass remembers: hashes, never calendar content. */
@Serializable
data class PhoneSyncState(
    val deviceID: String = SealCrypto.compactUuid(),
    val destination: SyncDestination? = null,
    val generation: String? = null,
    val fingerprint: String? = null,
    val eventHashes: Map<String, String> = emptyMap(),
    val reminderHashes: Map<String, String> = emptyMap(),
    val calendarsHash: String? = null,
    val statusHash: String? = null,
    val statusSentAt: Long? = null,
    val focusHash: String? = null,
    val alarmsHash: String? = null,
    val contactsHash: String? = null,
    val lastFullSync: Long? = null,
    val lastSync: Long? = null,
    val lastAttempt: Long? = null,
    val pause: SyncPause? = null,
    val unreported: List<PhoneChangeResult> = emptyList(),
    val answered: Map<String, PhoneChangeResult> = emptyMap(),
) {
    fun startedOver() = PhoneSyncState(deviceID = deviceID)

    fun forgotSent() = copy(
        eventHashes = emptyMap(), reminderHashes = emptyMap(), calendarsHash = null, statusHash = null, statusSentAt = null,
        focusHash = null, alarmsHash = null, contactsHash = null, lastFullSync = null, fingerprint = null,
    )

    fun encode(): String = ConduitJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): PhoneSyncState {
            if (text == null) return PhoneSyncState()
            runCatching { return ConduitJson.decodeFromString(serializer(), text) }
            return PhoneSyncState(deviceID = HealthSyncState.salvageDeviceID(text) ?: SealCrypto.compactUuid())
        }
    }
}

/** Diffing and the sharing as it really is (iOS PhoneSyncEngine's static parts). */
object PhoneSyncRules {
    data class Section(val upsert: List<JsonObject>, val delete: List<String>, val hashes: Map<String, String>)

    fun hash(value: JsonElement): String = SealCrypto.hex(SealCrypto.sha256(CanonicalJson.bytes(value)).copyOf(12))

    /**
     * What to send of `items` given the hashes last sent: everything when
     * `full`, otherwise new and changed items and the ids that went away.
     * A list cut at its limit reports nothing deleted.
     */
    fun diff(items: List<JsonObject>, sent: Map<String, String>, full: Boolean, truncated: Boolean = false): Section {
        val hashes = linkedMapOf<String, String>()
        val upsert = mutableListOf<JsonObject>()
        for (item in items) {
            val id = item["id"].str ?: continue
            val hash = hash(item)
            hashes[id] = hash
            if (full || sent[id] != hash) upsert += item
        }
        if (full || truncated) return Section(upsert, emptyList(), hashes)
        return Section(upsert, sent.keys.filter { it !in hashes }.sorted(), hashes)
    }

    /** Access taken away in the system settings narrows what the host is told. */
    fun effectiveSharing(preferences: PhoneSyncPreferences, access: PhoneCalendarAccess, remindersAuthorized: Boolean, alarmsAuthorized: Boolean = false): JsonObject {
        val calendar = when {
            preferences.calendarAccess == PhoneCalendarAccess.FULL && access == PhoneCalendarAccess.FULL -> PhoneCalendarAccess.FULL
            preferences.calendarAccess == PhoneCalendarAccess.OFF || access == PhoneCalendarAccess.OFF -> PhoneCalendarAccess.OFF
            else -> PhoneCalendarAccess.WRITE_ONLY
        }
        val reminders = preferences.reminders && remindersAuthorized
        val sharing = preferences.sharingJSON.toMutableMap()
        sharing["calendar"] = JsonPrimitive(calendar.raw)
        if (calendar != PhoneCalendarAccess.FULL) sharing["attendee_emails"] = JsonPrimitive(false)
        sharing["reminders"] = JsonPrimitive(reminders)
        if (calendar != PhoneCalendarAccess.FULL && !reminders) sharing["details"] = JsonPrimitive(false)
        sharing["alarms"] = JsonPrimitive(preferences.alarms && alarmsAuthorized)
        return JsonObject(sharing)
    }

    /** `prefix` and the SHA-256 of the parts (joined by U+001F) in base64url, 22 characters. */
    fun hashedID(prefix: String, vararg parts: String): String =
        prefix + SealCrypto.base64Url(SealCrypto.sha256(parts.joinToString("\u001f").toByteArray())).take(22)
}

/** The people the user picked for Hermes. */
@Serializable
data class PhonePerson(
    val id: String,
    val name: String,
    val nickname: String? = null,
    val relation: String? = null,
    val organization: String? = null,
    val phones: List<Phone> = emptyList(),
    val emails: List<Email> = emptyList(),
    /** `YYYY-MM-DD`, or `--MM-DD` without a year. */
    val birthday: String? = null,
) {
    @Serializable data class Phone(val number: String, val label: String? = null)
    @Serializable data class Email(val address: String, val label: String? = null)

    val json: JsonObject get() = buildJsonObject {
        put("id", id)
        put("name", name)
        nickname?.takeIf { it.isNotEmpty() }?.let { put("nickname", it) }
        relation?.trim()?.takeIf { it.isNotEmpty() }?.let { put("relation", it.take(RELATION_LENGTH)) }
        organization?.takeIf { it.isNotEmpty() }?.let { put("organization", it) }
        if (phones.isNotEmpty()) put("phones", JsonArray(phones.take(10).map { phone ->
            buildJsonObject { put("number", phone.number); phone.label?.takeIf { it.isNotEmpty() }?.let { put("label", it) } }
        }))
        if (emails.isNotEmpty()) put("emails", JsonArray(emails.take(10).map { email ->
            buildJsonObject { put("address", email.address); email.label?.takeIf { it.isNotEmpty() }?.let { put("label", it) } }
        }))
        birthday?.let { put("birthday", it) }
    }

    companion object {
        const val MAXIMUM = 200
        const val RELATION_LENGTH = 40
        const val STORAGE_KEY = "conduit.phoneSync.people.v1"

        fun id(forContact: String): String = "p_" + SealCrypto.hex(SealCrypto.sha256(forContact.toByteArray()).copyOf(8))

        fun isDialable(number: String): Boolean {
            val allowed = "0123456789+()*#.,;/ -"
            return number.length in 1..40 && number.all { it in allowed } && number.any { it.isDigit() }
        }

        /** Contacts often hold no-break spaces and typographic dashes. */
        fun plainNumber(number: String): String = number.map { c ->
            when {
                c.isWhitespace() || c == ' ' -> ' '
                c in "‐‑‒–—" -> '-'
                else -> c
            }
        }.joinToString("").trim()

        fun birthday(year: Int?, month: Int?, day: Int?): String? {
            if (month == null || day == null || month !in 1..12 || day !in 1..31) return null
            return if (year != null && year > 0) "%04d-%02d-%02d".format(year, month, day) else "--%02d-%02d".format(month, day)
        }

        /** Someone picked again keeps their relation and gets what the contact says now. */
        fun merging(picked: List<PhonePerson>, into: List<PhonePerson>): List<PhonePerson> {
            val result = into.toMutableList()
            for (person in picked) {
                val index = result.indexOfFirst { it.id == person.id }
                if (index >= 0) result[index] = person.copy(relation = result[index].relation) else result += person
            }
            return result.take(MAXIMUM)
        }

        fun decodeAll(text: String?): List<PhonePerson> =
            text?.let { runCatching { ConduitJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer()), it) }.getOrNull() }.orEmpty()

        fun encodeAll(list: List<PhonePerson>): String =
            ConduitJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), list.take(MAXIMUM))
    }
}

/** What Conduit remembers about each alarm or timer it set (Android can't list them back). */
@Serializable
data class PhoneAlarmRecord(
    val kind: String,
    val label: String? = null,
    val repeats: List<String>? = null,
    /** A one-off alarm's or timer's ring time (epoch ms). */
    val firesAt: Long? = null,
    val time: PhoneClockTime? = null,
    val setAt: Long = 0,
) {
    companion object {
        const val STORAGE_KEY = "conduit.phoneSync.alarms.v1"
        const val MAXIMUM = 50
        private val mapSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), serializer())

        fun decodeAll(text: String?): Map<String, PhoneAlarmRecord> =
            text?.let { runCatching { ConduitJson.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()

        fun encodeAll(map: Map<String, PhoneAlarmRecord>): String = ConduitJson.encodeToString(mapSerializer, map)
    }
}

object PhoneAlarmRules {
    /** The next time a clock-time alarm rings after `now`. */
    fun nextRing(time: PhoneClockTime, repeats: List<String>?, after: Instant, zone: ZoneId): Instant {
        val local = after.atZone(zone)
        val at = LocalTime.of(time.hour, time.minute)
        val days = repeats.orEmpty()
        if (days.isEmpty()) {
            val today = local.toLocalDate().atTime(at).atZone(zone)
            return (if (today.toInstant() > after) today else today.plusDays(1)).toInstant()
        }
        return days.mapNotNull { day ->
            val index = PhoneChangeFields.WEEKDAYS.indexOf(day).takeIf { it >= 0 } ?: return@mapNotNull null
            val weekday = DayOfWeek.of(index + 1)
            val candidate = local.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday)).atTime(at).atZone(zone)
            (if (candidate.toInstant() > after) candidate else candidate.plusWeeks(1)).toInstant()
        }.min()
    }
}

/** What a change does, in words, for the card and for voice (iOS PhoneChangeWording). */
object PhoneChangeWording {
    fun headline(change: PhoneChange): String = L10n.tr(
        when (change.kind) {
            PhoneChangeKind.ADD_EVENT -> "Hermes wants to add an event"
            PhoneChangeKind.UPDATE_EVENT -> "Hermes wants to change an event"
            PhoneChangeKind.DELETE_EVENT -> "Hermes wants to delete an event"
            PhoneChangeKind.ADD_REMINDER -> "Hermes wants to add a reminder"
            PhoneChangeKind.UPDATE_REMINDER -> "Hermes wants to change a reminder"
            PhoneChangeKind.COMPLETE_REMINDER -> if (change.fields.completed == false) "Hermes wants to reopen a reminder" else "Hermes wants to mark a reminder done"
            PhoneChangeKind.SET_ALARM -> "Hermes wants to set an alarm"
            PhoneChangeKind.SET_TIMER -> "Hermes wants to start a timer"
            PhoneChangeKind.CANCEL_ALARM -> "Hermes wants to cancel an alarm"
        },
    )

    private val shortDateTime: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val shortDate: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val shortTime: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** "Wake up · 06:30 weekdays", "Pasta · 10 min". */
    fun alarmSummary(fields: PhoneChangeFields, kind: PhoneChangeKind, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        fields.label?.let { parts += it }
        val seconds = fields.seconds
        when {
            kind == PhoneChangeKind.SET_TIMER && seconds != null -> parts += duration(seconds)
            fields.at != null -> parts += shortDateTime.format(fields.at.atZone(zone))
            fields.time != null -> {
                val clock = runCatching { shortTime.format(LocalTime.of(fields.time.hour, fields.time.minute)) }.getOrDefault(fields.time.text)
                parts += listOfNotNull(clock, days(fields.repeats)).joinToString(" ")
            }
        }
        if (parts.isEmpty()) parts += L10n.tr(if (kind == PhoneChangeKind.SET_TIMER) "Timer" else "Alarm")
        return parts.joinToString(" · ")
    }

    private fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it hr" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it sec" })
            .joinToString(" ").ifEmpty { "0 sec" }
    }

    fun days(repeats: List<String>?): String? {
        if (repeats.isNullOrEmpty()) return null
        val set = repeats.toSet()
        if (set.size == 7) return L10n.tr("every day")
        if (set == setOf("mon", "tue", "wed", "thu", "fri")) return L10n.tr("weekdays")
        if (set == setOf("sat", "sun")) return L10n.tr("weekends")
        return PhoneChangeFields.WEEKDAYS.withIndex().filter { it.value in set }
            .joinToString(", ") { DayOfWeek.of(it.index + 1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
    }

    fun detail(change: PhoneChange, zone: ZoneId = ZoneId.systemDefault()): String {
        if (change.kind == PhoneChangeKind.SET_ALARM || change.kind == PhoneChangeKind.SET_TIMER) return alarmSummary(change.fields, change.kind, zone)
        val parts = mutableListOf<String>()
        change.fields.title?.takeIf { it.isNotEmpty() }?.let { parts += it }
        val allDay = change.fields.allDay ?: false
        change.fields.start?.let { start ->
            val end = change.fields.end
            parts += when {
                allDay -> shortDate.format(start.atZone(zone))
                end != null -> shortDateTime.format(start.atZone(zone)) + "–" + shortTime.format(end.atZone(zone))
                else -> shortDateTime.format(start.atZone(zone))
            }
        }
        change.fields.due?.let { due ->
            val dueAllDay = change.fields.dueAllDay ?: (change.fields.dueText?.length == 10)
            parts += if (dueAllDay) shortDate.format(due.atZone(zone)) else shortDateTime.format(due.atZone(zone))
        }
        change.fields.location?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun approveTitle(change: PhoneChange): String = L10n.tr(
        when (change.kind) {
            PhoneChangeKind.ADD_EVENT, PhoneChangeKind.ADD_REMINDER -> "Add"
            PhoneChangeKind.DELETE_EVENT -> "Delete"
            PhoneChangeKind.COMPLETE_REMINDER -> "Mark done"
            PhoneChangeKind.UPDATE_EVENT, PhoneChangeKind.UPDATE_REMINDER -> "Change"
            PhoneChangeKind.SET_ALARM -> "Set"
            PhoneChangeKind.SET_TIMER -> "Start"
            PhoneChangeKind.CANCEL_ALARM -> "Cancel alarm"
        },
    )

    fun isRemoval(kind: PhoneChangeKind): Boolean = kind == PhoneChangeKind.DELETE_EVENT || kind == PhoneChangeKind.CANCEL_ALARM

    /** A time as the model says it: with an offset, a local time (this phone's zone), or a date. */
    fun localTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Boolean>? {
        val trimmed = text.trim()
        if (trimmed.length == 10 && PhoneDates.parse(trimmed) != null) return trimmed to true
        if (parseIsoInstant(trimmed) != null) return trimmed to false
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm")) {
            val parsed = runCatching { LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern)) }.getOrNull() ?: continue
            return PhoneDates.format(parsed.atZone(zone).toInstant(), zone) to false
        }
        return null
    }
}
