package app.hermesconduit.android.ui.phone

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PhoneAutoAdded
import app.hermesconduit.android.model.PhoneCalendarAccess
import app.hermesconduit.android.model.PhoneCalendarWindow
import app.hermesconduit.android.model.PhoneChangePrompt
import app.hermesconduit.android.model.PhoneChangeWording
import app.hermesconduit.android.model.PhonePerson
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.model.str
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
import app.hermesconduit.android.ui.health.VOICE_TOOLS_CAPABILITY
import app.hermesconduit.android.ui.plugin.PluginStatusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings > Calendar & phone (iOS PhoneSettingsPage): opt-in sharing of the
 * calendar, Do Not Disturb, phone status, alarms and picked people with one
 * Hermes profile, and how Hermes' calendar changes are handled.
 */
@Composable
fun PhoneSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { PhoneSync.of(context) }
    val engine = sync.engine
    val scope = rememberCoroutineScope()
    val preferences by engine.preferences.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val syncing by engine.isSyncing.collectAsStateWithLifecycle()
    val lastError by engine.lastError.collectAsStateWithLifecycle()
    val people by engine.people.collectAsStateWithLifecycle()
    val plugin by PluginStatusRepository.of(context).status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PluginStatusRepository.of(context).status(force = false) }
    val pluginServes = plugin.reports(PhoneSync.CAPABILITY)

    var choices by remember { mutableStateOf(if (preferences.isOn) preferences else PhoneSyncPreferences.SUGGESTED) }
    var showsConsent by remember { mutableStateOf(false) }
    var showsTurnOff by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askingMode by remember { mutableStateOf<LiveVoiceMode?>(null) }
    var voiceUses by remember { mutableStateOf(DeviceSharing.voiceReads(context, VoiceReadLog.PHONE_KEY)) }
    var calendarsOpen by remember { mutableStateOf(false) }
    val tasks = remember { (engine.calendarSource as? CalendarContractSource)?.tasks ?: TaskProviderReminders(context) }
    val taskApps = remember(state.lastSync, working) { tasks.installed }
    val showsAlarms = (engine.alarmSource.isSupported || choices.alarms) && (plugin.reports(PhoneSync.ALARMS_CAPABILITY) || choices.alarms)
    val showsPeople = plugin.reports(PhoneSync.CONTACTS_CAPABILITY) || choices.contacts

    fun apply(next: PhoneSyncPreferences) {
        choices = next
        if (!preferences.isOn) return
        scope.launch {
            try {
                sync.update(next)
                error = null
            } catch (e: Exception) {
                error = SyncSupport.describe(e)
                if (engine.preferences.value.isOn) choices = engine.preferences.value
            }
        }
    }

    // Calendar access is asked when the calendar is chosen, before turning on or while on.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        afterPermission?.invoke()
        afterPermission = null
    }
    fun withCalendarAccess(next: PhoneSyncPreferences, then: () -> Unit) {
        val wanted = when (next.calendarAccess) {
            PhoneCalendarAccess.FULL -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            PhoneCalendarAccess.WRITE_ONLY -> listOf(Manifest.permission.WRITE_CALENDAR)
            PhoneCalendarAccess.OFF -> emptyList()
        }
        val access = engine.calendarSource.eventAccess
        val missing = when (next.calendarAccess) {
            PhoneCalendarAccess.FULL -> access != PhoneCalendarAccess.FULL
            PhoneCalendarAccess.WRITE_ONLY -> access == PhoneCalendarAccess.OFF
            PhoneCalendarAccess.OFF -> false
        }
        // A tasks app's reminders need its own permissions, asked alongside.
        val tasksWanted = if (next.reminders && !engine.calendarSource.remindersAuthorized) tasks.missingPermissions else emptyList()
        val asking = (if (missing) wanted else emptyList()) + tasksWanted
        if (asking.isEmpty()) {
            then()
            return
        }
        afterPermission = {
            val now = engine.calendarSource.eventAccess
            when {
                now == PhoneCalendarAccess.OFF && next.calendarAccess != PhoneCalendarAccess.OFF -> {
                    error = L10n.tr("Conduit doesn't have access to your calendar. Allow it in the Settings app.")
                    working = false
                }
                next.reminders && !engine.calendarSource.remindersAuthorized -> {
                    error = L10n.tr("Conduit doesn't have access to your calendar or reminders. Allow it in the Settings app.")
                    working = false
                }
                else -> then()
            }
        }
        calendarPermission.launch(asking.toTypedArray())
    }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PhoneContacts.person(context, uri)?.let { picked ->
            engine.savePeople(PhonePerson.merging(listOf(picked), engine.people.value))
            if (preferences.isOn && preferences.contacts) scope.launch { sync.run(PhoneSyncReason.MANUAL) }
        }
    }

    ScreenScaffold(t("Calendar & phone"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Calendar & phone for Hermes")) {
                Caption(t("Share your calendar, Do Not Disturb and phone status with Hermes so your agent can plan around your day and pick good moments to reach you. It goes straight from this phone to your Hermes host, sealed end to end, and never through Conduit's notification relay."))
                if (preferences.isOn) {
                    preferences.destination?.let { SettingsRow(t("Sharing with %s on %s", it.profile, it.host), icon = Icons.Default.VerifiedUser) }
                    when {
                        syncing -> Caption(t("Syncing…"))
                        state.pause != null -> WarningCaption(state.pause!!.phoneMessage())
                        state.lastSync != null -> Caption(t("Last synced %s", relativeTime(state.lastSync!!)))
                        else -> Caption(t("Not synced yet."))
                    }
                    if (lastError != null && state.pause == null) ErrorCaption(lastError!!)
                } else if (!pluginServes) {
                    WarningCaption(t("Update the Conduit plugin on your Hermes host to share your calendar and phone status."))
                }
            }
            if (preferences.isOn || pluginServes) {
                SettingsSection(t("Calendar and reminders")) {
                    ChoiceRow(t("Calendar"), choices.calendarAccess, PhoneCalendarAccess.entries.map { it to t(it.title) }, { access ->
                        val next = choices.copy(calendar = access.raw)
                        if (preferences.isOn) withCalendarAccess(next) { apply(next) } else choices = next
                    })
                    Caption(
                        if (choices.calendarAccess == PhoneCalendarAccess.WRITE_ONLY) t("Hermes can ask to add events but can't read your calendar.")
                        else t("Hermes reads events from the last week and the time ahead you choose, and can ask to add or change them."),
                    )
                    if (choices.calendarAccess == PhoneCalendarAccess.FULL) {
                        ChoiceRow(t("Share"), choices.calendarWindow, PhoneCalendarWindow.entries.map { it to t(it.title) }, { apply(choices.copy(window = it.days)) })
                    }
                    if (taskApps.isNotEmpty() || choices.reminders) {
                        SettingsToggle(t("Reminders"), choices.reminders, { on ->
                            val next = choices.copy(reminders = on)
                            if (preferences.isOn && on) withCalendarAccess(next) { apply(next) } else apply(next)
                        }, icon = Icons.Default.Checklist)
                    }
                    if (taskApps.isNotEmpty()) {
                        Caption(t("Reminders come from %s.", taskApps.joinToString(", ") { it.title }))
                    } else {
                        Caption(t("Android has no system reminders app. To share reminders, install a tasks app that other apps can use, such as OpenTasks or Tasks.org."))
                    }
                    if (choices.calendarAccess == PhoneCalendarAccess.FULL || choices.reminders) {
                        SettingsToggle(t("Include notes and attendees"), choices.details, { apply(choices.copy(details = it)) })
                        if (choices.details && choices.calendarAccess == PhoneCalendarAccess.FULL) {
                            Column(Modifier.padding(start = 28.dp)) {
                                SettingsToggle(t("Include attendees' email addresses"), choices.attendeeEmails, { apply(choices.copy(attendeeEmails = it)) })
                            }
                        }
                        Caption(t("Without these, Hermes gets titles, times, places and lists, not what's written in notes or who's invited."))
                    }
                    if (preferences.isOn && (choices.calendarAccess == PhoneCalendarAccess.FULL || choices.reminders)) {
                        val calendars = remember(preferences, state.lastSync) {
                            engine.calendarSource.calendars(engine.calendarSource.eventAccess == PhoneCalendarAccess.FULL && choices.calendarAccess == PhoneCalendarAccess.FULL,
                                choices.reminders && engine.calendarSource.remindersAuthorized)
                                .mapNotNull { entry -> entry["id"].str?.let { id -> Triple(id, entry["title"].str.orEmpty(), engine.calendarSource.platformCalendarID(id)) } }
                        }
                        if (calendars.isNotEmpty()) {
                            SettingsRow(t("Calendars and lists"), value = "${calendars.count { it.third !in choices.hiddenCalendars }}/${calendars.size}", onClick = { calendarsOpen = !calendarsOpen })
                            if (calendarsOpen) calendars.forEach { (_, title, platformID) ->
                                SettingsToggle(title, platformID == null || platformID !in choices.hiddenCalendars, { shown ->
                                    platformID ?: return@SettingsToggle
                                    apply(choices.copy(hiddenCalendars = if (shown) choices.hiddenCalendars - platformID else choices.hiddenCalendars + platformID))
                                })
                            }
                        }
                    }
                }
                SettingsSection(t("Changes from Hermes"), footer = t("Hermes always asks before it changes or deletes anything. Without asking, new items go in when Conduit next runs, with Undo. In a voice call, Hermes asks you out loud.")) {
                    ChoiceRow(t("New events and reminders"), choices.autoAdd, listOf(false to t("Ask me each time"), true to t("Add without asking")), { apply(choices.copy(autoAdd = it)) })
                }
                SettingsSection(t("Focus and phone status")) {
                    SettingsToggle(t("Do Not Disturb"), choices.focus, { apply(choices.copy(focus = it)) }, icon = Icons.Default.DoNotDisturbOn)
                    Caption(t("Hermes hears whether Do Not Disturb is on, so it can hold calls and messages while it is."))
                    FocusFiltersSection(changed = {
                        if (engine.preferences.value.isOn && engine.preferences.value.focus) scope.launch { sync.run(PhoneSyncReason.FOCUS_CHANGED) }
                    })
                    SettingsToggle(t("Phone status"), choices.status, { apply(choices.copy(status = it)) }, icon = Icons.Default.BatteryStd)
                    Caption(t("Battery and charging, whether Android Auto or headphones are connected, and Wi-Fi or cellular. Only the latest state is kept."))
                }
                if (showsAlarms) {
                    SettingsSection(t("Alarms and timers")) {
                        SettingsToggle(t("Let Hermes set alarms and timers"), choices.alarms, { apply(choices.withAlarms(it)) }, icon = Icons.Default.Alarm)
                        Caption(t("They're set in your Clock app. Hermes asks each time unless new items go in without asking, and always asks before cancelling one. Hermes sees only the alarms it set."))
                    }
                }
                if (showsPeople) {
                    SettingsSection(t("People")) {
                        SettingsToggle(t("Share the people you pick"), choices.contacts, { apply(choices.withContacts(it)) }, icon = Icons.Default.People)
                        Caption(t("Pick the people Hermes should know, and say who they are to you, such as wife or boss, so \"text my wife\" or \"Mum's birthday\" just works. Hermes gets their name, phone numbers, email addresses and birthday. Conduit doesn't read your contacts: only the people you pick here."))
                        Caption(t("Their nickname and company go too, when the contact has them."))
                        if (choices.contacts) {
                            people.forEach { person ->
                                PersonRow(person, save = { relation ->
                                    val list = engine.people.value.map { if (it.id == person.id) it.copy(relation = relation.ifEmpty { null }?.take(PhonePerson.RELATION_LENGTH)) else it }
                                    engine.savePeople(list)
                                    if (preferences.isOn) scope.launch { sync.run(PhoneSyncReason.MANUAL) }
                                }, remove = {
                                    engine.savePeople(engine.people.value.filter { it.id != person.id })
                                    if (preferences.isOn) scope.launch { sync.run(PhoneSyncReason.MANUAL) }
                                })
                            }
                            SettingsRow(t("Add people"), icon = Icons.Default.PersonAdd, onClick = if (people.size < PhonePerson.MAXIMUM) ({ runCatching { contactPicker.launch(null) } }) else null)
                        }
                    }
                }
                if (preferences.isOn && plugin.reports(VOICE_TOOLS_CAPABILITY)) {
                    VoiceModesSection(
                        allowed = preferences::letsVoiceUse,
                        onToggle = { mode, on -> if (on) askingMode = mode else sync.setVoiceUse(mode, false) },
                        explanation = t("Let a live voice call read what you share here when you ask, add events and reminders you ask for, and ask you out loud about Hermes' changes. What it reads goes to the company that runs that voice mode, under its terms. Classic voice is Hermes itself and needs nothing here."),
                        reads = voiceUses,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (preferences.isOn) {
                        SecondaryActionButton(t("Sync now"), enabled = !syncing && !working) {
                            scope.launch {
                                sync.run(PhoneSyncReason.MANUAL)
                                error = if (DeviceSharing.activeDashboardID(context) == null) L10n.tr("Connect to your Hermes dashboard first.") else null
                            }
                        }
                        SecondaryActionButton(t("Turn off sharing"), enabled = !working, destructive = true) { showsTurnOff = true }
                    } else {
                        PrimaryActionButton(if (working) t("Turning on…") else t("Turn on sharing"), enabled = choices.sharesAnything && !working && DeviceSharing.activeDashboardID(context) != null) {
                            showsConsent = true
                        }
                    }
                    error?.let { ErrorCaption(it) }
                }
            }
        }
    }

    if (showsConsent) {
        ConsentDialog(
            t("Share your calendar and phone with Hermes?"),
            listOf(
                Icons.Default.Lock to t("Conduit sends what you chose to the %s profile on %s, sealed end to end. It never goes through Conduit's notification relay, and Conduit's developer can't see it.", DeviceSharing.activeProfile(context), DeviceSharing.activeHost(context)),
                Icons.Default.AutoAwesome to t("Your agent reads it when you ask, or when a task you set up needs it. Whatever it reads goes to the AI model provider your Hermes uses, under that provider's terms. If your Hermes is hosted by someone else, such as Hermes Cloud, they can read it too."),
                Icons.Default.People to t("Events can include other people's names. Notes, attendees and email addresses stay on this phone unless you turn them on."),
                Icons.Default.PanTool to t("Hermes can only ask for calendar changes. You approve each one unless you choose to let new items in without asking, and changes or deletions always ask."),
                Icons.Default.Alarm to t("Alarms and timers Hermes sets go into your Clock app. People are shared only if you pick them."),
                Icons.Default.EventBusy to t("You can turn sharing off at any time in Settings > Calendar & phone and delete what was sent from the host."),
            ),
            onAccept = {
                showsConsent = false
                working = true
                error = null
                withCalendarAccess(choices) {
                    scope.launch {
                        try {
                            sync.turnOn(choices)
                            choices = engine.preferences.value
                        } catch (e: Exception) {
                            error = SyncSupport.describe(e)
                        }
                        working = false
                    }
                }
            },
            onCancel = { showsConsent = false },
        )
    }
    if (showsTurnOff) {
        fun off(delete: Boolean) {
            showsTurnOff = false
            working = true
            error = null
            scope.launch {
                if (!sync.turnOff(delete)) error = L10n.tr("Sharing is off. The host couldn't be reached, so Conduit deletes the data there the next time it connects.")
                choices = PhoneSyncPreferences.SUGGESTED
                voiceUses = VoiceReadLog()
                working = false
            }
        }
        TurnOffDialog(
            t("Turn off sharing?"),
            t("Conduit stops sending your calendar, Do Not Disturb and phone status, and Hermes can't ask for calendar changes. Deleting removes what this phone sent from the host."),
            onDelete = { off(true) }, onKeep = { off(false) }, onCancel = { showsTurnOff = false },
        )
    }
    askingMode?.let { mode ->
        VoiceAllowDialog(t("Let %s use your calendar and reminders?", mode.displayName), mode, onAllow = {
            askingMode = null
            sync.setVoiceUse(mode, true)
        }, onCancel = { askingMode = null })
    }
}

@Composable
private fun PersonRow(person: PhonePerson, save: (String) -> Unit, remove: () -> Unit) {
    var relation by remember(person.id) { mutableStateOf(person.relation.orEmpty()) }
    DisposableEffect(person.id) {
        onDispose { if (relation.trim() != person.relation.orEmpty()) save(relation.trim()) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = relation,
                onValueChange = { relation = it.take(PhonePerson.RELATION_LENGTH) },
                placeholder = { Text(t("Who they are to you")) },
                singleLine = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save(relation.trim()) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = remove) { Icon(Icons.Default.RemoveCircle, t("Remove %s", person.name), tint = MaterialTheme.colorScheme.error) }
    }
}

/** The card over the app for the oldest change waiting on the user, and the banner after one went in without asking. */
@Composable
fun PhoneChangeOverlay() {
    val context = LocalContext.current
    val sync = remember { PhoneSync.of(context) }
    val prompts by sync.engine.prompts.collectAsStateWithLifecycle()
    val added by sync.engine.autoAdded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        prompts.firstOrNull()?.let { prompt -> PhoneChangeCard(prompt, prompts.size) { approve -> sync.answer(prompt, approve) } }
        added?.let { item ->
            LaunchedEffect(item) {
                delay(8_000)
                if (sync.engine.autoAdded.value == item) sync.engine.autoAdded.value = null
            }
            AutoAddedBanner(item, undo = { scope.launch { sync.engine.undo(item) } }, close = { sync.engine.autoAdded.value = null })
        }
    }
}

@Composable
private fun PhoneChangeCard(prompt: PhoneChangePrompt, waiting: Int, answer: (Boolean) -> Unit) {
    val change = prompt.change
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (change.kind.isAlarm) Icons.Default.Alarm else Icons.Default.CalendarMonth, null)
                Spacer(Modifier.width(8.dp))
                Text(PhoneChangeWording.headline(change), style = MaterialTheme.typography.titleSmall)
            }
            val detail = prompt.alarmTitle ?: PhoneChangeWording.detail(change)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (prompt.calendarTitle != null && change.kind.isAddition) {
                Text(t("In %s", prompt.calendarTitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            change.reason?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { answer(false) }, modifier = Modifier.weight(1f)) { Text(t("Not now")) }
                Button(
                    onClick = { answer(true) },
                    modifier = Modifier.weight(1f),
                    colors = if (PhoneChangeWording.isRemoval(change.kind)) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text(PhoneChangeWording.approveTitle(change)) }
            }
            if (waiting > 1) Text(t("%s more waiting", (waiting - 1).toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AutoAddedBanner(added: PhoneAutoAdded, undo: () -> Unit, close: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (added.isAlarm) Icons.Default.Alarm else Icons.Default.CalendarMonth, null)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    added.isAlarm -> t("Hermes set %s", added.title)
                    added.calendarTitle != null -> t("Hermes added %s to %s", added.title, added.calendarTitle)
                    else -> t("Hermes added %s", added.title)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = undo) { Text(t("Undo")) }
            TextButton(onClick = close) { Text(t("Close")) }
        }
    }
}
