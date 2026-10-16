package app.hermesconduit.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.hermesconduit.android.hermes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Approve or deny straight from a notification (`approval.respond`). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPROVAL) return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val request = intent.getStringExtra(EXTRA_REQUEST).orEmpty()
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val pending = goAsync()
        val hermes = context.hermes
        hermes.scope.launch {
            try {
                if (hermes.gateway == null) hermes.reconnectNow()
                repeat(20) { if (hermes.gateway?.state?.value == app.hermesconduit.android.net.GatewayClient.State.CONNECTED) return@repeat; kotlinx.coroutines.delay(250) }
                runCatching {
                    hermes.rpc("approval.respond", buildJsonObject {
                        put("choice", choice)
                        put("session_id", session)
                        if (request.isNotEmpty()) put("request_id", request)
                    })
                }
                ConduitNotifications.cancel(context, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVAL = "app.hermesconduit.android.APPROVAL"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SESSION = "session"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
