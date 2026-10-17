package app.hermesconduit.android.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.DictationCustomWords
import app.hermesconduit.android.model.DictationEngine
import app.hermesconduit.android.model.ReadAloudSpeed
import app.hermesconduit.android.model.VoiceConfigurationParser
import app.hermesconduit.android.model.VoiceFieldKind
import app.hermesconduit.android.model.VoiceKeyGroup
import app.hermesconduit.android.model.VoiceProviderConfiguration
import app.hermesconduit.android.model.VoiceProviderKind
import app.hermesconduit.android.model.VoiceProviderReadiness
import app.hermesconduit.android.model.VoiceSetupDefaults
import app.hermesconduit.android.model.VoiceSpokenCommandDefaults
import app.hermesconduit.android.model.VoiceSpokenCommands
import app.hermesconduit.android.model.VoiceTranscriptionMode
import app.hermesconduit.android.model.VoiceTypedField
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import kotlinx.coroutines.launch

private enum class StepState { DONE, ATTENTION, PENDING }

private enum class VoiceMode(val title: String, val summary: String) {
    CLASSIC("Classic", "Hermes listens and replies with the speech to text and assistant voice set up above. Works with any Hermes server."),
    GEMINI("Gemini Live", "A real-time conversation with Gemini that hands the work to Hermes. Needs the Hermes notifier plugin and a Gemini key on your Hermes server."),
    GPT("GPT-Live", "A real-time conversation on your Hermes server's ChatGPT sign-in that hands the work to Hermes. Needs the Hermes notifier plugin."),
    GROK("Grok Live", "A real-time conversation with Grok through your Hermes server's SuperGrok sign-in that hands the work to Hermes. Needs the Hermes notifier plugin."),
}

/** Settings > Voice (iOS VoiceSettingsView) for the active profile. */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val vm: VoiceSettingsViewModel = viewModel()
    val context = LocalContext.current
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val loading by vm.service.isLoading.collectAsStateWithLifecycle()
    val error by vm.service.errorMessage.collectAsStateWithLifecycle()
    val enabled by vm.voiceEnabled.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val applying by vm.applyingDefaults.collectAsStateWithLifecycle()
    val testStatus by vm.testStatus.collectAsStateWithLifecycle()
    val runningTest by vm.runningTest.collectAsStateWithLifecycle()
    val level by vm.hub.conversation.microphoneLevel.collectAsStateWithLifecycle()
    val conversationState by vm.hub.conversation.state.collectAsStateWithLifecycle()
    val advanced by context.appSettings.bool(AppSettings.VOICE_SETTINGS_ADVANCED, false).collectAsStateWithLifecycle()
    val outbox by vm.hub.outbox.collectAsStateWithLifecycle()
    val outboxBlocked by vm.hub.outboxBlocked.collectAsStateWithLifecycle()
    val outboxSaving by vm.hub.outboxSaving.collectAsStateWithLifecycle()
    var micGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val mode = when {
        prefs.geminiLiveEnabled -> VoiceMode.GEMINI
        prefs.gptLiveEnabled -> VoiceMode.GPT
        prefs.grokLiveEnabled -> VoiceMode.GROK
        else -> VoiceMode.CLASSIC
    }
    val transcription = prefs.resolvedTranscriptionMode
    val supportsSelectedTranscription = when (transcription) {
        VoiceTranscriptionMode.ON_DEVICE -> vm.onDeviceAvailable
        VoiceTranscriptionMode.SPEECH_SERVER -> true
        VoiceTranscriptionMode.HERMES -> snapshot.capability.supportsTranscription
    }
    val profileName = if (vm.profile == "default") t("Default profile") else vm.profile.replace('_', ' ').replaceFirstChar { it.uppercase() }
    // The setup step's fix ("choose one under Speech to text below") lives under Advanced.
    LaunchedEffect(enabled, mode, loading, snapshot) {
        val needsAttention = enabled && mode == VoiceMode.CLASSIC && !loading && snapshot.capability.isGatewayConnected &&
            (!supportsSelectedTranscription || !snapshot.capability.supportsSpeech)
        if (needsAttention) context.appSettings.set(AppSettings.VOICE_SETTINGS_ADVANCED, true)
    }

    ScreenScaffold(t("Voice"), onBack) { padding ->
        SettingsColumn(padding) {
            val pending = outbox.pending(vm.hub.hermes.connection.value?.id ?: "-", vm.profile).size
            if (pending > 0) {
                SettingsSection(t("Unsaved voice calls")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("Voice calls waiting to save: %s", pending.toString()), fontWeight = FontWeight.SemiBold)
                        Caption(
                            if (outboxBlocked) t("Your Hermes server can't save voice calls yet. Install or update the Hermes notifier plugin on your Hermes server, then tap Save Now.")
                            else t("These calls are kept on this device and Conduit retries them automatically. It keeps up to 20 calls, and a call that still hasn't saved after 7 days is dropped."),
                        )
                        OutlinedButton(onClick = vm::saveVoiceCallsNow, enabled = !outboxSaving, modifier = Modifier.fillMaxWidth()) {
                            Text(if (outboxSaving) t("Saving…") else t("Save Now"))
                        }
                    }
                }
            }

            // Set up voice
            SettingsSection(t("Set up voice")) {
                SettingsToggle(t("Enable voice on this device"), enabled, { vm.setVoiceEnabled(it) }, enabled = !applying)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption(
                        if (enabled) t("Voice is on for %s on this phone. Each step below turns green when it's ready; an orange step has a fix.", profileName)
                        else t("Turn this on to talk to %s. Conduit asks for the microphone and Speech Recognition, uses this phone for speech to text, and switches the assistant's voice to Edge TTS on Hermes if the current one isn't ready.", profileName),
                    )
                    if (applying) Caption(t("Setting up voice…"))
                    if (enabled) {
                        // Hermes
                        when {
                            loading -> SetupStep(t("Hermes"), t("Checking…"), StepState.PENDING)
                            snapshot.capability.isGatewayConnected -> SetupStep(t("Hermes"), t("Connected to %s", profileName), StepState.DONE)
                            else -> SetupStep(t("Hermes"), snapshot.capability.unavailableReason ?: t("Connect to Hermes before starting voice."), StepState.ATTENTION)
                        }
                        // Microphone
                        if (micGranted) SetupStep(t("Microphone"), t("Allowed"), StepState.DONE)
                        else SetupStep(t("Microphone"), t("Not allowed yet."), StepState.PENDING, t("Allow")) { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                        if (mode == VoiceMode.CLASSIC) {
                            SpeechToTextStep(vm, transcription, loading)
                            AssistantVoiceStep(vm, loading)
                        } else {
                            Caption(t("%s brings its own listening and speaking, so the speech-to-text and assistant voice steps don't apply. Pick Classic under Voice mode to use them.", t(mode.title)))
                        }
                        TextButton(onClick = vm::load, enabled = !loading && !applying) { Text(if (loading) t("Checking…") else t("Check again")) }
                        if (mode == VoiceMode.CLASSIC || runningTest != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { vm.runTest(VoiceProviderKind.STT) },
                                    enabled = runningTest == null && supportsSelectedTranscription,
                                    modifier = Modifier.weight(1f),
                                ) { Text(t("Test listening")) }
                                OutlinedButton(
                                    onClick = { vm.runTest(VoiceProviderKind.TTS) },
                                    enabled = runningTest == null && snapshot.capability.supportsSpeech,
                                    modifier = Modifier.weight(1f),
                                ) { Text(t("Test speaking")) }
                            }
                            testStatus?.let { Caption(it) }
                            if (runningTest == VoiceProviderKind.STT && conversationState == VoiceConversationState.LISTENING) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Caption(t("Microphone input"))
                                    VoiceInputLevelMeter(level, isActive = true)
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // Voice mode
            SettingsSection(t("Voice mode"), footer = t(mode.summary)) {
                ChoiceRow(t("Mode"), t(mode.title), VoiceMode.entries.map { it.name to t(it.title) }) { picked ->
                    val selected = VoiceMode.valueOf(picked)
                    vm.updatePreferences {
                        it.copy(
                            geminiLiveEnabled = selected == VoiceMode.GEMINI,
                            gptLiveEnabled = selected == VoiceMode.GPT,
                            grokLiveEnabled = selected == VoiceMode.GROK,
                        )
                    }
                    // One voice mode at a time: a call of the mode turned off ends.
                    if (selected != mode) vm.hub.liveCalls.close()
                }
            }
            LiveVoiceModeSettings(vm, prefs)
            if (enabled) {
                VoiceJobModelSection(vm, prefs)
                if (mode == VoiceMode.CLASSIC) VoiceReplyModelSection(vm)
            }

            // Conversation
            SettingsSection(t("Conversation")) {
                SettingsToggle(t("Continuous Conversation"), prefs.continuousConversation, { on -> vm.updatePreferences { it.copy(continuousConversation = on) } })
                Footnote(t("When enabled, Conduit automatically listens again after each response and keeps listening through long silences until you pause the mic. When disabled, the session stays open and you start the next listening turn manually. This does not change Pause Mic, Interrupt, Close, or wake-word settings."))
                SettingsToggle(t("Keep listening when locked"), prefs.keepListeningWhenLocked == true, { on -> vm.updatePreferences { it.copy(keepListeningWhenLocked = on) } })
                Footnote(t("A voice conversation that is already listening keeps going when you lock the phone or switch apps, so you can talk hands-free. While locked, Conduit always listens again after each response and keeps listening through silence. End it by saying a goodbye phrase or from the app. Uses more battery."))
                val keepAwake by context.appSettings.bool(AppSettings.VOICE_KEEP_SCREEN_AWAKE, false).collectAsStateWithLifecycle()
                SettingsToggle(t("Keep phone awake during voice conversations"), keepAwake, { context.appSettings.set(AppSettings.VOICE_KEEP_SCREEN_AWAKE, it) })
                Footnote(t("The screen stays on while a voice conversation is open, including the live voice modes. When off, the phone locks on its usual timer: live voice calls keep going in the background, and a classic voice conversation does too with Keep Listening When Locked on. Applies to this device."))
            }

            app.hermesconduit.android.wake.WakePhraseSettingsSection(vm.profile)

            // Read Aloud
            SettingsSection(t("Read Aloud"), footer = t("How fast the speaker button under a reply reads it aloud, without changing the voice's pitch. Applies from the next reply you play, on this device. Voice conversations always play at normal speed.")) {
                val stored by context.appSettings.float(AppSettings.READ_ALOUD_SPEED, 1f).collectAsStateWithLifecycle()
                val speed = ReadAloudSpeed.of(stored)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Speed"), fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ReadAloudSpeed.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = option == speed,
                                onClick = { context.appSettings.set(AppSettings.READ_ALOUD_SPEED, option.rate) },
                                shape = SegmentedButtonDefaults.itemShape(index, ReadAloudSpeed.entries.size),
                            ) { Text(option.label, maxLines = 1) }
                        }
                    }
                }
            }

            DictationSection()

            // Advanced
            SettingsSection(null) {
                Row(
                    Modifier.fillMaxWidth().clickable { context.appSettings.set(AppSettings.VOICE_SETTINGS_ADVANCED, !advanced) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Advanced"), fontWeight = FontWeight.SemiBold)
                        if (!advanced) Caption(t("Speech providers, voice models, spoken phrases and experimental options."))
                    }
                    Icon(if (advanced) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            if (advanced) {
                SpokenControlsSection(vm)
                if (mode == VoiceMode.CLASSIC) {
                    when {
                        loading -> Caption(t("Loading profile voice settings…"))
                        snapshot.capability.isGatewayConnected -> {
                            ProviderSection(vm, VoiceProviderKind.STT, snapshot.sttProviders, transcription)
                            ProviderSection(vm, VoiceProviderKind.TTS, snapshot.ttsProviders, transcription)
                            CredentialsSection(vm, transcription)
                        }
                    }
                }
                SettingsSection(t("Speaker and call screen")) {
                    SettingsToggle(t("Talk over Hermes on the speaker"), prefs.liveVoiceSpeakerBargeIn == true, { on -> vm.updatePreferences { it.copy(liveVoiceSpeakerBargeIn = on) } })
                    Footnote(t("Experimental. Keeps the microphone open while Hermes reads its reply on the phone's speaker or in the car, with echo cancellation, so you can speak over it to start a new turn. Headphones always allow this. Turn it off if Hermes keeps cutting itself off. Applies to the next conversation."))
                    val animate by context.appSettings.bool(AppSettings.VOICE_ANIMATE_ORB, true).collectAsStateWithLifecycle()
                    SettingsToggle(t("Animate the call orb"), animate, { context.appSettings.set(AppSettings.VOICE_ANIMATE_ORB, it) })
                    Footnote(t("The orb on the live call screen moves with the call. Turn this off for a still orb that uses less battery. It also holds still while the phone is hot or Reduce Motion is on. Applies to this device."))
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
}

@Composable
private fun SetupStep(title: String, detail: String, state: StepState, fixLabel: String? = null, fix: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            when (state) {
                StepState.DONE -> Icons.Default.CheckCircle
                StepState.ATTENTION -> Icons.Default.Error
                StepState.PENDING -> Icons.Default.RadioButtonUnchecked
            },
            null,
            tint = when (state) {
                StepState.DONE -> Color(0xFF34C759)
                StepState.ATTENTION -> Color(0xFFFF9500)
                StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Caption(detail)
        }
        if (fixLabel != null && fix != null) TextButton(onClick = fix) { Text(fixLabel) }
    }
}

@Composable
private fun SpeechToTextStep(vm: VoiceSettingsViewModel, mode: VoiceTranscriptionMode, loading: Boolean) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val server by vm.service.speechServer.collectAsStateWithLifecycle()
    val title = t("Speech to text")
    when (mode) {
        VoiceTranscriptionMode.ON_DEVICE ->
            if (vm.onDeviceAvailable) SetupStep(title, t("On this device"), StepState.DONE)
            else SetupStep(title, t("This phone can't transcribe your language on the device. Choose a Hermes provider under Speech to text below."), StepState.ATTENTION)
        VoiceTranscriptionMode.SPEECH_SERVER -> {
            val status = server
            when {
                status != null && status.available && status.url != null -> SetupStep(title, status.url, StepState.DONE)
                status != null && (status.configured || status.pluginMissing) -> SetupStep(title, status.reason ?: t("Your speech server. Use Test connection under Speech to text to check it."), StepState.ATTENTION)
                else -> SetupStep(title, t("Your speech server. Use Test connection under Speech to text to check it."), StepState.PENDING)
            }
        }
        VoiceTranscriptionMode.HERMES -> {
            if (loading || !snapshot.capability.isGatewayConnected) {
                SetupStep(title, t("Not checked yet"), StepState.PENDING)
                return
            }
            val name = providerName(snapshot.sttProviders, snapshot.selectedSTTProvider, VoiceProviderKind.STT)
            val fixLabel = if (vm.onDeviceAvailable) t("Use this device") else null
            val fix = { vm.selectProvider(VoiceSettingsViewModel.ON_DEVICE_PROVIDER_ID, VoiceProviderKind.STT) }
            if (!snapshot.capability.supportsTranscription) {
                SetupStep(title, snapshot.capability.unavailableReason ?: t("This Hermes profile has no ready speech-to-text provider."), StepState.ATTENTION, fixLabel, fix)
                return
            }
            when (val readiness = VoiceSetupDefaults.providerReadiness(snapshot.selectedSTTProvider, snapshot.sttProviders)) {
                VoiceSetupDefaults.Readiness.Ready -> SetupStep(title, t("%s on Hermes", name), StepState.DONE)
                is VoiceSetupDefaults.Readiness.NotReady -> SetupStep(
                    title, t("%s on Hermes isn't ready (%s). This phone can do it instead, with nothing to install.", name, VoiceProviderReadiness.label(readiness.status)),
                    StepState.ATTENTION, fixLabel, fix,
                )
                VoiceSetupDefaults.Readiness.Unknown -> SetupStep(title, t("%s on Hermes. Hermes doesn't report whether it's ready.", name), StepState.PENDING, fixLabel, fix)
            }
        }
    }
}

@Composable
private fun AssistantVoiceStep(vm: VoiceSettingsViewModel, loading: Boolean) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val title = t("Assistant voice")
    val name = providerName(snapshot.ttsProviders, snapshot.selectedTTSProvider, VoiceProviderKind.TTS)
    when {
        snapshot.capability.supportsSpeech -> SetupStep(title, t("%s on Hermes", name), StepState.DONE)
        VoiceSetupDefaults.canSwitchSpeechToEdge(snapshot) -> SetupStep(
            title,
            if (snapshot.selectedTTSProvider.isEmpty()) t("No assistant voice is set up yet. Edge TTS is free and ready.") else t("%s isn't ready on Hermes. Edge TTS is free and ready.", name),
            StepState.ATTENTION, t("Use Edge TTS"),
        ) { vm.selectProvider(VoiceSetupDefaults.EDGE_TTS_PROVIDER_ID, VoiceProviderKind.TTS) }
        loading || !snapshot.capability.isGatewayConnected -> SetupStep(title, t("Not checked yet"), StepState.PENDING)
        else -> SetupStep(
            title,
            if (snapshot.selectedTTSProvider.isEmpty()) t("No assistant voice is set up yet. Choose one under Assistant speech below.") else t("%s isn't ready on Hermes. Choose another under Assistant speech below.", name),
            StepState.ATTENTION,
        )
    }
}

private fun providerName(providers: List<VoiceProviderConfiguration>, id: String, kind: VoiceProviderKind): String =
    providers.firstOrNull { it.descriptor.id == id }?.descriptor?.displayName
        ?: VoiceConfigurationParser.catalogDescriptor(id, kind)?.displayName
        ?: if (id.isEmpty()) L10n.tr("Not chosen") else id.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** A row that opens a menu of choices. */
@Composable
internal fun ChoiceRow(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    open = false
                    onPick(id)
                })
            }
        }
    }
}

@Composable
private fun DictationSection() {
    val context = LocalContext.current
    val settings = context.appSettings
    val raw by settings.string(AppSettings.DICTATION_ENGINE, DictationEngine.DEVICE.raw).collectAsStateWithLifecycle()
    val stored by settings.string(AppSettings.DICTATION_CUSTOM_WORDS, "").collectAsStateWithLifecycle()
    val engine = DictationEngine.resolved(raw)
    val words = DictationCustomWords.decode(stored)
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    fun title(engine: DictationEngine) = when (engine) {
        DictationEngine.DEVICE -> "Android"
        DictationEngine.HERMES -> "Hermes"
        DictationEngine.SPEECH_SERVER -> L10n.tr("Your speech server")
    }
    fun save(list: List<String>) = settings.set(AppSettings.DICTATION_CUSTOM_WORDS, DictationCustomWords.encode(list))
    SettingsSection(t("Dictation")) {
        ChoiceRow(t("Engine"), title(engine), DictationEngine.entries.map { it.raw to title(it) }) { settings.set(AppSettings.DICTATION_ENGINE, it) }
        Footnote(
            when (engine) {
                DictationEngine.DEVICE -> t("Android's speech recognition, on this phone when your language allows. Words appear as you speak.")
                DictationEngine.HERMES -> t("The speech to text your Hermes uses, such as Whisper. Audio goes to your Hermes. Words appear as you speak when Hermes streams them, otherwise when you stop.")
                DictationEngine.SPEECH_SERVER -> t("Your own speech server, reached through Hermes. Your voice stays off other services.")
            },
        )
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("Custom words"), fontWeight = FontWeight.SemiBold)
            Caption(
                if (engine == DictationEngine.DEVICE) t("Names and terms dictation should expect, written the way you want them: your company, people, products.")
                else t("Names and terms dictation should expect, written the way you want them. Hermes uses the words in its own speech to text settings (stt.prompt), not this list."),
            )
            words.forEach { word ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.weight(1f))
                    IconButton(onClick = {
                        draft = word
                        editing = word
                    }) { Icon(Icons.Default.Edit, t("Edit")) }
                    IconButton(onClick = {
                        if (editing == word) {
                            editing = null
                            draft = ""
                        }
                        save(words - word)
                    }) { Icon(Icons.Default.Delete, t("Delete")) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(DictationCustomWords.MAXIMUM_LENGTH) },
                    placeholder = { Text(t("Add a word or name")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = DictationCustomWords.canonicalized(draft).isNotEmpty() && (editing != null || words.size < DictationCustomWords.MAXIMUM_COUNT),
                    onClick = {
                        val word = DictationCustomWords.canonicalized(draft)
                        val current = editing
                        if (current != null) {
                            val index = words.indexOf(current)
                            if (index >= 0) save(words.toMutableList().also { it[index] = word })
                        } else {
                            save(words + word)
                        }
                        draft = ""
                        editing = null
                    },
                ) { Text(if (editing == null) t("Add") else t("Save")) }
            }
        }
    }
}

@Composable
private fun SpokenControlsSection(vm: VoiceSettingsViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val stop = prefs.resolvedSpokenStopPhrases
    val end = prefs.resolvedSpokenEndConversationPhrases
    SettingsSection(t("Spoken Controls"), footer = t("The built-in phrases are in the app language. Once you edit a list, it stays as you left it.")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption(t("Phrases you can say during a Voice conversation. A phrase matches only when it is the entire spoken utterance — the same words inside a longer sentence do nothing."))
            PhraseListEditor(t("Stop Phrases"), t("Cancel the current response and keep Voice open."), stop) { phrases ->
                vm.updatePreferences { it.copy(spokenStopPhrases = VoiceSpokenCommands.storedPhrases(phrases, VoiceSpokenCommandDefaults.stopPhrases)) }
            }
            PhraseListEditor(t("End Conversation Phrases"), t("Close the Voice conversation completely."), end) { phrases ->
                vm.updatePreferences { it.copy(spokenEndConversationPhrases = VoiceSpokenCommands.storedPhrases(phrases, VoiceSpokenCommandDefaults.endConversationPhrases)) }
            }
            if (prefs.spokenStopPhrases != null || prefs.spokenEndConversationPhrases != null) {
                TextButton(onClick = { vm.updatePreferences { it.copy(spokenStopPhrases = null, spokenEndConversationPhrases = null) } }) {
                    Text(t("Use the built-in phrases"))
                }
            }
        }
    }
}

/** View, add, edit and delete phrases (down to an empty list, which turns that command off). */
@Composable
internal fun PhraseListEditor(title: String, purpose: String, phrases: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    fun save(updated: List<String>) {
        onChange(VoiceSpokenCommands.canonicalizedPhraseList(updated))
        draft = ""
        editingIndex = null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Caption(purpose)
        phrases.forEachIndexed { index, phrase ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(phrase, Modifier.weight(1f))
                IconButton(onClick = {
                    draft = phrase
                    editingIndex = index
                }) { Icon(Icons.Default.Edit, t("Edit")) }
                IconButton(onClick = { save(phrases.toMutableList().also { it.removeAt(index) }) }) { Icon(Icons.Default.Delete, t("Delete")) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(t("Add phrase")) })
            Button(
                enabled = VoiceSpokenCommands.canonicalized(draft).isNotEmpty(),
                onClick = {
                    val trimmed = draft.trim()
                    val index = editingIndex
                    save(if (index != null && index in phrases.indices) phrases.toMutableList().also { it[index] = trimmed } else phrases + trimmed)
                },
            ) { Text(if (editingIndex == null) t("Add") else t("Save")) }
        }
    }
}

@Composable
private fun ProviderSection(vm: VoiceSettingsViewModel, kind: VoiceProviderKind, providers: List<VoiceProviderConfiguration>, transcription: VoiceTranscriptionMode) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val saving by vm.savingField.collectAsStateWithLifecycle()
    val stt = kind == VoiceProviderKind.STT
    val choices = providers.map { it.descriptor.id to it.descriptor.displayName }.let { hermes ->
        if (stt) listOf(VoiceSettingsViewModel.ON_DEVICE_PROVIDER_ID to L10n.tr("On this device")) + hermes +
            (VoiceSettingsViewModel.SPEECH_SERVER_PROVIDER_ID to L10n.tr("Your speech server"))
        else hermes
    }
    val selected = if (stt) when (transcription) {
        VoiceTranscriptionMode.ON_DEVICE -> VoiceSettingsViewModel.ON_DEVICE_PROVIDER_ID
        VoiceTranscriptionMode.SPEECH_SERVER -> VoiceSettingsViewModel.SPEECH_SERVER_PROVIDER_ID
        VoiceTranscriptionMode.HERMES -> snapshot.selectedSTTProvider
    } else snapshot.selectedTTSProvider
    SettingsSection(if (stt) t("Speech to text") else t("Assistant speech")) {
        if (choices.isEmpty()) {
            Footnote(if (stt) t("No transcription providers were discovered for this Hermes profile.") else t("No speech providers were discovered for this Hermes profile."))
            return@SettingsSection
        }
        ChoiceRow(t("Provider"), choices.firstOrNull { it.first == selected }?.second ?: selected, choices, enabled = saving == null) { vm.selectProvider(it, kind) }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                stt && transcription == VoiceTranscriptionMode.ON_DEVICE -> {
                    Caption(if (vm.onDeviceAvailable) t("Ready") else t("Unavailable"))
                    Caption(t("Uses Android's speech recognition. Captured audio stays on this phone when your language has an on-device model."))
                }
                stt && transcription == VoiceTranscriptionMode.SPEECH_SERVER -> SpeechServerSettings(vm)
                else -> (providers.firstOrNull { it.descriptor.id == selected } ?: providers.firstOrNull())?.let { ProviderDetail(vm, it) }
            }
            if (stt && transcription == VoiceTranscriptionMode.HERMES) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Show words while you speak"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = snapshot.liveTranscription, onCheckedChange = vm::saveLiveTranscription)
                }
                Caption(t("Hermes transcribes while you talk, so your words show up as you say them and are ready as soon as you stop. Works with OpenAI, xAI and ElevenLabs speech to text. This is a Hermes setting, so Hermes Desktop and the terminal use it too."))
            }
        }
    }
}

@Composable
private fun ProviderDetail(vm: VoiceSettingsViewModel, provider: VoiceProviderConfiguration) {
    val descriptor = provider.descriptor
    provider.readiness?.let { readiness ->
        Row {
            Text(t("Readiness"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(readiness.statusLabel, color = if (readiness.status.equals("ready", true)) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VoiceKeyGroup.groups(readiness.requiredCredentials).forEach { group -> ApiKeyRow(vm, group) }
    }
    if (descriptor.id == "openai" && descriptor.kind == VoiceProviderKind.TTS) {
        Caption(t("Using your own OpenAI-compatible speech server, such as Chatterbox? Set its address in Base URL below. If the server doesn't check keys, any placeholder key works."))
    }
    if (descriptor.id == "local" && descriptor.kind == VoiceProviderKind.STT) Caption(t("Runs on the Hermes host using its local Whisper installation—not on this phone."))
    if (descriptor.supportsStreaming) Caption(t("Streams speech as it is generated"))
    if (descriptor.models.isNotEmpty()) Caption(t("Suggested models: ") + descriptor.models.joinToString(", "))
    if (descriptor.voices.isNotEmpty()) Caption(t("Suggested voices: ") + descriptor.voices.joinToString(", "))
    provider.fields.forEach { field -> FieldEditor(vm, field) }
}

@Composable
private fun FieldEditor(vm: VoiceSettingsViewModel, field: VoiceTypedField) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val saving by vm.savingField.collectAsStateWithLifecycle()
    var value by remember(field.key, snapshot.values[field.key]) { mutableStateOf(snapshot.values[field.key] ?: field.defaultValue) }
    val validation = VoiceConfigurationParser.validationMessage(value, field.key)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val kind = field.kind) {
            is VoiceFieldKind.Choice -> ChoiceRow(field.label, value.replace('_', ' ').replaceFirstChar { it.uppercase() }, kind.options.map { it to it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }) {
                value = it
                vm.saveField(field.key, it)
            }
            else -> OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(field.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (kind == VoiceFieldKind.Decimal) KeyboardType.Decimal else KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        validation?.let { Text(it, color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.help, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (field.kind !is VoiceFieldKind.Choice) {
                TextButton(enabled = saving != field.key && validation == null, onClick = { vm.saveField(field.key, value) }) {
                    Text(if (saving == field.key) t("Saving…") else t("Save"))
                }
            }
        }
    }
}

/** One row per key Hermes reads (variables read in order kept together); the key itself is never shown. */
@Composable
private fun ApiKeyRow(vm: VoiceSettingsViewModel, group: VoiceKeyGroup) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    val loading by vm.service.isLoading.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val tick = snapshot
    val configured = vm.service.configuredCredential(group.keys)
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(group.keys.first(), style = MaterialTheme.typography.labelLarge)
        group.note?.let { Caption(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(
                when {
                    configured != null -> t("Key set on Hermes") + (configured.redactedValue?.let { ", $it" } ?: "")
                    loading -> t("Checking…")
                    snapshot.environmentLoaded -> t("No key yet")
                    else -> t("Hermes didn’t say whether a key is set")
                },
            )
            Spacer(Modifier.weight(1f))
            if (!editing) TextButton(onClick = { editing = true }) { Text(if (configured != null) t("Replace") else t("Add key")) }
        }
        if (editing) {
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(t("Paste API key")) }, visualTransformation = PasswordVisualTransformation())
            Row {
                TextButton(onClick = {
                    editing = false
                    draft = ""
                }) { Text(t("Cancel")) }
                Button(enabled = draft.isNotBlank() && !saving, onClick = {
                    saving = true
                    vm.saveCredential(configured?.key ?: group.keys.first(), draft) { ok ->
                        saving = false
                        if (ok) {
                            editing = false
                            draft = ""
                        }
                    }
                }) { Text(if (configured == null) t("Add key") else t("Save key")) }
            }
        }
    }
}

@Composable
private fun CredentialsSection(vm: VoiceSettingsViewModel, transcription: VoiceTranscriptionMode) {
    val snapshot by vm.service.snapshot.collectAsStateWithLifecycle()
    fun keysOf(providers: List<VoiceProviderConfiguration>, selected: String): List<String> {
        val provider = providers.firstOrNull { it.descriptor.id == selected } ?: providers.firstOrNull()
        return VoiceKeyGroup.groups(provider?.readiness?.requiredCredentials.orEmpty()).flatMap { it.keys }
    }
    val shown = keysOf(snapshot.ttsProviders, snapshot.selectedTTSProvider).toMutableSet()
    if (transcription == VoiceTranscriptionMode.HERMES) shown += keysOf(snapshot.sttProviders, snapshot.selectedSTTProvider)
    val others = snapshot.credentials.filter { it.key !in shown }
    if (snapshot.credentials.isNotEmpty() && others.isEmpty()) return
    SettingsSection(t("Credentials on Hermes")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Caption(t("Keys stay on your Hermes host. Conduit only shows the masked key Hermes reports, never the whole key."))
            if (snapshot.credentials.isEmpty()) Caption(t("Hermes didn’t report any voice keys for this profile."))
            VoiceKeyGroup.groups(others).forEach { ApiKeyRow(vm, it) }
        }
    }
}

/** Speech-server (self-hosted STT) config: `voice.speech_server.url` and `.api_key`, with the host's status and test. */
@Composable
private fun ColumnScope.SpeechServerSettings(vm: VoiceSettingsViewModel) {
    val status by vm.service.speechServer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by remember(status?.url) { mutableStateOf(status?.url.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<app.hermesconduit.android.model.SpeechServerTestResult?>(null) }
    LaunchedEffect(vm.profile) { vm.service.speechServerStatus() }
    Caption(t("Your voice is recognized by a server you run, such as Hugging Face speech-to-speech or the one Hermes Live Voice starts. Hermes connects to it for Conduit, so it can run on another machine on your network."))
    val current = status
    if (current?.pluginMissing == true) {
        Text(app.hermesconduit.android.model.SpeechServerStatus.pluginMissingReason, color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall)
        return
    }
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Server address")) }, placeholder = { Text("ws://192.168.1.20:8765/v1/realtime") })
    Caption(t("Plain ws:// works for home network and Tailscale addresses. A server on the internet needs wss://."))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("Key"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Caption(if (current?.hasKey == true) t("Set") else t("None"))
    }
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(t("Optional, for a server behind a login")) }, visualTransformation = PasswordVisualTransformation())
    val hasChanges = url.trim() != (current?.url ?: "") || key.isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !busy && hasChanges, onClick = {
            busy = true
            result = null
            scope.launch {
                var ok = true
                if (url.trim() != (current?.url ?: "")) ok = vm.service.save(url.trim(), VoiceSettingsViewModel.SPEECH_SERVER_URL_KEY)
                if (ok && key.isNotBlank()) {
                    ok = vm.service.save(key.trim(), VoiceSettingsViewModel.SPEECH_SERVER_API_KEY)
                    if (ok) key = ""
                }
                val refreshed = vm.service.speechServerStatus()
                if (ok && refreshed.available) result = vm.service.testSpeechServer()
                busy = false
            }
        }) { Text(t("Save")) }
        OutlinedButton(enabled = !testing && !busy && current?.configured == true, onClick = {
            testing = true
            scope.launch {
                result = vm.service.testSpeechServer()
                testing = false
            }
        }) {
            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(t("Test connection"))
        }
        if (current?.hasKey == true) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    vm.service.save("", VoiceSettingsViewModel.SPEECH_SERVER_API_KEY)
                    vm.service.speechServerStatus()
                    busy = false
                }
            }) { Text(t("Remove key"), color = MaterialTheme.colorScheme.error) }
        }
    }
    val shownResult = result
    when {
        shownResult != null -> Text(shownResult.message, style = MaterialTheme.typography.bodySmall, color = if (shownResult.connected) Color(0xFF34C759) else Color(0xFFFF9500))
        current != null && current.configured && !current.available && current.reason != null ->
            Text(current.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
    }
}

@Suppress("unused")
private fun openAppSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}
