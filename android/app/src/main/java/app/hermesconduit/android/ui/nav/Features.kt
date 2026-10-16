package app.hermesconduit.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
    val content: @Composable (onBack: () -> Unit, navigate: (String) -> Unit) -> Unit,
)

/** Every feature screen. Keep one entry per feature so additions merge cleanly. */
val features: List<Feature> = listOf(
    Feature("settings", "Settings", Icons.Default.Settings) { back, nav -> SettingsScreen(back, nav) },
    Feature("cron", "Cron", Icons.Default.Schedule) { back, nav -> app.hermesconduit.android.ui.cron.CronScreen(back, nav) },
    Feature("settings.gateway", "Gateway", Icons.Default.Router, inDrawer = false) { back, nav -> app.hermesconduit.android.ui.gateway.GatewayScreen(back, nav) },
    Feature("settings.plugin", "Conduit plugin", Icons.Default.Extension, inDrawer = false) { back, nav -> app.hermesconduit.android.ui.plugin.PluginScreen(back, nav) },
    Feature("artifacts", "Artifacts", Icons.Default.GridView) { back, nav -> app.hermesconduit.android.ui.artifacts.ArtifactsScreen(back, nav) },
    Feature("workspace", "Workspace", Icons.Default.FolderOpen) { back, nav -> app.hermesconduit.android.ui.workspace.WorkspaceScreen(back, nav) },
)

fun feature(route: String): Feature? = features.firstOrNull { it.route == route.substringBefore('/') }
