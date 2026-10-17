package app.hermesconduit.android.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * The watch's ears: the system speech recognizer, kept listening turn after
 * turn. When the watch has no recognizer service (or the microphone isn't
 * allowed) the call falls back to the system's voice input screen.
 */
class SpeechInput(private val context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** Nothing was heard (silence or no match). */
        fun onSilence()
        fun onFailure(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(listener: Listener) {
        cancel()
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return listener.onFailure(SpeechRecognizer.ERROR_CLIENT)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                if (text.isNullOrBlank()) listener.onSilence() else listener.onFinal(text)
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onSilence()
                    else -> listener.onFailure(error)
                }
            }
        })
        runCatching { r.startListening(intent()) }.onFailure { listener.onFailure(SpeechRecognizer.ERROR_CLIENT) }
    }

    /** The user is done talking: finish with what was heard. */
    fun finish() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    companion object {
        fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, L10n.tr("Talk to Hermes"))

        fun best(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The watch's voice: Android TextToSpeech, fed sentence by sentence as the
 * reply streams in. [onIdle] runs on the main thread once everything queued
 * has been spoken (or was stopped).
 */
class Speaker(context: Context, private val onStart: () -> Unit, private val onIdle: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val waiting = mutableListOf<String>()
    private var queued = 0
    private var counter = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts.language = Locale.getDefault() }
                val pending = waiting.toList()
                waiting.clear()
                pending.forEach(::speak)
            } else if (waiting.isNotEmpty()) {
                waiting.clear()
                onIdle()
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post(onStart)
            }
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
    }

    val speaking: Boolean get() = queued > 0 || waiting.isNotEmpty()

    /** Callbacks for utterances from before the last [stop] don't count. */
    private var generation = 0

    private fun finished(utteranceId: String?) {
        main.post {
            if (utteranceId != null && !utteranceId.startsWith("g$generation-")) return@post
            if (queued > 0) queued--
            if (queued == 0 && waiting.isEmpty()) onIdle()
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            waiting += text
            return
        }
        queued++
        val id = "g$generation-${counter++}"
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) finished(id)
    }

    /** Barge-in: drops everything queued. */
    fun stop() {
        generation++
        waiting.clear()
        queued = 0
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
