package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface StreamEvent {
    val sessionId: String

    data class GatewayReady(override val sessionId: String = "") : StreamEvent
    data class MessageStart(override val sessionId: String) : StreamEvent
    data class MessageDelta(override val sessionId: String, val text: String) : StreamEvent
    data class ReasoningDelta(override val sessionId: String, val text: String) : StreamEvent
    data class MessageComplete(
        override val sessionId: String,
        val messageId: String?,
        val content: String?,
        val reasoning: String?,
    ) : StreamEvent
    data class Error(override val sessionId: String, val message: String) : StreamEvent
    data class Interrupted(override val sessionId: String) : StreamEvent
    data class Busy(override val sessionId: String, val busy: Boolean) : StreamEvent
    data class Title(override val sessionId: String, val storedSessionId: String, val title: String) : StreamEvent
    data class ToolStart(override val sessionId: String, val name: String, val input: String?, val toolId: String?) : StreamEvent
    data class ToolComplete(override val sessionId: String, val name: String, val output: String?, val toolId: String?) : StreamEvent
    data class Approval(override val sessionId: String, val request: ApprovalRequest) : StreamEvent
    data class Clarify(override val sessionId: String, val request: ClarifyRequest) : StreamEvent
    /** A server→client request (approval/clarify/…) was withdrawn. */
    data class RequestCancelled(override val sessionId: String, val requestId: String, val method: String, val reason: String) : StreamEvent
    data class ContextUpdate(override val sessionId: String, val percent: Double, val used: Long, val max: Long) : StreamEvent
    data class ModelUpdate(override val sessionId: String, val model: String, val provider: String) : StreamEvent
    data class Status(override val sessionId: String, val kind: String, val text: String?) : StreamEvent
    data class InputPromptRequest(override val sessionId: String, val prompt: InputPrompt) : StreamEvent
    data class Reaction(override val sessionId: String, val rowId: Long, val reactions: List<MessageReaction>, val role: String) : StreamEvent
    data class Review(override val sessionId: String, val summary: String) : StreamEvent
    data class Cwd(override val sessionId: String, val cwd: String) : StreamEvent
    data class SessionInfo(override val sessionId: String, val info: JsonObject) : StreamEvent
    data class Subagent(override val sessionId: String, val type: String, val payload: JsonObject) : StreamEvent
    data class Unparsed(override val sessionId: String, val type: String) : StreamEvent
}

/** Port of the iOS `StreamEventParser`: `event` notification params → [StreamEvent]. */
object StreamEventParser {
    fun parse(params: JsonElement?): StreamEvent? {
        val obj = params.obj ?: return null
        val type = obj["type"].str.orEmpty()
        val sessionId = obj["session_id"].str.orEmpty()
        val payload = obj["payload"].obj

        return when (type) {
            "gateway.ready" -> StreamEvent.GatewayReady(sessionId)
            "message.start" -> StreamEvent.MessageStart(sessionId)
            "message.delta" -> {
                val text = payload?.get("text").str ?: obj["text"].str ?: ""
                if (text.isEmpty()) null else StreamEvent.MessageDelta(sessionId, text)
            }
            "reasoning.delta", "message.reasoning", "message.reasoning.delta" -> {
                val text = payload?.get("reasoning").str
                    ?: payload?.get("reasoning_content").str
                    ?: payload?.get("text").str
                    ?: payload?.get("content").str
                    ?: obj["reasoning"].str
                    ?: obj["text"].str
                    ?: ""
                if (text.isEmpty()) null else StreamEvent.ReasoningDelta(sessionId, text)
            }
            "message.complete" -> StreamEvent.MessageComplete(
                sessionId = sessionId,
                messageId = payload?.get("message_id").str
                    ?: payload?.get("id").str
                    ?: payload?.get("message").obj?.get("id").str
                    ?: obj["message_id"].str
                    ?: obj["id"].str,
                content = payload?.get("content").str ?: payload?.get("text").str ?: payload?.get("rendered").str,
                reasoning = payload?.get("reasoning").str,
            )
            "error" -> StreamEvent.Error(sessionId, payload?.get("message").str ?: "Hermes reported an error.")
            "message.interrupted", "session.interrupted" -> StreamEvent.Interrupted(sessionId)
            "session.busy" -> StreamEvent.Busy(sessionId, payload?.get("busy").bool ?: false)
            "status.update" -> {
                if (sessionId.isEmpty()) null
                else StreamEvent.Status(sessionId, payload?.get("kind").str.orEmpty(), payload?.get("text").str)
            }
            "session.title" -> {
                val stored = payload?.get("session_id").str.orEmpty()
                val title = payload?.get("title").str?.trim().orEmpty()
                if (stored.isEmpty() || title.isEmpty()) null else StreamEvent.Title(sessionId, stored, title)
            }
            "tool.start", "tool_call" -> StreamEvent.ToolStart(
                sessionId = sessionId,
                name = payload?.get("name").str.orEmpty(),
                input = payload?.let { p ->
                    listOf("args_text", "context", "input", "arguments", "args").firstNotNullOfOrNull { p[it].descriptive }
                },
                toolId = payload?.let(::toolId),
            )
            "tool.complete", "tool_result" -> StreamEvent.ToolComplete(
                sessionId = sessionId,
                name = payload?.get("name").str.orEmpty(),
                output = payload?.get("output").descriptive ?: payload?.get("result").descriptive,
                toolId = payload?.let(::toolId),
            )
            "clarify", "clarify.request" -> payload
                ?.let { MessageNormalizer.clarifyRequest(it, sessionId, serverRequestId = null) }
                ?.let { StreamEvent.Clarify(sessionId, it) }
            "clarify.expire" -> {
                val id = (payload?.get("request_id").str ?: payload?.get("requestId").str ?: "").trim()
                if (id.isEmpty()) null else StreamEvent.RequestCancelled(sessionId, id, "clarify", "timeout")
            }
            "request.cancel" -> {
                val id = payload?.get("id").str?.trim().orEmpty()
                if (id.isEmpty()) null
                else StreamEvent.RequestCancelled(
                    sessionId,
                    id,
                    payload?.get("method").str.orEmpty(),
                    payload?.get("reason").str.orEmpty(),
                )
            }
            "approval.request" -> payload
                ?.let { MessageNormalizer.approvalRequest(it, sessionId) }
                ?.let { StreamEvent.Approval(sessionId, it) }
            "context.update", "session.context" -> StreamEvent.ContextUpdate(
                sessionId,
                payload?.get("context_percent").double ?: 0.0,
                payload?.get("context_used").long ?: 0,
                payload?.get("context_max").long ?: 0,
            )
            "message.reaction" -> {
                val rowId = payload?.get("row_id").long ?: 0
                if (rowId <= 0) null
                else StreamEvent.Reaction(sessionId, rowId, MessageNormalizer.reactions(payload?.get("reactions")), payload?.get("role").str.orEmpty())
            }
            "review.summary" -> payload?.let { p ->
                val text = p.firstString("summary", "text", "message", "content") ?: return@let null
                StreamEvent.Review(sessionId, text)
            }
            "cwd.update", "workspace.update" -> StreamEvent.Cwd(sessionId, payload?.get("cwd").str.orEmpty())
            "session.info" -> StreamEvent.SessionInfo(sessionId, payload ?: JsonObject(emptyMap()))
            "model.update" -> StreamEvent.ModelUpdate(
                sessionId,
                payload?.get("model").str.orEmpty(),
                payload?.get("provider").str.orEmpty(),
            )
            else -> if (type.startsWith("subagent.") && payload != null) StreamEvent.Subagent(sessionId, type, payload)
            else StreamEvent.Unparsed(sessionId, type)
        }
    }

    private fun toolId(payload: JsonObject): String? =
        listOf("tool_id", "tool_call_id", "call_id")
            .firstNotNullOfOrNull { payload[it].str?.trim()?.takeIf(String::isNotEmpty) }
}
