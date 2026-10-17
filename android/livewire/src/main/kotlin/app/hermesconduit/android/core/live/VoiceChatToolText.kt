package app.hermesconduit.android.core.live

/**
 * What a live call's voice model hears from the chat tools (find_chat,
 * open_chat, read_chat, and GPT-Live's "Find chat:", "Open chat:" and "Read
 * chat:" delegations). Written for the model, so not localized.
 */
object VoiceChatToolText {
    const val FIND_CHAT = "find_chat"
    const val OPEN_CHAT = "open_chat"
    const val READ_CHAT = "read_chat"

    fun searchNext(count: Int, opens: Boolean): String {
        if (count == 1) {
            return if (opens) "One chat matches. If it fits what the user asked for, call open_chat with number 1, or read_chat with number 1 to talk about it."
            else "One chat matches. If it fits what the user asked for, call read_chat with number 1 to talk about it."
        }
        return (if (opens) "If one clearly fits what the user asked for, call open_chat with its number, or read_chat with its number to talk about it."
        else "If one clearly fits what the user asked for, call read_chat with its number to talk about it.") +
            " Otherwise say the titles of the likeliest two or three, with when, and ask which one."
    }

    const val NO_MATCHES = "No chats match that. Tell the user, and offer to try other words."
    const val UNKNOWN_FOR_READ = "That number isn't one of the chats find_chat returned. Call find_chat first, then read_chat with a number from its list."
    const val READ_NOTE = "Talk about this chat from what it says: answer what the user asked about it, or say in a few sentences what it was about. Don't read it out word for word unless they ask; skip code, links and long lists. If it doesn't hold what they asked about, say so."

    fun emptyChat(title: String): String {
        val plain = plainTitle(title)
        return "${if (plain.isEmpty()) "That chat" else "\"$plain\""} has no messages to read. Tell the user."
    }

    /** A chat title outside the fence: one line, clipped, without angle or square brackets. */
    fun plainTitle(raw: String): String {
        val line = raw.filter { it !in "<>[]" }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (line.length <= 120) line else line.take(119) + "…"
    }

    fun failed(reason: String) = "$reason Tell the user in a few words."

    fun searchResult(summary: String, count: Int, opens: Boolean): Map<String, String> =
        if (count == 0) mapOf("chats" to "", "message" to NO_MATCHES) else mapOf("chats" to summary, "message" to searchNext(count, opens))

    fun readResult(chat: String) = mapOf("chat" to chat, "message" to READ_NOTE)

    fun emptyReadResult(title: String) = mapOf("chat" to "", "message" to emptyChat(title))

    /** Said instead when the call's connection is gone. */
    fun fallbackText(result: Map<String, String>, name: String): String? = when (name) {
        FIND_CHAT -> when {
            !result["chats"].isNullOrEmpty() -> "[The chat search you just made found these:\n${result["chats"]}\n${result["message"].orEmpty()}]"
            !result["error"].isNullOrEmpty() -> "[The chat search you just made failed: ${result["error"]}]"
            else -> result["message"]?.let { "[The chat search you just made: $it]" }
        }
        OPEN_CHAT -> if (result["status"] == "opened" && result["message"] != null) "[The chat you just asked to open: ${result["message"]}]"
        else result["error"]?.let { "[Opening the chat failed: $it]" }
        READ_CHAT -> when {
            !result["chat"].isNullOrEmpty() -> "[The chat you just asked to read:\n${result["chat"]}\n${result["message"].orEmpty()}]"
            !result["error"].isNullOrEmpty() -> "[Reading the chat failed: ${result["error"]}]"
            else -> result["message"]?.let { "[The chat you just asked to read: $it]" }
        }
        else -> null
    }

    private val findMarker = Regex("^\\s*find\\s+(?:a\\s+|the\\s+|my\\s+)?chats?\\s*:", RegexOption.IGNORE_CASE)

    /** "Find chat: GPU setup" at the very start of a delegation. */
    fun findChatMarker(request: String): String? = findMarker.find(request)?.let { request.substring(it.range.last + 1).trim() }

    fun openChatMarker(request: String) = chatNumberMarker("open", request)
    fun readChatMarker(request: String) = chatNumberMarker("read", request)

    private fun chatNumberMarker(verb: String, request: String): String? {
        val pattern = Regex(
            "^\\s*" + verb + "\\s+(?:the\\s+|my\\s+)?(?:chats?|conversations?)\\s*(?:(?:number\\s*|#\\s*)?(\\d{1,2})\\s*(?::|$)|:\\s*(?:number\\s*|#\\s*)?(\\d{1,2})\\b)",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(request) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value
    }

    fun delegatedSearchFound(summary: String, count: Int, opens: Boolean): String {
        val next = if (count == 1) {
            if (opens) "One chat matches. If it fits what the user asked for, delegate \"Open chat: 1\", or \"Read chat: 1\" to talk about it."
            else "One chat matches. If it fits what the user asked for, delegate \"Read chat: 1\" to talk about it."
        } else {
            (if (opens) "If one clearly fits what the user asked for, delegate \"Open chat:\" with its number, like \"Open chat: 2\", or \"Read chat:\" with its number to talk about it."
            else "If one clearly fits what the user asked for, delegate \"Read chat:\" with its number, like \"Read chat: 2\", to talk about it.") +
                " Otherwise say the titles of the likeliest two or three, with when, and ask which one; don't read the lines out."
        }
        return "[Chats that match:\n$summary\n$next]"
    }

    const val DELEGATED_NO_MATCHES = "[No chats match that. Tell the user, and offer to try other words.]"
    fun delegatedSearchFailed(reason: String) = "[Conduit couldn't search the chats: $reason Tell the user in a few words.]"
    fun delegatedRead(chat: String) = "[$chat\n$READ_NOTE]"
    fun delegatedEmptyChat(title: String) = "[${emptyChat(title)}]"
    const val DELEGATED_UNKNOWN_CHAT = "[That isn't one of the chats found. Delegate \"Find chat:\" with a few words from the topic first.]"
    fun delegatedReadFailed(reason: String) = "[Conduit couldn't read the chat: $reason Tell the user in a few words.]"
}
