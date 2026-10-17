package app.hermesconduit.android.car

import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.ui.voice.VoiceConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the Android Auto app, against the iOS CarPlay tables. */
class CarVoiceModelTest {
    @Test
    fun controllerStatesMapToTheFiveCarStates() {
        assertEquals(CarVoiceState.READY, CarVoiceState.map(VoiceConversationState.IDLE))
        assertEquals(CarVoiceState.LISTENING, CarVoiceState.map(VoiceConversationState.LISTENING))
        assertEquals(CarVoiceState.PROCESSING, CarVoiceState.map(VoiceConversationState.TRANSCRIBING))
        assertEquals(CarVoiceState.PROCESSING, CarVoiceState.map(VoiceConversationState.THINKING))
        assertEquals(CarVoiceState.RESPONDING, CarVoiceState.map(VoiceConversationState.SPEAKING))
        // Muted is a playback detail: still responding.
        assertEquals(CarVoiceState.RESPONDING, CarVoiceState.map(VoiceConversationState.MUTED))
        assertEquals(CarVoiceState.ERROR, CarVoiceState.map(VoiceConversationState.FAILED))
    }

    @Test
    fun titlesCarryNoUserContentButTheReadyChat() {
        assertEquals(listOf("Ready"), CarVoiceState.READY.titleVariants(CarVoiceControls()))
        assertEquals(listOf("Ready · Groceries", "Ready"), CarVoiceState.READY.titleVariants(CarVoiceControls(chatTitle = "Groceries")))
        // Only Ready names the chat.
        assertEquals(listOf("Listening…"), CarVoiceState.LISTENING.titleVariants(CarVoiceControls(chatTitle = "Groceries")))
        assertEquals(listOf("Something went wrong. See your phone.", "Something went wrong"), CarVoiceState.ERROR.titleVariants(CarVoiceControls()))
        assertEquals(
            CarVoiceIssue.VOICE_OFF.titleVariants,
            CarVoiceState.ERROR.titleVariants(CarVoiceControls(errorIssue = CarVoiceIssue.VOICE_OFF, chatTitle = "Groceries")),
        )
        // An issue only changes the Error title.
        assertEquals(listOf("Thinking…"), CarVoiceState.PROCESSING.titleVariants(CarVoiceIssue.NOT_CONNECTED))
    }

    @Test
    fun longChatTitlesAreShortenedToAGlance() {
        val title = "A very long chat title that goes on and on"
        val short = CarVoiceState.shortenedChatTitle(title)
        assertEquals("A very long chat title that…", short)
        assertEquals(CarVoiceState.MAXIMUM_CHAT_TITLE_LENGTH, short.length)
        assertEquals("Exactly twenty-eight letters", CarVoiceState.shortenedChatTitle("Exactly twenty-eight letters"))
        // Trailing spaces before the ellipsis go.
        assertEquals("abcdefghijklmnopqrstuvwxyz…", CarVoiceState.shortenedChatTitle("abcdefghijklmnopqrstuvwxyz           tail"))
        // Emoji are never cut in half.
        val emoji = "😀".repeat(30)
        assertEquals("😀".repeat(27) + "…", CarVoiceState.shortenedChatTitle(emoji))
    }

    @Test
    fun buttonsFollowTheIosTable() {
        val open = CarVoiceControls()
        val paused = CarVoiceControls(isMicrophoneMuted = true)
        assertEquals(listOf(CarVoiceButton.LISTEN, CarVoiceButton.NEW_CHAT), CarVoiceButton.buttons(CarVoiceState.READY, open))
        assertEquals(listOf(CarVoiceButton.LISTEN, CarVoiceButton.NEW_CHAT), CarVoiceButton.buttons(CarVoiceState.ERROR, paused))
        for (state in listOf(CarVoiceState.LISTENING, CarVoiceState.PROCESSING, CarVoiceState.RESPONDING)) {
            assertEquals(listOf(CarVoiceButton.MUTE, CarVoiceButton.END), CarVoiceButton.buttons(state, open))
            // A paused microphone offers Listen, not an Unmute the driver never asked for.
            assertEquals(listOf(CarVoiceButton.RESUME, CarVoiceButton.END), CarVoiceButton.buttons(state, paused))
        }
        assertEquals("Listen", CarVoiceButton.RESUME.title)
        assertTrue(CarVoiceState.entries.all { CarVoiceButton.buttons(it, open).size <= 2 })
    }

    @Test
    fun duplicateStatesAreNotForwarded() {
        assertEquals(CarVoiceState.READY, CarVoiceStateActivation.activationTarget(null, CarVoiceState.READY))
        assertNull(CarVoiceStateActivation.activationTarget(CarVoiceState.RESPONDING, CarVoiceState.map(VoiceConversationState.MUTED)))
        assertEquals(CarVoiceState.LISTENING, CarVoiceStateActivation.activationTarget(CarVoiceState.RESPONDING, CarVoiceState.LISTENING))
    }

    @Test
    fun setupIssuesNameTheFirstThingToFix() {
        fun issue(
            connected: Boolean = true,
            enabled: Boolean = true,
            micDenied: Boolean = false,
            stt: Boolean? = true,
            tts: Boolean? = true,
        ) = CarVoiceIssue.of(connected, enabled, micDenied, stt, tts)
        assertEquals(CarVoiceIssue.NOT_CONNECTED, issue(connected = false, enabled = false, micDenied = true))
        assertEquals(CarVoiceIssue.VOICE_OFF, issue(enabled = false, micDenied = true))
        assertEquals(CarVoiceIssue.MICROPHONE_DENIED, issue(micDenied = true, stt = false))
        assertEquals(CarVoiceIssue.NO_SPEECH_TO_TEXT, issue(stt = false, tts = false))
        assertEquals(CarVoiceIssue.NO_TEXT_TO_SPEECH, issue(tts = false))
        // Providers not checked yet name nothing.
        assertNull(issue(stt = null, tts = null))
        assertTrue(CarVoiceIssue.entries.all { it.titleVariants.size >= 2 })
    }

    @Test
    fun earconsSoundOnSentAndFailedOnly() {
        assertEquals(CarEarcon.SENT, CarEarcon.forTransition(CarVoiceState.LISTENING, CarVoiceState.PROCESSING))
        assertEquals(CarEarcon.FAILED, CarEarcon.forTransition(CarVoiceState.RESPONDING, CarVoiceState.ERROR))
        // Not at connect, and not again for an Error already shown.
        assertNull(CarEarcon.forTransition(null, CarVoiceState.ERROR))
        assertNull(CarEarcon.forTransition(CarVoiceState.ERROR, CarVoiceState.ERROR))
        assertNull(CarEarcon.forTransition(CarVoiceState.READY, CarVoiceState.LISTENING))
        assertNull(CarEarcon.forTransition(CarVoiceState.PROCESSING, CarVoiceState.RESPONDING))
    }

    @Test
    fun earconSamplesFadeInAndOut() {
        val sent = CarEarcon.SENT.samples()
        assertEquals((44_100 * 0.07).toInt() + (44_100 * 0.09).toInt(), sent.size)
        assertEquals(0, sent.first().toInt())
        assertTrue(kotlin.math.abs(sent[(44_100 * 0.07).toInt() - 1].toInt()) < 50)
        val peak = sent.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 15_000..(0.6 * Short.MAX_VALUE).toInt() + 1)
        assertEquals((44_100 * 0.16).toInt(), CarEarcon.FAILED.samples().size)
    }

    private fun session(
        id: String,
        title: String = id,
        lastActiveMs: Long? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        source: String = "chat",
        preview: String? = null,
    ) = SessionSummary(
        id = id, title = title, preview = preview, model = null, profile = null, pinned = pinned, archived = archived,
        unread = false, messageCount = 2, lastActiveMs = lastActiveMs, source = null, sourceKind = source,
    )

    @Test
    fun recentChatsAreNewestFirstWithUndatedOnesAfter() {
        val rows = CarBrowse.recentChats(
            listOf(session("undated1"), session("old", lastActiveMs = 1), session("archived", lastActiveMs = 9, archived = true), session("new", lastActiveMs = 5), session("undated2")),
        )
        assertEquals(listOf("new", "old", "undated1", "undated2"), rows.map { it.sessionId })
    }

    @Test
    fun pinnedChatsComeFirstAndTheListIsCapped() {
        val sessions = (1..20).map { session("c$it", lastActiveMs = it.toLong()) } +
            listOf(session("p1", lastActiveMs = 1, pinned = true), session("p2", lastActiveMs = 2, pinned = true))
        val list = CarBrowse.chatList(sessions)
        assertEquals(listOf("p2", "p1"), list.pinned.map { it.sessionId })
        assertTrue(list.pinned.all { it.kind == CarChatKind.PINNED })
        assertEquals(CarBrowse.MAXIMUM_CHATS - 2, list.recent.size)
        assertEquals("c20", list.recent.first().sessionId)
        assertEquals(CarBrowse.MAXIMUM_CHATS, list.all.size)
        // A smaller car limit is honoured too.
        assertEquals(4, CarBrowse.chatList(sessions, limit = 4).all.size)
    }

    @Test
    fun rowsAreShapedForTheCar() {
        val rows = CarBrowse.recentChats(
            listOf(
                session("a", title = "  ", preview = "  "),
                session("b", title = "Trip", preview = " Leave at 9 ", source = "voice"),
                session("c", source = "voice_job"),
                session("d", source = "cron"),
                session("e", source = "telegram"),
            ),
        )
        assertEquals("New Chat", rows[0].title)
        // A chat with no preview reads its title.
        assertEquals("New Chat", rows[0].preview)
        assertEquals("Leave at 9", rows[1].preview)
        assertEquals(
            listOf(CarChatKind.CHAT, CarChatKind.VOICE, CarChatKind.VOICE_JOB, CarChatKind.CRON, CarChatKind.CHAT),
            rows.map { it.kind },
        )
    }

    @Test
    fun rowsMatchAnyOfTheirIds() {
        val row = CarChatRow("stored", "runtime", "T", "T", null, false, alternateIds = setOf("key"))
        assertTrue(row.matches("stored") && row.matches("runtime") && row.matches("key"))
        assertTrue(!row.matches(null) && !row.matches("other"))
    }

    @Test
    fun placeholderStandsInOnlyForAnEmptyList() {
        val empty = CarChatList(emptyList(), emptyList())
        assertEquals("Connecting…", CarBrowse.placeholder(empty, isConnected = false, isLoading = true))
        assertEquals("Loading chats…", CarBrowse.placeholder(empty, isConnected = true, isLoading = true))
        assertNull(CarBrowse.placeholder(empty, isConnected = true, isLoading = false))
        val one = CarChatList(emptyList(), CarBrowse.recentChats(listOf(session("a"))))
        assertNull(CarBrowse.placeholder(one, isConnected = false, isLoading = true))
    }

    @Test
    fun orbPalettesFollowTheState() {
        assertEquals(CarOrbPalette.of(CarVoiceState.PROCESSING), CarOrbPalette.of(CarVoiceState.RESPONDING))
        assertEquals(0xFF8FB4FF.toInt(), CarOrbPalette.of(CarVoiceState.LISTENING).light)
        assertEquals(CarOrbPalette.WHITE, CarOrbPalette.of(CarVoiceState.ERROR).mark)
        assertEquals(CarOrbPalette.INK, CarOrbPalette.of(CarVoiceState.READY).mark)
    }
}
