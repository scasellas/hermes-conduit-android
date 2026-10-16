package app.hermesconduit.android.assist

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection

@Composable
fun ScreenQuestionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val isAssistant = android.os.Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
    ScreenScaffold(t("Ask Hermes About Screen"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(
                t("How it works"),
                footer = t("Long-press the Home button or the power button in any app and Conduit opens with a screenshot of it, ready for your question."),
            ) {}
            SettingsSection(
                t("Setup"),
                footer = if (isAssistant) t("Conduit is your digital assistant app.") else t("Choose Conduit as the digital assistant app, and turn on “Use screenshot”."),
            ) {
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }, modifier = Modifier.padding(16.dp)) { Text(t("Open assistant settings")) }
            }
            SettingsSection(t("Other ways to run it"), footer = t("Take a screenshot, tap Share, and pick Conduit.")) {}
        }
    }
}
