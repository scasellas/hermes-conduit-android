package app.hermesconduit.android.ui.artifacts

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertChart
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.ArtifactFilter
import app.hermesconduit.android.model.ArtifactItem
import app.hermesconduit.android.model.ArtifactParser
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.gateway.userMessage
import app.hermesconduit.android.ui.nav.activityAppViewModel
import app.hermesconduit.android.ui.nav.openChatById
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

private sealed interface Viewer {
    data class Picture(val name: String, val bitmap: Bitmap) : Viewer
    data class Page(val name: String, val html: String) : Viewer
}

/** Route `artifacts` (every chat in the profile) and `artifacts/<sessionId>` (one chat). */
@Composable
fun ArtifactsScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val sessionId = LocalRouteArg.current
    val chatIsOpen = sessionId != null
    val viewModel: ArtifactsViewModel = viewModel()
    LaunchedEffect(sessionId) { viewModel.start(sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val app = activityAppViewModel()
    var viewer by remember { mutableStateOf<Viewer?>(null) }
    var missing by remember { mutableStateOf<ArtifactItem?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    fun showInChat(item: ArtifactItem) {
        app?.openChatById(item.sessionId, item.sessionTitle, navigate)
    }

    fun open(item: ArtifactItem) {
        if (item.path == null) {
            item.url?.let { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) }
            return
        }
        if (!item.exists) {
            missing = item
            return
        }
        scope.launch {
            try {
                val file = viewModel.read(item) ?: return@launch
                when (item.kind) {
                    ArtifactItem.Kind.PAGE -> viewer = Viewer.Page(item.name, file.bytes.toString(Charsets.UTF_8))
                    ArtifactItem.Kind.IMAGE -> {
                        val bitmap = HostFiles.decodeBitmap(file.bytes, 2048)
                        if (bitmap != null) viewer = Viewer.Picture(item.name, bitmap)
                        else HostFiles.open(context, HostFiles.stage(context, file), file.mimeType)
                    }
                    else -> HostFiles.open(context, HostFiles.stage(context, file), file.mimeType)
                }
            } catch (e: Exception) {
                failed = e.userMessage()
            }
        }
    }

    fun shareFile(item: ArtifactItem) {
        scope.launch {
            try {
                val file = viewModel.read(item) ?: return@launch
                HostFiles.share(context, HostFiles.stage(context, file), file.mimeType)
            } catch (e: Exception) {
                failed = e.userMessage()
            }
        }
    }

    val actions = ArtifactActions(
        open = ::open,
        showInChat = ::showInChat,
        copy = { clipboard.setText(AnnotatedString(it)) },
        shareLink = { url ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
            context.startActivity(android.content.Intent.createChooser(send, null))
        },
        shareFile = ::shareFile,
    )

    ScreenScaffold(if (chatIsOpen) t("In This Chat") else t("Artifacts"), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.supported == true) {
                OutlinedTextField(
                    state.query, viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(t("Search names and chats")) },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtifactFilter.entries.forEach { option ->
                        FilterChip(selected = state.filter == option, onClick = { viewModel.setFilter(option) }, label = { Text(t(option.title)) })
                    }
                }
            }
            val currentKey = state.filter to state.query.trim()
            when {
                state.supported == null -> LoadingBox()
                state.supported == false -> Unavailable(
                    t("Update the Conduit plugin"),
                    t("Update the Conduit plugin on your Hermes host to see artifacts here. Settings › Conduit plugin can update it."),
                    action = t("Conduit plugin") to { navigate("settings.plugin") },
                )
                state.items.isEmpty() && (state.loading || state.loadedKey != currentKey) -> LoadingBox()
                state.items.isEmpty() && state.failure != null -> Unavailable(
                    t("Artifacts couldn't load"), state.failure!!, action = t("Try Again") to { viewModel.reload() },
                )
                state.items.isEmpty() && state.nextBefore == null -> if (state.query.isBlank() && state.filter == ArtifactFilter.ALL) {
                    if (chatIsOpen) Unavailable(t("No Artifacts Yet"), t("Files, pages and links from this chat show up here once Hermes has saved it."))
                    else Unavailable(t("No Artifacts Yet"), t("Files Hermes sends or writes, pages it draws, files you attach and links show up here."))
                } else {
                    Unavailable(t("No Results"), t("Nothing here matches."))
                }
                else -> ArtifactList(state, chatIsOpen, viewModel, actions, dimmed = state.loadedKey != currentKey)
            }
        }
    }

    when (val v = viewer) {
        is Viewer.Picture -> ImageViewer(v.name, v.bitmap) { viewer = null }
        is Viewer.Page -> PageViewer(v.name, v.html) { viewer = null }
        null -> Unit
    }
    missing?.let { item ->
        AlertDialog(
            onDismissRequest = { missing = null },
            title = { Text(t("This file is no longer on your Hermes host.")) },
            text = { Text(item.path.orEmpty()) },
            confirmButton = { TextButton(onClick = { missing = null }) { Text(t("OK")) } },
        )
    }
    failed?.let { message ->
        AlertDialog(
            onDismissRequest = { failed = null },
            title = { Text(t("Couldn’t open file")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failed = null }) { Text(t("OK")) } },
        )
    }
}

private class ArtifactActions(
    val open: (ArtifactItem) -> Unit,
    val showInChat: (ArtifactItem) -> Unit,
    val copy: (String) -> Unit,
    val shareLink: (String) -> Unit,
    val shareFile: (ArtifactItem) -> Unit,
)

@Composable
private fun Unavailable(title: String, detail: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        action?.let { (label, run) -> Button(onClick = run, modifier = Modifier.padding(top = 12.dp)) { Text(label) } }
    }
}

@Composable
private fun ArtifactList(state: ArtifactsState, chatIsOpen: Boolean, viewModel: ArtifactsViewModel, actions: ArtifactActions, dimmed: Boolean) {
    val days = ArtifactParser.days(state.items)
    LazyColumn(
        Modifier.fillMaxSize().alpha(if (dimmed) 0.45f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEach { day ->
            item(key = "day:${day.day}") {
                Text(
                    dayTitle(day.day).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val visual = day.items.filter { it.isVisual }
            visual.chunked(3).forEachIndexed { index, chunk ->
                item(key = "grid:${day.day}:$index") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEach { item -> Tile(item, state.openingId == item.id, viewModel, actions, Modifier.weight(1f)) }
                        repeat(3 - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            items(day.items.filter { !it.isVisual }, key = { "row:" + it.id }) { item ->
                ArtifactRow(item, chatIsOpen, state.openingId == item.id, actions)
            }
        }
        if (state.nextBefore != null) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
                    if (state.failure != null) {
                        TextButton(onClick = viewModel::retryMore) { Text(t("Try Again")) }
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        // Reaching the end reads the next page.
                        LaunchedEffect(state.nextBefore) { viewModel.loadMore() }
                    }
                }
            }
        }
    }
}

@Composable
private fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> t("Today")
        today.minusDays(1) -> t("Yesterday")
        else -> {
            val pattern = if (day.year == today.year) "EEEE, MMMM d" else null
            if (pattern != null) day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
            else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: ArtifactItem, opening: Boolean, viewModel: ArtifactsViewModel, actions: ArtifactActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val thumbnail by produceState<Bitmap?>(null, item.id) { value = viewModel.thumbnail(item) }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .alpha(if (item.exists) 1f else 0.45f)
            .combinedClickable(onClick = { actions.open(item) }, onLongClick = { menu = true }),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(if (item.kind == ArtifactItem.Kind.VIDEO) Icons.Default.PlayArrow else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                item.name,
                Modifier.align(Alignment.BottomStart).padding(7.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (opening) CircularProgressIndicator(Modifier.size(24.dp))
        ArtifactMenu(item, menu, { menu = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtifactRow(item: ArtifactItem, chatIsOpen: Boolean, opening: Boolean, actions: ArtifactActions) {
    var menu by remember { mutableStateOf(false) }
    val (icon, tint) = kindIcon(item.kind)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (item.exists) 1f else 0.55f)
            .combinedClickable(onClick = { actions.open(item) }, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(detail(item, chatIsOpen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        ArtifactMenu(item, menu, { menu = false }, actions)
    }
}

@Composable
private fun ArtifactMenu(item: ArtifactItem, expanded: Boolean, onDismiss: () -> Unit, actions: ArtifactActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(t("Open")) }, onClick = { onDismiss(); actions.open(item) })
        DropdownMenuItem(text = { Text(t("Show in Chat")) }, onClick = { onDismiss(); actions.showInChat(item) })
        val url = item.url
        val path = item.path
        if (url != null) {
            DropdownMenuItem(text = { Text(t("Share")) }, onClick = { onDismiss(); actions.shareLink(url) })
            DropdownMenuItem(text = { Text(t("Copy Link")) }, onClick = { onDismiss(); actions.copy(url) })
        } else if (path != null) {
            if (item.exists) DropdownMenuItem(text = { Text(t("Share")) }, onClick = { onDismiss(); actions.shareFile(item) })
            DropdownMenuItem(text = { Text(t("Copy Path")) }, onClick = { onDismiss(); actions.copy(path) })
        }
    }
}

private fun kindIcon(kind: ArtifactItem.Kind): Pair<ImageVector, Color> = when (kind) {
    ArtifactItem.Kind.IMAGE -> Icons.Default.Image to Color(0xFFAF52DE)
    ArtifactItem.Kind.VIDEO -> Icons.Default.PlayArrow to Color(0xFFAF52DE)
    ArtifactItem.Kind.AUDIO -> Icons.Default.AudioFile to Color(0xFF007AFF)
    ArtifactItem.Kind.PAGE -> Icons.Default.InsertChart to Color(0xFF5E5CE6)
    ArtifactItem.Kind.DOCUMENT -> Icons.Default.Description to Color(0xFFFF3B30)
    ArtifactItem.Kind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFFFF3B30)
    ArtifactItem.Kind.LINK -> Icons.Default.Link to Color(0xFF8E8E93)
}

private fun detail(item: ArtifactItem, chatIsOpen: Boolean): String {
    val parts = mutableListOf(L10n.tr(item.kind.label))
    if (item.source == ArtifactItem.Source.ATTACHED) parts += L10n.tr("You sent")
    if (!item.exists) {
        parts += L10n.tr("No longer on the host")
    } else if (item.size != null && item.kind != ArtifactItem.Kind.LINK) {
        parts += formatBytes(item.size)
    }
    if (item.kind == ArtifactItem.Kind.LINK) item.host?.let { parts += it }
    if (chatIsOpen) parts += DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.timestampMs))
    else if (item.sessionTitle.isNotEmpty()) parts += item.sessionTitle
    return parts.joinToString(" · ")
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "%.0f KB".format(bytes / 1_000.0)
    bytes < 1_000_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.1f GB".format(bytes / 1_000_000_000.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewer(name: String, bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offsetX += pan.x
        offsetY += pan.y
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Close")) } },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).transformable(transform), contentAlignment = Alignment.Center) {
                Image(
                    bitmap.asImageBitmap(),
                    name,
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageViewer(name: String, html: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, t("Close")) } },
                    actions = {
                        IconButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/html").putExtra(android.content.Intent.EXTRA_TEXT, html)
                            context.startActivity(android.content.Intent.createChooser(send, null))
                        }) { Icon(Icons.Default.Share, t("Share")) }
                    },
                )
            },
        ) { padding ->
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Pages Hermes draws are self-contained and scripted; they get no file or content access.
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
