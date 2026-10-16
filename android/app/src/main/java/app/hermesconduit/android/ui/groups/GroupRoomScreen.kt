package app.hermesconduit.android.ui.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.Attachment
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.GroupEvent
import app.hermesconduit.android.model.GroupMember
import app.hermesconduit.android.model.GroupMessageParts
import app.hermesconduit.android.model.GroupPendingApproval
import app.hermesconduit.android.model.GroupRoomMentions
import app.hermesconduit.android.model.GroupRoomTurns
import app.hermesconduit.android.model.MentionAutocomplete
import app.hermesconduit.android.ui.AttachmentChip
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.MentionSuggestionList
import app.hermesconduit.android.ui.copyToClipboard
import app.hermesconduit.android.ui.markdown.RichMarkdown
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One hosted room (route `groups.room/<room id>`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupRoomScreen(onBack: () -> Unit) {
    val roomId = LocalRouteArg.current ?: return
    val model = groupsViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val surface by model.room.collectAsStateWithLifecycle()
    val roster by model.bots.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var menu by remember { mutableStateOf(false) }
    var confirmDisband by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(roomId) { model.openRoom(roomId) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.setForeground(true)
                Lifecycle.Event.ON_STOP -> model.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.closeRoom()
        }
    }
    LaunchedEffect(surface?.gone) { if (surface?.gone == true) onBack() }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bytes = runCatching { model.getApplication<android.app.Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) model.setPicture(roomId, bytes)
        }
    }

    val room = surface?.takeIf { it.roomId == roomId }
    val members = room?.members.orEmpty()
    val approvals = room?.let(model::pendingApprovals).orEmpty()
    val responder = room?.responder
    val subtitle = when {
        approvals.isNotEmpty() -> t("Approval needed")
        responder != null -> t("%s is responding…", GroupRoomTurns.displayName(responder, t("A member")))
        room?.driver?.working == true -> t("%d members · working…", members.size)
        else -> t("%d members", members.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Leave this group chat")) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GroupRoomIcon(state.pictures[roomId], members, roster, 34.dp)
                        Column {
                            Text(room?.room?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, t("Group chat actions")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (state.canSetPictures) {
                            DropdownMenuItem(text = { Text(t("Choose Group Picture")) }, onClick = {
                                menu = false
                                pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            if (model.hasCustomPicture(roomId)) {
                                DropdownMenuItem(text = { Text(t("Remove Group Picture")) }, onClick = { menu = false; model.setPicture(roomId, null) })
                            }
                        }
                        if (state.supports("groups.rename")) {
                            DropdownMenuItem(text = { Text(t("Rename")) }, onClick = { menu = false; renaming = room?.room?.name.orEmpty() })
                        }
                        val driver = room?.driver
                        if (state.supports("groups.stop") && driver != null && (driver.running || driver.working)) {
                            DropdownMenuItem(text = { Text(t("Stop")) }, onClick = { menu = false; model.stop() })
                        }
                        if (state.supports("groups.disband")) {
                            DropdownMenuItem(text = { Text(t("Disband Group"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDisband = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            RoomTranscript(room, roster, Modifier.weight(1f), onRetry = model::retrySend)
            if (approvals.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    approvals.forEach { approval ->
                        RoomApprovalCard(approval, memberName(approval.memberId, members, t("A member")), room?.approvalAnswers?.get(approval.id)) { choice ->
                            model.approve(approval, choice)
                        }
                    }
                }
            }
            if (room != null) GroupRoomComposer(model, room, state, members)
        }
    }

    if (confirmDisband) {
        AlertDialog(
            onDismissRequest = { confirmDisband = false },
            title = { Text(t("Disband this group chat?")) },
            text = { Text(t("The room, its history, and every member's session end permanently. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = { confirmDisband = false; model.disband {} }) { Text(t("Disband"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDisband = false }) { Text(t("Cancel")) } },
        )
    }
    renaming?.let { current ->
        var name by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("Rename")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(t("Group name")) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; model.rename(name) }) { Text(t("Save")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("Cancel")) } },
        )
    }
}

private fun memberName(memberId: String, members: List<GroupMember>, fallback: String): String =
    GroupRoomTurns.memberWithId(memberId, members)?.let { GroupRoomTurns.displayName(it, fallback) }
        ?: memberId.trim().ifEmpty { fallback }

@Composable
private fun RoomTranscript(room: RoomSurface?, roster: List<BotProfile>, modifier: Modifier, onRetry: (String) -> Unit) {
    val events = room?.visibleEvents.orEmpty()
    val members = room?.members.orEmpty()
    val responder = room?.responder
    val pending = room?.pending
    val listState = rememberLazyListState()
    LaunchedEffect(room?.replay?.cursor, responder?.identityKey, pending?.eventId) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    if (room == null || (room.loading && events.isEmpty())) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (events.isEmpty() && pending == null && responder == null) {
            item(key = "empty") {
                Text(
                    t("No messages yet. Say something to the room."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        itemsIndexed(events, key = { _, e -> e.id }) { index, event ->
            val previous = events.getOrNull(index - 1)
            EventRow(event, members, roster, continuesRun = previous?.let { sameSpeaker(it, event) } ?: false)
        }
        if (pending != null) {
            item(key = "pending-row") {
                UserBubble(pending.text, members, null) {
                    if (room.sendInFlight) Text(t("Sending…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton(onClick = { onRetry(pending.text) }) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Not delivered — retry"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (responder != null) {
            item(key = "responding-row") { RespondingRow(responder, roster) }
        }
    }
}

private fun sameSpeaker(previous: GroupEvent, next: GroupEvent): Boolean {
    if (previous.isUserMessage && next.isUserMessage) return true
    if (!previous.isMemberMessage || !next.isMemberMessage) return false
    val key = previous.actor.id.trim()
    return key.isNotEmpty() && key == next.actor.id.trim()
}

@Composable
private fun EventRow(event: GroupEvent, members: List<GroupMember>, roster: List<BotProfile>, continuesRun: Boolean) {
    when {
        event.isUserMessage -> UserBubble(event.messageText.orEmpty(), members, event.createdAt) {}
        event.isMemberMessage -> MemberBubble(
            GroupRoomTurns.member(event, members),
            event.actor.displayLabel(members).ifEmpty { t("Member") },
            event.messageText.orEmpty(),
            members,
            roster,
            event.createdAt,
            continuesRun,
        )
        else -> Text(
            systemCaption(event, members),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** `turn.*` events name their member; only `room.activity` speaks for the room. */
@Composable
private fun systemCaption(event: GroupEvent, members: List<GroupMember>): String {
    val name = GroupRoomTurns.member(event, members)?.let { GroupRoomTurns.displayName(it, t("A member")) }.orEmpty()
    return when (event.kind) {
        "room.renamed" -> t("The room was renamed.")
        "room.members_changed" -> t("The room's members changed.")
        "room.disbanded" -> t("This room was disbanded.")
        "room.stop_requested" -> t("Stop requested.")
        "room.activity" -> when (GroupRoomTurns.roomActivityOutcome(event)) {
            GroupRoomTurns.RoomActivityOutcome.SETTLED -> t("The room settled.")
            GroupRoomTurns.RoomActivityOutcome.BOUNDED -> t("The conversation reached its turn limit.")
            GroupRoomTurns.RoomActivityOutcome.OTHER -> t("Room updated.")
        }
        "turn.settled" -> if (name.isEmpty()) t("A member passed.") else t("%s passed.", name)
        "turn.failed" -> if (name.isEmpty()) t("A member's turn failed.") else t("%s's turn failed.", name)
        "turn.cancelled" -> if (name.isEmpty()) t("Work was stopped.") else t("%s's turn was stopped.", name)
        "member.unavailable" -> if (name.isEmpty()) t("A member is unavailable.") else t("%s is unavailable.", name)
        else -> t("Room updated.")
    }
}

private fun time(seconds: Double?): String? =
    seconds?.takeIf { it > 0 }?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((it * 1000).toLong())) }

/** Mention spans tinted as the room classifies them. */
@Composable
private fun mentionText(text: String, members: List<GroupMember>, onAccent: Boolean): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return remember(text, members, onAccent, accent) {
        buildAnnotatedString {
            append(text)
            for (match in GroupRoomMentions.mentionScan.findAll(text)) {
                val kind = GroupRoomMentions.classify(match.groupValues[1], members) ?: continue
                val style = if (onAccent) SpanStyle(color = Color.White, background = Color.White.copy(alpha = 0.22f), fontWeight = FontWeight.SemiBold)
                else SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    background = when (kind) {
                        GroupRoomMentions.Kind.AGENT -> accent.copy(alpha = 0.10f)
                        GroupRoomMentions.Kind.BROADCAST -> Color(0xFFFF9500).copy(alpha = 0.10f)
                        GroupRoomMentions.Kind.HUMAN -> Color(0xFFFFCC00).copy(alpha = 0.12f)
                    },
                )
                addStyle(style, match.range.first, match.range.last + 1)
            }
        }
    }
}

@Composable
private fun FileChips(paths: List<String>, onAccent: Boolean) {
    paths.forEach { path ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Description, null, Modifier.size(18.dp), tint = if (onAccent) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(GroupMessageParts.fileName(path), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (onAccent) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun UserBubble(text: String, members: List<GroupMember>, timestamp: Double?, footer: @Composable () -> Unit) {
    val context = LocalContext.current
    val (body, files) = remember(text) { GroupMessageParts.split(text) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(21.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileChips(files, onAccent = true)
            if (body.isNotEmpty()) SelectionContainer { Text(mentionText(body, members, onAccent = true), color = MaterialTheme.colorScheme.onPrimary) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            footer()
            time(timestamp)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (body.isNotEmpty()) IconButton(onClick = { copyToClipboard(context, body) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, t("Copy message"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MemberBubble(
    member: GroupMember?,
    name: String,
    text: String,
    members: List<GroupMember>,
    roster: List<BotProfile>,
    timestamp: Double?,
    continuesRun: Boolean,
) {
    val context = LocalContext.current
    val (body, files) = remember(text) { GroupMessageParts.split(text) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (continuesRun) Spacer(Modifier.width(30.dp)) else GroupMemberAvatar(member, name, roster, 30.dp)
        Column(Modifier.weight(1f, fill = false).padding(end = 32.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!continuesRun) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    time(timestamp)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Column(
                Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileChips(files, onAccent = false)
                if (body.isNotEmpty()) RichMarkdown(body)
            }
            if (body.isNotEmpty()) IconButton(onClick = { copyToClipboard(context, body) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, t("Copy response"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The member whose turn is running, as a placeholder where their reply will land. */
@Composable
private fun RespondingRow(member: GroupMember, roster: List<BotProfile>) {
    val name = GroupRoomTurns.displayName(member, t("A member"))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        GroupMemberAvatar(member, name, roster, 30.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.clip(RoundedCornerShape(19.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text(t("Responding…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A member's tool call the room is holding: Run (once) or Reject. */
@Composable
private fun RoomApprovalCard(approval: GroupPendingApproval, memberName: String, answer: RoomApprovalAnswer?, respond: (String) -> Unit) {
    val submitting = answer == RoomApprovalAnswer.Submitting
    val failure = (answer as? RoomApprovalAnswer.Failed)?.message
    val orange = Color(0xFFFF9500)
    Surface(color = orange.copy(alpha = 0.10f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, null, tint = orange)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            submitting -> t("SENDING DECISION")
                            failure == null -> t("APPROVAL NEEDED")
                            else -> t("TRY AGAIN")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (failure == null) orange else MaterialTheme.colorScheme.error,
                    )
                    Text(memberName, style = MaterialTheme.typography.titleSmall)
                    if (approval.description.isNotEmpty()) Text(approval.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (approval.command.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        approval.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { respond("once") }, enabled = approval.allows("once") && !submitting) { Text(t("Run")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { respond("deny") }, enabled = approval.allows("deny") && !submitting) {
                    Text(t("Reject"), color = MaterialTheme.colorScheme.error)
                }
            }
            failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * The room composer: text with @member completion and attachments, sent
 * through `groups.send`. Files go up through the host plugin first.
 */
@Composable
private fun GroupRoomComposer(model: GroupsViewModel, room: RoomSurface, state: GroupsState, members: List<GroupMember>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(room.roomId) { mutableStateOf(model.draft(room.roomId)) }
    val attachments = remember(room.roomId) { mutableStateListOf<Attachment>() }
    var menu by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) { model.saveDraft(room.roomId, draft) }

    fun add(uri: android.net.Uri?) {
        if (uri == null) return
        try {
            attachments += Attachment.read(context, uri)
            localError = null
        } catch (e: Exception) {
            localError = e.message
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { it.forEach(::add) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { it.forEach(::add) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) add(cameraUri) }

    val roomAccepts = !room.sendInFlight && room.pending == null && state.supports("groups.send") && room.room?.isDisbanded != true
    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && roomAccepts && !room.uploading

    fun send() {
        if (!canSend) return
        val text = draft
        val files = attachments.toList()
        draft = ""
        attachments.clear()
        scope.launch {
            val registered = if (files.isEmpty()) model.send(text) else model.sendWithAttachments(text, files)
            if (!registered) {
                // Nothing holds the text: hand it (and the files) back.
                draft = if (draft.isEmpty()) text else text + "\n" + draft
                attachments.addAll(0, files)
            }
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        val notices = buildList {
            if (room.sendInFlight && room.pending == null) add(t("Sending…"))
            if (room.uploading) add(t("Uploading files…"))
            if (room.pending != null && !room.sendInFlight) add(t("Your previous message is still pending. Retry it from the room, or wait for it to deliver."))
        }
        notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        (localError ?: room.error)?.let { error ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { localError = null; model.dismissRoomError() }) { Text(t("Dismiss")) }
            }
        }
        val mentions = remember(draft, members) {
            MentionAutocomplete.activeQuery(draft)?.let { MentionAutocomplete.filter(MentionAutocomplete.roomCandidates(members, "Everyone"), it) }.orEmpty()
        }
        MentionSuggestionList(mentions.map { if (it.tag == "all") it.copy(title = t("Everyone")) else it }) { draft = MentionAutocomplete.completing(draft, it) }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a -> AttachmentChip(a) { if (!room.uploading) attachments.remove(a) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !room.uploading, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, t("Attach")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (state.canAttachFiles) {
                        DropdownMenuItem(text = { Text(t("Camera")) }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) }, onClick = {
                            menu = false
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "photo-${System.currentTimeMillis()}.jpg"))
                            cameraUri = uri
                            takePicture.launch(uri)
                        })
                        DropdownMenuItem(text = { Text(t("Photo Library")) }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) }, onClick = {
                            menu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                        DropdownMenuItem(text = { Text(t("Document")) }, leadingIcon = { Icon(Icons.Default.AttachFile, null) }, onClick = {
                            menu = false
                            pickFile.launch(arrayOf("*/*"))
                        })
                    } else {
                        DropdownMenuItem(
                            text = { Text(t("Update the Conduit plugin on your Hermes host to send files to group chats.")) },
                            onClick = { menu = false },
                        )
                    }
                }
            }
            TextField(
                value = draft,
                onValueChange = { draft = it; localError = null },
                placeholder = { Text(t("Message the room")) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 200.dp),
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = ::send, enabled = canSend, modifier = Modifier.size(52.dp)) {
                if (room.uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, t("Send"))
            }
        }
    }
}
