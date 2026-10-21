package app.hermesconduit.android.ui.location

import android.Manifest
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.LocationPlaceKind
import app.hermesconduit.android.model.LocationPrecision
import app.hermesconduit.android.model.LocationRead
import app.hermesconduit.android.model.LocationRule
import app.hermesconduit.android.model.LocationSavedPlace
import app.hermesconduit.android.model.LocationSharingSettings
import app.hermesconduit.android.model.LocationSyncState
import app.hermesconduit.android.model.LocationHostStatus
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
import app.hermesconduit.android.ui.device.SecondaryActionButton
import app.hermesconduit.android.ui.device.SyncSupport
import app.hermesconduit.android.ui.device.VoiceAllowDialog
import app.hermesconduit.android.ui.device.VoiceModesSection
import app.hermesconduit.android.ui.device.WarningCaption
import app.hermesconduit.android.ui.device.relativeTime
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

object LocationSettingsText {
    fun precisionTitle(precision: LocationPrecision) = L10n.tr(
        when (precision) { LocationPrecision.PLACES -> "Places only"; LocationPrecision.AREA -> "Area"; LocationPrecision.PRECISE -> "Precise" },
    )

    fun precisionSummary(precision: LocationPrecision) = L10n.tr(
        when (precision) {
            LocationPrecision.PLACES -> "Hermes hears only which of your saved places you're at, and your phone's time zone."
            LocationPrecision.AREA -> "Your neighbourhood and city, a position to about 1 km, and distances to your saved places."
            LocationPrecision.PRECISE -> "Your position as your phone measures it, and distances to your saved places."
        },
    )

    fun kindTitle(kind: LocationPlaceKind) = L10n.tr(kind.raw.replaceFirstChar { it.uppercase() })

    fun retentionTitle(days: Int) = L10n.tr(
        when (days) { 0 -> "Latest only"; 1 -> "24 hours"; 7 -> "7 days"; 30 -> "30 days"; else -> "1 year" },
    )

    fun readAction(action: String): String {
        if (action == "maps" || action.startsWith("maps ")) return L10n.tr("Looked something up in Maps")
        return when (action) {
            "now" -> L10n.tr("Checked where you are")
            "places" -> L10n.tr("Listed your places")
            "history" -> L10n.tr("Looked at where you've been")
            "distance_to" -> L10n.tr("Worked out a distance")
            "locate" -> L10n.tr("Asked your phone where you are")
            "rules" -> L10n.tr("Looked at place rules")
            else -> action
        }
    }

    fun ruleDetail(rule: LocationRule): String {
        val parts = mutableListOf<String>()
        parts += when {
            rule.isAnySavedPlace -> L10n.tr("Any saved place")
            !rule.placeName.isNullOrEmpty() -> rule.placeName
            else -> L10n.tr("A saved place")
        }
        when (rule.event) {
            "arrive" -> parts += L10n.tr("arrive")
            "leave" -> parts += L10n.tr("leave")
            "either" -> parts += L10n.tr("arrive or leave")
            else -> if (rule.event.isNotEmpty()) parts += rule.event
        }
        when (rule.repeatMode) {
            "daily" -> parts += L10n.tr("once a day")
            "every" -> parts += L10n.tr("every time")
        }
        rule.days?.let { parts += it }
        rule.hours?.let { parts += it }
        if (rule.calls) parts += L10n.tr("Calls you")
        return parts.joinToString(" · ")
    }

    fun isHermesCloud(host: String): Boolean {
        val lowered = host.lowercase()
        return lowered == "nousresearch.com" || lowered.endsWith(".nousresearch.com")
    }
}

private fun kindIcon(kind: LocationPlaceKind) = when (kind) {
    LocationPlaceKind.HOME -> Icons.Default.Home
    LocationPlaceKind.WORK -> Icons.Default.Work
    LocationPlaceKind.GYM -> Icons.Default.FitnessCenter
    LocationPlaceKind.SCHOOL -> Icons.Default.School
    LocationPlaceKind.OTHER -> Icons.Default.Place
}

/**
 * Settings > Location (iOS LocationSettingsPage): two layers, each its own
 * opt-in with its own detail, saved places, pause, how long the host keeps
 * history, every time Hermes read the location, the place rules it set up,
 * and deleting the host's copy. Nothing is shared until the user agrees on
 * the consent sheet, which comes before Android asks for permission.
 */
@Composable
fun LocationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sharing = remember { LocationSharing.of(context) }
    val scope = rememberCoroutineScope()
    val config by sharing.config.collectAsStateWithLifecycle()
    val unreadable by sharing.configUnreadable.collectAsStateWithLifecycle()
    val saveFailed by sharing.configSaveFailed.collectAsStateWithLifecycle()
    val engineState by sharing.engine.state.collectAsStateWithLifecycle()
    val engineError by sharing.engine.lastError.collectAsStateWithLifecycle()
    val permissions by sharing.permissionsVersion.collectAsStateWithLifecycle()
    val plugin by PluginStatusRepository.of(context).status.collectAsStateWithLifecycle()
    val settings = config.settings
    val pluginServes = plugin.reports(LocationSharing.CAPABILITY)
    val destination = LocationSharing.destination(context)
    val canTurnOn = pluginServes && destination != null

    var consentFor by remember { mutableStateOf<LocationSharing.Layer?>(null) }
    var editing by remember { mutableStateOf<LocationSavedPlace?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var showsWipe by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostStatus by remember { mutableStateOf<LocationHostStatus?>(null) }
    var reads by remember { mutableStateOf<List<LocationRead>>(emptyList()) }
    var rules by remember { mutableStateOf<List<LocationRule>>(emptyList()) }
    var deleting by remember { mutableStateOf(setOf<String>()) }
    var hostUnreachable by remember { mutableStateOf(false) }
    var askingMode by remember { mutableStateOf<LiveVoiceMode?>(null) }
    var pauseMenu by remember { mutableStateOf(false) }
    val voiceMapReads = remember(reads) {
        DeviceSharing.voiceReads(context, VoiceReadLog.LOCATION_KEY).entries.reversed().mapIndexed { index, entry ->
            LocationRead(app.hermesconduit.android.model.LocationTime.format(java.time.Instant.ofEpochMilli(entry.at)), entry.action, null, entry.modeName, 1_000 + index)
        }
    }

    suspend fun loadFromHost() {
        val request = if (sharing.binding != null) sharing.hostRequest() else null
        if (request == null) {
            hostUnreachable = sharing.binding != null
            return
        }
        try {
            val deviceID = config.deviceID
            hostStatus = if (app.hermesconduit.android.model.LocationIDs.isValidWireID(deviceID)) sharing.engine.status(deviceID, request) else null
            reads = sharing.engine.reads(request)
            rules = sharing.engine.rules(request)
            hostUnreachable = false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hostUnreachable = true
        }
    }

    LaunchedEffect(Unit) {
        PluginStatusRepository.of(context).status(force = false)
        sharing.retryConfigSave()
        sharing.refreshAuthorization()
        sharing.clearExpiredPause()
        loadFromHost()
    }

    fun turnOn(layer: LocationSharing.Layer) {
        val target = LocationSharing.destination(context) ?: run {
            error = L10n.tr("Connect to your Hermes dashboard first.")
            return
        }
        working = true
        error = null
        scope.launch {
            try {
                sharing.turnOn(layer, target)
                loadFromHost()
            } catch (e: Exception) {
                error = SyncSupport.describe(e)
            }
            working = false
        }
    }

    // The background layer asks for "Allow all the time" after the foreground permission.
    var pendingLayer by remember { mutableStateOf<LocationSharing.Layer?>(null) }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        sharing.refreshAuthorization()
        pendingLayer?.let(::turnOn)
        pendingLayer = null
    }
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        sharing.refreshAuthorization()
        val layer = pendingLayer ?: return@rememberLauncherForActivityResult
        if (result.values.none { it }) {
            pendingLayer = null
            error = L10n.tr("Conduit doesn't have permission to use your location. Allow it in the Settings app under Apps > Conduit > Permissions > Location.")
            return@rememberLauncherForActivityResult
        }
        if (layer == LocationSharing.Layer.BACKGROUND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sharing.fixes?.hasBackground != true) {
            backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            pendingLayer = null
            turnOn(layer)
        }
    }

    fun askAndTurnOn(layer: LocationSharing.Layer) {
        pendingLayer = layer
        val fixes = sharing.fixes
        when {
            fixes?.canLocate != true -> foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            layer == LocationSharing.Layer.BACKGROUND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !fixes.hasBackground ->
                backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> {
                pendingLayer = null
                turnOn(layer)
            }
        }
    }

    fun openAppSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    ScreenScaffold(t("Location"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Location for Hermes")) {
                Caption(t("Let Hermes know where you are, so it can answer “what's near me”, work out travel times and remind you at places. Your location goes straight from this phone to your Hermes host, sealed end to end, and never through Conduit's notification relay."))
                Caption(t("Maps lookups use this phone's geocoder, so Google sees what's looked up."))
                if (!pluginServes && !settings.isOn) WarningCaption(t("Update the Conduit plugin on your Hermes host to share your location."))
                if (saveFailed) ErrorCaption(t("Couldn't save your location settings on this phone. Your changes apply while Conduit is open, and Conduit tries saving them again when you next change something or open the app."))
                if (unreadable) {
                    WarningCaption(t("Conduit can't read its location settings right now. If this keeps happening, start over: your saved places and choices are reset."))
                    TextButton(onClick = { sharing.startOver() }) { Text(t("Start over"), color = MaterialTheme.colorScheme.error) }
                } else if (settings.isOn) {
                    config.binding?.let { SettingsRow(t("Sharing with %s on %s", it.profile, it.host), icon = Icons.Default.VerifiedUser) }
                    val paused = settings.pausedUntil
                    when {
                        paused != null && settings.isPaused(System.currentTimeMillis()) -> WarningCaption(
                            if (paused == LocationSharingSettings.UNTIL_TURNED_ON) t("Paused until you turn it back on.")
                            else t("Paused until %s.", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(paused))),
                        )
                        engineState.pause != null -> WarningCaption(LocationSyncState.message(engineState.pause!!))
                        engineState.lastFixSent != null -> Caption(t("Last sent %s", relativeTime(engineState.lastFixSent!!)))
                        else -> Caption(t("Nothing sent yet."))
                    }
                    permissions.let { _ ->
                        val fixes = sharing.fixes
                        if (fixes?.canLocate == false) {
                            WarningCaption(t("Location access is off for Conduit. Allow it in the Settings app."))
                            TextButton(onClick = ::openAppSettings) { Text(t("Open Settings")) }
                        } else if (settings.background && fixes?.hasBackground == false) {
                            WarningCaption(t("Sharing in the background needs location access set to Allow all the time. Change it in the Settings app."))
                            TextButton(onClick = ::openAppSettings) { Text(t("Open Settings")) }
                        }
                        if (fixes?.isApproximate == true) WarningCaption(t("Precise location is off for Conduit, so saved places and Precise aren't available. Area still works."))
                    }
                    if (engineError != null && engineState.pause == null) ErrorCaption(engineError!!)
                }
            }
            if (settings.isOn || pluginServes) {
                SettingsSection(t("While I'm talking to Hermes")) {
                    SettingsToggle(t("Share while I'm talking to Hermes"), settings.talking, { on ->
                        if (on) consentFor = LocationSharing.Layer.TALKING else scope.launch { sharing.turnOff(LocationSharing.Layer.TALKING); loadFromHost() }
                    }, enabled = !working && (settings.talking || canTurnOn))
                    Caption(t("A position is sent when you send a message or start a voice call in Conduit, and as you move during a call while Conduit is open. Needs location access while using the app."))
                    PrecisionPicker(settings.talkingPrecision) { p -> scope.launch { sharing.setPrecision(p, LocationSharing.Layer.TALKING); loadFromHost() } }
                }
                SettingsSection(t("In the background")) {
                    SettingsToggle(t("Share in the background"), settings.background, { on ->
                        if (on) consentFor = LocationSharing.Layer.BACKGROUND else scope.launch { sharing.turnOff(LocationSharing.Layer.BACKGROUND); loadFromHost() }
                    }, enabled = !working && (settings.background || canTurnOn))
                    Caption(t("Arriving at and leaving your saved places, and longer moves. Lets Hermes run place rules. Needs location access Allow all the time."))
                    PrecisionPicker(settings.backgroundPrecision) { p -> scope.launch { sharing.setPrecision(p, LocationSharing.Layer.BACKGROUND); loadFromHost() } }
                    if (settings.background) Caption(t("Hermes can't ask this phone for a fresh position; it uses the last one sent."))
                }
                SettingsSection(t("Saved places"), footer = t("Up to 20. Where they are stays on this phone: Hermes gets only their names, and at a private place only “at a private place”. Saved places need background sharing to report arriving and leaving.")) {
                    config.places.forEach { place ->
                        SettingsRow(
                            place.name,
                            subtitle = LocationSettingsText.kindTitle(place.kind) + " · " + "${place.radius.toInt()} m",
                            icon = kindIcon(place.kind),
                            onClick = { editing = place; editingIsNew = false },
                            trailing = if (place.isPrivate) ({ Icon(Icons.Default.Lock, t("Private")) }) else null,
                        )
                    }
                    SettingsRow(t("Add a place"), icon = Icons.Default.Add, onClick = if (config.places.size < LocationSavedPlace.MAX_COUNT) ({
                        editing = LocationSavedPlace(name = "", kind = LocationPlaceKind.HOME, latitude = Double.NaN, longitude = Double.NaN)
                        editingIsNew = true
                    }) else null)
                }
                if (settings.isOn) {
                    SettingsSection(t("Pause"), footer = t("While paused nothing is sent, Hermes is told sharing is paused, and place rules don't run.")) {
                        if (settings.isPaused(System.currentTimeMillis())) {
                            SettingsRow(t("Resume sharing"), icon = Icons.Default.PlayArrow, onClick = { scope.launch { sharing.pause(null); loadFromHost() } })
                        } else {
                            androidx.compose.foundation.layout.Box {
                                SettingsRow(t("Pause sharing"), icon = Icons.Default.Pause, onClick = { pauseMenu = true })
                                DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                                    fun pause(until: Long) {
                                        pauseMenu = false
                                        scope.launch { sharing.pause(until); loadFromHost() }
                                    }
                                    DropdownMenuItem(text = { Text(t("For 1 hour")) }, onClick = { pause(System.currentTimeMillis() + 3_600_000) })
                                    DropdownMenuItem(text = { Text(t("Until tomorrow")) }, onClick = {
                                        val zone = ZoneId.systemDefault()
                                        pause(LocalDate.now(zone).plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli())
                                    })
                                    DropdownMenuItem(text = { Text(t("Until I turn it back on")) }, onClick = { pause(LocationSharingSettings.UNTIL_TURNED_ON) })
                                }
                            }
                        }
                    }
                }
                SettingsSection(t("History on the host"), footer = t("How long the host keeps the places you've been. Your latest position is always kept until you delete it; there is never a record of every position.")) {
                    ChoiceRow(t("Keep places visited for"), settings.retentionDays, LocationSharingSettings.RETENTION_CHOICES.map { it to LocationSettingsText.retentionTitle(it) }, { days ->
                        scope.launch { sharing.setRetention(days); loadFromHost() }
                    })
                }
                SettingsSection(t("Maps"), footer = t("Hermes looks up places and routes only through this phone, never from your Hermes server. This phone can't answer Hermes' own lookups, so with this on Hermes has no maps outside a live call.")) {
                    SettingsToggle(t("Keep maps lookups on this phone"), !settings.mapsHost, { on -> scope.launch { sharing.setMapsHost(!on) } }, enabled = !working)
                }
            }
            if (settings.isOn && config.binding != null && (LocationSharing.servesLocationVoice(plugin) || config.voiceReaders.orEmpty().isNotEmpty())) {
                VoiceModesSection(
                    allowed = sharing::allowsVoiceRead,
                    onToggle = { mode, on -> if (on) askingMode = mode else scope.launch { sharing.setVoiceRead(mode, false) } },
                    explanation = t("Let a live voice call read your location when you ask about it, straight from your Hermes host: the saved place you're at, your area and distances to your saved places, never coordinates. What it reads goes to the company that runs that voice mode, under its terms. Each read is listed under When Hermes read your location. Classic voice is Hermes itself and needs nothing here."),
                    reads = VoiceReadLog(),
                    warning = if (sharing.isPaused()) t("Nothing reads your location by voice while sharing is paused.") else null,
                )
            }
            if (config.binding != null) {
                SettingsSection(t("When Hermes read your location"), footer = t("Every time Hermes reads your location it's listed here, for 30 days. The list never holds the location itself.")) {
                    if (hostUnreachable) WarningCaption(t("Couldn't reach the host."))
                    else hostStatus?.let { Caption(t("Reads in the last 24 hours: %s", it.readsToday.toString())) }
                    val shown = (reads + voiceMapReads).withIndex().sortedWith(compareByDescending<IndexedValue<LocationRead>> { it.value.date ?: java.time.Instant.EPOCH }.thenBy { it.index }).map { it.value }
                    if (shown.isEmpty()) {
                        if (!hostUnreachable) Caption(t("Hermes hasn't read your location yet."))
                    } else shown.take(50).forEach { read ->
                        val detail = listOfNotNull(
                            read.date?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.toEpochMilli())) } ?: read.at,
                            read.sessionTitle?.takeIf { it.isNotEmpty() },
                            read.platform?.takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                        SettingsRow(LocationSettingsText.readAction(read.action), subtitle = detail, icon = Icons.Default.Visibility)
                    }
                }
                SettingsSection(t("Place rules")) {
                    if (rules.isEmpty()) Caption(t("No place rules. Teach Hermes what to do at your places, for example: “When I get to the gym, remind me to log my workout.”"))
                    rules.forEach { rule ->
                        SettingsRow(rule.what, subtitle = LocationSettingsText.ruleDetail(rule), icon = Icons.Default.Schedule, trailing = {
                            IconButton(enabled = rule.id !in deleting, onClick = {
                                deleting = deleting + rule.id
                                scope.launch {
                                    val request = sharing.hostRequest()
                                    if (request != null) {
                                        try {
                                            sharing.engine.deleteRule(rule.id, request)
                                            rules = rules.filter { it.id != rule.id }
                                        } catch (e: Exception) {
                                            error = SyncSupport.describe(e)
                                        }
                                    }
                                    deleting = deleting - rule.id
                                }
                            }) { Icon(Icons.Default.Delete, t("Delete"), tint = MaterialTheme.colorScheme.error) }
                        })
                    }
                }
                SecondaryActionButton(t("Delete location data on %s", config.binding?.host ?: destination?.host.orEmpty()), enabled = !working, destructive = true) { showsWipe = true }
            }
            error?.let { ErrorCaption(it) }
        }
    }

    consentFor?.let { layer ->
        val host = if (settings.isOn) config.binding?.host.orEmpty() else destination?.host ?: config.binding?.host.orEmpty()
        ConsentDialog(
            t("Share your location with Hermes?"),
            listOf(
                Icons.Default.Lock to t("Conduit sends your location, at the detail you choose, to the %s profile on %s, sealed end to end. It never goes through Conduit's notification relay, and Conduit's developer can't see it.", DeviceSharing.activeProfile(context), host),
                Icons.Default.Schedule to t("Your phone's time zone is always sent too, at every level of detail, so Hermes knows your local time. At Area and Precise, so are distances to your saved places, except private ones."),
                Icons.Default.AutoAwesome to t("Your agent reads it when you ask, or when a task or place rule needs it. Whatever it reads goes to the AI model provider your Hermes uses, under that provider's terms."),
                Icons.Default.Cloud to if (LocationSettingsText.isHermesCloud(host)) t("This Hermes runs on Hermes Cloud, so Nous Research hosts what Conduit sends.")
                    else t("If your Hermes is hosted by someone else, such as Hermes Cloud, they can read it too."),
                Icons.Default.Visibility to t("Every read is listed in Settings > Location. You can pause sharing, turn it off and delete what was sent at any time."),
                Icons.Default.PanTool to t("Your location is never used for advertising and isn't stored in the cloud by Conduit."),
            ),
            onAccept = {
                consentFor = null
                askAndTurnOn(layer)
            },
            onCancel = { consentFor = null },
        )
    }
    editing?.let { draft ->
        PlaceEditor(
            draft = draft,
            isNew = editingIsNew,
            sharing = sharing,
            save = { place ->
                editing = null
                scope.launch { sharing.savePlace(place); loadFromHost() }
            },
            delete = { id ->
                editing = null
                scope.launch { sharing.removePlace(id); loadFromHost() }
            },
            cancel = { editing = null },
        )
    }
    if (showsWipe) {
        AlertDialog(
            onDismissRequest = { showsWipe = false },
            title = { Text(t("Delete location data on the host?")) },
            text = { Text(t("Hermes can no longer read what was deleted. Sharing stays as it is; turn the layers off first to stop sending.")) },
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    fun wipe(everything: Boolean) {
                        showsWipe = false
                        working = true
                        error = null
                        scope.launch {
                            try {
                                sharing.wipe(everything)
                                reads = emptyList()
                                if (everything) rules = emptyList()
                                loadFromHost()
                            } catch (e: Exception) {
                                error = SyncSupport.describe(e)
                            }
                            working = false
                        }
                    }
                    TextButton(onClick = { wipe(false) }) { Text(t("Delete this phone's location data"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { wipe(true) }) { Text(t("Delete everything, including place rules"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showsWipe = false }) { Text(t("Cancel")) }
                }
            },
        )
    }
    askingMode?.let { mode ->
        VoiceAllowDialog(t("Let %s read your location?", mode.displayName), mode, onAllow = {
            askingMode = null
            scope.launch { sharing.setVoiceRead(mode, true) }
        }, onCancel = { askingMode = null })
    }
}

@Composable
private fun PrecisionPicker(value: LocationPrecision, onSelect: (LocationPrecision) -> Unit) {
    ChoiceRow(t("Detail"), value, LocationPrecision.entries.map { it to LocationSettingsText.precisionTitle(it) }, onSelect)
    Caption(LocationSettingsText.precisionSummary(value))
}

/** Adds or edits a saved place: the current position or an address, a name, a kind, a radius and Private. */
@Composable
private fun PlaceEditor(
    draft: LocationSavedPlace,
    isNew: Boolean,
    sharing: LocationSharing,
    save: (LocationSavedPlace) -> Unit,
    delete: (String) -> Unit,
    cancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(draft.name) }
    var kind by remember { mutableStateOf(draft.kind) }
    var isPrivate by remember { mutableStateOf(draft.isPrivate) }
    var latitude by remember { mutableStateOf(draft.latitude) }
    var longitude by remember { mutableStateOf(draft.longitude) }
    var radius by remember { mutableStateOf(draft.radius.toFloat()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Address>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    val hasCoordinate = !latitude.isNaN() && !longitude.isNaN()
    val place = if (hasCoordinate && name.trim().isNotEmpty()) {
        LocationSavedPlace(draft.id, name.trim(), kind, isPrivate, latitude, longitude, radius.toDouble()).normalized()
    } else null

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(if (isNew) t("Add a place") else t("Edit place")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(LocationSavedPlace.MAX_NAME_LENGTH) }, label = { Text(t("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow(t("Kind"), kind, LocationPlaceKind.entries.map { it to LocationSettingsText.kindTitle(it) }, { kind = it })
                SettingsToggle(t("Private"), isPrivate, { isPrivate = it })
                Text(t("At a private place Hermes hears only “at a private place”, never its name or where it is, and your visits there aren't kept."), style = MaterialTheme.typography.bodySmall)
                Text(t("Where"), style = MaterialTheme.typography.titleSmall)
                if (hasCoordinate) Text("%.5f, %.5f".format(Locale.US, latitude, longitude), style = MaterialTheme.typography.bodySmall)
                if (sharing.fixes?.canLocate == true) {
                    TextButton(enabled = !locating, onClick = {
                        locating = true
                        message = null
                        scope.launch {
                            val fix = sharing.fixes.currentFix(LocationPrecision.PRECISE, 30)
                            if (fix != null) {
                                latitude = fix.latitude
                                longitude = fix.longitude
                            } else message = L10n.tr("Couldn't find your position. Try searching for the address.")
                            locating = false
                        }
                    }) { Text(if (locating) t("Finding you…") else t("Use my current location")) }
                }
                OutlinedTextField(
                    query, { query = it }, label = { Text(t("Search for an address")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val text = query.trim()
                        if (text.isEmpty()) return@KeyboardActions
                        scope.launch {
                            results = withContext(Dispatchers.IO) {
                                @Suppress("DEPRECATION")
                                runCatching { if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()).getFromLocationName(text, 8).orEmpty() else emptyList() }.getOrDefault(emptyList())
                            }
                            message = if (results.isEmpty()) L10n.tr("No matches.") else null
                        }
                    }),
                )
                results.forEach { address ->
                    SettingsRow(address.featureName ?: address.getAddressLine(0).orEmpty(), subtitle = address.getAddressLine(0), onClick = {
                        latitude = address.latitude
                        longitude = address.longitude
                        if (name.isBlank()) name = (address.featureName ?: "").take(LocationSavedPlace.MAX_NAME_LENGTH)
                        results = emptyList()
                    })
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(t("Radius: %s", "${radius.toInt()} m"), style = MaterialTheme.typography.bodyMedium)
                Slider(radius, { radius = (Math.round(it / 25f) * 25f) }, valueRange = LocationSavedPlace.MIN_RADIUS.toFloat()..LocationSavedPlace.MAX_RADIUS.toFloat())
                Text(t("Arriving and leaving count once you cross this circle. A larger circle notices you sooner but can miss short visits."), style = MaterialTheme.typography.bodySmall)
                if (!isNew) TextButton(onClick = { delete(draft.id) }) { Text(t("Delete place"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = place != null, onClick = { place?.let(save) }) { Text(t("Save")) } },
        dismissButton = { TextButton(onClick = cancel) { Text(t("Cancel")) } },
    )
}
