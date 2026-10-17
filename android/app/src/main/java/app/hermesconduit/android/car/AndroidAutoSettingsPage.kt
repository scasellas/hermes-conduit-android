package app.hermesconduit.android.car

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle

/**
 * Settings > Android Auto (iOS Settings > Voice > CarPlay): whether the car
 * opens on the chat list, and the voice screen's status sounds. Saved on this
 * device.
 */
@Composable
fun AndroidAutoSettingsPage(onBack: () -> Unit) {
    val settings = LocalContext.current.appSettings
    val choosesChatFirst by settings.bool(CarPreferences.CHOOSE_CHAT_FIRST_KEY, CarPreferences.CHOOSE_CHAT_FIRST_DEFAULT).collectAsStateWithLifecycle()
    val playsSounds by settings.bool(CarPreferences.SOUNDS_KEY, CarPreferences.SOUNDS_DEFAULT).collectAsStateWithLifecycle()

    ScreenScaffold(t("Android Auto"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Android Auto")) {
                SettingsToggle(
                    t("Choose a chat first"), choosesChatFirst, { settings.set(CarPreferences.CHOOSE_CHAT_FIRST_KEY, it) },
                    subtitle = t("Android Auto opens on your pinned and recent chats, with New voice chat at the top. Off: Voice starts as soon as Android Auto opens."),
                    icon = Icons.Default.FormatListBulleted,
                )
                SettingsToggle(
                    t("Status sounds"), playsSounds, { settings.set(CarPreferences.SOUNDS_KEY, it) },
                    subtitle = t("A soft tone when your turn is sent and a low one if Voice fails, in the classic voice mode."),
                    icon = Icons.Default.MusicNote,
                )
            }
        }
    }
}
