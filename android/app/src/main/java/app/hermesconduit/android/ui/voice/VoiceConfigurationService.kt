package app.hermesconduit.android.ui.voice

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.SpeechServerTranscription
import app.hermesconduit.android.model.SpeechServerStatus
import app.hermesconduit.android.model.SpeechServerTestResult
import app.hermesconduit.android.model.VoiceConfigurationParser
import app.hermesconduit.android.model.VoiceConfigurationSnapshot
import app.hermesconduit.android.model.VoiceCredentialStatus
import app.hermesconduit.android.model.VoiceProviderKind
import app.hermesconduit.android.model.VoiceSessionTag
import app.hermesconduit.android.model.VoiceSessionTagKind
import app.hermesconduit.android.model.VoiceTranscriptSaveRequest
import app.hermesconduit.android.model.VoiceTranscriptSaveResult
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thin REST seam so the voice configuration logic is testable without a dashboard. */
interface VoiceConfigurationRequester {
    suspend fun get(path: String): JsonObject
    /** A GET with query parameters of its own (the profile is added as for `get`). */
    suspend fun get(path: String, query: Map<String, String>): JsonObject = get(path)
    suspend fun send(method: String, path: String, body: JsonObject): JsonObject
}

class HermesVoiceRequester(private val hermes: HermesConnection, private val profile: String) : VoiceConfigurationRequester {
    private fun query() = mapOf("profile" to profile.takeIf { it != "default" })
    override suspend fun get(path: String): JsonObject = hermes.rest { it.getJson(path, query(), timeoutMs = 12_000) }
    override suspend fun get(path: String, query: Map<String, String>): JsonObject = hermes.rest { it.getJson(path, query() + query, timeoutMs = 12_000) }
    override suspend fun send(method: String, path: String, body: JsonObject): JsonObject =
        hermes.rest { it.sendJson(method, path, body, query(), timeoutMs = 12_000) }
}

/**
 * Port of `HermesVoiceConfigurationService`: profile-scoped voice
 * configuration stays on the Hermes host. Conduit only reads credential
 * metadata (set or not, Hermes' masked value) and writes what the user saves.
 */
class VoiceConfigurationService(private val requester: VoiceConfigurationRequester, val profile: String) {
    private val _snapshot = MutableStateFlow(VoiceConfigurationSnapshot.unavailable(profile, L10n.tr("Voice settings have not been loaded.")))
    val snapshot: StateFlow<VoiceConfigurationSnapshot> = _snapshot
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    private val _speechServer = MutableStateFlow<SpeechServerStatus?>(null)
    val speechServer: StateFlow<SpeechServerStatus?> = _speechServer

    fun clearError() { _errorMessage.value = null }

    suspend fun reload() {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            coroutineScope {
                val schema = async { runCatching { requester.get("api/config/schema") } }
                val config = async { runCatching { requester.get("api/config") } }
                val stt = async { runCatching { requester.get("api/tools/toolsets/stt/config") } }
                val tts = async { runCatching { requester.get("api/tools/toolsets/tts/config") } }
                val env = async { runCatching { requester.get("api/env") } }
                val configObject = config.await().getOrElse { error ->
                    _snapshot.value = VoiceConfigurationSnapshot.unavailable(
                        profile, L10n.tr("This Hermes server doesn't offer voice settings. Update Hermes to use Voice. Text chat still works."),
                    )
                    _errorMessage.value = error.message
                    return@coroutineScope
                }
                val sttResult = stt.await().getOrNull()
                val ttsResult = tts.await().getOrNull()
                _snapshot.value = VoiceConfigurationParser.parse(
                    profile, schema.await().getOrNull(), configObject, sttResult, ttsResult, env.await().getOrNull(), ttsResult != null,
                )
                if (_speechServer.value != null) speechServerStatus()
                if (sttResult == null && ttsResult == null) {
                    _errorMessage.value = L10n.tr("Update Hermes to use Voice. This version can't tell Conduit whether voice is ready.")
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** Provider selection through the toolset endpoint (Hermes owns the row → config mapping), with the legacy fallback. */
    suspend fun saveProvider(provider: String, kind: VoiceProviderKind): Boolean {
        val providers = if (kind == VoiceProviderKind.STT) _snapshot.value.sttProviders else _snapshot.value.ttsProviders
        val row = providers.firstOrNull { it.descriptor.id == provider }?.readiness
        if (row == null) {
            if (provider.lowercase() == "nous") {
                _errorMessage.value = L10n.tr("Hermes must translate this managed selection; its provider endpoint is unavailable.")
                return false
            }
            return saveVendorSelection(provider, kind)
        }
        if (saveProviderSelection(row.displayName, kind)) return true
        if (row.isManagedNous) return false
        _errorMessage.value = null
        return saveVendorSelection(provider, kind)
    }

    private suspend fun saveVendorSelection(vendor: String, kind: VoiceProviderKind): Boolean {
        val config = runCatching { requester.get("api/config") }.getOrElse {
            _errorMessage.value = L10n.tr("Could not load voice settings to save this change.")
            return false
        }
        val section = (config[kind.raw].obj ?: JsonObject(emptyMap())).toMutableMap()
        section["provider"] = JsonPrimitive(vendor)
        section.remove("use_gateway")
        val updated = JsonObject(config + (kind.raw to JsonObject(section)))
        return try {
            requester.send("PUT", "api/config", buildJsonObject { put("config", updated) })
            reload()
            true
        } catch (e: Exception) {
            _errorMessage.value = L10n.tr("Could not save %s: %s", "${kind.raw}.provider", e.message.orEmpty())
            false
        }
    }

    private suspend fun saveProviderSelection(rowName: String, kind: VoiceProviderKind): Boolean = try {
        val response = requester.send("PUT", "api/tools/toolsets/${kind.raw}/provider", buildJsonObject { put("provider", rowName) })
        val error = response["error"].str
        if (!error.isNullOrEmpty()) {
            _errorMessage.value = L10n.tr("Could not select %s: %s", rowName, error)
            false
        } else {
            reload()
            if (response["needs_nous_auth"].bool == true) {
                val feature = if (kind == VoiceProviderKind.STT) "speech-to-text" else "speech"
                _errorMessage.value = L10n.tr("Hermes saved this selection, but the Nous subscription needs sign-in before %s can use it.", feature)
            }
            true
        }
    } catch (e: Exception) {
        _errorMessage.value = L10n.tr("Could not select %s: %s", rowName, e.message.orEmpty())
        false
    }

    suspend fun save(value: String, key: String): Boolean {
        VoiceConfigurationParser.validationMessage(value, key)?.let {
            _errorMessage.value = it
            return false
        }
        val stored = VoiceConfigurationParser.storedValue(value, key)
        val config = runCatching { requester.get("api/config") }.getOrElse {
            _errorMessage.value = L10n.tr("Could not load voice settings to save this change.")
            return false
        }
        val clears = stored.isEmpty()
        val updated = VoiceConfigurationParser.withValue(config, key, if (clears) null else JsonPrimitive(stored))
        return try {
            requester.send("PUT", "api/config", buildJsonObject { put("config", updated) })
            val values = _snapshot.value.values.toMutableMap()
            if (clears || key.endsWith("api_key")) values.remove(key) else values[key] = stored
            var next = _snapshot.value.copy(values = values)
            if (key == "stt.provider") next = next.copy(selectedSTTProvider = value)
            if (key == "tts.provider") next = next.copy(selectedTTSProvider = value)
            _snapshot.value = next
            true
        } catch (e: Exception) {
            _errorMessage.value = L10n.tr("Could not save %s: %s", key, e.message.orEmpty())
            false
        }
    }

    /** `stt.streaming`: a Hermes setting, so Hermes' own clients use it too. */
    suspend fun saveLiveTranscription(enabled: Boolean): Boolean {
        val config = runCatching { requester.get("api/config") }.getOrElse {
            _errorMessage.value = L10n.tr("Could not load voice settings to save this change.")
            return false
        }
        val updated = VoiceConfigurationParser.withValue(config, "stt.streaming", JsonPrimitive(enabled))
        return try {
            requester.send("PUT", "api/config", buildJsonObject { put("config", updated) })
            _snapshot.value = _snapshot.value.copy(liveTranscription = enabled)
            true
        } catch (e: Exception) {
            _errorMessage.value = L10n.tr("Could not save live transcription: %s", e.message.orEmpty())
            false
        }
    }

    suspend fun speechServerStatus(): SpeechServerStatus {
        val status = try {
            SpeechServerStatus.parse(requester.get(SpeechServerTranscription.STATUS_PATH))
        } catch (e: DashboardHttpException) {
            if (e.code == 404 || e.code == 405) SpeechServerStatus.pluginMissingStatus
            else SpeechServerStatus(false, false, null, false, e.message, false)
        } catch (e: Exception) {
            SpeechServerStatus(false, false, null, false, e.message, false)
        }
        _speechServer.value = status
        return status
    }

    suspend fun testSpeechServer(): SpeechServerTestResult = try {
        SpeechServerTestResult.parse(requester.send("POST", SpeechServerTranscription.TEST_PATH, JsonObject(emptyMap())))
    } catch (e: DashboardHttpException) {
        if (e.code == 404 || e.code == 405) SpeechServerTestResult(false, null, SpeechServerStatus.pluginMissingReason)
        else SpeechServerTestResult(false, null, e.message)
    } catch (e: Exception) {
        SpeechServerTestResult(false, null, e.message)
    }

    fun configuredCredential(keys: List<String>): VoiceCredentialStatus? = keys.mapNotNull(::credential).firstOrNull { it.isSet }

    fun credential(key: String): VoiceCredentialStatus? {
        val row = _snapshot.value.environment[key]
        val listed = _snapshot.value.credentials.firstOrNull { it.key == key }
        if (row?.isSet == true) return row
        if (listed?.isSet == true) return listed
        return row ?: listed
    }

    /** Never requests `/api/env/reveal`: Conduit only learns whether a key is set and its masked form. */
    suspend fun saveCredential(value: String, key: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || key.isEmpty()) return false
        return try {
            val response = requester.send("PUT", "api/env", buildJsonObject {
                put("key", key)
                put("value", trimmed)
                put("profile", profile)
            })
            val error = response["error"].str
            if (!error.isNullOrEmpty()) {
                _errorMessage.value = L10n.tr("Could not save credential: %s", error)
                false
            } else {
                reload()
                true
            }
        } catch (e: Exception) {
            _errorMessage.value = L10n.tr("Could not save credential: %s", e.message.orEmpty())
            false
        }
    }
}

/** The conduit_push plugin's voice routes (`PLUGIN/voice/…`). Every call names its profile. */
class VoiceHistoryClient(private val requester: VoiceConfigurationRequester) {
    sealed class HistoryException(message: String) : Exception(message) {
        class PluginMissing : HistoryException("plugin missing")
        class Unsupported : HistoryException("unsupported")
        class Busy : HistoryException("busy")
        class RowUnavailable : HistoryException("row unavailable")
        class Malformed : HistoryException("malformed")
    }

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: DashboardHttpException) {
        throw when (e.code) {
            404, 405 -> HistoryException.PluginMissing()
            501 -> HistoryException.Unsupported()
            409 -> HistoryException.Busy()
            422 -> HistoryException.RowUnavailable()
            else -> e
        }
    }

    suspend fun save(request: VoiceTranscriptSaveRequest): VoiceTranscriptSaveResult = mapped {
        VoiceTranscriptSaveResult.parse(requester.send("POST", SESSIONS_PATH, request.body())) ?: throw HistoryException.Malformed()
    }

    suspend fun tags(): Map<String, VoiceSessionTag> = mapped {
        VoiceSessionTag.tags(requester.get(TAGS_PATH)) ?: throw HistoryException.Malformed()
    }

    suspend fun tag(sessionId: String, kind: VoiceSessionTagKind, parentId: String? = null, parentTitle: String? = null) {
        mapped {
            requester.send("POST", TAGS_PATH, buildJsonObject {
                put("session_id", sessionId)
                put("kind", kind.raw)
                if (!parentId.isNullOrEmpty()) put("parent_id", parentId)
                if (!parentTitle.isNullOrEmpty()) put("parent_title", parentTitle)
            })
        }
    }

    /** The calls kept with a chat, oldest first. */
    suspend fun chatCalls(chatId: String, callId: String? = null): List<app.hermesconduit.android.ui.voice.live.VoiceChatCall> = mapped {
        val query = buildMap { put("chat_id", chatId); callId?.let { put("call_id", it) } }
        app.hermesconduit.android.ui.voice.live.VoiceChatCall.parse(requester.get(CHAT_CALLS_PATH, query)) ?: throw HistoryException.Malformed()
    }

    /** The stored resume summary, or null when there is none yet. */
    suspend fun summary(sessionId: String): app.hermesconduit.android.core.live.VoiceResumeSummary? = mapped {
        parseSummary(requester.get(SUMMARY_PATH, mapOf("session_id" to sessionId)))
    }

    suspend fun storeSummary(summary: app.hermesconduit.android.core.live.VoiceResumeSummary, sessionId: String) {
        mapped {
            requester.send("POST", SUMMARY_PATH, buildJsonObject {
                put("session_id", sessionId)
                put("text", summary.text)
                put("covers", summary.covers)
            })
        }
    }

    companion object {
        fun parseSummary(response: JsonObject): app.hermesconduit.android.core.live.VoiceResumeSummary? {
            if (response["ok"].bool != true || response["available"].bool != true) return null
            val text = response["text"].str?.takeIf { it.isNotEmpty() } ?: return null
            val covers = response["covers"].long?.toInt()?.takeIf { it > 0 } ?: return null
            return app.hermesconduit.android.core.live.VoiceResumeSummary(text, covers)
        }

        const val SESSIONS_PATH = "api/plugins/conduit_push/voice/sessions"
        const val TAGS_PATH = "api/plugins/conduit_push/voice/tags"
        const val SUMMARY_PATH = "api/plugins/conduit_push/voice/summary"
        const val CHAT_CALLS_PATH = "api/plugins/conduit_push/voice/chat-calls"
    }
}
