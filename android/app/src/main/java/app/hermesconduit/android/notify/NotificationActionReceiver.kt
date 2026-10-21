package app.hermesconduit.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.hermesconduit.android.hermes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Approve or deny straight from a notification (`approval.respond`). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> return reply(context, intent)
            ACTION_READ -> {
                ConduitNotifications.cancel(context, intent.getIntExtra(EXTRA_NOTIFICATION, 0))
                return
            }
        }
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

    /** A spoken or typed reply from the notification (Android Auto, Wear, the shade). */
    private fun reply(context: Context, intent: Intent) {
        val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_REPLY)?.toString()?.trim().orEmpty()
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (text.isEmpty()) return
        val pending = goAsync()
        val hermes = context.hermes
        hermes.scope.launch {
            try {
                if (hermes.gateway == null) hermes.reconnectNow()
                kotlinx.coroutines.withTimeoutOrNull(10_000) { hermes.link.first { it == app.hermesconduit.android.core.LinkState.CONNECTED } }
                runCatching {
                    val resumed = hermes.rpc("session.resume", buildJsonObject {
                        put("session_id", session); put("cols", 96); put("source", "desktop"); put("omit_messages", true)
                    })
                    val runtime = (resumed as? kotlinx.serialization.json.JsonObject)?.get("session_id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: session
                    hermes.rpc("prompt.submit", buildJsonObject { put("session_id", runtime); put("text", text) })
                }
                ConduitNotifications.cancel(context, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.hermesconduit.android.REPLY"
        const val ACTION_READ = "app.hermesconduit.android.READ"
        const val EXTRA_REPLY = "reply_text"
        const val ACTION_APPROVAL = "app.hermesconduit.android.APPROVAL"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SESSION = "session"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
