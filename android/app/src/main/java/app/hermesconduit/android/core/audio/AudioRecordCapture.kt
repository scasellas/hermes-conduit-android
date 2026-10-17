package app.hermesconduit.android.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import app.hermesconduit.android.core.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * A microphone other than the phone's: the car's, through Android Auto
 * (`CarAudioRecord`). 16 kHz mono PCM16, like the phone capture; `read`
 * blocks and returns -1 once the microphone is gone.
 */
interface ExternalMicrophone {
    fun start()
    fun read(buffer: ByteArray, offset: Int, size: Int): Int
    fun stop()
}

/**
 * 16 kHz mono PCM16 microphone capture on `AudioRecord` with the
 * VOICE_COMMUNICATION source (the platform's echo canceller and noise
 * suppression), a 5-second pre-roll, and per-frame level events. A reader
 * thread does the I/O; events are delivered on [scope].
 */
class AudioRecordCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    /** VOICE_COMMUNICATION for conversations; MIC for plain dictation. */
    private val source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
) : AudioCaptureService {
    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<CaptureEvent> = _events

    @Volatile override var captureGeneration: Long = 0
        private set

    private val lock = Object()
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    @Volatile private var activelyRecording = false
    @Volatile private var heldForPlayback = false
    @Volatile private var paused = false
    private val preRoll = Pcm.Ring(Pcm.CAPTURE_SAMPLE_RATE * 2 * PRE_ROLL_SECONDS)
    private val captured = Pcm.Growing()

    /**
     * Records from this microphone instead of the phone's while set (the car's,
     * while Android Auto runs the conversation). Takes effect on the next start.
     */
    @Volatile var externalMicrophone: ExternalMicrophone? = null
    private var openExternal: ExternalMicrophone? = null

    /** Raw frames as they arrive (for engines that stream them, e.g. live voice). */
    @Volatile var onPcm16Chunk: ((ByteArray) -> Unit)? = null

    override val isHeldForPlayback: Boolean get() = heldForPlayback

    override var cancelsEcho: Boolean = false
        private set

    override fun hasPermission() = externalMicrophone != null || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override val recordedPcm16: ByteArray get() = synchronized(lock) { if (activelyRecording) captured.snapshot() else ByteArray(0) }

    override fun startListening(includePreRoll: Boolean) {
        startIfNeeded()
        synchronized(lock) {
            captured.set(if (includePreRoll) preRoll.snapshot() else ByteArray(0))
            activelyRecording = true
        }
        paused = false
        heldForPlayback = false
    }

    override fun beginBargeInMonitoring() {
        if (paused) return
        startIfNeeded()
        activelyRecording = false
        heldForPlayback = false
    }

    override fun holdForPlayback() {
        if (paused) return
        if (!running) {
            pause()
            return
        }
        activelyRecording = false
        heldForPlayback = true
    }

    override fun pause() {
        if (paused) return
        paused = true
        heldForPlayback = false
        captureGeneration++
        teardown()
    }

    override fun resume() {
        startIfNeeded()
        paused = false
        heldForPlayback = false
    }

    override fun finishUtterance(): CapturedAudio {
        val pcm = synchronized(lock) {
            activelyRecording = false
            captured.snapshot().also { captured.clear() }
        }
        paused = false
        if (pcm.isEmpty()) throw VoiceAudioException(L10n.tr("No speech was captured."))
        return CapturedAudio(pcm, Pcm.CAPTURE_SAMPLE_RATE)
    }

    override fun stop() {
        activelyRecording = false
        paused = false
        heldForPlayback = false
        captureGeneration++
        teardown()
    }

    @SuppressLint("MissingPermission")
    private fun startIfNeeded() {
        if (running) return
        externalMicrophone?.let { return startExternal(it) }
        if (!hasPermission()) throw VoiceAudioException(L10n.tr(MICROPHONE_DENIED))
        val minBuffer = AudioRecord.getMinBufferSize(Pcm.CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = try {
            AudioRecord(source, Pcm.CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_BYTES * 8))
        } catch (e: Exception) {
            throw VoiceAudioException(L10n.tr("The microphone is not available right now."))
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw VoiceAudioException(L10n.tr("The microphone is not available right now."))
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        cancelsEcho = echoCanceler?.enabled == true || source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
        try {
            audio.startRecording()
        } catch (e: Exception) {
            audio.release()
            throw VoiceAudioException(L10n.tr("The microphone is not available right now."))
        }
        record = audio
        running = true
        captureGeneration++
        val generation = captureGeneration
        reader = Thread({ readLoop(generation) { buffer -> audio.read(buffer, 0, buffer.size) } }, "conduit-capture").apply { start() }
    }

    private fun startExternal(microphone: ExternalMicrophone) {
        try {
            microphone.start()
        } catch (e: Exception) {
            throw VoiceAudioException(L10n.tr("The microphone is not available right now."))
        }
        openExternal = microphone
        // Whatever echo handling the car does, it isn't the platform canceller.
        cancelsEcho = false
        running = true
        captureGeneration++
        val generation = captureGeneration
        reader = Thread({ readLoop(generation) { buffer -> microphone.read(buffer, 0, buffer.size) } }, "conduit-capture").apply { start() }
    }

    private fun readLoop(generation: Long, readInto: (ByteArray) -> Int) {
        val buffer = ByteArray(FRAME_BYTES)
        while (running && captureGeneration == generation) {
            val read = try { readInto(buffer) } catch (_: Exception) { -1 }
            if (read < 0) {
                if (running && captureGeneration == generation) scope.launch { _events.emit(CaptureEvent.Interrupted(generation)) }
                break
            }
            if (read == 0) continue
            if (heldForPlayback || paused) continue
            val chunk = buffer.copyOf(read)
            synchronized(lock) {
                preRoll.append(chunk)
                if (activelyRecording) captured.append(chunk)
            }
            onPcm16Chunk?.invoke(chunk)
            val level = Pcm.peak(chunk)
            _events.tryEmit(CaptureEvent.Level(level, System.currentTimeMillis(), generation))
        }
    }

    private fun teardown() {
        running = false
        val audio = record
        record = null
        reader = null
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        openExternal?.let { microphone -> runCatching { microphone.stop() } }
        openExternal = null
        if (audio != null) {
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
    }

    fun routeChanged() {
        _events.tryEmit(CaptureEvent.RouteChanged)
    }

    companion object {
        const val PRE_ROLL_SECONDS = 5
        const val MICROPHONE_DENIED = "Conduit needs the microphone for voice. Allow it in Settings > Apps > Conduit > Permissions."
        /** 20 ms at 16 kHz mono PCM16. */
        const val FRAME_BYTES = 640
    }
}
