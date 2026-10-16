package app.hermesconduit.android.watch

import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.wearwire.ReplyEvent
import app.hermesconduit.wearwire.WearApproval

/**
 * One watch turn's gateway events → the reply events the watch gets: the
 * reply so far as it streams, the end of the turn, a failure, and approval
 * requests (Approve from the Watch). Pure, so it is unit-tested.
 */
class WatchTurnReducer(private val runtimeId: String, private val chatTitle: () -> String = { "Hermes" }) {
    private val text = StringBuilder()
    private var seq = 0
    var finished = false
        private set

    /** The reply so far, as spoken: without Hermes' inline tags. */
    val reply: String get() = ContentSegmenter.removingTags(text.toString()).trim()

    fun on(event: StreamEvent): ReplyEvent? {
        if (finished) return null
        if (event.sessionId.isNotEmpty() && event.sessionId != runtimeId && event !is StreamEvent.Approval) return null
        return when (event) {
            is StreamEvent.MessageStart -> null
            is StreamEvent.MessageDelta -> {
                text.append(event.text)
                ReplyEvent.Text(++seq, reply)
            }
            is StreamEvent.MessageComplete -> {
                event.content?.takeIf { it.isNotBlank() }?.let { text.clear(); text.append(it) }
                finish(ReplyEvent.Done(reply))
            }
            is StreamEvent.Busy -> if (!event.busy && (text.isNotEmpty() || seq > 0)) finish(ReplyEvent.Done(reply)) else null
            is StreamEvent.Interrupted -> finish(ReplyEvent.Done(reply, interrupted = true))
            is StreamEvent.Error -> finish(ReplyEvent.Failed(event.message))
            is StreamEvent.Approval -> if (event.request.sessionId == runtimeId || event.sessionId == runtimeId) ReplyEvent.Approval(approval(event.request, chatTitle())) else null
            is StreamEvent.RequestCancelled -> if (event.method == "approval") ReplyEvent.ApprovalCleared(event.requestId) else null
            else -> null
        }
    }

    private fun finish(event: ReplyEvent): ReplyEvent {
        finished = true
        return event
    }

    companion object {
        fun approval(request: ApprovalRequest, title: String) = WearApproval(
            sessionId = request.sessionId,
            requestId = request.requestId,
            title = title,
            command = request.command.orEmpty(),
            description = request.description.orEmpty(),
        )
    }
}
