package app.hermesconduit.android.ui

import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.ToolCall
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.str

data class ChatState(
    /** Stored session id (REST), null for a chat not created yet. */
    val sessionId: String? = null,
    /** Runtime id returned by `session.create`/`session.resume`. */
    val runtimeId: String? = null,
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val hasEarlier: Boolean = false,
    val loadedOffset: Int = 0,
    val model: String? = null,
    val provider: String? = null,
    val contextPercent: Double? = null,
    val approval: ApprovalRequest? = null,
    val clarify: ClarifyRequest? = null,
    val modelConfirm: ModelConfirm? = null,
    val inputPrompt: app.hermesconduit.android.model.InputPrompt? = null,
    val cwd: String? = null,
    val reasoningEffort: String? = null,
    val fast: Boolean = false,
    val yolo: Boolean = false,
    val activeAgents: Int = 0,
    /** Text to place in the composer (quote, slash prefill); consumed by the composer. */
    val prefill: String? = null,
    val compacting: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    /** The bot profile a Bot Chat runs in (null for an ordinary chat of the dashboard profile). */
    val botProfile: String? = null,
)

/** A model switch the gateway wants confirmed (`confirm_required`). */
data class ModelConfirm(val provider: String, val model: String, val message: String)

/**
 * Pure reducer for live turn events, kept free of Android types so it can be
 * unit-tested. Returns the new state for an event addressed to this chat.
 */
object ChatReducer {
    private var counter = 0L

    private fun newId() = "live-${System.nanoTime()}-${counter++}"

    fun reduce(state: ChatState, event: StreamEvent): ChatState = when (event) {
        is StreamEvent.MessageStart -> state.copy(running = true, error = null, messages = ensureStreaming(state.messages))
        is StreamEvent.MessageDelta -> state.copy(
            running = true,
            messages = updateStreaming(state.messages) { it.copy(content = it.content + event.text) },
        )
        is StreamEvent.ReasoningDelta -> state.copy(
            running = true,
            messages = updateStreaming(state.messages) { it.copy(reasoning = (it.reasoning ?: "") + event.text) },
        )
        is StreamEvent.ToolStart -> state.copy(
            running = true,
            messages = updateStreaming(state.messages) {
                it.copy(toolCalls = it.toolCalls + ToolCall(event.toolId, event.name.ifEmpty { "tool" }, event.input, running = true))
            },
        )
        is StreamEvent.ToolComplete -> state.copy(messages = completeTool(state.messages, event))
        is StreamEvent.MessageComplete -> state.copy(
            messages = finishStreaming(state.messages) { message ->
                message.copy(
                    id = event.messageId ?: message.id,
                    content = event.content?.takeIf { it.isNotBlank() } ?: message.content,
                    reasoning = event.reasoning?.takeIf { it.isNotBlank() } ?: message.reasoning,
                )
            },
        )
        is StreamEvent.Busy -> if (event.busy) state.copy(running = true)
        else state.copy(running = false, messages = finishStreaming(state.messages) { it })
        is StreamEvent.Interrupted -> state.copy(running = false, messages = finishStreaming(state.messages) { it }, clarify = null)
        is StreamEvent.Error -> state.copy(running = false, error = event.message, messages = finishStreaming(state.messages) { it })
        is StreamEvent.Approval -> state.copy(approval = event.request)
        is StreamEvent.Clarify -> state.copy(clarify = event.request)
        is StreamEvent.RequestCancelled -> state.copy(
            clarify = state.clarify?.takeUnless { it.requestId == event.requestId },
            inputPrompt = state.inputPrompt?.takeUnless { it.requestId == event.requestId },
            approval = if (event.method == "approval") null else state.approval,
        )
        is StreamEvent.ContextUpdate -> state.copy(contextPercent = event.percent)
        is StreamEvent.ModelUpdate -> {
            val changed = event.model.isNotEmpty() && state.model != null && event.model != state.model
            state.copy(
                model = event.model.ifEmpty { state.model },
                provider = event.provider.ifEmpty { state.provider },
                messages = if (changed) state.messages + ChatMessage(newId(), MessageRole.SYSTEM, "Model changed to ${event.model}", kind = "model_change")
                else state.messages,
            )
        }
        is StreamEvent.InputPromptRequest -> state.copy(inputPrompt = event.prompt)
        is StreamEvent.Reaction -> state.copy(
            messages = state.messages.map { if (it.rowId == event.rowId) it.copy(reactions = event.reactions) else it },
        )
        is StreamEvent.Review -> state.copy(messages = state.messages + ChatMessage(newId(), MessageRole.SYSTEM, event.summary, kind = "review"))
        is StreamEvent.Cwd -> state.copy(cwd = event.cwd.ifEmpty { state.cwd })
        is StreamEvent.SessionInfo -> applyInfo(state, event.info)
        is StreamEvent.Subagent -> state
        is StreamEvent.Title -> state.copy(title = event.title)
        is StreamEvent.Status -> when (event.kind) {
            "compacting" -> state.copy(compacting = true, notice = event.text ?: "Compacting context…")
            "compacted" -> state.copy(compacting = false, notice = event.text ?: "Context compacted.")
            else -> state.copy(notice = event.text ?: state.notice)
        }
        is StreamEvent.GatewayReady, is StreamEvent.Unparsed -> state
    }

    /** Applies a `session.info` / resume `info` snapshot. */
    fun applyInfo(state: ChatState, info: kotlinx.serialization.json.JsonObject): ChatState {
        fun str(vararg keys: String) = keys.firstNotNullOfOrNull { k -> info[k].str?.takeIf { it.isNotBlank() } }
        fun bool(vararg keys: String) = keys.firstNotNullOfOrNull { k -> info[k].bool }
        return state.copy(
            model = str("model") ?: state.model,
            provider = str("provider") ?: state.provider,
            cwd = str("cwd") ?: state.cwd,
            contextPercent = info["context_percent"].double ?: state.contextPercent,
            reasoningEffort = str("reasoning_effort", "reasoning") ?: state.reasoningEffort,
            fast = bool("fast", "fast_mode") ?: state.fast,
            yolo = bool("yolo") ?: state.yolo,
            activeAgents = (info["active_agents"] ?: info["active_subagents"]).let { it.long?.toInt() ?: it.arr?.size } ?: state.activeAgents,
            running = bool("running") ?: state.running,
        )
    }

    private fun ensureStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.lastOrNull()
        if (last != null && last.role == MessageRole.ASSISTANT && last.streaming) return messages
        return messages + ChatMessage(newId(), MessageRole.ASSISTANT, "", streaming = true, timestampMs = System.currentTimeMillis())
    }

    private fun updateStreaming(messages: List<ChatMessage>, update: (ChatMessage) -> ChatMessage): List<ChatMessage> {
        val list = ensureStreaming(messages).toMutableList()
        list[list.lastIndex] = update(list.last())
        return list
    }

    private fun finishStreaming(messages: List<ChatMessage>, update: (ChatMessage) -> ChatMessage): List<ChatMessage> {
        val index = messages.indexOfLast { it.streaming }
        if (index < 0) return messages
        val list = messages.toMutableList()
        val finished = update(list[index]).copy(
            streaming = false,
            toolCalls = list[index].toolCalls.map { it.copy(running = false) },
        )
        if (finished.content.isBlank() && finished.reasoning.isNullOrBlank() && finished.toolCalls.isEmpty()) list.removeAt(index)
        else list[index] = finished
        return list
    }

    private fun completeTool(messages: List<ChatMessage>, event: StreamEvent.ToolComplete): List<ChatMessage> {
        val list = messages.toMutableList()
        for (i in list.indices.reversed()) {
            val message = list[i]
            val index = message.toolCalls.indexOfFirst { call ->
                call.running && if (event.toolId != null && call.id != null) call.id == event.toolId else call.name == event.name
            }
            if (index >= 0) {
                val calls = message.toolCalls.toMutableList()
                calls[index] = calls[index].copy(output = event.output, running = false)
                list[i] = message.copy(toolCalls = calls)
                return list
            }
        }
        return list
    }
}
