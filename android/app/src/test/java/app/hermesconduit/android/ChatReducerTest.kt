package app.hermesconduit.android

import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.ui.ChatReducer
import app.hermesconduit.android.ui.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun run(vararg events: StreamEvent) = events.fold(ChatState(runtimeId = "s")) { state, e -> ChatReducer.reduce(state, e) }

    @Test
    fun streamsATurnWithToolsIntoOneAssistantMessage() {
        val state = run(
            StreamEvent.MessageStart("s"),
            StreamEvent.ReasoningDelta("s", "think"),
            StreamEvent.ToolStart("s", "terminal", "ls", "c1"),
            StreamEvent.ToolComplete("s", "terminal", "a.txt", "c1"),
            StreamEvent.MessageDelta("s", "Hel"),
            StreamEvent.MessageDelta("s", "lo"),
        )
        assertTrue(state.running)
        val message = state.messages.single()
        assertTrue(message.streaming)
        assertEquals("Hello", message.content)
        assertEquals("think", message.reasoning)
        assertEquals("a.txt", message.toolCalls.single().output)
        assertFalse(message.toolCalls.single().running)

        val done = ChatReducer.reduce(
            ChatReducer.reduce(state, StreamEvent.MessageComplete("s", "m1", "Hello!", null)),
            StreamEvent.Busy("s", false),
        )
        assertFalse(done.running)
        assertEquals("m1", done.messages.single().id)
        assertEquals("Hello!", done.messages.single().content)
        assertFalse(done.messages.single().streaming)
    }

    @Test
    fun emptyStreamingBubbleIsDroppedOnInterrupt() {
        val state = run(StreamEvent.MessageStart("s"), StreamEvent.Interrupted("s"))
        assertTrue(state.messages.isEmpty())
        assertFalse(state.running)
    }

    @Test
    fun cancelledClarifyIsCleared() {
        val clarify = app.hermesconduit.android.model.ClarifyRequest("srq-1", "s", emptyList(), true)
        val state = ChatReducer.reduce(ChatState(clarify = clarify), StreamEvent.RequestCancelled("s", "srq-1", "clarify", "timeout"))
        assertNull(state.clarify)
    }
}
