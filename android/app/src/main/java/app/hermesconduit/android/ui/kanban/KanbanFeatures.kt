package app.hermesconduit.android.ui.kanban

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.nav.Feature

/** Pushed Kanban sub-screens (not in the drawer). Routes with an argument use `route/{arg}`. */
fun kanbanSubFeatures(): Array<Feature> {
    val icon = Icons.Default.ViewKanban
    return arrayOf(
        Feature("kanban.task", "Task", icon, inDrawer = false) { back, nav -> KanbanTaskDetailScreen(back, nav) },
        Feature("kanban.new", "New task", icon, inDrawer = false) { back, _ -> KanbanComposerScreen(back) },
        Feature("kanban.log", "Worker Log", icon, inDrawer = false) { back, _ -> KanbanWorkerLogScreen(back) },
        Feature("kanban.board", "Board Settings", icon, inDrawer = false) { back, _ -> KanbanBoardEditorScreen(back) },
        Feature("kanban.orchestration", "Orchestration Settings", icon, inDrawer = false) { back, _ -> KanbanOrchestrationScreen(back) },
        Feature("kanban.profiles", "Profiles", icon, inDrawer = false) { back, nav -> KanbanProfileRoutingScreen(back, nav) },
        Feature("kanban.profile", "Routing Description", icon, inDrawer = false) { back, _ -> KanbanProfileDescriptionScreen(back) },
    )
}

/** Board filters: assignee, tenant, Show Archived (server fetch flag), Group Running by Profile (saved). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanFilterSheet(
    assignees: List<String>,
    tenants: List<String>,
    includeArchived: Boolean,
    assigneeFilter: String?,
    tenantFilter: String?,
    groupRunning: Boolean,
    onIncludeArchived: (Boolean) -> Unit,
    onAssignee: (String?) -> Unit,
    onTenant: (String?) -> Unit,
    onGroupRunning: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(t("Filters"), style = MaterialTheme.typography.titleLarge)
            KanbanSection(t("Assignee")) {
                KanbanPickerRow(
                    label = t("Assignee"),
                    value = assigneeFilter ?: t("All Profiles"),
                    options = listOf<Pair<String?, String>>(null to t("All Profiles")) + assignees.map { it to it },
                    onSelect = onAssignee,
                    isSelected = { it == assigneeFilter },
                )
            }
            if (tenants.isNotEmpty()) {
                KanbanSection(t("Tenant")) {
                    KanbanPickerRow(
                        label = t("Tenant"),
                        value = tenantFilter ?: t("All Tenants"),
                        options = listOf<Pair<String?, String>>(null to t("All Tenants")) + tenants.map { it to it },
                        onSelect = onTenant,
                        isSelected = { it == tenantFilter },
                    )
                }
            }
            KanbanSection(
                null,
                footer = t("Group Running by Profile is a saved preference. Show Archived applies to the board fetch. Assignee and tenant filters are temporary until changed."),
            ) {
                SettingsToggle(t("Show Archived"), includeArchived, onIncludeArchived)
                SettingsToggle(t("Group Running by Profile"), groupRunning, onGroupRunning)
            }
            TextButton(onClick = onReset) { Text(t("Reset Filters")) }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 16.dp)) { Text(t("Done")) }
        }
    }
}
