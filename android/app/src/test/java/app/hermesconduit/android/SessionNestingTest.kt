package app.hermesconduit.android

import app.hermesconduit.android.model.SessionNesting
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.model.VoiceSessionTag
import app.hermesconduit.android.model.VoiceSessionTagKind
import app.hermesconduit.android.ui.SessionsState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNestingTest {
    private fun row(id: String, at: Long? = null, parent: String? = null, aliases: Set<String> = emptySet()) =
        SessionSummary(id, id, null, "m", null, false, false, false, null, at, null, parentId = parent, alternateIds = aliases)

    @Test
    fun nestsUnderAliasAndSortsChildrenNewestFirst() {
        val rows = listOf(row("chat", aliases = setOf("rt-chat")), row("job1", 1, "rt-chat"), row("job2", 5, "chat"), row("solo"))
        assertEquals(
            listOf("chat" to 0, "job2" to 1, "job1" to 1, "solo" to 0),
            SessionNesting.group(rows) { it.parentId }.map { it.first.id to it.second },
        )
    }

    @Test
    fun cyclesAndMissingParentsStayTopLevel() {
        val rows = listOf(row("a", parent = "b"), row("b", parent = "a"), row("c", parent = "gone"), row("d", parent = "d"))
        val grouped = SessionNesting.group(rows) { it.parentId }
        assertEquals(setOf("a", "b", "c", "d"), grouped.map { it.first.id }.toSet())
        assertEquals(4, grouped.size)
        assertEquals(listOf(0), grouped.filter { it.first.id == "c" || it.first.id == "d" }.map { it.second }.distinct())
    }

    @Test
    fun voiceTagsFileAndNestRows() {
        val state = SessionsState(
            sessions = listOf(row("chat"), row("call"), row("job", 3)),
            voiceTags = mapOf(
                "call" to VoiceSessionTag(VoiceSessionTagKind.CALL, engine = "gemini-live"),
                "job" to VoiceSessionTag(VoiceSessionTagKind.JOB, parentId = "call"),
            ),
            callChats = mapOf("call" to "chat"),
        )
        assertEquals(listOf("chat" to 0, "call" to 1, "job" to 2), state.nested.map { it.first.id to it.second })
        assertEquals("voice", state.category(state.sessions[1]))
        assertEquals("voice_job", state.category(state.sessions[2]))
        assertEquals("Gemini Live", state.voiceDetail(state.sessions[1]))
        assertEquals("From call", state.voiceDetail(state.sessions[2]))
        assertEquals(listOf("call", "job"), state.copy(filter = "voice").visible.map { it.id })
        assertEquals(listOf("job"), state.copy(filter = "source.voice_job").visible.map { it.id })
        // A search flattens the nest.
        assertEquals(listOf(0), state.copy(query = "o").nested.map { it.second }.distinct())
    }
}
