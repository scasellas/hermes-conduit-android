package app.hermesconduit.android.model

data class SessionSummary(
    /** Id used for REST and `session.resume` (stored id when known). */
    val id: String,
    val title: String,
    val preview: String?,
    val model: String?,
    val profile: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val unread: Boolean,
    val messageCount: Int?,
    /** Epoch milliseconds of the latest activity, if any. */
    val lastActiveMs: Long?,
    val source: String?,
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL, REASONING }

data class ToolCall(
    val id: String?,
    val name: String,
    val input: String?,
    val output: String? = null,
    val running: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val timestampMs: Long? = null,
    val streaming: Boolean = false,
)

data class ApprovalRequest(
    /** Queue id sent back as `request_id` to `approval.respond`. */
    val requestId: String,
    val sessionId: String,
    val command: String?,
    val description: String?,
    val choices: List<String>,
)

data class ClarifyQuestion(
    val id: String?,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean,
)

data class ClarifyRequest(
    /** Server request id (`srq-…`) or legacy request id. */
    val requestId: String,
    val sessionId: String,
    val questions: List<ClarifyQuestion>,
    val isServerRequest: Boolean,
)

data class ModelProvider(
    val slug: String,
    val name: String,
    val models: List<String>,
    val isCurrent: Boolean,
)

data class ModelOptions(
    val model: String?,
    val provider: String?,
    val providers: List<ModelProvider>,
)
