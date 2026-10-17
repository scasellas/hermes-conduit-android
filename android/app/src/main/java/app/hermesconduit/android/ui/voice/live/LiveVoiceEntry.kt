package app.hermesconduit.android.ui.voice.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.live.GPTLiveBilling
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.ui.AppViewModel
import app.hermesconduit.android.ui.ChatState
import app.hermesconduit.android.ui.LocalNavigate
import app.hermesconduit.android.ui.nav.activityAppViewModel
import app.hermesconduit.android.ui.voice.VoiceConversationScreen
import app.hermesconduit.android.ui.voice.VoiceHub

/** The app's open chat as the chat a live call works in. */
class AppChatHost(private val app: AppViewModel) : LiveCallChatHost {
    override val chat: ChatState get() = app.chat.value
    override suspend fun submit(text: String): Boolean = app.submitPrompt(text, emptyList(), SPOKEN_PROMPT_SURFACE)
    override fun openSession(id: String, title: String) = app.openSessionById(id, title)
    override fun listedChats(): List<Triple<String, String, Long?>> = app.sessions.value.sessions.map { Triple(it.id, it.title, it.lastActiveMs) }
    override fun respondApproval(choice: String) = app.respondApproval(choice)
    override fun answerClarify(request: ClarifyRequest, answers: List<String>) = app.answerClarify(request, answers)

    private companion object {
        const val SPOKEN_PROMPT_SURFACE = "voice-live"
    }
}

/** Binds the activity's chat to the live calls while `content` is up. */
@Composable
private fun BindChatHost() {
    val context = LocalContext.current
    val app = activityAppViewModel() ?: return
    val hub = remember { VoiceHub.get(context) }
    DisposableEffect(app) {
        val host = AppChatHost(app)
        hub.liveCalls.chatHost = host
        onDispose { if (hub.liveCalls.chatHost === host) hub.liveCalls.chatHost = null }
    }
}

/**
 * The voice route: the live call screen when a live call is open or the
 * profile uses a live mode, the classic conversation otherwise.
 */
@Composable
fun VoiceEntryScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val hub = remember { VoiceHub.get(context) }
    val open by hub.liveCalls.mode.collectAsState()
    hub.preferencesVersion.collectAsState().value
    val mode = open ?: hub.liveCalls.enabledMode()
    BindChatHost()
    if (mode == null) VoiceConversationScreen(onBack, navigate) else LiveVoiceCallScreen(mode, onBack)
}

/**
 * The minimised live call above the composer (iOS MinimisedLiveVoiceBar):
 * tap brings the call back, with mute and End at hand. A call that failed
 * while minimised keeps its bar, so tapping it shows why. Draws nothing
 * when voice hasn't been set up in this process (previews, snapshots).
 */
@Composable
fun MinimisedLiveVoiceBar() {
    val hub = VoiceHub.peek() ?: return
    val calls = hub.liveCalls
    val mode by calls.mode.collectAsState()
    val minimised by calls.minimised.collectAsState()
    val current = mode ?: return
    if (!minimised) return
    BindChatHost()
    val call = remember(current) { LiveCallView.of(calls, current) }
    val phase by call.phase.collectAsState()
    val muted by call.microphoneMuted.collectAsState()
    val billing by call.billing.collectAsState()
    val navigate = LocalNavigate.current
    val failed = phase is LiveCallPhase.Failed
    val name = if (billing == GPTLiveBilling.API) L10n.tr("GPT-Live · API key") else call.title
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(onClickLabel = if (failed) L10n.tr("Shows why the call stopped") else L10n.tr("Opens the call")) {
                    calls.restore()
                    navigate(LiveVoiceCalls.ROUTE)
                }
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(call.barStatus(phase, muted), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!failed) {
                IconButton(onClick = { call.setMicrophoneMuted(!muted) }) {
                    Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) t("Unmute microphone") else t("Mute microphone"))
                }
            }
            IconButton(onClick = { calls.close() }) {
                Icon(Icons.Default.CallEnd, t("End call"), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
