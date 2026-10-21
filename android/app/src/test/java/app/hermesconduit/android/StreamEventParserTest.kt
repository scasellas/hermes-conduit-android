package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.StreamEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventParserTest {
    private fun parse(json: String) = StreamEventParser.parse(ConduitJson.parseToJsonElement(json))

    @Test
    fun deltasPreferPayloadText() {
        assertEquals(StreamEvent.MessageDelta("s1", "Hi"), parse("""{"type":"message.delta","session_id":"s1","payload":{"text":"Hi"}}"""))
        assertEquals(StreamEvent.MessageDelta("s1", "top"), parse("""{"type":"message.delta","session_id":"s1","text":"top"}"""))
        assertNull(parse("""{"type":"message.delta","session_id":"s1","payload":{"text":""}}"""))
    }

    @Test
    fun reasoningAliases() {
        assertEquals(
            StreamEvent.ReasoningDelta("s", "why"),
            parse("""{"type":"message.reasoning.delta","session_id":"s","payload":{"reasoning_content":"why"}}"""),
        )
    }

    @Test
    fun completeReadsNestedIds() {
        val event = parse("""{"type":"message.complete","session_id":"s","payload":{"message":{"id":"m9"},"rendered":"done"}}""")
        assertEquals(StreamEvent.MessageComplete("s", "m9", "done", null), event)
    }

    @Test
    fun toolEventsCarryIdsAndDescriptiveInput() {
        val start = parse("""{"type":"tool.start","session_id":"s","payload":{"name":"terminal","call_id":" c1 ","args":{"cmd":"ls"}}}""")
        assertEquals(StreamEvent.ToolStart("s", "terminal", """{"cmd":"ls"}""", "c1"), start)
        val done = parse("""{"type":"tool_result","session_id":"s","payload":{"name":"terminal","tool_id":"c1","result":"ok"}}""")
        assertEquals(StreamEvent.ToolComplete("s", "terminal", "ok", "c1"), done)
    }

    @Test
    fun approvalDefaultsChoicesAndHidesAlwaysWhenNotPermanent() {
        val event = parse("""{"type":"approval.request","session_id":"s","payload":{"request_id":"q1","command":"rm -rf x","allow_permanent":false}}""")
        event as StreamEvent.Approval
        assertEquals(listOf("once", "session", "deny"), event.request.choices)
        assertEquals("q1", event.request.requestId)
        val smart = parse("""{"type":"approval.request","session_id":"s","payload":{"smart_denied":true}}""") as StreamEvent.Approval
        assertEquals(listOf("once", "deny"), smart.request.choices)
    }

    @Test
    fun statusUpdateRequiresSession() {
        assertNull(parse("""{"type":"status.update","payload":{"kind":"compacting"}}"""))
    }

    @Test
    fun requestCancelAndBusy() {
        assertEquals(
            StreamEvent.RequestCancelled("s", "srq-1", "clarify", "timeout"),
            parse("""{"type":"request.cancel","session_id":"s","payload":{"id":"srq-1","method":"clarify","reason":"timeout"}}"""),
        )
        assertEquals(StreamEvent.Busy("s", false), parse("""{"type":"session.busy","session_id":"s","payload":{"busy":false}}"""))
        assertTrue(parse("""{"type":"weird.thing","session_id":"s"}""") is StreamEvent.Unparsed)
    }
}
