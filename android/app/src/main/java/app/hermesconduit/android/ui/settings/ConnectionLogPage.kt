package app.hermesconduit.android.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The connection timeline, newest last, copyable for bug reports. */
@Composable
fun ConnectionLogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by context.hermes.timeline.collectAsStateWithLifecycle()
    val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    ScreenScaffold(
        t("Connection log"),
        onBack,
        actions = {
            IconButton(onClick = { copyToClipboard(context, entries.joinToString("\n") { "${format.format(Date(it.atMs))} ${it.text}" }) }) {
                Icon(Icons.Default.ContentCopy, t("Copy"))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            items(entries.reversed()) { entry ->
                Text("${format.format(Date(entry.atMs))}  ${entry.text}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}
