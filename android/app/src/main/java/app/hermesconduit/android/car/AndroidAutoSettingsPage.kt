package app.hermesconduit.android.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle

/**
 * Settings > Android Auto (iOS Settings > Voice > CarPlay): whether the car
 * opens on the chat list, the shortcuts the car's Shortcuts grid offers, and
 * the voice screen's status sounds. Saved on this device.
 */
@Composable
fun AndroidAutoSettingsPage(onBack: () -> Unit) {
    val settings = LocalContext.current.appSettings
    val choosesChatFirst by settings.bool(CarPreferences.CHOOSE_CHAT_FIRST_KEY, CarPreferences.CHOOSE_CHAT_FIRST_DEFAULT).collectAsStateWithLifecycle()
    val playsSounds by settings.bool(CarPreferences.SOUNDS_KEY, CarPreferences.SOUNDS_DEFAULT).collectAsStateWithLifecycle()
    val stored by settings.string(CarPreferences.SHORTCUTS_KEY, "").collectAsStateWithLifecycle()
    val shortcuts = remember(stored) { CarShortcuts.decode(stored) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftPrompt by remember { mutableStateOf("") }

    fun clearDraft() {
        editingId = null
        draftTitle = ""
        draftPrompt = ""
    }

    fun commitDraft() {
        val shortcut = CarShortcut(id = editingId ?: java.util.UUID.randomUUID().toString(), title = draftTitle, prompt = draftPrompt)
        if (CarPreferences.save(settings, shortcut)) clearDraft()
    }

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
            SettingsSection(
                t("Shortcuts"),
                footer = t("One tap in the car starts the prompt as a Voice Job and opens a conversation, which speaks the result when the job is done."),
            ) {
                if (shortcuts.isEmpty()) SettingsRow(t("No shortcuts yet."))
                for (shortcut in shortcuts) {
                    SettingsRow(shortcut.title, subtitle = shortcut.prompt, icon = Icons.Default.Bolt) {
                        Row {
                            IconButton(onClick = {
                                editingId = shortcut.id
                                draftTitle = shortcut.title
                                draftPrompt = shortcut.prompt
                            }) { Icon(Icons.Default.Edit, t("Edit %s", shortcut.title)) }
                            IconButton(onClick = {
                                CarPreferences.delete(settings, shortcut.id)
                                if (editingId == shortcut.id) clearDraft()
                            }) { Icon(Icons.Default.Delete, t("Delete %s", shortcut.title)) }
                        }
                    }
                }
                if (editingId != null || CarShortcuts.canAdd(shortcuts)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            label = { Text(t("Shortcut name")) },
                            placeholder = { Text(t("Name, like Morning brief")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draftPrompt,
                            onValueChange = { draftPrompt = it },
                            label = { Text(t("Shortcut prompt")) },
                            placeholder = { Text(t("What to ask Hermes")) },
                            minLines = 1,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            if (editingId != null) TextButton(onClick = ::clearDraft) { Text(t("Cancel")) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = ::commitDraft, enabled = draftTitle.isNotBlank() && draftPrompt.isNotBlank()) {
                                Text(if (editingId == null) t("Add") else t("Save"))
                            }
                        }
                    }
                } else {
                    Text(
                        t("Android Auto shows up to 8 shortcuts."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
