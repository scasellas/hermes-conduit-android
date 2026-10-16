package app.hermesconduit.android.core.audio

import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.HermesSpeechFailure
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.ConnectionUrlPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Base64

/**
 * The Hermes voice routes for the active dashboard and [profile]: upload
 * transcription, the speak stream (with its `/api/audio/speak` fallback),
 * live transcription and the speech-server relay. Socket callbacks are hopped
 * onto [scope] (the main dispatcher) before they touch any state.
 */
class HermesVoiceGateway(
    private val hermes: HermesConnection,
    override val profile: String,
    private val scope: CoroutineScope = hermes.scope,
) : VoiceGateway {
    private val baseUrl: String get() = hermes.api?.baseUrl ?: throw IOException(L10n.tr("Connect to Hermes before starting voice."))

    override val identity: String get() = (hermes.api?.baseUrl ?: "") + "|" + profile

    private fun profileQuery() = mapOf("profile" to profile.takeIf { it != "default" })

    override suspend fun transcribe(audio: CapturedAudio): String {
        val dataUrl = audio.dataUrl
        val response = hermes.rest {
            it.sendJson(
                "POST", "api/audio/transcribe",
                buildJsonObject {
                    put("data_url", dataUrl)
                    put("mime_type", "audio/wav")
                },
                profileQuery(),
                timeoutMs = TranscriptionTimeouts.forDataUrl(dataUrl.length),
            )
        }
        response["error"].str?.takeIf { it.isNotEmpty() }?.let { throw IOException(HermesSpeechFailure.message(it)) }
        val raw = response["transcript"].str ?: response["text"].str
            ?: throw IOException(L10n.tr("Hermes didn't send back what you said. Try again."))
        // An empty transcript (quiet audio, a filtered hallucination) means listen again.
        return raw.trim()
    }

    private suspend fun ticket(): String = hermes.rest { it.webSocketTicket() }

    private fun socketUrl(path: String, ticket: String) =
        ConnectionUrlPolicy.webSocketUrl(baseUrl, path, mapOf("ticket" to ticket, "profile" to profile))

    private fun openSocket(url: String, listener: WebSocketListener): WebSocket {
        val api = hermes.api ?: throw IOException(L10n.tr("Connect to Hermes before starting voice."))
        val request = Request.Builder().url(url).header("X-Conduit-Client", "conduit").build()
        return api.http.newWebSocket(request, listener)
    }

    override suspend fun openSpeechStream(
        onStart: (Int) -> Unit,
        onPcm16: (ByteArray, Int) -> Unit,
        onEncodedAudio: (ByteArray) -> Unit,
    ): SpeechStream {
        val url = try {
            socketUrl("api/audio/speak-stream", ticket())
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            throw VoiceAudioException(L10n.tr("Could not open the speech stream."))
        }
        val holder = SocketHolder()
        lateinit var stream: HermesSpeechStream
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { stream.onText(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                scope.launch { stream.onBytes(data) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { stream.onClosed() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { stream.onFailure(t) } }
        }
        stream = HermesSpeechStream(
            scope = scope,
            socket = holder,
            fallback = { text -> fallbackAudio(text) },
            onStart = onStart,
            onPcm16 = onPcm16,
            onEncodedAudio = onEncodedAudio,
        )
        holder.socket = openSocket(url, listener)
        return stream
    }

    private suspend fun fallbackAudio(text: String): ByteArray {
        val response = hermes.rest {
            it.sendJson("POST", "api/audio/speak", buildJsonObject { put("text", text) }, profileQuery(), timeoutMs = 90_000)
        }
        response["error"].str?.takeIf { it.isNotEmpty() }?.let { throw IOException(HermesSpeechFailure.message(it)) }
        val dataUrl = response["data_url"].str ?: response["dataUrl"].str
        return dataUrl?.let(::decodeDataUrl) ?: throw IOException(L10n.tr("Hermes returned invalid fallback audio."))
    }

    override suspend fun openLiveTranscription(sampleRate: Int, onPartial: (String) -> Unit, onUnavailable: () -> Unit): LiveTranscription? {
        // A ticket failure is not a verdict on the profile: this utterance uses the upload.
        val url = runCatching { socketUrl("api/audio/transcribe-stream", ticket()) }.getOrNull() ?: return null
        val holder = SocketHolder()
        lateinit var session: HermesLiveTranscription
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { session.onText(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                scope.launch { session.onBytes(data) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { session.onFailure() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { session.onFailure() } }
        }
        holder.socket = runCatching { openSocket(url, listener) }.getOrNull() ?: return null
        session = HermesLiveTranscription(scope, holder, sampleRate, onPartial, onUnavailable)
        return session
    }

    override suspend fun openSpeechServerTranscription(sampleRate: Int, onPartial: (String) -> Unit, onFailure: (String) -> Unit): LiveTranscription? {
        val url = runCatching { socketUrl(SpeechServerTranscription.SOCKET_PATH, ticket()) }.getOrNull() ?: return null
        val holder = SocketHolder()
        lateinit var session: SpeechServerTranscription
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { session.onText(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                scope.launch { session.onBytes(data) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { session.onClosed() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { session.onClosed() } }
        }
        holder.socket = runCatching { openSocket(url, listener) }.getOrNull() ?: return null
        session = SpeechServerTranscription(scope, holder, sampleRate, onPartial, onFailure)
        return session
    }

    /** One OkHttp socket behind both socket seams. */
    private class SocketHolder : SpeechSocket, VoiceSocket {
        @Volatile var socket: WebSocket? = null
        private var pending = mutableListOf<Any>()

        override fun send(text: String): Boolean = sendText(text)

        override fun sendText(text: String): Boolean {
            val s = socket ?: synchronized(this) { pending += text; return true }
            flush(s)
            return s.send(text)
        }

        override fun sendBytes(bytes: ByteArray): Boolean {
            val s = socket ?: synchronized(this) { pending += bytes; return true }
            flush(s)
            return s.send(bytes.toByteString())
        }

        private fun flush(s: WebSocket) {
            val queued = synchronized(this) { pending.also { pending = mutableListOf() } }
            queued.forEach { if (it is String) s.send(it) else s.send((it as ByteArray).toByteString()) }
        }

        override fun cancel() { socket?.cancel() }
        override fun close() { socket?.close(1000, null) ?: Unit }
    }

    companion object {
        fun decodeDataUrl(dataUrl: String): ByteArray? {
            val comma = dataUrl.indexOf(',')
            if (!dataUrl.startsWith("data:") || comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) return null
            return runCatching { Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim()) }.getOrNull()
        }
    }
}
