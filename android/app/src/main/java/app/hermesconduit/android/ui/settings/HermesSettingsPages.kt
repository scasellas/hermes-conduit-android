package app.hermesconduit.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.BuildConfig
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.ModelOptions
import app.hermesconduit.android.model.ProfileConfig
import app.hermesconduit.android.model.SettingControl
import app.hermesconduit.android.model.SettingField
import app.hermesconduit.android.model.SettingValue
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.components.SettingsToggle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun f(key: String, label: String, help: String, control: SettingControl) = SettingField(key, L10n.tr(label), L10n.tr(help), control)

val chatFields get() = listOf(
    f("display.personality", "Personality", "Default response style for new conversations.", SettingControl.Options(emptyList(), "")),
    f("timezone", "Timezone", "Used for dates, reminders, and scheduled work.", SettingControl.Text("")),
    f("display.show_reasoning", "Show thinking", "Show collapsible thinking blocks when provided.", SettingControl.Toggle(true)),
    f("display.tool_progress", "Tool cards", "Show tool calls and expandable details in conversations.", SettingControl.TextToggle("all", "off", true)),
    f("display.expand_tools", "Keep tool cards expanded", "Keep completed tool details open by default.", SettingControl.Toggle(false)),
    f(
        "display.memory_notifications", "Self-improvement updates",
        "Choose whether Conduit follows Hermes, always shows, or never shows maintenance updates.",
        SettingControl.LabeledOptions(listOf("default" to L10n.tr("Use Hermes default"), "on" to L10n.tr("Always show"), "off" to L10n.tr("Never show")), "default"),
    ),
    f("agent.image_input_mode", "Image attachments", "How Hermes supplies images to a model.", SettingControl.Options(listOf("auto", "native", "text"), "auto")),
)

val workspaceFields get() = listOf(
    f("terminal.cwd", "Default working directory", "Server-side path for new workspaces.", SettingControl.Text("")),
    f("code_execution.mode", "Code execution mode", "Default execution boundary.", SettingControl.Options(listOf("project", "strict"), "project")),
    f(
        "approvals.mode", "Approval mode",
        "Profile-wide default: manual asks every time; smart asks when risk warrants it; off is YOLO mode. When set to off, Hermes auto-approves everything and per-session YOLO toggles have no effect — that's a Hermes limitation, not a Conduit bug. To use per-session YOLO, set this to manual or smart.",
        SettingControl.Options(listOf("manual", "smart", "off"), "smart"),
    ),
    f("security.redact_secrets", "Redact secrets", "Hide detected credentials from tool output where possible.", SettingControl.Toggle(true)),
    f("security.allow_private_urls", "Allow private URLs", "Permit tool access to private-network URLs.", SettingControl.Toggle(false)),
)

val memoryFields get() = listOf(
    f("memory.provider", "Memory provider", "Provider used for long-term memory.", SettingControl.Options(emptyList(), "")),
    f("memory.memory_enabled", "Long-term memory", "Allow Hermes to retain relevant working memory.", SettingControl.Toggle(true)),
    f("memory.user_profile_enabled", "User profile memory", "Allow Hermes to maintain user preferences.", SettingControl.Toggle(true)),
    f("context.engine", "Context engine", "Installed context-management engine.", SettingControl.Options(emptyList(), "default")),
    f("compression.enabled", "Context compression", "Compress older context when the window becomes crowded.", SettingControl.Toggle(true)),
    f("compression.threshold", "Compression threshold", "Fraction of the context window that starts compression.", SettingControl.Number(0.8)),
    f("compression.target_ratio", "Compression target", "Fraction retained after compression.", SettingControl.Number(0.5)),
    f("compression.protect_last_n", "Protected recent messages", "Recent messages left intact by compression.", SettingControl.Number(8.0)),
    f("delegation.max_concurrent_children", "Concurrent delegate agents", "Maximum child agents that can work at once.", SettingControl.Number(2.0)),
)

val builtInPersonalities = listOf("", "helpful", "concise", "technical", "creative", "teacher", "kawaii", "catgirl", "pirate", "shakespeare", "surfer", "noir", "uwu", "philosopher", "hype")

@Composable
fun ChatSettingsPage(onBack: () -> Unit) {
    val model: ProfileConfigViewModel = viewModel()
    val personalities by model.personalities.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = context.appSettings
    ProfileConfigPage(
        t("Chat"),
        chatFields,
        onBack,
        fieldsHeader = "Hermes",
        model = model,
        optionOverrides = { mapOf("display.personality" to (builtInPersonalities + personalities).distinct()) },
        leading = {
            val resume by settings.string(AppSettings.CHAT_RESUME, "continue").collectAsStateWithLifecycle()
            SettingsSection(t("When returning to Conduit"), footer = t("Stored only on this device, not in your Hermes profile. Choose which surface Conduit opens to and whether it preserves your exact reading position or follows the newest conversation.")) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("Conversation"), style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf("continue" to t("Continue"), "latest" to t("Latest")).forEachIndexed { i, (id, label) ->
                            SegmentedButton(selected = resume == id, onClick = { settings.set(AppSettings.CHAT_RESUME, id) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                }
            }
            BusyModeSection()
        },
        trailing = {
            val scaleIndex by settings.int(AppSettings.CHAT_TEXT_SIZE, 2).collectAsStateWithLifecycle()
            val sizes = listOf(0.85f to "Smallest", 0.925f to "Smaller", 1f to "Default", 1.125f to "Larger", 1.25f to "Largest")
            SettingsSection(t("Chat text size"), footer = t("Readable conversation content only — messages, lists, tables, and code. Buttons, timestamps, and the rest of the interface keep their size.")) {
                Column(Modifier.padding(16.dp)) {
                    Slider(
                        value = scaleIndex.toFloat(),
                        onValueChange = {
                            val i = it.toInt().coerceIn(0, 4)
                            settings.set(AppSettings.CHAT_TEXT_SIZE, i)
                            settings.set(AppSettings.CHAT_FONT_SCALE, sizes[i].first)
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                    )
                    Text(t(sizes[scaleIndex.coerceIn(0, 4)].second), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val returnSends by settings.bool(AppSettings.SEND_ON_ENTER, false).collectAsStateWithLifecycle()
            SettingsSection(t("Keyboard"), footer = t("Hardware keyboards only. The on-screen keyboard's Return key keeps inserting a new line.")) {
                SettingsToggle(t("Return key sends"), returnSends, { settings.set(AppSettings.SEND_ON_ENTER, it) })
            }
            val maxMb by settings.int(AppSettings.ATTACHMENT_MAX_MB, 16).collectAsStateWithLifecycle()
            SettingsSection(t("Attachments"), footer = if (maxMb > 16) t("Large files take longer to send and can fail on slow connections.") else null) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("Maximum file size"), style = MaterialTheme.typography.titleSmall)
                    OptionMenu(maxMb.toString(), listOf(16, 32, 64, 128, 256).map { it.toString() to "$it MB" }) { settings.set(AppSettings.ATTACHMENT_MAX_MB, it.toInt()) }
                }
            }
            val takeover by settings.bool(AppSettings.AUTO_TAKEOVER, false).collectAsStateWithLifecycle()
            SettingsSection(t("Chats open on your computer"), footer = t("When a chat is open in Hermes Desktop or the terminal, take it over here without asking.")) {
                SettingsToggle(t("Take over automatically"), takeover, { settings.set(AppSettings.AUTO_TAKEOVER, it) })
            }
            val haptics by settings.bool(AppSettings.HAPTICS, true).collectAsStateWithLifecycle()
            SettingsSection(t("Haptic feedback")) {
                SettingsToggle(t("Haptic feedback"), haptics, { settings.set(AppSettings.HAPTICS, it) })
            }
        },
    )
}

/** `config.set busy`: what a message sent during a running turn does. */
@Composable
private fun BusyModeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        mode = runCatching { context.hermes.rpc("config.set", buildJsonObject { put("key", "busy"); put("value", "status") }).obj?.get("value").str }
            .getOrNull()?.takeIf { it == "steer" || it == "interrupt" } ?: "steer"
    }
    SettingsSection(t("During a response"), footer = t("Steer adds guidance to the active turn. Interrupt stops it before handling the new message.")) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            listOf("steer" to t("Steer"), "interrupt" to t("Interrupt")).forEachIndexed { i, (id, label) ->
                SegmentedButton(
                    selected = mode == id,
                    onClick = {
                        val previous = mode
                        mode = id
                        scope.launch {
                            runCatching { context.hermes.rpc("config.set", buildJsonObject { put("key", "busy"); put("value", id) }) }
                                .onFailure { mode = previous; error = L10n.tr("Could not save this setting. Restored the previous choice.") }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        ErrorText(error)
    }
}

@Composable
fun WorkspaceSettingsPage(onBack: () -> Unit) = ProfileConfigPage(t("Workspace & safety"), workspaceFields, onBack)

@Composable
fun MemorySettingsPage(onBack: () -> Unit) {
    val model: ProfileConfigViewModel = viewModel()
    val providers by model.memoryProviders.collectAsStateWithLifecycle()
    val engines by model.contextEngines.collectAsStateWithLifecycle()
    ProfileConfigPage(
        t("Memory & delegation"),
        memoryFields,
        onBack,
        model = model,
        optionOverrides = { mapOf("memory.provider" to listOf("") + providers, "context.engine" to (listOf("default") + engines).distinct()) },
        trailing = { DelegateModelSection(model) },
    )
}

private suspend fun loadModelOptions(context: android.content.Context): ModelOptions? {
    val hermes = context.hermes
    val profile = hermes.profile.value.takeIf { it != "default" }
    return runCatching {
        MessageNormalizer.modelOptions(hermes.rest { it.getJson("api/model/options", mapOf("explicit_only" to "true", "profile" to profile)) })
    }.getOrNull()
}

@Composable
private fun DelegateModelSection(model: ProfileConfigViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<ModelOptions?>(null) }
    var provider by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        options = loadModelOptions(context)
        runCatching { model.config() }.onSuccess { c ->
            provider = ProfileConfig.valueAt(c, "delegation.provider")?.text.orEmpty()
            modelId = ProfileConfig.valueAt(c, "delegation.model")?.text.orEmpty()
            reasoning = ProfileConfig.valueAt(c, "delegation.reasoning_effort")?.text.orEmpty()
        }
    }
    SettingsSection(t("Delegate model"), footer = t("Leave both selections empty to inherit the chat model. Set a provider and model to route delegate agents separately.")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val providers = options?.providers.orEmpty()
            Text(t("Delegate provider"), style = MaterialTheme.typography.labelLarge)
            OptionMenu(provider, listOf("" to t("Inherit chat provider")) + providers.map { it.slug to it.name }) {
                provider = it
                modelId = if (it.isEmpty()) "" else providers.firstOrNull { p -> p.slug == it }?.models?.firstOrNull().orEmpty()
            }
            if (provider.isNotEmpty()) {
                Text(t("Delegate model"), style = MaterialTheme.typography.labelLarge)
                OptionMenu(modelId, listOf("" to t("Inherit chat model")) + providers.firstOrNull { it.slug == provider }?.models.orEmpty().map { it to it }) { modelId = it }
            }
            Text(t("Delegate reasoning"), style = MaterialTheme.typography.labelLarge)
            OptionMenu(reasoning, listOf("" to t("Inherit chat reasoning")) + listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra").map { it to it.replaceFirstChar(Char::uppercase) }) { reasoning = it }
            Button(onClick = {
                status = L10n.tr("Saving…")
                model.save("delegation.provider", SettingValue.Text(provider)) { ok1 ->
                    if (!ok1) { status = L10n.tr("Could not save delegate defaults."); return@save }
                    model.save("delegation.model", SettingValue.Text(modelId)) { ok2 ->
                        if (!ok2) { status = L10n.tr("Could not save delegate defaults."); return@save }
                        model.save("delegation.reasoning_effort", SettingValue.Text(reasoning)) { ok3 ->
                            status = if (ok3) L10n.tr("Saved") else L10n.tr("Could not save delegate defaults.")
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(t("Save delegate defaults")) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = scope
}

/** Default provider, model and reasoning for new sessions (`/api/model/set` + `/api/config`). */
@Composable
fun ModelDefaultsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val model: ProfileConfigViewModel = viewModel()
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<ModelOptions?>(null) }
    var provider by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf("medium") }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    val hermes = context.hermes
    val profile = hermes.profile.value
    val query = mapOf("profile" to profile.takeIf { it != "default" })
    LaunchedEffect(Unit) {
        options = loadModelOptions(context)
        val info = runCatching { hermes.rest { it.getJson("api/model/info", query) } }.getOrNull()
        runCatching { model.config() }.onSuccess { c ->
            val modelConfig = c["model"].obj
            modelId = listOfNotNull(modelConfig?.get("default").str, c["model"].str, info?.get("model").str, options?.model).firstOrNull { it.isNotBlank() }.orEmpty()
            provider = listOfNotNull(modelConfig?.get("provider").str, info?.get("provider").str, options?.provider).firstOrNull { it.isNotBlank() }.orEmpty()
            reasoning = c["reasoning"].str ?: c["reasoning_effort"].str ?: "medium"
        }
        loading = false
    }
    ScreenScaffold(t("Model"), onBack) { padding ->
        SettingsColumn(padding) {
            if (loading) { LoadingBox(Modifier.padding(32.dp)); return@SettingsColumn }
            val providers = options?.providers.orEmpty()
            SettingsSection(t("Default model"), footer = t("Select a provider first, then one of its available models. These defaults apply to new sessions in this profile.")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Provider"), style = MaterialTheme.typography.labelLarge)
                    OptionMenu(provider, providers.map { it.slug to it.name }) {
                        provider = it
                        modelId = providers.firstOrNull { p -> p.slug == it }?.models?.firstOrNull().orEmpty()
                    }
                    Text(t("Model"), style = MaterialTheme.typography.labelLarge)
                    OptionMenu(modelId, (providers.firstOrNull { it.slug == provider }?.models.orEmpty() + modelId).distinct().filter { it.isNotEmpty() }.map { it to it }) { modelId = it }
                }
            }
            SettingsSection(t("Reasoning")) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("Default reasoning"), style = MaterialTheme.typography.labelLarge)
                    OptionMenu(reasoning, app.hermesconduit.android.ui.reasoningEfforts.map { it to it }) { reasoning = it }
                }
            }
            Button(
                onClick = {
                    status = L10n.tr("Saving…")
                    scope.launch {
                        status = runCatching {
                            val result = hermes.rest {
                                it.sendJson("POST", "api/model/set", buildJsonObject { put("model", modelId); put("provider", provider); put("scope", "main"); put("profile", profile) }, query)
                            }
                            if (result["confirm_required"]?.let { it.toString() == "true" } == true) {
                                return@runCatching result["confirm_message"].str ?: L10n.tr("Hermes requires confirmation before using this model.")
                            }
                            var config = model.config()
                            config = ProfileConfig.withValue(config, "model.default", SettingValue.Text(modelId))
                            config = ProfileConfig.withValue(config, "model.provider", SettingValue.Text(provider))
                            config = ProfileConfig.withValue(config, "reasoning", SettingValue.Text(reasoning))
                            hermes.rest { it.sendJson("PUT", "api/config", buildJsonObject { put("config", config); put("profile", profile) }, query) }
                            L10n.tr("Saved")
                        }.getOrElse { L10n.tr("Could not save model defaults.") + " " + (it.message ?: "") }
                    }
                },
                enabled = provider.isNotEmpty() && modelId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t("Save model defaults")) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Active profile and its device-local display name. */
@Composable
fun ProfileSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = context.appSettings
    val profile by context.hermes.profile.collectAsStateWithLifecycle()
    val key = AppSettings.displayNameKey(context.hermes.connection.value?.id, profile)
    var name by remember(key) { mutableStateOf(settings.getString(key, "")) }
    var saved by remember { mutableStateOf(false) }
    ScreenScaffold(t("Profile"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("Active profile")) { SettingsRow(t("Profile"), value = profile) }
            SettingsSection(t("On this device"), footer = t("Only this device uses the display name.")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it; saved = false }, label = { Text(t("Display name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { settings.set(key, name.trim()); saved = true }) { Text(if (saved) t("Saved") else t("Save display name")) }
                }
            }
        }
    }
}

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val profile by context.hermes.profile.collectAsStateWithLifecycle()
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    ScreenScaffold(t("About & privacy"), onBack) { padding ->
        SettingsColumn(padding) {
            SettingsSection(t("About"), footer = t("A touch-first native client for your Hermes gateway.")) {
                SettingsRow(t("Active profile"), value = profile.replaceFirstChar(Char::uppercase))
                SettingsRow(t("Version"), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
            SettingsSection(t("Data handling"), footer = t("Conduit connects to the dashboard and gateway you configure. Conversations and attachments are handled by that Hermes installation.")) {}
            SettingsSection(t("Links")) {
                SettingsRow(t("Privacy policy"), icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = { open("https://kaishi00.github.io/hermes-conduit-notifier/privacy/") })
                SettingsRow(t("Support"), icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = { open("https://kaishi00.github.io/hermes-conduit-notifier/support/") })
                SettingsRow("GitHub", icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = { open("https://github.com/kaishi00/hermes-conduit-notifier") })
            }
            SettingsSection(t("Disclaimer"), footer = t("Hermes Conduit is an independent client and is not affiliated with or endorsed by Nous Research. © 2026 Milim.")) {}
        }
    }
}
