package app.hermesconduit.android.ui.voice

import android.content.Context
import android.widget.Toast
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.core.audio.AndroidDeviceTranscriber
import app.hermesconduit.android.core.audio.AudioRecordCapture
import app.hermesconduit.android.core.audio.AudioTrackPlayback
import app.hermesconduit.android.core.audio.HermesVoiceGateway
import app.hermesconduit.android.core.audio.VoiceAudioRoute
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.ReadAloudSpeed
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.VoiceCapabilitySnapshot
import app.hermesconduit.android.model.VoiceJobProfileTarget
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.model.VoiceSessionTagKind
import app.hermesconduit.android.model.VoiceTranscriptOutbox
import app.hermesconduit.android.model.VoiceTranscriptionMode
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.MessageActionHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app-wide voice services (the iOS AppState voice wiring): read aloud,
 * the classic voice conversation, voice background jobs, per-profile voice
 * preferences and the capability snapshot. One per process (`VoiceHub.get`).
 */
class VoiceHub private constructor(private val context: Context) {
    val hermes = context.hermes
    val settings: AppSettings = context.appSettings
    val scope = hermes.scope

    val readAloud = ReadAloudController(
        scope,
        AudioTrackPlayback(context),
        ReadAloudMediaSession(context),
        playbackRate = { ReadAloudSpeed.of(settings.float(AppSettings.READ_ALOUD_SPEED, 1f).value).rate },
        reportError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
    )

    private val capture = AudioRecordCapture(context, scope)
    private val conversationPlayback = AudioTrackPlayback(context)
    val audioRoute = VoiceAudioRoute(context).also { route ->
        route.onRouteChanged = {
            capture.routeChanged()
            liveCalls.routeChanged()
        }
    }

    val jobs: VoiceBackgroundJobSupervisor = VoiceBackgroundJobSupervisor(
        scope,
        VoiceBackgroundJobBackend(
            createSession = { profile -> createJobSession(profile) },
            setTitle = { id, title -> hermes.rpc("session.title", buildJsonObject { put("session_id", id); put("title", title) }) },
            submit = { id, text ->
                hermes.rpc("prompt.submit", buildJsonObject { put("session_id", id); put("text", text) }, app.hermesconduit.android.net.GatewayClient.timeoutFor("prompt.submit"))
            },
            cancel = { id -> hermes.rpc("session.interrupt", buildJsonObject { put("session_id", id) }) },
            liveSessions = { profile -> activeSessions(profile) },
            resolveProfile = { name -> resolveJobProfile(name) },
            threadIsBusy = { thread -> liveCalls.threadIsBusy(thread) },
            resolveThreadRuntime = { thread -> liveCalls.resolveThreadRuntime(thread) },
            submitThreadTurn = { thread, runtimeId, text -> liveCalls.submitThreadTurn(thread, runtimeId, text) },
            redirect = { sessionId, text -> liveCalls.redirect(sessionId, text) },
            latestThreadReply = { thread -> liveCalls.latestThreadReply(thread) },
            latestThreadPrompt = { thread -> liveCalls.latestThreadPrompt(thread) },
            searchChats = { query -> liveCalls.searchChats(query) },
            readChat = { id -> liveCalls.readChat(id) },
        ),
    ).also { supervisor ->
        // The open live call takes job updates; otherwise the classic conversation does.
        supervisor.onNoticePending = { if (!liveCalls.deliverPendingJobUpdates()) conversation.deliverPendingBackgroundJobNoticeIfIdle() }
        supervisor.onJobSessionCreated = { ids ->
            val job = supervisor.jobs.value.firstOrNull { job -> ids.any(job::owns) }
            // A live call's jobs are tagged with its row once the call ends.
            if (!liveCalls.noteJobSessions(ids, job)) tagSessions(ids, VoiceSessionTagKind.JOB)
        }
    }

    val conversation: VoiceConversationController = VoiceConversationController(
        scope = scope,
        capture = capture,
        playback = conversationPlayback,
        deviceTranscriber = AndroidDeviceTranscriber(context),
        routePolicyProvider = {
            val talkOver = preferences().liveVoiceSpeakerBargeIn == true && capture.cancelsEcho
            audioRoute.currentPolicy(talkOver)
        },
        backgroundJobs = jobs,
    )

    /** Gemini Live, GPT-Live and Grok Live calls. */
    val liveCalls: app.hermesconduit.android.ui.voice.live.LiveVoiceCalls = app.hermesconduit.android.ui.voice.live.LiveVoiceCalls(context, this).also { it.bind(jobs) }

    private val _capability = MutableStateFlow<VoiceCapabilitySnapshot?>(null)
    /** The active profile's voice capability, refreshed on connect and profile switch. */
    val capability: StateFlow<VoiceCapabilitySnapshot?> = _capability

    private val _conversationOpen = MutableStateFlow(false)
    val conversationOpen: StateFlow<Boolean> = _conversationOpen

    private val _preferencesVersion = MutableStateFlow(0)
    /** Bumped when a profile's voice preferences change. */
    val preferencesVersion: StateFlow<Int> = _preferencesVersion

    /** Profiles the user has, for routing "for Fam, …" voice jobs. */
    @Volatile var knownProfiles: List<String> = emptyList()

    init {
        // Hermes may read and propose the voice settings (the agent-settings catalog).
        VoiceAgentSettings.register(this)
        scope.launch {
            hermes.events.collect { event ->
                jobs.observe(event)
                assistantEvent(event)?.let(conversation::receiveAssistantEvent)
            }
        }
        scope.launch {
            combine(hermes.connection, hermes.profile) { connection, profile -> connection?.baseUrl to profile }
                .distinctUntilChanged()
                .collect {
                    val gateway = gateway()
                    readAloud.setGateway(gateway)
                    if (_conversationOpen.value) closeConversation()
                    liveCalls.reset()
                    conversation.setGateway(gateway)
                    conversation.setProfilePreferences(preferences())
                    jobs.reset()
                    _capability.value = null
                    refreshCapabilities()
                }
        }
        scope.launch { hermes.connected.collect { if (_capability.value?.isGatewayConnected != true) refreshCapabilities() } }
    }

    fun gateway(): HermesVoiceGateway? = if (hermes.isSignedIn) HermesVoiceGateway(hermes, hermes.profile.value, scope) else null

    // region Preferences

    private fun profileKey(profile: String = hermes.profile.value): String =
        (hermes.connection.value?.baseUrl?.lowercase() ?: "disconnected") + "." + profile

    fun preferences(profile: String = hermes.profile.value): VoiceProfilePreferences =
        VoiceProfilePreferences.parse(settings.getString("conduit.voice.preferences.v1.${profileKey(profile)}", "").ifEmpty { null })

    fun updatePreferences(transform: (VoiceProfilePreferences) -> VoiceProfilePreferences) {
        val next = transform(preferences())
        settings.set("conduit.voice.preferences.v1.${profileKey()}", next.toJson().toString())
        conversation.setProfilePreferences(next.copy(outputMuted = conversation.isOutputMuted.value))
        _preferencesVersion.value++
    }

    fun isVoiceEnabled(profile: String = hermes.profile.value) = settings.getBool("conduit.voice.enabled.v1.${profileKey(profile)}", false)

    /** Whether the profile has a saved speech-to-text choice. */
    fun transcriptionModeChosen() = preferences().transcriptionMode != null

    fun setVoiceEnabled(enabled: Boolean): Boolean {
        if (!hermes.isSignedIn) return false
        settings.set("conduit.voice.enabled.v1.${profileKey()}", enabled)
        if (!enabled) closeConversation()
        _preferencesVersion.value++
        return true
    }

    fun setTranscriptionMode(mode: VoiceTranscriptionMode) = updatePreferences { it.copy(transcriptionMode = mode) }

    // endregion

    // region Capability

    suspend fun refreshCapabilities() {
        if (!hermes.isSignedIn) {
            _capability.value = null
            return
        }
        val service = VoiceConfigurationService(HermesVoiceRequester(hermes, hermes.profile.value), hermes.profile.value)
        runCatching { service.reload() }
        _capability.value = service.snapshot.value.capability
    }

    fun publishCapability(snapshot: VoiceCapabilitySnapshot) {
        _capability.value = snapshot
    }

    /** TTS-only availability (no speech-to-text needed): why Read Aloud can't run, or null. */
    fun readAloudUnavailableReason(): String? {
        if (!hermes.isSignedIn || hermes.link.value == LinkState.OFFLINE) return L10n.tr("Connect to Hermes before reading responses aloud.")
        if (!isVoiceEnabled()) return VOICE_OFF
        val capability = _capability.value ?: return null
        if (!capability.supportsSpeech) return NO_TTS
        return null
    }

    /** Why the classic voice conversation can't start, named by what to fix (iOS `VoiceSetupIssue`), or null. */
    fun voiceUnavailableReason(): String? {
        if (!hermes.isSignedIn) return L10n.tr("Connect to Hermes before starting voice.")
        if (!isVoiceEnabled()) return VOICE_OFF
        val prefs = preferences()
        // A live mode needs neither Hermes' speech-to-text nor its text-to-speech: its own check runs when the call starts.
        if (prefs.geminiLiveEnabled || prefs.gptLiveEnabled || prefs.grokLiveEnabled) return null
        val capability = _capability.value ?: return null
        if (prefs.resolvedTranscriptionMode == VoiceTranscriptionMode.HERMES && !capability.supportsTranscription) {
            return capability.unavailableReason ?: L10n.tr("This Hermes profile has no ready speech-to-text provider. Set one up in Settings > Voice.")
        }
        if (!capability.supportsSpeech) return NO_TTS
        return null
    }

    // endregion

    // region Conversation

    /** Opens the classic conversation for the chat [host] speaks into. */
    fun openConversation(host: VoiceConversationHost): String? {
        voiceUnavailableReason()?.let { return it }
        val gateway = gateway() ?: return L10n.tr("Hermes could not prepare a voice conversation.")
        // No two voice modes share the audio.
        liveCalls.close()
        conversation.host = host
        conversation.setGateway(gateway)
        if (!conversation.hasLiveVoiceSession) {
            conversation.setProfilePreferences(preferences())
            conversation.beginVoiceTurn()
        }
        audioRoute.begin()
        _conversationOpen.value = true
        if (preferences().keepListeningWhenLocked == true) VoiceConversationService.start(context)
        return null
    }

    fun closeConversation() {
        // A mute chosen during the conversation is kept for the next one.
        val muted = conversation.isOutputMuted.value
        if (preferences().outputMuted != muted && hermes.isSignedIn) {
            settings.set("conduit.voice.preferences.v1.${profileKey()}", preferences().copy(outputMuted = muted).toJson().toString())
        }
        conversation.stop()
        conversation.setBackgroundListening(false)
        conversation.host = null
        audioRoute.end()
        _conversationOpen.value = false
        VoiceConversationService.stop(context)
    }

    /** The conversation screen left the foreground. */
    fun onConversationBackgrounded() {
        if (!_conversationOpen.value) return
        if (preferences().keepListeningWhenLocked == true && conversation.isConversationRunning) {
            conversation.setBackgroundListening(true)
        } else {
            conversation.setForegroundActive(false)
            conversation.suspendRuntimeForLifecycle()
        }
    }

    fun onConversationForegrounded() {
        conversation.setForegroundActive(true)
        conversation.setBackgroundListening(false)
    }

    // endregion

    // region Hermes calls

    private suspend fun createJobSession(profile: String?): Pair<String, String?> {
        val params = buildJsonObject {
            put("cols", 96)
            put("source", "desktop")
            val target = profile ?: hermes.profile.value
            if (target.isNotBlank()) put("profile", target)
            if (profile == null) {
                val prefs = preferences()
                prefs.voiceJobModel?.takeIf { it.isNotEmpty() }?.let { model ->
                    put("model", model)
                    prefs.voiceJobProvider?.takeIf { it.isNotEmpty() }?.let { put("provider", it) }
                }
                prefs.voiceJobReasoningEffort?.takeIf { it.isNotEmpty() }?.let { put("reasoning_effort", it) }
            }
        }
        val created = hermes.rpc("session.create", params).obj
        val runtime = created?.get("session_id").str.orEmpty()
        return runtime to created?.get("stored_session_id").str
    }

    private suspend fun activeSessions(profile: String?): List<LiveSessionStatus> {
        val result = hermes.rpc("session.active_list", if (profile != null) buildJsonObject { put("profile", profile) } else buildJsonObject {}).obj
        return result?.get("sessions").arr?.mapNotNull { it.obj }?.mapNotNull { row ->
            val id = row["id"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LiveSessionStatus(id, row["session_key"].str.orEmpty(), row["status"].str ?: "idle")
        }.orEmpty()
    }

    private fun resolveJobProfile(name: String): VoiceJobProfileTarget {
        val wanted = name.trim()
        if (wanted.isEmpty()) return VoiceJobProfileTarget.Unknown
        val active = hermes.profile.value
        if (wanted.equals(active, ignoreCase = true) || (active == "default" && wanted.equals(L10n.tr("Default profile"), ignoreCase = true))) {
            return VoiceJobProfileTarget.Active
        }
        val match = knownProfiles.firstOrNull { it.equals(wanted, ignoreCase = true) || it.replace('_', ' ').equals(wanted, ignoreCase = true) }
        return match?.let { VoiceJobProfileTarget.Other(it) } ?: VoiceJobProfileTarget.Unknown
    }

    /** Files sessions under Voice or Voice Jobs on the host (best effort; older plugins have no tags). */
    fun tagSessions(ids: List<String>, kind: VoiceSessionTagKind, parentId: String? = null, parentTitle: String? = null) {
        val unique = ids.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty() || !hermes.isSignedIn) return
        val client = VoiceHistoryClient(HermesVoiceRequester(hermes, hermes.profile.value))
        scope.launch { unique.forEach { id -> runCatching { client.tag(id, kind, parentId, parentTitle) } } }
    }

    // endregion

    // region Unsaved voice calls

    private val _outbox = MutableStateFlow(VoiceTranscriptOutbox.parse(settings.getString(VoiceTranscriptOutbox.STORAGE_KEY, "").ifEmpty { null }))
    val outbox: StateFlow<VoiceTranscriptOutbox> = _outbox
    private val _outboxBlocked = MutableStateFlow(false)
    val outboxBlocked: StateFlow<Boolean> = _outboxBlocked
    private val _outboxSaving = MutableStateFlow(false)
    val outboxSaving: StateFlow<Boolean> = _outboxSaving

    private fun dashboardKey() = hermes.connection.value?.id ?: "-"

    fun pendingVoiceCalls(): Int = _outbox.value.pruned(System.currentTimeMillis()).pending(dashboardKey(), hermes.profile.value)
        .count { !liveCalls.isSavingCall(it.request.callId) }

    /** Keeps a live call's unsaved turns on the device (replacing that call's earlier entry). */
    fun queueVoiceTranscript(entry: VoiceTranscriptOutbox.Entry) {
        storeOutbox(_outbox.value.pruned(System.currentTimeMillis()).adding(entry))
    }

    fun dequeueVoiceTranscript(callId: String) {
        val outbox = _outbox.value
        if (outbox.entries.none { it.request.callId == callId }) return
        storeOutbox(VoiceTranscriptOutbox(outbox.entries.filter { it.request.callId != callId }))
    }

    private fun storeOutbox(outbox: VoiceTranscriptOutbox) {
        _outbox.value = outbox
        settings.set(VoiceTranscriptOutbox.STORAGE_KEY, if (outbox.entries.isEmpty()) "" else outbox.toJson())
    }

    /** Retries the calls kept on this device for the active dashboard and profile. */
    suspend fun saveVoiceCallsNow() {
        if (_outboxSaving.value || !hermes.isSignedIn) return
        _outboxSaving.value = true
        try {
            var outbox = _outbox.value.pruned(System.currentTimeMillis())
            val client = VoiceHistoryClient(HermesVoiceRequester(hermes, hermes.profile.value))
            for (entry in outbox.pending(dashboardKey(), hermes.profile.value)) {
                // A running or closing call's own save settles its entry.
                if (liveCalls.isSavingCall(entry.request.callId)) continue
                try {
                    val result = client.save(entry.request)
                    _outboxBlocked.value = false
                    outbox = VoiceTranscriptOutbox(outbox.entries - entry)
                    if (entry.jobSessionIds.isNotEmpty()) tagSessions(entry.jobSessionIds, VoiceSessionTagKind.JOB, result.sessionId ?: result.chatId, entry.request.title)
                    // The chat the call was attached to gets its card now that the call is saved.
                    liveCalls.linkSavedCall(entry.chatAttachment, result.sessionId, result.chatId, entry.profile)
                } catch (_: VoiceHistoryClient.HistoryException.PluginMissing) {
                    _outboxBlocked.value = true
                    break
                } catch (_: VoiceHistoryClient.HistoryException.Unsupported) {
                    _outboxBlocked.value = true
                    break
                } catch (_: VoiceHistoryClient.HistoryException.RowUnavailable) {
                    // The row or chat is gone: the rest of a call alone would pass for the whole call.
                    outbox = VoiceTranscriptOutbox(outbox.entries - entry)
                } catch (_: Exception) {
                    // Kept for the next try.
                }
            }
            storeOutbox(outbox)
        } finally {
            _outboxSaving.value = false
        }
    }

    // endregion

    companion object {
        @Volatile private var instance: VoiceHub? = null

        fun get(context: Context): VoiceHub = instance ?: synchronized(this) {
            instance ?: VoiceHub(context.applicationContext).also { instance = it }
        }

        fun peek(): VoiceHub? = instance

        val VOICE_OFF: String get() = L10n.tr("Voice is off for this profile. Turn on \"Enable voice on this device\" in Settings > Voice.")
        val NO_TTS: String get() = L10n.tr("This Hermes profile has no ready text-to-speech provider. Set one up in Settings > Voice.")

        /** Installs the Read Aloud hook on assistant messages (called once at app start). */
        fun install(context: Context) {
            MessageActionHooks.readAloudButton = { message -> ReadAloudButton(message) }
        }

        fun assistantEvent(event: StreamEvent): VoiceAssistantEvent? = when (event) {
            is StreamEvent.MessageStart -> VoiceAssistantEvent.Started(event.sessionId)
            is StreamEvent.MessageDelta -> VoiceAssistantEvent.Delta(event.sessionId, event.text)
            is StreamEvent.MessageComplete -> VoiceAssistantEvent.Completed(event.sessionId, event.content)
            is StreamEvent.Error -> VoiceAssistantEvent.Failed(event.sessionId, event.message)
            is StreamEvent.Interrupted -> VoiceAssistantEvent.Interrupted(event.sessionId)
            else -> null
        }
    }
}
