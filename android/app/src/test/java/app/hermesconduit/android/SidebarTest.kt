package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.ui.SessionsState
import app.hermesconduit.android.ui.nestSessions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarTest {
    private fun sessions(json: String) = MessageNormalizer.sessions(ConduitJson.parseToJsonElement(json) as JsonArray)

    @Test
    fun sourcesAreClassifiedLikeIos() {
        fun kind(json: String) = MessageNormalizer.classifySource(ConduitJson.parseToJsonElement(json) as JsonObject)
        assertEquals("cron", kind("""{"source":"telegram","cron_job_id":"j1"}"""))
        assertEquals("cron", kind("""{"id":"cron_123"}"""))
        assertEquals("telegram", kind("""{"source":"telegram"}"""))
        assertEquals("api", kind("""{"platform":"api-server"}"""))
        assertEquals("chat", kind("""{"source":"desktop"}"""))
        assertEquals("other", kind("""{"source":"matrix"}"""))
    }

    @Test
    fun childrenNestUnderTheirParent() {
        val list = sessions("""[{"id":"a","title":"A"},{"id":"b","title":"B","parent_session_id":"a"},{"id":"c","title":"C","parent_session_id":"gone"}]""")
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 0), nestSessions(list).map { it.first.id to it.second })
    }

    @Test
    fun filtersAndQuery() {
        val list = sessions("""[{"id":"a","title":"Deploy fix","unread":true},{"id":"b","title":"Notes","source":"discord"}]""")
        val state = SessionsState(list, liveStatus = mapOf("b" to "working"))
        assertEquals(listOf("a"), state.copy(filter = "unread").visible.map { it.id })
        assertEquals(listOf("b"), state.copy(filter = "running").visible.map { it.id })
        assertEquals(listOf("b"), state.copy(filter = "source.discord").visible.map { it.id })
        assertEquals(listOf("a"), state.copy(query = "deploy").visible.map { it.id })
    }

    @Test
    fun generatedTitlesAreCleaned() {
        assertEquals("Fix the deploy", MessageNormalizer.generatedTitle("<think>hmm</think>\nTitle: \"Fix the deploy\""))
    }
}
