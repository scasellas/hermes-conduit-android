package app.hermesconduit.android.ui.phone

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangeKind
import app.hermesconduit.android.model.PhoneTaskApp
import app.hermesconduit.android.model.PhoneTaskList
import app.hermesconduit.android.model.PhoneTaskRow
import app.hermesconduit.android.model.PhoneTaskRules
import app.hermesconduit.android.model.TaskContractNames
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Reminders through the tasks apps that implement the OpenTasks provider
 * contract (OpenTasks, Tasks.org), standing in for iOS's EventKit
 * reminders. Each installed app the user allowed is read; ids are hashed
 * from the provider and its own id, so the host never sees those.
 */
class TaskProviderReminders(private val context: Context) {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The tasks apps on this phone. */
    val installed: List<PhoneTaskApp>
        get() = PhoneTaskApp.entries.filter { app ->
            runCatching { context.packageManager.resolveContentProvider(app.authority, 0) != null }.getOrDefault(false)
        }

    /** The installed ones Conduit may read and write. */
    val allowed: List<PhoneTaskApp> get() = installed.filter { app -> app.permissions.all(::granted) }

    val authorized: Boolean get() = allowed.isNotEmpty()

    /** What to ask for so every installed tasks app can be used. */
    val missingPermissions: List<String> get() = installed.flatMap { it.permissions }.filterNot(::granted)

    fun tasksUri(app: PhoneTaskApp): Uri = "content://${app.authority}/${TaskContractNames.TASKS}".toUri()
    private fun listsUri(app: PhoneTaskApp): Uri = "content://${app.authority}/${TaskContractNames.TASK_LISTS}".toUri()

    /** Shared reminder id to where it lives, from the last read. */
    private val known = mutableMapOf<String, Pair<PhoneTaskApp, Long>>()
    private val lock = Any()

    fun lists(apps: List<PhoneTaskApp> = allowed): List<PhoneTaskList> = apps.flatMap { app ->
        val rows = mutableListOf<PhoneTaskList>()
        runCatching {
            context.contentResolver.query(
                listsUri(app),
                arrayOf(TaskContractNames.ID, TaskContractNames.LIST_NAME, TaskContractNames.LIST_COLOR, TaskContractNames.ACCOUNT_NAME,
                    TaskContractNames.VISIBLE, TaskContractNames.SYNC_ENABLED),
                null, null, TaskContractNames.ID,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    // Lists the user doesn't sync are hidden in the tasks app too.
                    if (cursor.int(TaskContractNames.SYNC_ENABLED) == 0) continue
                    rows += PhoneTaskList(
                        app = app,
                        id = cursor.long(TaskContractNames.ID) ?: continue,
                        title = cursor.string(TaskContractNames.LIST_NAME).orEmpty(),
                        color = cursor.int(TaskContractNames.LIST_COLOR) ?: 0x888888,
                        account = cursor.string(TaskContractNames.ACCOUNT_NAME).orEmpty(),
                        visible = cursor.int(TaskContractNames.VISIBLE) != 0,
                    )
                }
            }
        }
        rows
    }

    fun listJSON(limit: Int): List<JsonObject> {
        if (limit <= 0) return emptyList()
        val lists = lists()
        val defaultList = PhoneTaskRules.defaultList(lists)
        return lists.take(limit).map { PhoneTaskRules.listJSON(it, it == defaultList) }
    }

    fun list(sharedID: String?): PhoneTaskList? = sharedID?.let { id -> lists().firstOrNull { it.sharedID == id } }

    fun listTitle(sharedID: String?): String? = (list(sharedID) ?: PhoneTaskRules.defaultList(lists()))?.title

    fun platformListID(sharedID: String): String? = lists().firstOrNull { it.sharedID == sharedID }?.platformID

    fun reminders(hidden: Set<String>, details: Boolean, completedSince: Instant, limit: Int): List<JsonObject> {
        val shown = lists().filter { it.platformID !in hidden }
        if (shown.isEmpty()) return emptyList()
        val found = mutableListOf<PhoneTaskRow>()
        for (app in shown.map { it.app }.distinct()) {
            val listIDs = shown.filter { it.app == app }.map { it.id }.toSet()
            found += tasks(app, PhoneTaskRules.READ_SELECTION, arrayOf(completedSince.toEpochMilli().toString())).filter { it.listID in listIDs }
        }
        synchronized(lock) {
            known.clear()
            found.forEach { known[PhoneTaskRules.reminderID(it.app.authority, it.id)] = it.app to it.id }
        }
        return found.take(limit).map { PhoneTaskRules.json(it, details) }
    }

    private fun tasks(app: PhoneTaskApp, selection: String?, args: Array<String>?): List<PhoneTaskRow> {
        val rows = mutableListOf<PhoneTaskRow>()
        runCatching {
            context.contentResolver.query(
                tasksUri(app),
                arrayOf(TaskContractNames.ID, TaskContractNames.LIST_ID, TaskContractNames.TITLE, TaskContractNames.PRIORITY, TaskContractNames.STATUS,
                    TaskContractNames.COMPLETED, TaskContractNames.RRULE, TaskContractNames.DUE, TaskContractNames.IS_ALLDAY, TaskContractNames.TZ,
                    TaskContractNames.DESCRIPTION, TaskContractNames.URL),
                selection, args, "${TaskContractNames.IS_CLOSED} ASC, ${TaskContractNames.ID} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows += PhoneTaskRow(
                        app = app,
                        id = cursor.long(TaskContractNames.ID) ?: continue,
                        listID = cursor.long(TaskContractNames.LIST_ID) ?: continue,
                        title = cursor.string(TaskContractNames.TITLE).orEmpty(),
                        priority = cursor.int(TaskContractNames.PRIORITY),
                        status = cursor.int(TaskContractNames.STATUS),
                        completedAt = cursor.long(TaskContractNames.COMPLETED),
                        recurring = !cursor.string(TaskContractNames.RRULE).isNullOrEmpty(),
                        due = cursor.long(TaskContractNames.DUE),
                        allDay = cursor.int(TaskContractNames.IS_ALLDAY) == 1,
                        zone = cursor.string(TaskContractNames.TZ),
                        notes = cursor.string(TaskContractNames.DESCRIPTION),
                        url = cursor.string(TaskContractNames.URL),
                    )
                }
            }
        }
        return rows
    }

    private fun find(id: String): Pair<PhoneTaskApp, Long> {
        if (!authorized) throw PhoneSourceException(denied())
        synchronized(lock) { known[id] }?.let { (app, taskID) ->
            if (app in allowed && PhoneTaskRules.reminderID(app.authority, taskID) == id) return app to taskID
        }
        for (app in allowed) {
            val ids = mutableListOf<Long>()
            runCatching {
                context.contentResolver.query(tasksUri(app), arrayOf(TaskContractNames.ID), "${TaskContractNames.DELETED} = 0", null, null)?.use { cursor ->
                    while (cursor.moveToNext()) cursor.long(TaskContractNames.ID)?.let { ids += it }
                }
            }
            ids.firstOrNull { PhoneTaskRules.reminderID(app.authority, it) == id }?.let { return app to it }
        }
        throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
    }

    /** Applies a reminder change; the reminder's shared id. */
    fun apply(change: PhoneChange, now: Instant = Instant.now()): String {
        if (installed.isEmpty()) throw PhoneSourceException(L10n.tr("Android has no reminders app for Conduit to use, so reminders can't be set here."))
        val fields = change.fields
        // Saying "applied" without the place alert would be untrue.
        if (fields.locationAlarmName != null) throw PhoneSourceException(L10n.tr("Reminders that go off at a place can't be set from Hermes yet."))
        val resolver = context.contentResolver
        return when (change.kind) {
            PhoneChangeKind.ADD_REMINDER -> {
                if (!authorized) throw PhoneSourceException(denied())
                val lists = lists()
                val target = fields.listID?.let { shared -> lists.firstOrNull { it.sharedID == shared } } ?: PhoneTaskRules.defaultList(lists)
                    ?: throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
                val values = contentValues(PhoneTaskRules.values(fields))
                values.put(TaskContractNames.LIST_ID, target.id)
                val uri = write { resolver.insert(tasksUri(target.app), values) } ?: throw PhoneSourceException(readOnly())
                PhoneTaskRules.reminderID(target.app.authority, ContentUris.parseId(uri))
            }
            PhoneChangeKind.UPDATE_REMINDER, PhoneChangeKind.COMPLETE_REMINDER -> {
                val (app, taskID) = find(change.reminderID.orEmpty())
                val values = if (change.kind == PhoneChangeKind.COMPLETE_REMINDER) {
                    contentValues(PhoneTaskRules.completion(fields.completed ?: true, now))
                } else {
                    contentValues(PhoneTaskRules.values(fields)).also { values ->
                        // Moving between lists works within one tasks app.
                        fields.listID?.let { shared -> lists(listOf(app)).firstOrNull { it.sharedID == shared } }?.let { values.put(TaskContractNames.LIST_ID, it.id) }
                    }
                }
                if (values.size() > 0) {
                    val changed = write { resolver.update(ContentUris.withAppendedId(tasksUri(app), taskID), values, null, null) }
                    if (changed == 0) throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
                }
                PhoneTaskRules.reminderID(app.authority, taskID)
            }
            else -> throw PhoneSourceException(L10n.tr("That item isn't on this phone any more."))
        }
    }

    /** The banner's Undo for a reminder added without asking. */
    fun remove(id: String) {
        val (app, taskID) = find(id)
        write { context.contentResolver.delete(ContentUris.withAppendedId(tasksUri(app), taskID), null, null) }
    }

    private fun <T> write(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw PhoneSourceException(denied())
    } catch (e: IllegalArgumentException) {
        // The provider turns down values it can't take.
        throw PhoneSourceException(readOnly())
    }

    private fun denied() = L10n.tr("Conduit doesn't have access to your calendar or reminders. Allow it in the Settings app.")
    private fun readOnly() = L10n.tr("That calendar can't be changed.")

    private fun contentValues(map: Map<String, Any?>) = ContentValues().apply {
        map.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
            }
        }
    }

    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getInt(it) }
}
