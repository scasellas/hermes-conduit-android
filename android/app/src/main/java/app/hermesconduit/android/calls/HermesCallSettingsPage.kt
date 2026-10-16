package app.hermesconduit.android.calls

import android.content.Intent
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.HermesCallSettings
import app.hermesconduit.android.model.HermesCallSettingsFormat
import app.hermesconduit.android.model.HermesCallsStatus
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import app.hermesconduit.android.ui.location.LocationSharing
import app.hermesconduit.android.ui.nav.activityAppViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** "2 minutes", "1 hour, 30 minutes", in the app's language. */
private fun gapLabel(seconds: Int): String = runCatching {
    val measures = buildList {
        if (seconds / 3600 > 0) add(Measure(seconds / 3600, MeasureUnit.HOUR))
        if (seconds % 3600 / 60 > 0) add(Measure(seconds % 3600 / 60, MeasureUnit.MINUTE))
        if (seconds % 60 > 0 || isEmpty()) add(Measure(seconds % 60, MeasureUnit.SECOND))
    }
    MeasureFormat.getInstance(Locale.forLanguageTag(L10n.language), MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
}.getOrElse { HermesCallSettingsFormat.gap(seconds) }

/** Settings › Calls from Hermes (iOS HermesCallsSettingsPage). The settings live with the notifier plugin, per profile. */
@Composable
fun HermesCallSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val calls = remember { HermesCalls.get(context) }
    val status by calls.status.collectAsStateWithLifecycle()
    val profile by context.hermes.profile.collectAsStateWithLifecycle()
    val appVm = activityAppViewModel()
    var loadFailed by remember { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loadFailed = false
        unsupported = !calls.supportsCalls()
        if (!unsupported) loadFailed = !calls.refreshStatus()
    }
    LaunchedEffect(profile) { load() }

    fun startChat(prefill: String) {
        // A new chat with the request in the composer, for the user to send.
        appVm?.receiveShare(prefill, emptyList())
    }

    ScreenScaffold(t("Calls from Hermes"), onBack) { padding ->
        SettingsColumn(padding) {
            val current = status
            when {
                unsupported -> SettingsSection(t("Calls from Hermes")) {
                    Column(Modifier.padding(16.dp)) {
                        Caption(t("Update the Hermes notifier plugin on your Hermes host to use calls from Hermes."))
                    }
                }
                current != null -> CallSettingsSection(current, profile, calls, startTestCall = { startChat(HermesCallSettingsFormat.testCallPrompt) }, startWatch = { startChat(HermesCallSettingsFormat.watchPrompt(it)) })
                loadFailed -> SettingsSection(t("Calls from Hermes")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption(t("Couldn't read Hermes' call settings."))
                        OutlinedButton(onClick = { scope.launch { load() } }) { Text(t("Try again")) }
                    }
                }
                else -> SettingsSection(t("Calls from Hermes")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Caption(t("Loading call settings…"))
                    }
                }
            }
            HowToSection(current?.settings, hearsMissedCalls = true)
            CallTraceSection()
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CallSettingsSection(status: HermesCallsStatus, profile: String, calls: HermesCalls, startTestCall: () -> Unit, startWatch: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(profile) { mutableStateOf(status.settings) }
    var rulesText by remember(profile) { mutableStateOf(status.settings.rules ?: "") }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val ringsLikeCall by context.appSettings.bool(HermesCalls.RINGS_KEY, true).collectAsStateWithLifecycle()
    val connection by context.hermes.connection.collectAsStateWithLifecycle()
    val dashboard = connection?.id
    LaunchedEffect(status.settings) { if (saveJob == null) draft = status.settings }

    /** Saved once the user stops tapping; one the host didn't take puts back what it holds. */
    fun change(edit: (HermesCallSettings) -> HermesCallSettings) {
        draft = edit(draft)
        val settings = draft
        val previous = saveJob
        previous?.cancel()
        saveJob = scope.launch {
            delay(500)
            val saved = calls.save(settings, profile, dashboard)
            saveJob = null
            if (!saved) {
                draft = calls.status.value?.settings ?: status.settings
                android.widget.Toast.makeText(context, L10n.tr("Couldn't save Hermes' call settings. Check your connection and try again."), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun commitRules() {
        if (draft.rules == null) return
        val rules = HermesCallSettingsFormat.rules(rulesText)
        if (rules != draft.rules) change { it.copy(rules = rules) }
    }

    SettingsSection(t("Calls from Hermes")) {
        SettingsToggle(t("Hermes can call me"), draft.enabled, { on -> change { it.copy(enabled = on) } })
        Footnote(t("When a job you asked about is done, Hermes calls you so you can talk it through. Your phone rings like a phone call; where it can't, a “Hermes wants to talk” notification comes instead: tap Talk to answer."))
        Footnote(t("On Android, calls reach this phone through Conduit's own connection to Hermes. To get them with Conduit closed, keep Notifications on with Stay connected."))
        if (draft.enabled) {
            SettingsToggle(t("Ring like a phone call"), ringsLikeCall, { context.appSettings.set(HermesCalls.RINGS_KEY, it) })
            Footnote(t("On this phone. Off, a “Hermes wants to talk” notification comes instead: tap Talk to answer."))
            if (ringsLikeCall && !HermesCallNotifications.canUseFullScreen(context)) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(t("Allow calls to show full screen")) }
            }
            SettingsToggle(t("Call when I ask"), draft.whenAsked, { on -> change { it.copy(whenAsked = on) } })
            Footnote(t("Say “call me when it's done” during a live call, or ask Hermes in a chat to call you. Hermes calls about that job once the call has ended; if it finishes while you're still talking, you hear about it in the call instead."))
            if (draft.whenAsked && draft.decides != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = startTestCall) {
                        Icon(Icons.Default.Phone, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(t("Try a test call"))
                    }
                    Caption(t("Opens a new chat with “%s” ready to send. Send it, and your phone rings when Hermes replies.", HermesCallSettingsFormat.testCallPrompt))
                }
            }
            if (draft.decides != null) {
                SettingsToggle(t("Hermes decides when to call"), draft.decides == true, { on -> change { it.copy(decides = on) } })
                Footnote(t("Hermes may also call on its own when news can't wait, within your limits. It never calls for routine updates."))
            }
            if (draft.decides == true && draft.rules != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("What's worth a call"), fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = rulesText,
                        onValueChange = { text -> rulesText = HermesCallSettingsFormat.limitRules(text, status.rulesMax) ?: text },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitRules() },
                        placeholder = { Text(t("For example: only if production is down or a deploy fails. Never before 9 am.")) },
                        minLines = 3,
                        maxLines = 8,
                        supportingText = { Text("${rulesText.codePointCount(0, rulesText.length)}/${status.rulesMax}") },
                    )
                    Caption(t("Hermes reads this before each call it decides to make, and calls only if the news fits. Calls you ask for don't depend on it."))
                    if (draft.whenAsked && HermesCallSettingsFormat.rules(rulesText).isNotEmpty()) {
                        OutlinedButton(onClick = { commitRules(); startWatch(rulesText) }) {
                            Icon(Icons.Default.Visibility, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(t("Have Hermes watch for this"))
                        }
                        Caption(t("Hermes doesn't check for what your note describes on its own. This opens a new chat asking it to set up scheduled checks that call you when it happens. After you change the note, tap it again to bring the checks up to date."))
                    }
                }
            }
            if (draft.alerts != null) {
                SettingsToggle(t("Call about approvals and questions"), draft.alerts == true, { on -> change { it.copy(alerts = on) } })
                Footnote(t("Hermes calls when it has waited a minute for your OK or an answer, or when a request fails. In a live call you can answer out loud."))
            }
            GapPicker(draft.minGapSeconds, HermesCallSettingsFormat.gapChoices(status.minGapBounds, draft.minGapSeconds)) { seconds -> change { it.copy(minGapSeconds = seconds) } }
            Stepper(t("Calls per hour: %s", draft.perHour.toString()), draft.perHour, status.perHourBounds) { value -> change { it.copy(perHour = value) } }
            Stepper(t("Calls per day: %s", draft.perDay.toString()), draft.perDay, status.perDayBounds) { value -> change { it.copy(perDay = value) } }
            Footnote(t("Past a limit, a finished job sends its usual notification instead of calling. These settings are this profile's, kept on your Hermes host."))
            if (draft.avoidPlaces != null || draft.avoidUnknownPlace != null) AvoidPlaces(draft) { edit -> change(edit) }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun AvoidPlaces(draft: HermesCallSettings, change: ((HermesCallSettings) -> HermesCallSettings) -> Unit) {
    val context = LocalContext.current
    val places = remember { runCatching { LocationSharing.of(context).places }.getOrDefault(emptyList()) }
    if (draft.avoidPlaces != null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(t("Don't call me while I'm at"), fontWeight = FontWeight.SemiBold)
            if (places.isEmpty()) Caption(t("Save places in Settings under Location first."))
        }
        places.forEach { place ->
            val on = draft.avoidPlaces.contains(place.id)
            SettingsToggle(place.name, on, { enabled ->
                change { settings ->
                    val list = (settings.avoidPlaces ?: emptyList()).filter { it != place.id } + listOfNotNull(place.id.takeIf { enabled })
                    settings.copy(avoidPlaces = list.take(HermesCallSettings.MAX_AVOID_PLACES))
                }
            })
        }
        Footnote(t("A call Hermes would make while you're at one of these places comes as the usual “Hermes wants to talk” notification instead. Needs location shared in the background."))
    }
    if (draft.avoidUnknownPlace != null) {
        SettingsToggle(t("Don't call me when Conduit can't say where I am"), draft.avoidUnknownPlace == true, { on -> change { it.copy(avoidUnknownPlace = on) } })
        Footnote(t("That's when location sharing is off or paused, or no recent location has been sent. The call comes as the usual notification instead."))
    }
}

@Composable
private fun GapPicker(value: Int, choices: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Time between calls"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(gapLabel(value), fontWeight = FontWeight.Medium)
            Icon(Icons.Default.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { seconds ->
                DropdownMenuItem(text = { Text(gapLabel(seconds)) }, onClick = { open = false; onPick(seconds) })
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, bounds: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange(value - 1) }, enabled = value > bounds.first) { Icon(Icons.Default.Remove, t("Decrease")) }
        IconButton(onClick = { onChange(value + 1) }, enabled = value < bounds.last) { Icon(Icons.Default.Add, t("Increase")) }
    }
}

/** What to say to get a call, and what else to know. */
@Composable
private fun HowToSection(settings: HermesCallSettings?, hearsMissedCalls: Boolean) {
    SettingsSection(t("How to use calls")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(t("Ask in any chat or voice call: “Call me when the tests finish.” Your phone rings once the job is done, and answering opens voice in that chat."))
            if (settings?.decides != null) {
                Caption(t("To be called when something happens, ask Hermes to watch for it: “Check my homelab every 5 minutes and call me if it's down for more than 10 minutes.” Hermes sets up a scheduled check that calls you."))
            }
            if (settings?.rules != null) {
                Caption(t("To let Hermes call on its own, turn on Hermes decides when to call and write what's worth a call. Hermes checks your note before each call it decides to make."))
            } else if (settings?.decides != null) {
                Caption(t("To let Hermes call on its own when news can't wait, turn on Hermes decides when to call."))
            }
            if (hearsMissedCalls) Caption(t("If you decline or miss a call, Hermes knows the next time you write in that chat."))
        }
    }
}

/** The last call, step by step (iOS shows it in Gateway Diagnostics). */
@Composable
private fun CallTraceSection() {
    val context = LocalContext.current
    val trace by HermesCallTrace.timeline.collectAsStateWithLifecycle()
    val report = trace?.report ?: return
    SettingsSection(t("Last call")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            TextButton(onClick = { app.hermesconduit.android.ui.copyToClipboard(context, report) }) { Text(t("Copy")) }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}
