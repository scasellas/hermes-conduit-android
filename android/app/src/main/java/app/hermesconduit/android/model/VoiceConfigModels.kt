package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class VoiceProviderKind(val raw: String) { STT("stt"), TTS("tts") }

data class VoiceCapabilitySnapshot(
    val isGatewayConnected: Boolean,
    val supportsTranscription: Boolean,
    val supportsSpeech: Boolean,
    val unavailableReason: String?,
) {
    companion object {
        fun unavailable(reason: String) = VoiceCapabilitySnapshot(false, false, false, reason)
    }
}

data class VoiceProviderDescriptor(
    val id: String,
    val displayName: String,
    val kind: VoiceProviderKind,
    val models: List<String> = emptyList(),
    val voices: List<String> = emptyList(),
    val supportsStreaming: Boolean = false,
)

data class VoiceCredentialStatus(
    val key: String,
    val isSet: Boolean,
    val description: String,
    /** Hermes' masked form ("sk-p...abcd"). Never the key itself. */
    val redactedValue: String? = null,
) {
    companion object {
        fun maskedValue(value: JsonElement?): String? {
            val text = value.str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (text.contains("…") || text.contains("...") || text.contains("*")) text else null
        }
    }
}

data class VoiceProviderReadiness(
    val id: String,
    val kind: VoiceProviderKind,
    val status: String,
    val isActive: Boolean,
    val displayName: String,
    val isManagedNous: Boolean,
    val requiredCredentials: List<VoiceCredentialStatus>,
) {
    val statusLabel: String get() = label(status)

    companion object {
        fun label(status: String): String = when (status.lowercase()) {
            "ready" -> L10n.tr("Ready")
            "needs_keys" -> L10n.tr("Needs an API key")
            "needs_auth" -> L10n.tr("Needs sign-in")
            "needs_install" -> L10n.tr("Not installed")
            else -> status.replace('_', ' ').split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        }
    }
}

sealed interface VoiceFieldKind {
    data object Text : VoiceFieldKind
    data object Decimal : VoiceFieldKind
    data class Choice(val options: List<String>) : VoiceFieldKind
}

data class VoiceTypedField(
    val key: String,
    val label: String,
    val help: String,
    val kind: VoiceFieldKind,
    val defaultValue: String,
    val numericRange: ClosedFloatingPointRange<Double>? = null,
)

data class VoiceProviderConfiguration(
    val descriptor: VoiceProviderDescriptor,
    val fields: List<VoiceTypedField>,
    val readiness: VoiceProviderReadiness?,
) {
    val id: String get() = "${descriptor.kind.raw}.${descriptor.id}"
}

data class VoiceConfigurationSnapshot(
    val profile: String,
    val capability: VoiceCapabilitySnapshot,
    val sttProviders: List<VoiceProviderConfiguration>,
    val ttsProviders: List<VoiceProviderConfiguration>,
    val selectedSTTProvider: String,
    val selectedTTSProvider: String,
    val values: Map<String, String>,
    val credentials: List<VoiceCredentialStatus>,
    val environment: Map<String, VoiceCredentialStatus> = emptyMap(),
    val environmentLoaded: Boolean = false,
    /** `stt.streaming`: Hermes recognizes speech while the user talks. */
    val liveTranscription: Boolean = false,
) {
    companion object {
        fun unavailable(profile: String, reason: String) = VoiceConfigurationSnapshot(
            profile, VoiceCapabilitySnapshot.unavailable(reason), emptyList(), emptyList(), "", "", emptyMap(), emptyList(),
        )
    }
}

/** Port of `VoiceConfigurationParser`: `/api/config`, its schema, toolset readiness and `/api/env` → a snapshot. */
object VoiceConfigurationParser {
    fun parse(
        profile: String,
        schema: JsonObject?,
        config: JsonObject,
        sttReadiness: JsonObject?,
        ttsReadiness: JsonObject?,
        environment: JsonObject?,
        ttsToolsetConfigAvailable: Boolean,
    ): VoiceConfigurationSnapshot {
        val selectedSTT = nestedString(config, "stt.provider") ?: "local"
        val selectedTTS = nestedString(config, "tts.provider") ?: "edge"
        val readiness = readinessRows(sttReadiness, VoiceProviderKind.STT) + readinessRows(ttsReadiness, VoiceProviderKind.TTS)
        val environmentRows = allCredentialRows(environment)
        val credentials = mergedCredentials(credentialRows(environment) + readiness.flatMap { it.requiredCredentials }).map { credential ->
            if (credential.isSet) credential.copy(redactedValue = environmentRows[credential.key]?.redactedValue) else credential
        }
        val schemaIds = providerIds(schema)
        val sttIds = unique(schemaIds.first + readiness.filter { it.kind == VoiceProviderKind.STT }.map { it.id } + selectedSTT)
        val ttsIds = unique(schemaIds.second + readiness.filter { it.kind == VoiceProviderKind.TTS }.map { it.id } + selectedTTS)
        val stt = sttIds.map { providerConfiguration(it, VoiceProviderKind.STT, readiness, credentials) }
        val tts = ttsIds.map { providerConfiguration(it, VoiceProviderKind.TTS, readiness, credentials) }
        val sttEnabled = nestedBool(config, "stt.enabled") ?: true
        val selectedTTSReady = selectedProviderIsReady(tts, selectedTTS)
        val supportsSpeech = ttsToolsetConfigAvailable && selectedTTSReady
        val reason = when {
            !sttEnabled -> L10n.tr("Speech-to-text is disabled for this Hermes profile.")
            !supportsSpeech -> L10n.tr("The selected text-to-speech provider is not ready for this profile.")
            else -> null
        }
        return VoiceConfigurationSnapshot(
            profile = profile,
            capability = VoiceCapabilitySnapshot(true, sttEnabled, supportsSpeech, reason),
            sttProviders = stt,
            ttsProviders = tts,
            selectedSTTProvider = selectedSTT,
            selectedTTSProvider = selectedTTS,
            values = allVoiceValues(config),
            credentials = credentials,
            environment = environmentRows,
            environmentLoaded = environment != null,
            liveTranscription = nestedBool(config, "stt.streaming") ?: false,
        )
    }

    private fun providerConfiguration(
        id: String,
        kind: VoiceProviderKind,
        readiness: List<VoiceProviderReadiness>,
        credentials: List<VoiceCredentialStatus>,
    ): VoiceProviderConfiguration {
        val descriptor = catalogDescriptor(id, kind) ?: VoiceProviderDescriptor(
            id, id.replace('_', ' ').split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }, kind,
        )
        val row = readiness.firstOrNull { it.id == id && it.kind == kind }
        val required = row?.requiredCredentials ?: credentials.filter {
            (id == "stepfun" && it.key == "STEPFUN_API_KEY") || (id == "xiaomi_mimo" && it.key == "MIMO_API_KEY")
        }
        return VoiceProviderConfiguration(descriptor, typedFields(id, kind), row?.copy(requiredCredentials = required))
    }

    fun catalogDescriptor(id: String, kind: VoiceProviderKind): VoiceProviderDescriptor? {
        val stt = kind == VoiceProviderKind.STT
        return when {
            id == "local" && stt -> VoiceProviderDescriptor(id, L10n.tr("Local"), kind, listOf("base", "tiny", "small", "medium", "large-v3", "turbo"))
            (id == "nous" || id == "openai") && stt -> VoiceProviderDescriptor(
                id, if (id == "nous") L10n.tr("Nous Subscription") else "OpenAI", kind,
                listOf("whisper-1", "gpt-4o-mini-transcribe", "gpt-4o-transcribe", "gpt-transcribe"),
            )
            id == "groq" && stt -> VoiceProviderDescriptor(id, "Groq", kind, listOf("whisper-large-v3-turbo", "whisper-large-v3"))
            id == "mistral" && stt -> VoiceProviderDescriptor(id, "Mistral", kind, listOf("voxtral-mini-latest", "voxtral-mini-2602"))
            id == "xai" && stt -> VoiceProviderDescriptor(id, "xAI", kind, listOf("grok-voice-transcribe-2.0", "grok-voice-transcribe-1.0"))
            id == "elevenlabs" && stt -> VoiceProviderDescriptor(id, "ElevenLabs Scribe", kind, listOf("scribe_v2", "scribe_v1"))
            id == "deepinfra" && stt -> VoiceProviderDescriptor(id, "DeepInfra", kind)
            id == "nous" -> VoiceProviderDescriptor(id, L10n.tr("Nous Subscription"), kind, supportsStreaming = true)
            id == "openai" -> VoiceProviderDescriptor(id, "OpenAI", kind, supportsStreaming = true)
            id == "elevenlabs" -> VoiceProviderDescriptor(id, "ElevenLabs", kind, supportsStreaming = true)
            id == "xai" -> VoiceProviderDescriptor(id, "xAI", kind, supportsStreaming = true)
            id == "gemini" && !stt -> VoiceProviderDescriptor(id, "Gemini", kind, supportsStreaming = true)
            id == "edge" && !stt -> VoiceProviderDescriptor(id, "Edge TTS", kind)
            id == "stepfun" && stt -> VoiceProviderDescriptor(id, "StepFun", kind, listOf("stepaudio-2.5-asr", "step-asr"))
            id == "stepfun" -> VoiceProviderDescriptor(id, "StepFun", kind, listOf("stepaudio-2.5-tts"), supportsStreaming = true)
            id == "xiaomi_mimo" && stt -> VoiceProviderDescriptor(id, L10n.tr("Xiaomi MiMo"), kind, listOf("mimo-v2.5-asr"))
            id == "xiaomi_mimo" -> VoiceProviderDescriptor(
                id, L10n.tr("Xiaomi MiMo"), kind, listOf("mimo-v2.5-tts"),
                listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"), supportsStreaming = true,
            )
            else -> null
        }
    }

    private fun customEndpointHelp(provider: String) =
        L10n.tr("Optional %s-compatible speech endpoint Hermes should call (for example https://your-host/v1). Leave blank for the provider default. This does not change the server Conduit connects to.", provider)

    fun typedFields(id: String, kind: VoiceProviderKind): List<VoiceTypedField> {
        val root = if (id == "nous") "${kind.raw}.openai" else "${kind.raw}.$id"
        val modelKey = if (kind == VoiceProviderKind.STT && id == "elevenlabs") "model_id" else "model"
        val defaultModel = if (id == "local" && kind == VoiceProviderKind.STT) "base" else ""
        val modelHelp = L10n.tr("You can enter any installed model identifier.")
        if (kind == VoiceProviderKind.TTS && id == "elevenlabs") {
            return listOf(
                VoiceTypedField("tts.elevenlabs.voice_id", L10n.tr("Voice ID"), L10n.tr("Voice ID from your ElevenLabs-compatible endpoint. Leave blank for the provider default."), VoiceFieldKind.Text, ""),
                VoiceTypedField("tts.elevenlabs.model_id", L10n.tr("Model"), modelHelp, VoiceFieldKind.Text, ""),
                VoiceTypedField("tts.elevenlabs.base_url", L10n.tr("Base URL"), customEndpointHelp("ElevenLabs"), VoiceFieldKind.Text, ""),
            )
        }
        val fields = mutableListOf(
            VoiceTypedField("$root.$modelKey", L10n.tr("Model"), modelHelp, VoiceFieldKind.Text, defaultModel),
            VoiceTypedField("$root.language", L10n.tr("Language"), L10n.tr("Leave blank for automatic language detection."), VoiceFieldKind.Text, ""),
        )
        if (kind == VoiceProviderKind.TTS) {
            fields += VoiceTypedField("$root.voice", L10n.tr("Voice ID"), L10n.tr("Built-in voices are suggestions; custom voice IDs remain supported."), VoiceFieldKind.Text, "")
            if (id != "openai" && id != "nous") {
                val instructionKey = if (id == "xiaomi_mimo") "delivery_instructions" else "instruction"
                fields += VoiceTypedField("$root.$instructionKey", L10n.tr("Delivery instruction"), L10n.tr("Optional speaking style guidance sent to the provider."), VoiceFieldKind.Text, "")
            } else {
                fields += VoiceTypedField("$root.base_url", L10n.tr("Base URL"), customEndpointHelp("OpenAI"), VoiceFieldKind.Text, "")
                fields += VoiceTypedField(
                    "$root.speed", L10n.tr("Speed"),
                    L10n.tr("Speech rate multiplier (0.25–4.0); Hermes clamps this range. Leave blank to remove the override. Note: applies to Hermes' whole-file synthesis — upstream's current PCM streaming path does not use this setting."),
                    VoiceFieldKind.Decimal, "1", 0.25..4.0,
                )
            }
        }
        if (id == "stepfun") {
            fields += VoiceTypedField("$root.endpoint_preset", L10n.tr("Endpoint"), L10n.tr("Open Platform, Step Plan, International, or a custom endpoint."), VoiceFieldKind.Choice(listOf("open_platform", "step_plan", "international", "custom")), "open_platform")
            fields += VoiceTypedField("$root.endpoint", L10n.tr("Custom endpoint"), L10n.tr("Used only when Endpoint is Custom."), VoiceFieldKind.Text, "")
            if (kind == VoiceProviderKind.TTS) {
                fields += VoiceTypedField("$root.speed", L10n.tr("Speed"), L10n.tr("Provider speech-rate multiplier."), VoiceFieldKind.Decimal, "1")
                fields += VoiceTypedField("$root.volume", L10n.tr("Volume"), L10n.tr("Provider output volume multiplier."), VoiceFieldKind.Decimal, "1")
                fields += VoiceTypedField("$root.sample_rate", L10n.tr("Sample rate"), L10n.tr("PCM sample rate requested from Hermes."), VoiceFieldKind.Decimal, "24000")
            }
        }
        return fields
    }

    private fun decimalField(key: String): VoiceTypedField? {
        val parts = key.split('.')
        if (parts.size < 2 || (parts[0] != "stt" && parts[0] != "tts")) return null
        val kind = if (parts[0] == "stt") VoiceProviderKind.STT else VoiceProviderKind.TTS
        return typedFields(parts[1], kind).firstOrNull { it.key == key }
    }

    private fun fmt(value: Double) = if (value == Math.floor(value)) value.toString() else value.toString()

    fun validationMessage(value: String, key: String): String? {
        val field = decimalField(key) ?: return null
        if (field.kind != VoiceFieldKind.Decimal) return null
        val range = field.numericRange ?: return null
        val normalized = normalizedNumber(value)
        if (normalized.isEmpty()) return null
        val parsed = normalized.toDoubleOrNull()
            ?: return L10n.tr("%s must be a number between %s and %s, or leave blank to remove the override.", field.label, fmt(range.start), fmt(range.endInclusive))
        if (parsed !in range) {
            return L10n.tr("%s must be between %s and %s. Hermes clamps values into this range; Conduit refuses them instead of saving something upstream would silently rewrite.", field.label, fmt(range.start), fmt(range.endInclusive))
        }
        return null
    }

    fun storedValue(value: String, key: String): String {
        val trimmed = value.trim()
        val field = decimalField(key) ?: return trimmed
        if (field.kind != VoiceFieldKind.Decimal || field.numericRange == null) return trimmed
        return normalizedNumber(trimmed)
    }

    private fun normalizedNumber(value: String) = value.trim().replace(',', '.')

    private fun providerIds(schema: JsonObject?): Pair<List<String>, List<String>> {
        val stt = mutableListOf<String>()
        val tts = mutableListOf<String>()
        schema?.get("fields").arr?.mapNotNull { it.obj }?.forEach { field ->
            val key = (field["key"].str ?: field["name"].str ?: "").lowercase()
            if (key != "stt.provider" && key != "tts.provider") return@forEach
            val values = optionStrings(field["options"] ?: field["choices"])
            if (key.startsWith("stt")) stt += values else tts += values
        }
        return unique(stt) to unique(tts)
    }

    private fun optionStrings(value: JsonElement?): List<String> {
        val array = value.arr ?: return emptyList()
        return array.mapNotNull { item -> item.obj?.let { it["value"].str ?: it["id"].str ?: it["name"].str } ?: item.str }
    }

    private fun readinessRows(payload: JsonObject?, kind: VoiceProviderKind): List<VoiceProviderReadiness> =
        payload?.get("providers").arr?.mapNotNull { it.obj }?.mapNotNull { row ->
            val id = providerId(row, kind)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val credentials = row["env_vars"].arr?.mapNotNull { it.obj }?.mapNotNull { item ->
                val key = item["key"].str ?: return@mapNotNull null
                VoiceCredentialStatus(key, item["is_set"].bool ?: false, item["prompt"].str ?: key)
            }.orEmpty()
            val statusElement = row["status"]
            val status = when {
                statusElement is JsonPrimitive && statusElement.str != null -> statusElement.str!!
                statusElement is JsonObject -> statusElement["state"].str ?: statusElement["label"].str ?: L10n.tr("Unknown")
                else -> if (credentials.all { it.isSet }) "ready" else "needs_keys"
            }
            VoiceProviderReadiness(id, kind, status, row["is_active"].bool ?: false, row["name"].str ?: id, id == "nous", credentials)
        }.orEmpty()

    private fun providerId(row: JsonObject, kind: VoiceProviderKind): String? {
        val name = row["name"].str?.trim().orEmpty()
        if (row["managed_nous_feature"].str == kind.raw || name.lowercase() == "nous subscription") return "nous"
        if (kind == VoiceProviderKind.STT) row["stt_provider"].str?.takeIf { it.isNotEmpty() }?.let { return it }
        if (kind == VoiceProviderKind.TTS) row["tts_provider"].str?.takeIf { it.isNotEmpty() }?.let { return it }
        if (name.isEmpty()) return null
        when (name.lowercase()) {
            "local whisper" -> return "local"
            "openai", "openai tts" -> return "openai"
            "elevenlabs scribe" -> return "elevenlabs"
            "microsoft edge tts" -> return "edge"
            "xai tts" -> return "xai"
            "mistral (voxtral tts)" -> return "mistral"
            "google gemini tts" -> return "gemini"
        }
        return name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("_").lowercase()
    }

    private fun credentialRows(payload: JsonObject?): List<VoiceCredentialStatus> =
        payload?.entries?.mapNotNull { (key, value) ->
            val row = value.obj ?: return@mapNotNull null
            if (key != "STEPFUN_API_KEY" && key != "MIMO_API_KEY") return@mapNotNull null
            VoiceCredentialStatus(key, row["is_set"].bool ?: false, row["description"].str ?: key)
        }?.sortedBy { it.key }.orEmpty()

    private fun allCredentialRows(payload: JsonObject?): Map<String, VoiceCredentialStatus> {
        val rows = mutableMapOf<String, VoiceCredentialStatus>()
        payload?.forEach { (key, value) ->
            val row = value.obj ?: return@forEach
            val isSet = row["is_set"].bool ?: false
            rows[key] = VoiceCredentialStatus(key, isSet, row["description"].str ?: key, if (isSet) VoiceCredentialStatus.maskedValue(row["redacted_value"]) else null)
        }
        return rows
    }

    private fun mergedCredentials(values: List<VoiceCredentialStatus>): List<VoiceCredentialStatus> {
        val result = linkedMapOf<String, VoiceCredentialStatus>()
        for (value in values) {
            val existing = result[value.key]
            result[value.key] = if (existing != null) {
                VoiceCredentialStatus(value.key, existing.isSet || value.isSet, if (existing.description == existing.key) value.description else existing.description)
            } else value
        }
        return result.values.sortedBy { it.key }
    }

    private fun allVoiceValues(config: JsonObject): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (root in listOf("stt", "tts")) config[root].obj?.let { flatten(it, root, values) }
        return values
    }

    private fun flatten(obj: JsonObject, prefix: String, output: MutableMap<String, String>) {
        for ((key, value) in obj) {
            val path = "$prefix.$key"
            when {
                value is JsonObject -> flatten(value, path, output)
                value is JsonPrimitive && value.str != null -> output[path] = value.content
            }
        }
    }

    fun nestedValue(obj: JsonObject, key: String): JsonElement? {
        var current: JsonElement = obj
        for (piece in key.split('.')) current = current.obj?.get(piece) ?: return null
        return current
    }

    private fun nestedString(obj: JsonObject, key: String): String? = (nestedValue(obj, key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun nestedBool(obj: JsonObject, key: String): Boolean? {
        val value = nestedValue(obj, key) as? JsonPrimitive ?: return null
        value.booleanOrNull?.let { return it }
        value.content.toDoubleOrNull()?.let { return it != 0.0 }
        return when (value.content.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun selectedProviderIsReady(providers: List<VoiceProviderConfiguration>, selectedId: String): Boolean {
        val readiness = providers.firstOrNull { it.descriptor.id == selectedId }?.readiness ?: return false
        return readiness.isActive && readiness.status.lowercase() == "ready"
    }

    private fun unique(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it) }
    }

    /** Sets (or, for blank, removes and prunes) a dotted key in a config document. */
    fun withValue(config: JsonObject, dottedKey: String, value: JsonElement?): JsonObject {
        val path = dottedKey.split('.')
        fun apply(obj: JsonObject, index: Int): JsonObject {
            val map = obj.toMutableMap()
            val key = path[index]
            if (index == path.lastIndex) {
                if (value == null) map.remove(key) else map[key] = value
            } else {
                val child = map[key].obj
                if (child == null && value == null) return obj
                val updated = apply(child ?: JsonObject(emptyMap()), index + 1)
                if (updated.isEmpty() && value == null) map.remove(key) else map[key] = updated
            }
            return JsonObject(map)
        }
        return apply(config, 0)
    }
}

/** The one-switch happy path: Edge TTS for the assistant voice when the current one isn't ready. */
object VoiceSetupDefaults {
    const val EDGE_TTS_PROVIDER_ID = "edge"

    data class Plan(val usesOnDeviceTranscription: Boolean = false, val switchesSpeechToEdge: Boolean = false)

    fun plan(transcriptionModeChosen: Boolean, onDeviceAvailable: Boolean, snapshot: VoiceConfigurationSnapshot) =
        Plan(!transcriptionModeChosen && onDeviceAvailable, canSwitchSpeechToEdge(snapshot))

    fun canSwitchSpeechToEdge(snapshot: VoiceConfigurationSnapshot): Boolean {
        if (!snapshot.capability.isGatewayConnected || snapshot.capability.supportsSpeech || snapshot.selectedTTSProvider == EDGE_TTS_PROVIDER_ID) return false
        return providerReadiness(EDGE_TTS_PROVIDER_ID, snapshot.ttsProviders) == Readiness.Ready
    }

    sealed interface Readiness {
        data object Ready : Readiness
        data class NotReady(val status: String) : Readiness
        data object Unknown : Readiness
    }

    fun providerReadiness(id: String, providers: List<VoiceProviderConfiguration>): Readiness {
        val readiness = providers.firstOrNull { it.descriptor.id == id }?.readiness ?: return Readiness.Unknown
        return if (readiness.status.equals("ready", ignoreCase = true)) Readiness.Ready else Readiness.NotReady(readiness.status)
    }
}

/** Variables Hermes reads in order for one key, shown as one row. */
data class VoiceKeyGroup(val keys: List<String>, val note: String?) {
    companion object {
        val geminiKeys = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY")
        val openAIVoiceKeys = listOf("VOICE_TOOLS_OPENAI_KEY", "OPENAI_API_KEY")
        val xAIKeys = listOf("XAI_API_KEY")

        fun groups(credentials: List<VoiceCredentialStatus>): List<VoiceKeyGroup> {
            val groups = mutableListOf<VoiceKeyGroup>()
            for (credential in credentials) {
                val keys = listOf(openAIVoiceKeys, geminiKeys).firstOrNull { credential.key in it } ?: listOf(credential.key)
                val note = if (credential.description == credential.key) null else credential.description
                val index = groups.indexOfFirst { it.keys == keys }
                if (index >= 0) {
                    if (groups[index].note == null && note != null) groups[index] = VoiceKeyGroup(keys, note)
                    continue
                }
                groups += VoiceKeyGroup(keys, note)
            }
            return groups
        }
    }
}

/** What the notifier says about this profile's speech server. */
data class SpeechServerStatus(
    val configured: Boolean,
    val available: Boolean,
    val url: String?,
    val hasKey: Boolean,
    val reason: String?,
    val pluginMissing: Boolean,
) {
    companion object {
        val pluginMissingReason: String get() = L10n.tr("Update the Conduit plugin on your Hermes host to use your own speech server.")
        val pluginMissingStatus get() = SpeechServerStatus(false, false, null, false, pluginMissingReason, true)

        fun parse(response: JsonObject) = SpeechServerStatus(
            configured = response["configured"].bool ?: false,
            available = response["available"].bool ?: false,
            url = response["url"].str?.takeIf { it.isNotEmpty() },
            hasKey = response["has_key"].bool ?: false,
            reason = (response["reason"].str ?: response["detail"].str)?.takeIf { it.isNotEmpty() },
            pluginMissing = false,
        )
    }
}

data class SpeechServerTestResult(val connected: Boolean, val latencyMilliseconds: Int?, val reason: String?) {
    val message: String
        get() = when {
            connected && latencyMilliseconds != null -> L10n.tr("Connected. Your speech server answered in %s ms.", latencyMilliseconds.toString())
            connected -> L10n.tr("Connected to your speech server.")
            else -> reason ?: L10n.tr("Could not reach your speech server.")
        }

    companion object {
        fun parse(response: JsonObject) = SpeechServerTestResult(
            connected = response["connected"].bool ?: false,
            latencyMilliseconds = response["latency_ms"].double?.toInt(),
            reason = (response["reason"].str ?: response["detail"].str)?.takeIf { it.isNotEmpty() },
        )
    }
}

// region Voice history (PLUGIN/voice/*)

enum class VoiceSessionTagKind(val raw: String) { CALL("call"), CLASSIC("classic"), JOB("job") }

data class VoiceSessionTag(val kind: VoiceSessionTagKind, val engine: String? = null, val parentId: String? = null, val parentTitle: String? = null) {
    companion object {
        fun parse(raw: JsonElement?): VoiceSessionTag? {
            val obj = raw.obj ?: return null
            val kind = VoiceSessionTagKind.entries.firstOrNull { it.raw == obj["kind"].str } ?: return null
            fun text(key: String) = obj[key].str?.takeIf { it.isNotEmpty() }
            return VoiceSessionTag(kind, text("engine"), text("parent_id"), text("parent_title"))
        }

        fun tags(response: JsonObject): Map<String, VoiceSessionTag>? {
            if (response["ok"].bool != true) return null
            val raw = response["tags"].obj ?: return null
            return raw.mapNotNull { (id, value) -> parse(value)?.let { id to it } }.toMap()
        }
    }
}

/** One settled turn of a saved call, numbered from 0 within the call. */
data class VoiceTranscriptTurn(val index: Int, val role: String, val text: String, val atSeconds: Double) {
    fun payload() = JsonObject(mapOf("index" to JsonPrimitive(index), "role" to JsonPrimitive(role), "text" to JsonPrimitive(text), "at" to JsonPrimitive(atSeconds)))
}

data class VoiceTranscriptSaveRequest(
    val callId: String,
    val engine: String,
    val sessionId: String? = null,
    val title: String? = null,
    val turns: List<VoiceTranscriptTurn>,
    val chatId: String? = null,
) {
    /** The `POST PLUGIN/voice/sessions` body, as the Swift client writes it. */
    fun body(): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "call_id" to JsonPrimitive(callId),
            "engine" to JsonPrimitive(engine),
            "turns" to JsonArray(turns.map { it.payload() }),
        )
        if (sessionId != null) map["session_id"] = JsonPrimitive(sessionId)
        else if (!chatId.isNullOrEmpty()) map["chat_id"] = JsonPrimitive(chatId)
        if (!title.isNullOrEmpty()) map["title"] = JsonPrimitive(title)
        return JsonObject(map)
    }

    fun toJson() = JsonObject(
        buildMap {
            put("callID", JsonPrimitive(callId))
            put("engine", JsonPrimitive(engine))
            sessionId?.let { put("sessionID", JsonPrimitive(it)) }
            title?.let { put("title", JsonPrimitive(it)) }
            chatId?.let { put("chatID", JsonPrimitive(it)) }
            put("turns", JsonArray(turns.map { JsonObject(mapOf("index" to JsonPrimitive(it.index), "role" to JsonPrimitive(it.role), "text" to JsonPrimitive(it.text), "at" to JsonPrimitive(it.atSeconds))) }))
        },
    )

    companion object {
        fun fromJson(obj: JsonObject): VoiceTranscriptSaveRequest? {
            val callId = obj["callID"].str ?: return null
            val turns = obj["turns"].arr?.mapNotNull { it.obj }?.mapNotNull { t ->
                VoiceTranscriptTurn(t["index"].long?.toInt() ?: return@mapNotNull null, t["role"].str ?: return@mapNotNull null, t["text"].str.orEmpty(), t["at"].double ?: 0.0)
            }.orEmpty()
            return VoiceTranscriptSaveRequest(callId, obj["engine"].str ?: return null, obj["sessionID"].str, obj["title"].str, turns, obj["chatID"].str)
        }
    }
}

data class VoiceTranscriptSaveResult(val sessionId: String?, val written: Int, val chatId: String?) {
    companion object {
        fun parse(response: JsonObject): VoiceTranscriptSaveResult? {
            if (response["ok"].bool != true) return null
            val written = response["written"].long?.toInt() ?: return null
            val sessionId = response["session_id"].str?.takeIf { it.isNotEmpty() }
            val chatId = if (sessionId == null) response["chat_id"].str?.takeIf { it.isNotEmpty() } else null
            return VoiceTranscriptSaveResult(sessionId, written, chatId)
        }
    }
}

/**
 * Saves that failed when a call ended, kept on the device until the host
 * takes them; keyed by dashboard and profile.
 */
data class VoiceTranscriptOutbox(val entries: List<Entry> = emptyList()) {
    data class Entry(
        val dashboard: String,
        val profile: String,
        val request: VoiceTranscriptSaveRequest,
        val queuedAtMs: Long,
        val jobSessionIds: List<String> = emptyList(),
        /** The chat the call was attached to (a `VoiceCallAttachment`'s JSON): its card is added once a retry saves the call. */
        val chatAttachment: JsonObject? = null,
    )

    fun adding(entry: Entry): VoiceTranscriptOutbox {
        val list = entries.filter { it.request.callId != entry.request.callId } + entry
        return VoiceTranscriptOutbox(list.takeLast(MAXIMUM_ENTRIES))
    }

    fun pruned(nowMs: Long) = VoiceTranscriptOutbox(entries.filter { nowMs - it.queuedAtMs <= MAXIMUM_AGE_MS })

    fun pending(dashboard: String, profile: String) = entries.filter { it.dashboard == dashboard && it.profile == profile }

    fun toJson(): String = JsonObject(
        mapOf(
            "entries" to JsonArray(
                entries.map { e ->
                    JsonObject(
                        mapOf(
                            "dashboard" to JsonPrimitive(e.dashboard), "profile" to JsonPrimitive(e.profile), "request" to e.request.toJson(),
                            "queuedAt" to JsonPrimitive(e.queuedAtMs), "jobSessionIDs" to JsonArray(e.jobSessionIds.map(::JsonPrimitive)),
                        ) + (e.chatAttachment?.let { mapOf("chatAttachment" to it) } ?: emptyMap()),
                    )
                },
            ),
        ),
    ).toString()

    companion object {
        const val STORAGE_KEY = "conduit.voiceTranscriptOutbox.v1"
        const val MAXIMUM_ENTRIES = 20
        const val MAXIMUM_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun parse(json: String?): VoiceTranscriptOutbox {
            val root = json?.let { runCatching { ConduitJson.parseToJsonElement(it).obj }.getOrNull() } ?: return VoiceTranscriptOutbox()
            val entries = (root["entries"] as? JsonArray)?.mapNotNull { it.obj }?.mapNotNull { e ->
                Entry(
                    e["dashboard"].str ?: return@mapNotNull null,
                    e["profile"].str ?: return@mapNotNull null,
                    e["request"].obj?.let(VoiceTranscriptSaveRequest::fromJson) ?: return@mapNotNull null,
                    e["queuedAt"].long ?: 0,
                    e["jobSessionIDs"].arr?.mapNotNull { it.str }.orEmpty(),
                    e["chatAttachment"].obj,
                )
            }.orEmpty()
            return VoiceTranscriptOutbox(entries)
        }
    }
}

// endregion
