package app.hermesconduit.android.wake

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.AndroidSpeech
import app.hermesconduit.android.core.audio.SimpleRecognitionListener
import app.hermesconduit.android.model.CompiledWakePhrase
import app.hermesconduit.android.model.WakePhraseBinding
import app.hermesconduit.android.model.WakePhraseMatcher
import app.hermesconduit.android.model.WakeWordService
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class WakeWordUnavailableException(message: String) : Exception(message)

/** A wake listener: phrases to listen for, detections, and failures it couldn't recover from. */
abstract class WakeListener : WakeWordService {
    var bindings: List<WakePhraseBinding> = emptyList()
    /** Called on the main thread once per detection, after the microphone is released. */
    var onDetection: ((WakePhraseBinding) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    /** Whether this engine records with its own AudioRecord (so it can run in a microphone service). */
    abstract val recordsItself: Boolean

    protected val main = Handler(Looper.getMainLooper())

    protected fun hasMicrophone(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/**
 * The sherpa-onnx keyword spotter (iOS SherpaWakeWordService): the
 * downloaded pack's model and the compiled phrases' keyword file, fed 16 kHz
 * audio from its own AudioRecord on a reader thread. Nothing is recorded or
 * kept, and audio never leaves the phone.
 */
class SherpaWakeListener(private val context: Context, private val store: WakeModelStore) : WakeListener() {
    override val recordsItself = true
    @Volatile private var running = false
    private var thread: Thread? = null
    private var compiled: List<CompiledWakePhrase> = emptyList()

    override val isArmed: Boolean get() = running

    /** Uses these compiled phrases from the next arm (iOS `prepare`). */
    fun prepare(phrases: List<CompiledWakePhrase>) {
        if (phrases != compiled) {
            compiled = phrases
            if (running) { disarm(); arm() }
        }
    }

    @SuppressLint("MissingPermission")
    override fun arm() {
        if (running) return
        val dir = store.localDirectory() ?: throw WakeWordUnavailableException(L10n.tr("The reviewed wake model is not installed on this device."))
        if (compiled.isEmpty()) throw WakeWordUnavailableException(L10n.tr("Choose at least one valid wake phrase before listening."))
        if (!hasMicrophone(context)) throw WakeWordUnavailableException(L10n.tr("Wake phrases need microphone and speech recognition access."))
        val keywords = File(dir, "conduit-keywords.txt").apply { writeText(compiled.joinToString("\n") { it.keywordLine } + "\n") }
        val aliases = compiled.associateBy { it.alias }
        val bindingsByAlias = aliases.mapValues { (_, phrase) -> WakePhraseBinding(phrase.profileKey, phrase.phrase, phrase.startsFreshConversation) }
        val d = store.descriptor
        val spotter = try {
            KeywordSpotter(
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = OnlineModelConfig().apply {
                        transducer = OnlineTransducerModelConfig().apply {
                            encoder = File(dir, d.encoder).path
                            decoder = File(dir, d.decoder).path
                            joiner = File(dir, d.joiner).path
                        }
                        tokens = File(dir, d.tokens).path
                        numThreads = 1
                        provider = "cpu"
                        modelType = "zipformer2"
                    },
                    maxActivePaths = 4,
                    keywordsFile = keywords.path,
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 1,
                ),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "keyword spotter not created", e)
            throw WakeWordUnavailableException(L10n.tr("Wake-word runtime is unavailable."))
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE / 5 * 2))
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            spotter.release()
            throw WakeWordUnavailableException(L10n.tr("Wake listening couldn't use the microphone. Close other apps using it, then try again."))
        }
        running = true
        thread = Thread({
            val stream = spotter.createStream("")
            val pcm = ShortArray(SAMPLE_RATE / 10)
            val samples = FloatArray(pcm.size)
            var failure: String? = null
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n < 0) {
                        failure = L10n.tr("The microphone is not available right now.")
                        break
                    }
                    for (i in 0 until n) samples[i] = pcm[i] / 32768f
                    stream.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), SAMPLE_RATE)
                    while (spotter.isReady(stream)) spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotEmpty()) {
                        spotter.reset(stream)
                        val binding = bindingsByAlias[keyword] ?: continue
                        running = false
                        runCatching { record.stop() }
                        record.release()
                        main.post { onDetection?.invoke(binding) }
                        return@Thread
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "wake listening stopped", e)
                failure = L10n.tr("Wake listening couldn't use the microphone. Close other apps using it, then try again.")
            } finally {
                runCatching { record.stop() }
                record.release()
                stream.release()
                spotter.release()
            }
            val wasRunning = running
            running = false
            if (failure != null && wasRunning) main.post { onFailure?.invoke(failure) }
        }, "conduit-wake").apply { start() }
    }

    override fun disarm() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "VoiceWake"
    }
}

/**
 * Wake phrases on Android's speech recognizer (iOS
 * AppleSpeechWakeWordService): continuous recognition with partial results,
 * restarted each cycle, each transcript run through [WakePhraseMatcher].
 * On-device when the phone offers it. Foreground only: the recognizer runs
 * in another app's service, which can't use the microphone in the background.
 */
class SpeechRecognizerWakeListener(private val context: Context) : WakeListener() {
    override val recordsItself = false
    private var recognizer: SpeechRecognizer? = null
    private var armed = false
    private var consecutiveFailures = 0
    private var generation = 0
    private val restart = Runnable { startCycle() }

    override val isArmed: Boolean get() = armed

    override fun arm() {
        if (armed) return
        if (bindings.none { WakePhraseMatcher.isUsable(it.phrase) }) throw WakeWordUnavailableException(L10n.tr("Choose at least one valid wake phrase before listening."))
        if (!hasMicrophone(context)) throw WakeWordUnavailableException(L10n.tr("Wake phrases need microphone and speech recognition access."))
        if (!AndroidSpeech.isRecognitionAvailable(context)) throw WakeWordUnavailableException(L10n.tr("On-device speech recognition is not available for your current language locale."))
        armed = true
        consecutiveFailures = 0
        startCycle()
    }

    private fun startCycle() {
        if (!armed) return
        main.removeCallbacks(restart)
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        val generation = ++generation
        val created = AndroidSpeech.createRecognizer(context)
        recognizer = created
        created.setRecognitionListener(object : SimpleRecognitionListener() {
            override fun onPartialResults(partialResults: Bundle?) = receive(partialResults, generation, final = false)
            override fun onResults(results: Bundle?) = receive(results, generation, final = true)
            override fun onError(error: Int) {
                if (generation != this@SpeechRecognizerWakeListener.generation || !armed) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    main.post(restart)
                    return
                }
                consecutiveFailures++
                if (consecutiveFailures >= MAXIMUM_CONSECUTIVE_FAILURES) {
                    disarm()
                    onFailure?.invoke(L10n.tr("Speech recognition is temporarily unavailable."))
                    return
                }
                main.postDelayed(restart, 1_000)
            }
        })
        // The app's language, not the dictation language: phrases are agent names.
        val intent = AndroidSpeech.recognizerIntent(L10n.language, partials = true, words = bindings.map { it.phrase }).apply {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        runCatching { created.startListening(intent) }.onFailure { main.postDelayed(restart, 1_000) }
        main.postDelayed(restart, CYCLE_MS)
    }

    private fun receive(bundle: Bundle?, generation: Int, final: Boolean) {
        if (generation != this.generation || !armed) return
        val transcripts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (transcripts.any { it.isNotBlank() }) consecutiveFailures = 0
        val binding = transcripts.firstNotNullOfOrNull { WakePhraseMatcher.match(it, bindings) }
        if (binding != null) {
            disarm()
            onDetection?.invoke(binding)
            return
        }
        if (final) main.post(restart)
    }

    override fun disarm() {
        armed = false
        generation++
        main.removeCallbacks(restart)
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    companion object {
        const val CYCLE_MS = 30_000L
        const val MAXIMUM_CONSECUTIVE_FAILURES = 5
    }
}
