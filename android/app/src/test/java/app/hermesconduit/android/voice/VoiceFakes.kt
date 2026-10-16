package app.hermesconduit.android.voice

import app.hermesconduit.android.core.audio.AudioCaptureService
import app.hermesconduit.android.core.audio.CaptureEvent
import app.hermesconduit.android.core.audio.CapturedAudio
import app.hermesconduit.android.core.audio.LiveTranscription
import app.hermesconduit.android.core.audio.PlaybackIntent
import app.hermesconduit.android.core.audio.SpeechPlaybackService
import app.hermesconduit.android.core.audio.SpeechStream
import app.hermesconduit.android.core.audio.VoiceGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class FakePlayback : SpeechPlaybackService {
    var started = 0
    var stopped = 0
    var pcmBytes = 0
    var clips = 0
    var playing = false
    override var isPaused = false
    override val isPlaying: Boolean get() = playing && !isPaused
    override var intent = PlaybackIntent.STANDALONE
    override var playbackRate = 1f
    var lastRate = 1f
    override fun start(sampleRate: Int) {
        started++
        playing = true
        lastRate = playbackRate
    }
    override fun enqueuePcm16(data: ByteArray, sampleRate: Int): Int {
        pcmBytes += data.size
        return data.size
    }
    override fun playEncoded(data: ByteArray) {
        clips++
        playing = true
    }
    override fun finish() {}
    override suspend fun drain() { playing = false }
    override fun stop() {
        stopped++
        playing = false
        isPaused = false
    }
    override fun pause(): Boolean {
        if (!playing) return false
        isPaused = true
        return true
    }
    override fun resume() { isPaused = false }
}

/** A speech stream the test drives: `finish()` waits until the test releases it. */
class FakeSpeechStream(private val onStart: (Int) -> Unit, private val onPcm: (ByteArray, Int) -> Unit) : SpeechStream {
    val appended = mutableListOf<String>()
    var cancelled = false
    val release = CompletableDeferred<Boolean>()
    var autoFinish = true
    override suspend fun append(text: String) {
        if (cancelled) throw kotlinx.coroutines.CancellationException()
        appended += text
    }
    override suspend fun finish(): Boolean {
        if (autoFinish) {
            onStart(24_000)
            onPcm(ByteArray(480), 24_000)
            return true
        }
        return release.await()
    }
    override fun cancel() {
        cancelled = true
        release.cancel()
    }

    /** Hermes starts streaming audio. */
    fun emitAudio() {
        onStart(24_000)
        onPcm(ByteArray(480), 24_000)
    }
}

class FakeGateway : VoiceGateway {
    override val profile = "default"
    override val identity = "test|default"
    var transcripts = ArrayDeque<String>()
    var transcribed = 0
    val streams = mutableListOf<FakeSpeechStream>()
    var autoFinish = true
    override suspend fun transcribe(audio: CapturedAudio): String {
        transcribed++
        return transcripts.removeFirstOrNull() ?: ""
    }
    override suspend fun openSpeechStream(onStart: (Int) -> Unit, onPcm16: (ByteArray, Int) -> Unit, onEncodedAudio: (ByteArray) -> Unit): SpeechStream =
        FakeSpeechStream(onStart, onPcm16).also {
            it.autoFinish = autoFinish
            streams += it
        }
    override suspend fun openLiveTranscription(sampleRate: Int, onPartial: (String) -> Unit, onUnavailable: () -> Unit): LiveTranscription? = null
    override suspend fun openSpeechServerTranscription(sampleRate: Int, onPartial: (String) -> Unit, onFailure: (String) -> Unit): LiveTranscription? = null
}

class FakeCapture : AudioCaptureService {
    val flow = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CaptureEvent> = flow
    override var captureGeneration = 1L
    var permission = true
    var listening = false
    var paused = false
    var stops = 0
    var held = false
    override fun hasPermission() = permission
    override fun startListening(includePreRoll: Boolean) {
        listening = true
        paused = false
        held = false
    }
    override fun beginBargeInMonitoring() {
        held = false
    }
    override fun pause() {
        paused = true
        captureGeneration++
    }
    override fun holdForPlayback() { held = true }
    override val isHeldForPlayback: Boolean get() = held
    override fun resume() { paused = false }
    override fun finishUtterance(): CapturedAudio {
        listening = false
        return CapturedAudio(ByteArray(3_200))
    }
    override fun stop() {
        stops++
        listening = false
        captureGeneration++
    }
    override val recordedPcm16: ByteArray get() = ByteArray(0)
}
