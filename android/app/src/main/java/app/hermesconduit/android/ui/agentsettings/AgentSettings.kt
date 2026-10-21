package app.hermesconduit.android.ui.agentsettings

import android.content.Context
import android.os.Build
import app.hermesconduit.android.BuildConfig
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.present.PresentedScreens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** iOS AgentSettingsMode: off | ask | small. */
object AgentSettingsMode {
    const val KEY = "agent_settings.mode"
    fun get(context: Context) = context.appSettings.getString(KEY, "ask")
}

sealed interface AgentValue {
    data class B(val value: Boolean) : AgentValue
    data class S(val value: String) : AgentValue

    fun json(): JsonElement = when (this) { is B -> JsonPrimitive(value); is S -> JsonPrimitive(value) }

    companion object {
        fun from(json: JsonElement?): AgentValue? {
            val p = json as? JsonPrimitive ?: return null
            if (p is kotlinx.serialization.json.JsonNull) return null
            if (!p.isString) p.booleanOrNull?.let { return B(it) }
            return if (p.isString) S(p.content) else null
        }
    }
}

/** One Conduit setting Hermes may read and propose changes to (iOS AgentSettingDescriptor). */
class AgentSettingDescriptor(
    val key: String,
    val section: String,
    val title: String,
    val description: String,
    /** null for a bool; choices (value to title) otherwise. */
    val choices: List<Pair<String, String>>?,
    /** "small" applies right away in small mode with Undo; "ask" always asks. */
    val tier: String,
    val read: () -> AgentValue,
    val write: (AgentValue) -> Unit,
) {
    fun accepts(value: AgentValue) = when (value) {
        is AgentValue.B -> choices == null
        is AgentValue.S -> choices?.any { it.first == value.value } == true
    }

    fun display(value: AgentValue): String = when (value) {
        is AgentValue.B -> if (value.value) L10n.tr("On") else L10n.tr("Off")
        is AgentValue.S -> choices?.firstOrNull { it.first == value.value }?.second ?: value.value
    }

    fun payload() = buildJsonObject {
        put("key", key); put("title", title); put("section", section); put("description", description); put("tier", tier)
        put("value", read().json())
        if (choices == null) put("kind", "bool") else {
            put("kind", "choice")
            put("choices", buildJsonArray { choices.forEach { (v, t) -> add(buildJsonObject { put("value", v); put("title", t) }) } })
        }
    }
}

data class AgentSettingRequest(val id: String, val key: String, val value: AgentValue, val from: AgentValue?, val reason: String) {
    companion object {
        fun from(json: JsonElement): AgentSettingRequest? {
            val o = json.obj ?: return null
            val id = o["id"].str?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
            val key = o["key"].str?.takeIf { it.isNotEmpty() } ?: return null
            val value = AgentValue.from(o["value"]) ?: return null
            return AgentSettingRequest(id, key, value, AgentValue.from(o["from"]), o["reason"].str?.take(200).orEmpty())
        }
    }
}

data class ReviewItem(val request: AgentSettingRequest, val title: String, val oldValue: String, val newValue: String)

data class AppliedNotice(val changes: List<Triple<AgentSettingRequest, AgentValue, String>>) {
    val message: String
        get() = if (changes.size == 1) L10n.tr("Hermes changed %s", changes.first().third) else L10n.tr("Hermes changed %d settings", changes.size)
}

/** `PUT PLUGIN/conduit-settings`: publishes the catalog and takes Hermes' pending changes. */
object AgentSettingsSync {
    private const val PATH = "api/plugins/conduit_push/conduit-settings"

    /** Features register their settings here (voice registers its keys). */
    val catalog = mutableListOf<AgentSettingDescriptor>()

    private val _review = MutableStateFlow<List<ReviewItem>>(emptyList())
    val review: StateFlow<List<ReviewItem>> = _review
    private val _applied = MutableStateFlow<AppliedNotice?>(null)
    val applied: StateFlow<AppliedNotice?> = _applied

    private val results = mutableListOf<JsonObject>()
    private val answered = mutableSetOf<String>()
    private val mutex = Mutex()

    private fun record(id: String, status: String, note: String = "") {
        synchronized(results) {
            results.removeAll { it["id"].str == id && status != "undone" }
            results += buildJsonObject { put("id", id); put("status", status); if (note.isNotEmpty()) put("note", note) }
            while (results.size > 40) results.removeAt(0)
            answered += id
        }
    }

    private fun descriptor(key: String) = synchronized(catalog) { catalog.firstOrNull { it.key == key } }

    fun sync(context: Context) {
        val hermes = context.hermes
        if (!hermes.isSignedIn) return
        hermes.scope.launch {
            mutex.withLock {
                repeat(3) {
                    val sent = synchronized(results) { results.toList() }
                    val body = buildJsonObject {
                        put("installation_id", PresentedScreens.installationId(context))
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("app_version", BuildConfig.VERSION_NAME)
                        put("mode", AgentSettingsMode.get(context))
                        put("settings", buildJsonArray { synchronized(catalog) { catalog.toList() }.forEach { add(it.payload()) } })
                        put("results", buildJsonArray { sent.forEach { add(it) } })
                    }
                    val profile = hermes.profile.value.takeIf { it != "default" }
                    val response = runCatching { hermes.rest { it.sendJson("PUT", PATH, body, mapOf("profile" to profile)) } }.getOrNull() ?: return@withLock
                    if (response["ok"].bool != true) return@withLock
                    val sentIds = sent.mapNotNull { it["id"].str }.toSet()
                    synchronized(results) { results.removeAll { it["id"].str in sentIds } }
                    val pending = response["pending"].arr?.mapNotNull(AgentSettingRequest::from)?.filter { it.id !in sentIds }.orEmpty()
                    if (!take(context, pending)) return@withLock
                }
            }
        }
    }

    /** iOS AgentSettingDecision; returns whether anything was answered (so another round goes out). */
    private fun take(context: Context, pending: List<AgentSettingRequest>): Boolean {
        val mode = AgentSettingsMode.get(context)
        val review = mutableListOf<ReviewItem>()
        val appliedNow = mutableListOf<Triple<AgentSettingRequest, AgentValue, String>>()
        var anyAnswered = false
        for (request in pending.distinctBy { it.id }) {
            if (request.id in answered) continue
            val d = descriptor(request.key)
            when {
                d == null || !d.accepts(request.value) -> { record(request.id, "refused", "Conduit doesn't let Hermes change this setting to that value."); anyAnswered = true }
                mode == "off" -> { record(request.id, "refused", "The user turned off Hermes changing Conduit settings."); anyAnswered = true }
                d.read() == request.value -> { record(request.id, "applied", "It already had that value."); anyAnswered = true }
                request.from != null && request.from != d.read() -> { record(request.id, "conflict", "The user changed this setting after Hermes read it; the user's value was kept."); anyAnswered = true }
                mode == "small" && d.tier == "small" -> {
                    val previous = d.read()
                    d.write(request.value)
                    record(request.id, "applied")
                    appliedNow += Triple(request, previous, d.title)
                    anyAnswered = true
                }
                else -> review += ReviewItem(request, d.title, d.display(d.read()), d.display(request.value))
            }
        }
        _review.value = review
        if (appliedNow.isNotEmpty()) _applied.value = AppliedNotice(appliedNow)
        return anyAnswered
    }

    fun resolve(context: Context, items: List<ReviewItem>, apply: Boolean) {
        for (item in items) {
            val d = descriptor(item.request.key)
            when {
                !apply -> record(item.request.id, "dismissed")
                d == null || !d.accepts(item.request.value) -> record(item.request.id, "refused", "Conduit no longer lets Hermes change this setting to that value.")
                item.request.from != null && d.read() != item.request.from -> record(item.request.id, "conflict", "The user changed this setting after Hermes read it; the user's value was kept.")
                else -> { d.write(item.request.value); record(item.request.id, "applied", "The user approved it.") }
            }
        }
        val ids = items.map { it.request.id }.toSet()
        _review.value = _review.value.filterNot { it.request.id in ids }
        sync(context)
    }

    fun undo(context: Context, notice: AppliedNotice) {
        _applied.value = null
        for ((request, previous, _) in notice.changes) {
            val d = descriptor(request.key) ?: continue
            if (d.read() != request.value) continue
            d.write(previous)
            record(request.id, "undone", "The user tapped Undo.")
        }
        sync(context)
    }

    fun dismissApplied() { _applied.value = null }
}
