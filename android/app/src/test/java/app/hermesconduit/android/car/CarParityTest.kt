package app.hermesconduit.android.car

import app.hermesconduit.android.core.audio.ExternalMicrophone
import app.hermesconduit.android.core.audio.ExternalMicrophoneRelay
import app.hermesconduit.android.core.audio.Pcm
import app.hermesconduit.android.core.live.LiveVoiceHostIssue
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.ui.voice.VoiceBackgroundJob
import app.hermesconduit.android.ui.voice.live.LiveCallPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The More screen, live voice modes, Mark as read and shortcuts, against the iOS CarPlay tables. */
class CarParityTest {
    private val allPhases = listOf(
        LiveCallPhase.Idle, LiveCallPhase.Connecting, LiveCallPhase.Listening, LiveCallPhase.Speaking,
        LiveCallPhase.Paused, LiveCallPhase.Reconnecting, LiveCallPhase.Ending, LiveCallPhase.Failed("boom"),
    )

    @Test
    fun liveCallPhasesMapLikeCarPlayVoiceStateMapping() {
        // iOS map(geminiLive:) and map(gptLive:): the same table, Gemini's covering reconnecting.
        val expected = mapOf(
            LiveCallPhase.Idle to CarVoiceState.READY,
            LiveCallPhase.Connecting to CarVoiceState.PROCESSING,
            LiveCallPhase.Reconnecting to CarVoiceState.PROCESSING,
            LiveCallPhase.Paused to CarVoiceState.PROCESSING,
            LiveCallPhase.Ending to CarVoiceState.PROCESSING,
            LiveCallPhase.Listening to CarVoiceState.LISTENING,
            LiveCallPhase.Speaking to CarVoiceState.RESPONDING,
            LiveCallPhase.Failed("boom") to CarVoiceState.ERROR,
        )
        for (phase in allPhases) assertEquals(phase.toString(), expected.getValue(phase), CarVoiceState.map(phase))
        // No failure detail crosses into the car.
        assertEquals(CarVoiceState.ERROR, CarVoiceState.map(LiveCallPhase.Failed("secret transcript")))
    }

    @Test
    fun listenActionsFollowThePhase() {
        for (phase in allPhases) {
            val idle = phase == LiveCallPhase.Idle || phase is LiveCallPhase.Failed
            assertEquals(if (idle) CarGPTLiveListenAction.START else CarGPTLiveListenAction.NOTHING, CarGPTLiveListenAction.forPhase(phase))
            val gemini = when {
                idle -> CarGeminiLiveListenAction.START
                phase == LiveCallPhase.Speaking -> CarGeminiLiveListenAction.INTERRUPT
                else -> CarGeminiLiveListenAction.NOTHING
            }
            assertEquals(gemini, CarGeminiLiveListenAction.forPhase(phase))
        }
    }

    @Test
    fun liveModesOfferNoNewChatAndUnmuteRatherThanListen() {
        val live = CarVoiceControls(isClassic = false)
        assertEquals(listOf(CarVoiceButton.LISTEN), CarVoiceButton.buttons(CarVoiceState.READY, live))
        assertEquals(listOf(CarVoiceButton.LISTEN), CarVoiceButton.buttons(CarVoiceState.ERROR, live))
        assertEquals(listOf(CarVoiceButton.MUTE, CarVoiceButton.END), CarVoiceButton.buttons(CarVoiceState.LISTENING, live))
        assertEquals(listOf(CarVoiceButton.UNMUTE, CarVoiceButton.END), CarVoiceButton.buttons(CarVoiceState.RESPONDING, live.copy(isMicrophoneMuted = true)))
        // Classic: a paused microphone offers Listen.
        assertEquals(listOf(CarVoiceButton.RESUME, CarVoiceButton.END), CarVoiceButton.buttons(CarVoiceState.LISTENING, CarVoiceControls(isMicrophoneMuted = true)))
        assertEquals("Unmute", CarVoiceButton.UNMUTE.title)
    }

    @Test
    fun voiceModeComesFromTheProfileAndOnlyOneIsOn() {
        val classic = VoiceProfilePreferences()
        assertEquals(CarVoiceMode.CLASSIC, CarVoiceMode.of(classic))
        for (mode in CarVoiceMode.entries) {
            val applied = CarVoiceMode.applying(mode, classic.copy(geminiLiveEnabled = true, gptLiveEnabled = true, grokLiveEnabled = true))
            assertEquals(mode, CarVoiceMode.of(applied))
            assertEquals(listOf(applied.geminiLiveEnabled, applied.gptLiveEnabled, applied.grokLiveEnabled).count { it }, if (mode == CarVoiceMode.CLASSIC) 0 else 1)
        }
        assertNull(CarVoiceMode.CLASSIC.live)
        assertEquals(LiveVoiceMode.GROK_LIVE, CarVoiceMode.GROK_LIVE.live)
        assertEquals(listOf("Classic voice", "Gemini Live", "GPT-Live", "Grok Live"), CarVoiceMode.entries.map { it.title })
    }

    @Test
    fun liveIssuesNameTheHostBeforeTheMicrophone() {
        assertEquals(CarVoiceIssue.NOT_CONNECTED, CarVoiceIssue.ofLive(false, LiveVoiceMode.GPT_LIVE, LiveVoiceHostIssue.NOT_SET_UP, true))
        assertEquals(CarVoiceIssue.NOTIFIER_PLUGIN_MISSING, CarVoiceIssue.ofLive(true, LiveVoiceMode.GEMINI_LIVE, LiveVoiceHostIssue.PLUGIN_MISSING, true))
        assertEquals(CarVoiceIssue.GEMINI_LIVE_NOT_SET_UP, CarVoiceIssue.ofLive(true, LiveVoiceMode.GEMINI_LIVE, LiveVoiceHostIssue.NOT_SET_UP, true))
        assertEquals(CarVoiceIssue.GPT_LIVE_NOT_SET_UP, CarVoiceIssue.ofLive(true, LiveVoiceMode.GPT_LIVE, LiveVoiceHostIssue.NOT_SET_UP, false))
        assertEquals(CarVoiceIssue.GROK_LIVE_NOT_SET_UP, CarVoiceIssue.ofLive(true, LiveVoiceMode.GROK_LIVE, LiveVoiceHostIssue.NOT_SET_UP, false))
        assertEquals(CarVoiceIssue.MICROPHONE_DENIED, CarVoiceIssue.ofLive(true, LiveVoiceMode.GROK_LIVE, null, true))
        assertNull(CarVoiceIssue.ofLive(true, LiveVoiceMode.GROK_LIVE, null, false))
        assertEquals(listOf("Grok Live isn't set up on Hermes", "Grok Live not set up"), CarVoiceIssue.GROK_LIVE_NOT_SET_UP.titleVariants)
        assertEquals(listOf("Update the Hermes notifier plugin", "Plugin update needed"), CarVoiceIssue.NOTIFIER_PLUGIN_MISSING.titleVariants)
    }

    private fun job(id: String, status: VoiceBackgroundJob.Status, startedAt: Long, threadTurn: Boolean = false) =
        VoiceBackgroundJob(id = id, title = "Job $id", instructions = "do $id", status = status, startedAtMs = startedAt, isThreadTurn = threadTurn)

    @Test
    fun jobRowsAreNewestFirstWithoutThreadTurns() {
        val rows = CarBrowse.jobRows(
            listOf(
                job("a", VoiceBackgroundJob.Status.Finished, 1),
                job("b", VoiceBackgroundJob.Status.Running, 3),
                job("c", VoiceBackgroundJob.Status.Failed("x"), 2),
                job("t", VoiceBackgroundJob.Status.Finished, 9, threadTurn = true),
            ),
        )
        assertEquals(listOf("b", "c", "a"), rows.map { it.id })
        assertEquals(listOf("Running", "Failed", "Done"), rows.map { it.status })
        assertEquals(listOf(false, true, true), rows.map { it.canReplay })
        assertEquals(listOf(CarJobKind.RUNNING, CarJobKind.FAILED, CarJobKind.DONE), rows.map { it.kind })
        assertEquals("Waiting for you", CarBrowse.statusText(VoiceBackgroundJob.Status.NeedsInput))
        assertEquals("Running", CarBrowse.statusText(VoiceBackgroundJob.Status.Starting))
        assertEquals("Cancelled", CarBrowse.statusText(VoiceBackgroundJob.Status.Cancelled))
        assertEquals(CarJobKind.WAITING, CarJobKind.of(VoiceBackgroundJob.Status.NeedsInput))
    }

    @Test
    fun voiceOptionsMarkWhatIsInUse() {
        val modes = CarBrowse.modeRows(CarVoiceMode.GPT_LIVE)
        assertEquals(CarVoiceMode.entries, modes.map { it.mode })
        assertEquals(listOf(false, false, true, false), modes.map { it.isSelected })
        // One profile: no choice that does nothing.
        assertTrue(CarBrowse.agentRows(listOf("default"), "default").isEmpty())
        val agents = CarBrowse.agentRows(listOf("default", "work_bot"), "work_bot")
        assertEquals(listOf("Default profile", "Work bot"), agents.map { it.title })
        assertEquals(listOf("default", "work_bot"), agents.map { it.id })
        assertEquals(listOf(false, true), agents.map { it.isSelected })
    }

    @Test
    fun markAsReadClearsTheRowByAnyOfItsIds() {
        val row = CarChatRow("stored", "runtime", "Chat", "Hi", 1L, unread = true, alternateIds = setOf("alias"))
        val other = row.copy(sessionId = "other", runtimeId = null, alternateIds = emptySet())
        val list = CarChatList(listOf(row), listOf(other))
        val read = list.markingRead("runtime")
        assertFalse(read.pinned.single().unread)
        assertTrue(read.recent.single().unread)
        assertTrue(read.markingRead("alias", unread = true).pinned.single().unread)
    }

    @Test
    fun aChatRowIsTheThreadALiveCallWorksIn() {
        val thread = CarChatRow("stored", "runtime", "Groceries", "", null, false).thread
        assertEquals("runtime", thread.runtimeSessionId)
        assertEquals("stored", thread.storedSessionId)
        assertEquals("Groceries", thread.title)
        assertEquals("stored", CarChatRow("stored", null, "T", "", null, false).thread.runtimeSessionId)
    }

    @Test
    fun shortcutsAreTrimmedCappedAndReplacedById() {
        assertNull(CarShortcuts.saving(emptyList(), CarShortcut("1", "  ", "prompt")))
        assertNull(CarShortcuts.saving(emptyList(), CarShortcut("1", "Title", " \n")))
        val one = CarShortcuts.saving(emptyList(), CarShortcut("1", " Morning brief ", " What's on today? "))!!
        assertEquals(listOf(CarShortcut("1", "Morning brief", "What's on today?")), one)
        val edited = CarShortcuts.saving(one, CarShortcut("1", "Brief", "Today"))!!
        assertEquals(listOf(CarShortcut("1", "Brief", "Today")), edited)
        val full = (1..CarPreferences.MAXIMUM_SHORTCUTS).map { CarShortcut("$it", "T$it", "P$it") }
        assertFalse(CarShortcuts.canAdd(full))
        assertNull(CarShortcuts.saving(full, CarShortcut("new", "T", "P")))
        // Editing one in a full grid still works.
        assertEquals("Edited", CarShortcuts.saving(full, CarShortcut("3", "Edited", "P"))!![2].title)
    }

    @Test
    fun shortcutsRoundTripAndDropWhatIsUnreadable() {
        val shortcuts = listOf(CarShortcut("a", "Brief \"quoted\"", "Line one\nline two"), CarShortcut("b", "Home", "Lights off"))
        assertEquals(shortcuts, CarShortcuts.decode(CarShortcuts.encode(shortcuts)))
        assertTrue(CarShortcuts.decode("").isEmpty())
        assertTrue(CarShortcuts.decode("not json").isEmpty())
        assertEquals(listOf("ok"), CarShortcuts.decode("""[{"id":"x","title":"","prompt":"p"},{"id":"ok","title":"T","prompt":"P"},3]""").map { it.id })
        val many = (1..12).map { CarShortcut("$it", "T", "P") }
        assertEquals(CarPreferences.MAXIMUM_SHORTCUTS, CarShortcuts.decode(CarShortcuts.encode(many)).size)
    }

    @Test
    fun aLiveCallOutlivesTheCarOnlyWhereThePhoneShowsIt() {
        assertTrue(CarSurfaces.keepsLiveCall(isPhoneOnScreen = true, isCallScreenShown = true, isMinimisedBarShown = false))
        assertTrue(CarSurfaces.keepsLiveCall(isPhoneOnScreen = true, isCallScreenShown = false, isMinimisedBarShown = true))
        assertFalse(CarSurfaces.keepsLiveCall(isPhoneOnScreen = true, isCallScreenShown = false, isMinimisedBarShown = false))
        assertFalse(CarSurfaces.keepsLiveCall(isPhoneOnScreen = false, isCallScreenShown = true, isMinimisedBarShown = true))
    }

    @Test
    fun theRelayHandsOverTheCarsAudioAndSilenceWhereThereIsNone() {
        val relay = ExternalMicrophoneRelay(object : ExternalMicrophone {
            override fun start() = Unit
            override fun read(buffer: ByteArray, offset: Int, size: Int) = -1
            override fun stop() = Unit
        }, capacityMs = 1)
        // 1 ms at 16 kHz PCM16 is 32 bytes: older audio is dropped first.
        relay.write(ByteArray(40) { (it + 1).toByte() }, 40)
        val target = ByteArray(36) { 99 }
        relay.fill(target)
        assertArrayEquals(ByteArray(32) { (it + 9).toByte() } + ByteArray(4), target)
        // Nothing left: all silence.
        val empty = ByteArray(8) { 7 }
        relay.fill(empty)
        assertArrayEquals(ByteArray(8), empty)
    }

    @Test
    fun ringTakesTheOldestBytes() {
        val ring = Pcm.Ring(4)
        ring.append(byteArrayOf(1, 2, 3, 4, 5))
        val out = ByteArray(3)
        assertEquals(3, ring.take(out))
        assertArrayEquals(byteArrayOf(2, 3, 4), out)
        assertEquals(1, ring.size)
        ring.append(byteArrayOf(6))
        assertEquals(2, ring.take(out, 3))
        assertArrayEquals(byteArrayOf(5, 6), out.copyOf(2))
    }
}
