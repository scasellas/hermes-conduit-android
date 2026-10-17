package app.hermesconduit.android.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * The watch's microphone and speaker for a live call (iOS `WatchAudio`): the
 * microphone as 16 kHz PCM16 mono (what Gemini and Grok take), and a
 * streaming player for the model's speech at its own rate. Callbacks run on
 * the main thread. Half duplex is the call's job: it drops what the
 * microphone hears while this plays.
 */
class WatchAudio(private val context: Context) {
    /** 20 ms of microphone audio and when it was captured (uptime ms). */
    var onCapture: ((ShortArray, Long) -> Unit)? = null
    /** The first audio of a stretch became audible (uptime ms). */
    var onPlaybackStarted: ((Long) -> Unit)? = null
    /** Everything queued has played. */
    var onDrained: (() -> Unit)? = null
    /** Another app (a call, an alarm) took the audio, or gave it back. */
    var onInterruption: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    private val writes = LinkedBlockingQueue<ShortArray>()
    private var writer: Thread? = null
    @Volatile private var framesWritten = 0L
    @Volatile private var generation = 0
    private var playing = false
    private var startedReported = false
    private var focus: android.media.AudioFocusRequest? = null

    /** Model speech is queued or still audible. */
    val isPlaying: Boolean get() = playing

    val isRunning: Boolean get() = recording

    /** Starts the microphone and a player at [playbackRate]. Throws when the microphone can't start. */
    @SuppressLint("MissingPermission")
    fun start(playbackRate: Int) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(L10n.tr("Conduit needs the microphone to talk to Hermes."))
        }
        stop()
        requestFocus()
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CAPTURE_RATE / 5 * 2))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(L10n.tr("The microphone didn't start."))
        }
        record.startRecording()
        recorder = record
        recording = true
        thread(name = "watch-mic", isDaemon = true) {
            val frame = ShortArray(CAPTURE_RATE / 50)
            while (recording) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                val chunk = frame.copyOf(read)
                val at = SystemClock.uptimeMillis()
                main.post { if (recording) onCapture?.invoke(chunk, at) }
            }
        }
        ensureTrack(playbackRate)
    }

    private fun ensureTrack(rate: Int) {
        if (track != null && trackRate == rate) return
        track?.let { runCatching { it.stop(); it.release() } }
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, rate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trackRate = rate
        framesWritten = 0
        track?.play()
        if (writer == null) {
            writer = thread(name = "watch-speaker", isDaemon = true) {
                while (true) {
                    val samples = try { writes.take() } catch (_: InterruptedException) { break }
                    if (samples.isEmpty()) break
                    val out = track ?: continue
                    val mine = generation
                    var offset = 0
                    while (offset < samples.size && mine == generation) {
                        val n = out.write(samples, offset, samples.size - offset)
                        if (n <= 0) break
                        offset += n
                    }
                    if (mine == generation) framesWritten += offset
                }
            }
        }
    }

    /** Queues model speech at [sampleRate]. */
    fun enqueue(samples: ShortArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        if (trackRate != sampleRate || track == null) ensureTrack(sampleRate)
        if (!playing) {
            playing = true
            startedReported = false
        }
        writes.offer(samples)
    }

    /** Barge-in: drops everything queued and stops at once. */
    fun stopPlayback() {
        generation++
        writes.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        framesWritten = 0
        playing = false
        startedReported = false
    }

    /** From the call's ticker: reports the first audible audio and the end of what was queued. */
    fun checkPlayback() {
        if (!playing) return
        val out = track ?: return
        val played = runCatching { out.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
        if (!startedReported && played > 0) {
            startedReported = true
            onPlaybackStarted?.invoke(SystemClock.uptimeMillis())
        }
        if (writes.isEmpty() && played >= framesWritten && framesWritten > 0) {
            playing = false
            // The head counts from the track's start: a fresh stretch counts from here.
            generation++
            runCatching { out.pause(); out.flush(); out.play() }
            framesWritten = 0
            onDrained?.invoke()
        }
    }

    fun stop() {
        recording = false
        recorder?.let { runCatching { it.stop(); it.release() } }
        recorder = null
        generation++
        writes.clear()
        writer?.let { writes.offer(ShortArray(0)) }
        writer = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        trackRate = 0
        playing = false
        abandonFocus()
    }

    private fun requestFocus() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onInterruption?.invoke(true)
                    AudioManager.AUDIOFOCUS_GAIN -> onInterruption?.invoke(false)
                }
            }, main)
            .build()
        focus = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    companion object {
        const val CAPTURE_RATE = 16_000

        /** Two falling notes: the call lost its session and waits. At [rate]. */
        fun lostChime(rate: Int): ShortArray {
            val noteLength = (rate * 0.18).toInt()
            val fade = (rate * 0.01).toInt()
            val out = ArrayList<Short>()
            for (frequency in listOf(880.0, 660.0)) {
                for (index in 0 until noteLength) {
                    val edge = minOf(index, noteLength - 1 - index)
                    val envelope = if (edge < fade) edge.toDouble() / fade else 1.0
                    val value = kotlin.math.sin(2 * Math.PI * frequency * index / rate) * 0.3 * envelope
                    out += (value * Short.MAX_VALUE).toInt().toShort()
                }
                repeat((rate * 0.06).toInt()) { out += 0 }
            }
            return out.toShortArray()
        }
    }
}
