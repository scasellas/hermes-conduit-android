package app.hermesconduit.android.ui.cron

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.CronJob
import app.hermesconduit.android.model.CronJobAction
import app.hermesconduit.android.model.CronJobFilter
import app.hermesconduit.android.model.CronParser
import app.hermesconduit.android.model.CronRun
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.ui.gateway.userMessage
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CronState(
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val filter: CronJobFilter = CronJobFilter.ALL,
    val query: String = "",
    val showAll: Boolean = false,
    /** Cron-sourced sessions of the profile ("Recent runs"). */
    val recentRuns: List<SessionSummary> = emptyList(),
    val runs: List<CronRun> = emptyList(),
    val runsLoading: Boolean = false,
    val actionJobId: String? = null,
    /** Whether the host's plugin keeps per-job notifications. */
    val supportsNotifications: Boolean = false,
    /** Muted job ids; null until read (unknown counts as on). */
    val muted: Set<String>? = null,
    val notificationsChangingJobId: String? = null,
    val error: String? = null,
)

/** Scheduled jobs (iOS AppState §Scheduled jobs + AppState+CronNotifications). */
class CronViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val plugin = PluginStatusRepository.of(hermes)
    private val _state = MutableStateFlow(CronState())
    val state: StateFlow<CronState> = _state

    init {
        refresh()
        // A profile or dashboard switch reloads.
        viewModelScope.launch { hermes.profile.drop(1).collect { reset() } }
        viewModelScope.launch { hermes.connection.drop(1).collect { reset() } }
    }

    private val profile get() = hermes.profile.value

    private fun reset() {
        _state.value = CronState(filter = _state.value.filter)
        refresh()
    }

    fun setFilter(filter: CronJobFilter) = _state.update { it.copy(filter = filter) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun toggleShowAll() = _state.update { it.copy(showAll = !it.showAll) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun job(id: String): CronJob? = _state.value.jobs.firstOrNull { it.id == id }

    fun notifies(job: CronJob): Boolean = _state.value.muted?.contains(job.id) != true

    fun refresh() {
        viewModelScope.launch { loadJobs() }
        viewModelScope.launch { loadRecentRuns() }
        viewModelScope.launch { refreshNotifications() }
    }

    private suspend fun loadJobs() {
        if (_state.value.loading) return
        val profile = profile
        _state.update { it.copy(loading = true) }
        try {
            val jobs = try {
                hermes.rest { it.getJson("api/cron/jobs", mapOf("profile" to profile)) }.let(CronParser::jobs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An older gateway without the dashboard route answers over RPC.
                runCatching { CronParser.rpcJobs(hermes.rpc("cron.list")) }.getOrElse { throw e }
            }
            if (profile == this.profile) _state.update { it.copy(jobs = jobs, loaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (profile == this.profile) {
                _state.update { it.copy(loaded = true, error = L10n.tr("Could not load scheduled jobs: %s", e.userMessage())) }
            }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadRecentRuns() {
        val profile = profile
        val query = mapOf(
            "limit" to "200", "offset" to "0", "min_messages" to "1", "archived" to "exclude",
            "order" to "recent", "source" to "cron", "profile" to profile.takeIf { it != "default" },
        )
        val sessions = runCatching {
            val result = hermes.rest { it.getJson("api/profiles/sessions", query) }
            MessageNormalizer.sessions(result["sessions"].arr ?: result["_array"].arr ?: JsonArray(emptyList()))
        }.getOrNull() ?: return
        if (profile == this.profile) _state.update { it.copy(recentRuns = sessions.filter { s -> !s.archived }.take(20)) }
    }

    fun loadRuns(job: CronJob) {
        val profile = profile
        _state.update { it.copy(runs = emptyList(), runsLoading = true) }
        viewModelScope.launch {
            try {
                var runs = try {
                    val result = hermes.rest { it.getJson("api/cron/jobs/${job.id}/runs", mapOf("limit" to "20", "profile" to profile)) }
                    CronParser.runs(CronParser.runValues(result), profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { CronParser.rpcRuns(hermes.rpc("cron.runs"), profile) }.getOrElse { throw e }
                }
                if (runs.isEmpty()) runs = runCatching { CronParser.rpcRuns(hermes.rpc("cron.runs"), profile) }.getOrDefault(emptyList())
                if (profile == this@CronViewModel.profile) _state.update { it.copy(runs = runs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (profile == this@CronViewModel.profile) {
                    _state.update { it.copy(error = L10n.tr("Could not load scheduled-job runs: %s", e.userMessage())) }
                }
            } finally {
                _state.update { it.copy(runsLoading = false) }
            }
        }
    }

    fun perform(action: CronJobAction, job: CronJob, then: (() -> Unit)? = null) {
        if (_state.value.actionJobId != null) return
        val profile = profile
        _state.update { it.copy(actionJobId = job.id) }
        viewModelScope.launch {
            try {
                val result = hermes.rest {
                    it.sendJson("POST", "api/cron/jobs/${job.id}/${action.path}", buildJsonObject { put("profile", profile) }, mapOf("profile" to profile))
                }
                if (profile != this@CronViewModel.profile) return@launch
                val updated = CronParser.job(result)?.takeIf { it.id == job.id }
                if (updated != null) {
                    _state.update { s -> s.copy(jobs = s.jobs.map { if (it.id == job.id) updated else it }) }
                } else {
                    _state.update { it.copy(actionJobId = null) }
                    loadJobs()
                }
                then?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One whole sentence per action, so each can be translated.
                val key = when (action) {
                    CronJobAction.PAUSE -> "Could not pause scheduled job: %s"
                    CronJobAction.RESUME -> "Could not resume scheduled job: %s"
                    CronJobAction.TRIGGER -> "Could not run scheduled job: %s"
                }
                _state.update { it.copy(error = L10n.tr(key, e.userMessage())) }
            } finally {
                _state.update { it.copy(actionJobId = null) }
            }
        }
    }

    // region Notifications (PLUGIN/cron/notifications, capability `cron-notifications`)

    private suspend fun refreshNotifications() {
        val supports = runCatching { plugin.supports(CAPABILITY) }.getOrDefault(false)
        _state.update { it.copy(supportsNotifications = supports) }
        if (!supports || _state.value.notificationsChangingJobId != null) return
        val profile = profile
        val muted = runCatching {
            CronParser.mutedJobs(hermes.rest { it.getJson(NOTIFICATIONS_PATH, mapOf("profile" to profile.takeIf { p -> p != "default" })) })
        }.getOrNull() ?: return
        if (profile == this.profile && _state.value.notificationsChangingJobId == null) _state.update { it.copy(muted = muted) }
    }

    fun setNotifies(notify: Boolean, job: CronJob) {
        val current = _state.value
        if (!current.supportsNotifications || current.notificationsChangingJobId != null) return
        val profile = profile
        val previous = current.muted
        // The switch moves at once; the host's answer settles it.
        val optimistic = (previous ?: emptySet()).let { if (notify) it - job.id else it + job.id }
        _state.update { it.copy(muted = optimistic, notificationsChangingJobId = job.id) }
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("job_id", job.id)
                    put("notify", notify)
                }
                val response = hermes.rest {
                    it.sendJson("PUT", NOTIFICATIONS_PATH, body, mapOf("profile" to profile.takeIf { p -> p != "default" }))
                }
                val muted = CronParser.mutedJobs(response) ?: throw IllegalStateException(L10n.tr("The gateway returned an invalid response."))
                if (profile == this@CronViewModel.profile) _state.update { it.copy(muted = muted) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        muted = previous,
                        error = L10n.tr("Could not change this job's notifications: %s", e.userMessage()),
                    )
                }
            } finally {
                _state.update { it.copy(notificationsChangingJobId = null) }
            }
        }
    }

    // endregion

    companion object {
        const val CAPABILITY = "cron-notifications"
        const val NOTIFICATIONS_PATH = "api/plugins/conduit_push/cron/notifications"
    }
}
