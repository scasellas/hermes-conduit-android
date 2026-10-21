package app.hermesconduit.android.push

import android.content.Context
import app.hermesconduit.android.ConduitApplication
import app.hermesconduit.android.calls.HermesCalls
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LocationLocateRequest
import app.hermesconduit.android.model.PushAction
import app.hermesconduit.android.model.PushDispatch
import app.hermesconduit.android.model.PushE2E
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.ui.location.LocationSharing
import app.hermesconduit.android.ui.location.MapsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens a UnifiedPush message and does what it asks (iOS: the Notification
 * Service Extension, the VoIP push and the Location Push extension, on one
 * channel). The body is the iOS push's JSON without `aps`: a `conduit`
 * routing stub and, for anything that shows or does something, a sealed
 * `conduit_e2e` envelope opened with the key this phone gave the plugin.
 *
 * - a notification type posts through [app.hermesconduit.android.notify.NotificationMonitor.pushed];
 * - `call.requested` rings ([HermesCalls.pushed]);
 * - `location.requested` answers the locate or maps request through the
 *   location routes, as LocationPushService does;
 * - anything else, or a push that doesn't verify, only wakes Conduit: the
 *   socket reconnects and the background sweep runs once.
 *
 * A sealed message is handled once; a redelivery is a replay and does nothing.
 */
class ConduitPushHandler(private val context: Context) {
    private val hermes = context.hermes
    private val push = ConduitPush.get(context)

    suspend fun handle(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): PushAction {
        val payload = PushE2E.parseMessage(bytes)
        val action = if (payload == null) {
            PushAction.Wake(null, "unreadable")
        } else {
            PushDispatch.plan(PushE2E.evaluate(payload, push.records(), nowMs))
        }
        hermes.log("Push received: ${describe(action)}")
        when (action) {
            is PushAction.Notify -> if (push.seen.insert(action.replayKey, "delivered", nowMs)) {
                (context.applicationContext as ConduitApplication).notifications.pushed(action)
            }
            is PushAction.Ring -> if (push.seen.insert(action.replayKey, "rang", nowMs)) {
                HermesCalls.get(context).pushed(action.target)
            }
            is PushAction.Locate -> answer(action, nowMs)
            is PushAction.Wake -> wake()
        }
        return action
    }

    /** A locate or maps request: claimed while it's answered, kept answered once the host has it. */
    private suspend fun answer(action: PushAction.Locate, nowMs: Long) {
        val namespace = action.request.kind
        if (!push.seen.claim(action.replayKey, namespace, CLAIM_TTL_MS, nowMs)) return
        val sharing = LocationSharing.of(context)
        val answered = try {
            if (action.request.kind == LocationLocateRequest.MAPS) sharing.answerMaps(action.request, MapsEngine(context)::answer)
            else sharing.answerLocate(action.request)
        } catch (e: CancellationException) {
            push.seen.remove(action.replayKey, namespace)
            throw e
        } catch (_: Exception) {
            false
        }
        if (answered) push.seen.markAnswered(action.replayKey, namespace) else push.seen.remove(action.replayKey, namespace)
    }

    /** Reconnects the socket and runs the background sweep once, as the 60-second loop would. */
    private suspend fun wake() {
        if (!hermes.isSignedIn) return
        if (hermes.gateway?.state?.value != GatewayClient.State.CONNECTED) hermes.reconnectNow()
        withTimeoutOrNull(WAKE_TIMEOUT_MS) { hermes.link.first { it == LinkState.CONNECTED } } ?: return
        (context.applicationContext as ConduitApplication).notifications.sweepNow()
    }

    private fun describe(action: PushAction): String = when (action) {
        is PushAction.Notify -> action.type
        is PushAction.Ring -> "call"
        is PushAction.Locate -> "location (${action.request.kind})"
        is PushAction.Wake -> "wake (${action.type ?: "no type"}, ${action.reason})"
    }

    companion object {
        /** A claim nobody finished (the process was killed) ages out so a redelivery can answer. */
        const val CLAIM_TTL_MS = 30_000L
        const val WAKE_TIMEOUT_MS = 20_000L
    }
}
