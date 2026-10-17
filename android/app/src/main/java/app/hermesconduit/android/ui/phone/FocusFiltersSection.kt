package app.hermesconduit.android.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.HermesFocusFilter
import app.hermesconduit.android.model.HermesFocusRules
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.device.Caption
import app.hermesconduit.android.ui.device.ChoiceRow
import kotlinx.coroutines.delay

/**
 * Do Not Disturb & Modes: the "Tell Hermes" filters iOS adds in Settings >
 * Focus > Focus Filters, set up here since Android has none. Each says, for
 * any Do Not Disturb or for Conduit's own mode, what Hermes is told and
 * whether it holds its calls. [changed] runs once edits settle.
 */
@Composable
fun FocusFiltersSection(changed: () -> Unit) {
    val context = LocalContext.current
    val filters by PhoneModes.filters(context).collectAsStateWithLifecycle()
    var edits by remember { mutableIntStateOf(0) }
    var hasMode by remember { mutableStateOf(PhoneModes.hasConduitMode(context)) }
    var canAddModes by remember { mutableStateOf(PhoneModes.canAddModes(context)) }
    LifecycleResumeEffect(Unit) {
        // Back from the system's settings: what they allow now.
        hasMode = PhoneModes.hasConduitMode(context)
        canAddModes = PhoneModes.canAddModes(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(1_500)
        changed()
    }
    fun save(list: List<HermesFocusFilter>) {
        PhoneModes.saveFilters(context, list)
        edits += 1
    }
    fun update(filter: HermesFocusFilter) = save(filters.map { if (it.id == filter.id) filter else it })

    SettingsRow(t("Do Not Disturb & Modes"), icon = Icons.Default.Tune)
    Caption(t("Add a filter for Do Not Disturb or a mode, give it a name for Hermes such as Work, and choose whether Hermes holds its calls while it's on."))
    val modes = buildList {
        add(HermesFocusFilter.ANY to t("Any Do Not Disturb"))
        if (hasMode || filters.any { it.mode == HermesFocusFilter.CONDUIT_MODE }) add(HermesFocusFilter.CONDUIT_MODE to t("Conduit mode"))
    }
    filters.forEach { filter ->
        FilterRows(filter, modes, update = ::update, remove = { save(filters.filter { it.id != filter.id }) })
    }
    if (filters.isEmpty()) Caption(t("Without a filter, Hermes hears only whether Do Not Disturb is on."))
    if (filters.size < HermesFocusFilter.MAXIMUM) {
        SettingsRow(t("Add a filter"), icon = Icons.Default.Add, onClick = {
            val mode = if (hasMode && filters.none { it.mode == HermesFocusFilter.CONDUIT_MODE }) HermesFocusFilter.CONDUIT_MODE else HermesFocusFilter.ANY
            save(filters + HermesFocusFilter(SealCrypto.compactUuid(), mode))
        })
    }
    if (PhoneModes.supportsConduitMode) {
        if (hasMode) {
            Caption(t("Turn the Conduit mode on or schedule it in Settings > Modes, and Hermes hears the name you gave it."))
            SettingsRow(t("Remove Conduit from Modes"), icon = Icons.Default.DoNotDisturbOn, onClick = {
                PhoneModes.removeConduitMode(context)
                hasMode = PhoneModes.hasConduitMode(context)
            })
        } else {
            SettingsRow(t("Add Conduit to Modes"), icon = Icons.Default.DoNotDisturbOn, onClick = {
                if (!canAddModes) {
                    runCatching { context.startActivity(PhoneModes.policyAccessIntent()) }
                } else {
                    PhoneModes.addConduitMode(context)
                    hasMode = PhoneModes.hasConduitMode(context)
                }
            })
            Caption(
                if (canAddModes) t("A Conduit mode in Settings > Modes lets Hermes hear a name of its own, apart from Do Not Disturb.")
                else t("Allow Conduit to manage Do Not Disturb, then come back to add its mode."),
            )
        }
    }
}

@Composable
private fun FilterRows(filter: HermesFocusFilter, modes: List<Pair<String, String>>, update: (HermesFocusFilter) -> Unit, remove: () -> Unit) {
    var label by remember(filter.id) { mutableStateOf(filter.label) }
    Column(Modifier.padding(start = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ChoiceRow(t("Mode"), filter.mode, modes, { update(filter.copy(mode = it)) })
            }
            IconButton(onClick = remove) { Icon(Icons.Default.RemoveCircle, t("Remove"), tint = MaterialTheme.colorScheme.error) }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { text ->
                label = text.take(HermesFocusFilter.LABEL_LENGTH)
                update(filter.copy(label = HermesFocusRules.cleanLabel(label)))
            },
            label = { Text(t("Name for Hermes")) },
            placeholder = { Text(t("Work")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingsToggle(t("Hold calls from Hermes"), filter.holdCalls, { update(filter.copy(holdCalls = it)) })
    }
}
