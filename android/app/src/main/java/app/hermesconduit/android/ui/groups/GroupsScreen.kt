package app.hermesconduit.android.ui.groups

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.DesktopGroupChat
import app.hermesconduit.android.model.GroupCreateAttempt
import app.hermesconduit.android.model.GroupMember
import app.hermesconduit.android.model.GroupRoom
import app.hermesconduit.android.model.GroupRoomTurns
import app.hermesconduit.android.ui.bots.BotAvatar
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

/** The activity-wide [GroupsViewModel]: the room list and the open room share it. */
@Composable
fun groupsViewModel(): GroupsViewModel {
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    return androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = activity)
}

/** Hosted group chats on this gateway, plus Hermes Desktop's mirrored ones. */
@Composable
fun GroupsScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val model = groupsViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val bots by model.bots.collectAsStateWithLifecycle()
    val desktop by model.desktopGroups.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var desktopOpen by remember { mutableStateOf<DesktopGroupChat?>(null) }
    LaunchedEffect(Unit) { model.refresh() }
    val visibleBots = bots.filter { !it.isHiddenByMeta }
    val available = state.phase == GroupChatPhase.Available

    ScreenScaffold(
        title = t("Group Chats"),
        onBack = onBack,
        actions = { IconButton(onClick = { model.refresh() }) { Icon(Icons.Default.Refresh, t("Refresh")) } },
        floating = {
            if (available && visibleBots.size >= 2) {
                ExtendedFloatingActionButton(
                    onClick = { model.clearError(); creating = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(t("New Group Chat")) },
                )
            }
        },
    ) { padding ->
        val rows = if (available) state.rooms else emptyList()
        when {
            rows.isEmpty() && desktop.isEmpty() -> GroupsEmptyState(state.phase, Modifier.padding(padding), onRetry = { model.refresh() })
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                (state.phase as? GroupChatPhase.Failed)?.let { item { ErrorText(it.message) } }
                items(rows, key = { it.roomId }) { room ->
                    RoomRow(
                        title = room.name,
                        subtitle = t("%d members", room.members.size),
                        onClick = { navigate("groups.room/${Uri.encode(room.roomId)}") },
                    ) { GroupRoomIcon(state.pictures[room.roomId], room.members, bots, it) }
                }
                items(desktop, key = { "desktop:" + it.key }) { group ->
                    val last = group.messages.lastOrNull()?.text?.trim().orEmpty()
                    RoomRow(
                        title = group.name,
                        subtitle = last.ifEmpty { t("%d members", group.members.size) },
                        trailing = { Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { desktopOpen = group },
                    ) {
                        val picture = group.roomId?.let { state.pictures[it] }
                        GroupRoomIcon(picture, group.groupMembers, bots, it)
                    }
                }
                if (available && visibleBots.size < 2) {
                    item {
                        Text(
                            t("Pick 2 to 6 bots for a group chat."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        GroupCreateDialog(model, visibleBots, onDismiss = { creating = false }) { room ->
            creating = false
            navigate("groups.room/${Uri.encode(room.roomId)}")
        }
    }
    desktopOpen?.let { DesktopGroupDialog(it, bots, onDismiss = { desktopOpen = null }) }
}

@Composable
private fun GroupsEmptyState(phase: GroupChatPhase, modifier: Modifier, onRetry: () -> Unit) {
    when (phase) {
        GroupChatPhase.Idle, GroupChatPhase.Loading -> LoadingBox(modifier)
        else -> Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Groups, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (phase) {
                    GroupChatPhase.GatewayUnsupported -> t("Update Hermes to use group chats.")
                    is GroupChatPhase.Failed -> phase.message
                    else -> t("Group chats run on this gateway. The gateway drives each member's turn — closing Conduit never stops a room.")
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (phase !is GroupChatPhase.Available) OutlinedButton(onClick = onRetry) { Text(t("Retry")) }
        }
    }
}

@Composable
private fun RoomRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    icon: @Composable (Dp) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon(44.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** The roster bot a room member is: by profile, else by name. */
fun botFor(member: GroupMember?, name: String, roster: List<BotProfile>): BotProfile? {
    member?.profile?.trim()?.takeIf { it.isNotEmpty() }?.let { p -> roster.firstOrNull { it.name == p }?.let { return it } }
    val label = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return roster.firstOrNull { it.displayLabel == label || it.name == label }
}

@Composable
fun GroupMemberAvatar(member: GroupMember?, name: String, roster: List<BotProfile>, size: Dp, modifier: Modifier = Modifier) {
    val model = groupsViewModel()
    val avatars by model.botAvatars.collectAsStateWithLifecycle()
    val bot = botFor(member, name, roster)
    if (bot != null) {
        LaunchedEffect(bot.name, bot.hasAvatar) { model.loadAvatar(bot) }
        BotAvatar(bot.name, bot.displayLabel, bot.appearanceColor, avatars[bot.name].takeIf { bot.hasAvatar }, size, modifier)
    } else {
        BotAvatar(name, name, null, null, size, modifier)
    }
}

/** A room's picture, else up to three members' pictures packed in one circle. */
@Composable
fun GroupRoomIcon(picture: ImageBitmap?, members: List<GroupMember>, roster: List<BotProfile>, size: Dp) {
    when {
        picture != null -> Image(picture, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        members.isEmpty() -> Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Groups, null, Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val shown = members.take(3)
            val spots = when (shown.size) {
                1 -> listOf(Triple(0f, 0f, 1f))
                2 -> listOf(Triple(-0.17f, -0.17f, 0.64f), Triple(0.17f, 0.17f, 0.64f))
                else -> listOf(Triple(0f, -0.22f, 0.54f), Triple(-0.22f, 0.17f, 0.54f), Triple(0.22f, 0.17f, 0.54f))
            }
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                shown.forEachIndexed { i, member ->
                    val (x, y, d) = spots[i]
                    GroupMemberAvatar(member, GroupRoomTurns.displayName(member), roster, size * d, Modifier.offset(size * x, size * y))
                }
            }
        }
    }
}

/** New Group Chat: a name plus 2–6 bots from this gateway's roster. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCreateDialog(model: GroupsViewModel, bots: List<BotProfile>, onDismiss: () -> Unit, onCreated: (GroupRoom) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // One id per logical room: an ambiguous create's retry reuses it.
    var roomId by remember { mutableStateOf(GroupCreateAttempt.mintRoomId()) }
    var attempted by remember { mutableStateOf<GroupCreateAttempt.Inputs?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canCreate = name.isNotBlank() && selected.size in 2..6 && !busy

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(t("New Group Chat")) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } },
                    actions = {
                        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else TextButton(enabled = canCreate, onClick = {
                            busy = true
                            val inputs = GroupCreateAttempt.Inputs(name.trim(), selected)
                            roomId = GroupCreateAttempt.roomId(inputs, roomId, attempted)
                            attempted = inputs
                            val id = roomId
                            scope.launch {
                                val room = model.createRoom(name, bots.filter { it.name in selected }, id)
                                busy = false
                                room?.let(onCreated)
                            }
                        }) { Text(t("Create")) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                state.error?.let { item { ErrorText(it) } }
                item {
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text(t("Name")) },
                        placeholder = { Text(t("Group name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                item {
                    Text(t("Members (2–6)").uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(bots, key = { it.name }) { bot ->
                    val on = bot.name in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (on) selected - bot.name else selected + bot.name }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GroupMemberAvatar(GroupMember(profile = bot.name), bot.displayLabel, bots, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(bot.displayLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(bot.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text(
                        t("Group chats run on this gateway. The gateway drives each member's turn — closing Conduit never stops a room."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Read-only transcript of a Desktop group chat (Desktop runs its rounds). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopGroupDialog(group: DesktopGroupChat, roster: List<BotProfile>, onDismiss: () -> Unit) {
    val members = group.groupMembers
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t("%d members", group.members.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = { TextButton(onClick = onDismiss) { Text(t("Done")) } },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        t("Created in Hermes Desktop. Desktop runs this group's conversation, so reply from there; this shows the latest synced messages."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (group.omitted > 0) item {
                    Text(t("Earlier messages are only in Hermes Desktop."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                items(group.messages, key = { it.id }) { message ->
                    Column {
                        val speaker = message.speaker.trim()
                        if (message.isMember) MemberBubble(null, speaker.ifEmpty { t("Member") }, message.text, members, roster, message.timestamp, continuesRun = false)
                        else UserBubble(message.text, members, message.timestamp) {}
                        if (message.truncated) {
                            Text(t("Shortened — the full message is in Hermes Desktop."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (group.messages.isEmpty()) item { Text(t("No messages yet."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }
}
