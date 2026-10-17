package app.hermesconduit.android

import app.hermesconduit.android.model.HermesFocusFilter
import app.hermesconduit.android.model.HermesFocusRules
import app.hermesconduit.android.model.HermesNativeCallPlan
import app.hermesconduit.android.model.PhoneChangeFields
import app.hermesconduit.android.model.PhoneModeState
import app.hermesconduit.android.model.PhoneSyncRules
import app.hermesconduit.android.model.PhoneTaskApp
import app.hermesconduit.android.model.PhoneTaskList
import app.hermesconduit.android.model.PhoneTaskRow
import app.hermesconduit.android.model.PhoneTaskRules
import app.hermesconduit.android.model.TaskContractNames
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Do Not Disturb & Modes filters (iOS HermesFocusFilter) and reminders from tasks apps (iOS EventKit reminders). */
class FocusAndRemindersTest {
    private val at = Instant.ofEpochSecond(1_760_000_000)
    private val berlin = ZoneId.of("Europe/Berlin")

    // region Focus filters

    private val off = PhoneModeState(doNotDisturb = false)
    private val dnd = PhoneModeState(doNotDisturb = true)
    private val conduitMode = PhoneModeState(doNotDisturb = true, activeModes = setOf(HermesFocusFilter.CONDUIT_MODE))

    @Test fun withoutFiltersDoNotDisturbStandsInForFocus() {
        val on = HermesFocusRules.snapshot(emptyList(), dnd, at)
        assertTrue(on.active)
        assertEquals("Do Not Disturb", on.label)
        assertFalse(on.holdCalls)
        val quiet = HermesFocusRules.snapshot(emptyList(), off, at)
        assertFalse(quiet.active)
        assertNull(quiet.label)
    }

    @Test fun aFilterSaysWhatIOSFilterSays() {
        val work = HermesFocusFilter("a", HermesFocusFilter.ANY, "  Work  ", holdCalls = true)
        val snapshot = HermesFocusRules.snapshot(listOf(work), dnd, at)
        assertTrue(snapshot.active)
        assertEquals("Work", snapshot.label)
        assertTrue(snapshot.holdCalls)
        // The wire is iOS's PhoneFocusSnapshot.json.
        assertEquals(setOf("at", "active", "hold_calls", "label"), snapshot.json.keys)
        assertEquals(true, snapshot.json["hold_calls"].bool)
        assertEquals("Work", snapshot.json["label"].str)
    }

    @Test fun noFilterOnSendsTheDefaults() {
        val work = HermesFocusFilter("a", HermesFocusFilter.ANY, "Work", holdCalls = true)
        val snapshot = HermesFocusRules.snapshot(listOf(work), off, at)
        assertFalse(snapshot.active)
        assertNull(snapshot.label)
        assertFalse(snapshot.holdCalls)
        assertFalse(HermesFocusRules.holdsCalls(listOf(work), off))
    }

    @Test fun anUnnamedFilterIsActiveOnlyWhenItHoldsCalls() {
        val nameless = HermesFocusFilter("a", HermesFocusFilter.ANY, "", holdCalls = false)
        assertFalse(HermesFocusRules.snapshot(listOf(nameless), dnd, at).active)
        val holding = nameless.copy(holdCalls = true)
        val snapshot = HermesFocusRules.snapshot(listOf(holding), dnd, at)
        assertTrue(snapshot.active)
        assertNull(snapshot.label)
        assertFalse("label" in snapshot.json)
    }

    @Test fun aModesFilterComesBeforeAnyDoNotDisturb() {
        val any = HermesFocusFilter("a", HermesFocusFilter.ANY, "Busy")
        val mode = HermesFocusFilter("b", HermesFocusFilter.CONDUIT_MODE, "Deep work", holdCalls = true)
        assertEquals(mode, HermesFocusRules.applying(listOf(any, mode), conduitMode))
        assertEquals(any, HermesFocusRules.applying(listOf(any, mode), dnd))
        assertNull(HermesFocusRules.applying(listOf(mode), dnd))
        assertTrue(HermesFocusRules.holdsCalls(listOf(any, mode), conduitMode))
        assertFalse(HermesFocusRules.holdsCalls(listOf(any, mode), dnd))
    }

    @Test fun namesKeepToFortyCharacters() {
        val long = HermesFocusFilter("a", label = "x".repeat(60))
        assertEquals(40, HermesFocusRules.snapshot(listOf(long), dnd, at).label?.length)
    }

    @Test fun filtersRoundTrip() {
        val list = listOf(HermesFocusFilter("a", HermesFocusFilter.ANY, "Work", true), HermesFocusFilter("b", HermesFocusFilter.CONDUIT_MODE))
        assertEquals(list, HermesFocusFilter.decodeAll(HermesFocusFilter.encodeAll(list)))
        assertEquals(emptyList<HermesFocusFilter>(), HermesFocusFilter.decodeAll("not json"))
    }

    @Test fun aHeldCallDoesNotRingAndIsNotMissed() {
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.TALK),
            HermesNativeCallPlan.plan(1_000, 2_000, voiceInUse = false, replayed = false, ringingWanted = true, held = true))
        // Late still reads as missed; replayed still posts nothing.
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.MISSED), HermesNativeCallPlan.plan(0, 91_000, false, false, true, held = true))
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.NONE), HermesNativeCallPlan.plan(null, 0, false, replayed = true, ringingWanted = true, held = true))
        assertEquals(HermesNativeCallPlan.Ring, HermesNativeCallPlan.plan(1_000, 2_000, false, false, true, held = false))
    }

    // endregion

    // region Reminders

    private fun task(
        status: Int? = TaskContractNames.STATUS_NEEDS_ACTION, due: Long? = null, allDay: Boolean = false, zone: String? = null,
        completedAt: Long? = null, priority: Int? = 1, notes: String? = "Bring the receipt", url: String? = "https://example.com",
    ) = PhoneTaskRow(PhoneTaskApp.OPEN_TASKS, 42, 7, "Return parcel", priority, status, completedAt, recurring = false, due = due, allDay = allDay,
        zone = zone, notes = notes, url = url)

    @Test fun idsAreHashedAndStable() {
        val id = PhoneTaskRules.reminderID("org.dmfs.tasks", 42)
        assertTrue(id.startsWith("r_"))
        assertEquals(24, id.length)
        assertEquals(id, PhoneTaskRules.reminderID("org.dmfs.tasks", 42))
        assertTrue(id != PhoneTaskRules.reminderID("org.tasks.opentasks", 42))
        // A list's id is hashed from what the user's choice of lists stores, as the engine filters it.
        val list = PhoneTaskList(PhoneTaskApp.TASKS_ORG, 3, "Errands", 0xFF3366, "Local", true)
        assertEquals(PhoneSyncRules.hashedID("c_", list.platformID), list.sharedID)
        assertEquals("tasks:org.tasks.opentasks:3", list.platformID)
    }

    @Test fun aTimedReminderGoesAsIOSSendsIt() {
        val due = Instant.parse("2026-10-18T07:30:00Z")
        val json = PhoneTaskRules.json(task(due = due.toEpochMilli(), zone = "Europe/Berlin"), details = false, zone = berlin)
        assertEquals(PhoneTaskRules.reminderID("org.dmfs.tasks", 42), json["id"].str)
        assertEquals(PhoneTaskRules.listID("org.dmfs.tasks", 7), json["list_id"].str)
        assertEquals("Return parcel", json["title"].str)
        assertEquals(1L, json["priority"].long)
        assertEquals(false, json["completed"].bool)
        assertEquals(false, json["recurring"].bool)
        assertEquals("2026-10-18T09:30:00+02:00", json["due"].str)
        assertEquals(false, json["due_all_day"].bool)
        assertFalse("notes" in json)
        assertFalse("url" in json)
        assertFalse("completed_at" in json)
    }

    @Test fun anAllDayReminderGoesAsADate() {
        val due = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()
        val json = PhoneTaskRules.json(task(due = due, allDay = true), details = true, zone = ZoneId.of("America/Los_Angeles"))
        assertEquals("2026-10-18", json["due"].str)
        assertEquals(true, json["due_all_day"].bool)
        assertEquals("Bring the receipt", json["notes"].str)
        assertEquals("https://example.com", json["url"].str)
    }

    @Test fun aDoneReminderSaysWhen() {
        val done = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
        val json = PhoneTaskRules.json(task(status = TaskContractNames.STATUS_COMPLETED, completedAt = done, priority = 12), details = false, zone = berlin)
        assertEquals(true, json["completed"].bool)
        assertEquals("2026-10-17T14:00:00+02:00", json["completed_at"].str)
        assertEquals(9L, json["priority"].long)
        val list = PhoneTaskRules.listJSON(PhoneTaskList(PhoneTaskApp.OPEN_TASKS, 7, "Errands", 0x12FF3366, "me@example.com", true), isDefault = true)
        assertEquals(setOf("id", "title", "kind", "color", "source", "writable", "shared", "default"), list.keys)
        assertEquals("reminder", list["kind"].str)
        assertEquals("#FF3366", list["color"].str)
    }

    @Test fun changesWriteTheColumnsIOSFills() {
        val timed = PhoneChangeFields.parse(buildJsonObject {
            put("title", "Call the vet"); put("notes", "About Rex"); put("priority", 15); put("due", "2026-10-18T09:00:00+02:00")
        })
        val values = PhoneTaskRules.values(timed, berlin)
        assertEquals("Call the vet", values[TaskContractNames.TITLE])
        assertEquals("About Rex", values[TaskContractNames.DESCRIPTION])
        assertEquals(9, values[TaskContractNames.PRIORITY])
        assertEquals(Instant.parse("2026-10-18T07:00:00Z").toEpochMilli(), values[TaskContractNames.DUE])
        assertEquals(0, values[TaskContractNames.IS_ALLDAY])
        assertEquals("Europe/Berlin", values[TaskContractNames.TZ])

        val dated = PhoneTaskRules.values(PhoneChangeFields.parse(buildJsonObject { put("due", "2026-10-18") }), berlin)
        assertEquals(Instant.parse("2026-10-18T00:00:00Z").toEpochMilli(), dated[TaskContractNames.DUE])
        assertEquals(1, dated[TaskContractNames.IS_ALLDAY])
        assertTrue(TaskContractNames.TZ in dated && dated[TaskContractNames.TZ] == null)

        // Nothing given, nothing written.
        assertEquals(emptyMap<String, Any?>(), PhoneTaskRules.values(PhoneChangeFields.parse(buildJsonObject { }), berlin))
    }

    @Test fun completingAndReopening() {
        val now = Instant.ofEpochMilli(1_000)
        val done = PhoneTaskRules.completion(true, now)
        assertEquals(TaskContractNames.STATUS_COMPLETED, done[TaskContractNames.STATUS])
        assertEquals(1_000L, done[TaskContractNames.COMPLETED])
        val open = PhoneTaskRules.completion(false, now)
        assertEquals(TaskContractNames.STATUS_NEEDS_ACTION, open[TaskContractNames.STATUS])
        assertTrue(TaskContractNames.COMPLETED in open && open[TaskContractNames.COMPLETED] == null)
    }

    @Test fun theDefaultListIsTheFirstVisible() {
        val hidden = PhoneTaskList(PhoneTaskApp.OPEN_TASKS, 1, "Old", 0, "", visible = false)
        val shown = PhoneTaskList(PhoneTaskApp.OPEN_TASKS, 2, "Inbox", 0, "", visible = true)
        assertEquals(shown, PhoneTaskRules.defaultList(listOf(hidden, shown)))
        assertEquals(hidden, PhoneTaskRules.defaultList(listOf(hidden)))
        assertNull(PhoneTaskRules.defaultList(emptyList()))
    }

    @Test fun tasksAppsByAuthority() {
        assertEquals(PhoneTaskApp.OPEN_TASKS, PhoneTaskApp.of("org.dmfs.tasks"))
        assertEquals(PhoneTaskApp.TASKS_ORG, PhoneTaskApp.of("org.tasks.opentasks"))
        assertNull(PhoneTaskApp.of("at.techbee.jtx.provider"))
        assertEquals(listOf("org.dmfs.permission.READ_TASKS", "org.dmfs.permission.WRITE_TASKS"), PhoneTaskApp.OPEN_TASKS.permissions)
    }

    // endregion
}
