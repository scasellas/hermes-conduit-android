package app.hermesconduit.android.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streamed PCM16 speech on `AudioTrack` (pitch-preserving speed through
 * `PlaybackParams`) and whole clips on `MediaPlayer`. Conversation playback
 * uses the voice-communication usage so the platform echo canceller hears it;
 * standalone playback (Read Aloud, provider tests) is media that ducks others.
 */
class AudioTrackPlayback(private val context: Context) : SpeechPlaybackService {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0
    @Volatile private var framesWritten = 0L
    @Volatile private var finished = false
    @Volatile private var writer: Thread? = null
    @Volatile private var generation = 0L
    private var player: MediaPlayer? = null
    @Volatile private var clipPlaying = false
    private var focusRequest: AudioFocusRequest? = null
    @Volatile override var isPaused: Boolean = false
        private set

    override var intent: PlaybackIntent = PlaybackIntent.STANDALONE
    override var playbackRate: Float = 1f

    /** Audio focus changes during standalone playback (another app, a call). */
    var onFocusChange: ((Int) -> Unit)? = null

    override val isPlaying: Boolean
        get() {
            if (clipPlaying) return true
            val t = track ?: return false
            if (isPaused) return false
            return queue.isNotEmpty() || runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten) < framesWritten
        }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (intent == PlaybackIntent.CONVERSATION) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        if (intent != PlaybackIntent.STANDALONE || focusRequest != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes())
                .setOnAudioFocusChangeListener { change -> onFocusChange?.invoke(change) }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(request)
    }

    override fun start(sampleRate: Int) {
        requestFocus()
        if (track != null && trackRate == sampleRate) return
        releaseTrack()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, sampleRate / 2))
            .build()
        if (playbackRate != 1f) {
            runCatching { audio.playbackParams = PlaybackParams().setSpeed(clampedRate(playbackRate)).setPitch(1f) }
        }
        audio.play()
        if (isPaused) audio.pause()
        track = audio
        trackRate = sampleRate
        framesWritten = 0
        finished = false
        val current = ++generation
        writer = Thread({ writeLoop(audio, current) }, "conduit-playback").apply { start() }
    }

    private fun writeLoop(audio: AudioTrack, current: Long) {
        while (generation == current) {
            val chunk = try { queue.take() } catch (_: InterruptedException) { return }
            if (generation != current) return
            if (chunk.isEmpty()) continue
            val written = runCatching { audio.write(chunk, 0, chunk.size) }.getOrDefault(-1)
            if (written < 0) return
            framesWritten += written / 2
        }
    }

    override fun enqueuePcm16(data: ByteArray, sampleRate: Int): Int {
        if (track == null || trackRate != sampleRate) start(sampleRate)
        val usable = data.size - data.size % 2
        if (usable <= 0) return 0
        queue.put(if (usable == data.size) data else data.copyOf(usable))
        return usable
    }

    override fun playEncoded(data: ByteArray) {
        if (data.isEmpty()) return
        requestFocus()
        val file = File.createTempFile("speech", ".audio", context.cacheDir)
        file.writeBytes(data)
        val media = MediaPlayer()
        media.setAudioAttributes(attributes())
        media.setDataSource(file.absolutePath)
        media.setOnCompletionListener {
            clipPlaying = false
            file.delete()
        }
        media.setOnErrorListener { _, _, _ ->
            clipPlaying = false
            file.delete()
            true
        }
        media.prepare()
        if (playbackRate != 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { media.playbackParams = media.playbackParams.setSpeed(clampedRate(playbackRate)) }
        }
        player?.release()
        player = media
        clipPlaying = true
        if (!isPaused) media.start()
    }

    override fun finish() {
        finished = true
    }

    override suspend fun drain() {
        val current = generation
        while (true) {
            if (generation != current && track == null && !clipPlaying) return
            val t = track
            val streamDone = t == null || (queue.isEmpty() && (runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten) >= framesWritten))
            if (streamDone && !clipPlaying) return
            if (t == null && player == null) return
            delay(40)
        }
    }

    override fun stop() {
        generation++
        queue.clear()
        releaseTrack()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        clipPlaying = false
        isPaused = false
        finished = false
        abandonFocus()
    }

    override fun pause(): Boolean {
        if (!isPlaying) return false
        isPaused = true
        runCatching { track?.pause() }
        runCatching { player?.pause() }
        return true
    }

    override fun resume() {
        if (!isPaused) return
        isPaused = false
        runCatching { track?.play() }
        runCatching { if (clipPlaying) player?.start() }
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        trackRate = 0
        writer?.interrupt()
        writer = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        t.release()
    }

    companion object {
        fun clampedRate(rate: Float) = rate.coerceIn(0.5f, 2.0f)
    }
}
