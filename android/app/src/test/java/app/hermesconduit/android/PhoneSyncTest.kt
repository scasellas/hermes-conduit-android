package app.hermesconduit.android

import app.hermesconduit.android.data.MemoryTextStore
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PhoneAlarmRules
import app.hermesconduit.android.model.PhoneCalendarAccess
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangeKind
import app.hermesconduit.android.model.PhoneChangePrompt
import app.hermesconduit.android.model.PhoneChangeWording
import app.hermesconduit.android.model.PhoneClockTime
import app.hermesconduit.android.model.PhoneFocusSnapshot
import app.hermesconduit.android.model.PhonePerson
import app.hermesconduit.android.model.PhoneSessionKey
import app.hermesconduit.android.model.PhoneSyncCrypto
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.PhoneSyncRules
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.phone.PhoneAlarmSource
import app.hermesconduit.android.ui.phone.PhoneCalendarSource
import app.hermesconduit.android.ui.phone.PhoneKeyStore
import app.hermesconduit.android.ui.phone.PhoneStatusSource
import app.hermesconduit.android.ui.phone.PhoneSyncEngine
import app.hermesconduit.android.ui.phone.PhoneVoiceTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Port of ConduitTests/PhoneSyncTests.swift. */
class PhoneSyncTest {
    private val gmt: ZoneId = ZoneOffset.UTC

    private fun json(value: Any?) = jsonOf(value) as JsonObject

    @Test
    fun phoneSealingMatchesTheNotifierVector() {
        val raw = SealCrypto.base64UrlDecoded("ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8")!!
        val device = SealCrypto.KeyPair(raw)
        val hostPublicKey = "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8"
        val kid = "623ffc0d912da4f291e9b21f5e9525a4"
        assertEquals(kid, PhoneSyncCrypto.keyID(device.publicKey, SealCrypto.base64UrlDecoded(hostPublicKey)!!))

        val key = PhoneSyncCrypto.agree(raw, "device-vector-1", hostPublicKey, kid)
        assertEquals("630785d4c722fbb7302ec37755e28687dc6ad95908044f75d23c2a68c5bda771", SealCrypto.hex(key.ingestBytes))
        assertEquals("897af24a563a0c8828564d83559f2c47a9f154ddc247ef3dc7517cb00a4d44e2", SealCrypto.hex(key.requestBytes))
        assertEquals("0e3aa3a1b6e61220b05cf2bc928c09be5b6dc7aee322e9e79b13d6472da199ec", SealCrypto.hex(key.answerBytes))

        val batch = """{"batch_id":"batch-vector-1","sent_at":"2026-10-14T08:02:11+01:00","sharing":{"calendar":"full","reminders":true}}"""
        val envelope = PhoneSyncCrypto.envelope(batch.toByteArray(), key, "device-vector-1", "batch-vector-1", ByteArray(12))
        assertEquals("AAAAAAAAAAAAAAAA", envelope["nonce"].str)
        assertEquals(
            "urNzwshgjVt59WJvei1cvkp4Wc5TcyVM23WAjA5ZYzKt5-yCuj5nf1wcGId5cRo_-gZWSlB6HzCH9-5eRkplX2HfKXWd5MclQSsgzZiR4gfhuMHlK48hL1dZy83OiMzIJ-ooKCr3oyJQ9rEZSdMt5y0D0q468B-Q1dLBKdoYvePegQ",
            envelope["ct"].str,
        )

        val request = """{"call_id":"call-vector-1","results":[{"id":"ch_vector000001","outcome":"applied","item_id":"e_1"}]}"""
        val exchange = PhoneSyncCrypto.exchangeEnvelope(request.toByteArray(), "changes", key, "device-vector-1", "call-vector-1", ByteArray(12) { 1 })
        assertEquals(
            "gELNGLE22UzmOD9jFhmSx2vvTH46EwCS3bOLvdBDvzUNVtuX4BUIafEHjmztpE2ePKpnfSirlccBF3ofBO7oFtA52hidOys7o54b_F_ExfXTtk3va2EBeRBbjFbj1ozUSOkGbc1cLenEB2JgsArgdPbgyEs",
            exchange["ct"].str,
        )

        val answer = buildJsonObject {
            put("nonce", "AgICAgICAgICAgIC")
            put("ct", "jzmv7wQbA5r8CkriEVswbKhirSLiYHJJQdLAWdBti6KmGBraXGxE4B3wTTRFUMAGFVB3D6Vcyos")
        }
        assertEquals("""{"call_id":"call-vector-1","changes":[]}""", PhoneSyncCrypto.openAnswer(answer, "changes", key, "device-vector-1", "call-vector-1").decodeToString())
        try {
            PhoneSyncCrypto.openAnswer(answer, "changes", key, "device-vector-1", "call-other")
            fail("An answer to another call is refused")
        } catch (_: SealCrypto.Failure) {
        }
    }

    @Test
    fun phoneSyncSendsOnlyWhatChanged() = runBlocking {
        val source = FakeCalendar()
        source.events = listOf(event("e_1", "Dentist"), event("e_2", "Gym"))
        val host = FakePhoneHost()
        val engine = makeEngine(source)
        engine.savePreferences(preferences())

        assertEquals(SyncOutcome.Synced, engine.sync(Instant.MAX, host::handle))
        assertEquals(1, host.keyRequests)
        val full = host.batches.last()["events"].obj!!
        assertEquals(true, full["full"].bool)
        assertEquals(2, full["upsert"].arr?.size)
        assertEquals("full", host.batches.last()["sharing"].obj?.get("calendar").str)
        assertNotNull("Focus goes when shared", host.batches.last()["focus"])

        engine.sync(Instant.MAX, host::handle)
        assertEquals(1, host.keyRequests)
        assertNull(host.batches.last()["events"])

        source.events = listOf(event("e_1", "Dentist (moved)"))
        engine.sync(Instant.MAX, host::handle)
        val diff = host.batches.last()["events"].obj!!
        assertEquals(false, diff["full"].bool)
        assertEquals(listOf("e_1"), diff["upsert"].arr?.map { it.obj?.get("id").str })
        assertEquals(listOf("e_2"), diff["delete"].arr?.map { it.str })

        host.forgetKey()
        engine.sync(Instant.MAX, host::handle)
        assertEquals(2, host.keyRequests)
        assertEquals(true, host.batches.last()["events"].obj?.get("full").bool)
    }

    @Test
    fun phoneChangesAskUnlessTheUserChoseAddWithoutAsking() = runBlocking {
        val source = FakeCalendar()
        val host = FakePhoneHost()
        host.changes = listOf(
            json(mapOf("id" to "ch_add", "kind" to "add_event", "auto" to true, "fields" to mapOf("title" to "Buy milk", "start" to "2026-10-15T12:00:00Z"))),
            json(mapOf("id" to "ch_del", "kind" to "delete_event", "auto" to false, "target" to mapOf("event_id" to "e_1"), "fields" to emptyMap<String, Any>())),
        )
        val engine = makeEngine(source)
        engine.savePreferences(preferences())

        engine.sync(Instant.MAX, host::handle)
        assertEquals(listOf("ch_add", "ch_del"), engine.prompts.value.map { it.id })
        assertTrue(source.applied.isEmpty())

        engine.answer(engine.prompts.value.first(), true)
        engine.answer(engine.prompts.value.first(), false)
        assertEquals(listOf("ch_add"), source.applied)
        host.changes = emptyList()
        engine.sync(Instant.MAX, host::handle)
        assertEquals(listOf("ch_add", "ch_del"), host.reported.map { it["id"].str })
        assertEquals(listOf("applied", "dismissed"), host.reported.map { it["outcome"].str })
        assertTrue(engine.state.value.unreported.isEmpty())

        engine.savePreferences(preferences().copy(autoAdd = true))
        host.changes = listOf(
            json(mapOf("id" to "ch_add2", "kind" to "add_event", "auto" to true,
                "fields" to mapOf("title" to "Lunch", "start" to "2026-10-15T12:00:00Z", "end" to "2026-10-15T13:00:00Z"))),
            json(mapOf("id" to "ch_del2", "kind" to "delete_event", "auto" to false, "target" to mapOf("event_id" to "e_1"), "fields" to emptyMap<String, Any>())),
        )
        engine.sync(Instant.MAX, host::handle)
        assertEquals(listOf("ch_add", "ch_add2"), source.applied)
        assertEquals("Lunch", engine.autoAdded.value?.title)
        assertEquals(listOf("ch_del2"), engine.prompts.value.map { it.id })

        // A report the host lost: the change comes back, and isn't applied twice.
        host.ignoreResults = true
        engine.sync(Instant.MAX, host::handle)
        assertEquals(listOf("ch_add", "ch_add2"), source.applied)
        assertEquals("Reported again next time", listOf("ch_add2"), engine.state.value.unreported.map { it.id })

        // An added item counts as sent, so once it's gone the next upload deletes it on the host.
        val deletions = host.batches.flatMap { batch -> batch["events"].obj?.get("delete").arr.orEmpty().mapNotNull { it.str } }
        assertTrue(deletions.contains("item_ch_add2"))
    }

    @Test
    fun phoneChangeNeedsWhatItActsOn() {
        assertNull("A deletion names its event", PhoneChange.parse(json(mapOf("id" to "ch_1", "kind" to "delete_event", "fields" to emptyMap<String, Any>()))))
        assertNull(PhoneChange.parse(json(mapOf("id" to "ch_2", "kind" to "add_event", "fields" to mapOf("title" to "No time")))))
        assertNull(PhoneChange.parse(json(mapOf("id" to "ch_3", "kind" to "format_disk"))))
        val change = PhoneChange.parse(json(mapOf("id" to "ch_4", "kind" to "add_reminder", "auto" to true,
            "fields" to mapOf("title" to "Call mum", "due" to "2026-10-16", "url" to "javascript:alert(1)"))))
        assertEquals("Call mum", change?.fields?.title)
        assertNotNull(change?.fields?.due)
        assertNull("Only web links", change?.fields?.url)
    }

    @Test
    fun phoneDiffSendsChangesAndDeletions() {
        val sent = mapOf("a" to PhoneSyncRules.hash(json(mapOf("id" to "a", "title" to "A"))), "b" to "old")
        val section = PhoneSyncRules.diff(listOf(json(mapOf("id" to "a", "title" to "A")), json(mapOf("id" to "c", "title" to "C"))), sent, false)
        assertEquals(listOf("c"), section.upsert.map { it["id"].str })
        assertEquals(listOf("b"), section.delete)
        assertEquals(setOf("a", "c"), section.hashes.keys)
        val whole = PhoneSyncRules.diff(listOf(json(mapOf("id" to "a", "title" to "A"))), sent, true)
        assertEquals(1, whole.upsert.size)
        assertTrue("A full upload replaces; nothing to delete by id", whole.delete.isEmpty())
        assertTrue("An item past the limit isn't reported deleted", PhoneSyncRules.diff(listOf(json(mapOf("id" to "a"))), sent, false, truncated = true).delete.isEmpty())

        val chosen = PhoneSyncPreferences(calendar = "full", reminders = true, status = true, focus = true)
        val narrowed = PhoneSyncRules.effectiveSharing(chosen, PhoneCalendarAccess.WRITE_ONLY, false)
        assertEquals("write_only", narrowed["calendar"].str)
        assertEquals(false, narrowed["reminders"].bool)
        assertEquals("off", PhoneSyncRules.effectiveSharing(chosen, PhoneCalendarAccess.OFF, true)["calendar"].str)
        val kept = PhoneSyncRules.effectiveSharing(chosen, PhoneCalendarAccess.FULL, true)
        assertEquals("full", kept["calendar"].str)
        assertEquals(true, kept["reminders"].bool)
        val addOnly = PhoneSyncPreferences(calendar = "write_only")
        assertEquals("write_only", PhoneSyncRules.effectiveSharing(addOnly, PhoneCalendarAccess.FULL, true)["calendar"].str)
        assertEquals(false, PhoneSyncRules.effectiveSharing(addOnly, PhoneCalendarAccess.FULL, true)["reminders"].bool)
        val withEmails = chosen.copy(details = true, attendeeEmails = true)
        assertEquals(true, PhoneSyncRules.effectiveSharing(withEmails, PhoneCalendarAccess.FULL, true)["attendee_emails"].bool)
        val emailsNarrowed = PhoneSyncRules.effectiveSharing(withEmails, PhoneCalendarAccess.WRITE_ONLY, true)
        assertEquals(false, emailsNarrowed["attendee_emails"].bool)
        assertEquals("Details also cover reminder notes", true, emailsNarrowed["details"].bool)
        assertEquals("Nothing detailed is sent", false, PhoneSyncRules.effectiveSharing(withEmails, PhoneCalendarAccess.WRITE_ONLY, false)["details"].bool)
    }

    @Test
    fun phoneVoiceUsesAreLoggedApart() {
        val now = 1_760_000_000_000L
        val log = VoiceReadLog().recording(LiveVoiceMode.GEMINI_LIVE, "events", now)
        val decoded = VoiceReadLog.decode(log.encode(), now)
        assertEquals(listOf("events"), decoded.entries.map { it.action })
        assertEquals("Gemini Live", decoded.entries.first().modeName)
        assertTrue("A week later it's gone", VoiceReadLog.decode(log.encode(), now + VoiceReadLog.KEPT_FOR_MS + 1).entries.isEmpty())
        assertEquals(200, (0 until 250).fold(VoiceReadLog()) { acc, i -> acc.recording(LiveVoiceMode.GPT_LIVE, "a$i", now) }.entries.size)
    }

    @Test
    fun phoneChangeIsNeverAppliedTwice() = runBlocking {
        val source = FakeCalendar()
        val host = FakePhoneHost()
        val engine = makeEngine(source)
        engine.savePreferences(preferences())
        host.changes = listOf(json(mapOf("id" to "ch_once", "kind" to "add_event", "auto" to false, "fields" to mapOf("title" to "Buy milk", "start" to "2026-10-15T12:00:00Z"))))
        engine.sync(Instant.MAX, host::handle)
        val prompt = engine.prompts.value.first()

        source.hold = CompletableDeferred()
        val first = async { engine.answer(prompt, true) }
        repeat(500) { if (!source.holding) delay(2) }
        assertTrue(source.holding)
        engine.answer(prompt, true)
        engine.sync(Instant.MAX, host::handle)
        assertTrue(engine.prompts.value.isEmpty())
        source.hold?.complete(Unit)
        first.await()
        assertEquals(listOf("ch_once"), source.applied)
    }

    @Test
    fun phoneVoiceCallsReadAnswerAndAdd() = runBlocking {
        val source = FakeCalendar()
        val host = FakePhoneHost()
        val engine = makeEngine(source)
        engine.savePreferences(preferences())
        engine.sync(Instant.MAX, host::handle)

        val read = engine.voiceRead("conduit_calendar", json(mapOf("action" to "next")), "gemini-live", "api/plugins/conduit_push/voice-tools/call", host::handle)
        assertEquals("conduit_calendar", read.obj?.get("tool").str)

        val tools = PhoneVoiceTools.tools(preferences(), PhoneCalendarAccess.FULL, remindersAuthorized = false)
        assertEquals(listOf(PhoneVoiceTools.CHANGES_TOOL, PhoneVoiceTools.ADD_TOOL), tools.map { it.name })
        val fullAccessAdd = tools.last()
        assertEquals(listOf("event"), fullAccessAdd.parameters["properties"].obj?.get("kind").obj?.get("enum").arr?.map { it.str })
        assertTrue(PhoneVoiceTools.tools(PhoneSyncPreferences(), PhoneCalendarAccess.FULL, true).isEmpty())
        assertEquals(listOf(PhoneVoiceTools.CHANGES_TOOL), PhoneVoiceTools.tools(preferences(), PhoneCalendarAccess.OFF, true).map { it.name })
        val addOnly = PhoneVoiceTools.tools(preferences(), PhoneCalendarAccess.WRITE_ONLY, true).last()
        assertTrue(addOnly.description.contains("leave the calendar out"))
        assertFalse(fullAccessAdd.description.contains("leave the calendar out"))

        host.changes = listOf(json(mapOf("id" to "ch_voice", "kind" to "add_event", "auto" to false, "reason" to "You asked",
            "fields" to mapOf("title" to "Call the plumber", "start" to "2026-10-15T12:00:00Z"))))
        engine.sync(Instant.MAX, host::handle)
        val changesTool = PhoneVoiceTools.tools(preferences(), PhoneCalendarAccess.FULL, true).first()
        val answer: suspend (PhoneChangePrompt, Boolean) -> Unit = { prompt, approve -> engine.answer(prompt, approve) }
        val listed = PhoneVoiceTools.run(changesTool, json(mapOf("action" to "list")), engine, answer)
        assertTrue(listed["result"]?.contains("Call the plumber") == true)
        assertTrue(PhoneVoiceTools.note(engine.prompts.value.first()).contains("ch_voice"))
        val approved = PhoneVoiceTools.run(changesTool, json(mapOf("action" to "approve", "id" to "ch_voice")), engine, answer)
        assertNotNull(approved["result"])
        assertEquals(listOf("ch_voice"), source.applied)
        assertNotNull("Answered already: not applied twice", PhoneVoiceTools.run(changesTool, json(mapOf("action" to "approve", "id" to "ch_voice")), engine, answer)["error"])

        val added = PhoneVoiceTools.run(fullAccessAdd, json(mapOf("kind" to "event", "title" to "Dentist", "start" to "2026-10-16T15:00", "calendar" to "personal")), engine, answer)
        assertNotNull(added["error"] ?: "", added["result"])
        val voiceID = source.applied.last()
        assertTrue(voiceID.startsWith("voice_"))
        assertEquals("", engine.state.value.eventHashes["item_$voiceID"])
        val unknown = PhoneVoiceTools.run(fullAccessAdd, json(mapOf("kind" to "event", "title" to "Gym", "start" to "2026-10-16T07:00", "calendar" to "Work")), engine, answer)
        assertNotNull("No calendar called Work", unknown["error"])
        assertNotNull(PhoneChangeWording.localTime("2026-10-16T15:00"))
        assertEquals(true, PhoneChangeWording.localTime("2026-10-16")?.second)
        assertNull(PhoneChangeWording.localTime("Thursday"))
    }

    @Test
    fun voiceModeNotAllowedIsToldWhereToAllowIt() {
        var preferences = preferences().copy(reminders = true)
        val hint = PhoneVoiceTools.settingHint(preferences, LiveVoiceMode.GPT_LIVE)
        assertTrue(hint.contains("GPT-Live"))
        assertTrue(hint.contains("Calendar & phone"))
        assertTrue(hint, hint.contains("shares their calendar and their reminders with Hermes"))
        val alarmsOnly = PhoneSyncPreferences(destination = preferences.destination).withAlarms(true)
        val alarmsHint = PhoneVoiceTools.settingHint(alarmsOnly, LiveVoiceMode.GPT_LIVE)
        assertTrue(alarmsHint, alarmsHint.contains("shares their alarms with Hermes, but hasn't let this call use it"))
        assertFalse(alarmsHint.contains("calendar"))
        val peopleOnly = PhoneSyncPreferences(destination = preferences.destination).withContacts(true)
        assertTrue(PhoneVoiceTools.settingHint(peopleOnly, LiveVoiceMode.GPT_LIVE).contains("shares the people they picked with Hermes"))
        preferences = preferences.withVoiceUse(LiveVoiceMode.GPT_LIVE, true)
        assertEquals("", PhoneVoiceTools.settingHint(preferences, LiveVoiceMode.GPT_LIVE))
        assertFalse(PhoneVoiceTools.settingHint(preferences, LiveVoiceMode.GEMINI_LIVE).isEmpty())
        val statusOnly = PhoneSyncPreferences(destination = preferences.destination, status = true, focus = true)
        assertEquals("", PhoneVoiceTools.settingHint(statusOnly, LiveVoiceMode.GEMINI_LIVE))
        assertEquals("", PhoneVoiceTools.settingHint(preferences.copy(destination = null), LiveVoiceMode.GEMINI_LIVE))
    }

    @Test
    fun phoneAlarmChangesNeedWhatTheyAct() {
        val weekly = PhoneChange.parse(json(mapOf("id" to "ch_a", "kind" to "set_alarm", "auto" to true,
            "fields" to mapOf("label" to "Wake up", "time" to "06:30", "repeats" to listOf("fri", "MON", "tue", "wed", "thu", "sun?")))))!!
        assertEquals(PhoneClockTime(6, 30), weekly.fields.time)
        assertEquals(listOf("mon", "tue", "wed", "thu", "fri"), weekly.fields.repeats)
        assertTrue(weekly.kind.addsWithoutAsking)
        assertNull("HH:MM only", PhoneChange.parse(json(mapOf("id" to "ch_b", "kind" to "set_alarm", "fields" to mapOf("time" to "6:30")))))
        assertNull(PhoneChange.parse(json(mapOf("id" to "ch_c", "kind" to "set_alarm", "fields" to mapOf("label" to "No time")))))
        assertNull(PhoneChange.parse(json(mapOf("id" to "ch_d", "kind" to "set_timer", "fields" to mapOf("seconds" to 2)))))
        assertNotNull(PhoneChange.parse(json(mapOf("id" to "ch_e", "kind" to "set_timer", "fields" to mapOf("seconds" to 600)))))
        assertNull(PhoneChange.parse(json(mapOf("id" to "ch_f", "kind" to "cancel_alarm", "fields" to emptyMap<String, Any>()))))
        val cancel = PhoneChange.parse(json(mapOf("id" to "ch_g", "kind" to "cancel_alarm", "target" to mapOf("alarm_id" to "a1"))))!!
        assertEquals("a1", cancel.alarmID)
        assertFalse("Cancelling always asks", cancel.kind.addsWithoutAsking)
        assertTrue(PhoneChangeWording.isRemoval(cancel.kind))
        assertTrue(PhoneChangeWording.alarmSummary(weekly.fields, PhoneChangeKind.SET_ALARM).startsWith("Wake up · "))
        assertEquals("weekdays", PhoneChangeWording.days(listOf("mon", "tue", "wed", "thu", "fri")))
        assertEquals("weekends", PhoneChangeWording.days(listOf("sat", "sun")))
        assertNull(PhoneChangeWording.days(null))
    }

    @Test
    fun phoneAlarmNextRingFollowsItsDays() {
        // Wednesday 15 October 2025, 12:00 UTC.
        val now = Instant.ofEpochSecond(1_760_529_600)
        val time = PhoneClockTime(6, 30)
        assertEquals("Tomorrow, the time having passed today", Instant.ofEpochSecond(1_760_509_800 + 86_400), PhoneAlarmRules.nextRing(time, null, now, gmt))
        assertEquals(DayOfWeek.MONDAY, PhoneAlarmRules.nextRing(time, listOf("mon"), now, gmt).atZone(gmt).dayOfWeek)
        assertEquals("Friday comes before Monday", DayOfWeek.FRIDAY, PhoneAlarmRules.nextRing(time, listOf("mon", "fri"), now, gmt).atZone(gmt).dayOfWeek)
    }

    @Test
    fun phoneAlarmsAndPeopleSyncAndApply() = runBlocking {
        val source = FakeCalendar()
        val alarms = FakeAlarms()
        alarms.list = mutableListOf(json(mapOf("id" to "a_old", "kind" to "alarm", "label" to "Gym", "fires_at" to "2026-10-15T06:00:00Z")))
        val host = FakePhoneHost()
        val engine = makeEngine(source, alarms)
        var preferences = preferences().withAlarms(true).withContacts(true).copy(autoAdd = true)
        engine.savePreferences(preferences)
        engine.savePeople(listOf(PhonePerson("p_1", "Sam Lee", relation = "wife", phones = listOf(PhonePerson.Phone("+44 7700 900123", "mobile")), birthday = "--04-12")))

        engine.sync(Instant.MAX, host::handle)
        val sharing = host.batches.last()["sharing"].obj!!
        assertEquals(true, sharing["alarms"].bool)
        assertEquals(true, sharing["contacts"].bool)
        assertEquals(listOf("a_old"), host.batches.last()["alarms"].arr?.map { it.obj?.get("id").str })
        val people = host.batches.last()["contacts"].arr!!
        assertEquals("wife", people.first().obj?.get("relation").str)
        assertEquals("--04-12", people.first().obj?.get("birthday").str)

        engine.sync(Instant.MAX, host::handle)
        assertNull(host.batches.last()["alarms"])
        assertNull(host.batches.last()["contacts"])

        host.changes = listOf(
            json(mapOf("id" to "ch_timer", "kind" to "set_timer", "auto" to true, "fields" to mapOf("label" to "Pasta", "seconds" to 600))),
            json(mapOf("id" to "ch_cancel", "kind" to "cancel_alarm", "auto" to false, "target" to mapOf("alarm_id" to "a_old"))),
        )
        engine.sync(Instant.MAX, host::handle)
        assertEquals(listOf("ch_timer"), alarms.applied.map { it.id })
        assertTrue("Alarms never reach the calendar", source.applied.isEmpty())
        assertEquals(true, engine.autoAdded.value?.isAlarm)
        val prompt = engine.prompts.value.first()
        assertEquals("ch_cancel", prompt.id)
        assertNull(prompt.calendarTitle)
        assertTrue(prompt.alarmTitle?.startsWith("Gym · ") == true)

        engine.answer(prompt, true)
        assertEquals(listOf("a_old"), alarms.cancelled)
        engine.undo(engine.autoAdded.value!!)
        assertEquals(listOf("a_old", "alarm_1"), alarms.cancelled)
        host.changes = emptyList()
        engine.sync(Instant.MAX, host::handle)
        assertEquals("alarm_1", host.reported.first { it["id"].str == "ch_timer" }["item_id"].str)
        assertEquals("Both gone from the phone", 0, host.batches.last()["alarms"].arr?.size)
        alarms.list = mutableListOf(json(mapOf("id" to "a_new", "kind" to "alarm")))
        alarms.unreadable = true
        engine.sync(Instant.MAX, host::handle)
        assertNull(host.batches.last()["alarms"])
        alarms.unreadable = false
        engine.sync(Instant.MAX, host::handle)
        assertEquals(1, host.batches.last()["alarms"].arr?.size)

        preferences = preferences.withAlarms(false).withContacts(false)
        engine.savePreferences(preferences)
        engine.sync(Instant.MAX, host::handle)
        val off = host.batches.last()["sharing"].obj!!
        assertEquals(false, off["alarms"].bool)
        assertEquals(false, off["contacts"].bool)
        assertNull(host.batches.last()["contacts"])
    }

    @Test
    fun phonePeoplePickedAgainKeepTheirRelation() {
        val people = PhonePerson.merging(listOf(PhonePerson("p_1", "Sam Lee (new)"), PhonePerson("p_2", "Alex")), listOf(PhonePerson("p_1", "Sam Lee", relation = "wife")))
        assertEquals(listOf("Sam Lee (new)", "Alex"), people.map { it.name })
        assertEquals("wife", people.first().relation)
        assertEquals(PhonePerson.RELATION_LENGTH, PhonePerson("p_3", "Kim", relation = "x".repeat(60)).json["relation"].str?.length)
        assertNull(PhonePerson("p_4", "Lee", relation = "  ").json["relation"])
        assertEquals("1990-04-12", PhonePerson.birthday(1990, 4, 12))
        assertEquals("--04-12", PhonePerson.birthday(null, 4, 12))
        assertEquals(PhonePerson.id("ABC"), PhonePerson.id("ABC"))
        assertFalse(PhonePerson.id("ABC") == PhonePerson.id("ABD"))
        assertEquals("+44 7700-900123", PhonePerson.plainNumber("+44 7700‑900123 "))
        assertTrue(PhonePerson.isDialable("+44 7700-900123"))
        assertFalse(PhonePerson.isDialable("1-800-FLOWERS"))
        assertFalse(PhonePerson.isDialable("--- ..."))
        assertEquals("1990-04-12", app.hermesconduit.android.ui.phone.PhoneContacts.parseBirthday("1990-04-12"))
        assertEquals("--04-12", app.hermesconduit.android.ui.phone.PhoneContacts.parseBirthday("--04-12"))
    }

    @Test
    fun phoneVoiceCallsSetAlarmsAndTimers() = runBlocking {
        val alarms = FakeAlarms()
        val engine = makeEngine(FakeCalendar(), alarms)
        val preferences = preferences().withAlarms(true)
        engine.savePreferences(preferences)
        assertFalse(PhoneVoiceTools.tools(preferences, PhoneCalendarAccess.FULL, true).map { it.name }.contains(PhoneVoiceTools.ALARM_TOOL))
        val tool = PhoneVoiceTools.tools(preferences, PhoneCalendarAccess.FULL, true, alarmsAuthorized = true).last()
        assertEquals(PhoneVoiceTools.ALARM_TOOL, tool.name)
        val answer: suspend (PhoneChangePrompt, Boolean) -> Unit = { _, _ -> }

        val timer = PhoneVoiceTools.run(tool, json(mapOf("kind" to "timer", "minutes" to 10, "label" to "Pasta")), engine, answer)
        assertNotNull(timer["error"] ?: "", timer["result"])
        assertEquals(600, alarms.applied.last().fields.seconds)
        val alarm = PhoneVoiceTools.run(tool, json(mapOf("kind" to "alarm", "time" to "6:45", "days" to listOf("sun", " Sat ", "sun"))), engine, answer)
        assertNotNull(alarm["error"] ?: "", alarm["result"])
        assertEquals(PhoneClockTime(6, 45), alarms.applied.last().fields.time)
        assertEquals(listOf("sat", "sun"), alarms.applied.last().fields.repeats)
        assertNotNull(PhoneVoiceTools.run(tool, json(mapOf("kind" to "timer", "minutes" to 0)), engine, answer)["error"])
        assertNotNull("A date and days together is asked about", PhoneVoiceTools.run(tool, json(mapOf("kind" to "alarm", "time" to "07:00", "date" to "2030-01-05", "days" to listOf("mon"))), engine, answer)["error"])
        assertNotNull(PhoneVoiceTools.run(tool, json(mapOf("kind" to "alarm", "time" to "07:00", "days" to listOf("funday"))), engine, answer)["error"])
        assertNotNull(PhoneVoiceTools.run(tool, json(mapOf("kind" to "alarm", "time" to "07:00", "date" to "2030-01-05T10:00")), engine, answer)["error"])
        assertEquals(2, alarms.applied.size)
    }

    // region Helpers

    private fun makeEngine(source: FakeCalendar, alarms: PhoneAlarmSource = FakeAlarms()) = PhoneSyncEngine(
        calendarSource = source,
        statusSource = FakeStatus(),
        alarmSource = alarms,
        store = MemoryTextStore(),
        keys = PhoneKeyStore(MemoryTextStore()),
        preferencesStore = MemoryTextStore(),
        peopleStore = MemoryTextStore(),
        zone = { gmt },
        now = { Instant.ofEpochSecond(1_760_400_000) },
    )

    private fun preferences() = PhoneSyncPreferences(
        destination = SyncDestination("d1", "default", "hermes.local"),
        calendar = "full", reminders = false, status = true, focus = true,
    )

    private fun event(id: String, title: String) = json(mapOf("id" to id, "calendar_id" to "c_1", "title" to title,
        "start" to "2026-10-15T09:00:00Z", "end" to "2026-10-15T10:00:00Z", "all_day" to false))

    private class FakeAlarms : PhoneAlarmSource {
        override val isSupported = true
        override val authorized = true
        var list = mutableListOf<JsonObject>()
        var unreadable = false
        val applied = mutableListOf<PhoneChange>()
        val cancelled = mutableListOf<String>()
        override fun alarms(now: Instant, zone: ZoneId): List<JsonObject>? = if (unreadable) null else list.toList()
        override suspend fun apply(change: PhoneChange, now: Instant): String {
            if (change.kind == PhoneChangeKind.CANCEL_ALARM) {
                val id = change.alarmID.orEmpty()
                cancel(id)
                return id
            }
            applied += change
            val id = "alarm_${applied.size}"
            list += json(mapOf("id" to id, "kind" to if (change.kind == PhoneChangeKind.SET_TIMER) "timer" else "alarm"))
            return id
        }
        override fun cancel(id: String) {
            cancelled += id
            list.removeAll { it["id"].str == id }
        }
        private fun json(value: Any?) = jsonOf(value) as JsonObject
    }

    private class FakeCalendar : PhoneCalendarSource {
        var events: List<JsonObject> = emptyList()
        val applied = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        @Volatile var holding = false
        override val eventAccess = PhoneCalendarAccess.FULL
        override val remindersAuthorized = false
        override fun calendars(eventsReadable: Boolean, reminders: Boolean) =
            listOf(jsonOf(mapOf("id" to "c_1", "title" to "Personal", "kind" to "event", "writable" to true, "shared" to false, "default" to true)) as JsonObject)
        override suspend fun events(from: Instant, to: Instant, hidden: Set<String>, details: Boolean, emails: Boolean, limit: Int) = events
        override suspend fun reminders(hidden: Set<String>, details: Boolean, completedSince: Instant, limit: Int) = emptyList<JsonObject>()
        override suspend fun apply(change: PhoneChange, window: Pair<Instant, Instant>): String? {
            hold?.let { holding = true; it.await(); holding = false; hold = null }
            applied += change.id
            return "item_${change.id}"
        }
        override suspend fun removeAdded(itemID: String, isEvent: Boolean, window: Pair<Instant, Instant>) {}
        override fun calendarTitle(id: String?, forEvents: Boolean) = "Personal"
        override fun platformCalendarID(forShared: String) = forShared
    }

    private class FakeStatus : PhoneStatusSource {
        override suspend fun snapshot() = jsonOf(mapOf("at" to "2026-10-14T00:00:00Z", "battery" to 0.5, "charging" to false, "carplay" to false, "activity" to "stationary")) as JsonObject
        override fun focus() = PhoneFocusSnapshot(true, "Work", true, Instant.ofEpochSecond(1_760_000_000))
    }

    /** The notifier's phone routes, enough to agree keys, open uploads and answer exchanges. */
    private class FakePhoneHost {
        private val hostKey = SealCrypto.newKeyPair()
        private var keys: PhoneSessionKey? = null
        var keyRequests = 0
        val batches = mutableListOf<JsonObject>()
        val reported = mutableListOf<JsonObject>()
        var changes: List<JsonObject> = emptyList()
        var ignoreResults = false

        fun forgetKey() { keys = null }

        private fun seal(payload: JsonObject, op: String, deviceID: String, callID: String, key: PhoneSessionKey): JsonObject {
            val sealed = SealCrypto.seal(payload.toString().toByteArray(), key.answerBytes, PhoneSyncCrypto.exchangeAAD("answer", op, key.kid, deviceID, callID))
            return buildJsonObject { put("ok", true); put("v", 1); put("nonce", sealed.nonce); put("ct", sealed.ct) }
        }

        suspend fun handle(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
            val base = PhoneSyncEngine.BASE
            val o = body?.obj
            if (path == "$base/status") return buildJsonObject {
                put("generation", "g1")
                keys?.let { k -> put("device", buildJsonObject { put("kid", k.kid) }) }
            }
            if (path == "$base/key") {
                keyRequests += 1
                val deviceID = o!!["device_id"].str!!
                val devicePublic = SealCrypto.base64UrlDecoded(o["public_key"].str!!)!!
                val kid = PhoneSyncCrypto.keyID(devicePublic, hostKey.publicKey)
                val shared = SealCrypto.sharedSecret(hostKey.privateKey, devicePublic)
                fun derive(purpose: String) = SealCrypto.base64Url(SealCrypto.hkdf(shared, PhoneSyncCrypto.SALT.toByteArray(),
                    purpose.toByteArray() + "\n$deviceID\n".toByteArray() + devicePublic + hostKey.publicKey))
                keys = PhoneSessionKey(kid, derive(PhoneSyncCrypto.INGEST_INFO), derive(PhoneSyncCrypto.EXCHANGE_REQUEST_INFO), derive(PhoneSyncCrypto.EXCHANGE_ANSWER_INFO))
                return buildJsonObject { put("host_public_key", SealCrypto.base64Url(hostKey.publicKey)); put("kid", kid); put("generation", "g1") }
            }
            val k = keys
            if (k == null || o!!["kid"].str != k.kid) throw DashboardHttpException(409, "unknown_key")
            val deviceID = o["device_id"].str!!
            when {
                path == "$base/ingest" -> {
                    val plaintext = SealCrypto.open(o, k.ingestBytes, PhoneSyncCrypto.ingestAAD(k.kid, deviceID, o["batch_id"].str!!))
                    batches += ConduitJson.parseToJsonElement(plaintext.decodeToString()).obj!!
                    return buildJsonObject { put("ok", true); put("pending", changes.size) }
                }
                path == "$base/exchange" -> {
                    val op = o["op"].str!!
                    val callID = o["call_id"].str!!
                    val request = ConduitJson.parseToJsonElement(SealCrypto.open(o, k.requestBytes, PhoneSyncCrypto.exchangeAAD("request", op, k.kid, deviceID, callID)).decodeToString()).obj!!
                    if (!ignoreResults) reported += request["results"].arr.orEmpty().mapNotNull { it.obj }
                    val answered = reported.mapNotNull { it["id"].str }.toSet()
                    val waiting = changes.filter { it["id"].str !in answered }
                    return seal(buildJsonObject { put("call_id", callID); put("changes", JsonArray(waiting)) }, op, deviceID, callID, k)
                }
                path.endsWith("/voice-tools/call") -> {
                    val callID = o["call_id"].str!!
                    assertEquals("voice", o["op"].str)
                    val request = ConduitJson.parseToJsonElement(SealCrypto.open(o, k.requestBytes, PhoneSyncCrypto.exchangeAAD("request", "voice", k.kid, deviceID, callID)).decodeToString()).obj!!
                    val result = buildJsonObject { put("ok", true); put("tool", request["tool"] ?: JsonPrimitive("")); put("args", request["args"] ?: JsonObject(emptyMap())) }
                    return seal(buildJsonObject { put("call_id", callID); put("result", result) }, "voice", deviceID, callID, k)
                }
            }
            throw DashboardHttpException(404, path)
        }
    }
    // endregion
}
