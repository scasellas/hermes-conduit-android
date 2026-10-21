package app.hermesconduit.android

import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.watch.WatchToolGrantClient
import app.hermesconduit.android.watch.WatchTurnReducer
import app.hermesconduit.wearwire.CallLogBuffer
import app.hermesconduit.wearwire.CallLogLines
import app.hermesconduit.wearwire.PhoneContext
import app.hermesconduit.wearwire.ReplyAssembler
import app.hermesconduit.wearwire.ReplyEvent
import app.hermesconduit.wearwire.SpeakableText
import app.hermesconduit.wearwire.SpeechChunker
import app.hermesconduit.wearwire.VoiceCommands
import app.hermesconduit.wearwire.WearApproval
import app.hermesconduit.wearwire.WearChat
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearWireTest {
    private fun roundTrip(message: WearMessage): WearMessage? {
        val (path, bytes) = WearWire.encode(message)
        return WearWire.decode(path, bytes)
    }

    @Test fun messagesRoundTrip() {
        val approval = WearApproval("s1", "r1", "Fix the build", "rm -rf build", "Deletes the build folder")
        val all = listOf(
            WearMessage.Turn("call-1", 3, "What's on my calendar?", "stored-9"),
            WearMessage.Turn("call-1", 0, "Hi"),
            WearMessage.Stop("call-1"),
            WearMessage.Stop("call-1", end = true),
            WearMessage.Reply("call-1", 3, ReplyEvent.Started("stored-9")),
            WearMessage.Reply("call-1", 3, ReplyEvent.Text(4, "You have two meetings.")),
            WearMessage.Reply("call-1", 3, ReplyEvent.Done("You have two meetings.", interrupted = true)),
            WearMessage.Reply("call-1", 3, ReplyEvent.Failed("Hermes took too long to answer.")),
            WearMessage.Reply("call-1", 3, ReplyEvent.Approval(approval)),
            WearMessage.Reply("call-1", 3, ReplyEvent.ApprovalCleared("r1")),
            WearMessage.ChatsRequest,
            WearMessage.Chats(listOf(WearChat("a", "Trip plans", "Booked the train", 1_700_000_000_000), WearChat("b", "", null, null))),
            WearMessage.Chats(emptyList(), "Sign in"),
            WearMessage.ApprovalsRequest,
            WearMessage.Approvals(listOf(approval)),
            WearMessage.ApprovalRespond("s1", "r1", WearWire.APPROVE_ONCE),
            WearMessage.ApprovalRespond("s1", "", WearWire.DENY),
            WearMessage.ApprovalResult("s1", "r1", false, "Not connected"),
        )
        all.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test fun refusesWhatTheWatchMayNotSend() {
        // Only Approve once or Deny: never "session" or "always" from the watch.
        assertNull(WearWire.decode(WearWire.APPROVAL_RESPOND, """{"session_id":"s","request_id":"r","choice":"always"}""".toByteArray()))
        assertNull(WearWire.decode(WearWire.APPROVAL_RESPOND, """{"session_id":"","choice":"once"}""".toByteArray()))
        // Empty turns, newer wire versions, unknown paths and junk.
        assertNull(WearWire.decode(WearWire.TURN, """{"call_id":"c","text":"   "}""".toByteArray()))
        assertNull(WearWire.decode(WearWire.TURN, """{"v":99,"call_id":"c","text":"hi"}""".toByteArray()))
        assertNull(WearWire.decode("/conduit/unknown", "{}".toByteArray()))
        assertNull(WearWire.decode(WearWire.TURN, "not json".toByteArray()))
        assertNull(WearWire.decode(WearWire.REPLY, """{"call_id":"c","kind":"mystery"}""".toByteArray()))
    }

    @Test fun turnTextIsTrimmedAndBlankSessionDropped() {
        val decoded = WearWire.decode(WearWire.TURN, """{"call_id":"c","turn_id":2,"text":"  hello ","session_id":""}""".toByteArray())
        assertEquals(WearMessage.Turn("c", 2, "hello", null), decoded)
    }

    @Test fun phoneContextRoundTripsAndDefaults() {
        val context = PhoneContext(signedIn = true, connected = false, voiceApprovals = true, profile = "work", updatedAtMs = 42)
        assertEquals(context, PhoneContext.decode(context.encoded()))
        assertEquals(PhoneContext(false, false, false, "default", 1, 0), PhoneContext.decode("{}"))
        assertNull(PhoneContext.decode("[]"))
    }

    @Test fun voiceCommands() {
        assertTrue(VoiceCommands.asksToEndCall("Goodbye."))
        assertTrue(VoiceCommands.asksToEndCall("Okay, goodbye!"))
        assertTrue(VoiceCommands.asksToEndCall("bye bye"))
        assertTrue(VoiceCommands.asksToEndCall("That’s all, thanks"))
        assertTrue(VoiceCommands.asksToEndCall("hang up"))
        assertFalse(VoiceCommands.asksToEndCall("goodbye to the old server"))
        assertFalse(VoiceCommands.asksToEndCall(""))
        assertTrue(VoiceCommands.asksToStop("Stop!"))
        assertFalse(VoiceCommands.asksToStop("stop the deploy"))
        assertEquals(WearWire.APPROVE_ONCE, VoiceCommands.approvalAnswer("Approve."))
        assertEquals(WearWire.APPROVE_ONCE, VoiceCommands.approvalAnswer("okay, approve once please"))
        assertEquals(WearWire.DENY, VoiceCommands.approvalAnswer("Deny it"))
        assertEquals(WearWire.DENY, VoiceCommands.approvalAnswer("don't approve"))
        assertNull(VoiceCommands.approvalAnswer("approve everything forever"))
        assertNull(VoiceCommands.approvalAnswer("what does it do?"))
    }

    @Test fun chunkerSpeaksWholeSentencesAsTheyArrive() {
        val chunker = SpeechChunker(minChunk = 10)
        assertEquals(emptyList<String>(), chunker.update("Sure. Your next"))
        val first = chunker.update("Sure. Your next meeting is at three. Then")
        assertEquals(listOf("Sure. Your next meeting is at three."), first)
        assertEquals(emptyList<String>(), chunker.update("Sure. Your next meeting is at three. Then lunch"))
        assertEquals(listOf("Then lunch with Ana."), chunker.finish("Sure. Your next meeting is at three. Then lunch with **Ana**."))
        assertEquals(emptyList<String>(), chunker.finish())
    }

    @Test fun chunkerFinishCleansMarkdown() {
        val chunker = SpeechChunker()
        assertEquals(listOf("Done: see the docs and run it"), chunker.finish("## Done: see [the docs](https://x.y) and `run it`"))
    }

    @Test fun speakableTextDropsCodeLinksAndTags() {
        assertEquals("Here: and then a list item", SpeakableText.clean("Here:\n```kotlin\nval x = 1\n```\nand then\n- a *list* item"))
        assertEquals("Open the site now", SpeakableText.clean("Open <b>the site</b> https://example.com now"))
    }

    @Test fun replyAssemblerKeepsTheNewest() {
        val reply = ReplyAssembler()
        assertTrue(reply.apply(ReplyEvent.Text(1, "Hel")))
        assertTrue(reply.apply(ReplyEvent.Text(3, "Hello the")))
        assertFalse(reply.apply(ReplyEvent.Text(2, "Hello")))
        assertEquals("Hello the", reply.text)
        reply.finish("Hello there.")
        assertEquals("Hello there.", reply.text)
        assertFalse(reply.apply(ReplyEvent.Text(9, "late")))
    }

    @Test fun callLogBufferNumbersLines() {
        val log = CallLogBuffer(keep = 3)
        (1..5).forEach { log.append("l$it") }
        assertEquals(listOf(4 to "l5", 3 to "l4", 2 to "l3"), log.newestFirst())
        log.clear()
        assertEquals(0, log.size)
        log.append("l6")
        assertEquals(listOf(5 to "l6"), log.newestFirst())
    }

    @Test fun callLogLinesAreSortedJson() {
        val line = CallLogLines.line("callStart", "watch", mapOf("ms" to 12, "ok" to true, "none" to null), nowMs = 0)
        assertEquals("""{"at":"1970-01-01T00:00:00.000Z","event":"callStart","ms":12,"none":null,"ok":true,"side":"watch"}""", line)
    }

    @Test fun callLogTrimKeepsTheNewestHalfAtALineBoundary() {
        val content = (1..10).joinToString("") { "line$it\n" }.toByteArray()
        assertNull(CallLogLines.trimmed(content, content.size))
        val kept = String(CallLogLines.trimmed(content, 40)!!)
        assertTrue(kept.endsWith("line10\n"))
        assertTrue(kept.length <= 20)
        assertTrue(kept.startsWith("line"))
    }

    @Test fun turnReducerStreamsAndEnds() {
        val reducer = WatchTurnReducer("rt") { "Chat" }
        assertNull(reducer.on(StreamEvent.MessageDelta("other", "x")))
        assertEquals(ReplyEvent.Text(1, "Hi"), reducer.on(StreamEvent.MessageDelta("rt", "Hi")))
        assertEquals(ReplyEvent.Text(2, "Hi there"), reducer.on(StreamEvent.MessageDelta("rt", " there")))
        val approval = ApprovalRequest("q", "rt", "ls", "List files", listOf("once", "session", "deny"))
        assertEquals(ReplyEvent.Approval(WearApproval("rt", "q", "Chat", "ls", "List files")), reducer.on(StreamEvent.Approval("rt", approval)))
        assertEquals(ReplyEvent.ApprovalCleared("q"), reducer.on(StreamEvent.RequestCancelled("rt", "q", "approval", "answered")))
        assertEquals(ReplyEvent.Done("Hi there, friend."), reducer.on(StreamEvent.MessageComplete("rt", "m", "Hi there, friend.", null)))
        assertTrue(reducer.finished)
        assertNull(reducer.on(StreamEvent.MessageDelta("rt", "late")))
    }

    @Test fun turnReducerFailuresAndInterrupts() {
        assertEquals(ReplyEvent.Failed("boom"), WatchTurnReducer("rt").on(StreamEvent.Error("rt", "boom")))
        val interrupted = WatchTurnReducer("rt")
        interrupted.on(StreamEvent.MessageDelta("rt", "Well"))
        assertEquals(ReplyEvent.Done("Well", interrupted = true), interrupted.on(StreamEvent.Interrupted("rt")))
        // A busy=false before anything streamed is the turn starting, not ending.
        val early = WatchTurnReducer("rt")
        assertNull(early.on(StreamEvent.Busy("rt", false)))
        early.on(StreamEvent.MessageDelta("rt", "Ok"))
        assertEquals(ReplyEvent.Done("Ok"), early.on(StreamEvent.Busy("rt", false)))
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun toolGrantParsing() {
        val grant = WatchToolGrantClient.grant(json("""
            {"ok":true,"grant_id":"g1","relay_url":"https://relay.example","key":"k","watch_key":"wk",
             "expires_at":"2026-10-16T12:00:00Z","tools":["web_search","recall_memory"],"max_calls":40,
             "max_jobs":5,"audio":{"url":"wss://relay.example/a","version":2,"engines":["gpt-live"]},"job_profiles":["work"]}
        """))!!
        assertEquals("g1", grant.grantId)
        assertEquals(listOf("web_search", "recall_memory"), grant.tools)
        assertEquals(40, grant.maxCalls)
        assertEquals(5, grant.maxJobs)
        assertEquals(WatchToolGrantClient.AudioBridge("wss://relay.example/a", 2, listOf("gpt-live")), grant.audio)
        assertEquals(listOf("work"), grant.hostJobProfiles)
        // Refused: not ok, plain http relay, no tools, zero calls; an audio bridge without wss is dropped.
        assertNull(WatchToolGrantClient.grant(json("""{"ok":false}""")))
        assertNull(WatchToolGrantClient.grant(json("""{"ok":true,"grant_id":"g","relay_url":"http://r","key":"k","watch_key":"w","tools":["x"],"max_calls":1}""")))
        assertNull(WatchToolGrantClient.grant(json("""{"ok":true,"grant_id":"g","relay_url":"https://r","key":"k","watch_key":"w","tools":[],"max_calls":1}""")))
        assertNull(WatchToolGrantClient.grant(json("""{"ok":true,"grant_id":"g","relay_url":"https://r","key":"k","watch_key":"w","tools":["x"],"max_calls":0}""")))
        assertNull(WatchToolGrantClient.grant(json("""{"ok":true,"grant_id":"g","relay_url":"https://r","key":"k","watch_key":"w","tools":["x"],"max_calls":1,"audio":{"url":"https://a","version":1}}"""))!!.audio)
    }

    @Test fun toolGrantBodyCarriesJobOptionsOnlyWithJobTools() {
        val plain = WatchToolGrantClient.grantBody(listOf("web_search"), 5, mapOf("model" to "m"), "old", listOf("work"), audio = true)
        assertEquals(setOf("tools", "audio"), plain.keys)
        val jobs = WatchToolGrantClient.grantBody(listOf("web_search", "start_job"), 5, mapOf("model" to "m"), "old", listOf("work"), audio = false)
        assertEquals(setOf("tools", "max_jobs", "job_options", "carry_jobs_from", "job_profiles"), jobs.keys)
        assertEquals("m", jobs["job_options"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(2, jobs["tools"]!!.jsonArray.size)
    }

    @Test fun audioRuntimeParsing() {
        assertEquals(WatchToolGrantClient.AudioRuntime("ready"), WatchToolGrantClient.audioRuntime(json("""{"engines":{"gpt_live":{"runtime":"ready"}}}""")))
        assertEquals(WatchToolGrantClient.AudioRuntime("failed", "no disk"), WatchToolGrantClient.audioRuntime(json("""{"engines":{"gpt_live":{"runtime":"failed","reason":"no disk"}}}""")))
        assertEquals(WatchToolGrantClient.AudioRuntime("missing"), WatchToolGrantClient.audioRuntime(json("{}")))
    }
}
