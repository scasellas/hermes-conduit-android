package app.hermesconduit.android.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.hermesconduit.android.wear.WatchCall
import app.hermesconduit.android.wear.WatchCall.Phase
import app.hermesconduit.android.wear.WatchVoiceCall
import app.hermesconduit.wearwire.WearApproval
import app.hermesconduit.android.wear.t
import kotlinx.coroutines.delay

/**
 * The call screen (WatchCallView): the orb and controls, then the
 * conversation so far (scroll down or turn the crown), a job's approval
 * request over both, and a summary once the call ends.
 */
@Composable
fun CallScreen(state: WatchCall.State, call: WatchVoiceCall, onRetry: () -> Unit) {
    if (state.phase == Phase.ENDED) return CallSummary(state, onDone = call::dismissEnded, onRetry = { call.dismissEnded(); onRetry() })
    val approval = state.pendingApproval
    if (approval != null && state.isActive) {
        // Only Approve or Deny takes it down.
        ApprovalCard(approval, busy = false, error = null) { approve -> call.answerApproval(approval, approve) }
        return
    }
    val jobApproval = state.jobApproval
    if (jobApproval != null && state.isActive) {
        // A live call's job, answered through the call's grant: Approve allows this one command only.
        val card = WearApproval(jobApproval.grantId, jobApproval.requestId, jobApproval.title, jobApproval.command, jobApproval.description)
        ApprovalCard(card, busy = false, error = null) { approve -> call.answerJobApproval(jobApproval, approve) }
        return
    }
    WatchList {
        item { CallHeader(state) }
        item {
            val tappable = state.phase in setOf(Phase.SPEAKING, Phase.THINKING, Phase.NEEDS_TAP, Phase.LOST, Phase.LISTENING)
            Box(
                Modifier
                    .then(if (tappable) Modifier.clickable(onClick = call::tap) else Modifier)
                    .semantics { contentDescription = orbLabel(state) },
                contentAlignment = Alignment.Center,
            ) { VoiceOrb(mood(state), size = 88.dp) }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(status(state), style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 2)
                detail(state)?.let { Footnote(it, if (state.notice != null) WatchColors.attention else MaterialTheme.colors.onSurfaceVariant) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = call::toggleMute,
                    enabled = state.isActive,
                    colors = ButtonDefaults.buttonColors(backgroundColor = if (state.muted) WatchColors.attention else Color.DarkGray),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize).semantics { contentDescription = if (state.muted) t("Unmute") else t("Mute") },
                ) { Icon(if (state.muted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null) }
                Button(
                    onClick = call::end,
                    enabled = state.isActive,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize).semantics { contentDescription = t("End call") },
                ) { Icon(Icons.Default.CallEnd, contentDescription = null) }
            }
        }
        if (state.transcript.isEmpty()) {
            item { Footnote(t("What you and Hermes say shows here.")) }
        } else {
            items(state.transcript) { line -> TranscriptLine(line) }
        }
    }
}

@Composable
private fun CallHeader(state: WatchCall.State) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.liveSinceMs) {
        while (state.liveSinceMs != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.chatTitle ?: "Hermes", style = MaterialTheme.typography.caption2, color = WatchColors.aura, maxLines = 1)
        if (state.jobsRunning > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Build,
                contentDescription = t("Jobs running: %s", state.jobsRunning.toString()),
                tint = WatchColors.attention,
                modifier = Modifier.size(12.dp),
            )
            Text(state.jobsRunning.toString(), style = MaterialTheme.typography.caption2, color = WatchColors.attention)
        }
        state.liveSinceMs?.let {
            Spacer(Modifier.width(6.dp))
            Text(elapsed(now - it), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptLine(line: WatchCall.Line) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text,
            style = MaterialTheme.typography.caption2,
            modifier = Modifier
                .semantics { contentDescription = if (line.fromUser) t("You: %s", line.text) else t("Hermes: %s", line.text) }
                .background(if (line.fromUser) WatchColors.aura.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CallSummary(state: WatchCall.State, onDone: () -> Unit, onRetry: () -> Unit) {
    val normal = state.endReason == null
    WatchList {
        item {
            Icon(
                if (normal) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (normal) WatchColors.aura else WatchColors.attention,
                modifier = Modifier.size(36.dp),
            )
        }
        item { Text(if (normal) t("Call ended") else t("Call didn't work"), style = MaterialTheme.typography.title3) }
        state.endReason?.let { item { Footnote(it) } }
        val since = state.liveSinceMs
        val ended = state.endedAtMs
        if (since != null && ended != null) item { Footnote(elapsed(ended - since)) }
        item { Chip(onClick = onDone, label = { Text(t("Done")) }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth()) }
        if (!normal) item { Chip(onClick = onRetry, label = { Text(t("Try again")) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
    }
}

private fun elapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun mood(state: WatchCall.State): OrbMood = when (state.phase) {
    Phase.IDLE, Phase.PREPARING, Phase.CONNECTING, Phase.RECONNECTING, Phase.THINKING -> OrbMood.WORKING
    Phase.LISTENING -> if (state.muted) OrbMood.MUTED else OrbMood.LISTENING
    Phase.SPEAKING -> OrbMood.SPEAKING
    Phase.LOST, Phase.NEEDS_TAP -> OrbMood.ATTENTION
    Phase.ENDING, Phase.ENDED -> OrbMood.DONE
}

private fun status(state: WatchCall.State): String = when (state.phase) {
    Phase.IDLE, Phase.PREPARING -> t("Starting…")
    Phase.CONNECTING -> t("Connecting…")
    Phase.RECONNECTING -> t("Reconnecting…")
    Phase.LISTENING -> if (state.muted) t("Muted") else t("Listening")
    Phase.THINKING -> t("Thinking…")
    Phase.SPEAKING -> t("Speaking")
    Phase.LOST -> t("Connection lost")
    Phase.NEEDS_TAP -> t("Paused")
    Phase.ENDING, Phase.ENDED -> t("Ending…")
}

/** A hint under the status, or the newest words of the conversation. */
private fun detail(state: WatchCall.State): String? = when (state.phase) {
    Phase.PREPARING, Phase.CONNECTING -> t("Asking your phone to set up the call")
    Phase.THINKING -> state.notice ?: t("Tap to interrupt")
    Phase.SPEAKING -> state.caption ?: t("Tap to interrupt")
    Phase.LOST -> state.notice ?: t("Tap to try again")
    Phase.NEEDS_TAP -> state.notice ?: t("Tap to continue")
    Phase.LISTENING -> state.caption ?: state.notice
    Phase.RECONNECTING -> state.notice
    else -> null
}

private fun orbLabel(state: WatchCall.State): String = when (state.phase) {
    Phase.SPEAKING, Phase.THINKING -> t("Interrupt")
    Phase.NEEDS_TAP, Phase.LOST -> t("Continue")
    else -> status(state)
}
