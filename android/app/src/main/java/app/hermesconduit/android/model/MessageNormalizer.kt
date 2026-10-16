package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Port of the core of the iOS `MessageNormalizer` (sessions, transcripts, decisions). */
object MessageNormalizer {

    private val activityKeys = arrayOf(
        "last_active", "lastActive", "updated_at", "updatedAt", "updated", "last_message_at",
        "lastMessageAt", "latest_message_at", "latestMessageAt", "modified_at", "modifiedAt",
        "created_at", "createdAt", "timestamp", "time",
    )

    private val messageTimeKeys = arrayOf(
        "timestamp", "timestamp_ms", "timestampMs", "created_at", "createdAt", "created", "created_ms",
        "createdMs", "time", "ts", "date", "datetime", "updated_at", "updatedAt", "sent_at", "sentAt",
        "last_active", "lastActive",
    )

    private val systemNoticeKinds = setOf(
        "model_switch", "personality_switch", "auto_continue", "async_delegation_complete",
        "process_complete", "internal_notification",
    )

    // region Sessions

    fun sessions(rows: JsonArray): List<SessionSummary> = rows.mapNotNull { it.obj?.let(::session) }

    fun session(row: JsonObject): SessionSummary? {
        val runtimeId = row["session_id"].str?.takeIf { it.isNotBlank() }
        val stored = row.firstString("stored_session_id", "storedSessionId")
            ?: if (runtimeId != null) row["id"].str?.takeIf { it.isNotBlank() } else null
        val id = stored ?: runtimeId ?: row["id"].str?.takeIf { it.isNotBlank() } ?: return null
        val preview = row["preview"].str?.trim()?.takeIf { it.isNotEmpty() }
        val title = row["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: preview ?: "Untitled chat"
        return SessionSummary(
            id = id,
            title = title,
            preview = preview,
            model = row["model"].str,
            profile = row.firstString("profile", "profile_name", "profileName", "profile_id", "profileId"),
            pinned = row["pinned"].bool ?: false,
            archived = row["archived"].bool ?: false,
            unread = row["unread"].bool ?: false,
            messageCount = (row["message_count"] ?: row["messageCount"]).long?.toInt(),
            lastActiveMs = activityKeys.firstNotNullOfOrNull { epochMillis(row[it]) },
            source = row.firstString("source", "origin", "platform", "handoff_platform"),
        )
    }

    // endregion

    // region Transcript

    fun messages(rows: JsonArray): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        rows.forEachIndexed { index, element ->
            val row = unwrap(element.obj ?: return@forEachIndexed)
            val displayKind = row["display_kind"].str
            if (displayKind == "hidden") return@forEachIndexed

            val rawRole = (row["role"].str ?: row["type"].str ?: "").lowercase()
            val id = (row["id"] ?: row["message_id"]).str ?: "row-$index"
            val timestamp = timestamp(row)
            var content = (row["display_content"].str ?: contentText(row["content"] ?: row["text"])).orEmpty()

            val role = when {
                displayKind != null && displayKind in systemNoticeKinds -> MessageRole.SYSTEM
                rawRole == "user" -> MessageRole.USER
                rawRole == "assistant" -> MessageRole.ASSISTANT
                rawRole == "system" -> MessageRole.SYSTEM
                rawRole == "reasoning" || rawRole == "thinking" -> MessageRole.REASONING
                rawRole == "tool" || "tool_result" in rawRole || "tool-result" in rawRole -> MessageRole.TOOL
                else -> return@forEachIndexed
            }

            when (role) {
                MessageRole.TOOL -> {
                    val output = toolOutput(row)
                    if (!attachToolResult(result, row, output)) {
                        result += ChatMessage(
                            id = id,
                            role = MessageRole.TOOL,
                            content = output.orEmpty(),
                            toolCalls = listOf(ToolCall(null, toolName(row) ?: "tool", null, output)),
                            timestampMs = timestamp,
                        )
                    }
                }
                MessageRole.REASONING -> {
                    // Fold a standalone reasoning row into the next assistant turn's
                    // trace by keeping it as its own collapsible message.
                    if (content.isNotBlank()) {
                        result += ChatMessage(id, MessageRole.ASSISTANT, "", reasoning = content, timestampMs = timestamp)
                    }
                }
                MessageRole.ASSISTANT -> {
                    val reasoning = row.firstString("reasoning", "reasoning_content")
                    val calls = (row["tool_calls"] ?: row["tools"]).arr?.mapNotNull { it.obj?.let(::toolCall) }.orEmpty()
                    if (content.isBlank() && reasoning == null && calls.isEmpty()) return@forEachIndexed
                    result += ChatMessage(id, role, content, reasoning, calls, timestamp)
                }
                MessageRole.USER -> {
                    if (displayKind?.trim() == "steer") content = steerText(content)
                    result += ChatMessage(id, role, content, timestampMs = timestamp)
                }
                MessageRole.SYSTEM -> if (content.isNotBlank()) {
                    result += ChatMessage(id, role, content, timestampMs = timestamp)
                }
            }
        }
        return result
    }

    /**
     * Hermes wraps a delivered steer as
     * `[OUT-OF-BAND USER MESSAGE — …]\n<text>\n[/OUT-OF-BAND USER MESSAGE]`.
     */
    fun steerText(text: String): String {
        var body = text.trim()
        val opener = "[OUT-OF-BAND USER MESSAGE"
        val closer = "[/OUT-OF-BAND USER MESSAGE]"
        val headerEnd = body.substringBefore('\n').indexOf(']')
        if (body.startsWith(opener) && headerEnd >= 0) body = body.substring(headerEnd + 1)
        if (body.endsWith(closer)) body = body.dropLast(closer.length)
        return body.trim()
    }

    private fun unwrap(row: JsonObject): JsonObject {
        val inner = row["message"].obj ?: row["payload"].obj
            ?: row["data"].obj?.takeIf { it.containsKey("role") || it.containsKey("type") }
            ?: return row
        return JsonObject(row + inner)
    }

    /** String, array of parts, or `{text|content}` object → plain text. */
    fun contentText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.str
        is JsonArray -> element.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.str
                is JsonObject -> {
                    val type = part["type"].str.orEmpty()
                    if ("image" in type) "[image]" else part.firstString("text", "content")
                }
                else -> null
            }
        }.joinToString("\n").takeIf { it.isNotEmpty() }
        is JsonObject -> element.firstString("text", "content")
    }

    private fun toolCall(obj: JsonObject): ToolCall {
        val function = obj["function"].obj
        val name = obj.firstString("name", "tool_name")
            ?: function?.firstString("name")
            ?: obj["input"].obj?.firstString("name")
            ?: "tool"
        val args = function?.get("arguments").descriptive
            ?: listOf("input", "arguments", "args", "command", "code").firstNotNullOfOrNull { obj[it].descriptive }
        return ToolCall(obj.firstString("id", "tool_call_id", "call_id"), name, args)
    }

    private fun toolName(row: JsonObject): String? =
        row.firstString("tool_name", "name") ?: row["function"].obj?.firstString("name") ?: row["tool"].obj?.firstString("name")

    private fun toolOutput(row: JsonObject): String? =
        listOf("result", "output", "tool_result", "response", "data", "stdout").firstNotNullOfOrNull { row[it].descriptive }
            ?: contentText(row["content"] ?: row["text"])

    /** Attaches a tool result to the latest assistant call it answers. */
    private fun attachToolResult(messages: MutableList<ChatMessage>, row: JsonObject, output: String?): Boolean {
        val callId = row.firstString("tool_call_id", "call_id", "tool_use_id")
        val name = toolName(row)
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.role != MessageRole.ASSISTANT || message.toolCalls.isEmpty()) continue
            val index = message.toolCalls.indexOfFirst { call ->
                call.output == null && (if (callId != null) call.id == callId else call.name == name)
            }
            if (index >= 0) {
                val calls = message.toolCalls.toMutableList()
                calls[index] = calls[index].copy(output = output ?: "")
                messages[i] = message.copy(toolCalls = calls)
                return true
            }
            if (callId == null) return false
        }
        return false
    }

    private fun timestamp(row: JsonObject): Long? {
        messageTimeKeys.firstNotNullOfOrNull { epochMillis(row[it]) }?.let { return it }
        val meta = row["metadata"].obj ?: row["meta"].obj ?: return null
        return messageTimeKeys.firstNotNullOfOrNull { epochMillis(meta[it]) }
    }

    /** Seconds, ms, µs or ns since the epoch, or an ISO-8601 string → epoch ms. */
    fun epochMillis(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        val number = primitive.double ?: primitive.content.toDoubleOrNull()
        if (number != null) {
            if (number <= 0) return null
            return when {
                number < 1e11 -> (number * 1000).toLong()
                number < 1e14 -> number.toLong()
                number < 1e17 -> (number / 1000).toLong()
                else -> (number / 1_000_000).toLong()
            }
        }
        val text = primitive.str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    // endregion

    // region Decisions

    fun approvalRequest(payload: JsonObject, sessionId: String): ApprovalRequest? {
        val session = (payload["session_id"].str ?: sessionId).trim()
        if (session.isEmpty()) return null
        val choices = payload["choices"].arr
            ?.mapNotNull { it.str?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        val smartDenied = payload["smart_denied"].bool == true
        val allowPermanent = payload["allow_permanent"].bool != false
        val resolvedChoices = choices
            ?: (if (smartDenied) listOf("once", "deny") else listOf("once", "session", "always", "deny"))
                .filter { allowPermanent || it != "always" }
        return ApprovalRequest(
            requestId = payload.firstString("request_id", "requestId")?.trim().orEmpty(),
            sessionId = session,
            command = payload.firstString("command", "code", "text")?.trim(),
            description = payload.firstString("description", "message", "prompt")?.trim() ?: "Approval required",
            choices = resolvedChoices,
        )
    }

    fun clarifyRequest(payload: JsonObject, sessionId: String, serverRequestId: String?): ClarifyRequest? {
        val requestId = serverRequestId ?: payload.firstString("request_id", "requestId", "id") ?: return null
        val session = (payload["session_id"].str ?: sessionId).trim()
        val questions = payload["questions"].arr?.mapNotNull { q ->
            val obj = q.obj ?: return@mapNotNull null
            val text = obj.firstString("question", "prompt", "text") ?: return@mapNotNull null
            ClarifyQuestion(
                id = obj.firstString("qid", "question_id", "id"),
                question = text,
                choices = choices(obj["choices"] ?: obj["options"]),
                multiSelect = obj["multi_select"].bool == true,
            )
        }?.takeIf { it.isNotEmpty() } ?: run {
            val text = payload.firstString("question", "prompt", "text") ?: return null
            listOf(
                ClarifyQuestion(
                    id = null,
                    question = text,
                    choices = choices(payload["choices"] ?: payload["options"]),
                    multiSelect = payload["multi_select"].bool == true,
                ),
            )
        }
        return ClarifyRequest(requestId, session, questions, isServerRequest = serverRequestId != null)
    }

    private fun choices(element: JsonElement?): List<String> = element.arr?.mapNotNull { choice ->
        choice.str ?: choice.obj?.firstString("label", "title", "text", "name", "value")
    }.orEmpty()

    // endregion

    fun modelOptions(result: JsonObject): ModelOptions = ModelOptions(
        model = result["model"].str,
        provider = result["provider"].str,
        providers = result["providers"].arr?.mapNotNull { element ->
            val p = element.obj ?: return@mapNotNull null
            val slug = p.firstString("slug", "id", "name") ?: return@mapNotNull null
            ModelProvider(
                slug = slug,
                name = p.firstString("name") ?: slug,
                models = p["models"].arr?.mapNotNull { m -> m.str ?: m.obj?.firstString("id", "model", "name") }.orEmpty(),
                isCurrent = p["is_current"].bool == true,
            )
        }.orEmpty(),
    )
}
