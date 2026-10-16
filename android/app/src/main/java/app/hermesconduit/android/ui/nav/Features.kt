package app.hermesconduit.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.hermesconduit.android.ui.capabilities.CapabilitiesScreen
import androidx.compose.material.icons.filled.ViewKanban
import app.hermesconduit.android.ui.kanban.KanbanBoardScreen
import app.hermesconduit.android.ui.kanban.kanbanSubFeatures
import app.hermesconduit.android.ui.settings.SettingsScreen

/**
 * A pushed screen reachable from the drawer. `navigate` opens another
 * feature by route (e.g. a settings sub-page).
 */
class Feature(
    val route: String,
    val title: String,
    val icon: ImageVector,
    /** Listed in the drawer's tools section. */
    val inDrawer: Boolean = true,
    /** Settings group for `settings.*` routes: Profile, Voice, Hermes, App, Device, Server, About. */
    val section: String? = null,
    val content: @Composable (onBack: () -> Unit, navigate: (String) -> Unit) -> Unit,
)

/** Every feature screen. Keep one entry per feature so additions merge cleanly. */
val features: List<Feature> = listOf(
    Feature("settings", "Settings", Icons.Default.Settings) { back, nav -> SettingsScreen(back, nav) },
    Feature("projects", "Projects", Icons.Default.Folder) { back, nav -> app.hermesconduit.android.ui.projects.ProjectsScreen(back, nav) },
    Feature("projects.detail", "Project", Icons.Default.Folder, inDrawer = false) { back, nav -> app.hermesconduit.android.ui.projects.ProjectDetailScreen(back, nav) },
    Feature("archived", "Archived", Icons.Default.Archive) { back, _ -> app.hermesconduit.android.ui.sessions.ArchivedScreen(back) },
    Feature("settings.profile", "Profile", Icons.Default.Person, inDrawer = false, section = "Profile") { back, _ -> app.hermesconduit.android.ui.settings.ProfileSettingsPage(back) },
    Feature("settings.workspace", "Workspace & safety", Icons.Default.Security, inDrawer = false, section = "Profile") { back, _ -> app.hermesconduit.android.ui.settings.WorkspaceSettingsPage(back) },
    Feature("settings.model", "Model", Icons.Default.Memory, inDrawer = false, section = "Hermes") { back, _ -> app.hermesconduit.android.ui.settings.ModelDefaultsPage(back) },
    Feature("settings.chat", "Chat", Icons.Default.Chat, inDrawer = false, section = "Hermes") { back, _ -> app.hermesconduit.android.ui.settings.ChatSettingsPage(back) },
    Feature("settings.memory", "Memory & delegation", Icons.Default.Psychology, inDrawer = false, section = "Hermes") { back, _ -> app.hermesconduit.android.ui.settings.MemorySettingsPage(back) },
    Feature("settings.profiles", "Profiles", Icons.Default.Person, inDrawer = false, section = "App") { back, _ -> app.hermesconduit.android.ui.settings.ProfilesPage(back) },
    Feature("settings.agent", "Let Hermes change settings", Icons.Default.Info, inDrawer = false, section = "App") { back, _ -> app.hermesconduit.android.ui.agentsettings.AgentSettingsPage(back) },
    Feature("settings.dashboards", "Saved Dashboards", Icons.Default.Info, inDrawer = false, section = "Server") { back, nav -> app.hermesconduit.android.ui.settings.SavedDashboardsPage(back, nav) },
    Feature("settings.notifications", "Notifications", Icons.Default.Notifications, inDrawer = false, section = "App") { back, _ -> app.hermesconduit.android.notify.NotificationSettingsPage(back) },
    Feature("settings.connectionlog", "Connection log", Icons.Default.Info, inDrawer = false, section = "Server") { back, _ -> app.hermesconduit.android.ui.settings.ConnectionLogPage(back) },
    Feature("settings.screenquestion", "Ask Hermes About Screen", Icons.Default.Info, inDrawer = false, section = "App") { back, _ -> app.hermesconduit.android.assist.ScreenQuestionPage(back) },
    Feature("settings.about", "About & privacy", Icons.Default.Info, inDrawer = false, section = "About") { back, _ -> app.hermesconduit.android.ui.settings.AboutPage(back) },
    Feature("capabilities", "Capabilities", Icons.Default.Extension, inDrawer = true) { back, _ -> CapabilitiesScreen(back) },
    Feature("cron", "Cron", Icons.Default.Schedule) { back, nav -> app.hermesconduit.android.ui.cron.CronScreen(back, nav) },
    Feature("settings.gateway", "Gateway", Icons.Default.Router, inDrawer = false) { back, nav -> app.hermesconduit.android.ui.gateway.GatewayScreen(back, nav) },
    Feature("settings.plugin", "Conduit plugin", Icons.Default.Extension, inDrawer = false) { back, nav -> app.hermesconduit.android.ui.plugin.PluginScreen(back, nav) },
    Feature("artifacts", "Artifacts", Icons.Default.GridView) { back, nav -> app.hermesconduit.android.ui.artifacts.ArtifactsScreen(back, nav) },
    Feature("workspace", "Workspace", Icons.Default.FolderOpen) { back, nav -> app.hermesconduit.android.ui.workspace.WorkspaceScreen(back, nav) },
    Feature("kanban", "Kanban", Icons.Default.ViewKanban) { back, nav -> KanbanBoardScreen(back, nav) },
    *kanbanSubFeatures(),
    Feature("bots", "Bots", Icons.Default.SmartToy) { back, nav -> app.hermesconduit.android.ui.bots.BotsScreen(back, nav) },
    Feature("groups", "Group Chats", Icons.Default.Groups) { back, nav -> app.hermesconduit.android.ui.groups.GroupsScreen(back, nav) },
    Feature("groups.room", "Group Chat", Icons.Default.Groups, inDrawer = false) { back, _ -> app.hermesconduit.android.ui.groups.GroupRoomScreen(back) },
)

fun feature(route: String): Feature? = features.firstOrNull { it.route == route.substringBefore('/') }
