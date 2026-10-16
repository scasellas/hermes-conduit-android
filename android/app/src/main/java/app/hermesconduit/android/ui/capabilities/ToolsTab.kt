package app.hermesconduit.android.ui.capabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.CapabilityFormat
import app.hermesconduit.android.model.CapabilityToolset
import app.hermesconduit.android.model.CapabilityToolsetOrdering
import app.hermesconduit.android.model.TerminalBackendOption
import app.hermesconduit.android.model.ToolsetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun needsKeysPill() = PillModel(L10n.tr("Needs keys"), CapabilityColors.orange)

/**
 * Settings > Capabilities > Tools: the profile's toolsets, the most called
 * first, each with its switch and page (tools, backend and keys).
 */
@Composable
fun ToolsTab(vm: CapabilitiesViewModel, state: CapabilitiesState, query: String, banner: String?, open: (CapabilityPage) -> Unit) {
    val settings = LocalContext.current.appSettings
    val mostUsedFirst by settings.bool(AppSettings.CAPABILITIES_TOOLS_MOST_USED, true).collectAsStateWithLifecycle()
    val toolsets = CapabilityToolsetOrdering.sorted(
        state.toolsets.filter { CapabilityToolsetOrdering.isVisible(it) && CapabilityToolsetOrdering.matches(it, query) },
        state.toolCalls.orEmpty(),
        mostUsedFirst,
    )

    LaunchedEffect(state.profile) {
        if (state.toolCalls == null) vm.loadToolCalls()
    }

    CapabilityTabList(refresh = {
        vm.refreshCapabilities()
        vm.loadToolCalls()
    }) {
        if (banner != null) item { CapabilityRefreshBanner(banner) }
        state.toggleError?.let { item { FootnoteText(it, MaterialTheme.colorScheme.error) } }
        if (toolsets.isEmpty()) {
            item {
                CapabilityEmptyState(
                    title = if (query.isEmpty()) t("No tools yet") else t("No matching tools"),
                    message = if (query.isEmpty()) null else t("Nothing matches “%s”.", query),
                    icon = Icons.Default.Build,
                )
            }
        } else {
            item(key = "toolsets") {
                CapabilitySection(
                    title = t("Toolsets"),
                    footer = t("Changes apply to new sessions."),
                    accessory = {
                        OptionsMenu(
                            label = t("Tool options"),
                            busy = state.bulkRunning,
                            mostUsedFirst = mostUsedFirst,
                            onSort = { settings.set(AppSettings.CAPABILITIES_TOOLS_MOST_USED, it) },
                            onAllOn = { vm.setAllToolsets(true) },
                            onAllOff = { vm.setAllToolsets(false) },
                        )
                    },
                ) {
                    toolsets.forEachIndexed { index, toolset ->
                        if (index > 0) CapabilityRowDivider()
                        CapabilityRow(
                            icon = CapabilityIcons.toolset(toolset.name),
                            active = toolset.enabled,
                            title = CapabilityToolsetOrdering.label(toolset),
                            pills = if (toolset.configured == false) listOf(needsKeysPill()) else emptyList(),
                            detail = toolset.description,
                            meta = toolsetMeta(toolset, state.toolCalls),
                            onClick = { open(CapabilityPage.Toolset(toolset.name)) },
                        ) {
                            CapabilitySwitch(toolset.enabled, { vm.toggleToolset(toolset.name, it) })
                        }
                    }
                }
            }
        }
    }
}

/** Calls when there are some, else how many tools the set holds. */
@Composable
private fun toolsetMeta(toolset: CapabilityToolset, counts: Map<String, Int>?): String? {
    if (counts != null) {
        val calls = CapabilityToolsetOrdering.calls(toolset, counts)
        if (calls > 0) return CapabilityFormat.uses(calls)
    }
    val toolCount = toolset.tools?.size ?: 0
    return if (toolCount > 0) t("%d tools", toolCount) else null
}

@Composable
private fun statusPill(status: String): PillModel? = when (status) {
    "ready" -> PillModel(t("Ready"), CapabilityColors.green)
    "needs_setup" -> PillModel(t("Needs setup"), CapabilityColors.orange)
    "needs_auth" -> PillModel(t("Needs sign-in"), CapabilityColors.orange)
    "needs_keys" -> needsKeysPill()
    "unavailable" -> PillModel(t("Unavailable"))
    else -> null
}

/** A toolset's page: its switch and tools, where the terminal runs, and which backend serves it with its keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsetDetailPage(vm: CapabilitiesViewModel, state: CapabilitiesState, name: String, onBack: () -> Unit) {
    val toolset = state.toolsets.firstOrNull { it.name == name }
    val scope = rememberCoroutineScope()
    var config by remember(name) { mutableStateOf<ToolsetConfig?>(null) }
    var configError by remember(name) { mutableStateOf<String?>(null) }
    var backends by remember(name) { mutableStateOf<List<TerminalBackendOption>?>(null) }
    var backendsError by remember(name) { mutableStateOf<String?>(null) }
    var working by remember(name) { mutableStateOf<String?>(null) }
    var notice by remember(name) { mutableStateOf<String?>(null) }
    var keyPrompt by remember(name) { mutableStateOf<ToolsetConfig.EnvVar?>(null) }
    var backendToConfirm by remember(name) { mutableStateOf<TerminalBackendOption?>(null) }

    suspend fun freshConfig() = try {
        vm.toolsetConfig(name)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    LaunchedEffect(name) {
        coroutineScope {
            val configLoad = async { freshConfig() }
            if (name == "terminal") {
                val fresh = try {
                    vm.terminalBackends()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (fresh != null) {
                    backends = fresh
                    backendsError = null
                } else if (backends == null) {
                    backendsError = L10n.tr("Couldn't read where the terminal runs.")
                }
            }
            val loaded = configLoad.await()
            if (loaded != null) {
                config = loaded
                configError = null
            } else if (config == null) {
                configError = L10n.tr("Couldn't read this toolset's backends.")
            }
        }
    }

    fun choose(provider: ToolsetConfig.Provider, capability: String?) {
        working = provider.name
        notice = null
        scope.launch {
            try {
                if (vm.selectProvider(name, provider.name, capability)) {
                    notice = L10n.tr("Sign in to Nous Portal on your host to use this backend.")
                }
                // A failed read keeps what's on screen.
                freshConfig()?.let { config = it }
                vm.refreshCapabilities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.userMessage()
            }
            working = null
        }
    }

    fun select(backend: TerminalBackendOption) {
        working = backend.name
        notice = null
        scope.launch {
            try {
                vm.selectTerminalBackend(backend.name)
                runCatching { vm.terminalBackends() }.getOrNull()?.let { backends = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.userMessage()
            }
            working = null
        }
    }

    fun save(value: String, env: ToolsetConfig.EnvVar) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        notice = null
        scope.launch {
            try {
                vm.saveKey(env.key, trimmed)
                freshConfig()?.let { config = it }
                vm.refreshCapabilities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.userMessage()
            }
        }
    }

    CapabilityDetailScaffold(toolset?.let(CapabilityToolsetOrdering::label) ?: name, onBack) {
        if (toolset != null) {
            CapabilityCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(CapabilityToolsetOrdering.label(toolset), style = MaterialTheme.typography.titleLarge)
                    if (toolset.configured == false) needsKeysPill().let { CapabilityPill(it.text, it.tint) }
                }
                if (!toolset.description.isNullOrEmpty()) {
                    Text(toolset.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.toolCalls?.let { counts ->
                    val calls = CapabilityToolsetOrdering.calls(toolset, counts)
                    if (calls > 0) Text(CapabilityFormat.uses(calls), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TurnedOnRow(toolset.enabled) { vm.toggleToolset(toolset.name, it) }
            }
            val tools = toolset.tools.orEmpty()
            if (tools.isNotEmpty()) {
                CapabilityDetailSection(t("Tools")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tools.forEach { tool ->
                            Row(
                                Modifier
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(tool, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                val count = state.toolCalls?.get(tool) ?: 0
                                if (count > 0) {
                                    Text(CapabilityFormat.uses(count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        notice?.let { FootnoteText(it, CapabilityColors.orange) }
        if (name == "terminal") {
            val list = backends
            if (!list.isNullOrEmpty()) {
                CapabilityDetailSection(t("Runs commands in")) {
                    list.forEachIndexed { index, backend ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        val clickable = working == null && backend.status != "unavailable" && !backend.active
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = clickable) {
                                    if (backend.status == "ready") select(backend) else backendToConfirm = backend
                                },
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(backend.label, style = MaterialTheme.typography.titleSmall)
                                    statusPill(backend.status)?.takeIf { backend.status != "needs_keys" && backend.status != "needs_auth" }
                                        ?.let { CapabilityPill(it.text, it.tint) }
                                }
                                if (backend.description.isNotEmpty()) {
                                    Text(backend.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (backend.detail != null && backend.status != "ready") {
                                    Text(backend.detail, style = MaterialTheme.typography.bodySmall, color = CapabilityColors.orange)
                                }
                            }
                            when {
                                working == backend.name -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                backend.active -> Icon(Icons.Default.Check, t("Selected"), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                backendsError?.let { FootnoteText(it) }
            }
        }
        val current = config
        if (current != null && current.hasCategory && current.providers.isNotEmpty()) {
            CapabilityDetailSection(t("Backends")) {
                current.providers.forEachIndexed { index, provider ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ProviderRow(
                        toolset = name,
                        provider = provider,
                        config = current,
                        working = working,
                        onKey = { keyPrompt = it },
                        onChoose = ::choose,
                    )
                }
            }
        } else {
            configError?.let { FootnoteText(it) }
        }
        FootnoteText(t("Changes apply to new sessions."))
    }

    keyPrompt?.let { env ->
        var value by remember(env.key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyPrompt = null },
            title = { Text(env.key) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (env.prompt == env.key) t("Saved to this profile on your host. Conduit never reads it back.") else env.prompt)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(t("API key")) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    keyPrompt = null
                    save(value, env)
                }) { Text(t("Save")) }
            },
            dismissButton = { TextButton(onClick = { keyPrompt = null }) { Text(t("Cancel")) } },
        )
    }

    backendToConfirm?.let { backend ->
        AlertDialog(
            onDismissRequest = { backendToConfirm = null },
            title = { Text(t("Use %s anyway?", backend.label)) },
            text = { Text(backend.detail ?: t("It still needs setting up on your host.")) },
            confirmButton = {
                TextButton(onClick = {
                    backendToConfirm = null
                    select(backend)
                }) { Text(t("Use Anyway")) }
            },
            dismissButton = { TextButton(onClick = { backendToConfirm = null }) { Text(t("Cancel")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderRow(
    toolset: String,
    provider: ToolsetConfig.Provider,
    config: ToolsetConfig,
    working: String?,
    onKey: (ToolsetConfig.EnvVar) -> Unit,
    onChoose: (ToolsetConfig.Provider, String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall)
                if (provider.badge.isNotEmpty()) CapabilityPill(provider.badge, MaterialTheme.colorScheme.tertiary)
                statusPill(provider.status)?.takeIf { provider.status != "unavailable" }?.let { CapabilityPill(it.text, it.tint) }
            }
            if (working == provider.name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (provider.tag.isNotEmpty()) {
            Text(provider.tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        provider.envVars.forEach { env ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    env.key,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (env.isSet) t("Set") else t("Not set"),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (env.isSet) CapabilityColors.green else CapabilityColors.orange,
                )
                TextButton(onClick = { onKey(env) }) { Text(if (env.isSet) t("Replace") else t("Add")) }
            }
        }
        when {
            toolset == "web" && provider.capabilities.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                provider.capabilities.forEach { capability ->
                    if (config.serves(capability, provider)) {
                        InUseLabel(if (capability == "extract") t("Used for Extract") else t("Used for Search"))
                    } else {
                        CapabilityRowButton(
                            if (capability == "extract") t("Use for Extract") else t("Use for Search"),
                            enabled = working == null,
                        ) { onChoose(provider, capability) }
                    }
                }
            }
            provider.isActive -> InUseLabel(t("In use"))
            else -> CapabilityRowButton(t("Use This Backend"), enabled = working == null) { onChoose(provider, null) }
        }
    }
}

@Composable
private fun InUseLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}
