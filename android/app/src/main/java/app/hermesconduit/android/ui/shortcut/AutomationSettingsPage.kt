package app.hermesconduit.android.ui.shortcut

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle

/** Settings > Automation: Send to Hermes and Talk to Conduit for automation apps (the iOS Shortcuts actions). */
@Composable
fun AutomationSettingsPage(onBack: () -> Unit) {
    val settings = LocalContext.current.appSettings
    val enabled by settings.bool(SendToHermes.ENABLED_KEY, false).collectAsStateWithLifecycle()
    ScreenScaffold(t("Automation"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(
                t("Send to Hermes"),
                footer = t("Automation apps like Tasker, MacroDroid and Automate can send text to Hermes as a chat message, without Conduit opening. With Wait for Answer on, Hermes's reply comes back to the automation."),
            ) {
                SettingsToggle(t("Allow other apps to send to Hermes"), enabled, { settings.set(SendToHermes.ENABLED_KEY, it) })
            }
            SettingsSection(
                t("Intent"),
                footer = t("Start it for a result to get the reply back: RESULT_OK with the extras reply and outcome (answered, sent, stillWorking, needsInput, queued or joinedRunningReply), or RESULT_CANCELED with error. Started without a result, the outcome shows as a notification. Hermes gets about 25 seconds; a longer answer is left in the chat."),
            ) {
                Code(SendToHermes.ACTION)
                SettingsRow(SendToHermes.EXTRA_TEXT, t("The message. android.intent.extra.TEXT works too."))
                SettingsRow(SendToHermes.EXTRA_WAIT_FOR_ANSWER, t("Wait for Answer: true unless set to false."))
                SettingsRow(SendToHermes.EXTRA_CHAT_NAME, t("Chat Name: every run with this name goes to the same chat. Without it, each run starts a new chat."))
                SettingsRow(SendToHermes.EXTRA_PROFILE, t("Profile: the profile the chat lives in. Without it, the profile in use."))
            }
            SettingsSection(
                t("Broadcast"),
                footer = t("Send the same action as a broadcast when your automation app can't start an activity. Add reply_action and reply_package to get the outcome back as that broadcast, with the same extras."),
            ) {}
            SettingsSection(
                t("Talk to Conduit"),
                footer = t("To start a voice conversation, open the link conduit://voice, or use Conduit's Voice app shortcut."),
            ) {
                Code("conduit://voice")
            }
        }
    }
}

@Composable
private fun Code(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
