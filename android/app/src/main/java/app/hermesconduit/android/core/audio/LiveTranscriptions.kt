package app.hermesconduit.android.core.audio

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** A WebSocket as the transcription sessions use it (OkHttp queues sends in order). */
interface VoiceSocket {
    fun sendText(text: String): Boolean
    fun sendBytes(bytes: ByteArray): Boolean
    fun close()
}

internal fun parseFrame(text: String): JsonObject? = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull()

/**
 * Live speech-to-text over Hermes' `/api/audio/transcribe-stream`
 * (`stt.streaming`). Conduit sends `{"sample_rate": N}`, binary PCM16 frames,
 * then `{"eos": true}`. Hermes answers `partial` frames and one `final`, or an
 * `error` (no live STT here → the recording is uploaded instead).
 * Socket callbacks must arrive on [scope]'s dispatcher.
 */
class HermesLiveTranscription(
    private val scope: CoroutineScope,
    private val socket: VoiceSocket,
    sampleRate: Int,
    private val onPartial: (String) -> Unit,
    private val onUnavailable: () -> Unit,
    private val finishTimeoutMs: Long = 10_000,
) : LiveTranscription {
    private sealed interface Outcome {
        data class Final(val text: String) : Outcome
        data object Failed : Outcome
        data object Refused : Outcome
    }

    private var outcome: Outcome? = null
    private val result = CompletableDeferred<String?>()
    private var sawPartial = false
    private var ended = false

    init {
        if (!socket.sendText("{\"sample_rate\":$sampleRate}")) resolve(Outcome.Failed)
    }

    override fun push(pcm16: ByteArray) {
        if (outcome != null || ended || pcm16.isEmpty()) return
        if (!socket.sendBytes(pcm16)) resolve(Outcome.Failed)
    }

    override suspend fun finish(): String? {
        outcome?.let { return transcript(it) }
        if (ended) return null
        ended = true
        if (!socket.sendText("{\"eos\":true}")) resolve(Outcome.Failed)
        val text = withTimeoutOrNull(finishTimeoutMs) { result.await() }
        if (outcome == null) resolve(Outcome.Failed)
        return text
    }

    override fun cancel() {
        ended = true
        resolve(Outcome.Failed)
    }

    fun onText(text: String) {
        if (outcome != null) return
        val frame = parseFrame(text) ?: return
        when (frame["type"].str) {
            "partial" -> {
                val words = frame["text"].str.orEmpty().trim()
                if (words.isEmpty()) return
                sawPartial = true
                onPartial(words)
            }
            "final" -> resolve(Outcome.Final(frame["transcript"].str.orEmpty()))
            "error" -> resolve(Outcome.Refused)
        }
    }

    fun onBytes(bytes: ByteArray) = onText(bytes.decodeToString())

    fun onFailure() = resolve(Outcome.Failed)

    private fun resolve(value: Outcome) {
        if (outcome != null) return
        outcome = value
        if (value == Outcome.Refused && !sawPartial && !ended) onUnavailable()
        result.complete(transcript(value))
        socket.close()
    }

    private fun transcript(outcome: Outcome): String? =
        (outcome as? Outcome.Final)?.text?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Speech-to-text through the user's own speech server (OpenAI Realtime
 * protocol), reached only through the notifier's relay
 * (`PLUGIN/speech-server/socket`). Transcription only, 24 kHz PCM16.
 */
class SpeechServerTranscription(
    private val scope: CoroutineScope,
    private val socket: VoiceSocket,
    private val captureSampleRate: Int,
    private val onPartial: (String) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val finishTimeoutMs: Long = 12_000,
    private val quietGraceMs: Long = 2_000,
) : LiveTranscription {
    private sealed interface Outcome {
        data class Finished(val text: String?) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private var outcome: Outcome? = null
    private val result = CompletableDeferred<String?>()
    private var ended = false
    private var quietGraceElapsed = false
    private var startedSegments = 0
    private var finishedSegments = 0
    private val segments = mutableListOf<String>()
    private var pendingText = ""
    private var lastError: String? = null
    private var publishedPartial = ""

    init {
        send(sessionUpdate.toString())
    }

    override fun push(pcm16: ByteArray) {
        if (outcome != null || ended || pcm16.isEmpty()) return
        sendAudio(pcm16)
    }

    override suspend fun finish(): String? {
        outcome?.let { return transcript(it) }
        if (ended) return null
        ended = true
        enqueueAudio(TRAILING_SILENCE)
        send(buildJsonObject { put("type", "input_audio_buffer.commit") }.toString())
        resolveIfSettled()
        val grace = scope.launch {
            delay(quietGraceMs)
            quietGraceElapsed = true
            resolveIfSettled()
        }
        val text = withTimeoutOrNull(finishTimeoutMs) { result.await() }
        grace.cancel()
        if (outcome == null) {
            if (segments.isEmpty()) resolve(Outcome.Failed(lastError ?: L10n.tr("Your speech server didn't send any words back in time.")))
            else resolve(Outcome.Finished(joinedTranscript))
            return transcript(outcome!!)
        }
        return text
    }

    override fun cancel() {
        ended = true
        resolve(Outcome.Finished(null))
    }

    private fun sendAudio(pcm16: ByteArray) {
        val chunkBytes = maxOf(2, captureSampleRate * 2)
        for (chunk in Pcm.chunks(pcm16, chunkBytes)) enqueueAudio(Pcm.resampled(chunk, captureSampleRate, INPUT_SAMPLE_RATE))
    }

    private fun enqueueAudio(pcm24: ByteArray) {
        if (pcm24.isEmpty()) return
        send(buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.getEncoder().encodeToString(pcm24))
        }.toString())
    }

    private fun send(text: String) {
        if (!socket.sendText(text)) resolve(Outcome.Failed(L10n.tr("Lost the connection to your speech server.")))
    }

    fun onText(text: String) {
        if (outcome != null) return
        val frame = parseFrame(text) ?: return
        when (frame["type"].str) {
            "input_audio_buffer.speech_started" -> startedSegments++
            "conversation.item.input_audio_transcription.delta" -> {
                pendingText += frame["delta"].str.orEmpty()
                publishPartial()
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val words = frame["transcript"].str.orEmpty().trim()
                if (words.isNotEmpty()) segments += words
                pendingText = ""
                finishedSegments++
                startedSegments = maxOf(startedSegments, finishedSegments)
                publishPartial()
                resolveIfSettled()
            }
            "conversation.item.input_audio_transcription.failed" -> {
                pendingText = ""
                finishedSegments++
                startedSegments = maxOf(startedSegments, finishedSegments)
                lastError = errorMessage(frame) ?: lastError
                publishPartial()
                resolveIfSettled()
            }
            "error" -> {
                val message = errorMessage(frame)
                if (message != null && !isEmptyCommit(frame)) lastError = L10n.tr("Your speech server reported: %s", message)
            }
        }
    }

    fun onBytes(bytes: ByteArray) = onText(bytes.decodeToString())

    fun onClosed() {
        if (outcome != null) return
        if (ended && finishedSegments >= startedSegments && segments.isNotEmpty()) resolve(Outcome.Finished(joinedTranscript))
        else resolve(Outcome.Failed(lastError ?: L10n.tr("Lost the connection to your speech server.")))
    }

    private fun publishPartial() {
        val text = (segments + pendingText).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        if (text == publishedPartial || outcome != null) return
        publishedPartial = text
        onPartial(text)
    }

    private fun resolveIfSettled() {
        if (!ended || outcome != null || finishedSegments < startedSegments) return
        if (startedSegments > 0 || quietGraceElapsed) resolve(Outcome.Finished(joinedTranscript))
    }

    private val joinedTranscript: String? get() = segments.joinToString(" ").trim().ifEmpty { null }

    private fun resolve(value: Outcome) {
        if (outcome != null) return
        outcome = value
        if (value is Outcome.Failed) onFailure(value.reason)
        result.complete(transcript(value))
        socket.close()
    }

    private fun transcript(outcome: Outcome): String? = (outcome as? Outcome.Finished)?.text

    companion object {
        const val SOCKET_PATH = "api/plugins/conduit_push/speech-server/socket"
        const val STATUS_PATH = "api/plugins/conduit_push/speech-server/status"
        const val TEST_PATH = "api/plugins/conduit_push/speech-server/test"
        const val INPUT_SAMPLE_RATE = 24_000
        val TRAILING_SILENCE: ByteArray = Pcm.silence(0.8, INPUT_SAMPLE_RATE)

        /** Transcription only: the server's own model and voice stay idle. */
        val sessionUpdate: JsonObject = buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "realtime")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", INPUT_SAMPLE_RATE)
                        }
                        putJsonObject("turn_detection") {
                            put("type", "server_vad")
                            put("create_response", false)
                            put("interrupt_response", false)
                        }
                    }
                }
            }
        }

        private fun errorMessage(frame: JsonObject): String? {
            val error = frame["error"].obj
            val message = (error?.get("message").str ?: frame["message"].str)?.trim()
            return message?.takeIf { it.isNotEmpty() }?.take(200)
        }

        private fun isEmptyCommit(frame: JsonObject): Boolean {
            val error = frame["error"].obj
            val code = error?.get("code").str ?: error?.get("type").str ?: ""
            return code.contains("commit_empty") || code.contains("buffer_too_small")
        }
    }
}

