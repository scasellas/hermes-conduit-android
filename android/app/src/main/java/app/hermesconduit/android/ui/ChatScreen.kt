package app.hermesconduit.android.ui

import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.t
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
    fun consumePrefill() {}
    fun quote(message: ChatMessage) {}
    fun react(message: ChatMessage, emoji: String) {}
    fun branchFrom(message: ChatMessage) {}
    fun runSlash(text: String): Boolean = false
    fun compress(focusTopic: String) {}
    fun loadContextBreakdown() {}
    fun loadDelegates() {}
    fun setReasoning(effort: String) {}
    fun setFast(enabled: Boolean) {}
    fun setYolo(enabled: Boolean) {}
    fun respondInputPrompt(value: String) {}
    /** Bots the composer's @ picker offers (Bot Mode roster minus the listener). */
    fun mentionCandidates(): List<app.hermesconduit.android.model.MentionAutocomplete.Candidate> = emptyList()
}

@Composable
fun ChatScreen(viewModel: AppViewModel, showMenuButton: Boolean, onMenu: () -> Unit) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val slash by viewModel.slashCommands.collectAsStateWithLifecycle()
    val breakdown by viewModel.contextBreakdown.collectAsStateWithLifecycle()
    val delegates by viewModel.delegates.collectAsStateWithLifecycle()
    ChatContent(chat, link, models, viewModel, showMenuButton, onMenu, slash, breakdown, delegates)
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
    slashCommands: List<app.hermesconduit.android.model.SlashCommand> = emptyList(),
    contextBreakdown: app.hermesconduit.android.model.ContextBreakdown? = null,
    delegates: List<app.hermesconduit.android.model.DelegateAgent>? = null,
) {
    var showModels by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showDelegates by remember { mutableStateOf(false) }

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
                    if (chat.activeAgents > 0) {
                        TextButton(onClick = { actions.loadDelegates(); showDelegates = true }) {
                            Text("⧉ ${chat.activeAgents}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (chat.yolo) Text("YOLO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp))
                    chat.contextPercent?.takeIf { it > 0 }?.let { raw ->
                        val percent = if (raw <= 1.0) raw * 100 else raw
                        TextButton(onClick = { actions.loadContextBreakdown(); showContext = true }) {
                            Box(contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = if (percent > 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Text("${percent.toInt()}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
            Transcript(chat, Modifier.weight(1f), actions)
            Banner(chat, onDismiss = actions::dismissNotice)
            chat.approval?.let { ApprovalCard(it, onChoice = actions::respondApproval) }
            chat.clarify?.let { ClarifyCard(it, onAnswer = { answers -> actions.answerClarify(it, answers) }) }
            chat.inputPrompt?.let { InputPromptCard(it, actions::respondInputPrompt) }
            Composer(
                chat = chat,
                enabled = link == LinkState.CONNECTED,
                actions = actions,
                slashCommands = slashCommands,
            )
        }
    }

    if (showModels) {
        ModelSheet(models, chat, actions, onDismiss = { showModels = false })
    }
    if (showContext) {
        ContextSheet(contextBreakdown, onCompress = { actions.compress(""); showContext = false }, onDismiss = { showContext = false })
    }
    if (showDelegates) {
        DelegatesSheet(delegates, onDismiss = { showDelegates = false })
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
private fun Transcript(chat: ChatState, modifier: Modifier, actions: ChatActions) {
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
                    TextButton(onClick = actions::loadEarlier, enabled = !chat.loading) { Text("Load earlier messages") }
                }
            }
        }
        items(chat.messages, key = { it.id }) { message -> MessageRow(message, actions, canAct = !chat.running || message != chat.messages.lastOrNull()) }
        item(key = "bottom") { Spacer(Modifier.size(1.dp)) }
    }
}

@Composable
internal fun Reasoning(text: String, live: Boolean) {
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
internal fun ToolCallCard(call: ToolCall) {
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
internal fun CodeBlock(label: String, text: String) {
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

val reasoningEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunSettings(chat: ChatState, actions: ChatActions) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("Reasoning"), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            reasoningEfforts.forEach { effort ->
                FilterChip(selected = chat.reasoningEffort == effort, onClick = { actions.setReasoning(effort) }, label = { Text(effort) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Fast mode"), Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = chat.fast, onCheckedChange = actions::setFast)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("YOLO for this chat"))
                Text(t("Approve every tool call automatically"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = chat.yolo, onCheckedChange = actions::setYolo)
        }
        androidx.compose.material3.HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(breakdown: app.hermesconduit.android.model.ContextBreakdown?, onCompress: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Context"), style = MaterialTheme.typography.titleLarge)
            if (breakdown == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${"%,d".format(breakdown.resolvedUsed)} / ${"%,d".format(breakdown.contextMax)} " + t("tokens") + " · ${breakdown.resolvedPercent.toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (breakdown.resolvedPercent / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                breakdown.categories.forEach { c ->
                    Row {
                        Text(c.label, Modifier.weight(1f))
                        Text("%,d".format(c.tokens), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = onCompress) { Text(t("Compress conversation")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelegatesSheet(agents: List<app.hermesconduit.android.model.DelegateAgent>?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Delegate agents"), style = MaterialTheme.typography.titleLarge)
            when {
                agents == null -> CircularProgressIndicator()
                agents.isEmpty() -> Text(t("No delegate agents are running."))
                else -> agents.forEach { a ->
                    Column {
                        Text(a.goal.ifEmpty { t("Delegate agent") }, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(a.status, a.model, a.currentTool).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        a.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(options: ModelOptions?, chat: ChatState, actions: ChatActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(t("Model"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        RunSettings(chat, actions)
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

