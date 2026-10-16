package app.hermesconduit.android.ui.voice

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.AudioCaptureService
import app.hermesconduit.android.core.audio.BargeInRoutePolicy
import app.hermesconduit.android.core.audio.CaptureEvent
import app.hermesconduit.android.core.audio.CapturedAudio
import app.hermesconduit.android.core.audio.DeviceSpeechTranscriber
import app.hermesconduit.android.core.audio.LiveTranscription
import app.hermesconduit.android.core.audio.Pcm
import app.hermesconduit.android.core.audio.PlaybackIntent
import app.hermesconduit.android.core.audio.SpeechPlaybackService
import app.hermesconduit.android.core.audio.SpeechStream
import app.hermesconduit.android.core.audio.VoiceAudioException
import app.hermesconduit.android.core.audio.VoiceGateway
import app.hermesconduit.android.core.audio.VoiceSpeechDetection
import app.hermesconduit.android.core.audio.VoiceSpeechDetector
import app.hermesconduit.android.core.audio.VoiceSpeechDetectorConstants
import app.hermesconduit.android.model.SpokenTextFilter
import app.hermesconduit.android.model.VoiceBackgroundJobCommands
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.model.VoiceSpokenCommands
import app.hermesconduit.android.model.VoiceTranscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoiceConversationState { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, MUTED, FAILED }

enum class VoiceSteerOutcome { STEERED, NO_RUNNING_TURN, FAILED }

/** Assistant events for the voice session, from the authoritative gateway stream. */
sealed interface VoiceAssistantEvent {
    val sessionId: String
    data class Started(override val sessionId: String) : VoiceAssistantEvent
    data class Delta(override val sessionId: String, val text: String) : VoiceAssistantEvent
    data class Completed(override val sessionId: String, val content: String?) : VoiceAssistantEvent
    data class Failed(override val sessionId: String, val message: String) : VoiceAssistantEvent
    data class Interrupted(override val sessionId: String) : VoiceAssistantEvent
}

data class VoiceTranscriptEntry(val id: Long, val isUser: Boolean, val text: String)

data class VoiceProviderTestResult(val passed: Boolean, val message: String)

/** What the conversation needs from the chat it speaks into. */
interface VoiceConversationHost {
    suspend fun submit(text: String): Boolean
    /** Cancels the running Hermes turn; true also when nothing was left to cancel. */
    suspend fun interrupt(): Boolean
    /** Adds words to the running turn; null host steering turns speech-while-thinking off. */
    suspend fun steer(text: String): VoiceSteerOutcome
    /** Whether `sessionId` routes this conversation's assistant stream. */
    fun ownsSession(sessionId: String): Boolean
    /** The authoritative close (a spoken end phrase converges on it). */
    fun endConversation()
}

/**
 * Port of the iOS classic `VoiceConversationController`: a full-duplex loop
 * of adaptive speech detection, transcription (Hermes upload or live socket,
 * on-device, or the user's speech server), `prompt.submit` as a voice turn,
 * streamed speech of the reply, barge-in, steering, spoken commands and voice
 * background jobs. Everything runs on [scope] (the main dispatcher).
 */
class VoiceConversationController(
    private val scope: CoroutineScope,
    private val capture: AudioCaptureService,
    private val playback: SpeechPlaybackService,
    private val deviceTranscriber: DeviceSpeechTranscriber?,
    private val routePolicyProvider: () -> BargeInRoutePolicy,
    val configuration: Configuration = Configuration(),
    private val backgroundJobs: VoiceBackgroundJobHandling? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Configuration(
        val bargeInActivityThreshold: Float = 0.075f,
        val trailingSilenceMs: Long = 1_250,
        val idleSilenceMs: Long = 12_000,
        val maximumUtteranceMs: Long = 60_000,
        val bargeInDurationMs: Long = 300,
        val speechDetector: VoiceSpeechDetectorConstants = VoiceSpeechDetectorConstants(),
    )

    private val _state = MutableStateFlow(VoiceConversationState.IDLE)
    val state: StateFlow<VoiceConversationState> = _state
    private val _failure = MutableStateFlow<String?>(null)
    /** Set while [state] is FAILED. */
    val failure: StateFlow<String?> = _failure
    private val _latestTranscript = MutableStateFlow("")
    val latestTranscript: StateFlow<String> = _latestTranscript
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript
    private val _isSteeringTurn = MutableStateFlow(false)
    val isSteeringTurn: StateFlow<Boolean> = _isSteeringTurn
    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted
    private val _isMicrophonePaused = MutableStateFlow(false)
    val isMicrophonePaused: StateFlow<Boolean> = _isMicrophonePaused
    private val _microphoneLevel = MutableStateFlow(0f)
    val microphoneLevel: StateFlow<Float> = _microphoneLevel
    private val _isPlaybackCaptureSuspended = MutableStateFlow(false)
    val isPlaybackCaptureSuspended: StateFlow<Boolean> = _isPlaybackCaptureSuspended
    private val _transcript = MutableStateFlow<List<VoiceTranscriptEntry>>(emptyList())
    val conversationTranscript: StateFlow<List<VoiceTranscriptEntry>> = _transcript

    private var lastMeterPublication: Long? = null
    var isRuntimeSuspended = false
        private set
    var preferences = VoiceProfilePreferences()
        private set
    private var gateway: VoiceGateway? = null
    var host: VoiceConversationHost? = null

    private var steerSentThisTurn = false
    private var backgroundNoticeJob: Job? = null
    private var backgroundNoticeDeliveryJob: Job? = null
    private var backgroundHandBackWatchdogJob: Job? = null
    var backgroundHandBackStartTimeoutMs: Long = 60_000
    private var captureEventsJob: Job? = null
    private val speechDeltas = ArrayDeque<String>()
    private val spokenTextFilter = SpokenTextFilter()
    private var isDrainingSpeech = false
    private var speechStream: SpeechStream? = null
    private var assistantFinished = false
    private var receivedAssistantDelta = false
    private var utteranceStartedAt: Long? = null
    private var lastSpeechAt: Long? = null
    private var bargeInStartedAt: Long? = null
    private var isForegroundActive = true
    private var isBackgroundListeningRequested = false
    private var isVoiceSessionActive = false
    private var isAwaitingVoiceAssistant = false
    private var awaitedAssistantResponseStarted = false
    private var suspendedInFlightTurnOrphaned = false
    private var operationGeneration = 0L
    private var utteranceJob: Job? = null
    private var liveTranscription: LiveTranscription? = null
    private var liveTranscriptionOpening: Job? = null
    private var finishingLiveTranscription: LiveTranscription? = null
    private var liveTranscriptionWindow = 0L
    private var liveTranscriptionAttemptedWindow: Long? = null
    private var liveTranscriptionSentBytes = 0
    private var liveTranscriptionUnavailable = false
    private var speechServerFailure: String? = null
    private var bargeInJob: Job? = null
    private var speechDrainJob: Job? = null
    private var speechDrainRevision = 0L
    private var isProviderTestRunning = false
    private var activeAssistantEntryId: Long? = null
    private var entryCounter = 0L
    private val speechDetector = VoiceSpeechDetector(configuration.speechDetector)
    private var cachedRoutePolicy: BargeInRoutePolicy? = null

    init {
        captureEventsJob = scope.launch { capture.events.collect { handleCaptureEvent(it) } }
    }

    private fun setState(value: VoiceConversationState, failureMessage: String? = null) {
        _failure.value = if (value == VoiceConversationState.FAILED) failureMessage else null
        _state.value = value
    }

    private fun fail(message: String) = setState(VoiceConversationState.FAILED, message)

    val currentState: VoiceConversationState get() = _state.value

    fun setGateway(gateway: VoiceGateway?) {
        if (gateway !== this.gateway) liveTranscriptionUnavailable = false
        this.gateway = gateway
    }

    val isGatewayAttached: Boolean get() = gateway != null

    /** Establishes ownership of assistant events for a fresh voice conversation. */
    fun beginVoiceTurn() {
        isVoiceSessionActive = true
        isAwaitingVoiceAssistant = false
        awaitedAssistantResponseStarted = false
        _transcript.value = emptyList()
        activeAssistantEntryId = null
    }

    fun setProfilePreferences(preferences: VoiceProfilePreferences) {
        this.preferences = preferences
        _isOutputMuted.value = preferences.outputMuted
    }

    val isContinuousConversationEnabled: Boolean get() = preferences.continuousConversation

    /** True while a voice session or provider test is armed or live. */
    val hasLiveVoiceSession: Boolean get() = isVoiceSessionActive || isProviderTestRunning

    fun setForegroundActive(active: Boolean) {
        isForegroundActive = active
    }

    fun setBackgroundListening(active: Boolean) {
        isBackgroundListeningRequested = active
    }

    val isBackgroundListening: Boolean get() = isBackgroundListeningRequested && isConversationRunning

    val isConversationRunning: Boolean
        get() {
            if (!isVoiceSessionActive || isRuntimeSuspended || _isMicrophonePaused.value) return false
            return when (_state.value) {
                VoiceConversationState.LISTENING, VoiceConversationState.TRANSCRIBING, VoiceConversationState.THINKING,
                VoiceConversationState.SPEAKING, VoiceConversationState.MUTED -> true
                else -> false
            }
        }

    fun startListening(includePreRoll: Boolean = false) {
        scope.launch { startListeningNow(includePreRoll) }
    }

    private fun startListeningNow(includePreRoll: Boolean = false) {
        if (!isForegroundActive && !isBackgroundListeningRequested) return
        isVoiceSessionActive = true
        isRuntimeSuspended = false
        if (gateway == null) {
            releaseHeldCapture()
            fail(L10n.tr("Voice isn't set up yet. Check Settings > Voice."))
            return
        }
        if (!capture.hasPermission()) {
            releaseHeldCapture()
            fail(L10n.tr(app.hermesconduit.android.core.audio.AudioRecordCapture.MICROPHONE_DENIED))
            return
        }
        try {
            capture.startListening(includePreRoll)
            resetLiveTranscription()
            cachedRoutePolicy = null
            if (_isMicrophonePaused.value || _isPlaybackCaptureSuspended.value) capture.pause()
            speechDetector.reset()
            resetMicrophoneMeter()
            utteranceStartedAt = clock()
            lastSpeechAt = null
            bargeInStartedAt = null
            setState(VoiceConversationState.LISTENING)
            scheduleBackgroundJobNoticeDelivery()
        } catch (e: Exception) {
            fail(
                if (isBackgroundListeningRequested) L10n.tr("Listening stopped while your phone was locked. Tap Listen to continue.")
                else e.message ?: L10n.tr("The microphone is not available right now."),
            )
        }
    }

    fun pauseMicrophone() {
        capture.pause()
        resetLiveTranscription()
        speechDetector.reset()
        resetMicrophoneMeter()
        _isMicrophonePaused.value = true
    }

    fun resumeMicrophone() {
        if (!isForegroundActive) return
        if (_isPlaybackCaptureSuspended.value) {
            scope.launch { interruptAssistantPlayback() }
            return
        }
        if (!_isMicrophonePaused.value) {
            if (_state.value == VoiceConversationState.IDLE || _state.value == VoiceConversationState.FAILED) startListeningNow()
            return
        }
        if (!isVoiceSessionActive) return
        try {
            capture.resume()
            _isMicrophonePaused.value = false
            cachedRoutePolicy = null
            isRuntimeSuspended = false
            speechDetector.reset()
            utteranceStartedAt = clock()
            lastSpeechAt = null
            bargeInStartedAt = null
            if (_state.value == VoiceConversationState.IDLE) setState(VoiceConversationState.LISTENING)
        } catch (e: Exception) {
            fail(e.message ?: L10n.tr("The microphone is not available right now."))
        }
    }

    /** The speaker-safe Interrupt: stop playback, retire the turn and open a fresh window (no pre-roll). */
    suspend fun interruptAssistantPlayback() {
        if (!_isPlaybackCaptureSuspended.value) return
        cachedRoutePolicy = null
        val generation = operationGeneration
        playback.stop()
        cancelSpeechDrainAndStream()
        clearSpeechQueue()
        assistantFinished = false
        isAwaitingVoiceAssistant = false
        awaitedAssistantResponseStarted = false
        _isPlaybackCaptureSuspended.value = false
        _isMicrophonePaused.value = false
        host?.interrupt()
        if (!isCurrent(generation)) return
        startListeningNow()
    }

    fun stop() {
        releaseRuntimeResources()
        liveTranscriptionUnavailable = false
        _isMicrophonePaused.value = false
        isVoiceSessionActive = false
        isAwaitingVoiceAssistant = false
        awaitedAssistantResponseStarted = false
        suspendedInFlightTurnOrphaned = false
        isRuntimeSuspended = false
        setState(VoiceConversationState.IDLE)
    }

    /** App backgrounded with the conversation open (and no background listening): release audio, keep the conversation. */
    fun suspendRuntimeForLifecycle() {
        releaseRuntimeResources()
        suspendedInFlightTurnOrphaned = suspendedInFlightTurnOrphaned || isAwaitingVoiceAssistant
        isAwaitingVoiceAssistant = false
        awaitedAssistantResponseStarted = false
        isRuntimeSuspended = true
        setState(VoiceConversationState.IDLE)
    }

    private fun releaseRuntimeResources() {
        operationGeneration++
        cachedRoutePolicy = null
        utteranceJob?.cancel()
        utteranceJob = null
        bargeInJob?.cancel()
        bargeInJob = null
        backgroundNoticeJob?.cancel()
        backgroundNoticeJob = null
        backgroundNoticeDeliveryJob?.cancel()
        backgroundNoticeDeliveryJob = null
        cancelBackgroundHandBackWatchdog()
        cancelSpeechDrainAndStream()
        capture.stop()
        cancelLiveTranscription()
        deviceTranscriber?.cancel()
        playback.stop()
        clearSpeechQueue()
        assistantFinished = false
        _isPlaybackCaptureSuspended.value = false
        _isSteeringTurn.value = false
        steerSentThisTurn = false
        speechDetector.reset()
        resetMicrophoneMeter()
    }

    fun setOutputMuted(muted: Boolean) {
        _isOutputMuted.value = muted
        if (muted) {
            playback.stop()
            cancelSpeechDrainAndStream()
            clearSpeechQueue()
            if (_state.value == VoiceConversationState.SPEAKING) setState(VoiceConversationState.MUTED)
            endPlaybackCaptureSuspensionAfterMute()
        } else if (_state.value == VoiceConversationState.MUTED) {
            setState(VoiceConversationState.THINKING)
        }
    }

    private fun endPlaybackCaptureSuspensionAfterMute() {
        if (!_isPlaybackCaptureSuspended.value) return
        _isPlaybackCaptureSuspended.value = false
        if (_isMicrophonePaused.value || !isVoiceSessionActive) return
        try {
            capture.resume()
            beginBargeInMonitoring()
        } catch (e: Exception) {
            fail(e.message ?: "")
        }
    }

    // region Provider tests

    /** Records a short sample and transcribes it without a chat turn (Settings > Voice). */
    suspend fun runTranscriptionTest(durationMs: Long = 4_000): VoiceProviderTestResult {
        stop()
        val gateway = gateway ?: return VoiceProviderTestResult(false, L10n.tr("Conduit could not connect this test to the selected profile."))
        isVoiceSessionActive = true
        isProviderTestRunning = true
        val generation = operationGeneration
        try {
            if (!capture.hasPermission()) return VoiceProviderTestResult(false, L10n.tr(app.hermesconduit.android.core.audio.AudioRecordCapture.MICROPHONE_DENIED))
            capture.startListening(false)
            setState(VoiceConversationState.LISTENING)
            delay(durationMs)
            if (!isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech-to-text test was cancelled."))
            val audio = capture.finishUtterance()
            setState(VoiceConversationState.TRANSCRIBING)
            resetMicrophoneMeter()
            val transcript = transcribe(audio, gateway, null)
            if (!isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech-to-text test was cancelled."))
            if (transcript.isEmpty()) return VoiceProviderTestResult(false, L10n.tr("The selected speech-to-text provider returned an empty transcript."))
            _latestTranscript.value = transcript
            return VoiceProviderTestResult(true, L10n.tr("Transcribed: %s", transcript))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: L10n.tr("Voice needs attention")
            return VoiceProviderTestResult(false, message)
        } finally {
            capture.stop()
            isProviderTestRunning = false
            resetMicrophoneMeter()
            if (isCurrent(generation)) setState(VoiceConversationState.IDLE)
            isVoiceSessionActive = false
        }
    }

    /** Speaks a fixed phrase through the same streaming/fallback transport as a conversation. */
    suspend fun runSpeechTest(text: String): VoiceProviderTestResult {
        stop()
        val gateway = gateway ?: return VoiceProviderTestResult(false, L10n.tr("Conduit could not connect this test to the selected profile."))
        isVoiceSessionActive = true
        isProviderTestRunning = true
        playback.intent = PlaybackIntent.STANDALONE
        playback.playbackRate = 1f
        val generation = operationGeneration
        var deliveredAudio = false
        try {
            setState(VoiceConversationState.THINKING)
            val stream = gateway.openSpeechStream(
                onStart = { rate ->
                    playback.start(rate)
                    setState(VoiceConversationState.SPEAKING)
                },
                onPcm16 = { data, rate -> if (playback.enqueuePcm16(data, rate) > 0) deliveredAudio = true },
                onEncodedAudio = { data ->
                    if (data.isNotEmpty()) {
                        playback.playEncoded(data)
                        deliveredAudio = true
                        setState(VoiceConversationState.SPEAKING)
                    }
                },
            )
            speechStream = stream
            stream.append(text)
            if (!isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech playback test was cancelled."))
            stream.finish()
            if (!isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech playback test was cancelled."))
            playback.finish()
            playback.drain()
            if (!isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech playback test was cancelled."))
            if (!deliveredAudio) return VoiceProviderTestResult(false, L10n.tr("Hermes connected, but the selected speech provider returned no audio."))
            return VoiceProviderTestResult(true, L10n.tr("Speech playback completed."))
        } catch (e: CancellationException) {
            if (isCurrent(generation)) return VoiceProviderTestResult(false, L10n.tr("The speech playback test was cancelled."))
            throw e
        } catch (e: Exception) {
            return VoiceProviderTestResult(false, e.message ?: L10n.tr("Hermes speech stream failed."))
        } finally {
            speechStream?.cancel()
            speechStream = null
            playback.stop()
            isProviderTestRunning = false
            resetMicrophoneMeter()
            if (isCurrent(generation)) setState(VoiceConversationState.IDLE)
            isVoiceSessionActive = false
        }
    }

    // endregion

    // region Levels and detection

    /** Public for deterministic state-machine tests. */
    fun ingestAudioLevel(level: Float, at: Long = clock()) {
        when (_state.value) {
            VoiceConversationState.LISTENING -> {
                if (utteranceStartedAt == null) utteranceStartedAt = at
                when (speechDetector.observe(level)) {
                    VoiceSpeechDetection.STARTED, VoiceSpeechDetection.CONTINUED -> {
                        lastSpeechAt = at
                        openLiveTranscriptionIfNeeded()
                    }
                    VoiceSpeechDetection.NONE -> Unit
                }
                pumpLiveTranscription()
                val started = utteranceStartedAt
                val speech = lastSpeechAt
                when {
                    started != null && at - started >= configuration.maximumUtteranceMs -> scheduleFinishUtterance()
                    speech != null && at - speech >= configuration.trailingSilenceMs -> scheduleFinishUtterance()
                    speech == null && started != null && at - started >= configuration.idleSilenceMs -> {
                        when {
                            _isSteeringTurn.value -> resumeTurnAfterSteer()
                            isContinuousConversationEnabled || isBackgroundListening -> restartSilentListeningWindow(at)
                            else -> pauseMicrophone()
                        }
                    }
                }
            }
            VoiceConversationState.THINKING, VoiceConversationState.SPEAKING, VoiceConversationState.MUTED -> {
                if (_isPlaybackCaptureSuspended.value) return
                val talkOver = isTalkOverAvailable
                if (!talkOver && !isSteerAvailable) {
                    bargeInStartedAt = null
                    return
                }
                if (level >= configuration.bargeInActivityThreshold) {
                    if (bargeInStartedAt == null) bargeInStartedAt = at
                    val started = bargeInStartedAt
                    if (started != null && at - started >= configuration.bargeInDurationMs) {
                        if (talkOver) scheduleBargeIn() else scheduleSteer()
                    }
                } else {
                    bargeInStartedAt = null
                }
            }
            else -> Unit
        }
    }

    private fun handleCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Level -> {
                if (event.generation != capture.captureGeneration) return
                if (isRuntimeSuspended) return
                val captureLive = !_isMicrophonePaused.value && !_isPlaybackCaptureSuspended.value && isVoiceSessionActive
                if (!captureLive && !isProviderTestRunning) return
                if (_state.value == VoiceConversationState.TRANSCRIBING) return
                val last = lastMeterPublication
                if (last == null || event.atMs - last >= 50) {
                    _microphoneLevel.value = event.level
                    lastMeterPublication = event.atMs
                }
                if (isProviderTestRunning) return
                ingestAudioLevel(event.level, event.atMs)
            }
            is CaptureEvent.Interrupted -> {
                if (isRuntimeSuspended) return
                if (event.generation != capture.captureGeneration) return
                failForAudioInterruption()
            }
            CaptureEvent.RouteChanged -> {
                if (isProviderTestRunning) return
                bargeInStartedAt = null
                cachedRoutePolicy = null
                if (_state.value == VoiceConversationState.LISTENING) speechDetector.reset()
                if (currentRoutePolicy() == BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX &&
                    (_state.value == VoiceConversationState.SPEAKING || _isPlaybackCaptureSuspended.value)
                ) suspendCaptureForPlayback()
            }
        }
    }

    private fun resetMicrophoneMeter() {
        _microphoneLevel.value = 0f
        lastMeterPublication = null
    }

    private fun restartSilentListeningWindow(at: Long) {
        try {
            capture.startListening(false)
            resetLiveTranscription()
            speechDetector.reset()
            resetMicrophoneMeter()
            bargeInStartedAt = null
            lastSpeechAt = null
            utteranceStartedAt = at
        } catch (_: Exception) {
            pauseMicrophone()
        }
    }

    // endregion

    // region Utterances

    private fun scheduleFinishUtterance() {
        if (utteranceJob != null) return
        val generation = operationGeneration
        utteranceJob = scope.launch {
            try {
                finishUtterance(generation)
            } finally {
                if (operationGeneration == generation && utteranceJob === kotlin.coroutines.coroutineContext[Job]) utteranceJob = null
            }
        }
    }

    private suspend fun finishUtterance(generation: Long) {
        if (_state.value != VoiceConversationState.LISTENING) return
        val gateway = gateway ?: return
        try {
            val live = detachLiveTranscription()
            val audio = try {
                capture.finishUtterance()
            } catch (e: Exception) {
                live?.cancel()
                throw e
            }
            setState(VoiceConversationState.TRANSCRIBING)
            resetMicrophoneMeter()
            speechDetector.reset()
            val transcript = transcribe(audio, gateway, live)
            if (!isCurrent(generation)) return
            _latestTranscript.value = transcript
            _liveTranscript.value = ""
            val steering = _isSteeringTurn.value
            if (transcript.isEmpty()) {
                if (steering) resumeTurnAfterSteer() else startListeningNow()
                return
            }
            if (isEndConversationCommand(transcript)) {
                endConversationForSpokenCommand()
                return
            }
            appendEntry(true, transcript)
            val jobs = backgroundJobs
            if (steering && !isStopCommand(transcript) && jobs != null) {
                VoiceBackgroundJobCommands.parse(transcript)?.let { command ->
                    val reply = jobs.performVoiceCommand(command)
                    if (!isCurrent(generation)) return
                    appendEntry(false, reply)
                    resumeTurnAfterSteer()
                    return
                }
            }
            if (steering) {
                val currentHost = host
                if (!isStopCommand(transcript) && isAwaitingVoiceAssistant && currentHost != null) {
                    when (currentHost.steer(transcript)) {
                        VoiceSteerOutcome.STEERED -> {
                            if (!isCurrent(generation)) return
                            if (isAwaitingVoiceAssistant) steerSentThisTurn = true
                            resumeTurnAfterSteer()
                            return
                        }
                        VoiceSteerOutcome.FAILED -> {
                            if (!isCurrent(generation)) return
                            resumeTurnAfterSteer()
                            return
                        }
                        VoiceSteerOutcome.NO_RUNNING_TURN -> if (!isCurrent(generation)) return
                    }
                }
                // Stop, or the turn finished while the user spoke: the held reply speech is dropped.
                _isSteeringTurn.value = false
                cancelSpeechDrainAndStream()
                clearSpeechQueue()
                assistantFinished = false
            }
            if (isStopCommand(transcript)) {
                host?.interrupt()
                if (!isCurrent(generation)) return
                playback.stop()
                startListeningNow()
                return
            }
            if (jobs != null) {
                VoiceBackgroundJobCommands.parse(transcript)?.let { command ->
                    setState(VoiceConversationState.THINKING)
                    beginBargeInMonitoring()
                    val reply = jobs.performVoiceCommand(command)
                    if (!isCurrent(generation) || _state.value != VoiceConversationState.THINKING) return
                    speakLocalNotice(reply)
                    return
                }
            }
            setState(VoiceConversationState.THINKING)
            beginBargeInMonitoring()
            if (suspendedInFlightTurnOrphaned) {
                val cancelled = host?.interrupt() ?: true
                if (!isCurrent(generation)) return
                if (!cancelled) {
                    releaseRuntimeResources()
                    fail(L10n.tr("Hermes could not cancel the previous response."))
                    return
                }
                suspendedInFlightTurnOrphaned = false
            }
            cancelBackgroundHandBackWatchdog()
            isAwaitingVoiceAssistant = true
            awaitedAssistantResponseStarted = false
            steerSentThisTurn = false
            val submitted = host?.submit(transcript) ?: false
            if (!submitted) {
                if (!isCurrent(generation)) return
                isAwaitingVoiceAssistant = false
                fail(L10n.tr("Hermes could not submit the transcription."))
            }
        } catch (e: CancellationException) {
            _isSteeringTurn.value = false
            if (isCurrent(generation)) {
                _liveTranscript.value = ""
                setState(VoiceConversationState.IDLE)
            }
            throw e
        } catch (e: Exception) {
            if (!isCurrent(generation)) return
            _liveTranscript.value = ""
            if (_isSteeringTurn.value) {
                resumeTurnAfterSteer()
                return
            }
            fail(e.message ?: L10n.tr("Voice needs attention"))
        }
    }

    private suspend fun transcribe(audio: CapturedAudio, gateway: VoiceGateway, live: LiveTranscription?): String {
        when (preferences.resolvedTranscriptionMode) {
            VoiceTranscriptionMode.HERMES -> {
                if (live != null) {
                    finishingLiveTranscription = live
                    val text = live.finish()
                    if (finishingLiveTranscription === live) finishingLiveTranscription = null
                    if (text != null) return text
                }
                return gateway.transcribe(audio)
            }
            VoiceTranscriptionMode.ON_DEVICE -> {
                live?.cancel()
                val transcriber = deviceTranscriber ?: throw VoiceAudioException(L10n.tr("On-device speech recognition is not available for your current language locale."))
                return transcriber.transcribe(audio)
            }
            VoiceTranscriptionMode.SPEECH_SERVER -> {
                var session = live
                if (session == null) {
                    speechServerFailure = null
                    session = gateway.openSpeechServerTranscription(audio.sampleRate, {}, { reason -> speechServerFailure = reason })
                    session?.push(audio.pcm16)
                }
                val server = session ?: throw VoiceAudioException(speechServerFailure ?: L10n.tr("Could not reach your speech server through Hermes."))
                finishingLiveTranscription = server
                val text = server.finish()
                if (finishingLiveTranscription === server) finishingLiveTranscription = null
                if (text != null) return text
                speechServerFailure?.let { throw VoiceAudioException(it) }
                return ""
            }
        }
    }

    private fun resetLiveTranscription() {
        liveTranscriptionWindow++
        liveTranscriptionOpening?.cancel()
        liveTranscriptionOpening = null
        liveTranscription?.cancel()
        liveTranscription = null
        liveTranscriptionSentBytes = 0
        if (_liveTranscript.value.isNotEmpty()) _liveTranscript.value = ""
    }

    private fun cancelLiveTranscription() {
        resetLiveTranscription()
        finishingLiveTranscription?.cancel()
        finishingLiveTranscription = null
    }

    private fun openLiveTranscriptionIfNeeded() {
        if (liveTranscriptionAttemptedWindow == liveTranscriptionWindow || isProviderTestRunning) return
        val gateway = gateway ?: return
        val opener: suspend ((String) -> Unit) -> LiveTranscription? = when (preferences.resolvedTranscriptionMode) {
            VoiceTranscriptionMode.HERMES -> {
                if (liveTranscriptionUnavailable) return
                { onPartial -> gateway.openLiveTranscription(Pcm.CAPTURE_SAMPLE_RATE, onPartial) { liveTranscriptionUnavailable = true } }
            }
            VoiceTranscriptionMode.SPEECH_SERVER -> {
                speechServerFailure = null
                { onPartial -> gateway.openSpeechServerTranscription(Pcm.CAPTURE_SAMPLE_RATE, onPartial) { reason -> speechServerFailure = reason } }
            }
            VoiceTranscriptionMode.ON_DEVICE -> return
        }
        val window = liveTranscriptionWindow
        liveTranscriptionAttemptedWindow = window
        liveTranscriptionOpening = scope.launch {
            val session = opener { text -> if (liveTranscriptionWindow == window) _liveTranscript.value = text }
            if (liveTranscriptionWindow != window) {
                session?.cancel()
                return@launch
            }
            liveTranscriptionOpening = null
            liveTranscription = session
            pumpLiveTranscription()
        }
    }

    private fun pumpLiveTranscription() {
        val live = liveTranscription ?: return
        val recorded = capture.recordedPcm16
        if (recorded.size <= liveTranscriptionSentBytes) return
        live.push(recorded.copyOfRange(liveTranscriptionSentBytes, recorded.size))
        liveTranscriptionSentBytes = recorded.size
    }

    private fun detachLiveTranscription(): LiveTranscription? {
        pumpLiveTranscription()
        val session = liveTranscription
        liveTranscription = null
        liveTranscriptionWindow++
        liveTranscriptionOpening?.cancel()
        liveTranscriptionOpening = null
        liveTranscriptionSentBytes = 0
        return session
    }

    // endregion

    // region Playback capture policy

    private fun beginBargeInMonitoring() {
        if (_isPlaybackCaptureSuspended.value) return
        try {
            capture.beginBargeInMonitoring()
            if (_isMicrophonePaused.value) capture.pause()
        } catch (e: Exception) {
            fail(e.message ?: L10n.tr("The microphone is not available right now."))
        }
    }

    private fun applyPlaybackCapturePolicy() {
        if (_isPlaybackCaptureSuspended.value) return
        if (currentRoutePolicy() == BargeInRoutePolicy.SPEAKER_SAFE_HALF_DUPLEX) suspendCaptureForPlayback() else beginBargeInMonitoring()
    }

    private fun currentRoutePolicy(): BargeInRoutePolicy {
        cachedRoutePolicy?.let { return it }
        return routePolicyProvider().also { cachedRoutePolicy = it }
    }

    private fun suspendCaptureForPlayback() {
        bargeInJob?.cancel()
        bargeInJob = null
        if (_isPlaybackCaptureSuspended.value) return
        _isPlaybackCaptureSuspended.value = true
        bargeInStartedAt = null
        speechDetector.reset()
        resetMicrophoneMeter()
        if (preferences.keepListeningWhenLocked == true) capture.holdForPlayback() else capture.pause()
    }

    private fun releaseHeldCapture() {
        if (capture.isHeldForPlayback) capture.pause()
    }

    private fun endPlaybackCaptureSuspensionWithoutRelistening() {
        if (_isPlaybackCaptureSuspended.value) capture.pause()
        _isPlaybackCaptureSuspended.value = false
    }

    private fun failForAudioInterruption() {
        releaseRuntimeResources()
        _isMicrophonePaused.value = false
        isVoiceSessionActive = false
        isAwaitingVoiceAssistant = false
        awaitedAssistantResponseStarted = false
        suspendedInFlightTurnOrphaned = false
        isRuntimeSuspended = false
        fail(L10n.tr("Audio was interrupted."))
    }

    private val isTalkOverAvailable: Boolean
        get() = _state.value == VoiceConversationState.SPEAKING && (playback.isPlaying || !isAwaitingVoiceAssistant)

    private val isSteerAvailable: Boolean
        get() = host != null && _state.value == VoiceConversationState.THINKING && isAwaitingVoiceAssistant &&
            !_isOutputMuted.value && utteranceJob == null && backgroundNoticeJob == null && !suspendedInFlightTurnOrphaned &&
            speechDeltas.isEmpty() && !isDrainingSpeech && speechStream == null

    // endregion

    // region Steering and barge-in

    private fun scheduleSteer() {
        if (bargeInJob != null) return
        val generation = operationGeneration
        bargeInJob = scope.launch {
            if (_isPlaybackCaptureSuspended.value || !isSteerAvailable) {
                if (operationGeneration == generation) bargeInJob = null
                return@launch
            }
            _isSteeringTurn.value = true
            startListeningNow(includePreRoll = true)
            if (operationGeneration == generation) bargeInJob = null
            if (!isCurrent(generation)) {
                _isSteeringTurn.value = false
                return@launch
            }
            if (_isSteeringTurn.value && _state.value != VoiceConversationState.LISTENING) resumeTurnAfterSteer()
        }
    }

    private fun resumeTurnAfterSteer() {
        _isSteeringTurn.value = false
        if (_state.value == VoiceConversationState.IDLE || _state.value == VoiceConversationState.FAILED) return
        setState(VoiceConversationState.THINKING)
        beginBargeInMonitoring()
        if (speechDeltas.isNotEmpty() || assistantFinished) startSpeechDrainIfNeeded()
    }

    private fun scheduleBargeIn() {
        if (bargeInJob != null) return
        val generation = operationGeneration
        bargeInJob = scope.launch {
            try {
                if (_isPlaybackCaptureSuspended.value || !isTalkOverAvailable) return@launch
                val turnStillRunning = isAwaitingVoiceAssistant
                playback.stop()
                cancelSpeechDrainAndStream()
                clearSpeechQueue()
                assistantFinished = false
                isAwaitingVoiceAssistant = false
                awaitedAssistantResponseStarted = false
                if (turnStillRunning) host?.interrupt()
                if (!isCurrent(generation) || _isPlaybackCaptureSuspended.value) return@launch
                startListeningNow(includePreRoll = true)
            } finally {
                if (operationGeneration == generation) bargeInJob = null
            }
        }
    }

    private suspend fun endConversationForSpokenCommand() {
        utteranceJob = null
        val currentHost = host
        if (currentHost != null) currentHost.endConversation() else stop()
        currentHost?.interrupt()
    }

    // endregion

    // region Background jobs

    private fun scheduleBackgroundJobNoticeDelivery() {
        if (backgroundJobs == null) return
        backgroundNoticeDeliveryJob?.cancel()
        backgroundNoticeDeliveryJob = scope.launch {
            backgroundNoticeDeliveryJob = null
            deliverPendingBackgroundJobNoticeIfIdle()
        }
    }

    /** Hands the next pending job update to the conversation, only in a quiet listening window. */
    fun deliverPendingBackgroundJobNoticeIfIdle() {
        val jobs = backgroundJobs ?: return
        if (!isVoiceSessionActive || isRuntimeSuspended || isProviderTestRunning) return
        if (_state.value != VoiceConversationState.LISTENING || lastSpeechAt != null || utteranceJob != null || bargeInJob != null) return
        if (backgroundNoticeJob != null || isAwaitingVoiceAssistant || isDrainingSpeech || suspendedInFlightTurnOrphaned) return
        when (val notice = jobs.takePendingNotice() ?: return) {
            is VoiceBackgroundJobNotice.Speak -> speakLocalNotice(notice.text)
            is VoiceBackgroundJobNotice.Submit -> {
                val generation = operationGeneration
                setState(VoiceConversationState.THINKING)
                beginBargeInMonitoring()
                isAwaitingVoiceAssistant = true
                awaitedAssistantResponseStarted = false
                backgroundNoticeJob = scope.launch {
                    val submitted = host?.submit(notice.prompt) ?: false
                    if (operationGeneration != generation) return@launch
                    backgroundNoticeJob = null
                    if (!isCurrent(generation) || _state.value != VoiceConversationState.THINKING) return@launch
                    if (!submitted) {
                        isAwaitingVoiceAssistant = false
                        speakLocalNotice(notice.fallback)
                        return@launch
                    }
                    startBackgroundHandBackWatchdog(generation, notice.fallback)
                }
            }
        }
    }

    private fun startBackgroundHandBackWatchdog(generation: Long, fallback: String) {
        if (!isAwaitingVoiceAssistant || awaitedAssistantResponseStarted) return
        backgroundHandBackWatchdogJob?.cancel()
        backgroundHandBackWatchdogJob = scope.launch {
            delay(backgroundHandBackStartTimeoutMs)
            backgroundHandBackWatchdogJob = null
            if (!isCurrent(generation) || _state.value != VoiceConversationState.THINKING || !isAwaitingVoiceAssistant || awaitedAssistantResponseStarted) return@launch
            isAwaitingVoiceAssistant = false
            speakLocalNotice(fallback)
        }
    }

    private fun cancelBackgroundHandBackWatchdog() {
        backgroundHandBackWatchdogJob?.cancel()
        backgroundHandBackWatchdogJob = null
    }

    private fun speakLocalNotice(text: String) {
        appendEntry(false, text)
        activeAssistantEntryId = null
        if (_state.value != VoiceConversationState.THINKING) {
            setState(VoiceConversationState.THINKING)
            beginBargeInMonitoring()
        }
        cancelSpeechDrainAndStream()
        clearSpeechQueue()
        if (!_isOutputMuted.value) speechDeltas.addLast(text)
        assistantFinished = true
        startSpeechDrainIfNeeded()
    }

    // endregion

    // region Assistant events

    fun receiveAssistantEvent(event: VoiceAssistantEvent) {
        val owns = host?.ownsSession(event.sessionId) ?: false
        if (!isVoiceSessionActive || !isAwaitingVoiceAssistant || !owns) return
        if (_state.value == VoiceConversationState.IDLE || _state.value == VoiceConversationState.FAILED) return
        when (event) {
            is VoiceAssistantEvent.Started -> {
                awaitedAssistantResponseStarted = true
                cancelBackgroundHandBackWatchdog()
                activeAssistantEntryId = null
                receivedAssistantDelta = false
                assistantFinished = false
                clearSpeechQueue()
                cancelSpeechDrainAndStream()
                if (_state.value == VoiceConversationState.THINKING || _state.value == VoiceConversationState.MUTED) beginBargeInMonitoring()
            }
            is VoiceAssistantEvent.Delta -> {
                awaitedAssistantResponseStarted = true
                cancelBackgroundHandBackWatchdog()
                receivedAssistantDelta = true
                appendAssistantDelta(event.text)
                if (_isOutputMuted.value) return
                val spoken = spokenTextFilter.feed(event.text)
                if (spoken.isNotEmpty()) speechDeltas.addLast(spoken)
                startSpeechDrainIfNeeded()
            }
            is VoiceAssistantEvent.Completed -> {
                if (!awaitedAssistantResponseStarted) return
                completeAssistantTranscript(event.content)
                if (!_isOutputMuted.value) {
                    val spoken = (if (!receivedAssistantDelta) spokenTextFilter.feed(event.content.orEmpty()) else "") + spokenTextFilter.finish()
                    if (spoken.isNotEmpty()) speechDeltas.addLast(spoken)
                }
                assistantFinished = true
                isAwaitingVoiceAssistant = false
                awaitedAssistantResponseStarted = false
                steerSentThisTurn = false
                startSpeechDrainIfNeeded()
            }
            is VoiceAssistantEvent.Failed -> {
                if (!awaitedAssistantResponseStarted && isCancellationMessage(event.message)) return
                isAwaitingVoiceAssistant = false
                awaitedAssistantResponseStarted = false
                _isSteeringTurn.value = false
                fail(event.message)
                playback.stop()
                cancelSpeechDrainAndStream()
                endPlaybackCaptureSuspensionWithoutRelistening()
            }
            is VoiceAssistantEvent.Interrupted -> {
                if (!awaitedAssistantResponseStarted) return
                isAwaitingVoiceAssistant = false
                awaitedAssistantResponseStarted = false
                _isSteeringTurn.value = false
                playback.stop()
                cancelSpeechDrainAndStream()
                endPlaybackCaptureSuspensionWithoutRelistening()
                setState(VoiceConversationState.IDLE)
            }
        }
    }

    private fun isEndConversationCommand(transcript: String) = VoiceSpokenCommands.matches(transcript, preferences.resolvedSpokenEndConversationPhrases)

    private fun isStopCommand(transcript: String) = VoiceSpokenCommands.matches(transcript, preferences.resolvedSpokenStopPhrases)

    private fun appendEntry(isUser: Boolean, text: String) {
        _transcript.update { it + VoiceTranscriptEntry(++entryCounter, isUser, text) }
    }

    private fun appendAssistantDelta(text: String) {
        if (text.isEmpty()) return
        val id = activeAssistantEntryId
        if (id != null && _transcript.value.any { it.id == id }) {
            _transcript.update { list -> list.map { if (it.id == id) it.copy(text = it.text + text) else it } }
            return
        }
        val entry = VoiceTranscriptEntry(++entryCounter, false, text)
        activeAssistantEntryId = entry.id
        _transcript.update { it + entry }
    }

    private fun completeAssistantTranscript(content: String?) {
        if (!content.isNullOrEmpty()) {
            val id = activeAssistantEntryId
            if (id != null && _transcript.value.any { it.id == id }) {
                _transcript.update { list -> list.map { if (it.id == id) it.copy(text = content) else it } }
            } else {
                appendEntry(false, content)
            }
        }
        activeAssistantEntryId = null
    }

    // endregion

    // region Speech drain

    private fun clearSpeechQueue() {
        speechDeltas.clear()
        spokenTextFilter.reset()
    }

    private fun startSpeechDrainIfNeeded() {
        if (isDrainingSpeech || _isSteeringTurn.value) return
        isDrainingSpeech = true
        val operation = operationGeneration
        val revision = speechDrainRevision
        speechDrainJob = scope.launch { drainSpeechQueue(operation, revision) }
    }

    private suspend fun drainSpeechQueue(operation: Long, revision: Long) {
        try {
            if (!isSpeechDrainCurrent(operation, revision)) return
            val gateway = gateway ?: return
            if (speechStream == null && speechDeltas.isNotEmpty()) {
                playback.intent = PlaybackIntent.CONVERSATION
                playback.playbackRate = 1f
                val opened = gateway.openSpeechStream(
                    onStart = { rate ->
                        if (isSpeechDrainCurrent(operation, revision) && !_isOutputMuted.value) {
                            playback.start(rate)
                            setState(VoiceConversationState.SPEAKING)
                            applyPlaybackCapturePolicy()
                        }
                    },
                    onPcm16 = { data, rate ->
                        if (isSpeechDrainCurrent(operation, revision) && !_isOutputMuted.value) playback.enqueuePcm16(data, rate)
                    },
                    onEncodedAudio = { data ->
                        if (isSpeechDrainCurrent(operation, revision) && !_isOutputMuted.value) {
                            playback.playEncoded(data)
                            setState(VoiceConversationState.SPEAKING)
                            applyPlaybackCapturePolicy()
                        }
                    },
                )
                if (!isSpeechDrainCurrent(operation, revision)) {
                    opened.cancel()
                    return
                }
                speechStream = opened
            }
            while (speechDeltas.isNotEmpty() && !_isOutputMuted.value) {
                val stream = speechStream ?: break
                stream.append(speechDeltas.removeFirst())
                if (!isSpeechDrainCurrent(operation, revision)) return
            }
            val stream = speechStream
            if (assistantFinished && stream != null) {
                stream.finish()
                if (!isSpeechDrainCurrent(operation, revision)) return
                playback.finish()
                playback.drain()
                if (!isSpeechDrainCurrent(operation, revision)) return
                speechStream = null
            }
        } catch (e: Exception) {
            if (!isSpeechDrainCurrent(operation, revision)) return
            if (e is CancellationException) {
                speechStream = null
                if (assistantFinished) {
                    assistantFinished = false
                    if (_isPlaybackCaptureSuspended.value) playback.stop()
                    _isPlaybackCaptureSuspended.value = false
                    isDrainingSpeech = false
                    speechDrainJob = null
                    startListeningNow()
                }
                return
            }
            playback.stop()
            endPlaybackCaptureSuspensionWithoutRelistening()
            if (_state.value == VoiceConversationState.SPEAKING || _state.value == VoiceConversationState.THINKING) fail(e.message ?: L10n.tr("Hermes speech stream failed."))
            return
        } finally {
            if (isSpeechDrainCurrent(operation, revision)) {
                isDrainingSpeech = false
                speechDrainJob = null
            }
        }
        if (assistantFinished && speechDeltas.isEmpty() && speechStream == null) {
            assistantFinished = false
            if (!isSpeechDrainCurrent(operation, revision)) return
            if (preferences.continuousConversation || isBackgroundListening) {
                cachedRoutePolicy = null
                _isPlaybackCaptureSuspended.value = false
                startListeningNow()
            } else {
                settleOpenSessionAfterAssistantTurn()
            }
        } else if (speechDeltas.isNotEmpty() && isSpeechDrainCurrent(operation, revision) && !_isOutputMuted.value) {
            // More speech arrived while the last chunk was in flight.
            startSpeechDrainIfNeeded()
        }
    }

    private fun settleOpenSessionAfterAssistantTurn() {
        cachedRoutePolicy = null
        _isPlaybackCaptureSuspended.value = false
        if (!_isMicrophonePaused.value) capture.pause()
        speechDetector.reset()
        resetMicrophoneMeter()
        setState(VoiceConversationState.IDLE)
    }

    private fun cancelSpeechDrainAndStream() {
        speechDrainRevision++
        val job = speechDrainJob
        val stream = speechStream
        speechDrainJob = null
        speechStream = null
        isDrainingSpeech = false
        job?.cancel()
        stream?.cancel()
    }

    private fun isSpeechDrainCurrent(operation: Long, revision: Long) = isCurrent(operation) && revision == speechDrainRevision

    private fun isCancellationMessage(message: String): Boolean {
        val normalized = message.trim().lowercase()
        return normalized.contains("cancelled") || normalized.contains("canceled")
    }

    private fun isCurrent(generation: Long) = generation == operationGeneration && isVoiceSessionActive

    // endregion

    fun dispose() {
        stop()
        captureEventsJob?.cancel()
    }
}
