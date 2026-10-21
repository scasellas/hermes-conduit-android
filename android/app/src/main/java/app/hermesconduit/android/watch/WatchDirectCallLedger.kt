package app.hermesconduit.android.watch

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchVoiceWire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The Hermes connection a watch call belongs to: the dashboard and the profile it began on. */
data class WatchDirectConnection(val profile: String, val dashboard: String)

/**
 * What the phone keeps about its recent watch calls across a restart (port
 * of the iOS `WatchDirectCallLedger`): the connection each began on and the
 * "Save voice calls" setting then, whether it ended, the start_job calls
 * sent toward Hermes for it, and what its grants cover. Profile names,
 * dashboard and grant ids only, never a key or a transcript. Stored as one
 * JSON string through [read]/[write].
 */
class WatchDirectCallLedger(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Call(
        val id: Long,
        val connection: WatchDirectConnection,
        val saveCalls: Boolean,
        val ended: Boolean = false,
        val jobs: List<String> = emptyList(),
        /** What its grants cover, so it can renew them after a restart. */
        val grant: GrantScope? = null,
    )

    /** The tools and limits a call's grants were asked for, and the grants' ids: never their keys. */
    data class GrantScope(
        val tools: List<String>,
        val jobTools: List<String>,
        val liveToken: Boolean,
        val maxJobs: Int,
        val jobOptions: Map<String, String>,
        val jobProfiles: List<WatchVoiceWire.JobProfileName>?,
        val voiceApprovals: Boolean,
        val grantIds: List<String>,
        val audio: Boolean?,
    )

    var calls: List<Call> = emptyList()
        private set
    /** The size of what was stored, when it couldn't be read. */
    var unreadableBytes: Int? = null
        private set

    init {
        val stored = read()
        if (!stored.isNullOrEmpty()) {
            val parsed = runCatching { decode(stored) }.getOrNull()
            if (parsed != null) calls = parsed else unreadableBytes = stored.toByteArray().size
        }
    }

    fun call(id: Long): Call? = calls.lastOrNull { it.id == id }

    fun hasSentJob(toolId: String, id: Long) = call(id)?.jobs?.contains(toolId) == true

    fun begin(id: Long, connection: WatchDirectConnection, saveCalls: Boolean) {
        calls = (calls.filter { it.id != id } + Call(id, connection, saveCalls)).takeLast(CALL_LIMIT)
        store()
    }

    /** Only a call this phone began is kept. */
    fun end(id: Long) {
        val index = calls.indexOfLast { it.id == id }
        if (index < 0 || calls[index].ended) return
        update(index) { it.copy(ended = true) }
    }

    fun setGrant(scope: GrantScope, id: Long) {
        val index = calls.indexOfLast { it.id == id }
        if (index < 0 || calls[index].grant == scope) return
        update(index) { it.copy(grant = scope) }
    }

    /** Written at once: the receipt is on disk before the start can reach Hermes. */
    fun recordJob(toolId: String, id: Long) {
        val index = calls.indexOfLast { it.id == id }
        if (index < 0 || toolId in calls[index].jobs) return
        update(index) { it.copy(jobs = (it.jobs + toolId).takeLast(JOB_LIMIT)) }
    }

    private fun update(index: Int, change: (Call) -> Call) {
        calls = calls.toMutableList().also { it[index] = change(it[index]) }
        store()
    }

    private fun store() = write(encode(calls))

    companion object {
        const val STORAGE_KEY = "watchDirect.calls.v1"
        /** Calls kept, newest last. */
        const val CALL_LIMIT = 16
        /** Start_job calls kept per call: more than any job cap allows. */
        const val JOB_LIMIT = 32

        fun jobKey(toolId: String, id: Long) = "$id:$toolId"

        fun encode(calls: List<Call>): String = JsonArray(calls.map { call ->
            buildJsonObject {
                put("id", call.id)
                put("connection", buildJsonObject { put("profile", call.connection.profile); put("dashboard", call.connection.dashboard) })
                put("saveCalls", call.saveCalls)
                put("ended", call.ended)
                put("jobs", strings(call.jobs))
                call.grant?.let { g ->
                    put("grant", buildJsonObject {
                        put("tools", strings(g.tools))
                        put("jobTools", strings(g.jobTools))
                        put("liveToken", g.liveToken)
                        put("maxJobs", g.maxJobs)
                        put("jobOptions", buildJsonObject { g.jobOptions.forEach { (k, v) -> put(k, v) } })
                        g.jobProfiles?.let { list -> put("jobProfiles", JsonArray(list.map { it.json() })) }
                        put("voiceApprovals", g.voiceApprovals)
                        put("grantIDs", strings(g.grantIds))
                        g.audio?.let { put("audio", it) }
                    })
                }
            }
        }).toString()

        fun decode(text: String): List<Call> {
            val array = app.hermesconduit.android.model.ConduitJson.parseToJsonElement(text) as JsonArray
            return array.map { element ->
                val o = element as JsonObject
                val connection = o["connection"] as JsonObject
                val grant = o["grant"] as? JsonObject
                Call(
                    id = (o["id"] as JsonPrimitive).longOrNull ?: error("id"),
                    connection = WatchDirectConnection(connection.str("profile").orEmpty(), connection.str("dashboard").orEmpty()),
                    saveCalls = (o["saveCalls"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    ended = (o["ended"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    jobs = o.strings("jobs"),
                    grant = grant?.let { g ->
                        GrantScope(
                            tools = g.strings("tools"),
                            jobTools = g.strings("jobTools"),
                            liveToken = (g["liveToken"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            maxJobs = (g["maxJobs"] as? JsonPrimitive)?.intOrNull ?: 0,
                            jobOptions = (g["jobOptions"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty(),
                            jobProfiles = (g["jobProfiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map(WatchVoiceWire.JobProfileName::from),
                            voiceApprovals = (g["voiceApprovals"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            grantIds = g.strings("grantIDs"),
                            audio = (g["audio"] as? JsonPrimitive)?.booleanOrNull,
                        )
                    },
                )
            }
        }

        private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    }
}

/**
 * The user's limits on a watch call's Hermes jobs through the push relay
 * (iOS `WatchJobSettings`): how many jobs one call may start, and whether the
 * model may answer a job's approval request by voice or only the watch's
 * buttons can. Picked in Settings > Watch.
 */
object WatchJobSettings {
    const val JOBS_PER_CALL = "watchDirect.jobsPerCall"
    const val VOICE_APPROVALS = WatchSettings.VOICE_APPROVALS
    const val DEFAULT_JOBS_PER_CALL = 5
    /** The host plugin's own cap (WATCH_JOBS_MAX). */
    const val MAXIMUM_JOBS_PER_CALL = 20

    /** 0 keeps a watch call's jobs on the phone's path. */
    fun clamped(stored: Int) = stored.coerceIn(0, MAXIMUM_JOBS_PER_CALL)
}

/** The phone's record of which watch voices can start ([WatchCallVoices]), for the active dashboard and profile. */
object WatchVoiceReadiness {
    private const val KEY = "watchVoice.readiness.v1"

    fun scope(connection: WatchDirectConnection) = "${connection.dashboard}/${connection.profile}"

    fun note(settings: AppSettings, connection: WatchDirectConnection, voice: String, ready: Boolean) {
        val next = WatchCallVoices.decode(settings.getString(KEY, "").ifEmpty { null }).noted(voice, ready, scope(connection))
        settings.set(KEY, next.encoded())
    }

    /** The checks for [connection]; another's say nothing about it. */
    fun current(settings: AppSettings, connection: WatchDirectConnection): Map<String, Boolean> {
        val stored = WatchCallVoices.decode(settings.getString(KEY, "").ifEmpty { null })
        return if (stored.scope == scope(connection)) stored.ready else emptyMap()
    }
}

/** Why the phone couldn't start or serve a call the watch asked for, as the watch shows it (iOS `WatchVoiceStartFailure`). */
object WatchVoiceStartFailure {
    val hermesUnreachable: String get() = L10n.tr("Conduit on your phone couldn't reach Hermes. Open it once and try again.")
    val ended: String get() = L10n.tr("The call ended before it connected.")
    val callRunning: String get() = L10n.tr("A voice call is already running on your phone.")
    val connectionChanged: String get() = L10n.tr("Conduit on your phone switched to another Hermes profile or server, so this call ended.")
    val versionMismatch: String get() = L10n.tr("Update Conduit on your phone and watch to the same version.")
}
