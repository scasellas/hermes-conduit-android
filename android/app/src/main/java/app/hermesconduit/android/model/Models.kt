package app.hermesconduit.android.model

@kotlinx.serialization.Serializable
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
    /** Classified source (iOS SessionSource raw value): chat, voice, voice_job, discord, telegram, api, webhook, cron, other. */
    val sourceKind: String = "chat",
    val parentId: String? = null,
    val runtimeId: String? = null,
    /** Every id this row is known by (runtime, stored, session_key). */
    val alternateIds: Set<String> = emptySet(),
    val lastReadAtMs: Long? = null,
) {
    fun matches(id: String?) = id != null && (id == this.id || id in alternateIds)
}

data class ProjectSummary(
    val id: String,
    val title: String,
    val primaryPath: String?,
    val isHome: Boolean,
    val isAuto: Boolean,
    val sessionCount: Int,
    val previewSessions: List<SessionSummary>,
)

data class ProjectLane(val id: String, val title: String, val sessions: List<SessionSummary>, val path: String?)

data class SearchMatch(val sessionId: String, val title: String, val snippet: String, val lastActiveMs: Long?)

@kotlinx.serialization.Serializable
enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL, REASONING }

@kotlinx.serialization.Serializable
data class ToolCall(
    val id: String?,
    val name: String,
    val input: String?,
    val output: String? = null,
    val running: Boolean = false,
)

@kotlinx.serialization.Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val timestampMs: Long? = null,
    val streaming: Boolean = false,
    /** Durable `messages.id` reactions address (null for live rows). */
    val rowId: Long? = null,
    val reactions: List<MessageReaction> = emptyList(),
    /** A mid-turn steer delivered to the running turn. */
    val isSteer: Boolean = false,
    /** Special system rows: "slash", "model_change", "review", "compaction". */
    val kind: String? = null,
)

@kotlinx.serialization.Serializable
data class MessageReaction(val emoji: String, val author: String, val at: Double? = null) {
    val isFromUser: Boolean get() = author == USER

    companion object {
        const val USER = "user"
        const val AGENT = "agent"
        /** The six reactions the picker offers, in order. */
        val tapbacks = listOf("👍", "👎", "❤️", "😂", "😮", "😢")

        /** Hermes' `set_message_reaction` rules: one reaction per author; the same emoji retracts. */
        fun applying(emoji: String?, author: String, to: List<MessageReaction>, at: Double = System.currentTimeMillis() / 1000.0): List<MessageReaction> {
            val previous = to.firstOrNull { it.author == author }
            val result = to.filter { it.author != author }.toMutableList()
            if (!emoji.isNullOrEmpty() && previous?.emoji != emoji) result += MessageReaction(emoji, author, at)
            return result
        }
    }
}

/** A sudo / secret / vault-unlock server request answered with a masked value. */
data class InputPrompt(
    val requestId: String,
    val sessionId: String,
    /** "sudo", "secret" or "vault.unlock_prompt". */
    val kind: String,
    val command: String = "",
    val envVar: String = "",
    val prompt: String = "",
    val displayName: String = "",
) {
    companion object {
        val kinds = setOf("sudo", "secret", "vault.unlock_prompt")
    }
}

data class SlashCommand(
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val argsHint: String? = null,
)

data class ContextCategory(val id: String, val label: String, val tokens: Long, val color: String)

data class ContextBreakdown(
    val categories: List<ContextCategory>,
    val contextMax: Long,
    val contextPercent: Double,
    val contextUsed: Long,
    val estimatedTotal: Long,
    val model: String?,
) {
    val resolvedUsed: Long get() = when {
        contextUsed > 0 -> contextUsed
        estimatedTotal > 0 -> estimatedTotal
        else -> categories.sumOf { it.tokens }
    }

    /** 0…100; Hermes has reported both fractions and percentages. */
    val resolvedPercent: Double get() = when {
        contextPercent > 0 -> if (contextPercent <= 1.0) contextPercent * 100 else contextPercent
        contextMax > 0 -> resolvedUsed * 100.0 / contextMax
        else -> 0.0
    }
}

data class DelegateAgent(
    val id: String,
    val goal: String,
    val status: String,
    val model: String?,
    val currentTool: String?,
    val summary: String?,
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
