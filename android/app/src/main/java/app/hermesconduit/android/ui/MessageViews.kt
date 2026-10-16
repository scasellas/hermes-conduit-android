package app.hermesconduit.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.InputPrompt
import app.hermesconduit.android.model.MessageReaction
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.ui.markdown.RichMarkdown
import app.hermesconduit.android.ui.theme.attentionColor
import java.text.DateFormat
import java.util.Date

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Conduit", text))
    Toast.makeText(context, L10n.tr("Copied"), Toast.LENGTH_SHORT).show()
}

/** Optional hook so voice features can add "Read aloud" without touching this file. */
object MessageActionHooks {
    var readAloud: ((Context, ChatMessage) -> Unit)? = null
    /** A full Read Aloud control (speaker / stop / resume), when the voice feature installs one. */
    var readAloudButton: (@Composable (ChatMessage) -> Unit)? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(message: ChatMessage, actions: ChatActions, canAct: Boolean = true) {
    val context = LocalContext.current
    val scale by context.appSettings.float(AppSettings.CHAT_FONT_SCALE, 1f).collectAsStateWithLifecycle()
    val showReasoning by context.appSettings.bool(AppSettings.SHOW_REASONING, true).collectAsStateWithLifecycle()
    val showTools by context.appSettings.bool(AppSettings.SHOW_TOOL_CALLS, true).collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var reacting by remember { mutableStateOf(false) }

    when (message.role) {
        MessageRole.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (message.isSteer) {
                Text(t("Steer"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                Surface(
                    color = if (message.isSteer) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .combinedClickable(onClick = {}, onLongClick = { menu = true }),
                ) {
                    Text(
                        message.content,
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = (16 * scale).sp,
                        fontStyle = if (message.isSteer) FontStyle.Italic else FontStyle.Normal,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(t("Copy")) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                        menu = false
                        copyToClipboard(context, message.content)
                    })
                    DropdownMenuItem(text = { Text(t("React")) }, leadingIcon = { Icon(Icons.Default.AddReaction, null) }, onClick = {
                        menu = false
                        reacting = true
                    })
                }
            }
            Reactions(message, onTap = { actions.react(message, it) })
            message.timestampMs?.let { Timestamp(it) }
        }
        MessageRole.SYSTEM -> SystemRow(message)
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showReasoning) message.reasoning?.takeIf { it.isNotBlank() }?.let { Reasoning(it, live = message.streaming && message.content.isEmpty()) }
            message.toolCalls.forEach { call ->
                // An answered clarify reads back as its question card, not the bare tool call.
                val clarify = remember(call) { app.hermesconduit.android.model.ClarifyHistoryRecord.from(call, app.hermesconduit.android.core.L10n::tr) }
                if (clarify != null) ClarifyHistoryCard(clarify) else if (showTools) ToolCallCard(call)
            }
            if (message.content.isNotBlank()) {
                RichMarkdown(message.content, fontScale = scale)
            } else if (message.streaming && message.toolCalls.none { it.running } && message.reasoning.isNullOrBlank()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Reactions(message, onTap = { actions.react(message, it) })
            if (!message.streaming && message.content.isNotBlank() && canAct) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Default.ContentCopy, t("Copy")) { copyToClipboard(context, message.content) }
                    MessageActionHooks.readAloudButton?.invoke(message) ?: MessageActionHooks.readAloud?.let { read ->
                        ActionIcon(Icons.AutoMirrored.Filled.VolumeUp, t("Read Aloud")) { read(context, message) }
                    }
                    ActionIcon(Icons.Default.FormatQuote, t("Quote")) { actions.quote(message) }
                    ActionIcon(Icons.AutoMirrored.Filled.CallSplit, t("Branch")) { actions.branchFrom(message) }
                    ActionIcon(Icons.Default.AddReaction, t("React")) { reacting = true }
                    Spacer(Modifier.weight(1f))
                    message.timestampMs?.let { Timestamp(it) }
                }
            }
        }
    }

    if (reacting) {
        TapbackPicker(current = message.reactions.firstOrNull { it.isFromUser }?.emoji, onPick = {
            reacting = false
            actions.react(message, it)
        }, onDismiss = { reacting = false })
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Timestamp(ms: Long) {
    Text(
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reactions(message: ChatMessage, onTap: (String) -> Unit) {
    if (message.reactions.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        message.reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
            val mine = list.any { it.isFromUser }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTap(emoji) },
            ) {
                Text(if (list.size > 1) "$emoji ${list.size}" else emoji, Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun TapbackPicker(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("React")) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MessageReaction.tapbacks.forEach { emoji ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (emoji == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onPick(emoji) },
                    ) { Text(emoji, Modifier.padding(8.dp), fontSize = 24.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}

@Composable
private fun SystemRow(message: ChatMessage) {
    when (message.kind) {
        "model_change", "review" -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(if (message.kind == "review") Icons.Default.FactCheck else Icons.Default.SwapHoriz, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    if (message.kind == "review") Text(t("Review"), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        "slash" -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Box(Modifier.padding(12.dp)) { RichMarkdown(message.content, fontScale = 0.9f) } }
        else -> Text(
            message.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
    }
}

/** sudo / secret / vault-unlock request with a masked field: Send gives Hermes the value, Skip lets it go on without. */
@Composable
fun InputPromptCard(prompt: InputPrompt, onRespond: (String) -> Unit) {
    var value by remember(prompt.requestId) { mutableStateOf("") }
    val title = when (prompt.kind) {
        "sudo" -> t("Hermes needs your sudo password")
        "secret" -> prompt.prompt.ifEmpty { t("Hermes needs a secret value") }
        else -> if (prompt.displayName.isEmpty()) t("Unlock your password manager") else t("Unlock %s", prompt.displayName)
    }
    val label = when (prompt.kind) {
        "sudo" -> t("sudo password")
        "secret" -> t("Secret value")
        else -> t("Master password")
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (prompt.kind == "secret") Icons.Default.Key else Icons.Default.Lock, null, tint = attentionColor(), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("INPUT NEEDED"), style = MaterialTheme.typography.labelSmall, color = attentionColor())
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (prompt.command.isNotEmpty()) CodeBlock(t("Command"), prompt.command)
            if (prompt.envVar.isNotEmpty()) Text(prompt.envVar, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRespond(value); value = "" }, enabled = value.isNotEmpty()) { Text(t("Send")) }
                TextButton(onClick = { onRespond("") }) { Text(t("Skip")) }
            }
        }
    }
}

