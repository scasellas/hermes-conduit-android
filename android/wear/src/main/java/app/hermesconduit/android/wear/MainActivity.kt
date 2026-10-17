package app.hermesconduit.android.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import app.hermesconduit.android.wear.ui.WearRoot

/**
 * The watch app. A tile, the complication, the `conduitwatch://talk` link
 * and the Talk to Hermes shortcut open it straight into a call, whatever
 * Call on open says (iOS TalkToHermesIntent / WatchCallURL).
 */
class MainActivity : ComponentActivity() {
    /** Bumped for each request to start a call from outside the app. */
    private val talkRequests = mutableIntStateOf(0)
    private var talkSource = "open"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = wearApp
        if (savedInstanceState == null) {
            when {
                isTalk(intent) -> request(intent.getStringExtra(EXTRA_SOURCE) ?: "link")
                app.prefs.callOnOpen.value && !app.call.state.value.isActive -> request("open")
            }
        }
        setContent { WearRoot(talkRequests.intValue, talkSource) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isTalk(intent)) request(intent.getStringExtra(EXTRA_SOURCE) ?: "link")
    }

    private fun request(source: String) {
        talkSource = source
        talkRequests.intValue++
    }

    companion object {
        const val ACTION_TALK = "app.hermesconduit.android.wear.TALK"
        const val EXTRA_SOURCE = "source"
        val TALK_URI: Uri = Uri.parse("conduitwatch://talk")

        fun isTalk(intent: Intent?): Boolean {
            if (intent == null) return false
            // The tile's launch action can only carry extras.
            if (intent.action == ACTION_TALK || intent.hasExtra(EXTRA_SOURCE)) return true
            val data = intent.data ?: return false
            return data.scheme == TALK_URI.scheme && data.host == TALK_URI.host
        }

        /** Opens the app into a call; [source] is logged (tile, complication, shortcut). */
        fun talkIntent(context: android.content.Context, source: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_TALK)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
