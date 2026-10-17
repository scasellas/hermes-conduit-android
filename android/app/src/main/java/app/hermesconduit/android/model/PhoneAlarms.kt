package app.hermesconduit.android.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * An alarm or timer Conduit set and rings itself (iOS PhoneAlarmRecord,
 * with what AlarmKit keeps on iOS: when it rings and whether it is ringing).
 * A fixed alarm or a timer has [firesAt]; a clock-time alarm has [time], and
 * [repeats] when it rings on those weekdays, or else the one [firesAt] it
 * rings at.
 */
@Serializable
data class PhoneAlarmRecord(
    val kind: String,
    val label: String? = null,
    val repeats: List<String>? = null,
    /** A one-off alarm's or timer's ring time (epoch ms). */
    val firesAt: Long? = null,
    val time: PhoneClockTime? = null,
    val setAt: Long = 0,
    /** When it started ringing (epoch ms), until stopped or silenced. */
    val ringingSince: Long? = null,
) {
    /** Rings on its weekdays until cancelled (AlarmKit's weekly recurrence); otherwise once. */
    val repeating: Boolean get() = time != null && !repeats.isNullOrEmpty()

    companion object {
        /**
         * Conduit's own alarms. The Clock-app alarms earlier versions set were
         * kept under [LEGACY_STORAGE_KEY]: they are the Clock app's, which
         * Conduit can neither ring nor cancel, so they are forgotten (they keep
         * ringing in the Clock app, where the user manages them).
         */
        const val STORAGE_KEY = "conduit.phoneSync.alarms.v2"
        const val LEGACY_STORAGE_KEY = "conduit.phoneSync.alarms.v1"
        const val MAXIMUM = 50
        private val mapSerializer = MapSerializer(String.serializer(), serializer())

        fun decodeAll(text: String?): Map<String, PhoneAlarmRecord> =
            text?.let { runCatching { ConduitJson.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()

        fun encodeAll(map: Map<String, PhoneAlarmRecord>): String = ConduitJson.encodeToString(mapSerializer, map)
    }
}

/** Why a change can't become an alarm (the source words it, as iOS PhoneAlarmError). */
class PhoneAlarmFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason { PAST, NOT_FOUND }
}

/**
 * The life of Conduit's alarms, apart from Android (iOS AlarmKitPhoneSource):
 * what a change sets, when the system alarm goes off next, ringing and
 * stopping, and the `alarms` list Hermes gets (phone-v1-contract.md
 * section 6). Pure: the time and zone are passed in.
 */
object PhoneAlarmBook {
    /**
     * An alarm nobody stops goes quiet after this long. AlarmKit leaves it
     * to the system; Android's Clock app silences after 10 minutes by
     * default, so Conduit's do too.
     */
    const val AUTO_SILENCE_MS = 10 * 60_000L

    /** A timer's length, as PhoneChange takes it (iOS's 5...86_400). */
    val TIMER_SECONDS = 5..86_400

    /** The record a `set_alarm` or `set_timer` makes. */
    fun record(change: PhoneChange, now: Instant, zone: ZoneId): PhoneAlarmRecord {
        val fields = change.fields
        val setAt = now.toEpochMilli()
        return when (change.kind) {
            PhoneChangeKind.SET_TIMER -> {
                val seconds = fields.seconds?.takeIf { it in TIMER_SECONDS } ?: throw PhoneAlarmFailure(PhoneAlarmFailure.Reason.NOT_FOUND)
                PhoneAlarmRecord("timer", fields.label, firesAt = now.plusSeconds(seconds.toLong()).toEpochMilli(), setAt = setAt)
            }
            PhoneChangeKind.SET_ALARM -> {
                val at = fields.at
                val time = fields.time
                when {
                    at != null -> {
                        if (at <= now) throw PhoneAlarmFailure(PhoneAlarmFailure.Reason.PAST)
                        PhoneAlarmRecord("alarm", fields.label, firesAt = at.toEpochMilli(), setAt = setAt)
                    }
                    time != null -> {
                        val days = fields.repeats.orEmpty()
                        // No days: rings once, at the next time the clock shows it (AlarmKit's `.never`).
                        val once = if (days.isEmpty()) PhoneAlarmRules.nextRing(time, null, now, zone).toEpochMilli() else null
                        PhoneAlarmRecord("alarm", fields.label, repeats = fields.repeats, firesAt = once, time = time, setAt = setAt)
                    }
                    else -> throw PhoneAlarmFailure(PhoneAlarmFailure.Reason.NOT_FOUND)
                }
            }
            else -> throw PhoneAlarmFailure(PhoneAlarmFailure.Reason.NOT_FOUND)
        }
    }

    fun isRinging(record: PhoneAlarmRecord, now: Instant): Boolean =
        record.ringingSince?.let { now.toEpochMilli() < it + AUTO_SILENCE_MS } ?: false

    /** When the system alarm should next go off; null when nothing is left to ring. */
    fun nextFire(record: PhoneAlarmRecord, now: Instant, zone: ZoneId): Instant? {
        if (record.repeating) {
            // Not again for the ring that just went off, even if it went off a moment early.
            val after = record.ringingSince?.let { maxOf(now, Instant.ofEpochMilli(it + 60_000)) } ?: now
            return PhoneAlarmRules.nextRing(record.time!!, record.repeats, after, zone)
        }
        if (record.ringingSince != null) return null
        return record.firesAt?.let(Instant::ofEpochMilli)
    }

    /**
     * What's left once time has passed: a one-off that rang out, or whose
     * time went by more than [AUTO_SILENCE_MS] ago without ringing (the
     * phone was off), is gone; a repeating alarm that rang out waits for its
     * next day. A one-off whose time passed only just is kept: it rings now.
     */
    fun tidied(records: Map<String, PhoneAlarmRecord>, now: Instant): Map<String, PhoneAlarmRecord> {
        val nowMs = now.toEpochMilli()
        return records.mapNotNull { (id, record) ->
            val ringing = record.ringingSince
            when {
                ringing != null && nowMs >= ringing + AUTO_SILENCE_MS -> if (record.repeating) id to record.copy(ringingSince = null) else null
                ringing == null && !record.repeating && (record.firesAt ?: Long.MIN_VALUE) + AUTO_SILENCE_MS <= nowMs -> null
                else -> id to record
            }
        }.toMap()
    }

    /** The records once `id` started ringing; unchanged when it's gone (cancelled meanwhile). */
    fun fired(records: Map<String, PhoneAlarmRecord>, id: String, now: Instant): Map<String, PhoneAlarmRecord> {
        val record = records[id] ?: return records
        return records + (id to record.copy(ringingSince = now.toEpochMilli()))
    }

    /** Stop (or silenced): a one-off is done, a repeating alarm waits for its next day. */
    fun stopped(records: Map<String, PhoneAlarmRecord>, id: String): Map<String, PhoneAlarmRecord> {
        val record = records[id] ?: return records
        return if (record.repeating) records + (id to record.copy(ringingSince = null)) else records - id
    }

    /**
     * The time zone changed: a clock-time alarm rings when the clock shows
     * its time, as AlarmKit's relative schedules do, so a one-off one that
     * hasn't rung moves to its time in the new zone. Fixed alarms and timers
     * stay at their moment.
     */
    fun rezoned(records: Map<String, PhoneAlarmRecord>, now: Instant, zone: ZoneId): Map<String, PhoneAlarmRecord> =
        records.mapValues { (_, record) ->
            val time = record.time
            val firesAt = record.firesAt
            if (time == null || record.repeating || record.ringingSince != null || firesAt == null || firesAt <= now.toEpochMilli()) record
            else record.copy(firesAt = PhoneAlarmRules.nextRing(time, null, now, zone).toEpochMilli())
        }

    /** The `alarms` list: `{id, kind, label?, fires_at?, repeats?, state?}`, oldest first, up to [PhoneAlarmRecord.MAXIMUM]. */
    fun listing(records: Map<String, PhoneAlarmRecord>, now: Instant, zone: ZoneId): List<JsonObject> =
        records.entries.sortedWith(compareBy({ it.value.setAt }, { it.key })).take(PhoneAlarmRecord.MAXIMUM).map { (id, record) ->
            buildJsonObject {
                put("id", id)
                put("kind", record.kind)
                record.label?.let { put("label", it) }
                val ring = if (record.repeating) PhoneAlarmRules.nextRing(record.time!!, record.repeats, now, zone)
                else record.firesAt?.let(Instant::ofEpochMilli)
                ring?.let { put("fires_at", PhoneDates.format(it, zone)) }
                record.repeats?.let { days -> put("repeats", JsonArray(days.map(::JsonPrimitive))) }
                put("state", if (isRinging(record, now)) "alerting" else "scheduled")
            }
        }
}
