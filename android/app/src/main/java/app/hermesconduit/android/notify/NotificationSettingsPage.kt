package app.hermesconduit.android.notify

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle

@Composable
fun NotificationSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.appSettings
    val enabled by settings.bool(NotificationPrefs.ENABLED, false).collectAsStateWithLifecycle()
    val background by settings.bool(AppSettings.BACKGROUND_CONNECTION, true).collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(ConduitNotifications.canPost(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (granted) {
            settings.set(NotificationPrefs.ENABLED, true)
            ConnectionService.sync(context)
        }
    }
    fun enable() {
        if (Build.VERSION.SDK_INT >= 33 && !ConduitNotifications.canPost(context)) request.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { settings.set(NotificationPrefs.ENABLED, true); ConnectionService.sync(context) }
    }

    ScreenScaffold(t("Notifications"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("This device")) {
                SettingsRow(t("Status"), value = when {
                    enabled && permitted -> t("On")
                    enabled -> t("Blocked in system settings")
                    else -> t("Off")
                })
                Button(
                    onClick = {
                        if (enabled) { settings.set(NotificationPrefs.ENABLED, false); ConnectionService.sync(context) } else enable()
                    },
                    modifier = Modifier.padding(16.dp),
                ) { Text(if (enabled) t("Turn off notifications") else t("Enable notifications")) }
                if (enabled && !permitted) {
                    SettingsRow(t("Open system settings"), onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
                }
            }
            SettingsSection(
                t("Background"),
                footer = t("Keeps Conduit connected to Hermes while it's in the background so notifications arrive right away. Android shows a quiet ongoing notification while it's on."),
            ) {
                SettingsToggle(t("Stay connected in the background"), background, {
                    settings.set(AppSettings.BACKGROUND_CONNECTION, it)
                    ConnectionService.sync(context)
                }, icon = Icons.Default.NotificationsActive)
            }
            val progress by settings.bool(NotificationPrefs.PROGRESS, true).collectAsStateWithLifecycle()
            SettingsSection(t("Lock Screen")) {
                SettingsToggle(
                    t("Show progress on the Lock Screen"), progress, { settings.set(NotificationPrefs.PROGRESS, it) },
                    subtitle = t("While Hermes works on a message you sent: what it's doing, how long it has run, its tools and context. Keeps updating in the background when notifications are on."),
                )
            }
            SettingsSection(t("Notify me when")) {
                listOf(
                    Triple(NotificationPrefs.APPROVAL, "Approval needed", "A tool is waiting for approval"),
                    Triple(NotificationPrefs.INPUT, "Input needed", "Hermes needs your answer"),
                    Triple(NotificationPrefs.RESPONSE, "Response ready", "An active turn finishes"),
                    Triple(NotificationPrefs.FAILED, "Turn failed", "A turn stops with an error"),
                    Triple(NotificationPrefs.BACKGROUND_TASK, "Background task finished", "A delegated agent completes"),
                    Triple(NotificationPrefs.ATTENTION_SOUND, "Approval & input sound", "Play a sound when Hermes is waiting on you"),
                    Triple(NotificationPrefs.COMPLETION_SOUND, "Completion sound", "Play a sound when a turn or task finishes"),
                    Triple(NotificationPrefs.PREVIEWS, "Show previews", "Include response text in notifications"),
                    Triple(NotificationPrefs.DECISION_CARDS, "Approval cards in pushes", "Include approval details so cards work from notifications. Disable for maximum privacy."),
                ).forEach { (key, title, detail) ->
                    val value by settings.bool(key, NotificationPrefs.defaults[key] ?: true).collectAsStateWithLifecycle()
                    SettingsToggle(t(title), value, { settings.set(key, it) }, subtitle = t(detail))
                }
            }
            SettingsSection(t("Replies to messages from"), footer = t("Replies in chats you started here always notify.")) {
                listOf("discord" to "Discord", "telegram" to "Telegram", "api" to "API", "webhook" to t("Webhook"), "cron" to t("Cron"), "other" to t("Other")).forEach { (kind, label) ->
                    val key = NotificationPrefs.replySource(kind)
                    val value by settings.bool(key, true).collectAsStateWithLifecycle()
                    SettingsToggle(label, value, { settings.set(key, it) })
                }
            }
        }
    }
}
