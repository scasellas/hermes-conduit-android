package app.hermesconduit.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.OpenInFull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import androidx.core.content.FileProvider
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.Attachment
import java.io.File

@Composable
fun Composer(chat: ChatState, enabled: Boolean, actions: ChatActions, slashCommands: List<app.hermesconduit.android.model.SlashCommand> = emptyList()) {
    val context = LocalContext.current
    val drafts = remember { ComposerDrafts(context) }
    var text by rememberSaveable(chat.sessionId) { mutableStateOf(drafts.load(chat.sessionId)) }
    androidx.compose.runtime.LaunchedEffect(chat.prefill) {
        chat.prefill?.let {
            text = if (text.isBlank()) it else "$text\n$it"
            actions.consumePrefill()
        }
    }
    androidx.compose.runtime.LaunchedEffect(text, chat.sessionId) { drafts.save(chat.sessionId, text) }
    val sendOnEnter by context.appSettings.bool(app.hermesconduit.android.data.AppSettings.SEND_ON_ENTER, false).collectAsStateWithLifecycle()
    var fullEditor by remember { mutableStateOf(false) }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var menu by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    fun add(uri: Uri?) {
        if (uri == null) return
        try {
            attachments += Attachment.read(context, uri)
            attachError = null
        } catch (e: Exception) {
            attachError = e.message
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> uris.forEach(::add) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(::add) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) add(cameraUri) }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        attachError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val suggestions = SlashCommands.suggestions(text, slashCommands)
        if (suggestions.isNotEmpty()) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 240.dp),
            ) {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(suggestions.size) { index ->
                        val c = suggestions[index]
                        Column(
                            Modifier.fillMaxWidth().clickable { text = "/${c.name} " }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("/${c.name}" + (c.argsHint?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                            Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (suggestions.isEmpty()) {
            val mentions = remember(text) {
                app.hermesconduit.android.model.MentionAutocomplete.activeQuery(text)
                    ?.let { q -> app.hermesconduit.android.model.MentionAutocomplete.filter(actions.mentionCandidates(), q) }
                    .orEmpty()
            }
            MentionSuggestionList(mentions) { text = app.hermesconduit.android.model.MentionAutocomplete.completing(text, it) }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment -> AttachmentChip(attachment) { attachments.remove(attachment) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, t("Attach")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(t("Camera")) },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                        onClick = {
                            menu = false
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraUri = uri
                            takePicture.launch(uri)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(t("Photo Library")) },
                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                        onClick = {
                            menu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(t("Document")) },
                        leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                        onClick = {
                            menu = false
                            pickFile.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            val submit = {
                if (attachments.isEmpty() && actions.runSlash(text)) {
                    text = ""
                } else {
                    actions.send(text, attachments.toList())
                    text = ""
                    attachments.clear()
                }
            }
            TextField(
                value = text,
                onValueChange = { new ->
                    // Return-key send for hardware keyboards (Settings > Chat).
                    if (sendOnEnter && new.endsWith("\n") && !text.endsWith("\n") && new.length == text.length + 1 && new.isNotBlank()) {
                        text = new.dropLast(1)
                        if (enabled) submit()
                    } else {
                        text = new
                    }
                },
                trailingIcon = if (text.lines().size > 3) ({
                    IconButton(onClick = { fullEditor = true }) { Icon(Icons.Default.OpenInFull, t("Expand")) }
                }) else null,
                placeholder = { Text(if (chat.running) t("Steer the running turn…") else t("Message Hermes")) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 200.dp),
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            val canSend = text.isNotBlank() || attachments.isNotEmpty()
            when {
                canSend -> FilledIconButton(
                    onClick = submit,
                    enabled = enabled,
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, t("Send")) }
                chat.running -> FilledIconButton(onClick = actions::stop, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Stop, t("Stop")) }
                else -> FilledIconButton(onClick = {}, enabled = false, modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, t("Send")) }
            }
        }
    }

    if (fullEditor) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullEditor = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxWidth().fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Message"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { fullEditor = false }) { Text(t("Done")) }
                    }
                    TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Per-chat drafts, like the iOS ComposerDraftStore. */
class ComposerDrafts(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("composer_drafts", android.content.Context.MODE_PRIVATE)
    private fun key(sessionId: String?) = sessionId ?: "__new__"
    fun load(sessionId: String?): String = prefs.getString(key(sessionId), "").orEmpty()
    fun save(sessionId: String?, text: String) {
        prefs.edit().apply { if (text.isBlank()) remove(key(sessionId)) else putString(key(sessionId), text) }.apply()
    }
}

/** The @mention picker shown above a composer while a trailing `@query` is typed. */
@Composable
internal fun MentionSuggestionList(
    candidates: List<app.hermesconduit.android.model.MentionAutocomplete.Candidate>,
    onSelected: (app.hermesconduit.android.model.MentionAutocomplete.Candidate) -> Unit,
) {
    if (candidates.isEmpty()) return
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 240.dp),
    ) {
        androidx.compose.foundation.lazy.LazyColumn {
            items(candidates.size) { index ->
                val c = candidates[index]
                Row(
                    Modifier.fillMaxWidth().clickable { onSelected(c) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${c.tag}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val thumbnail = remember(attachment.id) {
        if (attachment.isImage) runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size, options)?.asImageBitmap()
        }.getOrNull() else null
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(thumbnail, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Description, null, Modifier.size(24.dp))
        }
        Text(
            attachment.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp).padding(start = 6.dp),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, t("Remove"), Modifier.size(16.dp)) }
    }
}
