package app.hermesconduit.android.voice

import app.hermesconduit.android.core.audio.BargeInRoutePolicy
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.VoiceBackgroundJobCommand
import app.hermesconduit.android.model.VoiceJobProfileTarget
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.ui.voice.LiveSessionStatus
import app.hermesconduit.android.ui.voice.ReadAloudController
import app.hermesconduit.android.ui.voice.ReadAloudNowPlaying
import app.hermesconduit.android.ui.voice.ReadAloudRemoteCommands
import app.hermesconduit.android.ui.voice.VoiceAssistantEvent
import app.hermesconduit.android.ui.voice.VoiceBackgroundJob
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobBackend
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobNotice
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobSupervisor
import app.hermesconduit.android.ui.voice.VoiceConversationController
import app.hermesconduit.android.ui.voice.VoiceConversationHost
import app.hermesconduit.android.ui.voice.VoiceConversationState
import app.hermesconduit.android.ui.voice.VoiceSteerOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBackgroundJobSupervisorTest {
    private class Backend {
        var created = 0
        val createdProfiles = mutableListOf<String?>()
        val titles = mutableListOf<Pair<String, String>>()
        val submissions = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        var createError: Exception? = null
        var submitError: Exception? = null
        var cancelError: Exception? = null
        var liveRows = listOf<LiveSessionStatus>()
        var profileTargets = mapOf<String, VoiceJobProfileTarget>()
        var onSubmit: ((String) -> Unit)? = null

        val backend = VoiceBackgroundJobBackend(
            createSession = { profile ->
                createdProfiles += profile
                createError?.let { throw it }
                created++
                "rt-$created" to "st-$created"
            },
            setTitle = { id, title -> titles += id to title },
            submit = { id, text ->
                submissions += id to text
                onSubmit?.invoke(id)
                submitError?.let { throw it }
            },
            cancel = { id ->
                cancelError?.let { throw it }
                cancelled += id
            },
            liveSessions = { liveRows },
            resolveProfile = { name -> profileTargets[name.lowercase()] ?: VoiceJobProfileTarget.Unknown },
        )
    }

    private fun TestScope.make(): Pair<VoiceBackgroundJobSupervisor, Backend> {
        val fake = Backend()
        return VoiceBackgroundJobSupervisor(backgroundScope, fake.backend, pollIntervalMs = 3_600_000) to fake
    }

    @Test
    fun startingAJobCreatesATitledSessionAndSubmitsTheTask() = runTest {
        val (supervisor, fake) = make()
        val created = mutableListOf<String>()
        supervisor.onJobSessionCreated = { created += it }
        val reply = supervisor.performVoiceCommand(VoiceBackgroundJobCommand.Start("review the PR"))
        assertEquals("Started a background job: review the PR.", reply)
        assertEquals(listOf("rt-1" to "review the PR"), fake.titles)
        assertTrue(fake.submissions.single().second.endsWith("review the PR"))
        assertTrue(fake.submissions.single().second.startsWith("[Background job started from a Conduit voice conversation."))
        assertEquals(listOf("rt-1", "st-1"), created)
        assertEquals(VoiceBackgroundJob.Status.Running, supervisor.jobs.value.single().status)
    }

    @Test
    fun leadingProfileNameRoutesTheJob() = runTest {
        val (supervisor, fake) = make()
        fake.profileTargets = mapOf("fam" to VoiceJobProfileTarget.Other("fam"), "default" to VoiceJobProfileTarget.Active)
        val reply = supervisor.startJob("for Fam, check the router")
        assertEquals(listOf<String?>("fam"), fake.createdProfiles)
        assertEquals("check the router", supervisor.jobs.value.first().title)
        assertTrue(reply.contains("Fam"))
        supervisor.startJob("for the report, check last week's numbers")
        assertEquals("for the report, check last week's numbers", supervisor.jobs.value.last().instructions)
        val unknown = supervisor.startJob("check the server", spokenProfile = "Nobody")
        assertTrue(unknown.contains("Nobody"))
        assertEquals(2, supervisor.jobs.value.size)
    }

    @Test
    fun finishedJobIsHandedBackOnceWithItsResult() = runTest {
        val (supervisor, _) = make()
        var pending = 0
        supervisor.onNoticePending = { pending++ }
        supervisor.startJob("review the PR")
        supervisor.observe(StreamEvent.MessageComplete("other", null, "not ours", null))
        assertEquals(VoiceBackgroundJob.Status.Running, supervisor.jobs.value.single().status)
        supervisor.observe(StreamEvent.MessageComplete("st-1", null, "Looks good.", null))
        assertEquals(VoiceBackgroundJob.Status.Finished, supervisor.jobs.value.single().status)
        assertEquals(1, pending)
        val notice = supervisor.takePendingNotice() as VoiceBackgroundJobNotice.Submit
        assertTrue(notice.prompt.contains("review the PR"))
        assertTrue(notice.prompt.contains("Looks good."))
        assertTrue(notice.fallback.contains("review the PR"))
        assertNull(supervisor.takePendingNotice())
        val id = supervisor.jobs.value.single().id
        assertTrue(supervisor.replayOutcome(id))
        assertTrue(supervisor.takePendingNotice() is VoiceBackgroundJobNotice.Submit)
        assertNull(supervisor.takePendingNotice())
    }

    @Test
    fun approvalAnnouncesNeedsInputOncePerEpisode() = runTest {
        val (supervisor, _) = make()
        supervisor.startJob("clean the logs")
        val approval = app.hermesconduit.android.model.MessageNormalizer.approvalRequest(
            kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("rm -rf /var/log/old")) },
            "rt-1",
        )!!
        supervisor.observe(StreamEvent.Approval("rt-1", approval))
        assertEquals(VoiceBackgroundJob.Status.NeedsInput, supervisor.jobs.value.single().status)
        val speak = supervisor.takePendingNotice() as VoiceBackgroundJobNotice.Speak
        assertTrue(speak.text.contains("clean the logs"))
        assertNull(supervisor.takePendingNotice())
        supervisor.observe(StreamEvent.ToolStart("rt-1", "terminal", null, null))
        assertEquals(VoiceBackgroundJob.Status.Running, supervisor.jobs.value.single().status)
    }

    @Test
    fun limitsCancelsAndFailures() = runTest {
        val (supervisor, fake) = make()
        repeat(VoiceBackgroundJobSupervisor.MAXIMUM_ACTIVE_JOBS) { supervisor.startJob("job $it") }
        val refused = supervisor.startJob("one too many")
        assertTrue(refused.contains("${VoiceBackgroundJobSupervisor.MAXIMUM_ACTIVE_JOBS}"))
        assertEquals(VoiceBackgroundJobSupervisor.MAXIMUM_ACTIVE_JOBS, fake.created)
        val reply = supervisor.cancelAll()
        assertTrue(reply.contains("3"))
        assertEquals(listOf("rt-1", "rt-2", "rt-3"), fake.cancelled)
        supervisor.observe(StreamEvent.Interrupted("rt-1"))
        assertNull("the user already heard the cancellation", supervisor.takePendingNotice())
        assertEquals("There are no background jobs to cancel.", supervisor.cancelAll())

        val (other, failing) = make()
        failing.createError = IllegalStateException("offline")
        assertEquals("Couldn't start the background job.", other.startJob("anything"))
        assertEquals(0, other.activeJobCount)
        assertNull(other.takePendingNotice())

        val (third, lost) = make()
        lost.submitError = IllegalStateException("timeout")
        assertEquals("Couldn't start the background job.", third.startJob("lost ack"))
        assertEquals(listOf("rt-1"), lost.cancelled)

        val (fourth, refusing) = make()
        fourth.startJob("first")
        refusing.cancelError = IllegalStateException("not connected")
        val cancel = fourth.cancel(fourth.jobs.value.single().id)
        assertTrue(cancel!!.startsWith("Couldn't cancel"))
        assertTrue(fourth.jobs.value.single().status.isActive)
    }

    @Test
    fun livenessPollSettlesAJobWhoseCompletionWasMissed() = runTest {
        val (supervisor, fake) = make()
        supervisor.startJob("still going")
        supervisor.startJob("gone quiet")
        fake.liveRows = listOf(LiveSessionStatus("rt-1", "st-1", "working"))
        supervisor.pollOnce()
        assertEquals(listOf(VoiceBackgroundJob.Status.Running, VoiceBackgroundJob.Status.Running), supervisor.jobs.value.map { it.status })
        supervisor.pollOnce()
        assertEquals(listOf(VoiceBackgroundJob.Status.Running, VoiceBackgroundJob.Status.Finished), supervisor.jobs.value.map { it.status })
        val notice = supervisor.takePendingNotice() as VoiceBackgroundJobNotice.Speak
        assertTrue(notice.text.contains("gone quiet"))
        fake.liveRows = listOf(LiveSessionStatus("rt-1", "st-1", "idle"))
        supervisor.pollOnce()
        assertEquals(VoiceBackgroundJob.Status.Finished, supervisor.jobs.value.first().status)
    }

    @Test
    fun statusSummaryAndTitles() = runTest {
        val (supervisor, _) = make()
        assertEquals("No background jobs are running.", supervisor.statusSummary())
        supervisor.startJob("check the build")
        assertEquals("check the build is still running.", supervisor.statusSummary())
        val title = VoiceBackgroundJobSupervisor.title("word ".repeat(30))
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= VoiceBackgroundJobSupervisor.MAXIMUM_TITLE_CHARACTERS + 1)
        assertEquals("short task", VoiceBackgroundJobSupervisor.title("  short\n task "))
        assertTrue(VoiceBackgroundJobSupervisor.failedNotice("x", "line one\nline two").endsWith("(line one)"))
    }
}

class ReadAloudControllerTest {
    private class NowPlaying : ReadAloudNowPlaying {
        var begun = 0
        var ended = 0
        var paused: Boolean? = null
        var commands: ReadAloudRemoteCommands? = null
        override fun begin(title: String, commands: ReadAloudRemoteCommands) {
            begun++
            this.commands = commands
        }
        override fun setPaused(paused: Boolean) { this.paused = paused }
        override fun end() { ended++ }
    }

    @Test
    fun readsOneMessageAndSettles() = runTest {
        val playback = FakePlayback()
        val gateway = FakeGateway()
        val nowPlaying = NowPlaying()
        val controller = ReadAloudController(backgroundScope, playback, nowPlaying, playbackRate = { 1.5f })
        controller.setGateway(gateway)
        controller.toggle("m1", "*waves happily at everyone* Hello there 🎉")
        runCurrent()
        assertEquals(listOf("Hello there"), gateway.streams.single().appended.map { it.trim() })
        assertEquals(1.5f, playback.lastRate)
        assertEquals(1, nowPlaying.begun)
        assertEquals(ReadAloudController.State.Idle, controller.state.value)
        assertTrue(nowPlaying.ended >= 1)
    }

    @Test
    fun tappingTheActiveMessageStopsAndOthersTakeOver() = runTest {
        val playback = FakePlayback()
        val gateway = FakeGateway().apply { autoFinish = false }
        val controller = ReadAloudController(backgroundScope, playback, NowPlaying(), playbackRate = { 1f })
        controller.setGateway(gateway)
        controller.toggle("m1", "First reply")
        runCurrent()
        assertTrue(controller.isActiveMessage("m1"))
        controller.toggle("m2", "Second reply")
        runCurrent()
        assertTrue(gateway.streams[0].cancelled)
        assertTrue(controller.isActiveMessage("m2"))
        controller.toggle("m2", "Second reply")
        assertEquals(ReadAloudController.State.Idle, controller.state.value)
        assertTrue(gateway.streams[1].cancelled)
        // Nothing readable never interrupts.
        controller.toggle("m3", "Third")
        runCurrent()
        controller.toggle("m4", "MEDIA:/tmp/a.png")
        assertTrue(controller.isActiveMessage("m3"))
    }

    @Test
    fun pausesAndResumesFromTheMediaSession() = runTest {
        val playback = FakePlayback()
        val gateway = FakeGateway().apply { autoFinish = false }
        val nowPlaying = NowPlaying()
        val controller = ReadAloudController(backgroundScope, playback, nowPlaying, playbackRate = { 1f })
        controller.setGateway(gateway)
        assertFalse(controller.pause())
        controller.toggle("m1", "A reply")
        runCurrent()
        assertTrue(controller.state.value is ReadAloudController.State.Preparing)
        gateway.streams.single().emitAudio()
        assertEquals(ReadAloudController.State.Playing("m1"), controller.state.value)
        assertEquals(1, nowPlaying.begun)
        val commands = nowPlaying.commands!!
        assertTrue(commands.pause())
        assertTrue(controller.isPaused.value)
        assertEquals(true, nowPlaying.paused)
        assertTrue(commands.togglePause())
        assertFalse(controller.isPaused.value)
        assertEquals(false, nowPlaying.paused)
        assertTrue(commands.stop())
        assertEquals(ReadAloudController.State.Idle, controller.state.value)
        assertFalse(commands.stop())
    }

    @Test
    fun noGatewayFailsWithAMessage() = runTest {
        var reported: String? = null
        val controller = ReadAloudController(backgroundScope, FakePlayback(), NowPlaying(), playbackRate = { 1f }, reportError = { reported = it })
        controller.toggle("m1", "Hello")
        runCurrent()
        assertTrue(controller.state.value is ReadAloudController.State.Failed)
        assertTrue(reported!!.startsWith("Read aloud needs a connection to Hermes."))
    }
}

class VoiceConversationControllerTest {
    private class Host : VoiceConversationHost {
        val submitted = mutableListOf<String>()
        var interrupts = 0
        var ended = 0
        var steerOutcome = VoiceSteerOutcome.NO_RUNNING_TURN
        override suspend fun submit(text: String): Boolean {
            submitted += text
            return true
        }
        override suspend fun interrupt(): Boolean {
            interrupts++
            return true
        }
        override suspend fun steer(text: String) = steerOutcome
        override fun ownsSession(sessionId: String) = sessionId == "rt"
        override fun endConversation() { ended++ }
    }

    private class Harness(scope: TestScope) {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val gateway = FakeGateway()
        val host = Host()
        var time = 0L
        val controller = VoiceConversationController(
            scope.backgroundScope, capture, playback, null, { BargeInRoutePolicy.FULL_DUPLEX }, clock = { time },
        ).also {
            it.setGateway(gateway)
            it.host = host
            it.setProfilePreferences(VoiceProfilePreferences())
            it.beginVoiceTurn()
        }

        /** Speech then trailing silence, as level events. */
        fun speak(scope: TestScope) {
            controller.ingestAudioLevel(0.2f, time)
            time += 100
            controller.ingestAudioLevel(0.2f, time)
            time += 1_300
            controller.ingestAudioLevel(0.001f, time)
            scope.runCurrent()
        }
    }

    @Test
    fun anUtteranceIsTranscribedSubmittedAndItsReplySpoken() = runTest {
        val h = Harness(this)
        h.controller.startListening()
        runCurrent()
        assertEquals(VoiceConversationState.LISTENING, h.controller.state.value)
        h.gateway.transcripts.addLast("What's the weather?")
        h.speak(this)
        assertEquals(listOf("What's the weather?"), h.host.submitted)
        assertEquals(VoiceConversationState.THINKING, h.controller.state.value)
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Started("rt"))
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Delta("rt", "Sunny *grins widely at you now* and warm."))
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Completed("rt", "Sunny *grins widely at you now* and warm."))
        runCurrent()
        val spoken = h.gateway.streams.single().appended.joinToString("")
        assertFalse(spoken.contains("grins"))
        assertTrue(spoken.contains("Sunny"))
        assertTrue(h.playback.pcmBytes > 0)
        // Continuous conversation: the next listening window opens.
        assertEquals(VoiceConversationState.LISTENING, h.controller.state.value)
        assertEquals(listOf(true, false), h.controller.conversationTranscript.value.map { it.isUser })
        assertEquals("Sunny *grins widely at you now* and warm.", h.controller.conversationTranscript.value.last().text)
    }

    @Test
    fun eventsForOtherSessionsAreIgnored() = runTest {
        val h = Harness(this)
        h.controller.startListening()
        runCurrent()
        h.gateway.transcripts.addLast("hello")
        h.speak(this)
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Started("someone-else"))
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Completed("someone-else", "not ours"))
        runCurrent()
        assertTrue(h.gateway.streams.isEmpty())
        assertEquals(VoiceConversationState.THINKING, h.controller.state.value)
    }

    @Test
    fun spokenStopInterruptsAndRelistensAndEndClosesTheConversation() = runTest {
        val h = Harness(this)
        h.controller.startListening()
        runCurrent()
        h.gateway.transcripts.addLast("Stop.")
        h.speak(this)
        assertEquals(1, h.host.interrupts)
        assertTrue(h.host.submitted.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, h.controller.state.value)

        h.gateway.transcripts.addLast("Goodbye!")
        h.speak(this)
        assertEquals(1, h.host.ended)
        assertTrue(h.host.submitted.isEmpty())
    }

    @Test
    fun anEmptyTranscriptListensAgainAndIdleSilencePausesWithoutContinuous() = runTest {
        val h = Harness(this)
        h.controller.startListening()
        runCurrent()
        h.speak(this)
        assertEquals(1, h.gateway.transcribed)
        assertEquals(VoiceConversationState.LISTENING, h.controller.state.value)

        h.controller.setProfilePreferences(VoiceProfilePreferences(continuousConversation = false))
        h.controller.startListening()
        runCurrent()
        h.controller.ingestAudioLevel(0.001f, h.time)
        h.time += 13_000
        h.controller.ingestAudioLevel(0.001f, h.time)
        assertTrue(h.controller.isMicrophonePaused.value)
    }

    @Test
    fun aBackgroundJobCommandIsHandledLocally() = runTest {
        val capture = FakeCapture()
        val gateway = FakeGateway()
        val host = Host()
        val jobs = object : app.hermesconduit.android.ui.voice.VoiceBackgroundJobHandling {
            val commands = mutableListOf<VoiceBackgroundJobCommand>()
            override suspend fun performVoiceCommand(command: VoiceBackgroundJobCommand): String {
                commands += command
                return "No background jobs are running."
            }
            override fun takePendingNotice(): VoiceBackgroundJobNotice? = null
        }
        var time = 0L
        val controller = VoiceConversationController(backgroundScope, capture, FakePlayback(), null, { BargeInRoutePolicy.FULL_DUPLEX }, backgroundJobs = jobs, clock = { time })
        controller.setGateway(gateway)
        controller.host = host
        controller.beginVoiceTurn()
        controller.startListening()
        runCurrent()
        gateway.transcripts.addLast("Job status.")
        controller.ingestAudioLevel(0.2f, time)
        time += 1_400
        controller.ingestAudioLevel(0.001f, time)
        runCurrent()
        assertEquals(listOf<VoiceBackgroundJobCommand>(VoiceBackgroundJobCommand.Status), jobs.commands)
        assertTrue(host.submitted.isEmpty())
        assertEquals("No background jobs are running.", gateway.streams.single().appended.joinToString(""))
    }

    @Test
    fun mutingStopsSpeechAndAFailureSettles() = runTest {
        val h = Harness(this)
        h.controller.startListening()
        runCurrent()
        h.gateway.transcripts.addLast("Tell me a story")
        h.speak(this)
        h.controller.setOutputMuted(true)
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Started("rt"))
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Delta("rt", "Once upon a time"))
        runCurrent()
        assertTrue(h.gateway.streams.isEmpty())
        h.controller.receiveAssistantEvent(VoiceAssistantEvent.Failed("rt", "Model unavailable"))
        assertEquals(VoiceConversationState.FAILED, h.controller.state.value)
        assertEquals("Model unavailable", h.controller.failure.value)
        h.controller.stop()
        assertEquals(VoiceConversationState.IDLE, h.controller.state.value)
        assertNull(h.controller.failure.value)
    }
}
