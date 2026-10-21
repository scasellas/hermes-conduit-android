package app.hermesconduit.android.ui.voice.live

import android.content.Context
import app.hermesconduit.android.core.audio.AudioRecordCapture
import app.hermesconduit.android.core.audio.AudioTrackPlayback
import app.hermesconduit.android.core.audio.CaptureEvent
import app.hermesconduit.android.core.audio.ExternalMicrophone
import app.hermesconduit.android.core.audio.PlaybackIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A live call's audio on the shared pipeline: the microphone on
 * `AudioRecord` with the VOICE_COMMUNICATION source, and the model's speech
 * on a voice-communication `AudioTrack`, so the platform echo canceller
 * pairs them (the iOS echo-cancelling engine's job). Whether the speaker's
 * echo counts as cancelled (the mic stays open while the model talks on the
 * loudspeaker) is the user's "talk over it on the speaker" choice.
 */
class LiveVoiceAudio(
    context: Context,
    private val scope: CoroutineScope,
    /** "Talk over it on the speaker" for this profile, read for each call. */
    private val speakerBargeIn: () -> Boolean,
) {
    private val capture = AudioRecordCapture(context, scope)
    private val playback = AudioTrackPlayback(context).apply { intent = PlaybackIntent.CONVERSATION }
    private var eventsJob: Job? = null

    /** The car's microphone while Android Auto is connected; null records from the phone's. */
    var externalMicrophone: ExternalMicrophone?
        get() = capture.externalMicrophone
        set(value) {
            capture.externalMicrophone = value
        }

    val input: LiveAudioInput = object : LiveAudioInput {
        override var onChunk: ((ByteArray) -> Unit)? = null
        override var onInterrupted: (() -> Unit)? = null
        override val cancelsEcho: Boolean get() = speakerBargeIn() && capture.cancelsEcho

        override fun hasPermission() = capture.hasPermission()

        override fun start() {
            // Frames arrive on the capture thread: hop to the call's dispatcher.
            capture.onPcm16Chunk = { chunk -> scope.launch { onChunk?.invoke(chunk) } }
            if (eventsJob == null) {
                eventsJob = scope.launch {
                    capture.events.collect { event ->
                        if (event is CaptureEvent.Interrupted && event.generation == capture.captureGeneration) onInterrupted?.invoke()
                    }
                }
            }
            // Monitoring runs the microphone without keeping an utterance: every chunk is streamed.
            capture.beginBargeInMonitoring()
        }

        override fun stop() {
            capture.onPcm16Chunk = null
            capture.stop()
        }
    }

    val output: LiveAudioOutput = object : LiveAudioOutput {
        private var muted = false
        override val isPlaying: Boolean get() = playback.isPlaying

        override fun play(pcm: ByteArray, sampleRate: Int) {
            // Silenced, the speech still plays as silence, so the call's timing is unchanged (#487).
            playback.enqueuePcm16(if (muted) ByteArray(pcm.size) else pcm, sampleRate)
        }

        override fun interrupt() = playback.stop()
        override fun stop() = playback.stop()
        override fun setMuted(muted: Boolean) { this.muted = muted }
    }

    fun routeChanged() = capture.routeChanged()
}
