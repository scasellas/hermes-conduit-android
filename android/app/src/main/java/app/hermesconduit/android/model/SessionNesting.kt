package app.hermesconduit.android.model

/**
 * The Sessions drawer nests spawned rows under the session that started them
 * (iOS #556): a subagent under its parent, a voice job under the call that
 * started it, a call attached to a chat under that chat. Pure list shaping,
 * ported from iOS `SessionNesting`.
 */
object SessionNesting {
    /** Every id a row answers to: its own first, then its aliases. */
    fun nestingIds(session: SessionSummary): List<String> =
        (listOf(session.id) + listOfNotNull(session.runtimeId) + session.alternateIds).filter { it.isNotEmpty() }.distinct()

    /**
     * Groups `sessions` by `parentKeyOf`, flattened depth-first as (row, depth).
     * A row nests only under a row in the same list, matched on any id it
     * answers to; a row's own id beats one borrowed as an alias, and the
     * earlier row breaks what's left. A row that names itself, names no row
     * in the list, or closes a parent cycle stays at the top level. Roots keep
     * the list's order; children sort newest first.
     */
    fun group(sessions: List<SessionSummary>, maxDepth: Int = 3, parentKeyOf: (SessionSummary) -> String?): List<Pair<SessionSummary, Int>> {
        val rowForKey = HashMap<String, Int>()
        val rankForKey = HashMap<String, Int>()
        sessions.forEachIndexed { index, session ->
            for (id in nestingIds(session)) {
                val rank = if (id == session.id) 0 else 1
                val existing = rankForKey[id]
                if (existing != null && existing <= rank) continue
                rankForKey[id] = rank
                rowForKey[id] = index
            }
        }
        val parentRow = arrayOfNulls<Int>(sessions.size)
        sessions.forEachIndexed { index, session ->
            val key = parentKeyOf(session)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val parent = rowForKey[key] ?: return@forEachIndexed
            if (parent != index) parentRow[index] = parent
        }
        // The row where a parent cycle closes is freed as a root.
        val state = IntArray(sessions.size)
        for (start in sessions.indices) {
            val path = mutableListOf<Int>()
            var row = start
            while (state[row] == 0) {
                state[row] = 1
                path += row
                row = parentRow[row] ?: break
            }
            if (state[row] == 1) parentRow[row] = null
            path.forEach { state[it] = 2 }
        }
        val children = Array(sessions.size) { mutableListOf<Int>() }
        for (row in sessions.indices) parentRow[row]?.let { children[it] += row }
        val newestFirst = Comparator<Int> { lhs, rhs ->
            val l = sessions[lhs].lastActiveMs
            val r = sessions[rhs].lastActiveMs
            when {
                l != null && r != null -> if (l == r) lhs.compareTo(rhs) else r.compareTo(l)
                l != null -> -1
                r != null -> 1
                else -> lhs.compareTo(rhs)
            }
        }
        val result = mutableListOf<Pair<SessionSummary, Int>>()
        fun add(row: Int, depth: Int) {
            result += sessions[row] to minOf(depth, maxDepth)
            children[row].sortedWith(newestFirst).forEach { add(it, depth + 1) }
        }
        sessions.indices.filter { parentRow[it] == null }.forEach { add(it, 0) }
        return result
    }

    /** Whether the drawer groups its rows. A search or a status filter is a lookup: it flattens the list. */
    fun shouldGroup(query: String, statusFilterActive: Boolean) = query.isBlank() && !statusFilterActive

    /**
     * The key of the row `session` nests under: the call that started a voice
     * job, the chat a saved call was attached to, else the Hermes parent
     * (a subagent's). Classic voice runs in its chat's own session.
     */
    fun parentKey(session: SessionSummary, tag: VoiceSessionTag?, callChatKey: (List<String>) -> String?): String? =
        when (tag?.kind) {
            VoiceSessionTagKind.JOB -> tag.parentId ?: session.parentId
            VoiceSessionTagKind.CALL -> callChatKey(nestingIds(session)) ?: session.parentId
            else -> session.parentId
        }
}
