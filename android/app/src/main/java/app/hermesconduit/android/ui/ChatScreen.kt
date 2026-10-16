package app.hermesconduit.android.ui

import app.hermesconduit.android.core.LinkState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ChatMessage
import app.hermesconduit.android.model.ClarifyRequest
import app.hermesconduit.android.model.MessageRole
import app.hermesconduit.android.model.ModelOptions
import app.hermesconduit.android.model.ToolCall
import app.hermesconduit.android.ui.theme.attentionColor
import app.hermesconduit.android.ui.theme.successColor
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState

/** Everything the chat surface can ask of the app. */
interface ChatActions {
    fun send(text: String)
    fun send(text: String, attachments: List<app.hermesconduit.android.model.Attachment>) = send(text)
    fun stop()
    fun loadEarlier()
    fun dismissNotice()
    fun respondApproval(choice: String)
    fun answerClarify(request: ClarifyRequest, answers: List<String>)
    fun reconnectNow()
    fun loadModels()
    fun selectModel(provider: String, model: String, confirmed: Boolean = false)
    fun cancelModelConfirm()
}

@Composable
fun ChatScreen(viewModel: AppViewModel, showMenuButton: Boolean, onMenu: () -> Unit) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    ChatContent(chat, link, models, viewModel, showMenuButton, onMenu)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    chat: ChatState,
    link: LinkState,
    models: ModelOptions?,
    actions: ChatActions,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
) {
    var showModels by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (showMenuButton) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Chats") }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinkDot(link)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (link) {
                                    LinkState.CONNECTED -> chat.model ?: "Connected"
                                    LinkState.CONNECTING -> "Connecting…"
                                    LinkState.OFFLINE -> "Offline — tap to retry"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    if (link == LinkState.OFFLINE) actions.reconnectNow()
                                    else {
                                        actions.loadModels()
                                        showModels = true
                                    }
                                },
                            )
                        }
                    }
                },
                actions = {
                    chat.contextPercent?.takeIf { it > 0 }?.let {
                        Text("${it.toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Transcript(chat, Modifier.weight(1f), onLoadEarlier = actions::loadEarlier)
            Banner(chat, onDismiss = actions::dismissNotice)
            chat.approval?.let { ApprovalCard(it, onChoice = actions::respondApproval) }
            chat.clarify?.let { ClarifyCard(it, onAnswer = { answers -> actions.answerClarify(it, answers) }) }
            Composer(
                chat = chat,
                enabled = link == LinkState.CONNECTED,
                actions = actions,
            )
        }
    }

    if (showModels) {
        ModelSheet(models, chat, actions, onDismiss = { showModels = false })
    }
    chat.modelConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = actions::cancelModelConfirm,
            title = { Text("Switch to ${confirm.model}?") },
            text = { Text(confirm.message) },
            confirmButton = { TextButton(onClick = { actions.selectModel(confirm.provider, confirm.model, confirmed = true) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = actions::cancelModelConfirm) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LinkDot(link: LinkState) {
    val color = when (link) {
        LinkState.CONNECTED -> successColor()
        LinkState.CONNECTING -> attentionColor()
        LinkState.OFFLINE -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun Transcript(chat: ChatState, modifier: Modifier, onLoadEarlier: () -> Unit) {
    val listState = rememberLazyListState()
    val last = chat.messages.lastOrNull()
    // Follow the stream while the reader is at (or near) the bottom.
    LaunchedEffect(chat.messages.size, last?.content?.length, last?.toolCalls?.size) {
        if (chat.messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= listState.layoutInfo.totalItemsCount - 3) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
        }
    }
    LaunchedEffect(chat.sessionId) {
        if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.size)
    }

    if (chat.messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (chat.loading) CircularProgressIndicator()
            else Text("What can Hermes do for you?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (chat.hasEarlier) {
            item(key = "earlier") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onLoadEarlier, enabled = !chat.loading) { Text("Load earlier messages") }
                }
            }
        }
        items(chat.messages, key = { it.id }) { message -> MessageRow(message) }
        item(key = "bottom") { Spacer(Modifier.size(1.dp)) }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message.role) {
        MessageRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                SelectionContainer {
                    Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        MessageRole.SYSTEM -> Text(
            message.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { Reasoning(it, live = message.streaming && message.content.isEmpty()) }
            message.toolCalls.forEach { ToolCallCard(it) }
            if (message.content.isNotBlank()) {
                // Parsed during composition: the async default flashes empty on every streamed delta.
                val markdown = rememberMarkdownState(message.content, immediate = true)
                SelectionContainer { Markdown(markdownState = markdown, modifier = Modifier.fillMaxWidth()) }
            } else if (message.streaming && message.toolCalls.none { it.running } && message.reasoning.isNullOrBlank()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Reasoning(text: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (live) "Thinking…" else "Reasoning", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ToolCallCard(call: ToolCall) {
    var expanded by rememberSaveable(call.id, call.name) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (call.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Build, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(call.name, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                call.input?.takeIf { it.isNotBlank() }?.let { CodeBlock("Input", it) }
                call.output?.takeIf { it.isNotBlank() }?.let { CodeBlock("Output", it) }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text.take(MAX_TOOL_TEXT) + if (text.length > MAX_TOOL_TEXT) "\n…" else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}

private const val MAX_TOOL_TEXT = 8_000

@Composable
private fun Banner(chat: ChatState, onDismiss: () -> Unit) {
    val text = chat.error ?: chat.notice ?: return
    val isError = chat.error != null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(approval: ApprovalRequest, onChoice: (String) -> Unit) {
    DecisionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, tint = attentionColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(approval.description ?: "Approval required", style = MaterialTheme.typography.titleSmall)
        }
        approval.command?.takeIf { it.isNotBlank() }?.let { CodeBlock("Command", it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            approval.choices.forEach { choice ->
                val label = when (choice) {
                    "once" -> "Allow once"
                    "session" -> "Allow for chat"
                    "always" -> "Always allow"
                    "deny" -> "Deny"
                    else -> choice
                }
                if (choice == "deny") OutlinedButton(onClick = { onChoice(choice) }) { Text(label) }
                else Button(onClick = { onChoice(choice) }) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarifyCard(request: ClarifyRequest, onAnswer: (List<String>) -> Unit) {
    val answers = remember(request.requestId) { mutableStateListOf(*Array(request.questions.size) { "" }) }
    DecisionCard {
        Text("Hermes has a question", style = MaterialTheme.typography.titleSmall)
        request.questions.forEachIndexed { index, question ->
            Text(question.question, style = MaterialTheme.typography.bodyMedium)
            if (question.choices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.choices.forEach { choice ->
                        val picked = if (question.multiSelect) choice in parseMulti(answers[index]) else answers[index] == choice
                        FilterChip(
                            selected = picked,
                            onClick = {
                                answers[index] = if (question.multiSelect) toggleMulti(answers[index], choice) else choice
                            },
                            label = { Text(choice) },
                        )
                    }
                }
            }
            if (!question.multiSelect) {
                OutlinedTextField(
                    value = if (answers[index] in question.choices) "" else answers[index],
                    onValueChange = { answers[index] = it },
                    placeholder = { Text(if (question.choices.isEmpty()) "Your answer" else "Or type an answer") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Button(onClick = { onAnswer(answers.toList()) }, enabled = answers.all { it.isNotBlank() }) { Text("Answer") }
    }
}

/** Multi-select answers travel as a JSON array string. */
private fun parseMulti(value: String): List<String> =
    runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

private fun toggleMulti(value: String, choice: String): String {
    val current = parseMulti(value)
    val next = if (choice in current) current - choice else current + choice
    return if (next.isEmpty()) "" else kotlinx.serialization.json.Json.encodeToString(next)
}

@Composable
private fun DecisionCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(options: ModelOptions?, chat: ChatState, actions: ChatActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        val current = options
        if (current == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(current.providers, key = { it.slug }) { provider ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        provider.models.forEach { model ->
                            val selected = model == (chat.model ?: current.model) && provider.slug == (chat.provider ?: current.provider)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    actions.selectModel(provider.slug, model)
                                    onDismiss()
                                },
                                label = { Text(model, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
    }
}

