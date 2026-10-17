package app.hermesconduit.android.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import app.hermesconduit.android.wear.BuildConfig
import app.hermesconduit.android.wear.t
import app.hermesconduit.android.wear.wearApp
import app.hermesconduit.wearwire.WearApproval
import app.hermesconduit.wearwire.WearChat
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A scrolling watch screen with the time and a scroll indicator. */
@Composable
fun WatchList(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 28.dp),
            content = content,
        )
    }
}

@Composable
fun Footnote(text: String, color: Color = MaterialTheme.colors.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.caption3, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
}

@Composable
private fun NavChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, secondary: String? = null, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        icon = { Icon(icon, contentDescription = null, tint = WatchColors.aura) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// region Home

/** The home screen: one big button to talk to Hermes, then chats, approvals, the call log and settings. */
@Composable
fun HomeScreen(onTalk: () -> Unit, navigate: (String) -> Unit) {
    val app = LocalContext.current.wearApp
    val reachable by app.link.reachable.collectAsStateWithLifecycle()
    val context by app.link.phoneContext.collectAsStateWithLifecycle()
    val approvals by Approvals.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        app.link.refresh()
        Approvals.refresh(app)
    }
    WatchList {
        item {
            Box(
                Modifier.clickable(onClick = onTalk).semantics { contentDescription = t("Start a call") },
                contentAlignment = Alignment.Center,
            ) { VoiceOrb(OrbMood.READY, size = 84.dp) }
        }
        item { Text(t("Talk to Hermes"), style = MaterialTheme.typography.title3) }
        if (!reachable) item { Footnote(t("Phone not connected right now"), WatchColors.attention) }
        else if (context?.signedIn == false) item { Footnote(t("Sign in to Hermes in Conduit on your phone."), WatchColors.attention) }
        item { NavChip(t("Recent chats"), Icons.Default.Chat) { navigate("chats") } }
        item {
            NavChip(t("Approvals"), Icons.Default.Gavel, secondary = if (approvals.isEmpty()) null else t("%d waiting", approvals.size)) { navigate("approvals") }
        }
        item { NavChip(t("Call log"), Icons.AutoMirrored.Filled.List) { navigate("log") } }
        item { NavChip(t("Settings"), Icons.Default.Settings) { navigate("settings") } }
    }
}

// endregion

// region Chats

@Composable
fun ChatsScreen(onPick: (String, String) -> Unit) {
    val app = LocalContext.current.wearApp
    var chats by remember { mutableStateOf<List<WearChat>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val answer = app.link.ask(WearMessage.ChatsRequest) { it as? WearMessage.Chats }
        chats = answer?.chats.orEmpty()
        error = answer?.error ?: if (answer == null) t("Phone not connected right now") else null
    }
    WatchList {
        item { ListHeader { Text(t("Recent chats")) } }
        when {
            chats == null -> item { CircularProgressIndicator() }
            chats!!.isEmpty() -> item { Footnote(error ?: t("No chats yet")) }
            else -> {
                item { Footnote(t("Tap a chat to talk to Hermes in it.")) }
                items(chats!!) { chat ->
                    Chip(
                        onClick = { onPick(chat.id, chat.title) },
                        label = { Text(chat.title.ifBlank { t("New chat") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = chat.preview?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// endregion

// region Approvals

/** Approval requests waiting on Hermes, as the phone last listed them. */
object Approvals {
    val list = MutableStateFlow<List<WearApproval>>(emptyList())
    var selected: WearApproval? = null
    val error = MutableStateFlow<String?>(null)

    suspend fun refresh(app: app.hermesconduit.android.wear.WearApp): Boolean {
        val answer = app.link.ask(WearMessage.ApprovalsRequest) { it as? WearMessage.Approvals }
        if (answer != null) list.value = answer.approvals
        error.value = answer?.error ?: if (answer == null) t("Phone not connected right now") else null
        return answer != null
    }
}

@Composable
fun ApprovalsScreen(onOpen: () -> Unit) {
    val app = LocalContext.current.wearApp
    val approvals by Approvals.list.collectAsStateWithLifecycle()
    val error by Approvals.error.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        Approvals.refresh(app)
        loading = false
    }
    WatchList {
        item { ListHeader { Text(t("Approvals")) } }
        when {
            loading && approvals.isEmpty() -> item { CircularProgressIndicator() }
            approvals.isEmpty() -> item { Footnote(error ?: t("Nothing is waiting for approval.")) }
            else -> items(approvals) { approval ->
                Chip(
                    onClick = { Approvals.selected = approval; onOpen() },
                    label = { Text(approval.command.ifBlank { approval.description }.ifBlank { t("Approve this command?") }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(approval.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Default.Gavel, contentDescription = null, tint = WatchColors.attention) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One request (WatchApprovalCard): Approve allows this one command only. */
@Composable
fun ApprovalCard(approval: WearApproval, busy: Boolean, error: String?, onAnswer: (Boolean) -> Unit) {
    WatchList {
        item {
            Text(t("Approve this command?"), style = MaterialTheme.typography.title3, color = WatchColors.attention, textAlign = TextAlign.Center)
        }
        if (approval.title.isNotBlank()) item { Text(approval.title, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.SemiBold, maxLines = 3) }
        if (approval.description.isNotBlank()) item { Footnote(approval.description) }
        // All of it: the card scrolls, and the user approves what they read.
        if (approval.command.isNotBlank()) item {
            Text(
                approval.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(6.dp),
            )
        }
        error?.let { item { Footnote(it, WatchColors.attention) } }
        item {
            Chip(
                onClick = { if (!busy) onAnswer(true) },
                label = { Text(t("Approve once")) },
                icon = { Icon(Icons.Default.Check, contentDescription = null) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF2E7D32)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { if (!busy) onAnswer(false) },
                label = { Text(t("Deny")) },
                icon = { Icon(Icons.Default.Close, contentDescription = null) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFFC62828)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ApprovalDetailScreen(onDone: () -> Unit) {
    val app = LocalContext.current.wearApp
    val scope = rememberCoroutineScope()
    val approval = Approvals.selected
    if (approval == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ApprovalCard(approval, busy, error) { approve ->
        busy = true
        error = null
        scope.launch {
            val result = app.link.ask(WearMessage.ApprovalRespond(approval.sessionId, approval.requestId, if (approve) WearWire.APPROVE_ONCE else WearWire.DENY)) { message ->
                (message as? WearMessage.ApprovalResult)?.takeIf { it.requestId == approval.requestId && it.sessionId == approval.sessionId }
            }
            app.log.note("approvalAnswered", mapOf("approve" to approve, "ok" to (result?.ok == true)))
            busy = false
            if (result?.ok == true) {
                Approvals.list.value = Approvals.list.value.filterNot { it.key == approval.key }
                onDone()
            } else {
                error = result?.error ?: t("Phone not connected right now")
            }
        }
    }
}

// endregion

// region Call log and settings

/** The watch's side of the call log, newest first. */
@Composable
fun LogScreen(onClear: () -> Unit) {
    val app = LocalContext.current.wearApp
    val lines by app.log.lines.collectAsStateWithLifecycle()
    WatchList {
        item { ListHeader { Text(t("Call log")) } }
        item { Footnote(t("Every call's log also reaches your phone, where Settings > Watch can share it.")) }
        item {
            Chip(onClick = onClear, label = { Text(t("Clear")) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        items(lines, key = { it.first }) { (_, text) ->
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ConfirmClearScreen(onDone: () -> Unit) {
    val app = LocalContext.current.wearApp
    WatchList {
        item { Text(t("Clear the Watch call log?"), textAlign = TextAlign.Center, style = MaterialTheme.typography.title3) }
        item {
            Chip(
                onClick = { app.log.clear(); onDone() },
                label = { Text(t("Clear")) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFFC62828)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Chip(onClick = onDone, label = { Text(t("Cancel")) }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.wearApp
    val callOnOpen by app.prefs.callOnOpen.collectAsStateWithLifecycle()
    val reachable by app.link.reachable.collectAsStateWithLifecycle()
    WatchList {
        item { ListHeader { Text(t("Settings")) } }
        item {
            ToggleChip(
                checked = callOnOpen,
                onCheckedChange = app.prefs::setCallOnOpen,
                label = { Text(t("Call on open")) },
                toggleControl = { Switch(checked = callOnOpen) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Footnote(t("Opening Conduit starts a call right away. Turn this off to start each call with a tap.")) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = if (reachable) MaterialTheme.colors.onSurface else MaterialTheme.colors.onSurfaceVariant)
                Text(if (reachable) t("Phone connected") else t("Phone not connected right now"), style = MaterialTheme.typography.caption2)
            }
        }
        item { Footnote(t("Calls need Conduit on your phone: each turn runs through it. Once a call is on, it carries on with your wrist down.")) }
        item { Footnote(t("Version %s", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")) }
    }
}

// endregion
