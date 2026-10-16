package app.hermesconduit.android.ui.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.SpeechRecognizer
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.AndroidSpeech
import app.hermesconduit.android.core.audio.AudioRecordCapture
import app.hermesconduit.android.core.audio.CaptureEvent
import app.hermesconduit.android.core.audio.CapturedAudio
import app.hermesconduit.android.core.audio.LiveTranscription
import app.hermesconduit.android.core.audio.Pcm
import app.hermesconduit.android.core.audio.SimpleRecognitionListener
import app.hermesconduit.android.core.audio.TranscriptionTimeouts
import app.hermesconduit.android.core.audio.VoiceGateway
import app.hermesconduit.android.model.DictationEngine
import app.hermesconduit.android.model.DictationTranscript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DictationUpdate {
    /** The whole transcript so far; `isFinal` ends the dictation. */
    data class Text(val text: String, val isFinal: Boolean) : DictationUpdate
    data class Failed(val message: String) : DictationUpdate
}

/** One dictation's recognizer: it owns its microphone while it listens. */
interface DictationRecognizer {
    val settleTimeoutMs: Long
    /** Its words reach the draft only once it settles (an upload), so a new dictation must not cut that short. */
    val holdsWordsWhileSettling: Boolean
    val level: StateFlow<Float>
    fun begin(onUpdate: (DictationUpdate) -> Unit)
    fun endAudio()
    fun cancel()
}

/** Android's speech recognizer with its own microphone; restarted across pauses so a long dictation keeps going. */
class DeviceDictationRecognizer(private val context: Context, private val words: List<String>) : DictationRecognizer {
    override val settleTimeoutMs = 1_500L
    override val holdsWordsWhileSettling = false
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level
    private var recognizer: SpeechRecognizer? = null
    private var onUpdate: ((DictationUpdate) -> Unit)? = null
    private val transcript = DictationTranscript()
    private var capturing = false

    override fun begin(onUpdate: (DictationUpdate) -> Unit) {
        if (!AndroidSpeech.isRecognitionAvailable(context)) throw IllegalStateException(L10n.tr("Dictation isn't available right now."))
        this.onUpdate = onUpdate
        capturing = true
        listen()
    }

    private fun listen() {
        recognizer?.destroy()
        val created = AndroidSpeech.createRecognizer(context)
        recognizer = created
        created.setRecognitionListener(object : SimpleRecognitionListener() {
            override fun onRmsChanged(rmsdB: Float) { _level.value = AndroidSpeech.levelFromRms(rmsdB) }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) {
                    transcript.receive(text, endsUtterance = false)
                    onUpdate?.invoke(DictationUpdate.Text(transcript.text, false))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) transcript.receive(text, endsUtterance = true)
                if (capturing) {
                    // A pause ended the recognizer's utterance: keep dictating.
                    onUpdate?.invoke(DictationUpdate.Text(transcript.text, false))
                    listen()
                } else {
                    onUpdate?.invoke(DictationUpdate.Text(transcript.text, true))
                }
            }

            override fun onError(error: Int) {
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (capturing && quiet) {
                    listen()
                    return
                }
                if (!capturing || quiet || transcript.text.isNotEmpty()) {
                    onUpdate?.invoke(DictationUpdate.Text(transcript.text, true))
                } else {
                    onUpdate?.invoke(DictationUpdate.Failed(AndroidSpeech.errorMessage(error)))
                }
            }
        })
        created.startListening(AndroidSpeech.recognizerIntent(AndroidSpeech.preferredLanguageTag(), partials = true, words = words))
    }

    override fun endAudio() {
        capturing = false
        _level.value = 0f
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        capturing = false
        onUpdate = null
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }
}

/**
 * Hermes' own speech to text: live words over transcribe-stream when the
 * host streams them, the whole recording uploaded when you stop otherwise.
 * [speechServer] sends the audio to the user's own speech server instead.
 */
class HermesDictationRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gateway: VoiceGateway,
    private val speechServer: Boolean,
) : DictationRecognizer {
    override val settleTimeoutMs = TranscriptionTimeouts.MAXIMUM_MS + 10_000
    override val holdsWordsWhileSettling = true
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level
    private val capture = AudioRecordCapture(context, scope, MediaRecorder.AudioSource.MIC)
    private var onUpdate: ((DictationUpdate) -> Unit)? = null
    private var live: LiveTranscription? = null
    private var opening: Job? = null
    private var finishing: Job? = null
    private var levels: Job? = null
    private var sentBytes = 0
    private var partial = ""
    private var serverFailure: String? = null

    override fun begin(onUpdate: (DictationUpdate) -> Unit) {
        this.onUpdate = onUpdate
        capture.startListening(includePreRoll = false)
        levels = scope.launch {
            capture.events.collect { if (it is CaptureEvent.Level) { _level.value = it.level; pump() } }
        }
        val identity = gateway.identity
        if (speechServer || identity !in liveUnavailable) {
            opening = scope.launch {
                val session = if (speechServer) {
                    gateway.openSpeechServerTranscription(Pcm.CAPTURE_SAMPLE_RATE, ::receivePartial) { serverFailure = it }
                } else {
                    gateway.openLiveTranscription(Pcm.CAPTURE_SAMPLE_RATE, ::receivePartial) { liveUnavailable += identity }
                }
                if (this@HermesDictationRecognizer.onUpdate == null) {
                    session?.cancel()
                    return@launch
                }
                live = session
                pump()
            }
        }
    }

    private fun pump() {
        val session = live ?: return
        val recorded = capture.recordedPcm16
        if (recorded.size <= sentBytes) return
        session.push(recorded.copyOfRange(sentBytes, recorded.size))
        sentBytes = recorded.size
    }

    private fun receivePartial(text: String) {
        if (text.isEmpty()) return
        partial = text
        onUpdate?.invoke(DictationUpdate.Text(text, false))
    }

    override fun endAudio() {
        finishing = scope.launch {
            opening?.join()
            pump()
            levels?.cancel()
            _level.value = 0f
            val audio = runCatching { capture.finishUtterance() }.getOrNull()
            capture.stop()
            val session = live
            if (session != null) {
                val text = session.finish()
                if (!text.isNullOrEmpty()) {
                    onUpdate?.invoke(DictationUpdate.Text(text, true))
                    return@launch
                }
                if (speechServer) {
                    val failure = serverFailure
                    onUpdate?.invoke(if (failure != null && partial.isEmpty()) DictationUpdate.Failed(failure) else DictationUpdate.Text(partial, true))
                    return@launch
                }
            } else if (speechServer) {
                onUpdate?.invoke(DictationUpdate.Failed(serverFailure ?: L10n.tr("Could not reach your speech server through Hermes.")))
                return@launch
            }
            upload(audio)
        }
    }

    private suspend fun upload(audio: CapturedAudio?) {
        if (audio == null || audio.pcm16.isEmpty()) {
            onUpdate?.invoke(DictationUpdate.Text(partial, true))
            return
        }
        try {
            val text = gateway.transcribe(audio)
            onUpdate?.invoke(DictationUpdate.Text(text.ifEmpty { partial }, true))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onUpdate?.invoke(DictationUpdate.Failed(L10n.tr("Hermes couldn't transcribe that: %s", e.message.orEmpty())))
        }
    }

    override fun cancel() {
        onUpdate = null
        opening?.cancel()
        finishing?.cancel()
        levels?.cancel()
        live?.cancel()
        live = null
        capture.stop()
    }

    companion object {
        /** Hosts and profiles that answered they have no live speech to text, until relaunch. */
        private val liveUnavailable = mutableSetOf<String>()
    }
}

/**
 * The composer's dictate button (issues #290, #335): a speech recognizer
 * turns what is said into text in the draft; nothing is sent.
 */
class ComposerDictationService(private val context: Context, private val scope: CoroutineScope) {
    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    var isDictating = false
        private set

    var onTranscript: ((String) -> Unit)? = null
    var onFinish: ((Boolean) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null

    private var recognizer: DictationRecognizer? = null
    private var finishJob: Job? = null
    private var levelJob: Job? = null
    private var generation = 0L
    private var producedText = false

    /** Starts listening with [engine]; throws a message for the user when it can't. */
    fun start(engine: DictationEngine, words: List<String>, gateway: () -> VoiceGateway?) {
        if (_isStarting.value || _isCapturing.value || _isTranscribing.value) return
        if (isDictating) finish()
        _isStarting.value = true
        try {
            val created: DictationRecognizer = when (engine) {
                DictationEngine.DEVICE -> DeviceDictationRecognizer(context, words)
                DictationEngine.HERMES -> HermesDictationRecognizer(
                    context, scope, gateway() ?: throw IllegalStateException(L10n.tr("Dictation with Hermes needs a connection to Hermes.")), speechServer = false,
                )
                DictationEngine.SPEECH_SERVER -> HermesDictationRecognizer(
                    context, scope, gateway() ?: throw IllegalStateException(L10n.tr("Dictation with Hermes needs a connection to Hermes.")), speechServer = true,
                )
            }
            generation++
            val current = generation
            producedText = false
            try {
                created.begin { update -> receive(update, current) }
            } catch (e: Exception) {
                created.cancel()
                throw IllegalStateException(e.message ?: L10n.tr("The microphone is not available right now."))
            }
            recognizer = created
            levelJob = scope.launch { created.level.collect { _level.value = it } }
            _isCapturing.value = true
            isDictating = true
        } catch (e: Exception) {
            clearCallbacks()
            throw e
        } finally {
            _isStarting.value = false
        }
    }

    /** Tapped again: stop listening and let the recognizer settle its last words. */
    fun stop() {
        if (!_isCapturing.value) return
        _isCapturing.value = false
        val active = recognizer
        active?.endAudio()
        _isTranscribing.value = (active?.holdsWordsWhileSettling ?: false) && !producedText
        val current = generation
        val timeout = active?.settleTimeoutMs ?: 1_500
        finishJob?.cancel()
        finishJob = scope.launch {
            delay(timeout)
            if (generation != current) return@launch
            if (_isTranscribing.value) onFailure?.invoke(L10n.tr("Hermes didn't answer in time. Check that your Hermes server is running, then try again."))
            finish()
        }
    }

    /** The person typed: a dictation ends there, keeping its words, unless they are still on their way. */
    fun yieldToTyping() {
        if (_isTranscribing.value) return
        if (isDictating || _isStarting.value) cancel()
    }

    fun cancel() {
        _isStarting.value = false
        if (isDictating) finish() else clearCallbacks()
    }

    private fun receive(update: DictationUpdate, current: Long) {
        if (!isDictating || current != generation) return
        when (update) {
            is DictationUpdate.Text -> {
                if (update.text.isNotEmpty()) {
                    producedText = true
                    onTranscript?.invoke(update.text)
                }
                if (update.isFinal) finish()
            }
            is DictationUpdate.Failed -> {
                onFailure?.invoke(update.message)
                finish()
            }
        }
    }

    private fun finish() {
        generation++
        finishJob?.cancel()
        finishJob = null
        levelJob?.cancel()
        levelJob = null
        recognizer?.cancel()
        recognizer = null
        _level.value = 0f
        _isCapturing.value = false
        _isTranscribing.value = false
        isDictating = false
        val done = onFinish
        clearCallbacks()
        done?.invoke(producedText)
    }

    private fun clearCallbacks() {
        onTranscript = null
        onFinish = null
        onFailure = null
    }
}
