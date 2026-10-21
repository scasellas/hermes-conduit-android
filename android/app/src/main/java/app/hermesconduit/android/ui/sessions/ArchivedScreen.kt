package app.hermesconduit.android.ui.sessions

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.SessionSummary
import app.hermesconduit.android.ui.components.EmptyState
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ArchivedViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val _sessions = MutableStateFlow<List<SessionSummary>?>(null)
    val sessions: StateFlow<List<SessionSummary>?> = _sessions
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { hermes.rest { it.sessions(hermes.profile.value, archived = true) } }
                .onSuccess { _sessions.value = it.sessions; _error.value = null }
                .onFailure { _error.value = it.message; if (_sessions.value == null) _sessions.value = emptyList() }
        }
    }

    fun restore(session: SessionSummary) = mutate { it.setArchived(session.id, hermes.profile.value, false) }

    fun delete(session: SessionSummary) = mutate { it.deleteSession(session.id, hermes.profile.value) }

    private fun mutate(block: suspend (app.hermesconduit.android.net.DashboardApi) -> Unit) {
        viewModelScope.launch {
            runCatching { hermes.rest { block(it) } }.onFailure { _error.value = it.message }
            refresh()
        }
    }
}

@Composable
fun ArchivedScreen(onBack: () -> Unit, model: ArchivedViewModel = viewModel()) {
    val sessions by model.sessions.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    ScreenScaffold(t("Archived conversations"), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(t("Search")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ErrorText(error)
            val list = sessions
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(t("No archived conversations"))
                else -> LazyColumn {
                    items(list.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }, key = { it.id }) { session ->
                        ListItem(
                            headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = session.lastActiveMs?.let { { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) } },
                            trailingContent = {
                                androidx.compose.foundation.layout.Row {
                                    IconButton(onClick = { model.restore(session) }) { Icon(Icons.Default.Unarchive, t("Restore")) }
                                    IconButton(onClick = { deleting = session }) { Icon(Icons.Default.Delete, t("Delete"), tint = MaterialTheme.colorScheme.error) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("Delete chat?")) },
            text = { Text(session.title) },
            confirmButton = { TextButton(onClick = { model.delete(session); deleting = null }) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("Cancel")) } },
        )
    }
}
