package app.hermesconduit.android.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.ui.AppLock
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.nav.features

/** Settings hub. Sub-pages are features with `inDrawer = false` and a `settings.` route prefix. */
@Composable
fun SettingsScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val settings = context.appSettings
    val language by settings.string(AppSettings.LANGUAGE, "system").collectAsStateWithLifecycle()
    val theme by settings.string(AppSettings.THEME, "system").collectAsStateWithLifecycle()
    val lock by settings.bool(AppSettings.BIOMETRIC_LOCK, false).collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }

    val plugin = remember { app.hermesconduit.android.ui.plugin.PluginStatusRepository.of(context) }
    val pluginStatus by plugin.status.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { plugin.status(force = false) } }
    ScreenScaffold(t("Settings"), onBack) { padding ->
        SettingsColumn(padding) {
            if (pluginStatus.updateAvailable || pluginStatus.restartPending) {
                SettingsSection(null) {
                    SettingsRow(
                        if (pluginStatus.restartPending) t("Finish updating the Conduit plugin") else t("Update the Conduit plugin"),
                        subtitle = t("Newer Conduit features need a newer notifier plugin on this Hermes."),
                        icon = Icons.Default.SystemUpdate,
                        onClick = { navigate("settings.plugin") },
                    )
                }
            }
            val pages = features.filter { it.route.startsWith("settings.") }
            val order = listOf("Profile", "Voice", "Hermes", "App", "On this device", "Server", "About")
            pages.groupBy { it.section ?: "Server" }.toList().sortedBy { (section, _) -> order.indexOf(section).let { if (it < 0) order.size else it } }
                .forEach { (section, list) ->
                    if (section == "About") return@forEach
                    SettingsSection(t(section)) {
                        list.forEach { page -> SettingsRow(t(page.title), icon = page.icon, onClick = { navigate(page.route) }) }
                    }
                }
            SettingsSection(t("Appearance")) {
                SettingsRow(
                    t("Language"),
                    icon = Icons.Default.Language,
                    value = if (language == "system") t("System Default") else L10n.languages[language],
                    onClick = { picker = "language" },
                )
                val icon by settings.string(AppSettings.APP_ICON, "dark").collectAsStateWithLifecycle()
                SettingsRow(
                    t("App icon"),
                    icon = Icons.Default.Palette,
                    value = if (icon == "light") t("Light") else t("Dark"),
                    onClick = { picker = "icon" },
                )
                SettingsRow(
                    t("Theme"),
                    icon = Icons.Default.DarkMode,
                    value = when (theme) { "light" -> t("Light"); "dark" -> t("Dark"); else -> t("System") },
                    onClick = { picker = "theme" },
                )
            }
            pages.filter { it.section == "About" }.takeIf { it.isNotEmpty() }?.let { about ->
                SettingsSection(t("About")) { about.forEach { page -> SettingsRow(t(page.title), icon = page.icon, onClick = { navigate(page.route) }) } }
            }
            SettingsSection(t("Security"), footer = t("Ask for your fingerprint, face or screen lock when Conduit opens.")) {
                val available = (context as? FragmentActivity)?.let(AppLock::isAvailable) == true
                SettingsToggle(
                    t("Lock Conduit"),
                    checked = lock && available,
                    enabled = available,
                    icon = Icons.Default.Lock,
                    subtitle = if (available) null else t("Set up a screen lock on this device first."),
                    onChange = { on ->
                        val activity = context as? FragmentActivity ?: return@SettingsToggle
                        AppLock.authenticate(activity, L10n.tr("Unlock Conduit")) { ok -> if (ok) settings.set(AppSettings.BIOMETRIC_LOCK, on) }
                    },
                )
            }
        }
    }

    when (picker) {
        "language" -> ChoiceDialog(
            t("Language"),
            listOf("system" to t("System Default")) + L10n.languages.toList(),
            language,
            onPick = { settings.set(AppSettings.LANGUAGE, it) },
            onDismiss = { picker = null },
        )
        "icon" -> ChoiceDialog(
            t("App icon"),
            listOf("dark" to t("Dark"), "light" to t("Light")),
            settings.getString(AppSettings.APP_ICON, "dark"),
            onPick = {
                settings.set(AppSettings.APP_ICON, it)
                app.hermesconduit.android.core.AppIcon.apply(context, it)
            },
            onDismiss = { picker = null },
        )
        "theme" -> ChoiceDialog(
            t("Theme"),
            listOf("system" to t("System"), "light" to t("Light"), "dark" to t("Dark")),
            theme,
            onPick = { settings.set(AppSettings.THEME, it) },
            onDismiss = { picker = null },
        )
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(id); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = { onPick(id); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Done")) } },
    )
}
