package app.hermesconduit.android.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.model.CapabilityFormat
import app.hermesconduit.android.model.CapabilityInstallState
import app.hermesconduit.android.model.CapabilitySkillOrdering
import app.hermesconduit.android.model.SkillFrontmatter
import kotlinx.coroutines.CancellationException

/** Learned (written by the agent) and Hub skills say so; built-in ones carry nothing, as on Desktop. */
private fun provenancePill(provenance: String?): PillModel? = when (provenance) {
    "agent" -> PillModel(L10n.tr("Learned"), CapabilityColors.green)
    "hub" -> PillModel(L10n.tr("Hub"))
    else -> null
}

/**
 * Settings > Capabilities > Skills: the profile's skills, most used first,
 * each with its switch and page (SKILL.md), then the official catalog.
 */
@Composable
fun SkillsTab(vm: CapabilitiesViewModel, state: CapabilitiesState, query: String, banner: String?, open: (CapabilityPage) -> Unit) {
    val settings = LocalContext.current.appSettings
    val mostUsedFirst by settings.bool(AppSettings.CAPABILITIES_SKILLS_MOST_USED, true).collectAsStateWithLifecycle()
    val installed = CapabilitySkillOrdering.sorted(state.skills.filter { CapabilitySkillOrdering.matches(it, query) }, mostUsedFirst)
    val available = CapabilitySkillOrdering.available(state.officialSkills, state.skills.map { it.name }.toSet(), query)

    LaunchedEffect(state.profile) {
        if (state.officialSkills.isEmpty()) vm.loadOfficialSkills()
    }

    CapabilityTabList(refresh = {
        vm.refreshCapabilities()
        vm.loadOfficialSkills()
    }) {
        if (banner != null) item { CapabilityRefreshBanner(banner) }
        state.toggleError?.let { item { FootnoteText(it, MaterialTheme.colorScheme.error) } }
        if (installed.isEmpty() && available.isEmpty()) {
            item {
                CapabilityEmptyState(
                    title = if (query.isEmpty()) t("No skills yet") else t("No matching skills"),
                    message = if (query.isEmpty()) null else t("Nothing matches “%s”.", query),
                    icon = Icons.Default.Extension,
                )
            }
        }
        if (installed.isNotEmpty()) {
            item(key = "installed") {
                CapabilitySection(
                    title = t("Installed"),
                    footer = t("Changes apply to new sessions."),
                    accessory = {
                        OptionsMenu(
                            label = t("Skill options"),
                            busy = state.bulkRunning,
                            mostUsedFirst = mostUsedFirst,
                            onSort = { settings.set(AppSettings.CAPABILITIES_SKILLS_MOST_USED, it) },
                            onAllOn = { vm.setAllSkills(state.skills.map { it.name }, true) },
                            onAllOff = { vm.setAllSkills(state.skills.map { it.name }, false) },
                            onOffUnused = { vm.setAllSkills(state.skills.filter { (it.usage ?: 0) == 0 }.map { it.name }, false) },
                        )
                    },
                ) {
                    installed.forEachIndexed { index, skill ->
                        if (index > 0) CapabilityRowDivider()
                        CapabilityRow(
                            icon = CapabilityIcons.skill(skill.provenance),
                            active = skill.enabled,
                            title = skill.name,
                            subtitle = CapabilitySkillOrdering.prettyCategory(skill.category),
                            pills = listOfNotNull(provenancePill(skill.provenance)),
                            detail = skill.description,
                            meta = (skill.usage ?: 0).takeIf { it > 0 }?.let(CapabilityFormat::uses),
                            onClick = { open(CapabilityPage.Skill(skill.name)) },
                        ) {
                            CapabilitySwitch(skill.enabled, { vm.toggleSkill(skill.name, it) })
                        }
                    }
                }
            }
        }
        if (available.isNotEmpty()) {
            item(key = "available") {
                CapabilitySection(
                    title = t("Available to install"),
                    footer = t("From Hermes' official skills catalog. Hermes scans each skill before it installs."),
                ) {
                    available.forEachIndexed { index, skill ->
                        if (index > 0) CapabilityRowDivider()
                        val install = state.skillInstalls[skill.identifier]
                        CapabilityRow(
                            icon = Icons.Default.Download,
                            active = false,
                            title = skill.name,
                            subtitle = CapabilitySkillOrdering.prettyCategory(skill.category),
                            detail = skill.description,
                            onClick = { open(CapabilityPage.Official(skill.identifier)) },
                        ) {
                            CapabilityRowButton(t("Install"), busy = install == CapabilityInstallState.Running) {
                                vm.clearSkillInstallFailure(skill.identifier)
                                vm.installSkill(skill)
                            }
                        }
                        if (install is CapabilityInstallState.Failed) InstallFailure(install.reason)
                    }
                }
            }
        }
    }
}

@Composable
fun InstallFailure(reason: String) {
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 55.dp, end = 14.dp, bottom = 8.dp),
    )
}

/** Sort order and the bulk switches of the Skills and Tools sections. */
@Composable
fun OptionsMenu(
    label: String,
    busy: Boolean,
    mostUsedFirst: Boolean,
    onSort: (Boolean) -> Unit,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onOffUnused: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.MoreHoriz, label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(t("Sort"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            listOf(true to t("Most used first"), false to t("Least used first")).forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { if (value == mostUsedFirst) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = {
                        expanded = false
                        onSort(value)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(t("Turn All On")) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }, onClick = {
                expanded = false
                onAllOn()
            })
            DropdownMenuItem(text = { Text(t("Turn All Off")) }, leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, null) }, onClick = {
                expanded = false
                onAllOff()
            })
            if (onOffUnused != null) {
                DropdownMenuItem(text = { Text(t("Turn Off Unused")) }, leadingIcon = { Icon(Icons.Default.Bedtime, null) }, onClick = {
                    expanded = false
                    onOffUnused()
                })
            }
        }
    }
}

/** A skill's page: what it is, its switch, and its SKILL.md. */
@Composable
fun SkillDetailPage(vm: CapabilitiesViewModel, state: CapabilitiesState, name: String, onBack: () -> Unit) {
    val skill = state.skills.firstOrNull { it.name == name }
    var document by remember(name) { mutableStateOf<SkillFrontmatter?>(null) }
    var error by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        try {
            document = SkillFrontmatter.parse(vm.skillContent(name))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    CapabilityDetailScaffold(name, onBack) {
        if (skill != null) {
            SkillHeader(
                title = skill.name,
                description = skill.description,
                pills = listOfNotNull(PillModel(CapabilitySkillOrdering.prettyCategory(skill.category)), provenancePill(skill.provenance)),
                usage = skill.usage,
            ) {
                TurnedOnRow(skill.enabled) { vm.toggleSkill(skill.name, it) }
            }
        }
        SkillDocument(document, error)
        FootnoteText(t("Changes apply to new sessions."))
    }
}

/** A catalog skill's page before it's installed: its SKILL.md and Install. */
@Composable
fun OfficialSkillDetailPage(vm: CapabilitiesViewModel, state: CapabilitiesState, identifier: String, onBack: () -> Unit) {
    val skill = state.officialSkills.firstOrNull { it.identifier == identifier }
    var document by remember(identifier) { mutableStateOf<SkillFrontmatter?>(null) }
    var error by remember(identifier) { mutableStateOf<String?>(null) }
    LaunchedEffect(identifier) {
        try {
            document = SkillFrontmatter.parse(vm.skillPreview(identifier))
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    val install = state.skillInstalls[identifier]
    CapabilityDetailScaffold(skill?.name ?: identifier, onBack) {
        if (skill != null) {
            SkillHeader(
                title = skill.name,
                description = skill.description,
                pills = listOf(
                    PillModel(CapabilitySkillOrdering.prettyCategory(skill.category)),
                    PillModel(t("Official"), MaterialTheme.colorScheme.tertiary),
                ),
                usage = null,
            ) {
                if (skill.installed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = CapabilityColors.green)
                        Text(t("Installed"), style = MaterialTheme.typography.titleSmall, color = CapabilityColors.green)
                    }
                } else {
                    val running = install == CapabilityInstallState.Running
                    CapabilityWideButton(if (running) t("Installing…") else t("Install"), Icons.Default.Download, enabled = !running) {
                        vm.clearSkillInstallFailure(identifier)
                        vm.installSkill(skill)
                    }
                    if (install is CapabilityInstallState.Failed) {
                        Text(install.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        SkillDocument(document, error)
    }
}

/** A skill page's top card: its name, what it does, its pills, and the control. */
@Composable
private fun SkillHeader(title: String, description: String?, pills: List<PillModel>, usage: Int?, control: @Composable () -> Unit) {
    CapabilityCard {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            if (description.isNullOrEmpty()) t("No description.") else description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            pills.forEach { CapabilityPill(it.text, it.tint) }
            if (usage != null && usage > 0) {
                Text(CapabilityFormat.uses(usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        control()
    }
}

/** A SKILL.md: its frontmatter as rows, then its body as written. */
@Composable
private fun SkillDocument(document: SkillFrontmatter?, error: String?) {
    when {
        error != null -> FootnoteText(error)
        document != null -> {
            // The name and description are in the card above.
            val fields = document.fields.filter { it.key != "name" && it.key != "description" }
            if (fields.isNotEmpty()) {
                CapabilityDetailSection(t("Details")) {
                    fields.forEach { CapabilityMetric(it.key, it.value) }
                }
            }
            if (document.body.isNotEmpty()) {
                CapabilityDetailSection(t("Instructions")) {
                    SelectionContainer {
                        Text(
                            document.body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
