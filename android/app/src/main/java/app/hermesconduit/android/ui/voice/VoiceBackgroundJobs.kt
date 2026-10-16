package app.hermesconduit.android.ui.voice

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.VoiceBackgroundJobCommand
import app.hermesconduit.android.model.VoiceJobProfileTarget
import app.hermesconduit.android.model.VoiceJobProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** What the voice conversation should do with a pending job update. */
sealed interface VoiceBackgroundJobNotice {
    /** Speak a fixed notice locally; no Hermes turn is involved. */
    data class Speak(val text: String) : VoiceBackgroundJobNotice
    /** Hand a finished job's result to the voice session's own Hermes chat; `fallback` is spoken if that fails. */
    data class Submit(val prompt: String, val fallback: String) : VoiceBackgroundJobNotice
}

/** Seam the Voice controller uses for spoken job commands and hand-backs. */
interface VoiceBackgroundJobHandling {
    suspend fun performVoiceCommand(command: VoiceBackgroundJobCommand): String
    fun takePendingNotice(): VoiceBackgroundJobNotice?
}

/** One `session.active_list` row. */
data class LiveSessionStatus(val runtimeSessionId: String, val storedSessionId: String, val status: String) {
    val isRunning: Boolean get() = status == "working" || status == "waiting"
}

data class VoiceBackgroundJob(
    val id: String,
    val title: String,
    val instructions: String,
    val profile: String? = null,
    val profileLabel: String? = null,
    val runtimeSessionId: String? = null,
    val storedSessionId: String? = null,
    val status: Status,
    val result: String? = null,
    val startedAtMs: Long,
    val outcomeDelivered: Boolean = false,
    val inputRequestDelivered: Boolean = false,
    val consecutiveMissedPolls: Int = 0,
    val listedByLivenessPoll: Boolean = false,
    val number: Int = 0,
) {
    sealed interface Status {
        val isActive: Boolean get() = this is Starting || this is Running || this is NeedsInput
        data object Starting : Status
        data object Running : Status
        data object NeedsInput : Status
        data object Finished : Status
        data class Failed(val message: String) : Status
        data object Cancelled : Status
    }

    fun owns(sessionId: String) = sessionId.isNotEmpty() && (sessionId == runtimeSessionId || sessionId == storedSessionId)
}

/** Hermes operations the supervisor needs, as closures so the host binds the current client and tests script them. */
class VoiceBackgroundJobBackend(
    /** `profile` null creates the job in the active profile; returns (runtime id, stored id). */
    val createSession: suspend (profile: String?) -> Pair<String, String?>,
    val setTitle: suspend (sessionId: String, title: String) -> Unit,
    val submit: suspend (sessionId: String, text: String) -> Unit,
    val cancel: suspend (sessionId: String) -> Unit,
    val liveSessions: suspend (profile: String?) -> List<LiveSessionStatus>,
    val resolveProfile: (spokenName: String) -> VoiceJobProfileTarget = { VoiceJobProfileTarget.Unknown },
)

/**
 * Background jobs started from a voice conversation (issue #163): each job is
 * its own Hermes session; its result is handed back to the voice conversation
 * when it finishes. Classic-voice port of `VoiceBackgroundJobSupervisor`
 * (live-call thread turns, follow-ups and callbacks come with the live modes).
 * Runs on [scope]'s (main) dispatcher.
 */
class VoiceBackgroundJobSupervisor(
    private val scope: CoroutineScope,
    private val backend: VoiceBackgroundJobBackend,
    private val pollIntervalMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceBackgroundJobHandling {
    private val _jobs = MutableStateFlow<List<VoiceBackgroundJob>>(emptyList())
    val jobs: StateFlow<List<VoiceBackgroundJob>> = _jobs

    /** Called whenever a notice becomes pending, so an idle conversation can deliver it. */
    var onNoticePending: (() -> Unit)? = null
    /** Called with every id of a newly created job session (to tag it under Voice Jobs). */
    var onJobSessionCreated: ((List<String>) -> Unit)? = null

    private var pollJob: Job? = null
    private var generation = 0L
    private var lastJobNumber = 0

    val activeJobCount: Int get() = _jobs.value.count { it.status.isActive }

    private fun job(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun update(id: String, change: (VoiceBackgroundJob) -> VoiceBackgroundJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) change(it) else it }
    }

    override suspend fun performVoiceCommand(command: VoiceBackgroundJobCommand): String = when (command) {
        is VoiceBackgroundJobCommand.Start -> startJob(command.instructions)
        VoiceBackgroundJobCommand.Status -> statusSummary()
        VoiceBackgroundJobCommand.CancelAll -> cancelAll()
    }

    suspend fun startJob(requested: String, spokenProfile: String? = null): String {
        val active = activeJobCount
        if (active >= MAXIMUM_ACTIVE_JOBS) {
            return L10n.tr("You already have %s background jobs running. Cancel them before starting another.", active.toString())
        }
        val (instructions, profile, label) = when (val route = VoiceJobProfiles.route(requested, spokenProfile, backend.resolveProfile)) {
            is VoiceJobProfiles.Route.Run -> Triple(route.instructions, route.profile, route.label)
            is VoiceJobProfiles.Route.Unknown -> return L10n.tr("I don't know a profile or bot called %s, so I didn't start the job.", route.name)
        }
        lastJobNumber += 1
        val created = VoiceBackgroundJob(
            id = UUID.randomUUID().toString(), title = title(instructions), instructions = instructions, profile = profile,
            profileLabel = label, status = VoiceBackgroundJob.Status.Starting, startedAtMs = clock(), number = lastJobNumber,
        )
        _jobs.value = _jobs.value + created
        val generation = generation
        var createdSessionId: String? = null
        try {
            val (runtime, stored) = backend.createSession(profile)
            createdSessionId = runtime.ifEmpty { null }
            if (!startIsCurrent(created.id, generation)) return abandonStart(created, createdSessionId, generation)
            val runtimeId = createdSessionId ?: throw IllegalStateException(L10n.tr("Hermes did not return a session."))
            update(created.id) { it.copy(runtimeSessionId = runtimeId, storedSessionId = stored) }
            onJobSessionCreated?.invoke(listOfNotNull(runtimeId, stored).filter { it.isNotEmpty() })
            runCatching { backend.setTitle(runtimeId, created.title) }
            if (!startIsCurrent(created.id, generation)) return abandonStart(created, runtimeId, generation)
            backend.submit(runtimeId, jobPrompt(instructions))
            if (generation != this.generation || job(created.id)?.status == VoiceBackgroundJob.Status.Cancelled) {
                return abandonStart(created, runtimeId, generation)
            }
            if (job(created.id)?.status is VoiceBackgroundJob.Status.Failed) return failedStartReply(created.id)
            if (job(created.id)?.status == VoiceBackgroundJob.Status.Starting) update(created.id) { it.copy(status = VoiceBackgroundJob.Status.Running) }
            startPollingIfNeeded()
            return startedReply(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != this.generation || job(created.id)?.status == VoiceBackgroundJob.Status.Cancelled) {
                return abandonStart(created, createdSessionId, generation)
            }
            when (job(created.id)?.status) {
                VoiceBackgroundJob.Status.Running, VoiceBackgroundJob.Status.NeedsInput, VoiceBackgroundJob.Status.Finished -> {
                    startPollingIfNeeded()
                    return startedReply(created)
                }
                is VoiceBackgroundJob.Status.Failed -> Unit
                else -> {
                    update(created.id) { it.copy(status = VoiceBackgroundJob.Status.Failed(e.message.orEmpty())) }
                    createdSessionId?.let { runCatching { backend.cancel(it) } }
                }
            }
            return failedStartReply(created.id)
        }
    }

    private fun startedReply(job: VoiceBackgroundJob): String {
        val profile = job.profileLabel ?: job.profile
        return if (profile != null) L10n.tr("Started a background job on %s: %s.", profile, job.title)
        else L10n.tr("Started a background job: %s.", job.title)
    }

    private fun failedStartReply(id: String): String {
        update(id) { it.copy(outcomeDelivered = true) }
        pruneSettledJobs()
        return L10n.tr("Couldn't start the background job.")
    }

    private fun startIsCurrent(id: String, generation: Long) = generation == this.generation && job(id)?.status == VoiceBackgroundJob.Status.Starting

    private suspend fun abandonStart(job: VoiceBackgroundJob, sessionId: String?, generation: Long): String {
        sessionId?.let { runCatching { backend.cancel(it) } }
        if (generation != this.generation) return L10n.tr("Couldn't start the background job.")
        return L10n.tr("%s was cancelled.", job.title)
    }

    fun statusSummary(): String {
        val visible = _jobs.value.filter { it.status.isActive || !it.outcomeDelivered }
        if (visible.isEmpty()) return L10n.tr("No background jobs are running.")
        return visible.joinToString(" ") { job ->
            when (job.status) {
                VoiceBackgroundJob.Status.Starting, VoiceBackgroundJob.Status.Running -> L10n.tr("%s is still running.", job.title)
                VoiceBackgroundJob.Status.NeedsInput -> L10n.tr("%s is waiting for your input.", job.title)
                VoiceBackgroundJob.Status.Finished -> L10n.tr("%s has finished.", job.title)
                is VoiceBackgroundJob.Status.Failed -> L10n.tr("%s failed.", job.title)
                VoiceBackgroundJob.Status.Cancelled -> L10n.tr("%s was cancelled.", job.title)
            }
        }
    }

    suspend fun cancelAll(): String {
        val targets = _jobs.value.filter { it.status.isActive }
        if (targets.isEmpty()) return L10n.tr("There are no background jobs to cancel.")
        val failures = mutableListOf<String>()
        for (job in targets) {
            if (!cancelOnHermes(job)) failures += L10n.tr("Couldn't cancel %s. It may still be running.", job.title)
        }
        stopPollingIfIdle()
        pruneSettledJobs()
        val count = targets.size - failures.size
        val summary = if (count > 0) listOf(L10n.tr("Cancelled %s background jobs.", count.toString())) else emptyList()
        return (summary + failures).joinToString(" ")
    }

    suspend fun cancel(jobId: String): String? {
        val job = job(jobId)?.takeIf { it.status.isActive } ?: return null
        val cancelled = cancelOnHermes(job)
        stopPollingIfIdle()
        pruneSettledJobs()
        return if (cancelled) L10n.tr("%s was cancelled.", job.title) else L10n.tr("Couldn't cancel %s. It may still be running.", job.title)
    }

    private suspend fun cancelOnHermes(job: VoiceBackgroundJob): Boolean {
        update(job.id) { it.copy(status = VoiceBackgroundJob.Status.Cancelled, outcomeDelivered = true) }
        val sessionId = job.runtimeSessionId ?: return true
        return try {
            backend.cancel(sessionId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (job(job.id)?.status == VoiceBackgroundJob.Status.Cancelled) {
                update(job.id) { it.copy(status = job.status, outcomeDelivered = job.outcomeDelivered) }
            }
            false
        }
    }

    /** Hands a settled job's outcome to the voice conversation again (once). */
    fun replayOutcome(jobId: String): Boolean {
        val job = job(jobId) ?: return false
        if (job.status.isActive) return false
        update(jobId) { it.copy(outcomeDelivered = false) }
        noticeMayBePending()
        return true
    }

    /** Forgets every job without touching Hermes (server, profile and sign-out boundaries). */
    fun reset() {
        generation++
        pollJob?.cancel()
        pollJob = null
        _jobs.value = emptyList()
        lastJobNumber = 0
    }

    /** Observes every gateway event; only events addressed to a job session change anything. */
    fun observe(event: StreamEvent) {
        val sessionId = event.sessionId
        if (sessionId.isEmpty()) return
        val before = _jobs.value.firstOrNull { it.owns(sessionId) } ?: return
        if (!before.status.isActive) return
        if (event is StreamEvent.MessageComplete && isInterruptionNotice(event.content)) return
        var next = if (before.consecutiveMissedPolls != 0) before.copy(consecutiveMissedPolls = 0) else before
        next = when (event) {
            is StreamEvent.MessageStart, is StreamEvent.MessageDelta, is StreamEvent.ReasoningDelta, is StreamEvent.ToolStart, is StreamEvent.ToolComplete ->
                if (before.status == VoiceBackgroundJob.Status.NeedsInput || before.status == VoiceBackgroundJob.Status.Starting) {
                    next.copy(status = VoiceBackgroundJob.Status.Running, inputRequestDelivered = false)
                } else next
            is StreamEvent.Approval, is StreamEvent.Clarify, is StreamEvent.InputPromptRequest ->
                next.copy(status = VoiceBackgroundJob.Status.NeedsInput, inputRequestDelivered = false)
            is StreamEvent.MessageComplete -> next.copy(status = VoiceBackgroundJob.Status.Finished, result = event.content)
            is StreamEvent.Error -> next.copy(status = VoiceBackgroundJob.Status.Failed(event.message))
            is StreamEvent.Interrupted -> next.copy(status = VoiceBackgroundJob.Status.Cancelled)
            else -> return
        }
        if (next != before) {
            // Only real changes are published: a write per streamed delta would re-render at that rate.
            update(before.id) { next }
            if (next.status != before.status || next.inputRequestDelivered != before.inputRequestDelivered) noticeMayBePending()
        }
    }

    override fun takePendingNotice(): VoiceBackgroundJobNotice? {
        try {
            for (job in _jobs.value) {
                if (job.status == VoiceBackgroundJob.Status.NeedsInput && !job.inputRequestDelivered) {
                    update(job.id) { it.copy(inputRequestDelivered = true) }
                    return VoiceBackgroundJobNotice.Speak(waitingNotice(job.title))
                }
                if (job.status.isActive || job.outcomeDelivered) continue
                update(job.id) { it.copy(outcomeDelivered = true) }
                return when (job.status) {
                    VoiceBackgroundJob.Status.Finished -> {
                        val openChat = finishedNotice(job.title)
                        val result = job.result?.trim()
                        if (result.isNullOrEmpty()) VoiceBackgroundJobNotice.Speak(openChat)
                        else VoiceBackgroundJobNotice.Submit(completionPrompt(job.title, result), openChat)
                    }
                    is VoiceBackgroundJob.Status.Failed -> VoiceBackgroundJobNotice.Speak(failedNotice(job.title, ""))
                    VoiceBackgroundJob.Status.Cancelled -> VoiceBackgroundJobNotice.Speak(cancelledNotice(job.title))
                    else -> null
                }
            }
            return null
        } finally {
            pruneSettledJobs()
        }
    }

    private fun noticeMayBePending() {
        stopPollingIfIdle()
        onNoticePending?.invoke()
    }

    // region Liveness poll

    private fun startPollingIfNeeded() {
        if (pollJob != null || activeJobCount == 0) return
        val generation = generation
        pollJob = scope.launch {
            while (true) {
                delay(pollIntervalMs)
                if (generation != this@VoiceBackgroundJobSupervisor.generation) return@launch
                pollOnce()
                if (generation != this@VoiceBackgroundJobSupervisor.generation) return@launch
                if (activeJobCount == 0) {
                    pollJob = null
                    return@launch
                }
            }
        }
    }

    private fun stopPollingIfIdle() {
        if (activeJobCount != 0) return
        pollJob?.cancel()
        pollJob = null
    }

    /** Fallback for completions the event stream never delivered; `session.active_list` is authoritative about absence. */
    suspend fun pollOnce() {
        val generation = generation
        val profiles = _jobs.value.filter { it.status == VoiceBackgroundJob.Status.Running || it.status == VoiceBackgroundJob.Status.NeedsInput }
            .map { it.profile }.distinct()
        val rowsByProfile = mutableMapOf<String?, List<LiveSessionStatus>>()
        for (profile in profiles) {
            val rows = runCatching { backend.liveSessions(profile) }.getOrNull()
            if (generation != this.generation) return
            if (rows != null) rowsByProfile[profile] = rows
        }
        var changed = false
        _jobs.value = _jobs.value.map { job ->
            if (job.status != VoiceBackgroundJob.Status.Running && job.status != VoiceBackgroundJob.Status.NeedsInput) return@map job
            if (!rowsByProfile.containsKey(job.profile)) return@map job
            val rows = rowsByProfile.getValue(job.profile)
            val row = rows.firstOrNull { job.owns(it.runtimeSessionId) || job.owns(it.storedSessionId) }
            var next = job
            if (row != null) {
                if (next.storedSessionId.isNullOrEmpty() && row.storedSessionId.isNotEmpty() && row.storedSessionId != next.runtimeSessionId) {
                    next = next.copy(storedSessionId = row.storedSessionId)
                }
                next = next.copy(consecutiveMissedPolls = 0, listedByLivenessPoll = true)
                if (row.isRunning || row.status == "starting") return@map next
            } else {
                if (job.profile != null && !job.listedByLivenessPoll) return@map next
                next = next.copy(consecutiveMissedPolls = next.consecutiveMissedPolls + 1)
                if (next.consecutiveMissedPolls < MISSED_POLLS_BEFORE_SETTLING) return@map next
            }
            changed = true
            next.copy(status = VoiceBackgroundJob.Status.Finished)
        }
        if (changed) noticeMayBePending()
    }

    // endregion

    private fun pruneSettledJobs() {
        val settled = _jobs.value.filter { !it.status.isActive && it.outcomeDelivered }
        val excess = settled.size - MAXIMUM_SETTLED_JOBS
        if (excess <= 0) return
        val dropped = settled.take(excess).map { it.id }.toSet()
        _jobs.value = _jobs.value.filter { it.id !in dropped }
    }

    companion object {
        const val MAXIMUM_ACTIVE_JOBS = 3
        const val MAXIMUM_RESULT_CHARACTERS = 6_000
        const val MAXIMUM_TITLE_CHARACTERS = 60
        const val MAXIMUM_SETTLED_JOBS = 10
        const val MISSED_POLLS_BEFORE_SETTLING = 2
        const val MAXIMUM_REASON_CHARACTERS = 160

        fun isInterruptionNotice(content: String?) =
            content?.trim()?.equals("[This response was interrupted by a user correction.]", ignoreCase = true) == true

        /** A readable session title: the task itself, cut at a word boundary. */
        fun title(instructions: String): String {
            val collapsed = instructions.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (collapsed.length <= MAXIMUM_TITLE_CHARACTERS) return collapsed
            val cut = collapsed.take(MAXIMUM_TITLE_CHARACTERS)
            val space = cut.lastIndexOf(' ')
            if (space > MAXIMUM_TITLE_CHARACTERS / 2) return cut.substring(0, space) + "…"
            return "$cut…"
        }

        /** The first message of a job session. Written for the model, so not localized. */
        fun jobPrompt(instructions: String) =
            "[Background job started from a Conduit voice conversation. Nobody is watching this chat live, so work on it on your own. When you are done, end your final message with a plain-language summary that can be read aloud: what you found or did, with the key details.]\n\n$instructions"

        fun completionPrompt(title: String, result: String): String {
            val clipped = if (result.length > MAXIMUM_RESULT_CHARACTERS) result.take(MAXIMUM_RESULT_CHARACTERS) + "\n[…]" else result
            return "[Background job \"$title\" finished. Its final message is below. Tell me what it found or did, in the language we have been speaking: the substance, with the details that matter, not just a headline. Skip what doesn't work by ear, like code, long tables or links; those stay in the job's chat.]\n\n$clipped"
        }

        fun finishedNotice(title: String) = L10n.tr("%s has finished. Open it in Conduit to read the result.", title)
        fun waitingNotice(title: String) = L10n.tr("%s is waiting for your approval or an answer. Open it in Conduit to respond.", title)
        fun cancelledNotice(title: String) = L10n.tr("%s was cancelled.", title)

        fun failedNotice(title: String, reason: String): String {
            val notice = L10n.tr("%s failed. Open it in Conduit for details.", title)
            val line = reason.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (line.isEmpty()) return notice
            if (line.length <= MAXIMUM_REASON_CHARACTERS) return "$notice ($line)"
            val prefix = line.take(MAXIMUM_REASON_CHARACTERS)
            val tail = prefix.takeLast(40)
            val cut = tail.lastIndexOf(' ').let { if (it >= 0) prefix.length - tail.length + it else prefix.length }
            return "$notice (${prefix.substring(0, cut).trim()}…)"
        }
    }
}
