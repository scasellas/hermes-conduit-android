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
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
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
    appViewModelMove = viewModel::moveSessionToFolder
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
    fun setSessionFilter(filter: String) {}
    fun setSessionQuery(query: String) {}
    fun openSessionById(id: String, title: String) {}
    fun setUnread(session: SessionSummary, unread: Boolean) {}
    fun markAllRead() {}
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
                Text(t("Chats"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                        DropdownMenuItem(text = { Text(t("Add dashboard…")) }, onClick = { dashboardMenu = false; actions.addDashboard() })
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
                val appearance = app.hermesconduit.android.core.ProfileAppearance.of(androidx.compose.ui.platform.LocalContext.current)
                val appearanceVersion by appearance.version.collectAsStateWithLifecycle()
                appearance.sorted(profiles).forEach { name ->
                    DropdownMenuItem(
                        leadingIcon = { app.hermesconduit.android.ui.settings.Avatar(appearance, name, appearanceVersion, size = 28) },
                        text = { Text(name, fontWeight = if (name == profile) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            profileMenu = false
                            actions.selectProfile(name)
                        },
                    )
                }
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = sessions.query,
            onValueChange = actions::setSessionQuery,
            placeholder = { Text(t("Search")) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (sessions.query.isNotEmpty()) ({
                IconButton(onClick = { actions.setSessionQuery("") }) { Icon(Icons.Default.Close, t("Clear")) }
            }) else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val sources = sessions.sessions.map { it.sourceKind }.distinct().filter { it != "chat" }
            (sessionFilters + sources.map { "source.$it" }).forEach { filter ->
                androidx.compose.material3.FilterChip(
                    selected = sessions.filter == filter,
                    onClick = { actions.setSessionFilter(filter) },
                    label = { Text(filterTitle(filter)) },
                )
            }
        }
        if (sessions.loading || sessions.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        sessions.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        val visible = sessions.visible
        val nested = remember(visible) { nestSessions(visible) }
        LazyColumn(Modifier.weight(1f)) {
            if (visible.isEmpty() && !sessions.loading) {
                item {
                    Text(
                        if (sessions.query.isBlank() && sessions.filter == "all") t("No chats yet. Start one with +.") else t("No matching chats"),
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sessions.sessions.any { it.unread } && sessions.filter in setOf("all", "unread") && sessions.query.isBlank()) {
                item(key = "mark-all-read") {
                    TextButton(onClick = actions::markAllRead, modifier = Modifier.padding(horizontal = 8.dp)) { Text(t("Mark all as read")) }
                }
            }
            items(nested, key = { it.first.id }) { (session, depth) ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedId,
                    onOpen = { actions.openSession(session); onPicked() },
                    onRename = { actions.renameSession(session.id, it) },
                    onPin = { actions.togglePinned(session) },
                    onArchive = { actions.archiveSession(session.id) },
                    onDelete = { actions.deleteSession(session.id) },
                    depth = depth,
                    running = sessions.isRunning(session),
                    needsApproval = sessions.needsApproval(session),
                    onToggleUnread = { actions.setUnread(session, !session.unread) },
                )
            }
            val older = sessions.searchResults.orEmpty().filter { match -> visible.none { it.matches(match.sessionId) } }
            if (sessions.query.isNotBlank() && older.isNotEmpty()) {
                item(key = "search-header") {
                    Text(t("Messages"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(older, key = { "search-" + it.sessionId }) { match ->
                    Column(
                        Modifier.fillMaxWidth().clickable { actions.openSessionById(match.sessionId, match.title); onPicked() }.padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(match.title.ifEmpty { t("Untitled chat") }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (match.snippet.isNotEmpty()) Text(match.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
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
            Text(t("Sign out"))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(t("Sign out?")) },
            text = { Text(t("Conduit forgets this dashboard's address and credentials on this device.")) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; actions.signOut() }) { Text(t("Sign out")) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(t("Cancel")) } },
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
    depth: Int = 0,
    running: Boolean = false,
    needsApproval: Boolean = false,
    onToggleUnread: () -> Unit = {},
) {
    var moving by remember { mutableStateOf(false) }
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
                .padding(start = (8 + depth * 16).dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.unread) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (session.pinned) {
                        Icon(Icons.Default.PushPin, t("Pinned"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (depth > 0) {
                        Icon(Icons.Default.SubdirectoryArrowRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (needsApproval) Icon(Icons.Default.Shield, t("Needs approval"), Modifier.size(16.dp), tint = app.hermesconduit.android.ui.theme.attentionColor())
                    if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    if (session.sourceKind != "chat") {
                        Spacer(Modifier.width(4.dp))
                        Text(filterTitle("source." + session.sourceKind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
            DropdownMenuItem(text = { Text(if (session.unread) t("Mark as read") else t("Mark as unread")) }, onClick = { menu = false; onToggleUnread() })
            DropdownMenuItem(text = { Text(t("Rename")) }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text(if (session.pinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text(t("Move to Project…")) }, onClick = { menu = false; moving = true })
            DropdownMenuItem(text = { Text(t("Archive")) }, onClick = { menu = false; onArchive() })
            DropdownMenuItem(text = { Text(t("Delete"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
        }
    }

    if (moving) {
        MoveToProjectDialog(onPick = { cwd -> moving = false; appViewModelMove(session, cwd) }, onDismiss = { moving = false })
    }
    if (renaming) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(t("Rename chat")) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = { renaming = false; onRename(title.trim()) }) { Text(t("Save")) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(t("Cancel")) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Delete chat?")) },
            text = { Text("“${session.title}” is deleted from the dashboard for every device.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel")) } },
        )
    }
}

val sessionFilters = listOf("all", "unread", "needsApproval", "running", "voice", "scheduled")

@Composable
fun filterTitle(filter: String): String = when (filter) {
    "all" -> t("All")
    "unread" -> t("Unread")
    "needsApproval" -> t("Needs approval")
    "running" -> t("Running")
    "voice" -> t("Voice")
    "scheduled" -> t("Scheduled")
    "source.chat" -> t("Chat")
    "source.voice" -> t("Voice")
    "source.voice_job" -> t("Voice Jobs")
    "source.discord" -> "Discord"
    "source.telegram" -> "Telegram"
    "source.api" -> "API"
    "source.webhook" -> t("Webhook")
    "source.cron" -> t("Cron")
    else -> t("Other")
}

/** Children (branches, delegations) under their parent when both are listed, like iOS SessionNesting. */
fun nestSessions(sessions: List<SessionSummary>): List<Pair<SessionSummary, Int>> {
    val byId = sessions.associateBy { it.id }
    val children = sessions.filter { s -> s.parentId != null && byId.containsKey(s.parentId) && s.parentId != s.id }.groupBy { it.parentId!! }
    val childIds = children.values.flatten().map { it.id }.toSet()
    val result = mutableListOf<Pair<SessionSummary, Int>>()
    fun add(s: SessionSummary, depth: Int, seen: MutableSet<String>) {
        if (!seen.add(s.id)) return
        result += s to depth
        children[s.id]?.forEach { add(it, minOf(depth + 1, 3), seen) }
    }
    val seen = mutableSetOf<String>()
    sessions.filter { it.id !in childIds }.forEach { add(it, 0, seen) }
    sessions.filter { it.id !in seen }.forEach { add(it, 0, seen) }
    return result
}

private var appViewModelMove: (SessionSummary, String) -> Unit = { _, _ -> }

@Composable
private fun MoveToProjectDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val model: app.hermesconduit.android.ui.projects.ProjectsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val projects by model.projects.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { model.refresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Move to Project")) },
        text = {
            val list = projects?.filter { it.primaryPath != null && !it.isHome }
            when {
                list == null -> androidx.compose.material3.CircularProgressIndicator()
                list.isEmpty() -> Text(t("No projects yet"))
                else -> LazyColumn {
                    items(list, key = { it.id }) { p ->
                        Text(p.title, Modifier.fillMaxWidth().clickable { onPick(p.primaryPath!!) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
    )
}
