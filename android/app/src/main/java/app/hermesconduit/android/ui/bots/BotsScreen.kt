package app.hermesconduit.android.ui.bots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.BotAvatarColor
import app.hermesconduit.android.model.BotEditorDraft
import app.hermesconduit.android.model.BotMode
import app.hermesconduit.android.model.BotModePhase
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.BotProfileDetails
import app.hermesconduit.android.model.BotSaveResult
import app.hermesconduit.android.ui.appViewModel
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import kotlinx.coroutines.launch
import android.text.format.DateUtils

/** The Bots roster: every Hermes profile as a bot, opened into its one "Bot Chat". */
@Composable
fun BotsScreen(onBack: () -> Unit, navigate: (String) -> Unit, model: BotsViewModel = viewModel()) {
    val app = appViewModel()
    val bots by model.bots.collectAsStateWithLifecycle()
    val phase by model.phase.collectAsStateWithLifecycle()
    val avatars by model.avatars.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val opening by model.opening.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditorMode?>(null) }
    var deleting by remember { mutableStateOf<BotProfile?>(null) }

    LaunchedEffect(Unit) { model.refresh() }
    // conduit://bot/<profile> opens that bot's Bot Chat once the roster knows it.
    val linked = app.hermesconduit.android.ui.LocalRouteArg.current
    var linkHandled by remember { mutableStateOf(false) }
    LaunchedEffect(linked, bots) {
        val bot = linked?.let { name -> bots.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: return@LaunchedEffect
        if (linkHandled) return@LaunchedEffect
        linkHandled = true
        model.openBotChat(bot, app) { navigate("home") }
    }
    val visible = bots.filter { !it.isHiddenByMeta }
    val canManage = phase == BotModePhase.Available

    ScreenScaffold(
        title = t("Bots"),
        onBack = onBack,
        actions = {
            IconButton(onClick = { navigate("groups") }) { Icon(Icons.Default.Groups, t("Group Chats")) }
            IconButton(onClick = { model.reload() }) { Icon(Icons.Default.Refresh, t("Refresh")) }
        },
        floating = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    onClick = { editing = EditorMode.Create },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(t("New Bot")) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            notice?.let { NoticeCard(it, onDismiss = model::dismissNotice) }
            (phase as? BotModePhase.Failed)?.takeIf { visible.isNotEmpty() }?.let { NoticeCard(it.message, onDismiss = null) }
            when {
                visible.isEmpty() -> RosterEmptyState(phase, canManage, onCreate = { editing = EditorMode.Create }, onRetry = { model.refresh() })
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.name }) { bot ->
                        LaunchedEffect(bot.name, bot.hasAvatar) { model.loadAvatar(bot) }
                        BotCard(
                            bot = bot,
                            image = avatars[bot.name].takeIf { bot.hasAvatar },
                            opening = opening == bot.name,
                            canManage = canManage,
                            canDelete = model.canDelete(bot),
                            onOpen = { model.openBotChat(bot, app) { navigate("home") } },
                            onEdit = { editing = EditorMode.Edit(bot) },
                            onTogglePin = { model.setPinned(bot, !bot.isPinned) },
                            onDelete = { deleting = bot },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            t("Each bot is its own profile with one ongoing conversation."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    editing?.let { mode ->
        BotEditorDialog(
            mode = mode,
            model = model,
            profiles = profiles,
            storedImage = (mode as? EditorMode.Edit)?.bot?.let { avatars[it.name] },
            onDismiss = { editing = null },
            onFinished = { createdName, warning ->
                editing = null
                model.setNotice(warning)
                // Desktop opens a new bot's forever chat straight away.
                createdName?.let(BotRoster::bot)?.let { bot -> model.openBotChat(bot, app) { navigate("home") } }
            },
        )
    }
    deleting?.let { bot ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(t("Delete %s?", bot.displayLabel)) },
            text = { Text(t("This permanently removes the bot's profile, chats, memory and skills from the gateway.")) },
            confirmButton = {
                TextButton(onClick = { deleting = null; model.deleteBot(bot, app) }) {
                    Text(t("Delete Bot"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(t("Cancel")) } },
        )
    }
}

sealed interface EditorMode {
    data object Create : EditorMode
    data class Edit(val bot: BotProfile) : EditorMode
}

@Composable
private fun NoticeCard(message: String, onDismiss: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Dismiss")) }
        }
    }
}

@Composable
private fun RosterEmptyState(phase: BotModePhase, canManage: Boolean, onCreate: () -> Unit, onRetry: () -> Unit) {
    when (phase) {
        BotModePhase.Idle -> LoadingBox()
        BotModePhase.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.size(12.dp))
            Text(t("Loading bots…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val (title, detail) = when (phase) {
                BotModePhase.GatewayUnsupported -> t("Update Hermes to use Bot Mode.") to t("Update the gateway, then come back to chat with your bots.")
                is BotModePhase.Failed -> t("Could not load bots.") to phase.message
                else -> t("No Bots Yet") to t("Bots you create with Hermes appear here.")
            }
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (canManage) Button(onClick = onCreate) { Text(t("New Bot")) }
                OutlinedButton(onClick = onRetry) { Text(t("Retry")) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotCard(
    bot: BotProfile,
    image: ImageBitmap?,
    opening: Boolean,
    canManage: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).combinedClickable(
            onClick = onOpen,
            onLongClick = { if (canManage) menu = true },
        ),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BotAvatar(bot.name, bot.displayLabel, bot.appearanceColor, image, 64.dp)
                    if (opening) CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 2.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bot.displayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (bot.isPinned) Icon(Icons.Default.PushPin, t("Pinned"), Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    subtitle(bot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 32.dp),
                )
                activity(bot)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(t("Edit Bot")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text(if (bot.isPinned) t("Unpin") else t("Pin")) },
                    leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    onClick = { menu = false; onTogglePin() },
                )
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text(t("Delete Bot"), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** The latest preview, else the description, else the canonical chat's literal title. */
private fun subtitle(bot: BotProfile): String {
    val preview = (bot.canonicalSession?.preview ?: bot.lastPreview)?.trim().orEmpty()
    if (preview.isNotEmpty()) return preview
    return bot.profileDescription.trim().ifEmpty { BotMode.CANONICAL_CHAT_TITLE }
}

private fun activity(bot: BotProfile): String? {
    val last = bot.lastActive?.takeIf { it > 0 } ?: return null
    val ms = if (last > 10_000_000_000) last.toLong() else (last * 1000).toLong()
    return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

/** A bot's face: its picture, else its initial on its color. */
@Composable
fun BotAvatar(name: String, label: String, color: String?, image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (image != null) {
        Image(image, null, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    val rgb = BotAvatarColor.resolve(color, name)
    Box(
        modifier.size(size).clip(CircleShape).background(Color(rgb.red.toFloat(), rgb.green.toFloat(), rgb.blue.toFloat())),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (maxOf(11f, size.value * 0.42f)).sp,
        )
    }
}

/** New Bot / Edit Bot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotEditorDialog(
    mode: EditorMode,
    model: BotsViewModel,
    profiles: List<String>,
    storedImage: ImageBitmap?,
    onDismiss: () -> Unit,
    onFinished: (createdName: String?, warning: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edited = (mode as? EditorMode.Edit)?.bot
    val creating = edited == null
    var draft by remember { mutableStateOf(BotEditorDraft()) }
    var details by remember { mutableStateOf<BotProfileDetails?>(null) }
    // "idle" | "loading" | "loaded" | "failed"
    var detailsState by remember { mutableStateOf(if (creating) "loaded" else "loading") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickedImageError = t("That picture could not be used.")

    LaunchedEffect(edited?.name) {
        val bot = edited ?: return@LaunchedEffect
        draft = draft.copy(name = bot.botTitle.orEmpty(), description = bot.profileDescription, color = bot.appearanceColor)
        model.loadAvatar(bot)
        val loaded = model.loadDetails(bot)
        if (loaded == null) {
            detailsState = "failed"
        } else {
            details = loaded
            draft = draft.copy(description = if (draft.description == bot.profileDescription) loaded.description else draft.description, soul = loaded.soul)
            detailsState = "loaded"
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val png = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let(BotRoster::normalizedPng)
        if (png == null) error = pickedImageError
        else {
            draft = draft.copy(newAvatarPng = png, removesAvatar = false)
            error = null
        }
    }

    val identity = model.newBotSlug(draft.name, profiles)
    val canSave = !saving && if (creating) identity.isValid && !identity.isTaken else detailsState == "loaded" || detailsState == "failed"
    val preview: ImageBitmap? = remember(draft.newAvatarPng, draft.removesAvatar, storedImage) {
        draft.newAvatarPng?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            ?: if (draft.removesAvatar) null else storedImage
    }
    val hasStored = edited?.hasAvatar == true

    fun save() {
        if (!canSave) return
        saving = true
        error = null
        val snapshot = draft
        scope.launch {
            val created = if (creating) identity.slug else null
            val result = if (edited != null) model.updateBot(edited, snapshot, details) else model.createBot(snapshot, profiles)
            saving = false
            when (result) {
                is BotSaveResult.Failed -> error = result.message
                is BotSaveResult.Saved -> onFinished(created, result.warning)
            }
        }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (creating) t("New Bot") else t("Edit Bot")) },
                    navigationIcon = { TextButton(onClick = onDismiss, enabled = !saving) { Text(t("Cancel")) } },
                    actions = {
                        if (saving) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = ::save, enabled = canSave) { Text(if (creating) t("Create") else t("Save")) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val avatarName = edited?.name ?: identity.slug
                val avatarLabel = draft.name.trim().ifEmpty { edited?.displayLabel ?: "?" }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotAvatar(avatarName, avatarLabel, draft.color, preview, 88.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(t("Choose Photo")) }
                        if (preview != null || (hasStored && !draft.removesAvatar)) {
                            TextButton(onClick = {
                                draft = if (draft.newAvatarPng != null) draft.copy(newAvatarPng = null) else draft.copy(removesAvatar = hasStored)
                            }) { Text(t("Remove Photo"), color = MaterialTheme.colorScheme.error) }
                        } else if (draft.removesAvatar) {
                            TextButton(onClick = { draft = draft.copy(removesAvatar = false) }) { Text(t("Keep Photo")) }
                        }
                    }
                }
                Section(t("Color"), t("Shown behind the bot's initial when it has no picture.")) {
                    val swatches = listOf<String?>(null) + BotAvatarColor.swatches
                    swatches.chunked(7).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { value ->
                                val rgb = BotAvatarColor.resolve(value, avatarName)
                                val selected = draft.color == value
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape)
                                        .background(Color(rgb.red.toFloat(), rgb.green.toFloat(), rgb.blue.toFloat()))
                                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { draft = draft.copy(color = value) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (value == null) Text(t("Auto"), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                if (edited != null) {
                    Section(t("Display Name"), t("Profile ID: %s", edited.name)) {
                        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, placeholder = { Text(edited.displayName.ifEmpty { edited.name }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val footer = when {
                        draft.name.isBlank() -> t("The bot gets its own Hermes profile, chat, memory and skills.")
                        !identity.isValid -> t("Use at least one letter or number.")
                        identity.isTaken -> t("A profile named %s already exists.", identity.slug)
                        else -> t("Profile ID: %s", identity.slug)
                    }
                    Section(t("Name"), footer) {
                        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, placeholder = { Text(t("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                Section(t("Description"), null) {
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it) }, placeholder = { Text(t("What this bot is for")) }, modifier = Modifier.fillMaxWidth())
                }
                Section(
                    t("Personality"),
                    if (creating) t("Optional. Leave empty to start from a short identity built from the name and description.") else t("Saved as the bot's SOUL.md."),
                ) {
                    when (detailsState) {
                        "loaded" -> OutlinedTextField(draft.soul, { draft = draft.copy(soul = it) }, minLines = 6, modifier = Modifier.fillMaxWidth())
                        "failed" -> Text(t("Could not load this bot's personality."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, footer: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
