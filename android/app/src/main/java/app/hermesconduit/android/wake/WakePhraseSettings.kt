package app.hermesconduit.android.wake

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.VoiceSpokenCommands
import app.hermesconduit.android.model.WakePhraseMatcher
import app.hermesconduit.android.model.WakeProfilePreferences
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.voice.PhraseListEditor
import kotlinx.coroutines.launch

/** Wake phrase for this profile (iOS WakePhraseSettingsSection). Device-local: stored per dashboard and profile on this phone. */
@Composable
fun WakePhraseSettingsSection(profile: String) {
    val context = LocalContext.current
    val wake = remember { WakePhrase.get(context).also { it.start() } }
    val configuration by wake.configuration.collectAsStateWithLifecycle()
    val failure by wake.failure.collectAsStateWithLifecycle()
    val paused by wake.pausedForOtherAudio.collectAsStateWithLifecycle()
    val engine by wake.engine.collectAsStateWithLifecycle()
    val engineNote by wake.engineNote.collectAsStateWithLifecycle()
    val model by wake.modelStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (wake.gatewayID == null) return
    val prefs = remember(configuration, profile) { wake.preferences(profile) }
    val phrases = prefs.enabledPhrases
    var permissionDenied by remember { mutableStateOf(false) }
    val suggested = wake.suggestedPhrase(profile)

    fun save(updated: List<String>, startsFresh: Boolean = prefs.startsFreshConversation) =
        wake.setPreferences(WakeProfilePreferences(VoiceSpokenCommands.canonicalizedPhraseList(updated), startsFresh), profile)

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) save(listOf(suggested))
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    SettingsSection(t("Wake phrase")) {
        SettingsToggle(t("Listen for a wake phrase"), phrases.isNotEmpty(), { enabled ->
            if (!enabled) save(emptyList())
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) save(listOf(suggested))
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(t("Say the phrase while Conduit is open to start voice on this profile. Listening runs on this phone, stops during calls, and the microphone indicator stays on while it listens."))
            if (permissionDenied) Warning(t("Allow the microphone for Conduit in Settings to use a wake phrase."))
            if (phrases.isNotEmpty()) {
                PhraseListEditor(t("Wake phrases"), t("Use at least two words, such as \"%s\". Add a second spelling if Conduit mishears a name.", suggested), phrases) { save(it) }
                if (phrases.any { !WakePhraseMatcher.isUsable(it) }) Warning(t("Phrases with a single word are ignored."))
            }
        }
        if (phrases.isNotEmpty()) {
            SettingsToggle(t("Start a new conversation"), prefs.startsFreshConversation, { save(phrases, it) })
            SettingsToggle(t("Listen while other audio plays"), configuration.listensOverOtherAudio, { wake.setListensOverOtherAudio(it) })
            Footnote(t("Off: wake pauses while another app plays music or a podcast, and listens again once it stops. On: wake keeps listening while it plays."))
            if (paused) Footnote(t("Paused while another app plays audio. Wake listens again when it stops."))

            // The engine: the on-device keyword model once downloaded, else the phone's speech recognizer.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("Wake model"), style = MaterialTheme.typography.titleSmall)
                when (val state = model) {
                    WakeModelStore.State.Installed -> {
                        Caption(
                            if (engine == WakePhrase.Engine.KEYWORD_SPOTTER) t("Listening with the on-device keyword model (%s).", wake.modelStore.descriptor.displayName)
                            else t("Listening with this phone's speech recognizer."),
                        )
                        engineNote?.let { Warning(it) }
                        TextButton(onClick = { wake.modelStore.remove() }) { Text(t("Remove the wake model")) }
                    }
                    is WakeModelStore.State.Downloading -> {
                        Caption(t("Downloading the wake model…"))
                        if (state.fraction != null) LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Caption(t("Listening with this phone's speech recognizer, while Conduit is open. Download the on-device keyword model (about 33 MB) for faster, more private listening that can also keep going in the background."))
                        (state as? WakeModelStore.State.Failed)?.let { Warning(it.message) }
                        OutlinedButton(onClick = { scope.launch { wake.modelStore.download() } }) { Text(t("Download the wake model")) }
                    }
                }
            }
            if (engine == WakePhrase.Engine.KEYWORD_SPOTTER) {
                SettingsToggle(t("Keep listening in the background"), configuration.listensInBackground, { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    wake.setListensInBackground(on)
                })
                Footnote(t("Wake keeps listening with Conduit in the background or the phone locked, and a notification shows while it does. Uses more battery."))
            }
            failure?.let { Column(Modifier.padding(horizontal = 16.dp)) { Warning(it) } }
        }
        Footnote(t("Android's Assistant shortcut remains the supported way to begin a voice conversation from the lock screen."))
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Warning(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

@Composable
private fun Footnote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
