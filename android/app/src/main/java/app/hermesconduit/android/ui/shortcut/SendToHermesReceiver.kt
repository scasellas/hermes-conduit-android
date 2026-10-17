package app.hermesconduit.android.ui.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.hermesconduit.android.hermes
import kotlinx.coroutines.launch

/**
 * The broadcast form of "Send to Hermes", for automation apps that can't start
 * an activity from the background. With `reply_action` and `reply_package`, the
 * outcome comes back as that broadcast, sent only to that package; otherwise
 * it shows as a notification.
 */
class SendToHermesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SendToHermes.ACTION) return
        val app = context.applicationContext
        val request = SendToHermes.request(intent)
        val replyAction = intent.getStringExtra(SendToHermes.EXTRA_REPLY_ACTION)?.trim()?.ifEmpty { null }
        val replyPackage = intent.getStringExtra(SendToHermes.EXTRA_REPLY_PACKAGE)?.trim()?.ifEmpty { null }
        val pending = goAsync()
        app.hermes.scope.launch {
            try {
                val result = SendToHermes.run(app, request)
                if (replyAction != null && replyPackage != null) {
                    app.sendBroadcast(SendToHermes.fill(Intent(replyAction).setPackage(replyPackage), result))
                } else {
                    SendToHermes.notify(app, result)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
