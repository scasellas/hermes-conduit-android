package app.hermesconduit.android

import app.hermesconduit.android.model.PhoneAlarmBook
import app.hermesconduit.android.model.PhoneAlarmFailure
import app.hermesconduit.android.model.PhoneAlarmRecord
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneClockTime
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Conduit's own alarms (iOS AlarmKitPhoneSource): what a change sets, ringing, and the `alarms` list. */
class PhoneAlarmBookTest {
    private val zone = ZoneId.of("Europe/Berlin")
    // Wednesday 15 October 2025, 10:00 in Berlin.
    private val now = Instant.parse("2025-10-15T08:00:00Z")

    private fun change(kind: String, fields: JsonObject, target: JsonObject? = null): PhoneChange = PhoneChange.parse(
        buildJsonObject {
            put("id", "chg_1")
            put("kind", kind)
            put("fields", fields)
            target?.let { put("target", it) }
        },
    )!!

    private fun failure(block: () -> Unit): PhoneAlarmFailure.Reason {
        try {
            block()
        } catch (e: PhoneAlarmFailure) {
            return e.reason
        }
        fail("expected a failure")
        throw AssertionError()
    }

    @Test
    fun aTimerIsAnAlarmAtNowPlusItsLength() {
        val record = PhoneAlarmBook.record(change("set_timer", buildJsonObject { put("seconds", 600); put("label", "Pasta") }), now, zone)
        assertEquals("timer", record.kind)
        assertEquals("Pasta", record.label)
        assertEquals(now.plusSeconds(600).toEpochMilli(), record.firesAt)
        assertFalse(record.repeating)
        assertEquals(PhoneAlarmBook.nextFire(record, now, zone), now.plusSeconds(600))
        val unnamed = PhoneAlarmBook.record(change("set_timer", buildJsonObject { put("seconds", 5) }), now, zone)
        assertNull("The name shows as Timer, but Hermes hears none", unnamed.label)
    }

    @Test
    fun aFixedAlarmRingsAtItsMomentAndNotInThePast() {
        val at = "2025-11-20T07:15:00+01:00"
        val record = PhoneAlarmBook.record(change("set_alarm", buildJsonObject { put("at", at) }), now, zone)
        assertEquals(Instant.parse("2025-11-20T06:15:00Z").toEpochMilli(), record.firesAt)
        assertNull(record.time)
        // More than a day ahead is fine: the Clock app's 24-hour limit is gone.
        assertEquals(Instant.parse("2025-11-20T06:15:00Z"), PhoneAlarmBook.nextFire(record, now, zone))
        assertEquals(PhoneAlarmFailure.Reason.PAST, failure {
            PhoneAlarmBook.record(change("set_alarm", buildJsonObject { put("at", "2025-10-15T09:00:00+02:00") }), now, zone)
        })
    }

    @Test
    fun aClockTimeAlarmWithoutDaysRingsOnceAtTheNextTime() {
        val record = PhoneAlarmBook.record(change("set_alarm", buildJsonObject { put("time", "06:30"); put("label", "Wake up") }), now, zone)
        assertEquals(PhoneClockTime(6, 30), record.time)
        assertNull(record.repeats)
        assertFalse(record.repeating)
        // 06:30 has passed today: tomorrow, Thursday.
        assertEquals(Instant.parse("2025-10-16T04:30:00Z").toEpochMilli(), record.firesAt)
        // It rang: done, not tomorrow again.
        val records = mapOf("a" to record)
        val ringingAt = Instant.parse("2025-10-16T04:30:00Z")
        val rang = PhoneAlarmBook.fired(records, "a", ringingAt)
        assertNull(PhoneAlarmBook.nextFire(rang.getValue("a"), ringingAt, zone))
        assertTrue(PhoneAlarmBook.stopped(rang, "a").isEmpty())
    }

    @Test
    fun repeatingAlarmsRingOnTheirWeekdaysAsIOSDoes() {
        val record = PhoneAlarmBook.record(
            change("set_alarm", buildJsonObject { put("time", "07:00"); put("repeats", JsonArray(listOf("fri", "mon").map(::JsonPrimitive))) }), now, zone,
        )
        assertTrue(record.repeating)
        assertEquals("Days go in week order", listOf("mon", "fri"), record.repeats)
        assertNull(record.firesAt)
        val first = PhoneAlarmBook.nextFire(record, now, zone)!!
        assertEquals(DayOfWeek.FRIDAY, first.atZone(zone).dayOfWeek)
        assertEquals("07:00", first.atZone(zone).toLocalTime().toString())

        // It rings on Friday (a moment early, even), then waits for Monday, and stays.
        val early = first.minusMillis(40)
        val ringing = PhoneAlarmBook.fired(mapOf("r" to record), "r", early)
        val next = PhoneAlarmBook.nextFire(ringing.getValue("r"), early, zone)!!
        assertEquals(DayOfWeek.MONDAY, next.atZone(zone).dayOfWeek)
        val stopped = PhoneAlarmBook.stopped(ringing, "r")
        assertNull(stopped.getValue("r").ringingSince)
        // Silenced by itself too: kept for Monday.
        val later = PhoneAlarmBook.tidied(ringing, early.plusMillis(PhoneAlarmBook.AUTO_SILENCE_MS))
        assertNull(later.getValue("r").ringingSince)
    }

    @Test
    fun changesThatSayNothingToRingAreRefused() {
        assertEquals(PhoneAlarmFailure.Reason.NOT_FOUND, failure {
            PhoneAlarmBook.record(change("cancel_alarm", buildJsonObject {}, buildJsonObject { put("alarm_id", "x") }), now, zone)
        })
    }

    @Test
    fun theListReportsRealState() {
        val timer = PhoneAlarmRecord("timer", null, firesAt = now.plusSeconds(300).toEpochMilli(), setAt = 2)
        val weekly = PhoneAlarmRecord("alarm", "Gym", repeats = listOf("sat"), time = PhoneClockTime(9, 0), setAt = 1)
        val ringing = PhoneAlarmRecord("alarm", "Call Mum", firesAt = now.minusSeconds(60).toEpochMilli(), setAt = 3, ringingSince = now.minusSeconds(60).toEpochMilli())
        val list = PhoneAlarmBook.listing(mapOf("t" to timer, "w" to weekly, "r" to ringing), now, zone)
        assertEquals(listOf("w", "t", "r"), list.map { it["id"].str })
        val w = list[0]
        assertEquals("alarm", w["kind"].str)
        assertEquals("Gym", w["label"].str)
        assertEquals("2025-10-18T09:00:00+02:00", w["fires_at"].str)
        assertEquals(JsonArray(listOf(JsonPrimitive("sat"))), w["repeats"])
        assertEquals("scheduled", w["state"].str)
        val t = list[1]
        assertEquals(setOf("id", "kind", "fires_at", "state"), t.keys)
        assertEquals("timer", t["kind"].str)
        assertEquals("2025-10-15T10:05:00+02:00", t["fires_at"].str)
        assertEquals("alerting", list[2]["state"].str)

        val many = (1..60).associate { "a$it" to timer.copy(setAt = it.toLong()) }
        assertEquals(PhoneAlarmRecord.MAXIMUM, PhoneAlarmBook.listing(many, now, zone).size)
    }

    @Test
    fun alarmsThatRangAndWentAreForgotten() {
        val silence = PhoneAlarmBook.AUTO_SILENCE_MS
        val rangOut = PhoneAlarmRecord("timer", firesAt = now.toEpochMilli() - silence - 1, ringingSince = now.toEpochMilli() - silence - 1)
        val missed = PhoneAlarmRecord("alarm", firesAt = now.toEpochMilli() - silence)
        val justDue = PhoneAlarmRecord("alarm", firesAt = now.toEpochMilli() - 1_000)
        val ahead = PhoneAlarmRecord("alarm", firesAt = now.toEpochMilli() + 1_000)
        val kept = PhoneAlarmBook.tidied(mapOf("rangOut" to rangOut, "missed" to missed, "justDue" to justDue, "ahead" to ahead), now)
        assertEquals(setOf("justDue", "ahead"), kept.keys)
        // One that's only just due rings now (after a reboot, say).
        assertEquals(Instant.ofEpochMilli(justDue.firesAt!!), PhoneAlarmBook.nextFire(justDue, now, zone))
        assertFalse(PhoneAlarmBook.isRinging(justDue, now))
        assertTrue(PhoneAlarmBook.isRinging(justDue.copy(ringingSince = now.toEpochMilli()), now.plusSeconds(60)))
        assertEquals("Gone before it rang: nothing to mark", kept, PhoneAlarmBook.fired(kept, "gone", now))
    }

    @Test
    fun aOneOffClockTimeAlarmFollowsTheClockToANewZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val clock = PhoneAlarmRecord("alarm", time = PhoneClockTime(6, 30), firesAt = Instant.parse("2025-10-16T04:30:00Z").toEpochMilli())
        val fixed = PhoneAlarmRecord("alarm", firesAt = Instant.parse("2025-10-16T04:30:00Z").toEpochMilli())
        val moved = PhoneAlarmBook.rezoned(mapOf("c" to clock, "f" to fixed), now, tokyo)
        // 17:00 in Tokyo now: 06:30 tomorrow there.
        assertEquals(Instant.parse("2025-10-15T21:30:00Z").toEpochMilli(), moved.getValue("c").firesAt)
        assertEquals(fixed, moved.getValue("f"))
    }

    @Test
    fun recordsRoundTripAndLegacyKeyIsApart() {
        val records = mapOf("a" to PhoneAlarmRecord("alarm", "Wake", listOf("mon"), null, PhoneClockTime(6, 0), 5, 9))
        assertEquals(records, PhoneAlarmRecord.decodeAll(PhoneAlarmRecord.encodeAll(records)))
        assertTrue(PhoneAlarmRecord.decodeAll("not json").isEmpty())
        assertFalse(PhoneAlarmRecord.STORAGE_KEY == PhoneAlarmRecord.LEGACY_STORAGE_KEY)
        // A Clock-app record from before reads, but isn't Conduit's to ring.
        assertEquals("conduit.phoneSync.alarms.v1", PhoneAlarmRecord.LEGACY_STORAGE_KEY)
    }
}
