package app.hermesconduit.android.ui.nav

import androidx.compose.material.icons.Icons
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
)

fun feature(route: String): Feature? = features.firstOrNull { it.route == route.substringBefore('/') }
