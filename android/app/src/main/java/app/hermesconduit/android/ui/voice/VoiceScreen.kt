package app.hermesconduit.android.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.VoiceSessionTagKind
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.AppViewModel
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.nav.activityAppViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The voice host for the chat open in [app]: spoken turns go there as voice turns. */
private class ChatVoiceHost(private val hub: VoiceHub, private val app: AppViewModel, private val onEnd: () -> Unit) : VoiceConversationHost {
    /** Voice started with no chat open: the chat it creates is filed under Voice. */
    private var startsFreshChat = app.chat.value.runtimeId == null && app.chat.value.sessionId == null

    override suspend fun submit(text: String): Boolean {
        val submitted = app.submitPrompt(text, emptyList(), SPOKEN_PROMPT_SURFACE)
        if (submitted && startsFreshChat) {
            startsFreshChat = false
            val chat = app.chat.value
            hub.tagSessions(listOfNotNull(chat.sessionId, chat.runtimeId), VoiceSessionTagKind.CLASSIC)
        }
        return submitted
    }

    override suspend fun interrupt(): Boolean {
        val runtimeId = app.chat.value.runtimeId ?: return true
        return runCatching { hub.hermes.rpc("session.interrupt", buildJsonObject { put("session_id", runtimeId) }) }.isSuccess
    }

    override suspend fun steer(text: String): VoiceSteerOutcome {
        val chat = app.chat.value
        val runtimeId = chat.runtimeId ?: return VoiceSteerOutcome.NO_RUNNING_TURN
        if (!chat.running) return VoiceSteerOutcome.NO_RUNNING_TURN
        return try {
            val result = hub.hermes.rpc("session.steer", buildJsonObject { put("session_id", runtimeId); put("text", text) })
            if ((result as? kotlinx.serialization.json.JsonObject)?.get("status").str == "rejected") VoiceSteerOutcome.FAILED else VoiceSteerOutcome.STEERED
        } catch (_: Exception) {
            VoiceSteerOutcome.FAILED
        }
    }

    override fun ownsSession(sessionId: String): Boolean {
        val chat = app.chat.value
        return sessionId.isNotEmpty() && (sessionId == chat.runtimeId || sessionId == chat.sessionId)
    }

    override fun endConversation() {
        hub.closeConversation()
        onEnd()
    }

    companion object {
        const val SPOKEN_PROMPT_SURFACE = "voice-live"
    }
}

/** The classic voice conversation (iOS VoiceConversationSheet). */
@Composable
fun VoiceConversationScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val hub = remember { VoiceHub.get(context) }
    val appVm = activityAppViewModel()
    val controller = hub.conversation
    val state by controller.state.collectAsStateWithLifecycle()
    val failure by controller.failure.collectAsStateWithLifecycle()
    val level by controller.microphoneLevel.collectAsStateWithLifecycle()
    val paused by controller.isMicrophonePaused.collectAsStateWithLifecycle()
    val muted by controller.isOutputMuted.collectAsStateWithLifecycle()
    val suspended by controller.isPlaybackCaptureSuspended.collectAsStateWithLifecycle()
    val steering by controller.isSteeringTurn.collectAsStateWithLifecycle()
    val live by controller.liveTranscript.collectAsStateWithLifecycle()
    val transcript by controller.conversationTranscript.collectAsStateWithLifecycle()
    val jobs by hub.jobs.jobs.collectAsStateWithLifecycle()
    val keepAwake by context.appSettings.bool(AppSettings.VOICE_KEEP_SCREEN_AWAKE, false).collectAsStateWithLifecycle()
    val profiles = appVm?.profiles?.collectAsStateWithLifecycle()?.value.orEmpty()
    val profile by hub.hermes.profile.collectAsStateWithLifecycle()
    var openError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val close = {
        hub.closeConversation()
        onBack()
    }

    LaunchedEffect(profiles) { hub.knownProfiles = profiles }
    LaunchedEffect(appVm) {
        appVm?.sessions?.value?.sessions?.let { list -> app.hermesconduit.android.model.HermesSessionReference.titles = list.associate { it.id to it.title } }
    }

    fun open() {
        val viewModel = appVm ?: return
        if (hub.conversationOpen.value) {
            if (controller.currentState == VoiceConversationState.IDLE && !controller.isRuntimeSuspended) controller.startListening()
            return
        }
        openError = hub.openConversation(ChatVoiceHost(hub, viewModel, onBack))
        if (openError == null) controller.startListening()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { open() }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            openError = L10n.tr(app.hermesconduit.android.core.audio.AudioRecordCapture.MICROPHONE_DENIED)
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hub.preferences().keepListeningWhenLocked == true &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else open()
    }

    LaunchedEffect(Unit) {
        if (hub.capability.value == null) hub.refreshCapabilities()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) open()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Keep phone awake during voice conversations (device setting).
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> hub.onConversationBackgrounded()
                Lifecycle.Event.ON_START -> hub.onConversationForegrounded()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // The app closing ends the voice session (a rotation keeps it); Back and End close it explicitly.
            val activity = context.activity()
            if (activity?.isFinishing == true && !activity.isChangingConfigurations) hub.closeConversation()
        }
    }

    androidx.activity.compose.BackHandler { close() }

    val interruptAvailable = suspended
    val microphoneActive = !paused && state != VoiceConversationState.IDLE && state != VoiceConversationState.FAILED
    val statusTitle = when {
        interruptAvailable -> t("Hermes is speaking")
        paused -> t("Microphone paused")
        steering -> t("Steering Hermes")
        else -> when (state) {
            VoiceConversationState.IDLE -> t("Ready to listen")
            VoiceConversationState.LISTENING -> t("Listening")
            VoiceConversationState.TRANSCRIBING -> t("Transcribing")
            VoiceConversationState.THINKING -> t("Hermes is thinking")
            VoiceConversationState.SPEAKING -> t("Hermes is speaking")
            VoiceConversationState.MUTED -> t("Assistant audio muted")
            VoiceConversationState.FAILED -> t("Voice needs attention")
        }
    }
    val statusDetail = when {
        interruptAvailable -> t("Tap Interrupt to speak.")
        paused -> t("Tap Listen when you are ready to resume.")
        steering -> t("Hermes keeps working with your words added.")
        else -> when (state) {
            VoiceConversationState.IDLE -> t("Tap Listen when you are ready.")
            VoiceConversationState.LISTENING -> t("Pause the microphone whenever you need a break.")
            VoiceConversationState.TRANSCRIBING -> t("Sending your speech to Hermes.")
            VoiceConversationState.THINKING -> t("Speak to steer Hermes while it works.")
            VoiceConversationState.SPEAKING -> t("Speak over Hermes to start a new turn.")
            VoiceConversationState.MUTED -> t("Assistant text is still continuing in chat.")
            VoiceConversationState.FAILED -> failure.orEmpty()
        }
    }
    val profileName = if (profile == "default") t("Default profile") else profile.replace('_', ' ').replaceFirstChar { it.uppercase() }

    ScreenScaffold(
        title = t("Voice conversation"),
        onBack = close,
        actions = { IconButton(onClick = { navigate("settings.voice") }) { Icon(Icons.Default.Settings, t("Voice")) } },
    ) { padding ->
        SettingsColumn(padding) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceOrb(state, level, Modifier.size(200.dp))
                Text(statusTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$profileName · ${t("Classic voice")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (microphoneActive && state == VoiceConversationState.LISTENING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("Mic input"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        VoiceInputLevelMeter(level, isActive = true)
                    }
                }
                if (live.isNotEmpty()) {
                    Text(live, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                ErrorText(openError)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                val primaryLabel = when {
                    state == VoiceConversationState.TRANSCRIBING -> t("Transcribing")
                    interruptAvailable -> t("Interrupt")
                    microphoneActive -> t("Pause mic")
                    else -> t("Listen")
                }
                Button(
                    enabled = state != VoiceConversationState.TRANSCRIBING && openError == null,
                    onClick = {
                        when {
                            interruptAvailable -> scope.launch { controller.interruptAssistantPlayback() }
                            microphoneActive -> controller.pauseMicrophone()
                            else -> if (!hub.conversationOpen.value) open() else controller.resumeMicrophone()
                        }
                    },
                ) {
                    Icon(
                        when {
                            interruptAvailable -> Icons.Default.PanTool
                            microphoneActive -> Icons.Default.MicOff
                            else -> Icons.Default.Mic
                        },
                        null,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(primaryLabel)
                }
                FilledTonalButton(onClick = { controller.setOutputMuted(!muted) }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (muted) t("Sound on") else t("Silence"))
                }
                OutlinedButton(onClick = close, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.CallEnd, t("End call"))
                    Spacer(Modifier.size(6.dp))
                    Text(t("End"))
                }
            }
            Text(
                t("Your conversation also continues in chat. Close ends this voice session."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsSection(t("Conversation")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (transcript.isEmpty()) {
                        Text(t("Your spoken words and Hermes' replies will appear here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    transcript.forEach { entry -> TranscriptBubble(entry.isUser, entry.text) }
                }
            }

            val visibleJobs = jobs
            SettingsSection(t("Background jobs"), footer = t("Each job is its own chat in your session list. Say “job status” or “cancel background jobs” at any time.")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visibleJobs.isEmpty()) {
                        Text(t("Say “background job” and then a task to run it as its own chat while you keep talking."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    visibleJobs.forEach { job ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.title, style = MaterialTheme.typography.bodyMedium)
                                Text(jobStatusLabel(job.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (job.status.isActive) {
                                TextButton(onClick = { scope.launch { hub.jobs.cancel(job.id) } }) { Text(t("Cancel")) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun jobStatusLabel(status: VoiceBackgroundJob.Status): String = when (status) {
    VoiceBackgroundJob.Status.Starting -> t("Starting")
    VoiceBackgroundJob.Status.Running -> t("Running")
    VoiceBackgroundJob.Status.NeedsInput -> t("Waiting for your input")
    VoiceBackgroundJob.Status.Finished -> t("Finished")
    is VoiceBackgroundJob.Status.Failed -> t("Failed")
    VoiceBackgroundJob.Status.Cancelled -> t("Cancelled")
}

@Composable
private fun TranscriptBubble(isUser: Boolean, text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(if (isUser) t("You") else t("Hermes"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
