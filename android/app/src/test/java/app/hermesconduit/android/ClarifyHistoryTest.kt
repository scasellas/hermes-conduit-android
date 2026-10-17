package app.hermesconduit.android

import app.hermesconduit.android.model.ClarifyHistoryRecord
import app.hermesconduit.android.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClarifyHistoryTest {
    private fun clarify(output: String, input: String? = null, running: Boolean = false) =
        ToolCall("t1", "clarify", input, output, running)

    @Test
    fun currentShapeReadsAnswers() {
        val record = ClarifyHistoryRecord.from(
            clarify("""{"responses":[{"question":"Which env?","choices_offered":["dev","prod"],"status":"answered","user_response":"prod"},{"question":"Run tests?","status":"skipped"}],"outcome":"answered"}"""),
        )!!
        assertEquals(listOf("prod", "Skipped"), record.questions.map { it.answer })
        assertEquals(listOf("dev", "prod"), record.questions[0].choices)
        assertNull(record.notice)
    }

    @Test
    fun singleQuestionAndMultiSelectString() {
        val record = ClarifyHistoryRecord.from(clarify("""{"question":"Which checks?","user_response":"[\"lint\",\"unit\"]"}"""))!!
        assertEquals("lint, unit", record.questions.single().answer)
    }

    @Test
    fun timedOutQuestionGetsNotice() {
        val record = ClarifyHistoryRecord.from(
            clarify("""{"responses":[{"status":"unanswered"}],"outcome":"timed_out"}""", input = """{"questions":[{"question":"Deploy now?","choices":["yes","no"]}]}"""),
        )!!
        assertEquals("Deploy now?", record.questions.single().question)
        assertEquals(listOf("yes", "no"), record.questions.single().choices)
        assertNull(record.questions.single().answer)
        assertEquals("This question is no longer active — Hermes timed it out and continued.", record.notice)
    }

    @Test
    fun keepsToolCardForUnknownShapes() {
        assertNull(ClarifyHistoryRecord.from(clarify("""{"error":"boom"}""")))
        assertNull(ClarifyHistoryRecord.from(clarify("not json")))
        assertNull(ClarifyHistoryRecord.from(clarify("""{"responses":[{"question":"q","status":"weird"}]}""")))
        assertNull(ClarifyHistoryRecord.from(clarify("""{"question":"q","user_response":"a"}""", running = true)))
        assertNull(ClarifyHistoryRecord.from(ToolCall("t", "terminal", null, """{"question":"q","user_response":"a"}""")))
    }
}
