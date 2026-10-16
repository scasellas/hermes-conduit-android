package app.hermesconduit.android.ui.kanban

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.KanbanTint

@Composable
fun kanbanTint(tint: KanbanTint): Color = when (tint) {
    KanbanTint.SECONDARY -> MaterialTheme.colorScheme.onSurfaceVariant
    KanbanTint.PURPLE -> Color(0xFF9C6ADE)
    KanbanTint.BLUE -> Color(0xFF3D8BFD)
    KanbanTint.GREEN -> Color(0xFF34A853)
    KanbanTint.RED -> MaterialTheme.colorScheme.error
    KanbanTint.ORANGE -> Color(0xFFF29900)
}

val KanbanOrange = Color(0xFFF29900)
val KanbanGreen = Color(0xFF34A853)

fun kanbanStatusIcon(status: String): ImageVector = when (status) {
    "triage" -> Icons.Default.Inbox
    "todo" -> Icons.Default.RadioButtonUnchecked
    "scheduled" -> Icons.Default.Schedule
    "ready" -> Icons.Default.PlayCircle
    "running" -> Icons.Default.Autorenew
    "blocked" -> Icons.Default.Report
    "review" -> Icons.Default.Visibility
    "done" -> Icons.Default.CheckCircle
    "archived" -> Icons.Default.Archive
    else -> Icons.Outlined.Circle
}

/** Keeps the polling/live driver running while this screen is started. */
@Composable
fun AttachKanbanRuntime(runtime: KanbanRuntime) {
    LifecycleStartEffect(runtime) {
        runtime.attach()
        onStopOrDispose { runtime.detach() }
    }
}

fun kanbanRelativeTime(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

fun kanbanCopy(context: Context, text: String, notice: String) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(notice, text))
    Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
}

/** Rounded card section with an optional uppercase title (detail and form screens). */
@Composable
fun KanbanSection(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** Tinted callout box (blocked question, ready-but-unassigned). */
@Composable
fun KanbanCallout(tint: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = tint.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun KanbanMetricRow(label: String, value: String, maxLines: Int = 1) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines)
    }
}

/** A labelled value that opens a dropdown of options. */
@Composable
fun <T> KanbanPickerRow(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    isSelected: (T) -> Boolean = { false },
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { open = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, title) ->
                DropdownMenuItem(
                    text = { Text(title, fontWeight = if (isSelected(option)) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Full-screen sheet for pickers and editors pushed from a form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanFullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Done")) } },
                        actions = actions,
                    )
                },
                content = content,
            )
        }
    }
}

@Composable
fun KanbanNotice(text: String, color: Color, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Non-composable translation helper for strings built in callbacks. */
fun kt(key: String, vararg args: Any?): String = if (args.isEmpty()) L10n.tr(key) else L10n.tr(key, *args)
