package app.hermesconduit.android.ui.voice.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.live.GPTLiveBilling
import app.hermesconduit.android.core.live.GPTLiveClient
import app.hermesconduit.android.core.live.LiveFinishedTurn
import app.hermesconduit.android.core.live.LiveTranscriptEntry
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.ui.markdown.RichMarkdown
import app.hermesconduit.android.ui.voice.VoiceBackgroundJob
import app.hermesconduit.android.ui.voice.VoiceConversationState
import app.hermesconduit.android.ui.voice.VoiceHub
import app.hermesconduit.android.ui.voice.VoiceOrb
import app.hermesconduit.android.ui.voice.VoiceScreenCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One live call as the call screen and the minimised bar draw it, whichever engine runs it. */
class LiveCallView(
    val mode: LiveVoiceMode,
    val phase: StateFlow<LiveCallPhase>,
    val transcript: StateFlow<List<LiveTranscriptEntry>>,
    val microphoneMuted: StateFlow<Boolean>,
    val speakerMuted: StateFlow<Boolean>,
    val finishedTurn: StateFlow<LiveFinishedTurn?>,
    val billing: StateFlow<GPTLiveBilling?>,
    val note: StateFlow<String?>,
    val limitNote: StateFlow<String?>,
    val setMicrophoneMuted: (Boolean) -> Unit,
    val setSpeakerMuted: (Boolean) -> Unit,
    /** Null for an engine only interrupted by speaking over it (GPT-Live). */
    val interrupt: (() -> Unit)?,
    val isEnding: () -> Boolean,
) {
    val title: String get() = mode.displayName

    fun assistantLabel(text: String): String = when (mode) {
        LiveVoiceMode.GEMINI_LIVE -> L10n.tr("Gemini: %s", text)
        LiveVoiceMode.GROK_LIVE -> L10n.tr("Grok: %s", text)
        LiveVoiceMode.GPT_LIVE -> L10n.tr("GPT-Live: %s", text)
    }

    fun statusText(phase: LiveCallPhase, microphoneMuted: Boolean, speakerMuted: Boolean): String = when (phase) {
        LiveCallPhase.Idle -> L10n.tr("Not connected")
        LiveCallPhase.Connecting -> when (mode) {
            LiveVoiceMode.GEMINI_LIVE -> L10n.tr("Connecting to Gemini Live…")
            LiveVoiceMode.GROK_LIVE -> L10n.tr("Connecting to Grok Live…")
            LiveVoiceMode.GPT_LIVE -> L10n.tr("Connecting to GPT-Live…")
        }
        LiveCallPhase.Reconnecting -> L10n.tr("Reconnecting…")
        LiveCallPhase.Listening -> if (microphoneMuted) L10n.tr("Microphone muted") else L10n.tr("Listening")
        LiveCallPhase.Speaking -> if (speakerMuted) L10n.tr("Speaking, silenced") else L10n.tr("Speaking")
        LiveCallPhase.Paused -> L10n.tr("Paused while another sound plays")
        LiveCallPhase.Ending -> L10n.tr("Ending conversation…")
        is LiveCallPhase.Failed -> phase.message
    }

    /** The bar's shorter status. */
    fun barStatus(phase: LiveCallPhase, microphoneMuted: Boolean): String = when (phase) {
        LiveCallPhase.Idle -> L10n.tr("Not connected")
        LiveCallPhase.Connecting -> L10n.tr("Connecting…")
        LiveCallPhase.Reconnecting -> L10n.tr("Reconnecting…")
        LiveCallPhase.Listening -> if (microphoneMuted) L10n.tr("Microphone muted") else L10n.tr("Listening")
        LiveCallPhase.Speaking -> L10n.tr("Speaking")
        LiveCallPhase.Paused -> L10n.tr("Paused")
        LiveCallPhase.Ending -> L10n.tr("Ending conversation…")
        is LiveCallPhase.Failed -> L10n.tr("Call stopped. Tap to see why.")
    }

    companion object {
        private val none = MutableStateFlow<Nothing?>(null)

        fun of(calls: LiveVoiceCalls, mode: LiveVoiceMode): LiveCallView = when (mode) {
            LiveVoiceMode.GPT_LIVE -> calls.gpt.let { c ->
                LiveCallView(
                    mode, c.phaseFlow, c.transcriptFlow, c.microphoneMuted, c.speakerMuted, c.finishedTurn, c.billing, c.voiceNote, c.limitNote,
                    c::setMicrophoneMuted, c::setSpeakerMuted, null, { c.isEnding },
                )
            }
            else -> (if (mode == LiveVoiceMode.GROK_LIVE) calls.grok else calls.gemini).let { c ->
                LiveCallView(
                    mode, c.phaseFlow, c.transcriptFlow, c.microphoneMuted, c.speakerMuted, c.finishedTurn, none, none, none,
                    c::setMicrophoneMuted, c::setSpeakerMuted, c::interruptSpeaking, { c.isEnding },
                )
            }
        }

        fun orbState(phase: LiveCallPhase, microphoneMuted: Boolean): VoiceConversationState = when (phase) {
            LiveCallPhase.Idle -> VoiceConversationState.IDLE
            LiveCallPhase.Connecting, LiveCallPhase.Reconnecting, LiveCallPhase.Ending -> VoiceConversationState.THINKING
            LiveCallPhase.Listening -> if (microphoneMuted) VoiceConversationState.MUTED else VoiceConversationState.LISTENING
            LiveCallPhase.Speaking -> VoiceConversationState.SPEAKING
            LiveCallPhase.Paused -> VoiceConversationState.MUTED
            is LiveCallPhase.Failed -> VoiceConversationState.FAILED
        }
    }
}

/**
 * The call screen every live voice engine shares (iOS LiveVoiceCallSheet):
 * an orb that moves with the call's phase, the last lines as captions, the
 * call's newest job or screen card, and a row of round controls. Back (or
 * the chevron) minimises a running call; End hangs up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVoiceCallScreen(mode: LiveVoiceMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { VoiceHub.get(context) }
    val calls = hub.liveCalls
    val call = remember(mode) { LiveCallView.of(calls, mode) }
    val phase by call.phase.collectAsState()
    val transcript by call.transcript.collectAsState()
    val micMuted by call.microphoneMuted.collectAsState()
    val speakerMuted by call.speakerMuted.collectAsState()
    val billing by call.billing.collectAsState()
    val voiceNote by call.note.collectAsState()
    val limitNote by call.limitNote.collectAsState()
    val finishedTurn by call.finishedTurn.collectAsState()
    val thread by hub.jobs.liveThreadFlow.collectAsState()
    val asksFirst by hub.jobs.asksBeforeSendingFlow.collectAsState()
    val jobs by hub.jobs.jobs.collectAsState()
    val cards by hub.jobs.screenCards.collectAsState()
    val callId by hub.jobs.liveCallIdFlow.collectAsState()
    val keepAwake by context.appSettings.bool(AppSettings.VOICE_KEEP_SCREEN_AWAKE, false).collectAsState()
    var showsTranscript by remember { mutableStateOf(false) }
    var selectedJob by remember { mutableStateOf<String?>(null) }
    var selectedCard by remember { mutableStateOf<VoiceScreenCard?>(null) }
    var autoOpened by remember { mutableStateOf(setOf<String>()) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    val statusText = call.statusText(phase, micMuted, speakerMuted)
    val note = permissionError ?: limitNote ?: voiceNote
    val minimisable = phase.isActive

    // Leaving can be asked twice (End, then the call closing): the screen goes once.
    var left by remember { mutableStateOf(false) }
    fun goBack() {
        if (left) return
        left = true
        onBack()
    }

    fun leave() {
        calls.screenDismissed()
        goBack()
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionError = null
            calls.open(mode, calls.threadForOpenChat())
        } else {
            permissionError = L10n.tr(app.hermesconduit.android.core.audio.AudioRecordCapture.MICROPHONE_DENIED)
        }
    }

    LaunchedEffect(mode) {
        calls.restore()
        if (calls.mode.value == mode) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            calls.open(mode, calls.threadForOpenChat())
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A hands-free goodbye (or the notification's End) closes the screen like End.
    val openMode by calls.mode.collectAsState()
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openMode) {
        if (openMode == mode) wasOpen = true
        else if (wasOpen && openMode == null) goBack()
    }

    DisposableEffect(Unit) {
        calls.screenVisible = true
        onDispose { calls.screenVisible = false }
    }
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
    // TalkBack hears the call change state, and each line once with its final text.
    LaunchedEffect(phase) { if (phase != LiveCallPhase.Idle) view.announceForAccessibility(statusText) }
    LaunchedEffect(finishedTurn?.id) {
        val turn = finishedTurn ?: return@LaunchedEffect
        view.announceForAccessibility(if (turn.fromUser) L10n.tr("You: %s", turn.text) else call.assistantLabel(turn.text))
    }
    LaunchedEffect(voiceNote, limitNote) { (limitNote ?: voiceNote)?.let(view::announceForAccessibility) }
    // What the voice model just put on screen opens by itself, once, while nothing the user opened is up.
    LaunchedEffect(cards) {
        val card = cards.lastOrNull() ?: return@LaunchedEffect
        if (selectedJob != null || selectedCard?.let { it.id !in autoOpened } == true) return@LaunchedEffect
        if (card.callAnchor.callId != callId || card.id in autoOpened) return@LaunchedEffect
        if (System.currentTimeMillis() - card.shownAtMs >= SCREEN_CARD_FRESHNESS_MS) return@LaunchedEffect
        autoOpened = autoOpened + card.id
        selectedCard = card
    }

    BackHandler { leave() }

    val callJobs = jobs.filter { callId != null && it.callAnchor?.callId == callId }
    val callCards = cards.filter { callId != null && it.callAnchor.callId == callId }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave() }) {
                    Icon(if (minimisable) Icons.Default.KeyboardArrowDown else Icons.Default.Close, if (minimisable) t("Minimise call") else t("Close"))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(call.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    billing?.let { BillingBadge(it) }
                    thread?.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (minimisable) {
                    IconButton(
                        onClick = {
                            hub.jobs.setAsksBeforeSending(!asksFirst)
                            view.announceForAccessibility(if (!asksFirst) L10n.tr("Asking before sending to Hermes") else L10n.tr("Sending straight to Hermes"))
                        },
                        modifier = Modifier.semantics { contentDescription = L10n.tr("Ask before sending to Hermes") + ", " + (if (asksFirst) L10n.tr("On") else L10n.tr("Off")) },
                    ) {
                        Icon(
                            if (asksFirst) Icons.Default.PanTool else Icons.Outlined.PanTool, null,
                            tint = if (asksFirst) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }

            if (showsTranscript) {
                TranscriptList(call, transcript, statusText, note, callJobs, callCards, Modifier.weight(1f), onJob = { selectedJob = it.id }, onCard = { selectedCard = it })
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Spacer(Modifier.height(12.dp))
                    VoiceOrb(LiveCallView.orbState(phase, micMuted), if (phase == LiveCallPhase.Listening) 0.3f else 0f, Modifier.size(184.dp))
                    Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                    thread?.title?.let {
                        Text(L10n.tr("Working in %s. Say “quick…” for a fast side job.", it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    JobStrip(callJobs, callCards, onJob = { selectedJob = it.id }, onCard = { selectedCard = it })
                    Captions(transcript)
                }
            }

            // Controls
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                val canMute = phase.isActive && !call.isEnding()
                if (phase is LiveCallPhase.Failed) {
                    CallButton(Icons.Default.Refresh, t("Try again"), onClick = { calls.retry() })
                } else {
                    CallButton(
                        if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        if (micMuted) t("Unmute") else t("Mute"),
                        description = if (micMuted) t("Unmute microphone") else t("Mute microphone"),
                        selected = micMuted, enabled = canMute,
                        onClick = {
                            call.setMicrophoneMuted(!micMuted)
                            view.announceForAccessibility(if (!micMuted) L10n.tr("Microphone muted") else L10n.tr("Microphone unmuted"))
                        },
                    )
                    CallButton(
                        if (speakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (speakerMuted) t("Sound on") else t("Silence"),
                        description = if (speakerMuted) t("Turn the assistant's sound back on") else t("Silence the assistant"),
                        selected = speakerMuted, enabled = canMute,
                        onClick = {
                            call.setSpeakerMuted(!speakerMuted)
                            view.announceForAccessibility(if (!speakerMuted) L10n.tr("Assistant silenced") else L10n.tr("Assistant's sound back on"))
                        },
                    )
                    call.interrupt?.let { interrupt ->
                        CallButton(Icons.Default.Stop, t("Interrupt"), enabled = phase == LiveCallPhase.Speaking, onClick = interrupt)
                    }
                }
                CallButton(Icons.Default.Subject, t("Transcript"), selected = showsTranscript, onClick = { showsTranscript = !showsTranscript })
                CallButton(Icons.Default.CallEnd, t("End"), description = t("End call"), destructive = true, onClick = {
                    calls.close()
                    goBack()
                })
            }
        }
    }

    selectedJob?.let { id ->
        ModalBottomSheet(onDismissRequest = { selectedJob = null }) {
            JobResult(jobs.firstOrNull { it.id == id }, onOpenChat = { chatId, title ->
                selectedJob = null
                calls.screenDismissed()
                calls.chatHost?.openSession(chatId, title)
                goBack()
            })
        }
    }
    selectedCard?.let { card ->
        ModalBottomSheet(onDismissRequest = { selectedCard = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(card.title.ifEmpty { t("On screen") }, style = MaterialTheme.typography.titleLarge)
                RichMarkdown(card.markdown)
                TextButton(onClick = { selectedCard = null }, modifier = Modifier.align(Alignment.End)) { Text(t("Done")) }
            }
        }
    }
}

private const val SCREEN_CARD_FRESHNESS_MS = 5_000L

@Composable
private fun BillingBadge(billing: GPTLiveBilling) {
    val tint = if (billing == GPTLiveBilling.API) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.15f)) {
        Text(GPTLiveClient.badge(billing), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String = title,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp)) {
        val colors = when {
            destructive -> IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            selected -> IconButtonDefaults.filledIconButtonColors()
            else -> IconButtonDefaults.filledTonalIconButtonColors()
        }
        if (destructive || selected) {
            FilledIconButton(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.size(56.dp)) { Icon(icon, description) }
        } else {
            FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) { Icon(icon, description) }
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** The captions under the orb: the last two lines said. */
@Composable
private fun Captions(transcript: List<LiveTranscriptEntry>) {
    val lines = transcript.takeLast(2)
    Column(Modifier.fillMaxWidth().height(110.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEachIndexed { index, entry ->
            val latest = index == lines.lastIndex
            Text(
                entry.text,
                style = if (latest) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (latest) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (latest) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun jobStatusText(job: VoiceBackgroundJob): String = when (job.status) {
    VoiceBackgroundJob.Status.Starting, VoiceBackgroundJob.Status.Running -> L10n.tr("Working…")
    VoiceBackgroundJob.Status.NeedsInput -> L10n.tr("Waiting for you")
    VoiceBackgroundJob.Status.Finished -> if (job.result.isNullOrBlank()) L10n.tr("Done") else L10n.tr("Ready")
    is VoiceBackgroundJob.Status.Failed -> L10n.tr("Failed")
    VoiceBackgroundJob.Status.Cancelled -> L10n.tr("Cancelled")
}

@Composable
private fun JobCard(job: VoiceBackgroundJob, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = L10n.tr("Shows the full result"), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${job.title}, ${jobStatusText(job)}" },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (job.status) {
                    VoiceBackgroundJob.Status.Starting, VoiceBackgroundJob.Status.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    VoiceBackgroundJob.Status.Finished -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    is VoiceBackgroundJob.Status.Failed, VoiceBackgroundJob.Status.NeedsInput -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    VoiceBackgroundJob.Status.Cancelled -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(jobStatusText(job), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardRow(card: VoiceScreenCard, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = L10n.tr("Shows it again"), onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(card.title.ifEmpty { t("On screen") }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (card.title.isNotEmpty()) Text(t("On screen"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The newest job or screen card of the call, with how many more there are. */
@Composable
private fun JobStrip(jobs: List<VoiceBackgroundJob>, cards: List<VoiceScreenCard>, onJob: (VoiceBackgroundJob) -> Unit, onCard: (VoiceScreenCard) -> Unit) {
    val count = jobs.size + cards.size
    if (count == 0) return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val card = cards.lastOrNull()
        val job = jobs.lastOrNull()
        if (card != null && card.shownAtMs >= (job?.startedAtMs ?: 0)) CardRow(card) { onCard(card) }
        else if (job != null) JobCard(job) { onJob(job) }
        if (count > 1) Text(L10n.tr("%d more in the transcript", count - 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private sealed interface TimelineItem {
    data class Line(val entry: LiveTranscriptEntry) : TimelineItem
    data class Job(val job: VoiceBackgroundJob) : TimelineItem
    data class Card(val card: VoiceScreenCard) : TimelineItem
}

/** The call's lines with its jobs and screen cards placed after the line each followed. */
private fun timeline(transcript: List<LiveTranscriptEntry>, jobs: List<VoiceBackgroundJob>, cards: List<VoiceScreenCard>): List<TimelineItem> {
    val index = transcript.withIndex().associate { it.value.id to it.index }
    fun position(anchorEntry: String?): Int = anchorEntry?.let { index[it] } ?: -1
    val placed = jobs.map { position(it.callAnchor?.afterEntryId) to (it.startedAtMs to TimelineItem.Job(it) as TimelineItem) } +
        cards.map { position(it.callAnchor.afterEntryId) to (it.shownAtMs to TimelineItem.Card(it) as TimelineItem) }
    val byPosition = placed.groupBy({ it.first }, { it.second }).mapValues { (_, items) -> items.sortedBy { it.first }.map { it.second } }
    val items = mutableListOf<TimelineItem>()
    byPosition[-1]?.let(items::addAll)
    transcript.forEachIndexed { i, entry ->
        items += TimelineItem.Line(entry)
        byPosition[i]?.let(items::addAll)
    }
    return items
}

@Composable
private fun TranscriptList(
    call: LiveCallView,
    transcript: List<LiveTranscriptEntry>,
    status: String,
    note: String?,
    jobs: List<VoiceBackgroundJob>,
    cards: List<VoiceScreenCard>,
    modifier: Modifier,
    onJob: (VoiceBackgroundJob) -> Unit,
    onCard: (VoiceScreenCard) -> Unit,
) {
    val items = timeline(transcript, jobs, cards)
    val state = rememberLazyListState()
    LaunchedEffect(items.size) { if (items.isNotEmpty()) state.animateScrollToItem(items.lastIndex) }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(status, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (items.isEmpty()) {
                item {
                    Text(t("What you and the assistant say will appear here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
                }
            }
            items(items, key = {
                when (it) {
                    is TimelineItem.Line -> "l" + it.entry.id
                    is TimelineItem.Job -> "j" + it.job.id
                    is TimelineItem.Card -> "c" + it.card.id
                }
            }) { item ->
                when (item) {
                    is TimelineItem.Line -> Bubble(item.entry, if (item.entry.fromUser) L10n.tr("You: %s", item.entry.text) else call.assistantLabel(item.entry.text))
                    is TimelineItem.Job -> JobCard(item.job) { onJob(item.job) }
                    is TimelineItem.Card -> CardRow(item.card) { onCard(item.card) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(entry: LiveTranscriptEntry, label: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = if (entry.fromUser) Alignment.End else Alignment.Start) {
        Surface(
            color = if (entry.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text(entry.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** A job's full result over the call; follows the job, so it fills in when the job finishes while open. */
@Composable
private fun JobResult(job: VoiceBackgroundJob?, onOpenChat: (String, String) -> Unit) {
    var lastSeen by remember { mutableStateOf(job) }
    if (job != null) lastSeen = job
    val shown = job ?: lastSeen
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (shown == null) {
            Text(t("The full result is in its chat."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(shown.title, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.status.isActive) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(jobStatusText(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val status = shown.status
        when {
            !shown.result.isNullOrBlank() -> RichMarkdown(shown.result)
            status.isActive -> Text(t("Hermes is still working on this. The result will show here."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            status is VoiceBackgroundJob.Status.Failed -> Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status == VoiceBackgroundJob.Status.Cancelled -> Text(t("This job was cancelled."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(t("The full result is in its chat."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A job on another profile opens from that profile; the link only reaches chats on the one in use.
        val chatId = listOfNotNull(shown.storedSessionId, shown.runtimeSessionId).firstOrNull { it.isNotEmpty() }
        if (chatId != null && shown.profile == null) {
            TextButton(onClick = { onOpenChat(chatId, shown.title) }) { Text(t("Open chat")) }
        }
    }
}
