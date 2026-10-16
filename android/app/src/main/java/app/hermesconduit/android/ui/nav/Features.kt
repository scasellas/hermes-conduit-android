package app.hermesconduit.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.hermesconduit.android.ui.capabilities.CapabilitiesScreen
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
    Feature("settings.about", "About & privacy", Icons.Default.Info, inDrawer = false, section = "About") { back, _ -> app.hermesconduit.android.ui.settings.AboutPage(back) },
    Feature("capabilities", "Capabilities", Icons.Default.Extension, inDrawer = true) { back, _ -> CapabilitiesScreen(back) },
)

fun feature(route: String): Feature? = features.firstOrNull { it.route == route.substringBefore('/') }
