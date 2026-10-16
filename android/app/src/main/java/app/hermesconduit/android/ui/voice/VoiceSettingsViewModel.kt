package app.hermesconduit.android.ui.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.AndroidSpeech
import app.hermesconduit.android.model.VoiceProviderKind
import app.hermesconduit.android.model.VoiceSetupDefaults
import app.hermesconduit.android.model.VoiceTranscriptionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings > Voice for the active profile. */
class VoiceSettingsViewModel(application: Application) : AndroidViewModel(application) {
    val hub = VoiceHub.get(application)
    val profile: String = hub.hermes.profile.value
    val service = VoiceConfigurationService(HermesVoiceRequester(hub.hermes, profile), profile)

    private val _voiceEnabled = MutableStateFlow(hub.isVoiceEnabled())
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled
    private val _preferences = MutableStateFlow(hub.preferences())
    val preferences = _preferences
    private val _applyingDefaults = MutableStateFlow(false)
    val applyingDefaults: StateFlow<Boolean> = _applyingDefaults
    private val _savingField = MutableStateFlow<String?>(null)
    val savingField: StateFlow<String?> = _savingField
    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus
    private val _runningTest = MutableStateFlow<VoiceProviderKind?>(null)
    val runningTest: StateFlow<VoiceProviderKind?> = _runningTest

    val onDeviceAvailable: Boolean get() = AndroidSpeech.canTranscribeRecordings(getApplication())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            service.reload()
            hub.publishCapability(service.snapshot.value.capability)
            if (_preferences.value.resolvedTranscriptionMode == VoiceTranscriptionMode.SPEECH_SERVER) service.speechServerStatus()
        }
    }

    private fun refreshPreferences() {
        _preferences.value = hub.preferences()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        if (!hub.setVoiceEnabled(enabled)) return
        _voiceEnabled.value = enabled
        if (enabled) applySetupDefaults()
    }

    /** The one-switch happy path: a speech-to-text choice and an assistant voice that work with nothing installed. */
    private fun applySetupDefaults() {
        viewModelScope.launch {
            _applyingDefaults.value = true
            try {
                val prefs = hub.preferences()
                if (prefs.geminiLiveEnabled || prefs.gptLiveEnabled || prefs.grokLiveEnabled) return@launch
                if (!service.snapshot.value.capability.isGatewayConnected) service.reload()
                val plan = VoiceSetupDefaults.plan(hub.transcriptionModeChosen(), onDeviceAvailable, service.snapshot.value)
                if (plan.usesOnDeviceTranscription) {
                    hub.setTranscriptionMode(VoiceTranscriptionMode.ON_DEVICE)
                    refreshPreferences()
                }
                if (plan.switchesSpeechToEdge) service.saveProvider(VoiceSetupDefaults.EDGE_TTS_PROVIDER_ID, VoiceProviderKind.TTS)
                service.reload()
                hub.publishCapability(service.snapshot.value.capability)
            } finally {
                _applyingDefaults.value = false
            }
        }
    }

    fun selectProvider(id: String, kind: VoiceProviderKind) {
        viewModelScope.launch {
            _savingField.value = "${kind.raw}.provider"
            try {
                when {
                    kind == VoiceProviderKind.STT && id == ON_DEVICE_PROVIDER_ID -> {
                        if (!onDeviceAvailable) {
                            _testStatus.value = L10n.tr("On-device speech recognition is not available for your current language locale.")
                            return@launch
                        }
                        hub.setTranscriptionMode(VoiceTranscriptionMode.ON_DEVICE)
                        _testStatus.value = null
                    }
                    kind == VoiceProviderKind.STT && id == SPEECH_SERVER_PROVIDER_ID -> {
                        hub.setTranscriptionMode(VoiceTranscriptionMode.SPEECH_SERVER)
                        _testStatus.value = null
                        service.speechServerStatus()
                    }
                    else -> {
                        val selected = if (kind == VoiceProviderKind.STT) service.snapshot.value.selectedSTTProvider else service.snapshot.value.selectedTTSProvider
                        val saved = id == selected || service.saveProvider(id, kind)
                        if (saved && kind == VoiceProviderKind.STT) hub.setTranscriptionMode(VoiceTranscriptionMode.HERMES)
                    }
                }
                refreshPreferences()
                hub.publishCapability(service.snapshot.value.capability)
            } finally {
                _savingField.value = null
            }
        }
    }

    fun saveField(key: String, value: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _savingField.value = key
            service.save(value, key)
            _savingField.value = null
            onDone()
        }
    }

    fun saveLiveTranscription(enabled: Boolean) {
        viewModelScope.launch { service.saveLiveTranscription(enabled) }
    }

    fun saveCredential(key: String, value: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(service.saveCredential(value, key)) }
    }

    fun updatePreferences(transform: (app.hermesconduit.android.model.VoiceProfilePreferences) -> app.hermesconduit.android.model.VoiceProfilePreferences) {
        if (!hub.hermes.isSignedIn) return
        hub.updatePreferences(transform)
        refreshPreferences()
    }

    fun runTest(kind: VoiceProviderKind) {
        if (_runningTest.value != null) return
        viewModelScope.launch {
            _runningTest.value = kind
            _testStatus.value = if (kind == VoiceProviderKind.STT) L10n.tr("Listening for a short test…") else L10n.tr("Starting speech playback…")
            val controller = hub.conversation
            if (hub.conversationOpen.value) hub.closeConversation()
            // Read Aloud and a provider test would both produce audio.
            hub.readAloud.stop()
            controller.setGateway(hub.gateway())
            controller.setProfilePreferences(hub.preferences())
            val result = if (kind == VoiceProviderKind.STT) controller.runTranscriptionTest() else controller.runSpeechTest(L10n.tr("Conduit voice is ready for this profile."))
            _testStatus.value = result.message
            _runningTest.value = null
        }
    }

    fun saveVoiceCallsNow() {
        viewModelScope.launch { hub.saveVoiceCallsNow() }
    }

    companion object {
        const val ON_DEVICE_PROVIDER_ID = "apple_on_device"
        const val SPEECH_SERVER_PROVIDER_ID = "conduit_speech_server"
        const val SPEECH_SERVER_URL_KEY = "voice.speech_server.url"
        const val SPEECH_SERVER_API_KEY = "voice.speech_server.api_key"
    }
}
