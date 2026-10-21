package app.hermesconduit.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.t
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ProfileConfig
import app.hermesconduit.android.model.SettingControl
import app.hermesconduit.android.model.SettingField
import app.hermesconduit.android.model.SettingValue
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.components.ErrorText
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads and writes the active profile's `/api/config`, like the iOS profile settings engine. */
class ProfileConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val hermes = application.hermes
    private val _values = MutableStateFlow<Map<String, SettingValue>?>(null)
    val values: StateFlow<Map<String, SettingValue>?> = _values
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _personalities = MutableStateFlow<List<String>>(emptyList())
    val personalities: StateFlow<List<String>> = _personalities
    private val _memoryProviders = MutableStateFlow<List<String>>(emptyList())
    val memoryProviders: StateFlow<List<String>> = _memoryProviders
    private val _contextEngines = MutableStateFlow<List<String>>(emptyList())
    val contextEngines: StateFlow<List<String>> = _contextEngines

    private val profile get() = hermes.profile.value
    private fun query() = mapOf("profile" to profile.takeIf { it != "default" })

    suspend fun config(): JsonObject = hermes.rest { it.getJson("api/config", query()) }

    fun load(keys: List<String>) {
        viewModelScope.launch {
            runCatching { config() }
                .onSuccess { config -> _values.value = keys.mapNotNull { k -> ProfileConfig.valueAt(config, k)?.let { k to it } }.toMap() }
                .onFailure {
                    _values.value = emptyMap()
                    _error.value = L10n.tr("Could not load profile settings: %s", it.message ?: "")
                }
        }
    }

    fun loadOptions() {
        viewModelScope.launch {
            runCatching { config() }.onSuccess { config ->
                _personalities.value = config["agent"].obj?.get("personalities").obj?.keys?.sorted().orEmpty()
            }
            runCatching { hermes.rest { it.getJson("api/dashboard/plugins/hub", query()) } }.onSuccess { hub ->
                val providers = hub["providers"].obj
                _memoryProviders.value = providers?.get("memory_options").arr
                    ?.mapNotNull { o -> o.obj?.takeIf { it["status"].str == "ready" }?.get("name").str }?.sorted().orEmpty()
                _contextEngines.value = providers?.get("context_options").arr?.mapNotNull { it.obj?.get("name").str }?.distinct()?.sorted().orEmpty()
            }
        }
    }

    /** Saves one key; returns through [onDone] whether Hermes took it. */
    fun save(key: String, value: SettingValue, onDone: (Boolean) -> Unit = {}) {
        val previous = _values.value?.get(key)
        _values.value = _values.value.orEmpty() + (key to value)
        _error.value = null
        viewModelScope.launch {
            val ok = runCatching {
                if (profile == "default" && (key == "context.engine" || key == "memory.provider")) {
                    val body = buildJsonObject { put(if (key == "context.engine") "context_engine" else "memory_provider", value.text.orEmpty()) }
                    hermes.rest { it.sendJson("PUT", "api/dashboard/plugin-providers", body) }
                } else {
                    val updated = ProfileConfig.withValue(config(), key, value)
                    hermes.rest {
                        it.sendJson("PUT", "api/config", buildJsonObject { put("config", updated); put("profile", profile) }, query())
                    }
                }
            }.onFailure { _error.value = L10n.tr("Could not save %s: %s", key, it.message ?: "") }.isSuccess
            if (!ok) _values.value = _values.value.orEmpty().let { if (previous != null) it + (key to previous) else it - key }
            onDone(ok)
        }
    }
}

@Composable
fun ProfileConfigPage(
    title: String,
    fields: List<SettingField>,
    onBack: () -> Unit,
    fieldsHeader: String? = null,
    optionOverrides: (ProfileConfigViewModel) -> Map<String, List<String>> = { emptyMap() },
    model: ProfileConfigViewModel = viewModel(),
    leading: @Composable ColumnScope.() -> Unit = {},
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    val values by model.values.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    LaunchedEffect(fields) {
        model.load(fields.map { it.key })
        model.loadOptions()
    }
    ScreenScaffold(title, onBack) { padding ->
        SettingsColumn(padding) {
            leading()
            val current = values
            if (current == null) {
                LoadingBox(Modifier.padding(32.dp))
            } else {
                SettingsSection(fieldsHeader) {
                    val overrides = optionOverrides(model)
                    fields.forEachIndexed { index, field ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        FieldRow(field, current[field.key], overrides[field.key]) { model.save(field.key, it) }
                    }
                }
            }
            ErrorText(error)
            trailing()
        }
    }
}

@Composable
private fun FieldRow(field: SettingField, value: SettingValue?, overrideOptions: List<String>?, onSave: (SettingValue) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun label() {
            Column(Modifier.fillMaxWidth()) {
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                Text(field.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (val c = field.control) {
            is SettingControl.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { label() }
                Switch(checked = value?.bool ?: c.default, onCheckedChange = { onSave(SettingValue.Bool(it)) })
            }
            is SettingControl.TextToggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { label() }
                val on = (value?.text ?: if (c.default) c.on else c.off) != c.off
                Switch(checked = on, onCheckedChange = { onSave(SettingValue.Text(if (it) c.on else c.off)) })
            }
            is SettingControl.Options -> {
                label()
                val choices = overrideOptions ?: c.options
                OptionMenu(value?.text ?: c.default, choices.map { it to optionLabel(field.key, it) }) { onSave(SettingValue.Text(it)) }
                if (choices.isEmpty()) Text(t("No configured choices are available."), style = MaterialTheme.typography.bodySmall)
            }
            is SettingControl.LabeledOptions -> {
                label()
                OptionMenu(value?.text ?: c.default, c.options) { onSave(SettingValue.Text(it)) }
            }
            is SettingControl.Text, is SettingControl.Number -> {
                label()
                val isNumber = c is SettingControl.Number
                val initial = value?.text ?: (if (c is SettingControl.Text) c.default else (c as SettingControl.Number).default.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
                var draft by remember(initial) { mutableStateOf(initial) }
                var invalid by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        draft,
                        { draft = it; invalid = false },
                        singleLine = true,
                        isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = if (isNumber) KeyboardType.Decimal else KeyboardType.Text, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        if (isNumber) {
                            val n = draft.toDoubleOrNull()
                            if (n == null) invalid = true else onSave(SettingValue.Number(n))
                        } else onSave(SettingValue.Text(draft))
                    }) { Text(t("Save")) }
                }
                if (invalid) Text(t("Enter a valid number for %s.", field.label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun optionLabel(key: String, value: String): String = when {
    value.isEmpty() -> if (key == "display.personality") t("Hermes default") else t("None")
    else -> value
}

@Composable
fun OptionMenu(selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == selected }?.second ?: selected.ifEmpty { "—" }, Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}
