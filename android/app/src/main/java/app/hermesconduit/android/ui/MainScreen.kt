package app.hermesconduit.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.nav.features
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.model.SessionSummary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MainScreen(viewModel: AppViewModel, openFeature: (String) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

        val drawer = @Composable {
            SessionDrawer(
                viewModel = viewModel,
                onPicked = { if (!wide) closeDrawer() },
                openFeature = { route -> if (!wide) closeDrawer(); openFeature(route) },
            )
        }
        val chat = @Composable {
            ChatScreen(
                viewModel = viewModel,
                showMenuButton = !wide,
                onMenu = { scope.launch { drawerState.open() } },
            )
        }

        if (wide) {
            PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(320.dp)) { drawer() } }) { chat() }
        } else {
            BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet(Modifier.width(320.dp)) { drawer() } },
            ) { chat() }
        }
    }
}

/** Everything the chat list can ask of the app. */
interface DrawerActions {
    fun refreshSessionsAsync()
    fun newChat()
    fun selectProfile(name: String)
    fun openSession(session: SessionSummary)
    fun renameSession(id: String, title: String)
    fun togglePinned(session: SessionSummary)
    fun archiveSession(id: String)
    fun deleteSession(id: String)
    fun signOut()
    fun switchDashboard(id: String)
    fun addDashboard()
}

@Composable
private fun SessionDrawer(viewModel: AppViewModel, onPicked: () -> Unit, openFeature: (String) -> Unit) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val dashboards by viewModel.dashboards.collectAsStateWithLifecycle()
    val active by viewModel.connection.collectAsStateWithLifecycle()
    SessionDrawerContent(
        sessions, chat.sessionId, profile, profiles, viewModel.dashboardUrl, viewModel, onPicked,
        dashboards = dashboards.map { it.id to it.displayName },
        activeDashboardId = active?.id,
        openFeature = openFeature,
    )
}

@Composable
fun SessionDrawerContent(
    sessions: SessionsState,
    selectedId: String?,
    profile: String,
    profiles: List<String>,
    dashboardUrl: String?,
    actions: DrawerActions,
    onPicked: () -> Unit,
    dashboards: List<Pair<String, String>> = emptyList(),
    activeDashboardId: String? = null,
    openFeature: (String) -> Unit = {},
) {
    var profileMenu by remember { mutableStateOf(false) }
    var dashboardMenu by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Box {
                    Text(
                        (dashboards.firstOrNull { it.first == activeDashboardId }?.second ?: dashboardUrl.orEmpty()) + "  ▾",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { dashboardMenu = true }.padding(vertical = 4.dp),
                    )
                    DropdownMenu(expanded = dashboardMenu, onDismissRequest = { dashboardMenu = false }) {
                        dashboards.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name, fontWeight = if (id == activeDashboardId) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    dashboardMenu = false
                                    actions.switchDashboard(id)
                                    onPicked()
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Add dashboard…") }, onClick = { dashboardMenu = false; actions.addDashboard() })
                    }
                }
            }
            IconButton(onClick = actions::refreshSessionsAsync) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = { actions.newChat(); onPicked() }) { Icon(Icons.Default.Add, "New chat") }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { profileMenu = true }) {
                Icon(Icons.Default.Person, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Profile: $profile")
            }
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                profiles.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (name == profile) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            profileMenu = false
                            actions.selectProfile(name)
                        },
                    )
                }
            }
        }
        if (sessions.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        sessions.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            if (sessions.sessions.isEmpty() && !sessions.loading) {
                item {
                    Text(
                        "No chats yet. Start one with +.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sessions.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedId,
                    onOpen = { actions.openSession(session); onPicked() },
                    onRename = { actions.renameSession(session.id, it) },
                    onPin = { actions.togglePinned(session) },
                    onArchive = { actions.archiveSession(session.id) },
                    onDelete = { actions.deleteSession(session.id) },
                )
            }
        }
        HorizontalDivider()
        val tools = features.filter { it.inDrawer }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tools.forEach { tool ->
                Column(
                    Modifier.clip(MaterialTheme.shapes.medium).clickable { openFeature(tool.route) }.padding(8.dp).width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(t(tool.title), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign out")
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Conduit forgets this dashboard's address and credentials on this device.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; actions.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f)

    Box {
        androidx.compose.material3.Surface(
            color = background,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned) {
                        Icon(Icons.Default.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        session.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (session.unread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                val subtitle = listOfNotNull(
                    session.lastActiveMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
                    session.model,
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text(if (session.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onArchive() })
            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = { renaming = false; onRename(title.trim()) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete chat?") },
            text = { Text("“${session.title}” is deleted from the dashboard for every device.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
