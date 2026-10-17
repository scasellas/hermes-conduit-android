package app.hermesconduit.android.push

import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.AndroidPushWire
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The UnifiedPush connector's events for Conduit (connector 3: a bound
 * [PushService]; the library's own receiver takes the distributor's
 * broadcasts and forwards them here). Starts Conduit's process when it was
 * closed. Callbacks may come on a binder or executor thread: the work goes
 * to the app's main scope.
 */
class ConduitPushService : PushService() {
    private val push get() = ConduitPush.get(this)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (instance != ConduitPush.INSTANCE) return
        val wire = AndroidPushWire.Endpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth, endpoint.temporary)
        hermes.scope.launch { push.newEndpoint(wire) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (instance != ConduitPush.INSTANCE) return
        // The connector has already opened Web Push encryption (RFC 8291) when
        // the plugin used it; either way the body is Conduit's own sealed JSON.
        val content = message.content
        val handler = ConduitPushHandler(applicationContext)
        hermes.scope.launch { runCatching { handler.handle(content) } }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (instance != ConduitPush.INSTANCE) return
        hermes.scope.launch { push.registrationFailed(reason) }
    }

    override fun onUnregistered(instance: String) {
        if (instance != ConduitPush.INSTANCE) return
        hermes.scope.launch { push.unregistered() }
    }
}
