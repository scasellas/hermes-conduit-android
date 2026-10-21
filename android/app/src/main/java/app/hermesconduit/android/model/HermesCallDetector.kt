package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonObject

/**
 * Android's way to learn that Hermes calls (#449). iOS hears a
 * `call.requested` push through the relay, which only serves APNs devices.
 * Here calls come from the live gateway socket instead:
 *
 * - a `call.requested` event on the socket, with the push's routing object
 *   (wire-identical; [HermesCallTarget.parse]);
 * - Hermes using the notifier plugin's call tools in a chat this device
 *   watches (`conduit_call_watch`, `conduit_call_user`): the call rings once
 *   that turn ends, as the host would place it;
 * - with "Call about approvals and questions" on, an approval or question
 *   left waiting for a minute, or a failed turn.
 *
 * The host's settings gate each and its limits (time between calls, per
 * hour, per day) apply, as the host applies them to the calls it places.
 * Pure: the clock is passed in.
 */
class HermesCallDetector {
    /** A call to place: the session it's about and what the call says. */
    data class LocalCall(val sessionId: String, val kind: HermesCallRequest.Kind?, val title: String?, val reason: String?, val callId: String = "")

    private data class Pending(val title: String?, val reason: String?, val callId: String)
    private data class Waiting(val kind: HermesCallRequest.Kind, val sinceMs: Long, val reason: String?)

    /** The active profile's call settings; null until read (then only socket calls ring). */
    var settings: HermesCallSettings? = null

    private val toolInputs = mutableMapOf<String, String?>()
    private val pending = mutableMapOf<String, Pending>()
    private val turnEnd = mutableMapOf<String, HermesCallRequest.Kind>()
    private val waiting = mutableMapOf<String, Waiting>()
    private val placed = ArrayDeque<Long>()
    private val rangSessions = mutableMapOf<String, Long>()

    fun reset() {
        toolInputs.clear(); pending.clear(); turnEnd.clear(); waiting.clear()
    }

    private val callsOn get() = settings?.enabled == true
    private val asksOn get() = settings?.let { it.enabled && (it.whenAsked || it.decides == true) } == true
    private val alertsOn get() = settings?.let { it.enabled && it.alerts == true } == true

    /** Feeds one gateway event; returns the calls it places. */
    fun observe(event: StreamEvent, nowMs: Long): List<LocalCall> {
        val sid = event.sessionId
        if (sid.isEmpty()) return emptyList()
        when (event) {
            is StreamEvent.ToolStart -> {
                if (event.name in CALL_TOOLS) toolInputs[event.toolId ?: (sid + event.name)] = event.input
                waiting.remove(sid)
            }
            is StreamEvent.ToolComplete -> if (event.name in CALL_TOOLS && asksOn) {
                val input = toolInputs.remove(event.toolId ?: (sid + event.name))
                toolCall(sid, input, event.output)?.let { pending[sid] = it }
            }
            is StreamEvent.MessageStart, is StreamEvent.MessageDelta -> waiting.remove(sid)
            is StreamEvent.Approval -> if (alertsOn) {
                waiting[sid] = Waiting(HermesCallRequest.Kind.APPROVAL, nowMs, HermesCallRequest.cleanedReason(event.request.description ?: event.request.command))
            }
            is StreamEvent.Clarify -> if (alertsOn) {
                waiting[sid] = Waiting(HermesCallRequest.Kind.QUESTION, nowMs, HermesCallRequest.cleanedReason(event.request.questions.firstOrNull()?.question))
            }
            is StreamEvent.RequestCancelled -> waiting.remove(sid)
            is StreamEvent.Error -> {
                turnEnd[sid] = HermesCallRequest.Kind.FAILED
                if (sid !in pending && alertsOn) {
                    waiting.remove(sid)
                    return listOfNotNull(place(LocalCall(sid, HermesCallRequest.Kind.FAILED, null, null), nowMs))
                }
            }
            is StreamEvent.Interrupted -> turnEnd[sid] = HermesCallRequest.Kind.STOPPED
            is StreamEvent.Busy -> if (!event.busy) return listOfNotNull(turnEnded(sid, nowMs))
            else -> Unit
        }
        return emptyList()
    }

    /** A session went idle (the sweep saw it): a pending call rings now. */
    fun turnEnded(sessionId: String, nowMs: Long): LocalCall? {
        val kind = turnEnd.remove(sessionId) ?: HermesCallRequest.Kind.DONE
        waiting.remove(sessionId)
        val call = pending.remove(sessionId) ?: return null
        return place(LocalCall(sessionId, kind, call.title, call.reason, call.callId), nowMs)
    }

    /** Approvals and questions left waiting a minute call now. */
    fun tick(nowMs: Long): List<LocalCall> {
        if (!alertsOn) {
            waiting.clear()
            return emptyList()
        }
        val due = waiting.filter { nowMs - it.value.sinceMs >= ALERT_WAIT_MS }
        due.keys.forEach { waiting.remove(it) }
        return due.mapNotNull { (sid, w) -> place(LocalCall(sid, w.kind, null, w.reason), nowMs) }
    }

    /** A `call.requested` from the socket: rings unless this session just rang. */
    fun socketCall(sessionId: String, nowMs: Long): Boolean {
        val last = rangSessions[sessionId]
        if (last != null && nowMs - last < DEDUPE_MS) return false
        pending.remove(sessionId)
        rangSessions[sessionId] = nowMs
        return true
    }

    private fun place(call: LocalCall, nowMs: Long): LocalCall? {
        if (!callsOn) return null
        val last = rangSessions[call.sessionId]
        if (last != null && nowMs - last < DEDUPE_MS) return null
        if (!withinLimits(nowMs)) return null
        placed.addLast(nowMs)
        rangSessions[call.sessionId] = nowMs
        rangSessions.entries.removeAll { nowMs - it.value > DAY_MS }
        return call
    }

    /** The host's limits: time between calls, calls per hour, calls per day. */
    fun withinLimits(nowMs: Long): Boolean {
        val s = settings ?: return true
        while (placed.isNotEmpty() && nowMs - placed.first() > DAY_MS) placed.removeFirst()
        val lastCall = placed.lastOrNull()
        if (lastCall != null && nowMs - lastCall < s.minGapSeconds * 1000L) return false
        if (placed.count { nowMs - it <= HOUR_MS } >= s.perHour) return false
        if (placed.size >= s.perDay) return false
        return true
    }

    private fun toolCall(sessionId: String, input: String?, output: String?): Pending? {
        val args = input?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val result = output?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        // A refusal Android can still honour (no relay pairing here) rings; any other doesn't.
        if (result != null && result["ok"].bool == false) {
            val error = (result["error"].str ?: result["detail"].str).orEmpty()
            if (!error.contains("pair", ignoreCase = true) && !error.contains("push", ignoreCase = true)) return null
        }
        val id = listOf("id", "watch_id", "call_id").firstNotNullOfOrNull { key -> result?.get(key).str?.takeIf { ID.matches(it) } }.orEmpty()
        return Pending(
            title = HermesCallRequest.cleanedTitle(args?.get("title").str),
            reason = HermesCallRequest.cleanedReason(args?.get("reason").str),
            callId = id,
        ).also { if (sessionId.isEmpty()) return null }
    }

    companion object {
        val CALL_TOOLS = setOf("conduit_call_watch", "conduit_call_user")
        const val ALERT_WAIT_MS = 60_000L
        const val DEDUPE_MS = 120_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private val ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}
