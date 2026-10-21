package app.hermesconduit.android.voice

import android.view.KeyEvent
import app.hermesconduit.android.core.live.LiveTranscriptEntry
import app.hermesconduit.android.core.live.VoiceAppLinks
import app.hermesconduit.android.core.live.VoiceCallTitles
import app.hermesconduit.android.core.live.VoiceResumeContext
import app.hermesconduit.android.core.live.VoiceResumePlan
import app.hermesconduit.android.core.live.VoiceResumeSummary
import app.hermesconduit.android.core.live.VoiceResumeTurn
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.VoiceTranscriptOutbox
import app.hermesconduit.android.model.VoiceTranscriptSaveRequest
import app.hermesconduit.android.model.VoiceTranscriptSaveResult
import app.hermesconduit.android.model.VoiceTranscriptTurn
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.voice.VoiceHistoryClient
import app.hermesconduit.android.ui.voice.VoiceThreadTarget
import app.hermesconduit.android.ui.voice.live.HeadsetMuteKeys
import app.hermesconduit.android.ui.voice.live.VoiceCallAttachment
import app.hermesconduit.android.ui.voice.live.VoiceCallChatLink
import app.hermesconduit.android.ui.voice.live.VoiceCallChatLinks
import app.hermesconduit.android.ui.voice.live.VoiceCallDuration
import app.hermesconduit.android.ui.voice.live.VoiceChatCall
import app.hermesconduit.android.ui.voice.live.VoiceTranscriptRecorder
import app.hermesconduit.android.ui.voice.live.transcriptLabel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallLinksTest {
    private fun link(
        callId: String = "c1",
        callSessionId: String = "",
        runtime: String = "rt-chat",
        stored: String? = "chat-1",
        startedAt: Long = 2_000,
        resumed: Boolean = false,
        profile: String = "default",
    ) = VoiceCallChatLink(callId, callSessionId, runtime, stored, "Trip plans", profile, startedAt, startedAt + 65_000, resumed)

    private fun message(id: String, at: Long?) = ChatMessage(id, MessageRole.USER, id, timestampMs = at)

    @Test
    fun cardGoesWhereItsCallStarted() {
        val links = VoiceCallChatLinks().adding(link(startedAt = 2_000))
        val history = listOf(message("a", 1_000), message("b", 3_000), message("c", 4_000))
        val merged = links.merge(history, chatIds = setOf("chat-1"), openIds = setOf("chat-1"), profile = "default")
        assertEquals(listOf("a", "voice-call-c1", "b", "c"), merged.map { it.id })
        val card = merged[1]
        assertEquals(MessageRole.SYSTEM, card.role)
        assertEquals(VoiceCallChatLink.DISPLAY_KIND, card.kind)
        assertEquals("Voice call", card.content)
        // A second merge adds nothing.
        assertEquals(merged.map { it.id }, links.merge(merged, setOf("chat-1"), setOf("chat-1"), "default").map { it.id })
    }

    @Test
    fun aCallAfterEveryMessageGoesLastAndUndatedRowsNeverMoveIt() {
        val links = VoiceCallChatLinks().adding(link(startedAt = 9_000, resumed = true))
        val merged = links.merge(listOf(message("a", null), message("b", 1_000)), setOf("chat-1"), setOf("chat-1"), "default")
        assertEquals(listOf("a", "b", "voice-call-c1"), merged.map { it.id })
        assertEquals("Voice call resumed", merged.last().content)
    }

    @Test
    fun aChatWithAStoredIdIsMatchedOnItAlone() {
        val stored = link(stored = "chat-1", runtime = "rt-old")
        assertTrue(stored.belongs(setOf("chat-1")))
        // A later session can run under the old runtime id.
        assertFalse(stored.belongs(setOf("rt-old")))
        val runtimeOnly = link(stored = null, runtime = "rt-new")
        assertTrue(runtimeOnly.belongs(setOf("rt-new")))
        assertFalse(link(stored = null, runtime = "").belongs(setOf("")))
    }

    @Test
    fun otherProfilesCardsStayOut() {
        val links = VoiceCallChatLinks().adding(link(profile = "work"))
        val history = listOf(message("a", 1_000))
        assertEquals(history, links.merge(history, setOf("chat-1"), setOf("chat-1"), "default"))
    }

    @Test
    fun aCallsOwnRowLeadsBackToItsChatOnly() {
        // Two resumes of the same row from the same chat: one origin card.
        val links = VoiceCallChatLinks()
            .adding(link(callId = "c1", callSessionId = "row-9", startedAt = 2_000))
            .adding(link(callId = "c2", callSessionId = "row-9", startedAt = 5_000, resumed = true))
            .adding(link(callId = "c3", callSessionId = "", startedAt = 3_000))
        val history = listOf(message("a", 1_000), message("b", 4_000))
        // The row's ids carry the chat's too: no chat card is placed in it.
        val merged = links.merge(history, chatIds = setOf("row-9", "chat-1"), openIds = setOf("row-9"), profile = "default")
        assertEquals(listOf("a", "voice-call-from-c1", "b"), merged.map { it.id })
        assertEquals(VoiceCallChatLink.ORIGIN_DISPLAY_KIND, merged[1].kind)
        assertEquals("Started from Trip plans", merged[1].content)
    }

    @Test
    fun linksFindTheirCallAndKeepTheNewest() {
        var links = VoiceCallChatLinks()
        links = links.adding(link(callId = "c1", callSessionId = "row-1", stored = "chat-a"))
        links = links.adding(link(callId = "c2", callSessionId = "row-1", stored = "chat-b"))
        assertEquals("chat-b", links.latest("row-1", "default")?.chatStoredSessionId)
        assertNull(links.latest("row-1", "work"))
        assertEquals("c1", links.link("voice-call-from-c1")?.callId)
        assertEquals("c2", links.link("voice-call-c2")?.callId)
        // Re-adding a call replaces it.
        assertEquals(2, links.adding(link(callId = "c1", callSessionId = "row-1")).links.size)
        // Bounded.
        var many = VoiceCallChatLinks()
        repeat(VoiceCallChatLinks.MAXIMUM_LINKS + 5) { many = many.adding(link(callId = "x$it")) }
        assertEquals(VoiceCallChatLinks.MAXIMUM_LINKS, many.links.size)
        assertEquals("x5", many.links.first().callId)
    }

    @Test
    fun aLinkNamesItsChatAsAThread() {
        val bot = link(stored = null, runtime = "rt-1").copy(chatProfile = "scout")
        assertEquals(VoiceThreadTarget("rt-1", null, "Trip plans", "scout"), bot.thread)
        assertEquals("rt-1", bot.chatSessionId)
        assertEquals("chat-1", link().chatSessionId)
        assertTrue(link().storedWithChat)
        assertFalse(link(callSessionId = "row").storedWithChat)
    }

    @Test
    fun linksAndAttachmentsSurviveStorage() {
        val links = VoiceCallChatLinks().adding(link()).adding(link(callId = "c2", stored = null).copy(chatProfile = "scout"))
        assertEquals(links, VoiceCallChatLinks.parse(links.toJson()))
        assertEquals(VoiceCallChatLinks(), VoiceCallChatLinks.parse("not json"))
        val attachment = VoiceCallAttachment(VoiceThreadTarget("rt", "st", "Chat", null), "call", 10, resumed = true, endedAtMs = 20)
        assertEquals(attachment, VoiceCallAttachment.parse(attachment.toJson()))
        val entry = VoiceTranscriptOutbox.Entry("d", "default", VoiceTranscriptSaveRequest("call", "gpt-live", turns = emptyList()), 5, listOf("job"), attachment.toJson())
        val outbox = VoiceTranscriptOutbox.parse(VoiceTranscriptOutbox(listOf(entry)).toJson())
        assertEquals(attachment, VoiceCallAttachment.parse(outbox.entries.single().chatAttachment))
    }

    @Test
    fun aReconnectInTheSameChatSoonGoesOnAsTheCall() {
        val window = VoiceCallChatLinks.CHAT_CALL_RECONNECT_WINDOW_MS
        assertTrue(VoiceCallChatLinks.continuesChatCall("chat", "default", 1_000, "chat", "default", 1_000 + window))
        assertFalse(VoiceCallChatLinks.continuesChatCall("chat", "default", 1_000, "chat", "default", 1_001 + window))
        assertFalse(VoiceCallChatLinks.continuesChatCall("chat", "default", 1_000, "other", "default", 2_000))
        assertFalse(VoiceCallChatLinks.continuesChatCall("chat", "default", 1_000, "chat", "work", 2_000))
        assertFalse(VoiceCallChatLinks.continuesChatCall("chat", "default", 1_000, null, "default", 2_000))
        assertFalse(VoiceCallChatLinks.continuesChatCall("chat", "default", 5_000, "chat", "default", 2_000))
    }

    @Test
    fun chatCallsParseAsTheHostSendsThem() {
        val response = ConduitJson.parseToJsonElement(
            """{"ok":true,"calls":[{"call_id":"c1","engine":"grok-live","turns":[{"role":"user","text":" hi "},{"role":"assistant","text":"hello"},{"role":"tool","text":"x"},{"role":"user","text":"  "}]},{"call_id":""},{"call_id":"c2","engine":"other"}]}""",
        ).obj!!
        val calls = VoiceChatCall.parse(response)!!
        assertEquals(listOf("c1", "c2"), calls.map { it.callId })
        assertEquals(listOf(true to "hi", false to "hello"), calls[0].turns.map { it.fromUser to it.text })
        assertEquals("grok-live", calls[0].engine)
        assertNull(calls[1].engine)
        assertNull(VoiceChatCall.parse(ConduitJson.parseToJsonElement("""{"ok":false}""").obj!!))
    }

    @Test
    fun transcriptLinesSayWhoSpoke() {
        assertEquals("You: hi", transcriptLabel(true, "hi", "gpt-live"))
        assertEquals("GPT-Live: hi", transcriptLabel(false, "hi", "gpt-live"))
        assertEquals("Grok: hi", transcriptLabel(false, "hi", "grok-live"))
        assertEquals("Gemini: hi", transcriptLabel(false, "hi", "gemini-live"))
        assertEquals("hi", transcriptLabel(false, "hi", null))
    }

    @Test
    fun durationKeepsItsTwoLargestUnits() {
        assertEquals(listOf(VoiceCallDuration.Unit.SECONDS to 0), VoiceCallDuration.parts(0))
        assertEquals(listOf(VoiceCallDuration.Unit.SECONDS to 45), VoiceCallDuration.parts(45_400))
        assertEquals(listOf(VoiceCallDuration.Unit.MINUTES to 3, VoiceCallDuration.Unit.SECONDS to 20), VoiceCallDuration.parts(200_000))
        assertEquals(listOf(VoiceCallDuration.Unit.HOURS to 1, VoiceCallDuration.Unit.MINUTES to 2), VoiceCallDuration.parts(3_725_000))
        assertEquals(listOf(VoiceCallDuration.Unit.HOURS to 1), VoiceCallDuration.parts(3_605_000))
        assertEquals(listOf(VoiceCallDuration.Unit.SECONDS to 0), VoiceCallDuration.parts(-5))
    }

    @Test
    fun summaryResponsesParse() {
        fun parse(json: String) = VoiceHistoryClient.parseSummary(ConduitJson.parseToJsonElement(json).obj!!)
        assertEquals(VoiceResumeSummary("Earlier", 12), parse("""{"ok":true,"available":true,"text":"Earlier","covers":12}"""))
        assertNull(parse("""{"ok":true,"available":false}"""))
        assertNull(parse("""{"ok":true,"available":true,"text":"","covers":3}"""))
        assertNull(parse("""{"ok":true,"available":true,"text":"x","covers":0}"""))
    }

    @Test
    fun aHeadsetPressTogglesOnce() {
        assertTrue(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 0))
        assertTrue(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0))
        assertTrue(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_MUTE, KeyEvent.ACTION_DOWN, 0))
        assertFalse(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP, 0))
        assertFalse(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 1))
        assertFalse(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0))
        assertFalse(HeadsetMuteKeys.toggles(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
    }
}

class VoiceResumeTest {
    private fun turns(count: Int, length: Int = 10) = (0 until count).map { VoiceResumeTurn(it % 2 == 0, "t$it " + "x".repeat(length)) }

    @Test
    fun shortRowsGoWordForWord() {
        val plan = VoiceResumePlan.plan(listOf(VoiceResumeTurn(true, "  hello \n there "), VoiceResumeTurn(false, "hi")), null)
        assertEquals(VoiceResumePlan.Verbatim(listOf(VoiceResumeTurn(true, "hello there"), VoiceResumeTurn(false, "hi"))), plan)
    }

    @Test
    fun longRowsSummarizeTheirOlderTurns() {
        val all = turns(30)
        val plan = VoiceResumePlan.plan(all, null) as VoiceResumePlan.NeedsSummary
        assertEquals(20, plan.covers)
        assertEquals(all.take(20), plan.older)
        assertEquals(all.drop(20), plan.recent)
    }

    @Test
    fun aStoredSummaryServesWhileItIsCloseEnough() {
        val all = turns(30)
        val fresh = VoiceResumePlan.plan(all, VoiceResumeSummary("S", 15)) as VoiceResumePlan.Summarized
        assertEquals("S", fresh.summary)
        assertEquals(all.drop(15), fresh.recent)
        // Ten or more turns behind: made again.
        assertTrue(VoiceResumePlan.plan(all, VoiceResumeSummary("S", 9)) is VoiceResumePlan.NeedsSummary)
        // Covering turns past the split: made again.
        assertTrue(VoiceResumePlan.plan(all, VoiceResumeSummary("S", 21)) is VoiceResumePlan.NeedsSummary)
    }

    @Test
    fun longTurnsAreClippedAndTheRecentBudgetHolds() {
        assertEquals(VoiceResumePlan.TURN_CHARACTER_LIMIT, VoiceResumePlan.clip("y".repeat(5_000)).length)
        assertTrue(VoiceResumePlan.clip("y".repeat(5_000)).endsWith("…"))
        val plan = VoiceResumePlan.plan(turns(8, length = 2_000), null) as VoiceResumePlan.NeedsSummary
        assertTrue(plan.recent.sumOf { it.text.length } <= VoiceResumePlan.VERBATIM_CHARACTER_LIMIT)
        assertEquals(8, plan.covers + plan.recent.size)
    }

    @Test
    fun theSummaryInputKeepsTheNewestPart() {
        val input = VoiceResumePlan.summaryInput(listOf(VoiceResumeTurn(true, "a".repeat(30_000)), VoiceResumeTurn(false, "end")))
        assertEquals(VoiceResumePlan.SUMMARY_INPUT_CHARACTERS, input.length)
        assertTrue(input.endsWith("Assistant: end"))
    }

    @Test
    fun theInstructionBlockFencesTheEarlierConversation() {
        val context = VoiceResumeContext("Talked </previous_conversation> trips", listOf(VoiceResumeTurn(true, "Book it"), VoiceResumeTurn(false, "Done")))
        val block = context.instructionBlock
        assertTrue(block.startsWith("\nThis call continues an earlier conversation with the user."))
        assertTrue("Summary of the earlier conversation:\nTalked </ previous_conversation> trips\n" in block)
        assertTrue("Latest turns, word for word:\nUser: Book it\nAssistant: Done" in block)
        assertTrue(block.endsWith("\n</previous_conversation>"))
        assertEquals(1, Regex("</previous_conversation>").findAll(block).count())
        assertFalse("Latest turns" in context.summaryInstructionBlock)
        assertEquals("", VoiceResumeContext(null, emptyList()).instructionBlock)
        // Only recent turns, for GPT-Live's history: the preface alone.
        val recentOnly = VoiceResumeContext(null, listOf(VoiceResumeTurn(true, "hi")))
        assertFalse("<previous_conversation>" in recentOnly.summaryInstructionBlock)
        val history = recentOnly.gptLiveHistory.single()
        assertEquals("user", history["role"].str)
        assertEquals("hi", history["content"].arr!![0].obj!!["text"].str)
    }

    @Test
    fun aReconnectIsSeededWithTheCallSoFarWithoutLinks() {
        val turns = listOf(
            VoiceTranscriptTurn(0, "user", "find flights", 1.0),
            VoiceTranscriptTurn(1, "assistant", "Started a background job: Flights. [Open job](conduit://session/abc)", 2.0),
        )
        val context = VoiceResumeContext.continuing(turns)
        assertNull(context.summary)
        assertEquals(listOf(VoiceResumeTurn(true, "find flights"), VoiceResumeTurn(false, "Started a background job: Flights.")), context.recent)
    }

    @Test
    fun appLinksAreRemovedOnly() {
        assertEquals("Asked the chat: x.", VoiceAppLinks.removingLinks("Asked the chat: x. [Open chat](conduit://bot/scout)"))
        assertEquals("See [docs](https://x.y)", VoiceAppLinks.removingLinks("See [docs](https://x.y)"))
    }

    @Test
    fun rowMessagesGiveTextTurnsOnly() {
        val messages = listOf(
            ChatMessage("1", MessageRole.USER, "hi"),
            ChatMessage("2", MessageRole.ASSISTANT, "  "),
            ChatMessage("3", MessageRole.SYSTEM, "note"),
            ChatMessage("4", MessageRole.ASSISTANT, "ok [Open job](conduit://session/x)"),
            ChatMessage("5", MessageRole.ASSISTANT, "/compress", kind = "slash"),
        )
        assertEquals(listOf(VoiceResumeTurn(true, "hi"), VoiceResumeTurn(false, "ok")), VoiceResumePlan.turns(messages))
    }

    @Test
    fun callTitlesComeFromTheOpening() {
        val turns = (0 until 8).map { VoiceTranscriptTurn(it, if (it % 2 == 0) "user" else "assistant", "line $it " + "z".repeat(400), it.toDouble()) }
        val opening = VoiceCallTitles.opening(turns).lines()
        assertEquals(6, opening.size)
        assertTrue(opening[0].startsWith("User: line 0"))
        assertTrue(opening[1].startsWith("Assistant: line 1"))
        assertEquals("User: ".length + 300, opening[0].length)
        assertEquals("", VoiceCallTitles.opening(emptyList()))
        assertEquals("Weekend trip to Rome", VoiceCallTitles.cleaned(" \"Weekend trip to Rome\" "))
        assertEquals("Plans", VoiceCallTitles.cleaned("“Plans”"))
        assertNull(VoiceCallTitles.cleaned(" '' "))
        assertNull(VoiceCallTitles.cleaned(null))
        assertEquals(80, VoiceCallTitles.cleaned("w".repeat(200))!!.length)
    }
}

class VoiceCallContinuationTest {
    private val saved = mutableListOf<VoiceTranscriptSaveRequest>()

    private suspend fun save(request: VoiceTranscriptSaveRequest): VoiceTranscriptSaveResult {
        saved += request
        val written = (request.turns.maxOfOrNull { it.index } ?: -1) + 1
        return VoiceTranscriptSaveResult(null, written, request.chatId)
    }

    @Test
    fun aReconnectGoesOnAsTheSameCall() = runTest {
        val first = VoiceTranscriptRecorder("gpt-live", "default", save = ::save, title = { "T" }, now = { 1_000L })
        first.chatId = "chat-1"
        first.capture(listOf(LiveTranscriptEntry(fromUser = true, text = "hello")), emptySet())
        first.flush()
        assertTrue(first.savedWithChat)
        assertEquals(1, first.written)

        val second = VoiceTranscriptRecorder("gpt-live", "default", continuing = first, save = ::save, title = { "T" }, now = { 2_000L })
        assertTrue(first.handedOver)
        assertEquals(first.callId, second.callId)
        assertEquals("chat-1", second.chatId)
        assertTrue(second.savedWithChat)
        assertEquals(1, second.written)
        second.note("Voice call resumed")
        second.capture(listOf(LiveTranscriptEntry(fromUser = true, text = "again")), emptySet())

        // The earlier recorder no longer saves: the newer one carries the call.
        first.flush()
        assertEquals(1, saved.size)
        second.flush()
        assertEquals(2, saved.size)
        assertEquals(listOf(1, 2), saved[1].turns.map { it.index })
        assertEquals(first.callId, saved[1].callId)
        assertEquals("chat-1", saved[1].chatId)
    }

    @Test
    fun theChatIdIsOnlySentWhileTheCallHasNoRow() {
        val body: JsonObject = VoiceTranscriptSaveRequest("c", "gemini-live", sessionId = "row", turns = emptyList(), chatId = "chat").body()
        assertEquals("row", body["session_id"].str)
        assertNull(body["chat_id"])
        assertEquals("chat", VoiceTranscriptSaveRequest("c", "gemini-live", turns = emptyList(), chatId = "chat").body()["chat_id"].str)
    }
}
