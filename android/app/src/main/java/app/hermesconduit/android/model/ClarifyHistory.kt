package app.hermesconduit.android.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One question of a finished clarify call, as the read-only history card shows it. */
data class ClarifyHistoryQuestion(
    val question: String,
    val choices: List<String>,
    /** The person's answer ("Skipped" for a skipped one); null when Hermes stopped waiting. */
    val answer: String?,
)

/**
 * A finished `clarify` tool row read back from persisted history. A transcript
 * reload only brings pending cards back, so without this an answered question
 * came back as the bare tool call (iOS #394).
 */
data class ClarifyHistoryRecord(
    val questions: List<ClarifyHistoryQuestion>,
    /** Shown under the card when a question went unanswered. */
    val notice: String?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val knownStatuses = setOf(null, "answered", "skipped", "unanswered")

        /**
         * Reads every result shape Hermes has written: the current
         * `{"responses": [{question, choices_offered, status, user_response}], "outcome"}`,
         * the older batch `{"responses": [...]}` and the single-question
         * `{question, choices_offered, user_response}`. Null for anything else
         * (a running call, an error, a compact resume row), which keeps the tool card.
         */
        fun from(tool: ToolCall, tr: (String) -> String = { it }): ClarifyHistoryRecord? {
            if (!tool.name.equals("clarify", ignoreCase = true) || tool.running) return null
            val result = obj(tool.output) ?: return null
            if (result["error"] != null) return null
            val rows = when {
                result["responses"] is JsonArray -> (result["responses"] as JsonArray).mapNotNull { it as? JsonObject }
                result["question"] != null -> listOf(result)
                else -> return null
            }
            val asked = askedQuestions(obj(tool.input))
            val questions = mutableListOf<ClarifyHistoryQuestion>()
            for ((index, row) in rows.withIndex()) {
                val call = asked.getOrNull(index) ?: JsonObject(emptyMap())
                val status = text(row["status"])
                // A row status this build doesn't know keeps the tool card.
                if (status !in knownStatuses) return null
                val question = text(row["question"]) ?: text(call["question"]) ?: continue
                val offered = list(row["choices_offered"]) ?: list(call["choices"]) ?: emptyList()
                val values = list(row["user_response"]) ?: jsonList(row["user_response"])
                val answer = when {
                    status == "skipped" -> tr("Skipped")
                    !values.isNullOrEmpty() -> values.joinToString(", ")
                    text(row["user_response"]) != null -> text(row["user_response"])
                    status == "answered" -> return null
                    else -> null
                }
                questions += ClarifyHistoryQuestion(question, offered, answer)
            }
            if (questions.isEmpty()) return null
            val notice = if (questions.any { it.answer == null }) {
                val timedOut = text(result["outcome"]) == "timed_out"
                val many = questions.size > 1
                tr(
                    when {
                        timedOut && many -> "These questions are no longer active — Hermes timed them out and continued."
                        timedOut -> "This question is no longer active — Hermes timed it out and continued."
                        many -> "These questions are no longer active."
                        else -> "This question is no longer active."
                    },
                )
            } else null
            return ClarifyHistoryRecord(questions, notice)
        }

        private fun obj(text: String?): JsonObject? =
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

        /** The call's questions: current `questions[]`, or one legacy scalar `{question, choices}`. */
        private fun askedQuestions(arguments: JsonObject?): List<JsonObject> {
            arguments ?: return emptyList()
            (arguments["questions"] as? JsonArray)?.let { return it.mapNotNull { q -> q as? JsonObject } }
            return if (arguments["question"] != null) listOf(arguments) else emptyList()
        }

        private fun text(value: JsonElement?): String? =
            (value as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        private fun list(value: JsonElement?): List<String>? = (value as? JsonArray)?.mapNotNull { text(it) }

        /** A list sent as JSON text, such as `["lint","unit"]`. */
        private fun jsonList(value: JsonElement?): List<String>? {
            val raw = text(value)?.takeIf { it.startsWith("[") } ?: return null
            return runCatching { (json.parseToJsonElement(raw) as? JsonArray)?.mapNotNull { text(it) } }.getOrNull()
        }
    }
}
