package app.hermesconduit.android.ui.voice.live

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.ui.ChatState
import app.hermesconduit.android.ui.LocalNavigate
import app.hermesconduit.android.ui.voice.VoiceHub
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The chat's history with its voice call cards (#556), each placed where its
 * call started. The chat as it is when voice hasn't been set up in this
 * process (previews, snapshots).
 */
@Composable
fun rememberMessagesWithCallCards(chat: ChatState): List<ChatMessage> {
    val hub = VoiceHub.peek() ?: return chat.messages
    val calls = hub.liveCalls
    BindChatHost()
    val links by calls.chatLinks.collectAsState()
    val rows by calls.callRows.collectAsState()
    LaunchedEffect(chat.sessionId, chat.runtimeId) { calls.refreshCallRows() }
    return remember(chat.messages, chat.sessionId, chat.runtimeId, links, rows) { calls.messagesWithCallCards(chat, links, rows) }
}

/**
 * A voice call started from this chat: the card opens its spoken turns,
 * kept with the chat (#556) or, for older calls and hosts, saved as their
 * own session. In such a session (`origin`), the same card leads back to the
 * chat.
 */
@Composable
fun VoiceCallMarkerCard(message: ChatMessage) {
    val calls = VoiceHub.peek()?.liveCalls
    val link = calls?.callLink(message.id)
    if (calls == null || link == null) {
        Text(message.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val origin = message.kind == VoiceCallChatLink.ORIGIN_DISPLAY_KIND
    val duration = callDuration(link.durationMs)
    val title = when {
        origin -> t("Started from %s", link.chatTitle)
        link.resumed -> t("Voice call resumed")
        else -> t("Voice call")
    }
    val detail = if (origin) t("Open chat") else t("%s · Open transcript", duration)
    val started = startedTime(link.startedAtMs)
    // Title, length (not on the origin card) and start time; the action stays in the click label.
    val spoken = listOfNotNull(title, duration.takeIf { !origin }, L10n.tr("Started %s", started)).joinToString(", ")
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.16f), shape)
            .clickable(onClickLabel = if (origin) t("Opens the chat this call was started from") else t("Opens the call's transcript")) {
                if (origin) calls.openCallChat(link.originMarkerId) else calls.openCallTranscript(link.markerId)
            }
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .heightIn(min = 44.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (origin) Icons.AutoMirrored.Filled.Chat else Icons.Default.Call, null, Modifier.size(15.dp), tint = accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(started, style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.78f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** The call's length as iOS' abbreviated duration: its two largest units ("1 hr, 5 min", "3m 20s"…), in the app's language. */
private fun callDuration(durationMs: Long): String {
    val measures = VoiceCallDuration.parts(durationMs).map { (unit, value) ->
        Measure(
            value,
            when (unit) {
                VoiceCallDuration.Unit.HOURS -> MeasureUnit.HOUR
                VoiceCallDuration.Unit.MINUTES -> MeasureUnit.MINUTE
                VoiceCallDuration.Unit.SECONDS -> MeasureUnit.SECOND
            },
        )
    }
    return runCatching {
        MeasureFormat.getInstance(Locale.forLanguageTag(L10n.language), MeasureFormat.FormatWidth.NARROW).formatMeasures(*measures.toTypedArray())
    }.getOrElse { measures.joinToString(" ") { "${it.number}" } }
}

/** When the call started: the time today, the date and time before. */
private fun startedTime(ms: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return format.format(Date(ms))
}

/**
 * The transcript behind a chat's call card when the call is kept with the
 * chat (#556), read from the host when the card is tapped; and why a card
 * couldn't open what it leads to. Draws nothing without voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChatCallTranscriptHost() {
    val calls = VoiceHub.peek()?.liveCalls ?: return
    val context = LocalContext.current
    val error by calls.callLinkError.collectAsState()
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        calls.clearCallLinkError()
    }
    val call by calls.chatCallTranscript.collectAsState()
    val shown = call ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = calls::dismissChatCallTranscript, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Voice call"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { calls.dismissChatCallTranscript() } }) { Text(t("Done")) }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown.turns, key = { it.id }) { entry -> Bubble(entry, transcriptLabel(entry.fromUser, entry.text, shown.engine)) }
        }
    }
}

/** Who spoke each line, as the live call screen says it. */
internal fun transcriptLabel(fromUser: Boolean, text: String, engine: String?): String = when {
    fromUser -> L10n.tr("You: %s", text)
    engine == "gpt-live" -> L10n.tr("GPT-Live: %s", text)
    engine == "grok-live" -> L10n.tr("Grok: %s", text)
    engine == "gemini-live" -> L10n.tr("Gemini: %s", text)
    else -> text
}

/**
 * A saved live call open as a chat: pick it up with a new live call that
 * continues the same row. A phone-forward glyph, not the waveform, so it isn't
 * mistaken for the voice button, which always starts a new call.
 */
@Composable
fun ResumeVoiceCallButton(chat: ChatState, enabled: Boolean) {
    val calls = VoiceHub.peek()?.liveCalls ?: return
    val rows by calls.callRows.collectAsState()
    val preparing by calls.preparingResume.collectAsState()
    val mode by calls.mode.collectAsState()
    val callId = calls.callRowId(chat, rows) ?: return
    if (mode != null || !calls.canResume()) return
    val navigate = LocalNavigate.current
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = { scope.launch { if (calls.resumeCall(callId)) navigate(LiveVoiceCalls.ROUTE) } },
        enabled = enabled && !preparing && !chat.running,
        modifier = Modifier.size(52.dp).semantics { contentDescription = L10n.tr("Resume call") },
    ) {
        if (preparing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.AutoMirrored.Filled.PhoneForwarded, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
