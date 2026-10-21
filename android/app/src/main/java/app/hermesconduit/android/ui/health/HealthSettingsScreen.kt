package app.hermesconduit.android.ui.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.HealthCategory
import app.hermesconduit.android.model.HealthHistoryDepth
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.device.Caption
import app.hermesconduit.android.ui.device.ChoiceRow
import app.hermesconduit.android.ui.device.ConsentDialog
import app.hermesconduit.android.ui.device.DeviceSharing
import app.hermesconduit.android.ui.device.ErrorCaption
import app.hermesconduit.android.ui.device.PrimaryActionButton
import app.hermesconduit.android.ui.device.SecondaryActionButton
import app.hermesconduit.android.ui.device.SyncSupport
import app.hermesconduit.android.ui.device.TurnOffDialog
import app.hermesconduit.android.ui.device.VoiceAllowDialog
import app.hermesconduit.android.ui.device.VoiceModesSection
import app.hermesconduit.android.ui.device.WarningCaption
import app.hermesconduit.android.ui.device.relativeTime
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun icon(category: HealthCategory) = when (category) {
    HealthCategory.ACTIVITY -> Icons.Default.DirectionsWalk
    HealthCategory.HEART -> Icons.Default.FavoriteBorder
    HealthCategory.SLEEP -> Icons.Default.Bed
    HealthCategory.WORKOUTS -> Icons.Default.DirectionsRun
    HealthCategory.BODY -> Icons.Default.MonitorWeight
    HealthCategory.VITALS -> Icons.Default.MonitorHeart
    HealthCategory.FITNESS -> Icons.Default.Air
}

/**
 * Settings > Health (iOS HealthSettingsPage): opt-in sharing of Health
 * Connect data with one Hermes profile. Off until the user picks what to
 * share and agrees on the consent sheet; turning it off can delete the
 * host's copy.
 */
@Composable
fun HealthSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { HealthSync.of(context) }
    val engine = sync.engine
    val scope = rememberCoroutineScope()
    val preferences by sync.preferences.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val syncing by engine.isSyncing.collectAsStateWithLifecycle()
    val lastError by engine.lastError.collectAsStateWithLifecycle()
    val plugin by PluginStatusRepository.of(context).status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PluginStatusRepository.of(context).status(force = false) }
    val pluginServes = plugin.reports(HealthSync.CAPABILITY)
    val available = remember { sync.source.isAvailable }

    var categories by remember { mutableStateOf(if (preferences.isOn) preferences.categorySet else setOf(HealthCategory.ACTIVITY, HealthCategory.HEART, HealthCategory.SLEEP, HealthCategory.WORKOUTS)) }
    var detailed by remember { mutableStateOf(preferences.detailedSet) }
    var history by remember { mutableStateOf(preferences.historyDepth) }
    var showsConsent by remember { mutableStateOf(false) }
    var showsTurnOff by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askingMode by remember { mutableStateOf<LiveVoiceMode?>(null) }
    var voiceReads by remember { mutableStateOf(DeviceSharing.voiceReads(context, VoiceReadLog.HEALTH_KEY)) }

    // After Health Connect's permission sheet: on with what was granted (pending: true = turning on).
    var pendingTurnOn by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val wanted = sync.source.permissions(categories)
        scope.launch {
            if (granted.none { it in wanted } && wanted.isNotEmpty()) {
                error = t_("Conduit doesn't have access to Health Connect. Allow it in Health Connect's settings.")
                working = false
                pendingTurnOn = false
                return@launch
            }
            try {
                if (pendingTurnOn) sync.turnOn(categories, detailed, history) else sync.update(categories, detailed, history)
                error = null
            } catch (e: Exception) {
                error = SyncSupport.describe(e)
            }
            working = false
            pendingTurnOn = false
        }
    }

    fun askPermissionsThen(turningOn: Boolean) {
        pendingTurnOn = turningOn
        working = turningOn
        runCatching { permissionLauncher.launch(sync.source.permissions(categories)) }.onFailure {
            working = false
            error = SyncSupport.describe(it)
        }
    }

    fun applyIfOn(newCategories: Boolean) {
        if (!preferences.isOn) return
        if (newCategories) askPermissionsThen(false) else scope.launch { sync.update(categories, detailed, history) }
    }

    ScreenScaffold(t("Health"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Health data for Hermes")) {
                Caption(t("Share Health Connect data with Hermes so your agent can answer questions about your activity, sleep and workouts. It goes straight from this phone to your Hermes host, sealed end to end, and never through Conduit's notification relay."))
                when {
                    !available -> WarningCaption(t("Health Connect isn't available on this device."))
                    preferences.isOn -> {
                        preferences.destination?.let { SettingsRow(t("Sharing with %s on %s", it.profile, it.host), icon = Icons.Default.VerifiedUser) }
                        when {
                            syncing -> Caption(t("Syncing…"))
                            state.pause != null -> WarningCaption(state.pause!!.healthMessage())
                            state.lastSync != null -> Caption(t("Last synced %s", relativeTime(state.lastSync!!)))
                            else -> Caption(t("Not synced yet."))
                        }
                        val cursor = state.backfillCursor
                        val start = state.historyStart
                        if (cursor != null && start != null && cursor > start) {
                            Caption(t("Bringing in history: back to %s so far.", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(cursor))))
                        }
                        if (lastError != null && state.pause == null) ErrorCaption(lastError!!)
                    }
                    !pluginServes -> WarningCaption(t("Update the Conduit plugin on your Hermes host to share health data."))
                }
            }
            if (available && (preferences.isOn || pluginServes)) {
                SettingsSection(
                    t("What to share"),
                    footer = t("Hermes gets hourly and daily summaries computed by Health Connect, a summary of each night's sleep, and each workout. Detailed readings add every individual measurement, such as each heart rate reading.") +
                        if (preferences.isOn) "\n\n" + t("Unticking a category or shortening the history only stops new uploads. What was already sent stays on the host until you turn off sharing and delete it.") else "",
                ) {
                    HealthCategory.entries.forEach { category ->
                        Column {
                            SettingsToggle(
                                t(category.title),
                                category in categories,
                                { on ->
                                    val next = if (on) categories + category else categories - category
                                    // Sharing on with nothing chosen is sharing off: use the button.
                                    if (!(preferences.isOn && next.isEmpty())) {
                                        categories = next
                                        if (!on) detailed = detailed - category
                                        applyIfOn(on)
                                    }
                                },
                                subtitle = t(category.summary),
                                icon = icon(category),
                                enabled = !(preferences.isOn && categories == setOf(category)),
                            )
                            if (category.hasDetailedReadings && category in categories) {
                                Column(Modifier.padding(start = 28.dp)) {
                                    SettingsToggle(t("Include detailed readings"), category in detailed, { on ->
                                        detailed = if (on) detailed + category else detailed - category
                                        applyIfOn(false)
                                    })
                                }
                            }
                        }
                    }
                }
                SettingsSection(t("History"), footer = t("How far back the first sync reaches. A long history comes in over a few days in the background. Hourly detail covers the last 30 days; older history is daily.")) {
                    ChoiceRow(t("Share history from"), history, HealthHistoryDepth.entries.map { it to t(it.title) }, { history = it; applyIfOn(false) })
                }
            }
            if (preferences.isOn && plugin.reports(VOICE_TOOLS_CAPABILITY)) {
                VoiceModesSection(
                    allowed = preferences::letsVoiceRead,
                    onToggle = { mode, on -> if (on) askingMode = mode else sync.setVoiceRead(mode, false) },
                    explanation = t("Let a live voice call read your health data when you ask about it, straight from your Hermes host. What it reads goes to the company that runs that voice mode, under its terms. Classic voice is Hermes itself and needs nothing here."),
                    reads = voiceReads,
                )
            }
            if (preferences.isOn || (available && pluginServes)) {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    if (preferences.isOn) {
                        SecondaryActionButton(t("Sync now"), enabled = !syncing && !working) {
                            scope.launch {
                                sync.run(HealthSyncReason.MANUAL)
                                error = if (DeviceSharing.activeDashboardID(context) == null) t_("Connect to your Hermes dashboard first.") else null
                            }
                        }
                        SecondaryActionButton(t("Turn off sharing"), enabled = !working, destructive = true) { showsTurnOff = true }
                    } else {
                        PrimaryActionButton(if (working) t("Turning on…") else t("Turn on sharing"), enabled = categories.isNotEmpty() && !working && DeviceSharing.activeDashboardID(context) != null) {
                            showsConsent = true
                        }
                    }
                    error?.let { ErrorCaption(it) }
                    if (available) {
                        androidx.compose.material3.TextButton(onClick = {
                            runCatching { context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        }) { Text(t("Open Health Connect")) }
                    }
                }
            }
        }
    }

    if (showsConsent) {
        val profile = DeviceSharing.activeProfile(context)
        val host = DeviceSharing.activeHost(context)
        ConsentDialog(
            t("Share health data with Hermes?"),
            listOf(
                Icons.Default.Lock to t("Conduit sends the health data you chose to the %s profile on %s, sealed end to end. It never goes through Conduit's notification relay, and Conduit's developer can't see it.", profile, host),
                Icons.Default.AutoAwesome to t("Your agent reads it when you ask, or when a task needs it. Whatever it reads goes to the AI model provider your Hermes uses, under that provider's terms. If your Hermes is hosted by someone else, such as Hermes Cloud, they can read it too."),
                Icons.Default.PanTool to t("Health data is never used for advertising and is not stored in the cloud by Conduit."),
                Icons.Default.HeartBroken to t("You can turn sharing off at any time in Settings > Health and delete what was sent from the host."),
            ),
            onAccept = {
                showsConsent = false
                askPermissionsThen(true)
            },
            onCancel = { showsConsent = false },
        )
    }
    if (showsTurnOff) {
        TurnOffDialog(
            t("Turn off health sharing?"),
            t("Conduit stops sending health data. Deleting removes what this phone sent from the host; Hermes can no longer read it."),
            onDelete = { showsTurnOff = false; turnOff(scope, sync, true, { working = it }, { error = it }); voiceReads = VoiceReadLog() },
            onKeep = { showsTurnOff = false; turnOff(scope, sync, false, { working = it }, { error = it }); voiceReads = VoiceReadLog() },
            onCancel = { showsTurnOff = false },
        )
    }
    askingMode?.let { mode ->
        VoiceAllowDialog(t("Let %s read your health data?", mode.displayName), mode, onAllow = {
            askingMode = null
            sync.setVoiceRead(mode, true)
        }, onCancel = { askingMode = null })
    }
}

const val VOICE_TOOLS_CAPABILITY = "voice-tools"

private fun t_(key: String) = app.hermesconduit.android.core.L10n.tr(key)

private fun turnOff(
    scope: kotlinx.coroutines.CoroutineScope,
    sync: HealthSync,
    delete: Boolean,
    working: (Boolean) -> Unit,
    error: (String?) -> Unit,
) {
    working(true)
    error(null)
    scope.launch {
        val done = sync.turnOff(delete)
        if (!done) error(t_("Sharing is off. The host couldn't be reached, so Conduit deletes the data there the next time it connects."))
        working(false)
    }
}
