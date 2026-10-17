package app.hermesconduit.android

import app.hermesconduit.android.ui.shortcut.PromptSubmission
import app.hermesconduit.android.ui.shortcut.ShortcutChat
import app.hermesconduit.android.ui.shortcut.ShortcutChatStorage
import app.hermesconduit.android.ui.shortcut.ShortcutChatStore
import app.hermesconduit.android.ui.shortcut.ShortcutSendBackend
import app.hermesconduit.android.ui.shortcut.ShortcutSendError
import app.hermesconduit.android.ui.shortcut.ShortcutSendOutcome
import app.hermesconduit.android.ui.shortcut.ShortcutSendRequest
import app.hermesconduit.android.ui.shortcut.ShortcutSender
import app.hermesconduit.android.ui.voice.LiveSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The "Send to Hermes" automation action (port of the iOS ShortcutSendTests):
 * which chat a run goes to, what it hands back, and how long it waits for
 * Hermes's reply.
 */
class ShortcutSendTest {
    /** A scripted Hermes with a clock that moves only when the run sleeps. */
    private class FakeHermes {
        var now = 1_000_000_000L
        var connects = true
        val created = mutableListOf<Pair<String, String>>()
        val resumed = mutableListOf<ShortcutChat>()
        var resumeFails = false
        val submitted = mutableListOf<Pair<String, String>>()
        var submitOutcome = PromptSubmission.ACCEPTED
        /** One list per poll; the last one repeats. */
        var polls = mutableListOf<List<LiveSessionStatus>>()
        var replies = mutableListOf<String?>()
        var replyReads = 0
        /** The caller ends the run while it waits. */
        var cancelsSleep = false

        fun backend() = ShortcutSendBackend(
            connect = { connects },
            activeProfile = { "default" },
            createChat = { profile, title ->
                created += profile to title
                ShortcutChat("run-${created.size}", "stored-${created.size}", title, profile)
            },
            resumeChat = { chat ->
                resumed += chat
                if (resumeFails) throw IllegalStateException("invalid response")
                "resumed-${resumed.size}"
            },
            submit = { _, runtimeId, text ->
                submitted += runtimeId to text
                submitOutcome
            },
            liveSessions = {
                val rows = polls.firstOrNull().orEmpty()
                if (polls.size > 1) polls.removeAt(0)
                rows
            },
            latestReply = {
                try {
                    when {
                        replies.isEmpty() -> null
                        replies.size > 1 -> replies.removeAt(0)
                        else -> replies[0]
                    }
                } finally {
                    replyReads += 1
                }
            },
            sleep = { ms ->
                if (cancelsSleep) throw CancellationException()
                now += ms
            },
            now = { now },
        )
    }

    private class MemoryStorage : ShortcutChatStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun sender(hermes: FakeHermes, chats: ShortcutChatStore) = ShortcutSender(hermes.backend(), chats)

    private fun request(text: String, chat: String? = null, profile: String? = null, wait: Boolean = false) =
        ShortcutSendRequest(text, chat, profile, wait)

    private fun liveRow(id: String, status: String) = LiveSessionStatus(id, "", status)

    // region Which chat

    @Test
    fun eachRunWithoutAChatNameStartsANewChatTitledFromTheText() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        val deadline = hermes.now + 25_000
        val first = sender(hermes, chats).send(request("  Weather: rain at 4\nmore  "), deadline)
        sender(hermes, chats).send(request("Second"), deadline)

        assertEquals(ShortcutSendOutcome.Sent, first)
        assertEquals(listOf("Shortcut: Weather: rain at 4", "Shortcut: Second"), hermes.created.map { it.second })
        assertEquals(listOf("default", "default"), hermes.created.map { it.first })
        assertEquals(listOf("run-1", "run-2"), hermes.submitted.map { it.first })
        assertEquals("Weather: rain at 4\nmore", hermes.submitted.first().second)
        assertTrue(hermes.resumed.isEmpty())
    }

    @Test
    fun aNamedChatIsReusedPerProfileWhateverTheCase() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        val deadline = hermes.now + 25_000
        sender(hermes, chats).send(request("one", chat = "Morning notes"), deadline)
        sender(hermes, chats).send(request("two", chat = " morning NOTES "), deadline)
        sender(hermes, chats).send(request("three", chat = "Morning notes", profile = "work"), deadline)

        assertEquals(listOf("Morning notes", "Morning notes"), hermes.created.map { it.second })
        assertEquals(listOf("default", "work"), hermes.created.map { it.first })
        assertEquals(listOf("stored-1"), hermes.resumed.map { it.storedSessionId })
        assertEquals(listOf("run-1", "resumed-1", "run-2"), hermes.submitted.map { it.first })
    }

    @Test
    fun aNamedChatThatCannotBeResumedIsReplacedByANewOne() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        val deadline = hermes.now + 25_000
        sender(hermes, chats).send(request("one", chat = "Log"), deadline)
        hermes.resumeFails = true
        sender(hermes, chats).send(request("two", chat = "Log"), deadline)
        hermes.resumeFails = false
        sender(hermes, chats).send(request("three", chat = "Log"), deadline)

        assertEquals(2, hermes.created.size)
        assertEquals(listOf("stored-1", "stored-2"), hermes.resumed.map { it.storedSessionId })
        assertEquals(listOf("run-1", "run-2", "resumed-2"), hermes.submitted.map { it.first })
    }

    @Test
    fun theChatListKeepsOnlyTheMostRecentChats() {
        val store = ShortcutChatStore(MemoryStorage())
        for (index in 0..ShortcutChatStore.MAXIMUM_CHATS) {
            store.save(ShortcutChat("r$index", title = "c$index", profile = "default"), "c$index", "default", index * 1_000L)
        }
        assertNull(store.chat("c0", "default"))
        assertEquals("r1", store.chat("c1", "default")?.runtimeSessionId)
        store.forget("c1", "default")
        assertNull(store.chat("c1", "default"))
    }

    // endregion

    // region What comes back

    @Test
    fun waitingReturnsTheReplyOnceTheTurnEnds() = runBlocking {
        val hermes = FakeHermes()
        hermes.polls = mutableListOf(listOf(liveRow("run-1", "working")), listOf(liveRow("run-1", "working")), listOf(liveRow("run-1", "idle")))
        hermes.replies = mutableListOf("It will rain at 4.")
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Weather?", wait = true), hermes.now + 25_000)

        assertEquals(ShortcutSendOutcome.Answered("It will rain at 4."), outcome)
        assertEquals("It will rain at 4.", outcome.text)
        assertEquals("answered", outcome.name)
    }

    @Test
    fun anIdleRowRightAfterTheSendIsNotTakenAsTheEnd() = runBlocking {
        val hermes = FakeHermes()
        hermes.polls = mutableListOf(
            listOf(liveRow("run-1", "idle")), listOf(liveRow("run-1", "idle")),
            listOf(liveRow("run-1", "working")), listOf(liveRow("run-1", "idle")),
        )
        hermes.replies = mutableListOf(null, null, "Done.")
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Go", wait = true), hermes.now + 25_000)

        assertEquals(ShortcutSendOutcome.Answered("Done."), outcome)
        assertEquals("Each early idle poll only looked for a reply", 3, hermes.replyReads)
    }

    @Test
    fun aReusedChatsOldReplyIsNotTakenAsTheAnswer() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        sender(hermes, chats).send(request("one", chat = "Log"), hermes.now + 25_000)
        hermes.polls = mutableListOf(listOf(liveRow("resumed-1", "working")), listOf(liveRow("resumed-1", "idle")))
        hermes.replies = mutableListOf("Old reply.")
        val outcome = sender(hermes, chats).send(request("two", chat = "Log", wait = true), hermes.now + 25_000)

        assertEquals(ShortcutSendOutcome.StillWorking, outcome)
    }

    @Test
    fun aQuestionFromHermesSaysTheChatNeedsAnAnswer() = runBlocking {
        val hermes = FakeHermes()
        hermes.polls = mutableListOf(listOf(liveRow("run-1", "working")), listOf(liveRow("run-1", "waiting")))
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Book it", wait = true), hermes.now + 25_000)

        assertEquals(ShortcutSendOutcome.NeedsInput, outcome)
    }

    @Test
    fun aLongTurnRunsOutOfTimeAndSaysWhereTheAnswerWillBe() = runBlocking {
        val hermes = FakeHermes()
        val start = hermes.now
        hermes.polls = mutableListOf(listOf(liveRow("run-1", "working")))
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Research", wait = true), start + 25_000)

        assertEquals(ShortcutSendOutcome.StillWorking, outcome)
        assertTrue(hermes.now - start <= 25_000)
        assertEquals("Only the last look when time is up", 1, hermes.replyReads)
    }

    @Test
    fun aBusyNamedChatQueuesWithoutWaiting() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        hermes.submitOutcome = PromptSubmission.QUEUED
        val queued = sender(hermes, chats).send(request("Later", wait = true), hermes.now + 25_000)
        hermes.submitOutcome = PromptSubmission.STEERED
        val joined = sender(hermes, chats).send(request("Also", wait = true), hermes.now + 25_000)

        assertEquals(ShortcutSendOutcome.Queued, queued)
        assertEquals(ShortcutSendOutcome.JoinedRunningReply, joined)
        assertEquals(0, hermes.replyReads)
    }

    @Test
    fun emptyTextAndNoConnectionFailWithoutSending() = runBlocking {
        val hermes = FakeHermes()
        val chats = ShortcutChatStore(MemoryStorage())
        val deadline = hermes.now + 25_000
        try {
            sender(hermes, chats).send(request(" \n "), deadline)
            fail("empty text was sent")
        } catch (e: ShortcutSendError) {
            assertEquals(ShortcutSendError.empty, e)
        }
        hermes.connects = false
        try {
            sender(hermes, chats).send(request("Hello"), deadline)
            fail("sent without a connection")
        } catch (e: ShortcutSendError) {
            assertEquals(ShortcutSendError.notConnected, e)
        }
        assertTrue(hermes.submitted.isEmpty())
        assertTrue(hermes.created.isEmpty())
    }

    @Test
    fun aCancelledRunEndsAsCancelledNotAsSent() = runBlocking {
        val hermes = FakeHermes()
        hermes.cancelsSleep = true
        try {
            sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Wait", wait = true), hermes.now + 25_000)
            fail("a cancelled run reported an outcome")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun aTurnThatEndsBetweenPollsStillAnswers() = runBlocking {
        val hermes = FakeHermes()
        // Never seen running: idle, idle, then the reply is there.
        hermes.polls = mutableListOf(listOf(liveRow("run-1", "idle")))
        hermes.replies = mutableListOf(null, null, "Quick answer.")
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Hi", wait = true), hermes.now + 25_000)
        assertEquals(ShortcutSendOutcome.Answered("Quick answer."), outcome)
    }

    @Test
    fun aReplyThatLandsAsTimeRunsOutStillAnswers() = runBlocking {
        val hermes = FakeHermes()
        hermes.polls = mutableListOf(listOf(liveRow("run-1", "working")))
        hermes.replies = mutableListOf("Made it.")
        val outcome = sender(hermes, ShortcutChatStore(MemoryStorage())).send(request("Long", wait = true), hermes.now + 25_000)
        assertEquals(ShortcutSendOutcome.Answered("Made it."), outcome)
        assertEquals("Read once, at the end", 1, hermes.replyReads)
    }

    @Test
    fun aLongFirstLineIsShortenedInTheTitle() {
        assertEquals("Shortcut: " + "a".repeat(59) + "…", ShortcutSender.title("a".repeat(100)))
    }

    @Test
    fun outcomesNameTheIosCasesAndPromptStatusesMapLikeIos() {
        assertEquals(
            listOf("sent", "stillWorking", "needsInput", "queued", "joinedRunningReply"),
            listOf(ShortcutSendOutcome.Sent, ShortcutSendOutcome.StillWorking, ShortcutSendOutcome.NeedsInput, ShortcutSendOutcome.Queued, ShortcutSendOutcome.JoinedRunningReply).map { it.name },
        )
        assertEquals("Sent to Hermes.", ShortcutSendOutcome.Sent.text)
        assertEquals(PromptSubmission.ACCEPTED, PromptSubmission.of("streaming"))
        assertEquals(PromptSubmission.ACCEPTED, PromptSubmission.of(null))
        assertEquals(PromptSubmission.QUEUED, PromptSubmission.of("Queued"))
        assertEquals(PromptSubmission.REDIRECTED, PromptSubmission.of("redirected"))
    }

    // endregion
}
