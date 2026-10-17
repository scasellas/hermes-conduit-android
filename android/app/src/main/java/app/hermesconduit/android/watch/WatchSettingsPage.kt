package app.hermesconduit.android.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.live.liveErrorMessage
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Settings > Watch (iOS Voice settings > Apple Watch, WatchSettingsSection):
 * whether Conduit is on a Wear OS watch, what a watch call may do on Hermes
 * (jobs per call, approvals), GPT-Live's setup on the Hermes host, a check of
 * the host's watch-tools grants, and the call log to share for a support
 * question.
 */
@Composable
fun WatchSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.appSettings
    val log = remember { WatchPhoneCallLog.get(context) }
    val scope = rememberCoroutineScope()
    val voiceApprovals by settings.bool(WatchSettings.VOICE_APPROVALS, false).collectAsStateWithLifecycle()
    val storedJobs by settings.int(WatchJobSettings.JOBS_PER_CALL, WatchJobSettings.DEFAULT_JOBS_PER_CALL).collectAsStateWithLifecycle()
    val jobsPerCall = WatchJobSettings.clamped(storedJobs)
    var status by remember { mutableStateOf<String?>(null) }
    var logBytes by remember { mutableLongStateOf(log.sizeBytes) }
    var confirmingClear by remember { mutableStateOf(false) }
    var grantCheck by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val gptLive = remember { WatchGPTLiveSetup(context.hermes, scope) }
    val gptState by gptLive.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        status = watchStatus(context)
        WatchBridge.get(context).publishContext()
    }
    LaunchedEffect(Unit) { gptLive.refresh() }
    // Leaving the screen stops the setup's checks.
    DisposableEffect(Unit) { onDispose { gptLive.stop() } }

    ScreenScaffold(t("Watch"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(
                t("Watch"),
                footer = t("Open Conduit on your watch to talk to Hermes: the call starts right away, or with a tap if you turn off Call on open in the watch's settings. Gemini Live runs on the watch; GPT-Live and Grok run on your Hermes host. Calls keep going with your wrist down and your phone locked, and are saved to voice history."),
            ) {
                SettingsRow(status ?: t("Checking for a watch…"), icon = Icons.Default.Watch)
            }

            SettingsSection(
                t("Jobs"),
                footer = t("Hermes jobs a Watch call can start, wrist up or down. Each runs as a normal Hermes chat under Voice Jobs, and Hermes' own approval settings apply."),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Work, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (jobsPerCall == 0) t("Jobs per Watch call: through the phone") else t("Jobs per Watch call: %s", jobsPerCall.toString()),
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                    IconButton(onClick = { settings.set(WatchJobSettings.JOBS_PER_CALL, WatchJobSettings.clamped(jobsPerCall - 1)) }, enabled = jobsPerCall > 0) {
                        Icon(Icons.Default.Remove, contentDescription = t("Fewer"))
                    }
                    IconButton(onClick = { settings.set(WatchJobSettings.JOBS_PER_CALL, WatchJobSettings.clamped(jobsPerCall + 1)) }, enabled = jobsPerCall < WatchJobSettings.MAXIMUM_JOBS_PER_CALL) {
                        Icon(Icons.Default.Add, contentDescription = t("More"))
                    }
                }
            }

            SettingsSection(
                t("Approve from the Watch"),
                footer = if (voiceApprovals) t("A job's approval request shows on the Watch, and Gemini Live or Grok can also take your answer by voice. Voice only ever approves once. Text in a web page or a job's output could try to talk it into approving.")
                else t("A job's approval request shows on the Watch with Approve once and Deny."),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !voiceApprovals, enabled = jobsPerCall > 0, onClick = { setVoiceApprovals(context, false) }, label = { Text(t("Tap")) })
                    FilterChip(selected = voiceApprovals, enabled = jobsPerCall > 0, onClick = { setVoiceApprovals(context, true) }, label = { Text(t("Tap or voice")) })
                }
            }

            SettingsSection(
                t("GPT-Live on the Watch"),
                footer = t("Your Hermes host holds GPT-Live's call for the Watch, with a small runtime it sets up once. The first call sets it up too, but that can take a few minutes."),
            ) {
                SettingsRow(
                    t("GPT-Live on the Watch"),
                    icon = Icons.Default.GraphicEq,
                    value = when (val s = gptState) {
                        WatchGPTLiveSetup.State.Unknown -> t("Checking…")
                        WatchGPTLiveSetup.State.Ready -> t("Ready")
                        WatchGPTLiveSetup.State.Preparing -> t("Setting up on Hermes…")
                        WatchGPTLiveSetup.State.NotSetUp -> t("Not set up yet")
                        is WatchGPTLiveSetup.State.Failed -> s.reason
                        is WatchGPTLiveSetup.State.Unavailable -> s.reason
                    },
                    trailing = when {
                        gptState == WatchGPTLiveSetup.State.Preparing -> ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
                        gptState == WatchGPTLiveSetup.State.Ready -> ({ Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32)) })
                        else -> null
                    },
                )
                if (gptLive.canPrepare(gptState)) {
                    SettingsRow(t("Set up"), icon = Icons.Default.Build, onClick = gptLive::prepare)
                }
            }

            SettingsSection(
                t("Watch tools on your Hermes host"),
                footer = t("Checks that your Hermes host can grant a watch call web search and memory through the push relay. The test grant is revoked right away."),
            ) {
                SettingsRow(
                    t("Check"),
                    icon = Icons.Default.Build,
                    value = grantCheck,
                    onClick = if (checking) null else ({
                        checking = true
                        grantCheck = null
                        scope.launch {
                            grantCheck = checkGrant(context)
                            checking = false
                        }
                    }),
                    trailing = if (checking) ({ CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) }) else null,
                )
            }

            SettingsSection(t("Call log"), footer = t("Timings and connection events from Watch calls, without what was said. Share it when reporting a problem.")) {
                SettingsRow(t("Share call log"), icon = Icons.Default.Share, value = sizeLabel(logBytes), onClick = {
                    runCatching { context.startActivity(log.shareIntent()) }
                })
                SettingsRow(t("Clear Log"), onClick = { confirmingClear = true }, trailing = {})
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(t("Clear the Watch call log?")) },
            confirmButton = {
                TextButton(onClick = {
                    log.clear()
                    logBytes = log.sizeBytes
                    confirmingClear = false
                }) { Text(t("Clear Log"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(t("Cancel")) } },
        )
    }
}

/**
 * GPT-Live's WebRTC runtime on the Hermes host (watch-audio/status and
 * /prepare, iOS `WatchGPTLiveSetup`): whether a watch call to GPT-Live can
 * start at once.
 */
class WatchGPTLiveSetup(private val hermes: HermesConnection, private val scope: CoroutineScope) {
    sealed interface State {
        data object Unknown : State
        data object Ready : State
        data object Preparing : State
        data object NotSetUp : State
        data class Failed(val reason: String) : State
        data class Unavailable(val reason: String) : State
    }

    private val client = WatchToolGrantClient(hermes)
    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state
    private var polling: Job? = null

    fun canPrepare(state: State) = state == State.NotSetUp || state is State.Failed

    suspend fun refresh() {
        if (hermes.link.value != LinkState.CONNECTED) {
            _state.value = State.Unavailable(L10n.tr("Connect to Hermes to check."))
            return
        }
        _state.value = try {
            stateOf(client.audioStatus(hermes.profile.value))
        } catch (_: Exception) {
            State.Unavailable(L10n.tr("Your Hermes host doesn't offer GPT-Live for the Watch. Update the Conduit notifier plugin."))
        }
    }

    fun prepare() {
        val profile = hermes.profile.value
        _state.value = State.Preparing
        polling?.cancel()
        polling = scope.launch {
            try {
                _state.value = stateOf(client.prepareAudio(profile))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(liveErrorMessage(e))
                return@launch
            }
            repeat(MAX_POLLS) {
                if (_state.value != State.Preparing) return@launch
                delay(POLL_INTERVAL_MS)
                runCatching { client.audioStatus(profile) }.getOrNull()?.let { _state.value = stateOf(it) }
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
    }

    companion object {
        /** How often a setup in progress is checked while the screen is up. */
        const val POLL_INTERVAL_MS = 3_000L
        /** Checks give up after this many; the next visit checks again. */
        const val MAX_POLLS = 120

        fun stateOf(runtime: WatchToolGrantClient.AudioRuntime): State = when (runtime.runtime) {
            "ready" -> State.Ready
            "preparing" -> State.Preparing
            "failed" -> State.Failed(runtime.reason ?: L10n.tr("Hermes couldn't set up GPT-Live for the Watch."))
            else -> State.NotSetUp
        }
    }
}

private fun setVoiceApprovals(context: android.content.Context, on: Boolean) {
    context.appSettings.set(WatchSettings.VOICE_APPROVALS, on)
    context.hermes.scope.launch { WatchBridge.get(context).publishContext() }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> L10n.tr("Empty")
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
}

/** iOS statusLine: paired, installed, or what to do. */
private suspend fun watchStatus(context: android.content.Context): String = try {
    val nodes = Wearable.getNodeClient(context).connectedNodes.await()
    if (nodes.isEmpty()) L10n.tr("No Wear OS watch is connected to this phone.")
    else {
        val installed = Wearable.getCapabilityClient(context).getCapability(WearWire.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await().nodes
        if (installed.isNotEmpty()) L10n.tr("Conduit is on your watch.") else L10n.tr("Install Conduit on your watch from the Play Store on the watch.")
    }
} catch (_: Exception) {
    L10n.tr("No Wear OS watch is connected to this phone.")
}

/** Asks for a short grant and revokes it at once: shows whether the host's watch-tools routes work. */
private suspend fun checkGrant(context: android.content.Context): String {
    val hermes = context.hermes
    if (!hermes.isSignedIn) return L10n.tr("Connect to Hermes to check.")
    val client = WatchToolGrantClient(hermes)
    val profile = hermes.profile.value
    return try {
        val grant = client.grant(listOf("web_search", "recall_memory"), profile)
        client.revoke(grant.grantId, profile)
        L10n.tr("Ready")
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: L10n.tr("Your Hermes host doesn't offer this. Update the Conduit notifier plugin.")
    }
}
