package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reminders on Android (iOS EventKit reminders): the tasks apps that
 * implement the OpenTasks provider contract (`org.dmfs.tasks.contract.
 * TaskContract`), each under its own authority and permissions. jtx Board
 * isn't here: its provider has its own contract and serves sync adapters
 * only, per account.
 */
enum class PhoneTaskApp(val authority: String, val packageName: String, val readPermission: String, val writePermission: String, val title: String) {
    OPEN_TASKS("org.dmfs.tasks", "org.dmfs.tasks", "org.dmfs.permission.READ_TASKS", "org.dmfs.permission.WRITE_TASKS", "OpenTasks"),
    TASKS_ORG("org.tasks.opentasks", "org.tasks", "org.tasks.permission.READ_TASKS", "org.tasks.permission.WRITE_TASKS", "Tasks.org");

    val permissions: List<String> get() = listOf(readPermission, writePermission)

    companion object {
        fun of(authority: String): PhoneTaskApp? = entries.firstOrNull { it.authority == authority }
    }
}

/** The TaskContract names Conduit uses (OpenTasks' contract, without the library). */
object TaskContractNames {
    const val TASKS = "tasks"
    const val TASK_LISTS = "tasklists"

    const val ID = "_id"
    const val LIST_ID = "list_id"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val URL = "url"
    const val PRIORITY = "priority"
    const val STATUS = "status"
    const val COMPLETED = "completed"
    const val COMPLETED_IS_ALLDAY = "completed_is_allday"
    const val PERCENT_COMPLETE = "percent_complete"
    const val IS_CLOSED = "is_closed"
    const val DUE = "due"
    const val IS_ALLDAY = "is_allday"
    const val TZ = "tz"
    const val RRULE = "rrule"
    const val DELETED = "_deleted"
    const val ORIGINAL_INSTANCE_ID = "original_instance_id"

    const val LIST_NAME = "list_name"
    const val LIST_COLOR = "list_color"
    const val ACCOUNT_NAME = "account_name"
    const val VISIBLE = "visible"
    const val SYNC_ENABLED = "sync_enabled"

    const val STATUS_NEEDS_ACTION = 0
    const val STATUS_COMPLETED = 2
}

/** One task list, as the provider holds it. */
data class PhoneTaskList(val app: PhoneTaskApp, val id: Long, val title: String, val color: Int, val account: String, val visible: Boolean) {
    /** What the user's choice of lists stores, apart from Android calendar ids. */
    val platformID: String get() = PhoneTaskRules.listKey(app.authority, id)
    val sharedID: String get() = PhoneTaskRules.listID(app.authority, id)
}

/** One task, as the provider holds it. */
data class PhoneTaskRow(
    val app: PhoneTaskApp,
    val id: Long,
    val listID: Long,
    val title: String,
    val priority: Int?,
    val status: Int?,
    val completedAt: Long?,
    val recurring: Boolean,
    val due: Long?,
    val allDay: Boolean,
    val zone: String?,
    val notes: String?,
    val url: String?,
)

object PhoneTaskRules {
    fun listKey(authority: String, id: Long): String = "tasks:$authority:$id"

    /** A list's shared id: hashed like a calendar's, from what the user's choices store. */
    fun listID(authority: String, id: Long): String = PhoneSyncRules.hashedID("c_", listKey(authority, id))

    /** A reminder's shared id: hashed from its provider and its id there (iOS from the EventKit id). */
    fun reminderID(authority: String, id: Long): String = PhoneSyncRules.hashedID("r_", authority, id.toString())

    fun listJSON(list: PhoneTaskList, isDefault: Boolean): JsonObject = buildJsonObject {
        put("id", list.sharedID)
        put("title", list.title.take(100))
        put("kind", "reminder")
        put("color", "#%06X".format(list.color and 0xFFFFFF))
        put("source", list.account.take(60))
        put("writable", true)
        put("shared", false)
        put("default", isDefault)
    }

    /** The default list for new reminders: the first visible one. */
    fun defaultList(lists: List<PhoneTaskList>): PhoneTaskList? = lists.firstOrNull { it.visible } ?: lists.firstOrNull()

    /** A reminder as iOS sends it (EventKitPhoneSource.json for EKReminder). */
    fun json(task: PhoneTaskRow, details: Boolean, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
        put("id", reminderID(task.app.authority, task.id))
        put("list_id", listID(task.app.authority, task.listID))
        put("title", task.title.take(200))
        put("priority", (task.priority ?: 0).coerceIn(0, 9))
        put("completed", task.status == TaskContractNames.STATUS_COMPLETED)
        put("recurring", task.recurring)
        task.due?.let { due ->
            if (task.allDay) {
                // All-day dates are kept at UTC midnight.
                put("due", PhoneDates.day(Instant.ofEpochMilli(due), ZoneOffset.UTC))
                put("due_all_day", true)
            } else {
                val dueZone = task.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
                put("due", PhoneDates.format(Instant.ofEpochMilli(due), dueZone))
                put("due_all_day", false)
            }
        }
        if (task.status == TaskContractNames.STATUS_COMPLETED) task.completedAt?.let { put("completed_at", PhoneDates.format(Instant.ofEpochMilli(it), zone)) }
        if (details) {
            task.notes?.takeIf { it.isNotEmpty() }?.let { put("notes", it.take(PhoneSyncLimits.NOTES_CHARS)) }
            task.url?.takeIf { it.isNotEmpty() }?.let { put("url", it) }
        }
    }

    /**
     * The columns an addition or a change writes (iOS fill for EKReminder).
     * A date due goes as UTC midnight with no zone; a time with this
     * phone's zone. Values are String, Int, Long or null.
     */
    fun values(fields: PhoneChangeFields, zone: ZoneId = ZoneId.systemDefault()): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        fields.title?.let { values[TaskContractNames.TITLE] = it }
        fields.notes?.let { values[TaskContractNames.DESCRIPTION] = it }
        fields.url?.let { values[TaskContractNames.URL] = it }
        fields.priority?.let { values[TaskContractNames.PRIORITY] = it.coerceIn(0, 9) }
        fields.due?.let { due ->
            val allDay = fields.dueAllDay ?: (fields.dueText?.length == 10)
            if (allDay) {
                values[TaskContractNames.DUE] = due.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                values[TaskContractNames.IS_ALLDAY] = 1
                values[TaskContractNames.TZ] = null
            } else {
                values[TaskContractNames.DUE] = due.toEpochMilli()
                values[TaskContractNames.IS_ALLDAY] = 0
                values[TaskContractNames.TZ] = zone.id
            }
        }
        return values
    }

    /** Marking done (or open again, with `completed: false`). */
    fun completion(done: Boolean, now: Instant): Map<String, Any?> = if (done) {
        linkedMapOf(
            TaskContractNames.STATUS to TaskContractNames.STATUS_COMPLETED,
            TaskContractNames.COMPLETED to now.toEpochMilli(),
            TaskContractNames.COMPLETED_IS_ALLDAY to 0,
            TaskContractNames.PERCENT_COMPLETE to 100,
        )
    } else {
        linkedMapOf(
            TaskContractNames.STATUS to TaskContractNames.STATUS_NEEDS_ACTION,
            TaskContractNames.COMPLETED to null,
            TaskContractNames.PERCENT_COMPLETE to null,
        )
    }

    /** Open tasks, and those done since `completedSince`; repeating tasks' single changed occurrences aren't items of their own. */
    const val READ_SELECTION = "${TaskContractNames.DELETED} = 0 AND ${TaskContractNames.ORIGINAL_INSTANCE_ID} IS NULL AND " +
        "(${TaskContractNames.IS_CLOSED} = 0 OR (${TaskContractNames.STATUS} = ${TaskContractNames.STATUS_COMPLETED} AND ${TaskContractNames.COMPLETED} >= ?))"
}
