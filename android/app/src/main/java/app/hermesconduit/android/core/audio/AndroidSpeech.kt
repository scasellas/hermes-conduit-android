package app.hermesconduit.android.core.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.SpeechRecognitionLocale
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android speech recognition helpers (the iOS Apple-speech counterpart). */
object AndroidSpeech {
    /** The person's languages (not the app UI language) as BCP-47 tags. */
    fun preferredLanguageTag(): String {
        val list = LocaleList.getDefault()
        val preferred = (0 until list.size()).map { list[it].toLanguageTag() }
        // The recognizer's language list isn't known synchronously on Android: the person's first language wins.
        return SpeechRecognitionLocale.preferred(preferred, Locale.getDefault().toLanguageTag(), preferred)
    }

    fun isRecognitionAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Recorded speech can be handed to the recognizer (API 33+ audio source). */
    fun canTranscribeRecordings(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRecognitionAvailable(context)

    fun createRecognizer(context: Context): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    fun recognizerIntent(language: String, partials: Boolean, words: List<String> = emptyList()): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && words.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
            }
        }

    fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> L10n.tr("Dictation needs microphone access. You can allow it in Settings.")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> L10n.tr("Dictation isn't available right now.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> L10n.tr("The microphone is not available right now.")
        else -> L10n.tr("Dictation isn't available right now.")
    }

    /** rmsdB (about −2…10) → a linear level for the input meter. */
    fun levelFromRms(rmsdB: Float): Float {
        val fraction = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        return Math.pow(10.0, (fraction * 60.0 - 60.0) / 20.0).toFloat()
    }
}

/**
 * Transcribes a finished recording on the device by feeding its PCM to the
 * recognizer (`EXTRA_AUDIO_SOURCE`, Android 13+). Must be used on the main thread.
 */
class AndroidDeviceTranscriber(private val context: Context) : DeviceSpeechTranscriber {
    private var recognizer: SpeechRecognizer? = null

    override suspend fun transcribe(audio: CapturedAudio): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !AndroidSpeech.isRecognitionAvailable(context)) {
            throw VoiceAudioException(L10n.tr("On-device speech recognition is not available for your current language locale."))
        }
        cancel()
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(audio.pcm16) } }
        }.start()
        val recognizer = AndroidSpeech.createRecognizer(context)
        this.recognizer = recognizer
        val intent = AndroidSpeech.recognizerIntent(AndroidSpeech.preferredLanguageTag(), partials = false).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.setRecognitionListener(object : SimpleRecognitionListener() {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (continuation.isActive) continuation.resume(text.trim())
                    }

                    override fun onError(error: Int) {
                        if (!continuation.isActive) return
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) continuation.resume("")
                        else continuation.resumeWithException(VoiceAudioException(AndroidSpeech.errorMessage(error)))
                    }
                })
                continuation.invokeOnCancellation { runCatching { recognizer.cancel() } }
                recognizer.startListening(intent)
            }
        } finally {
            runCatching { read.close() }
            runCatching { recognizer.destroy() }
            if (this.recognizer === recognizer) this.recognizer = null
        }
    }

    override fun cancel() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }
}

open class SimpleRecognitionListener : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {}
    override fun onResults(results: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
