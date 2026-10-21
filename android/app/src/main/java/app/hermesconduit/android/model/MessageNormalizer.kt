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
            sourceKind = classifySource(row),
            parentId = row.firstString("parent_session_id", "parentSessionId", "_lineage_root_id")?.takeIf { it != id },
            runtimeId = runtimeId,
            alternateIds = listOf("session_id", "id", "stored_session_id", "runtime_session_id", "session_key")
                .mapNotNull { row[it].str?.takeIf(String::isNotBlank) }.toSet(),
            lastReadAtMs = epochMillis(row["last_read_at"]),
        )
    }

    /** Port of `classifySource`: cron first, then external platforms, then the chat whitelist. */
    fun classifySource(row: JsonObject): String {
        val direct = listOf("source", "origin", "platform").mapNotNull { row[it].str?.lowercase()?.takeIf(String::isNotEmpty) }
        val handoff = row["handoff_platform"].str?.lowercase().orEmpty()
        val cronMeta = listOf("cron_job_id", "cron_run_id", "scheduled_job_id", "schedule_id").any { row[it] != null && row[it] !is kotlinx.serialization.json.JsonNull }
        val sessionId = (row["session_id"].str ?: row["id"].str ?: "").lowercase()
        val all = direct + listOfNotNull(handoff.takeIf { it.isNotEmpty() })
        if (cronMeta || Regex("^(?:session[_:-])?cron[_:-]").containsMatchIn(sessionId) ||
            all.any { it == "cron" || it == "scheduled" || it == "scheduler" || it.startsWith("cron:") || it.startsWith("cron_") || it.startsWith("scheduled:") }
        ) return "cron"
        fun classify(source: String) = when (source) {
            "discord" -> "discord"
            "telegram" -> "telegram"
            "webhook" -> "webhook"
            "api", "api-server", "api_server", "server" -> "api"
            else -> null
        }
        (direct.firstNotNullOfOrNull(::classify) ?: classify(handoff))?.let { return it }
        val fallback = direct.firstOrNull() ?: handoff
        val chat = setOf("", "desktop", "gateway", "tui", "chat", "web", "dashboard", "mobile", "cli", "local", "default", "unknown")
        return if (fallback in chat) "chat" else "other"
    }

    fun projects(payload: JsonElement?): List<ProjectSummary> {
        val records = payload.obj?.get("projects").arr ?: payload.arr ?: return emptyList()
        return records.mapNotNull { project(it) }
    }

    fun project(item: JsonElement?): ProjectSummary? {
        val o = item.obj ?: return null
        val id = o["id"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isHome = (o["isNoProject"] ?: o["is_no_project"]).bool ?: false
        val previews = (o["preview_sessions"] ?: o["previewSessions"]).arr ?: JsonArray(emptyList())
        val folder = o["folders"].arr?.firstNotNullOfOrNull { it.obj?.get("path").str?.takeIf(String::isNotBlank) }
        val repo = if (isHome) null else o["repos"].arr?.firstNotNullOfOrNull { it.obj?.get("path").str?.takeIf(String::isNotBlank) }
        return ProjectSummary(
            id = id,
            title = o.firstString("label", "name", "title") ?: "Untitled project",
            primaryPath = o.firstString("path", "primary_path", "primaryPath") ?: folder ?: repo,
            isHome = isHome,
            isAuto = (o["isAuto"] ?: o["is_auto"]).bool ?: (!isHome && !id.startsWith("p_")),
            sessionCount = ((o["session_count"] ?: o["sessionCount"]).long?.toInt()) ?: previews.size,
            previewSessions = sessions(previews),
        )
    }

    fun projectLanes(payload: JsonElement?): List<ProjectLane> {
        val project = payload.obj?.get("project").obj ?: return emptyList()
        val repos = project["repos"].arr ?: JsonArray(emptyList())
        val lanes = mutableListOf<ProjectLane>()
        for (repo in repos) {
            val r = repo.obj ?: continue
            val repoLabel = r.firstString("label", "path") ?: "Workspace"
            for (group in r["groups"].arr ?: JsonArray(emptyList())) {
                val g = group.obj ?: continue
                val label = g.firstString("label", "path") ?: "Sessions"
                val path = g.firstString("path", "cwd")
                val list = sessions(g["sessions"].arr ?: JsonArray(emptyList()))
                if (list.isEmpty()) continue
                lanes += ProjectLane(g["id"].str ?: "${r.hashCode()}-$label", if (repos.size > 1) "$repoLabel · $label" else label, list, path?.takeIf { it != label })
            }
        }
        if (lanes.isEmpty()) {
            val previews = sessions((project["preview_sessions"] ?: project["previewSessions"]).arr ?: JsonArray(emptyList()))
            if (previews.isNotEmpty()) lanes += ProjectLane("recent", "Recent", previews, null)
        }
        return lanes
    }

    fun searchMatches(result: JsonObject?): List<SearchMatch> {
        val seen = mutableSetOf<String>()
        return result?.get("results").arr?.mapNotNull { row ->
            val o = row.obj ?: return@mapNotNull null
            val id = (o["session_id"].str ?: o["id"].str)?.trim()?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return@mapNotNull null
            SearchMatch(
                sessionId = id,
                title = o["title"].str?.trim().orEmpty(),
                snippet = o["snippet"].str.orEmpty().replace(">>>", "").replace("<<<", "").split(Regex("\\s+")).joinToString(" ").take(160),
                lastActiveMs = epochMillis(o["last_active"]) ?: epochMillis(o["session_started"]) ?: epochMillis(o["started_at"]),
            )
        }.orEmpty()
    }

    /** Port of `normalizedGeneratedSessionTitle`. */
    fun generatedTitle(value: String): String? {
        val withoutThinking = value.replace(Regex("(?is)<think\\b[^>]*>.*?</think\\s*>"), "")
        var title = withoutThinking.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (title.lowercase().startsWith("title:")) title = title.drop(6).trim()
        title = title.trim('"', '\'', '`', ' ')
        return title.takeIf { it.isNotEmpty() }?.take(80)
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
            val rowId = row["row_id"].long ?: (row["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.long
            val reactions = reactions(displayMetadata(row["display_metadata"])?.get("reactions"))
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
                    result += ChatMessage(id, role, content, reasoning, calls, timestamp, rowId = rowId, reactions = reactions)
                }
                MessageRole.USER -> {
                    val steer = displayKind?.trim() == "steer"
                    if (steer) content = steerText(content)
                    result += ChatMessage(id, role, content, timestampMs = timestamp, rowId = rowId, reactions = reactions, isSteer = steer)
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

    /** `display_metadata` arrives as an object or a JSON string. */
    fun displayMetadata(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> value.str?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }

    fun reactions(value: JsonElement?): List<app.hermesconduit.android.model.MessageReaction> = value.arr?.mapNotNull { entry ->
        val obj = entry.obj ?: return@mapNotNull null
        val emoji = obj["emoji"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val author = obj["author"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        app.hermesconduit.android.model.MessageReaction(emoji, author, obj["at"].double)
    }.orEmpty()

    fun inputPrompt(requestId: String, method: String, params: JsonObject?): InputPrompt? {
        if (method !in InputPrompt.kinds || requestId.isEmpty()) return null
        val session = params?.get("session_id").str?.trim().orEmpty()
        if (session.isEmpty()) return null
        fun text(key: String) = params?.get(key).str?.trim().orEmpty()
        return InputPrompt(
            requestId = requestId,
            sessionId = session,
            kind = method,
            command = text("command"),
            envVar = text("env_var"),
            prompt = text("prompt"),
            displayName = text("display_name").ifEmpty { text("backend") },
        )
    }

    fun contextBreakdown(result: JsonObject?): ContextBreakdown = ContextBreakdown(
        categories = result?.get("categories").arr?.mapNotNull { c ->
            val o = c.obj ?: return@mapNotNull null
            val id = o["id"].str ?: return@mapNotNull null
            val label = o["label"].str ?: return@mapNotNull null
            ContextCategory(id, label, o["tokens"].long ?: 0, o["color"].str ?: "amber")
        }.orEmpty(),
        contextMax = result?.get("context_max").long ?: 0,
        contextPercent = result?.get("context_percent").double ?: 0.0,
        contextUsed = result?.get("context_used").long ?: 0,
        estimatedTotal = result?.get("estimated_total").long ?: 0,
        model = result?.get("model").str,
    )

    fun delegateAgents(result: JsonElement?): List<DelegateAgent> {
        val rows = result.obj?.let { it["subagents"].arr ?: it["agents"].arr ?: it["items"].arr } ?: result.arr ?: return emptyList()
        return rows.mapIndexedNotNull { index, element ->
            val o = element.obj ?: return@mapIndexedNotNull null
            DelegateAgent(
                id = o.firstString("subagent_id", "id", "agent_id") ?: "agent-$index",
                goal = o.firstString("goal", "task") ?: "",
                status = o.firstString("status") ?: "running",
                model = o.firstString("model"),
                currentTool = o.firstString("tool_name", "tool", "current_tool"),
                summary = o.firstString("summary", "text", "message"),
            )
        }
    }

    // region Slash commands

    fun normalizedSlashName(value: String) = value.trim().trimStart('/').lowercase()

    /** `/name rest` → (name, argument, cleaned) or null. */
    fun parseSlash(text: String): Triple<String, String, String>? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return null
        val cleaned = trimmed.trimStart('/').trim()
        val parts = cleaned.split(Regex("\\s+"), limit = 2)
        val first = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Triple(normalizedSlashName(first), parts.getOrElse(1) { "" }, cleaned)
    }

    fun slashCatalog(payload: JsonElement?, builtIns: List<SlashCommand>): List<SlashCommand> {
        val obj = payload.obj
        val commands = linkedMapOf<String, SlashCommand>()
        fun add(c: SlashCommand?) { if (c != null && c.name.isNotEmpty() && c.name !in commands) commands[c.name] = c }
        fun from(value: JsonElement, category: String?): SlashCommand? {
            value.arr?.let { pair ->
                val name = normalizedSlashName(pair.firstOrNull().str ?: return null)
                if (name.isEmpty()) return null
                val description = pair.getOrNull(1).str?.trim()?.takeIf { it.isNotEmpty() } ?: "Hermes command"
                return SlashCommand(name, description, category = category)
            }
            val o = value.obj ?: return null
            val name = normalizedSlashName(o.firstString("name", "command") ?: return null)
            if (name.isEmpty()) return null
            return SlashCommand(
                name = name,
                description = o.firstString("description", "desc") ?: "Hermes command",
                aliases = o["aliases"].arr?.mapNotNull { it.str?.let(::normalizedSlashName)?.takeIf(String::isNotEmpty) }.orEmpty(),
                category = category,
                argsHint = o.firstString("args_hint", "argsHint"),
            )
        }
        obj?.get("categories").arr?.forEach { category ->
            val c = category.obj ?: return@forEach
            val name = c["name"].str?.trim()
            c["pairs"].arr?.forEach { add(from(it, name)) }
        }
        obj?.get("pairs").arr?.forEach { add(from(it, "Skills & extensions")) }
        obj?.get("canon").obj?.forEach { (rawAlias, rawCanonical) ->
            val alias = normalizedSlashName(rawAlias)
            val canonical = normalizedSlashName(rawCanonical.str.orEmpty())
            val command = commands[canonical] ?: return@forEach
            if (alias.isNotEmpty() && alias != canonical && alias !in command.aliases) {
                commands[canonical] = command.copy(aliases = (command.aliases + alias).sorted())
            }
        }
        builtIns.forEach { if (it.name !in commands) commands[it.name] = it }
        return commands.values.sortedBy { it.name }
    }

    // endregion

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
