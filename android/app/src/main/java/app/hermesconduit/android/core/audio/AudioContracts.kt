package app.hermesconduit.android.core.audio

import kotlinx.coroutines.flow.SharedFlow
import java.util.Base64

/**
 * The voice audio layer, independent of Compose and of Hermes' session state
 * so the conversation, dictation and read-aloud controllers can be exercised
 * with deterministic test doubles. Live voice modes (Gemini / GPT / Grok)
 * reuse the capture, playback and PCM helpers here.
 */

/** Recorded speech: 16 kHz mono PCM16 (little endian). */
class CapturedAudio(val pcm16: ByteArray, val sampleRate: Int = Pcm.CAPTURE_SAMPLE_RATE) {
    val wav: ByteArray get() = Pcm.wav(pcm16, sampleRate)
    val dataUrl: String get() = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)
    val durationSeconds: Double get() = pcm16.size / 2.0 / sampleRate
}

sealed interface CaptureEvent {
    /** Raw microphone peak (0…1) for the input-tap lifetime `generation`. */
    data class Level(val level: Float, val atMs: Long, val generation: Long) : CaptureEvent
    data class Interrupted(val generation: Long) : CaptureEvent
    data object RouteChanged : CaptureEvent
}

class VoiceAudioException(message: String) : Exception(message)

interface AudioCaptureService {
    val events: SharedFlow<CaptureEvent>
    /** Bumped whenever the input is torn down or reinstalled; frames of an older generation are stale. */
    val captureGeneration: Long
    fun hasPermission(): Boolean
    /** Opens a recording window (with the last seconds of pre-roll when asked). */
    fun startListening(includePreRoll: Boolean)
    /** Keeps the microphone live for level monitoring without recording an utterance. */
    fun beginBargeInMonitoring()
    /** A real resource pause: the microphone is released. */
    fun pause()
    /** Drops frames during assistant playback but keeps the microphone (background listening). */
    fun holdForPlayback()
    val isHeldForPlayback: Boolean
    fun resume()
    fun finishUtterance(): CapturedAudio
    fun stop()
    /** PCM16 recorded so far in the open utterance (pre-roll included). */
    val recordedPcm16: ByteArray
    /** Whether the capture runs the platform echo canceller. */
    val cancelsEcho: Boolean get() = false
}

enum class PlaybackIntent { CONVERSATION, STANDALONE }

interface SpeechPlaybackService {
    val isPlaying: Boolean
    var intent: PlaybackIntent
    /** Speed for the next stream or clip; 1.0 is normal. */
    var playbackRate: Float
    fun start(sampleRate: Int)
    /** Returns the bytes accepted. */
    fun enqueuePcm16(data: ByteArray, sampleRate: Int): Int
    /** A whole clip (mp3, ogg, wav) from the fallback route. */
    fun playEncoded(data: ByteArray)
    fun finish()
    suspend fun drain()
    fun stop()
    fun pause(): Boolean
    fun resume()
    val isPaused: Boolean
}

/** Text in, speech out over Hermes' speak stream. */
interface SpeechStream {
    suspend fun append(text: String)
    /** True when PCM was streamed (false when the fallback clip played). */
    suspend fun finish(): Boolean
    fun cancel()
}

/** Words recognized while the user speaks; `finish()` is null when the recording must be uploaded. */
interface LiveTranscription {
    fun push(pcm16: ByteArray)
    suspend fun finish(): String?
    fun cancel()
}

/** The Hermes voice routes for one host and profile. */
interface VoiceGateway {
    val profile: String
    val identity: String
    suspend fun transcribe(audio: CapturedAudio): String
    suspend fun openSpeechStream(
        onStart: (Int) -> Unit,
        onPcm16: (ByteArray, Int) -> Unit,
        onEncodedAudio: (ByteArray) -> Unit,
    ): SpeechStream
    suspend fun openLiveTranscription(sampleRate: Int, onPartial: (String) -> Unit, onUnavailable: () -> Unit): LiveTranscription?
    suspend fun openSpeechServerTranscription(sampleRate: Int, onPartial: (String) -> Unit, onFailure: (String) -> Unit): LiveTranscription?
}

/** On-device transcription of a finished recording. */
interface DeviceSpeechTranscriber {
    suspend fun transcribe(audio: CapturedAudio): String
    fun cancel()
}
