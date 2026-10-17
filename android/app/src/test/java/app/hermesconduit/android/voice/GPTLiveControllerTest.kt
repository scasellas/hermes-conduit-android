package app.hermesconduit.android.voice

import app.hermesconduit.android.core.live.GPTLiveAvailability
import app.hermesconduit.android.core.live.GPTLiveBilling
import app.hermesconduit.android.core.live.GPTLiveProtocol
import app.hermesconduit.android.core.live.GPTLiveProtocol.ServerEvent
import app.hermesconduit.android.core.live.GPTLiveSessionControlling
import app.hermesconduit.android.core.live.GPTLiveSessionState
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.ui.voice.LiveSessionStatus
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobBackend
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobSupervisor
import app.hermesconduit.android.ui.voice.live.GPTLiveConversationController
import app.hermesconduit.android.ui.voice.live.LiveCallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeGPTSession : GPTLiveSessionControlling {
    override var onEvent: ((ServerEvent) -> Unit)? = null
    override var onStateChange: ((GPTLiveSessionState) -> Unit)? = null
    override var onAudioPaused: ((Boolean) -> Unit)? = null
    override var isReady = false
    override var voiceNote: String? = null
    override var briefingApplied = false
    override var greetingApplied = false
    override var billing = GPTLiveBilling.SUBSCRIPTION
    var started = 0
    var stopped = 0
    val appended = mutableListOf<Triple<String, GPTLiveProtocol.Channel, String?>>()
    var microphoneEnabled: Boolean? = null

    override fun start() { started++ }
    override fun stop() { stopped++; isReady = false }
    override fun appendContext(text: String, channel: GPTLiveProtocol.Channel, delegationId: String?): Boolean {
        appended += Triple(text, channel, delegationId)
        return true
    }
    override fun setMicrophoneEnabled(enabled: Boolean) { microphoneEnabled = enabled }
    override fun setSpeakerEnabled(enabled: Boolean) {}

    fun becomeReady() {
        isReady = true
        onStateChange?.invoke(GPTLiveSessionState.Ready)
    }

    val speakable get() = appended.filter { it.second == GPTLiveProtocol.Channel.SPEAKABLE }
}

class GPTLiveControllerTest {
    private class Jobs {
        val submissions = mutableListOf<Pair<String, String>>()
        var created = 0
        val backend = VoiceBackgroundJobBackend(
            createSession = { created++; "rt-$created" to "st-$created" },
            setTitle = { _, _ -> },
            submit = { id, text -> submissions += id to text },
            cancel = { },
            liveSessions = { emptyList<LiveSessionStatus>() },
        )
    }

    private class Harness(scope: CoroutineScope, clock: () -> Long, available: Boolean = true, endPhrases: List<String> = emptyList()) {
        val session = FakeGPTSession()
        val jobs = Jobs()
        val supervisor = VoiceBackgroundJobSupervisor(scope, jobs.backend, pollIntervalMs = 3_600_000, clock = clock)
        val controller = GPTLiveConversationController(
            scope = scope,
            makeSession = { session },
            availability = { if (available) GPTLiveAvailability.Available("model", "cove") else GPTLiveAvailability.Unavailable("no sign-in") },
            briefing = { "[rules]" },
            supervisor = supervisor,
            hasMicrophonePermission = { true },
            now = clock,
            endConversationPhrases = { endPhrases },
        )
    }

    private fun TestScope.harness(clock: () -> Long = { 1_000_000L }, available: Boolean = true, endPhrases: List<String> = emptyList()) =
        Harness(backgroundScope, clock, available, endPhrases)

    @Test
    fun briefsTheModelOnReadyWhenTheHostDidNotTakeIt() = runTest {
        val h = harness()
        h.controller.start()
        assertEquals(1, h.session.started)
        assertEquals(LiveCallPhase.Connecting, h.controller.phase)
        h.session.becomeReady()
        assertEquals(LiveCallPhase.Listening, h.controller.phase)
        assertEquals("[rules]", h.session.appended.first().first)
        assertEquals(GPTLiveProtocol.Channel.COMMENTARY, h.session.appended.first().second)
        assertEquals(true, h.session.microphoneEnabled)
        h.controller.setMicrophoneMuted(true)
        assertEquals(false, h.session.microphoneEnabled)
        h.controller.stop()
        assertEquals(LiveCallPhase.Idle, h.controller.phase)
        assertEquals(1, h.session.stopped)
    }

    @Test
    fun unavailableHostFailsWithTheReasonAndNeverCalls() = runTest {
        val h = harness(available = false)
        h.controller.start()
        assertTrue(h.controller.phase is LiveCallPhase.Failed)
        assertEquals(0, h.session.started)
    }

    @Test
    fun transcriptFragmentsJoinAsTheyComeAndTurnDoneFoldsInterleavedTurns() = runTest {
        val h = harness()
        h.controller.start()
        h.session.becomeReady()
        for (fragment in listOf("Hel", "lo", " the", "re.")) h.session.onEvent?.invoke(ServerEvent.OutputTranscript(fragment))
        assertEquals("Hello there.", h.controller.transcript.last().text)
        assertEquals(LiveCallPhase.Speaking, h.controller.phase)
        h.session.onEvent?.invoke(ServerEvent.TurnDone("assistant", "Hello there."))

        h.session.onEvent?.invoke(ServerEvent.InputTranscript("what's the"))
        h.session.onEvent?.invoke(ServerEvent.OutputTranscript("Let me"))
        h.session.onEvent?.invoke(ServerEvent.InputTranscript("weather"))
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "What's the weather?"))
        h.session.onEvent?.invoke(ServerEvent.OutputTranscript("check."))
        h.session.onEvent?.invoke(ServerEvent.TurnDone("assistant", "Let me check."))
        assertEquals(listOf("Hello there.", "What's the weather?", "Let me check."), h.controller.transcript.map { it.text })
        assertEquals(listOf(false, true, false), h.controller.transcript.map { it.fromUser })

        h.session.onEvent?.invoke(ServerEvent.OutputTranscript("Working on"))
        h.session.onEvent?.invoke(ServerEvent.TurnDone("system", "internal"))
        assertEquals(LiveCallPhase.Speaking, h.controller.phase)
        assertFalse(h.controller.transcript.any { it.text == "internal" })
        h.controller.stop()
    }

    @Test
    fun delegationWithoutTextHandsHermesTheUsersWordsAndItsResultAnswersIt() = runTest {
        var now = 1_000_000L
        val h = harness(clock = { now })
        h.controller.start()
        h.session.becomeReady()
        h.session.onEvent?.invoke(ServerEvent.InputTranscript("can you check"))
        h.session.onEvent?.invoke(ServerEvent.InputTranscript(" whether the build passed"))
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "Can you check whether the build passed?"))
        assertEquals(listOf("Can you check whether the build passed?"), h.controller.transcript.map { it.text })
        h.session.onEvent?.invoke(ServerEvent.OutputTranscript("On it."))
        h.session.onEvent?.invoke(ServerEvent.Delegation("del_1", ""))
        runCurrent()

        assertEquals(1, h.jobs.submissions.size)
        assertTrue(h.jobs.submissions[0].second.contains("Can you check whether the build passed?"))
        assertTrue(h.session.appended.any { it.third == "del_1" && it.second == GPTLiveProtocol.Channel.COMMENTARY })

        h.session.onEvent?.invoke(ServerEvent.TurnDone("assistant", "On it."))
        now += GPTLiveConversationController.USER_QUIET_MS + 500
        h.supervisor.observe(StreamEvent.MessageComplete("rt-1", null, "The build passed.", null))
        runCurrent()
        h.controller.deliverPendingJobUpdates()
        h.controller.flushPendingContextIfIdle()
        val result = h.session.speakable.firstOrNull { it.third == "del_1" && it.first.contains("The build passed.") }
        assertTrue("the result answers its delegation", result != null)
        assertFalse(result!!.first.contains("kept talking"))
        h.controller.stop()
    }

    @Test
    fun aDelegationResultWaitsWhileTheUserIsSpeaking() = runTest {
        var now = 1_000_000L
        val h = harness(clock = { now })
        h.controller.start()
        h.session.becomeReady()
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "Check the build."))
        h.session.onEvent?.invoke(ServerEvent.Delegation("del_1", ""))
        runCurrent()
        h.session.onEvent?.invoke(ServerEvent.TurnDone("assistant", "On it."))
        now += GPTLiveConversationController.USER_QUIET_MS + 500
        // The user starts talking again before the result lands.
        h.session.onEvent?.invoke(ServerEvent.InputTranscript("and also"))
        h.supervisor.observe(StreamEvent.MessageComplete("rt-1", null, "Passed.", null))
        runCurrent()
        h.controller.deliverPendingJobUpdates()
        h.controller.flushPendingContextIfIdle()
        assertNull(h.session.speakable.firstOrNull { it.first.contains("Passed.") })
        assertEquals(1, h.controller.pendingContextCountForTesting)

        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "And also the tests."))
        now += GPTLiveConversationController.USER_QUIET_MS + 500
        h.controller.flushPendingContextIfIdle()
        val result = h.session.speakable.first { it.first.contains("Passed.") }
        assertTrue("their words come first", result.first.startsWith(GPTLiveConversationController.RESULT_AFTER_USER_NOTE))
        h.controller.stop()
    }

    @Test
    fun endCallDelegationEndsTheCallOnceTheGoodbyeIsDone() = runTest {
        var now = 1_000_000L
        val h = harness(clock = { now }, endPhrases = listOf("goodbye"))
        var closed = 0
        h.controller.onEndConversation = { closed++ }
        h.controller.start()
        h.session.becomeReady()
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "Thanks, that's all for now."))
        h.session.onEvent?.invoke(ServerEvent.Delegation("del_1", "End call:"))
        assertEquals(LiveCallPhase.Ending, h.controller.phase)
        assertEquals(false, h.session.microphoneEnabled)
        assertTrue(h.jobs.submissions.isEmpty())
        now += GPTLiveConversationController.END_GRACE_MS + 1
        assertTrue(h.controller.finishEndIfDrained())
        assertEquals(1, closed)
        assertEquals(LiveCallPhase.Idle, h.controller.phase)
    }

    @Test
    fun aDelegationWithNothingNewStartsNoJob() = runTest {
        val h = harness()
        h.controller.start()
        h.session.becomeReady()
        val (request, words) = h.controller.delegationRequest("")
        assertEquals("", request)
        assertEquals("", words)
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "Book a table."))
        val (first, _) = h.controller.delegationRequest("")
        assertEquals("Book a table.", first.substringBefore(GPTLiveConversationController.DELEGATION_CONTEXT_MARKER))
        h.session.onEvent?.invoke(ServerEvent.TurnDone("user", "And check the weather."))
        val (second, _) = h.controller.delegationRequest("")
        assertTrue(second.startsWith("And check the weather."))
        assertTrue("an earlier request is marked handled", second.contains("User (handled separately): Book a table."))
        h.controller.stop()
    }
}
