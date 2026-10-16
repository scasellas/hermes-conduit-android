package app.hermesconduit.android.ui.capabilities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.ui.components.ScreenScaffold

enum class CapabilitiesTab(val key: String, val title: String, val searchPrompt: String) {
    SKILLS("skills", "Skills", "Search skills"),
    TOOLS("tools", "Tools", "Search tools"),
    CONNECTORS("connectors", "Connectors", "Search connectors"),
    PLUGINS("plugins", "Plugins", "Search plugins"),
}

/** A page pushed from one of the tabs. */
sealed interface CapabilityPage {
    data class Skill(val name: String) : CapabilityPage
    data class Official(val identifier: String) : CapabilityPage
    data class Toolset(val name: String) : CapabilityPage
    data class Connector(val name: String) : CapabilityPage
    data class Plugin(val name: String) : CapabilityPage
}

/**
 * Settings > Capabilities, as Hermes Desktop's: Skills, Tools, Connectors and
 * Plugins, each its own tab, scoped to the active Hermes profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(onBack: () -> Unit) {
    val vm: CapabilitiesViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalContext.current.appSettings
    val tabKey by settings.string(AppSettings.CAPABILITIES_TAB, CapabilitiesTab.SKILLS.key).collectAsStateWithLifecycle()
    val tab = CapabilitiesTab.entries.firstOrNull { it.key == tabKey } ?: CapabilitiesTab.SKILLS
    var search by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<CapabilityPage?>(null) }
    val query = search.trim()

    // A profile or dashboard switch drops the page it showed.
    val profile = state.profile
    var shownProfile by remember { mutableStateOf(profile) }
    if (shownProfile != profile) {
        shownProfile = profile
        page = null
    }

    BackHandler(enabled = page != null) { page = null }
    val open: (CapabilityPage) -> Unit = { page = it }
    val close = { page = null }

    when (val current = page) {
        is CapabilityPage.Skill -> SkillDetailPage(vm, state, current.name, close)
        is CapabilityPage.Official -> OfficialSkillDetailPage(vm, state, current.identifier, close)
        is CapabilityPage.Toolset -> ToolsetDetailPage(vm, state, current.name, close)
        is CapabilityPage.Connector -> ConnectorDetailPage(vm, state, current.name, close)
        is CapabilityPage.Plugin -> PluginDetailPage(vm, state, current.name, close)
        null -> ScreenScaffold(t("Capabilities"), onBack) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    CapabilitiesTab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = { settings.set(AppSettings.CAPABILITIES_TAB, entry.key) },
                            text = { Text(t(entry.title), maxLines = 1) },
                        )
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(t(tab.searchPrompt)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, t("Clear")) }
                        }
                    },
                    singleLine = true,
                )
                when (tab) {
                    CapabilitiesTab.SKILLS, CapabilitiesTab.TOOLS -> SkillsOrTools(vm, state, tab, query, open)
                    CapabilitiesTab.CONNECTORS -> ConnectorsTab(vm, state, query, open)
                    CapabilitiesTab.PLUGINS -> PluginsTab(vm, state, query, open)
                }
            }
        }
    }
}

/**
 * Skills and Tools share the installed-capability load: a spinner until this
 * profile's first result, an error with Try Again when it failed with nothing
 * to show, and a banner when a refresh failed over rows already shown.
 */
@Composable
private fun SkillsOrTools(vm: CapabilitiesViewModel, state: CapabilitiesState, tab: CapabilitiesTab, query: String, open: (CapabilityPage) -> Unit) {
    val hasRows = state.skills.isNotEmpty() || state.toolsets.isNotEmpty()
    val ownsRows = hasRows && state.snapshotLoaded
    when {
        state.capabilitiesLoading && !ownsRows -> LoadingCapabilities()
        state.loadError != null && !ownsRows -> CapabilityTabList(refresh = { vm.refreshCapabilities() }) {
            item {
                CapabilityEmptyState(
                    title = t("Couldn't Load"),
                    message = state.loadError,
                    retry = { vm.loadCapabilities() },
                )
            }
        }
        !state.snapshotLoaded && !state.capabilitiesLoading && state.loadError == null -> LoadingCapabilities()
        else -> {
            val banner = state.loadError.takeIf { ownsRows }
            if (tab == CapabilitiesTab.SKILLS) SkillsTab(vm, state, query, banner, open) else ToolsTab(vm, state, query, banner, open)
        }
    }
}

@Composable
private fun LoadingCapabilities() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        CircularProgressIndicator()
        Text(t("Loading capabilities…"), Modifier.padding(top = 12.dp))
    }
}
