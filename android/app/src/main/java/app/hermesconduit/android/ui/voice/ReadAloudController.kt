package app.hermesconduit.android.ui.voice

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.core.audio.PlaybackIntent
import app.hermesconduit.android.core.audio.SpeechPlaybackService
import app.hermesconduit.android.core.audio.SpeechStream
import app.hermesconduit.android.core.audio.VoiceGateway
import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.SpokenTextFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The remote actions Read Aloud answers (media session buttons); each returns false when refused. */
class ReadAloudRemoteCommands(
    val pause: () -> Boolean,
    val resume: () -> Boolean,
    val togglePause: () -> Boolean,
    val stop: () -> Boolean,
)

/** Lock screen / notification / headset controls for Read Aloud (iOS Now Playing). */
interface ReadAloudNowPlaying {
    fun begin(title: String, commands: ReadAloudRemoteCommands)
    fun setPaused(paused: Boolean)
    /** Clears the item; idempotent. */
    fun end()
}

/**
 * Manual per-message read aloud: one message through the same streaming TTS
 * transport and playback as a voice conversation, never touching the
 * microphone. Playback outlives the foreground (#373); only starting a new
 * reply needs the app on screen. Runs on [scope]'s (main) dispatcher.
 */
class ReadAloudController(
    private val scope: CoroutineScope,
    private val playback: SpeechPlaybackService,
    private val nowPlaying: ReadAloudNowPlaying,
    private val playbackRate: () -> Float,
    private val reportError: (String) -> Unit = {},
) {
    sealed interface State {
        data object Idle : State
        data class Preparing(val messageId: String) : State
        data class Playing(val messageId: String) : State
        data class Failed(val messageId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    var gateway: VoiceGateway? = null
        private set
    private var speechStream: SpeechStream? = null
    private var playbackJob: Job? = null
    private var operationGeneration = 0L
    private var isForegroundActive = true
    private var nowPlayingTitle = ""
    /** A reply left paused this long is stopped. */
    var pausedStopDelayMs: Long = 10 * 60 * 1000
    private var pausedStopJob: Job? = null

    fun isActiveMessage(messageId: String): Boolean = when (val s = _state.value) {
        is State.Preparing -> s.messageId == messageId
        is State.Playing -> s.messageId == messageId
        else -> false
    }

    /**
     * Stops playback if this message owns it; otherwise takes over. Content
     * with nothing readable is a no-op so an unreadable tap never kills playback.
     */
    fun toggle(messageId: String, content: String) {
        if (isActiveMessage(messageId)) {
            stop()
            return
        }
        // `MEDIA:` file tags are attachments, not words (#439).
        val readable = ContentSegmenter.removingTags(content)
        if (readable.isBlank()) return
        if (!isForegroundActive) return
        stop()
        startPlayback(messageId, readable)
    }

    fun stop() {
        operationGeneration++
        val job = playbackJob
        playbackJob = null
        val stream = speechStream
        speechStream = null
        job?.cancel()
        stream?.cancel()
        playback.stop()
        settle(State.Idle)
    }

    fun pause(): Boolean {
        if (_state.value !is State.Playing) return false
        if (_isPaused.value) return true
        if (!playback.pause()) return false
        _isPaused.value = true
        nowPlaying.setPaused(true)
        val generation = operationGeneration
        pausedStopJob = scope.launch {
            delay(pausedStopDelayMs)
            if (generation == operationGeneration && _isPaused.value && playback.isPaused) stop()
        }
        return true
    }

    fun resume(): Boolean {
        if (_state.value !is State.Playing) return false
        if (!_isPaused.value) return true
        if (!playback.isPaused) {
            stop()
            return false
        }
        playback.resume()
        _isPaused.value = false
        pausedStopJob?.cancel()
        pausedStopJob = null
        nowPlaying.setPaused(false)
        return true
    }

    fun togglePause(): Boolean = if (_isPaused.value) resume() else pause()

    /** A replaced or cleared gateway (disconnect, profile change) stops the operation. */
    fun setGateway(gateway: VoiceGateway?) {
        if (this.gateway === gateway) return
        this.gateway = gateway
        stop()
    }

    fun setForegroundActive(active: Boolean) {
        isForegroundActive = active
    }

    private fun startPlayback(messageId: String, content: String) {
        operationGeneration++
        val generation = operationGeneration
        _state.value = State.Preparing(messageId)
        nowPlayingTitle = title(content)
        playbackJob = scope.launch { runPlayback(generation, messageId, content) }
    }

    private suspend fun runPlayback(generation: Long, messageId: String, content: String) {
        val gateway = gateway
        if (gateway == null) {
            fail(messageId, L10n.tr("Read aloud needs a connection to Hermes. Wait for Conduit to reconnect, then try again."), generation)
            return
        }
        try {
            playback.intent = PlaybackIntent.STANDALONE
            playback.playbackRate = playbackRate()
            val stream = gateway.openSpeechStream(
                onStart = { rate ->
                    if (isCurrent(generation)) {
                        playback.start(rate)
                        transitionToPlaying(messageId, generation)
                    }
                },
                onPcm16 = { data, rate ->
                    if (isCurrent(generation)) {
                        playback.enqueuePcm16(data, rate)
                        transitionToPlaying(messageId, generation)
                    }
                },
                onEncodedAudio = { data ->
                    if (isCurrent(generation)) {
                        playback.playEncoded(data)
                        transitionToPlaying(messageId, generation)
                    }
                },
            )
            if (!isCurrent(generation)) {
                stream.cancel()
                return
            }
            speechStream = stream
            // A reply that is all action or emoji is read as written rather than not at all.
            val spoken = SpokenTextFilter.filter(content)
            stream.append(if (spoken.isBlank()) content else spoken)
            if (!isCurrent(generation)) return
            stream.finish()
            if (!isCurrent(generation)) return
            speechStream = null
            playback.finish()
            playback.drain()
            if (!isCurrent(generation)) return
            settle(State.Idle)
        } catch (e: Exception) {
            if (!isCurrent(generation)) return
            val stream = speechStream
            speechStream = null
            stream?.cancel()
            playback.stop()
            if (e is CancellationException) {
                settle(State.Idle)
                return
            }
            fail(messageId, e.message ?: L10n.tr("Hermes speech stream failed."), generation)
        } finally {
            if (operationGeneration == generation) playbackJob = null
        }
    }

    private fun transitionToPlaying(messageId: String, generation: Long) {
        if (!isCurrent(generation)) return
        if (_isPaused.value && !playback.isPaused) {
            _isPaused.value = false
            pausedStopJob?.cancel()
            pausedStopJob = null
            nowPlaying.setPaused(false)
        }
        val current = _state.value
        if (current is State.Preparing && current.messageId == messageId) {
            _state.value = State.Playing(messageId)
            nowPlaying.begin(
                nowPlayingTitle,
                ReadAloudRemoteCommands(
                    pause = { pause() },
                    resume = { resume() },
                    togglePause = { togglePause() },
                    stop = {
                        if (_state.value == State.Idle) false else {
                            stop()
                            true
                        }
                    },
                ),
            )
        }
    }

    private fun fail(messageId: String, message: String, generation: Long) {
        if (!isCurrent(generation)) return
        settle(State.Failed(messageId, message))
        reportError(message)
    }

    private fun settle(terminal: State) {
        _state.value = terminal
        _isPaused.value = false
        pausedStopJob?.cancel()
        pausedStopJob = null
        nowPlaying.end()
    }

    private fun isCurrent(generation: Long) = generation == operationGeneration

    companion object {
        const val MAX_TITLE_LENGTH = 80

        /** A short Now Playing title from the reply: its first spoken line. */
        fun title(content: String): String {
            val spoken = SpokenTextFilter.filter(content)
            val source = if (spoken.isBlank()) content else spoken
            val firstLine = source.lines().map { line ->
                line.filter { it != '*' && it != '`' }.trim { it in "#>-•" || it == ' ' || it == '\t' }
            }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (firstLine.length <= MAX_TITLE_LENGTH) return firstLine
            return firstLine.take(MAX_TITLE_LENGTH - 1).trim() + "…"
        }
    }
}
