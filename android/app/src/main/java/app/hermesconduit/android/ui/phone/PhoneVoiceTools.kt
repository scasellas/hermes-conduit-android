package app.hermesconduit.android.ui.phone

import app.hermesconduit.android.model.CanonicalJson
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PhoneCalendarAccess
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangeFields
import app.hermesconduit.android.model.PhoneChangePrompt
import app.hermesconduit.android.model.PhoneChangeResult
import app.hermesconduit.android.model.PhoneChangeWording
import app.hermesconduit.android.model.PhoneDates
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.VoiceHostTool
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.str
import app.hermesconduit.android.model.strictBool
import app.hermesconduit.android.ui.device.SyncSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's own tools for a live voice call (iOS PhoneVoiceTools):
 * answering the calendar changes Hermes asked for out loud, adding an event
 * the user asks for, and setting an alarm or timer, applied here straight
 * away. Descriptions and answers are for the model, not UI copy.
 */
object PhoneVoiceTools {
    const val CHANGES_TOOL = "phone_changes"
    const val ADD_TOOL = "phone_add"
    const val ALARM_TOOL = "phone_alarm"
    /** What seals them: nothing, they never leave the phone. */
    const val KEY = "local"

    fun tools(preferences: PhoneSyncPreferences, access: PhoneCalendarAccess, remindersAuthorized: Boolean, alarmsAuthorized: Boolean = false): List<VoiceHostTool> {
        if (!preferences.isOn) return emptyList()
        val tools = mutableListOf(changes)
        val (events, reminders) = addable(preferences, access, remindersAuthorized)
        if (events || reminders) tools += add(events, reminders, access == PhoneCalendarAccess.FULL)
        if (preferences.alarms && alarmsAuthorized) tools += alarm
        return tools
    }

    /** For a call whose mode the user hasn't let use what they share: where that's turned on. */
    fun settingHint(preferences: PhoneSyncPreferences, mode: LiveVoiceMode): String {
        if (!preferences.isOn || preferences.letsVoiceUse(mode)) return ""
        if (!(preferences.calendarAccess != PhoneCalendarAccess.OFF || preferences.reminders || preferences.alarms || preferences.contacts)) return ""
        val shared = listOfNotNull(
            "their calendar".takeIf { preferences.calendarAccess != PhoneCalendarAccess.OFF },
            "their reminders".takeIf { preferences.reminders },
            "their alarms".takeIf { preferences.alarms },
            "the people they picked".takeIf { preferences.contacts },
        )
        val last = shared.lastOrNull() ?: return ""
        val list = if (shared.size > 1) shared.dropLast(1).joinToString(", ") + " and " + last else last
        val (them, those) = if (shared.size > 1 || preferences.contacts) "these" to "those" else "it" to "that"
        return "\n\nThe user shares $list with Hermes, but hasn't let this call use $them directly. " +
            "When they ask about $those, you can still ask Hermes. The first time in this call, also tell them in " +
            "a sentence that they can let ${mode.displayName} use $them directly, which is faster, under Live " +
            "voice calls in Conduit's Settings, Calendar & phone."
    }

    private fun addable(preferences: PhoneSyncPreferences, access: PhoneCalendarAccess, remindersAuthorized: Boolean): Pair<Boolean, Boolean> =
        (preferences.calendarAccess != PhoneCalendarAccess.OFF && access != PhoneCalendarAccess.OFF) to (preferences.reminders && remindersAuthorized)

    private fun schema(properties: Map<String, Any?>, required: List<String>) = jsonOf(
        mapOf("type" to "object", "properties" to properties, "required" to required),
    ) as JsonObject

    private val changes = VoiceHostTool(
        CHANGES_TOOL,
        "Calendar and reminder changes Hermes asked for that wait on the user's answer on this iPhone. " +
            "list: what's waiting. approve or dismiss: answer one by its id from list, only after the user said " +
            "so out loud in this call. Approving applies it on the phone at once. Tell the user what it is in " +
            "a sentence (what, when, which calendar, and why if Hermes said) before asking. List once early " +
            "in the call: some may be waiting from before it.",
        schema(
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("list", "approve", "dismiss")),
                "id" to mapOf("type" to "string", "description" to "approve, dismiss: the change's id from list."),
            ),
            listOf("action"),
        ),
        KEY,
    )

    private fun add(events: Boolean, reminders: Boolean, calendarsVisible: Boolean): VoiceHostTool {
        val kinds = listOfNotNull("event".takeIf { events }, "reminder".takeIf { reminders })
        val addOnly = if (events && !calendarsVisible) " This iPhone lets Conduit add events but not see its calendars: leave the calendar out for events." else ""
        return VoiceHostTool(
            ADD_TOOL,
            "Adds ${kinds.joinToString(" or ") { if (it == "event") "an event" else "a reminder" }} to the user's iPhone at once. " +
                "Only when the user asked for it in this call: say back what you'll add (title, day and time, " +
                "and the calendar or list if named) and add it once they agree, then confirm in a few words. To change or delete " +
                "something, send the request to Hermes instead." + addOnly,
            schema(
                mapOf(
                    "kind" to mapOf("type" to "string", "enum" to kinds),
                    "title" to mapOf("type" to "string"),
                    "start" to mapOf("type" to "string", "description" to "event: the local start, like 2026-10-16T15:00, or a date (2026-10-16) for all day."),
                    "end" to mapOf("type" to "string", "description" to "event: the local end; default an hour after the start."),
                    "all_day" to mapOf("type" to "boolean", "description" to "event: all day."),
                    "due" to mapOf("type" to "string", "description" to "reminder: when it's due, a local time like 2026-10-16T09:00 or a date. Optional."),
                    "calendar" to mapOf("type" to "string", "description" to "The calendar or reminder list by name; the user's default when left out." +
                        if (addOnly.isEmpty()) "" else " Leave it out for events."),
                    "location" to mapOf("type" to "string", "description" to "event: where."),
                    "notes" to mapOf("type" to "string"),
                ),
                listOf("kind", "title"),
            ),
            KEY,
        )
    }

    private val alarm = VoiceHostTool(
        ALARM_TOOL,
        "Sets an alarm or starts a timer on the user's iPhone at once; it rings even in Silent mode. " +
            "Only when the user asked for it in this call: say back what you'll set (the time and days, or how long, " +
            "and its name if any) and set it once they agree, then confirm in a few words. To cancel one, send " +
            "the request to Hermes instead.",
        schema(
            mapOf(
                "kind" to mapOf("type" to "string", "enum" to listOf("alarm", "timer")),
                "time" to mapOf("type" to "string", "description" to "alarm: the local time on a 24-hour clock, like 06:30."),
                "date" to mapOf("type" to "string", "description" to "alarm: the day for a one-off alarm, like 2026-10-16; the next 'time' when left out."),
                "days" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to PhoneChangeFields.WEEKDAYS),
                    "description" to "alarm: the weekdays it repeats on; leave out for once."),
                "minutes" to mapOf("type" to "number", "description" to "timer: how long, in minutes (fractions allowed)."),
                "label" to mapOf("type" to "string", "description" to "A short name, like Pasta or Wake up."),
            ),
            listOf("kind"),
        ),
        KEY,
    )

    /** One call of a local tool. What the model hears back. */
    suspend fun run(tool: VoiceHostTool, arguments: JsonObject, engine: PhoneSyncEngine, answer: suspend (PhoneChangePrompt, Boolean) -> Unit): Map<String, String> =
        when (tool.name) {
            CHANGES_TOOL -> runChanges(arguments, engine, answer)
            ADD_TOOL -> runAdd(arguments, engine)
            ALARM_TOOL -> runAlarm(arguments, engine)
            else -> mapOf("error" to "That isn't available on this call.")
        }

    private suspend fun runChanges(arguments: JsonObject, engine: PhoneSyncEngine, answer: suspend (PhoneChangePrompt, Boolean) -> Unit): Map<String, String> {
        return when (val action = arguments["action"].str.orEmpty()) {
            "list" -> {
                val waiting = engine.prompts.value.map(::describe)
                VoiceHostTool.modelResult(
                    if (waiting.isEmpty()) buildJsonObject { put("changes", JsonArray(emptyList())); put("note", "Nothing is waiting.") }
                    else buildJsonObject { put("changes", JsonArray(waiting)) },
                )
            }
            "approve", "dismiss" -> {
                val id = arguments["id"].str
                val prompt = engine.prompts.value.firstOrNull { it.id == id } ?: return mapOf("error" to "No change with that id is waiting. List them again.")
                answer(prompt, action == "approve")
                if (action == "dismiss") return mapOf("result" to "Dismissed. Hermes will hear the user said no.")
                val result = engine.answered(prompt.id) ?: return mapOf("result" to "It's still being applied; Hermes will hear how it went.")
                if (result.outcome == PhoneChangeResult.FAILED) mapOf("error" to "It couldn't be applied: ${result.note ?: "the phone refused it"}.")
                else mapOf("result" to "Done on the phone; Hermes will hear it went in.")
            }
            else -> mapOf("error" to "action is list, approve or dismiss.")
        }
    }

    private suspend fun runAdd(arguments: JsonObject, engine: PhoneSyncEngine): Map<String, String> {
        val source = engine.calendarSource
        val (events, reminders) = addable(engine.preferences.value, source.eventAccess, source.remindersAuthorized)
        val title = arguments["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return mapOf("error" to "An event or reminder needs a title.")
        val isEvent = when (arguments["kind"].str.orEmpty()) {
            "event" -> if (events) true else return mapOf("error" to "The user doesn't let Hermes add those. They can turn it on in Conduit's Settings.")
            "reminder" -> if (reminders) false else return mapOf("error" to "The user doesn't let Hermes add those. They can turn it on in Conduit's Settings.")
            else -> return mapOf("error" to "kind is event or reminder.")
        }
        val fields = linkedMapOf<String, Any?>("title" to title)
        arguments["notes"].str?.let { fields["notes"] = it }
        val calendarName = arguments["calendar"].str?.trim()
        if (isEvent && !calendarName.isNullOrEmpty() && source.eventAccess != PhoneCalendarAccess.FULL) {
            return mapOf("error" to "This iPhone lets Conduit add events but not see its calendars. Leave the calendar out to use the default.")
        }
        val calendarID = calendarName?.takeIf { it.isNotEmpty() }?.let { name ->
            source.calendars(source.eventAccess == PhoneCalendarAccess.FULL, source.remindersAuthorized).firstOrNull { entry ->
                entry["kind"].str == (if (isEvent) "event" else "reminder") && entry["title"].str?.equals(name, ignoreCase = true) == true
            }?.get("id").str
        }
        if (!calendarName.isNullOrEmpty() && calendarID == null) {
            return mapOf("error" to "There's no ${if (isEvent) "calendar" else "list"} called $calendarName on this phone. Ask which one, or leave it out for the default.")
        }
        if (isEvent) {
            val start = arguments["start"].str?.let { PhoneChangeWording.localTime(it, engine.zone()) }
                ?: return mapOf("error" to "An event needs a start, like 2026-10-16T15:00.")
            fields["start"] = start.first
            fields["all_day"] = arguments["all_day"].strictBool ?: start.second
            arguments["end"].str?.let { PhoneChangeWording.localTime(it, engine.zone()) }?.let { fields["end"] = it.first }
            arguments["location"].str?.let { fields["location"] = it }
            calendarID?.let { fields["calendar_id"] = it }
        } else {
            arguments["due"].str?.let { PhoneChangeWording.localTime(it, engine.zone()) }?.let {
                fields["due"] = it.first
                fields["due_all_day"] = it.second
            }
            calendarID?.let { fields["list_id"] = it }
        }
        val id = "voice_" + SealCrypto.compactUuid()
        val change = PhoneChange.parse(jsonOf(mapOf("id" to id, "kind" to if (isEvent) "add_event" else "add_reminder", "fields" to fields)) as JsonObject)
            ?: return mapOf("error" to "That couldn't be added; check the title and time.")
        return try {
            engine.addFromVoice(change)
            mapOf("result" to "Added on the phone. Confirm it to the user in a few words.")
        } catch (e: Exception) {
            mapOf("error" to "It couldn't be added: ${SyncSupport.describe(e)}")
        }
    }

    private suspend fun runAlarm(arguments: JsonObject, engine: PhoneSyncEngine): Map<String, String> {
        if (!engine.preferences.value.alarms || !engine.alarmSource.authorized) {
            return mapOf("error" to "The user doesn't let Hermes set alarms. They can turn it on in Conduit's Settings.")
        }
        val fields = linkedMapOf<String, Any?>()
        arguments["label"].str?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["label"] = it }
        val kind: String
        when (arguments["kind"].str.orEmpty()) {
            "timer" -> {
                val minutes = arguments["minutes"].double?.takeIf { it > 0 } ?: return mapOf("error" to "A timer needs minutes, like 10.")
                val seconds = app.hermesconduit.android.model.roundHalfAway(minutes * 60).toInt()
                if (seconds !in 5..86_400) return mapOf("error" to "A timer runs from 5 seconds to 24 hours.")
                fields["seconds"] = seconds
                kind = "set_timer"
            }
            "alarm" -> {
                val timeText = arguments["time"].str?.trim()
                val time = timeText?.let { PhoneChangeFields.clockTime(if (it.length == 4) "0$it" else it) }
                    ?: return mapOf("error" to "An alarm needs a time like 06:30.")
                val named = arguments["days"].arr?.mapNotNull { it.str?.trim()?.lowercase() }.orEmpty()
                if (!named.all { it in PhoneChangeFields.WEEKDAYS }) return mapOf("error" to "days are mon, tue, wed, thu, fri, sat or sun.")
                val days = PhoneChangeFields.WEEKDAYS.filter { it in named }
                val dateText = arguments["date"].str?.trim().orEmpty()
                if (dateText.isNotEmpty() && days.isNotEmpty()) {
                    return mapOf("error" to "An alarm takes a date (once) or days (repeating), not both. Ask which the user means.")
                }
                val date = if (dateText.isEmpty()) null else PhoneChangeWording.localTime(dateText, engine.zone())
                if (dateText.isNotEmpty() && date?.second != true) return mapOf("error" to "date is a day, like 2026-10-16; the time goes in time.")
                if (date != null) {
                    val at = PhoneChangeWording.localTime(date.first + "T" + time.text, engine.zone())?.let { PhoneDates.parse(it.first) }
                        ?: return mapOf("error" to "That date and time couldn't be read.")
                    fields["at"] = PhoneDates.format(at, engine.zone())
                } else {
                    fields["time"] = time.text
                    if (days.isNotEmpty()) fields["repeats"] = days
                }
                kind = "set_alarm"
            }
            else -> return mapOf("error" to "kind is alarm or timer.")
        }
        val id = "voice_" + SealCrypto.compactUuid()
        val change = PhoneChange.parse(jsonOf(mapOf("id" to id, "kind" to kind, "fields" to fields)) as JsonObject)
            ?: return mapOf("error" to "That couldn't be set; check the time.")
        return try {
            engine.addFromVoice(change)
            mapOf("result" to "Set on the phone: ${PhoneChangeWording.alarmSummary(change.fields, change.kind, engine.zone())}. Confirm it to the user in a few words.")
        } catch (e: Exception) {
            mapOf("error" to "It couldn't be set: ${SyncSupport.describe(e)}")
        }
    }

    /** A waiting change as the model hears it. */
    fun describe(prompt: PhoneChangePrompt): JsonObject {
        val change = prompt.change
        val f = change.fields
        return buildJsonObject {
            put("id", change.id)
            put("kind", change.kind.raw.replace('_', ' '))
            f.title?.let { put("title", it) }
            f.start?.let { put("start", PhoneDates.format(it)) }
            f.end?.let { put("end", PhoneDates.format(it)) }
            if (f.allDay == true) put("all_day", true)
            f.dueText?.let { put("due", it) }
            prompt.calendarTitle?.let { put("calendar", it) }
            f.location?.let { put("location", it) }
            f.label?.let { put("label", it) }
            f.at?.let { put("at", PhoneDates.format(it)) }
            f.time?.let { put("time", it.text) }
            f.repeats?.let { days -> put("repeats", JsonArray(days.map(::JsonPrimitive))) }
            f.seconds?.let { put("seconds", it) }
            prompt.alarmTitle?.let { put("alarm", it) }
            change.reason?.let { put("reason", it) }
        }
    }

    /** The quiet note a call gets when Hermes asks for a change mid-call. */
    fun note(prompt: PhoneChangePrompt): String =
        "[Hermes is asking for a change on this iPhone: ${CanonicalJson.encode(describe(prompt))}. At the next natural pause, tell the user in a sentence and ask whether to go ahead; then call $CHANGES_TOOL with approve or dismiss and its id. Don't interrupt the user for it.]"
}
