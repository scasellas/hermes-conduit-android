package app.hermesconduit.android.ui.shortcut

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import kotlinx.coroutines.launch

/**
 * "Send to Hermes" for automation apps (Tasker, MacroDroid, Automate): no UI,
 * Conduit doesn't open. Started for a result, it hands back `reply` and
 * `outcome` (or `error` with RESULT_CANCELED); otherwise the outcome shows as
 * a notification.
 */
class SendToHermesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish(); return
        }
        val request = SendToHermes.request(intent)
        if (callingActivity != null) {
            // The caller waits for the answer: stay (invisibly) until the run ends.
            lifecycleScope.launch {
                when (val result = SendToHermes.run(this@SendToHermesActivity, request)) {
                    is SendToHermes.Result.Done -> setResult(RESULT_OK, SendToHermes.fill(Intent(), result))
                    is SendToHermes.Result.Failed -> setResult(RESULT_CANCELED, SendToHermes.fill(Intent(), result))
                }
                finish()
            }
            return
        }
        // Nobody waits: the run goes on in the app's scope and its outcome is a notification.
        val app = applicationContext
        if (SendToHermes.isEnabled(app) && request.text.isNotBlank()) {
            Toast.makeText(this, L10n.tr("Sending to Hermes…"), Toast.LENGTH_SHORT).show()
        }
        app.hermes.scope.launch { SendToHermes.notify(app, SendToHermes.run(app, request)) }
        finish()
    }
}
