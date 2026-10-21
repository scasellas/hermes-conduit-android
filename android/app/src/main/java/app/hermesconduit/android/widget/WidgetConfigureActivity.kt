package app.hermesconduit.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.ConnectionStore
import app.hermesconduit.android.data.OfflineCache
import app.hermesconduit.android.ui.theme.ConduitTheme
import kotlinx.coroutines.launch

/** Choose a workspace and conversation for this widget (iOS LauncherWidgetConfigurationIntent). */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val store = ConnectionStore(this)
        val dashboards = store.all()
        val cache = OfflineCache(this)
        setContent {
            ConduitTheme {
                Surface(Modifier.fillMaxSize()) {
                    var dashboard by remember { mutableStateOf(store.activeId ?: dashboards.firstOrNull()?.id) }
                    val profile = dashboards.firstOrNull { it.id == dashboard }?.profile ?: "default"
                    var session by remember { mutableStateOf<String?>(null) }
                    var hide by remember { mutableStateOf(false) }
                    val sessions = remember(dashboard) { cache.sessions(dashboard, profile).orEmpty().take(30) }
                    Column(Modifier.padding(16.dp)) {
                        Text(L10n.tr("Choose a workspace and conversation for this widget."), style = MaterialTheme.typography.titleMedium)
                        Text(L10n.tr("Workspace"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                        dashboards.forEach { d ->
                            Row(Modifier.fillMaxWidth().clickable { dashboard = d.id; session = null }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = dashboard == d.id, onClick = { dashboard = d.id; session = null })
                                Text(d.displayName + if (d.profile != "default") " · ${d.profile}" else "")
                            }
                        }
                        Text(L10n.tr("Conversation"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                Row(Modifier.fillMaxWidth().clickable { session = null }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = session == null, onClick = { session = null })
                                    Text(L10n.tr("Most recent"))
                                }
                            }
                            items(sessions, key = { it.id }) { s ->
                                Row(Modifier.fillMaxWidth().clickable { session = s.id }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = session == s.id, onClick = { session = s.id })
                                    Text(s.title, maxLines = 1)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(L10n.tr("Hide conversation title"), Modifier.weight(1f))
                            Switch(checked = hide, onCheckedChange = { hide = it })
                        }
                        Button(onClick = {
                            WidgetData.saveConfig(this@WidgetConfigureActivity, widgetId, dashboard, profile, session, hide)
                            lifecycleScope.launch {
                                WidgetData.refresh(this@WidgetConfigureActivity)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(L10n.tr("Done")) }
                    }
                }
            }
        }
    }
}
