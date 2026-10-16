package app.hermesconduit.android.core.audio

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HermesSpeechFailure
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Frames on `/api/audio/speak-stream`. Conduit sends `{"text": …}` text
 * frames then `{"done": true}`. Hermes answers binary PCM16 frames, and JSON
 * control objects (as text or binary frames): `start` (with `sample_rate`),
 * `end`, `fallback` (use `/api/audio/speak` instead) and `error`.
 */
sealed interface SpeakStreamFrame {
    data class Start(val sampleRate: Int) : SpeakStreamFrame
    data object End : SpeakStreamFrame
    data object Fallback : SpeakStreamFrame
    data class Error(val message: String?) : SpeakStreamFrame
    data class Pcm(val bytes: ByteArray) : SpeakStreamFrame
    /** A control object of a type Conduit doesn't know, or an unreadable text frame. */
    data object Ignored : SpeakStreamFrame
}

object SpeakStreamFraming {
    fun textFrame(text: String): String = JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive(text))).toString()
    const val DONE_FRAME = "{\"done\":true}"

    fun parse(text: String): SpeakStreamFrame {
        val obj = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull() ?: return SpeakStreamFrame.Ignored
        return control(obj)
    }

    fun parse(bytes: ByteArray): SpeakStreamFrame {
        if (bytes.isEmpty()) return SpeakStreamFrame.Ignored
        // A binary frame that is a JSON object is control; anything else is PCM.
        if (bytes[0] == '{'.code.toByte()) {
            val obj = runCatching { ConduitJson.parseToJsonElement(bytes.decodeToString()).obj }.getOrNull()
            if (obj != null) return control(obj)
        }
        return SpeakStreamFrame.Pcm(bytes)
    }

    private fun control(obj: JsonObject): SpeakStreamFrame = when (obj["type"].str) {
        "start" -> SpeakStreamFrame.Start((obj["sample_rate"].double ?: obj["sampleRate"].double ?: Pcm.DEFAULT_SPEECH_SAMPLE_RATE.toDouble()).toInt())
        "end" -> SpeakStreamFrame.End
        "fallback" -> SpeakStreamFrame.Fallback
        "error" -> SpeakStreamFrame.Error(obj["message"].str)
        else -> SpeakStreamFrame.Ignored
    }
}

/** The slice of a WebSocket the speech stream uses, so tests can script one. */
interface SpeechSocket {
    /** False when the socket is already closed or failed. */
    fun send(text: String): Boolean
    fun cancel()
}

/**
 * Port of the iOS `HermesSpeechStream`. All methods (and the socket callbacks
 * [onText], [onBytes], [onFailure], [onClosed]) run on [scope]'s dispatcher.
 */
class HermesSpeechStream(
    private val scope: CoroutineScope,
    private val socket: SpeechSocket,
    private val fallback: suspend (String) -> ByteArray,
    private val onStart: (Int) -> Unit,
    private val onPcm16: (ByteArray, Int) -> Unit,
    private val onEncodedAudio: (ByteArray) -> Unit,
    private val finishIdleTimeoutMs: Long = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
) : SpeechStream {
    private val completed = CompletableDeferred<Boolean>()
    private var allText = StringBuilder()
    private var receivedPcm = false
    private var sampleRate = Pcm.DEFAULT_SPEECH_SAMPLE_RATE
    private var started = false
    private var terminal = false
    private var terminalError: Throwable? = null
    private var fallbackMode = false
    private var fallbackAttempted = false
    private var finishRequested = false
    private var cancelledByClient = false
    private var lastActivity = now()
    private var idleJob: Job? = null
    private var fallbackJob: Job? = null

    override suspend fun append(text: String) {
        if (terminal) throw terminalError ?: CancellationException()
        if (text.isEmpty()) return
        allText.append(text)
        if (fallbackMode) return
        if (!socket.send(SpeakStreamFraming.textFrame(text))) {
            if (cancelledByClient) throw CancellationException()
            if (terminal) throw terminalError ?: IOException("closed")
            if (receivedPcm) throw IOException(L10n.tr("Hermes speech stream failed."))
            // The socket never carried speech: finish() plays the one-shot fallback instead.
            fallbackMode = true
            socket.cancel()
        }
    }

    override suspend fun finish(): Boolean {
        if (terminal) {
            terminalError?.let { throw it }
            return receivedPcm
        }
        finishRequested = true
        armFinishIdleTimeout()
        if (fallbackMode) {
            startFallbackIfNeeded()
        } else if (!socket.send(SpeakStreamFraming.DONE_FRAME)) {
            if (receivedPcm) complete(Result.failure(IOException(L10n.tr("Hermes speech stream failed."))))
            else {
                fallbackMode = true
                startFallbackIfNeeded()
            }
        }
        return completed.await()
    }

    override fun cancel() {
        if (cancelledByClient) return
        cancelledByClient = true
        idleJob?.cancel()
        fallbackJob?.cancel()
        socket.cancel()
        complete(Result.failure(CancellationException("Speech cancelled")))
    }

    // region Socket callbacks

    fun onText(text: String) {
        lastActivity = now()
        handle(SpeakStreamFraming.parse(text))
    }

    fun onBytes(bytes: ByteArray) {
        lastActivity = now()
        handle(SpeakStreamFraming.parse(bytes))
    }

    /** The handshake was refused or the connection dropped. */
    fun onFailure(error: Throwable) {
        if (terminal || cancelledByClient) return
        if (!receivedPcm) {
            fallbackMode = true
            startFallbackIfNeeded()
        } else {
            complete(Result.failure(IOException(error.message ?: L10n.tr("Hermes speech stream failed."))))
        }
    }

    fun onClosed() = onFailure(IOException(L10n.tr("Hermes speech stream failed.")))

    // endregion

    private fun handle(frame: SpeakStreamFrame) {
        if (terminal || cancelledByClient) return
        try {
            when (frame) {
                is SpeakStreamFrame.Start -> {
                    sampleRate = frame.sampleRate
                    if (!started) {
                        onStart(sampleRate)
                        started = true
                    }
                }
                SpeakStreamFrame.End -> complete(Result.success(receivedPcm))
                SpeakStreamFrame.Fallback -> {
                    fallbackMode = true
                    socket.cancel()
                    startFallbackIfNeeded()
                }
                is SpeakStreamFrame.Error -> complete(
                    Result.failure(IOException(frame.message?.let(HermesSpeechFailure::message) ?: L10n.tr("Hermes speech stream failed."))),
                )
                is SpeakStreamFrame.Pcm -> {
                    if (!started) {
                        onStart(sampleRate)
                        started = true
                    }
                    onPcm16(frame.bytes, sampleRate)
                    receivedPcm = true
                }
                SpeakStreamFrame.Ignored -> Unit
            }
        } catch (e: Exception) {
            complete(Result.failure(e))
        }
    }

    private fun armFinishIdleTimeout() {
        idleJob?.cancel()
        lastActivity = now()
        idleJob = scope.launch {
            while (true) {
                val wait = lastActivity + finishIdleTimeoutMs - now()
                if (wait > 0) delay(wait)
                if (terminal || cancelledByClient) return@launch
                if (now() - lastActivity >= finishIdleTimeoutMs) break
            }
            // A fallback already in flight owns the outcome.
            if (fallbackAttempted) return@launch
            if (receivedPcm) {
                complete(Result.failure(IOException(L10n.tr("Hermes speech streaming timed out."))))
            } else {
                fallbackMode = true
                startFallbackIfNeeded()
            }
        }
    }

    private fun startFallbackIfNeeded() {
        if (terminal || cancelledByClient || !finishRequested || fallbackAttempted || fallbackJob != null) return
        fallbackJob = scope.launch {
            try {
                playFallback()
            } catch (e: CancellationException) {
                if (!cancelledByClient && !terminal) complete(Result.failure(e))
            } catch (e: Exception) {
                if (!cancelledByClient && !terminal) complete(Result.failure(e))
            }
        }
    }

    private suspend fun playFallback() {
        if (cancelledByClient || terminal) throw CancellationException()
        if (receivedPcm) {
            complete(Result.success(true))
            return
        }
        if (fallbackAttempted) return
        fallbackAttempted = true
        val data = fallback(allText.toString())
        if (cancelledByClient || terminal) throw CancellationException()
        onEncodedAudio(data)
        complete(Result.success(false))
    }

    private fun complete(result: Result<Boolean>) {
        if (terminal) return
        terminal = true
        idleJob?.cancel()
        idleJob = null
        result.exceptionOrNull()?.let { terminalError = it }
        result.fold({ completed.complete(it) }, { completed.completeExceptionally(it) })
    }
}
