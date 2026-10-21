package app.hermesconduit.android.ui.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PluginStatus
import app.hermesconduit.android.ui.plugin.SetupCommand
import kotlinx.coroutines.launch

/** The "Check …" button's title for `mode`, which the setup guide sends the user back to. */
private fun checkTitle(mode: LiveVoiceMode): String = when (mode) {
    LiveVoiceMode.GEMINI_LIVE -> L10n.tr("Check Gemini Live")
    LiveVoiceMode.GPT_LIVE -> L10n.tr("Check GPT-Live")
    LiveVoiceMode.GROK_LIVE -> L10n.tr("Check Grok Live")
}

/**
 * "Requires Hermes notifier · Setup" under a live mode in Voice settings
 * (the iOS `LiveVoiceSetupLink`): opens the setup guide.
 */
@Composable
internal fun LiveVoiceSetupLink(mode: LiveVoiceMode) {
    var showing by remember { mutableStateOf(false) }
    val title = when (mode) {
        LiveVoiceMode.GEMINI_LIVE -> t("Set up Gemini Live")
        LiveVoiceMode.GPT_LIVE -> t("Set up GPT-Live")
        LiveVoiceMode.GROK_LIVE -> t("Set up Grok Live")
    }
    val hint = t("Opens live voice setup instructions")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = hint) { showing = true }
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(t("Requires Hermes notifier ·"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t("Setup"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    if (showing) LiveVoiceSetupSheet(mode, onDismiss = { showing = false })
}

/** How to get a live voice mode working on the Hermes server (the iOS `LiveVoiceSetupSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveVoiceSetupSheet(mode: LiveVoiceMode, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Live voice setup"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }) { Text(t("Done")) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                t("Install the notifier if it is missing, or update your existing installation. Both commands target the Hermes gateway used by this profile."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SetupCommand(1, t("Install the notifier"), "hermes plugins install ${PluginStatus.REPOSITORY} --enable")
            SetupCommand(1, t("Update the notifier"), "hermes plugins update conduit_push")
            when (mode) {
                LiveVoiceMode.GEMINI_LIVE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("2. " + t("Add a Gemini API key"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Detail(t("Add it in Voice settings under API key, or set GEMINI_API_KEY in the active Hermes profile’s .env file. The key stays on your Hermes server."))
                }
                LiveVoiceMode.GPT_LIVE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SetupCommand(2, t("Sign in to OpenAI Codex"), "hermes auth")
                    Detail(t("Choose OpenAI Codex when prompted. The sign-in stays on your Hermes server."))
                }
                LiveVoiceMode.GROK_LIVE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SetupCommand(2, t("Sign in to SuperGrok"), "hermes auth add xai-oauth")
                    Detail(t("Sign in with your SuperGrok or X Premium+ account, or add an xAI key in Voice settings under API key instead. The sign-in and the key stay on your Hermes server."))
                }
            }
            SetupCommand(3, t("Restart the gateway"), "hermes gateway restart")
            val repositoryHint = t("Opens the notifier project on GitHub")
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(onClickLabel = repositoryHint) { uri.openUri(REPOSITORY_URL) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(t("Hermes notifier repository"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    t("Return to Voice settings, enable this mode if it is off, and tap %s to check availability.", checkTitle(mode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val REPOSITORY_URL = "https://github.com/kaishi00/hermes-conduit-notifier"
