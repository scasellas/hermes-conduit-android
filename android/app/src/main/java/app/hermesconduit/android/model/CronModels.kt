package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A Hermes scheduled job (`/api/cron/jobs`, RPC `cron.list`). Port of iOS `CronJob`. */
data class CronJob(
    val id: String,
    val enabled: Boolean,
    val name: String? = null,
    val prompt: String? = null,
    val deliver: String? = null,
    val lastError: String? = null,
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    val noAgent: Boolean? = null,
    val scheduleDisplay: String? = null,
    val scheduleExpr: String? = null,
    val scheduleKind: String? = null,
    val script: String? = null,
    val state: String? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: id

    /** A finished one-shot job will not run again on its own, even if the record still reads enabled. */
    val isFinished: Boolean get() = state?.lowercase() == COMPLETED_STATE

    /** Whether the job will run on its own: enabled and not finished. */
    val isActive: Boolean get() = enabled && !isFinished

    /** What the row shows under the name: the schedule in words, else its expression. */
    val scheduleText: String? get() = scheduleDisplay ?: scheduleExpr

    companion object {
        const val COMPLETED_STATE = "completed"
    }
}

/** A Cron job action; the value is the dashboard API path segment. */
enum class CronJobAction(val path: String) { PAUSE("pause"), RESUME("resume"), TRIGGER("trigger") }

/** One run of a job: a cron-sourced session. */
data class CronRun(
    val id: String,
    val lastActive: Long? = null,
    val model: String? = null,
    val preview: String? = null,
    val profile: String? = null,
    val startedAt: Long? = null,
    val title: String? = null,
)

/** The Cron screen's Active / Inactive / All filter (iOS `CronJobFilter`). */
enum class CronJobFilter(val title: String) {
    ACTIVE("Active"), INACTIVE("Inactive"), ALL("All");

    fun includes(job: CronJob): Boolean = when (this) {
        ACTIVE -> job.isActive
        INACTIVE -> !job.isActive
        ALL -> true
    }

    companion object {
        /** Rows the collapsed Jobs list shows. */
        const val COLLAPSED_ROW_COUNT = 3
    }
}

object CronParser {
    fun job(element: JsonElement?): CronJob? {
        val o = element.obj ?: return null
        val id = o["id"].str?.takeIf { it.isNotBlank() } ?: return null
        // `schedule` is an object ({display, expr, kind}) on current Hermes;
        // tolerate a bare string from older ones.
        val schedule = o["schedule"]
        val scheduleObj = schedule.obj
        return CronJob(
            id = id,
            enabled = o["enabled"].bool ?: true,
            name = o["name"].str?.takeIf { it.isNotEmpty() },
            prompt = o["prompt"].str,
            deliver = o["deliver"].str?.takeIf { it.isNotEmpty() },
            lastError = o["last_error"].str,
            lastRunAt = o["last_run_at"].str,
            nextRunAt = o["next_run_at"].str,
            noAgent = o["no_agent"].bool,
            scheduleDisplay = o["schedule_display"].str ?: scheduleObj?.get("display").str,
            scheduleExpr = scheduleObj?.get("expr").str ?: schedule.takeIf { scheduleObj == null }.str,
            scheduleKind = scheduleObj?.get("kind").str,
            script = o["script"].str,
            state = o["state"].str,
        )
    }

    /** `/api/cron/jobs` (array, or `{jobs: […]}`), sorted by name. */
    fun jobs(response: JsonObject): List<CronJob> {
        val rows = response["_array"].arr ?: response["jobs"].arr ?: JsonArray(emptyList())
        return rows.mapNotNull(::job).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    /** RPC `cron.list` result (`{jobs}` or a bare array). */
    fun rpcJobs(result: JsonElement): List<CronJob> =
        (result.obj?.get("jobs").arr ?: result.arr ?: JsonArray(emptyList())).mapNotNull(::job)

    fun run(element: JsonElement?): CronRun? {
        val raw = element.obj ?: return null
        val o = raw["session"].obj ?: raw
        val id = text(o["id"] ?: o["session_id"] ?: o["run_id"]) ?: return null
        return CronRun(
            id = id,
            lastActive = MessageNormalizer.epochMillis(o["last_active"] ?: o["lastActive"]),
            model = text(o["model"]),
            preview = text(o["preview"] ?: o["summary"]),
            profile = text(o["profile"]),
            startedAt = MessageNormalizer.epochMillis(o["started_at"] ?: o["startedAt"]),
            title = text(o["title"] ?: o["name"]),
        )
    }

    /** The run rows, wherever this Hermes version puts them. */
    fun runValues(result: JsonObject): JsonArray {
        (result["runs"].arr ?: result["items"].arr ?: result["_array"].arr ?: result["data"].arr)?.let { return it }
        result["data"].obj?.let { return it["runs"].arr ?: it["items"].arr ?: JsonArray(emptyList()) }
        result["runs"].obj?.let { return it["items"].arr ?: it["results"].arr ?: JsonArray(emptyList()) }
        return JsonArray(emptyList())
    }

    /** Runs owned by `profile` (a run without an owner is kept). */
    fun runs(rows: JsonArray, profile: String): List<CronRun> =
        rows.mapNotNull(::run).filter { run -> run.profile.isNullOrEmpty() || profilesMatch(run.profile, profile) }

    fun rpcRuns(result: JsonElement, profile: String): List<CronRun> =
        runs(result.obj?.get("runs").arr ?: result.arr ?: JsonArray(emptyList()), profile)

    fun profilesMatch(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /** Muted job ids from `PLUGIN/cron/notifications`; null when malformed. */
    fun mutedJobs(response: JsonObject): Set<String>? {
        if (response["ok"].bool != true) return null
        val muted = response["muted"].arr ?: return null
        return muted.mapNotNull { it.str }.toSet()
    }

    /** The jobs the list shows: those the filter keeps that match the search, active first, then by name. */
    fun visibleJobs(jobs: List<CronJob>, filter: CronJobFilter, query: String): List<CronJob> {
        val q = query.trim()
        return jobs.filter { filter.includes(it) && (q.isEmpty() || matches(it, q)) }
            .sortedWith(
                compareBy<CronJob> { !it.isActive }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                    .thenBy { it.id },
            )
    }

    fun matches(job: CronJob, query: String): Boolean =
        listOf(job.displayName, job.prompt.orEmpty(), job.scheduleDisplay.orEmpty(), job.deliver.orEmpty())
            .any { it.contains(query, ignoreCase = true) }

    /** A search shows every match; otherwise the list stays collapsed to its first rows until expanded. */
    fun shownJobs(jobs: List<CronJob>, showAll: Boolean, isSearching: Boolean): List<CronJob> =
        if (showAll || isSearching) jobs else jobs.take(CronJobFilter.COLLAPSED_ROW_COUNT)

    /** English (title, description) keys for an empty list. */
    fun emptyState(filter: CronJobFilter, hasJobs: Boolean, isSearching: Boolean): Pair<String, String> {
        if (hasJobs && isSearching) {
            val hint = if (filter == CronJobFilter.ALL) "Try a different search." else "Try a different search or filter."
            return "No Matching Jobs" to hint
        }
        return when {
            filter == CronJobFilter.ACTIVE && hasJobs -> "No Active Jobs" to "Every scheduled job is paused or finished."
            filter == CronJobFilter.INACTIVE && hasJobs -> "No Inactive Jobs" to "Every scheduled job is active."
            else -> "No Scheduled Jobs" to "Scheduled jobs from Hermes appear here."
        }
    }

    private fun text(element: JsonElement?): String? = element.descriptive?.trim()?.takeIf { it.isNotEmpty() }
}
