package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNormalizerTest {
    private fun rows(json: String) = ConduitJson.parseToJsonElement(json) as JsonArray

    @Test
    fun sessionPrefersStoredIdAndTitleFallsBackToPreview() {
        val sessions = MessageNormalizer.sessions(
            rows(
                """[
                  {"session_id":"rt-1","id":"stored-1","preview":"Hello there","last_active":1700000000,"pinned":true,"message_count":3},
                  {"id":"stored-2","title":"Named","updated_at":"2024-01-02T03:04:05Z"}
                ]""",
            ),
        )
        assertEquals("stored-1", sessions[0].id)
        assertEquals("Hello there", sessions[0].title)
        assertEquals(1_700_000_000_000, sessions[0].lastActiveMs)
        assertTrue(sessions[0].pinned)
        assertEquals("Named", sessions[1].title)
        assertEquals(1_704_164_645_000, sessions[1].lastActiveMs)
    }

    @Test
    fun epochUnitsAreNormalized() {
        val ms = 1_700_000_000_000L
        listOf("1700000000", "1700000000000", "1700000000000000", "1700000000000000000").forEach {
            assertEquals(ms, MessageNormalizer.epochMillis(ConduitJson.parseToJsonElement(it)))
        }
    }

    @Test
    fun toolResultsAttachToTheirCall() {
        val messages = MessageNormalizer.messages(
            rows(
                """[
                  {"id":1,"role":"user","content":"list files"},
                  {"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{\"cmd\":\"ls\"}"}}]},
                  {"id":3,"role":"tool","tool_call_id":"c1","content":"a.txt"},
                  {"id":4,"role":"assistant","content":[{"type":"text","text":"Found"},{"type":"image_url"}],"reasoning":"easy"},
                  {"id":5,"role":"system","display_kind":"hidden","content":"secret"}
                ]""",
            ),
        )
        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        val call = messages[1].toolCalls.single()
        assertEquals("terminal", call.name)
        assertEquals("a.txt", call.output)
        assertEquals("Found\n[image]", messages[2].content)
        assertEquals("easy", messages[2].reasoning)
    }

    @Test
    fun envelopesAndSteersAreUnwrapped() {
        val messages = MessageNormalizer.messages(
            rows(
                """[
                  {"id":"a","message":{"role":"user","content":"hi"}},
                  {"id":"b","role":"user","display_kind":"steer","content":"[OUT-OF-BAND USER MESSAGE — sent mid-turn]\nfocus on tests\n[/OUT-OF-BAND USER MESSAGE]"},
                  {"id":"c","role":"assistant","display_kind":"model_switch","content":"Switched to x"}
                ]""",
            ),
        )
        assertEquals("hi", messages[0].content)
        assertEquals("focus on tests", messages[1].content)
        assertEquals(MessageRole.SYSTEM, messages[2].role)
    }

    @Test
    fun clarifyQuestionsAndScalarForm() {
        val batch = MessageNormalizer.clarifyRequest(
            ConduitJson.parseToJsonElement(
                """{"questions":[{"qid":"q1","question":"Which?","choices":["a",{"label":"B","value":"b"}],"multi_select":true}]}""",
            ) as JsonObject,
            sessionId = "s",
            serverRequestId = "srq-1",
        )!!
        assertTrue(batch.isServerRequest)
        assertEquals(listOf("a", "B"), batch.questions.single().choices)
        assertTrue(batch.questions.single().multiSelect)

        val scalar = MessageNormalizer.clarifyRequest(
            ConduitJson.parseToJsonElement("""{"request_id":"r1","question":"Go?"}""") as JsonObject,
            sessionId = "s",
            serverRequestId = null,
        )!!
        assertFalse(scalar.isServerRequest)
        assertEquals("r1", scalar.requestId)
        assertNull(scalar.questions.single().id)
    }

    @Test
    fun modelOptions() {
        val options = MessageNormalizer.modelOptions(
            ConduitJson.parseToJsonElement(
                """{"model":"m1","provider":"openrouter","providers":[{"slug":"openrouter","name":"OpenRouter","models":["m1",{"id":"m2"}],"is_current":true}]}""",
            ) as JsonObject,
        )
        assertEquals(listOf("m1", "m2"), options.providers.single().models)
        assertTrue(options.providers.single().isCurrent)
    }
}
