package app.hermesconduit.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.model.StreamEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Ask Hermes" in the text-selection menu — the Android counterpart of the
 * Send to Hermes shortcut with Wait for Answer: sends the selection to a new
 * chat and, when the calling app allows it, replaces the selection with the
 * reply (≈30 s budget, like iOS).
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (text.isEmpty()) {
            Toast.makeText(this, L10n.tr("There's no text to send. Give Send to Hermes a message."), Toast.LENGTH_LONG).show()
            finish(); return
        }
        val hermes = hermes
        if (!hermes.isSignedIn) {
            startActivity(packageManager.getLaunchIntentForPackage(packageName))
            finish(); return
        }
        Toast.makeText(this, L10n.tr("Sending to Hermes…"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val reply = withTimeoutOrNull(30_000) {
                if (hermes.gateway == null) hermes.start()
                hermes.link.first { it == app.hermesconduit.android.core.LinkState.CONNECTED }
                runCatching {
                    val created = hermes.rpc("session.create", buildJsonObject { put("cols", 96); put("source", "desktop") }).obj
                    val sid = created?.get("session_id").str ?: error("no session")
                    val answer = lifecycleScope.async {
                        val buffer = StringBuilder()
                        hermes.events.first { e ->
                            if (e.sessionId == sid) {
                                if (e is StreamEvent.MessageDelta) buffer.append(e.text)
                                if (e is StreamEvent.MessageComplete) e.content?.let { buffer.clear().append(it) }
                            }
                            e.sessionId == sid && (e is StreamEvent.Busy && !e.busy || e is StreamEvent.Error)
                        }
                        buffer.toString()
                    }
                    hermes.rpc("prompt.submit", buildJsonObject { put("session_id", sid); put("text", text) })
                    answer.await()
                }.getOrNull()
            }
            when {
                reply.isNullOrBlank() -> Toast.makeText(this@ProcessTextActivity, L10n.tr("Hermes is still working on it. The answer will be in the chat in Conduit."), Toast.LENGTH_LONG).show()
                !readOnly -> setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, reply))
                else -> {
                    copyToClipboard(this@ProcessTextActivity, reply)
                }
            }
            finish()
        }
    }
}
