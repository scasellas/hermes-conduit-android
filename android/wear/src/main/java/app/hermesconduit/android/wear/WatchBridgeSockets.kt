package app.hermesconduit.android.wear

import android.os.Handler
import android.os.Looper
import app.hermesconduit.android.core.live.GrokLiveProtocol
import app.hermesconduit.android.core.live.LiveServerClose
import app.hermesconduit.android.core.live.LiveSocket
import app.hermesconduit.wearwire.WatchAudioBridgeStream
import app.hermesconduit.wearwire.WatchAudioBridgeWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The watch's WebSocket to the Hermes host's audio bridge through the push
 * relay (iOS `WatchBridgeSocket`): binary messages only, the grant's relay
 * key as the bearer, no redirects (the key stays with the relay). OkHttp
 * pings it every 20 s: the relay closes a socket silent for a minute.
 * Callbacks run on the main thread.
 */
class WatchBridgeSocket(url: String, watchKey: String, http: OkHttpClient) {
    /** A binary message from the relay. */
    var onMessage: ((ByteArray) -> Unit)? = null
    /** open, close (code, reason) or failure (http). */
    var onEvent: ((String, Map<String, Any?>) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    var bytesUp = 0L
        private set
    var bytesDown = 0L
        private set
    @Volatile var closeCode: Int? = null
        private set

    private val socket: WebSocket = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()
        .newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $watchKey").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                post("open", emptyMap())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                main.post {
                    if (closed) return@post
                    bytesDown += data.size
                    onMessage?.invoke(data)
                }
            }
            // The relay sends no text.
            override fun onMessage(webSocket: WebSocket, text: String) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(1000, null)
                post("close", mapOf("code" to code, "reason" to reason.take(120)))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                post("close", mapOf("code" to code, "reason" to reason.take(120)))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post("failure", mapOf("http" to response?.code, "error" to t.javaClass.simpleName))
            }
        })

    private fun post(kind: String, fields: Map<String, Any?>) {
        main.post { if (!closed) onEvent?.invoke(kind, fields) }
    }

    /** False when the socket can't take it. */
    fun send(data: ByteArray): Boolean {
        if (closed) return false
        bytesUp += data.size
        return socket.send(data.toByteString())
    }

    fun close() {
        if (closed) return
        closed = true
        socket.close(1000, null)
    }

    companion object {
        const val PING_INTERVAL_S = 20L
    }
}

/**
 * A watch call to Grok (iOS `WatchGrokBridgeSocket`): the watch runs the
 * conversation as it runs Gemini's ([app.hermesconduit.android.core.live.GrokLiveSession],
 * shared with the phone), and xAI's session is held by the Hermes host on
 * its own xAI sign-in, reached through the call grant's audio bridge. This
 * is the session's socket over that bridge: one bridge stream per
 * connection, opened with the hello and a sealed start, and ready once the
 * host says the engine started. Then xAI's events go both ways as sealed
 * engine events; the host strips xAI's audio deltas to pace them, so its
 * audio comes back as one again here, and the session's
 * input_audio_buffer.append events go as audio, which the host appends.
 */
class WatchGrokBridgeSocket(
    url: String,
    private val grantId: String,
    watchKey: String,
    voice: String?,
    /** Null once the grant has no stream ids left: the socket then fails its first send. */
    private val stream: WatchAudioBridgeStream?,
    http: OkHttpClient,
    private val onNote: (String, Map<String, Any?>) -> Unit,
) : LiveSocket {
    private val main = Handler(Looper.getMainLooper())
    private val started = CompletableDeferred<Unit>()
    private val inbox = Channel<String>(Channel.UNLIMITED)
    @Volatile private var failure: IOException? = null
    /** The host's refusal, as the session reads a server's close. */
    @Volatile private var refusal: LiveServerClose? = null
    private val socket: WatchBridgeSocket? = stream?.let { WatchBridgeSocket(url, watchKey, http) }

    init {
        if (stream == null) {
            fail(IOException(L10n.tr("Couldn't reconnect to Hermes.")))
        } else {
            socket?.onEvent = { kind, fields -> socketEvent(kind, fields, voice) }
            socket?.onMessage = ::received
            main.postDelayed({ if (!started.isCompleted) fail(IOException(L10n.tr("Hermes didn't start Grok in time."))) }, START_WAIT_MS)
        }
    }

    override suspend fun send(text: String) {
        failure?.let { throw it }
        withTimeout(START_WAIT_MS) { started.await() }
        // The microphone goes as audio, which the host appends to xAI's input itself: a quarter fewer bytes through the relay.
        val pcm = WatchAudioBridgeWire.appendedAudio(text)
        if (pcm != null) WatchAudioBridgeWire.audioChunks(pcm).forEach { seal(it, WatchAudioBridgeWire.Kind.AUDIO) }
        else seal(text.toByteArray(Charsets.UTF_8), WatchAudioBridgeWire.Kind.EVENT)
    }

    private fun seal(plain: ByteArray, kind: WatchAudioBridgeWire.Kind) {
        failure?.let { throw it }
        val socket = socket ?: throw IOException(L10n.tr("Lost the connection to Hermes."))
        val stream = stream ?: throw IOException(L10n.tr("Couldn't reconnect to Hermes."))
        val message = try {
            stream.seal(plain, kind)
        } catch (_: Exception) {
            throw IOException(L10n.tr("Couldn't reconnect to Hermes."))
        }
        if (!socket.send(message)) throw failure ?: IOException(L10n.tr("Lost the connection to Hermes."))
    }

    override suspend fun receive(): String = inbox.receive()

    override fun close() {
        if (started.isCompleted && failure == null) {
            // Ends xAI's session now; the relay's notice would too.
            runCatching { seal(WatchAudioBridgeWire.end, WatchAudioBridgeWire.Kind.CONTROL) }
        }
        fail(IOException(L10n.tr("Lost the connection to Hermes.")))
    }

    override suspend fun serverClose(withinMs: Long): LiveServerClose? = refusal

    private fun socketEvent(kind: String, fields: Map<String, Any?>, voice: String?) {
        if (failure != null) return
        when (kind) {
            "open" -> {
                onNote("open", emptyMap())
                val stream = stream ?: return
                socket?.send(WatchAudioBridgeWire.hello(stream.streamId))
                // xAI's session is the watch's to set up: the start carries only the engine and voice.
                val start = WatchAudioBridgeWire.start(WatchAudioBridgeWire.GROK, voice, null, null, history = emptyList())
                runCatching { socket?.send(stream.seal(start, WatchAudioBridgeWire.Kind.CONTROL)) }
                    .onFailure { fail(IOException(L10n.tr("Couldn't reconnect to Hermes."))) }
            }
            "close", "failure" -> {
                onNote("closed", fields + ("event" to kind) + ("started" to started.isCompleted))
                val code = fields["code"] as? Int ?: socket?.closeCode
                val status = fields["http"] as? Int
                if (code == GRANT_ENDED) {
                    // The call's grant ended: closed on Hermes or the relay, or expired. Nothing to reconnect to.
                    refusal = LiveServerClose(GRANT_ENDED, L10n.tr("The call's access to Hermes ended."))
                } else if (!started.isCompleted && status != null && status != 101) {
                    // The relay refused the upgrade (no plugin route, a bad grant).
                    refusal = LiveServerClose(status, "", isHttpStatus = true)
                }
                fail(IOException(L10n.tr("Lost the connection to Hermes.")))
            }
        }
    }

    private fun received(data: ByteArray) {
        if (failure != null || WatchAudioBridgeWire.notice(data) != null) return
        val stream = stream ?: return
        val (kind, plain) = try {
            stream.open(data)
        } catch (e: Exception) {
            onNote("unopened", mapOf("error" to e.message, "bytes" to data.size))
            return
        }
        when (kind) {
            WatchAudioBridgeWire.Kind.CONTROL -> WatchAudioBridgeWire.control(plain)?.let(::handle)
            WatchAudioBridgeWire.Kind.EVENT -> inbox.trySend(plain.toString(Charsets.UTF_8))
            // As xAI sent it, before the host took it out to pace it.
            WatchAudioBridgeWire.Kind.AUDIO -> inbox.trySend(buildJsonObject {
                put("type", "response.output_audio.delta")
                put("delta", Base64.getEncoder().encodeToString(plain))
            }.toString())
        }
    }

    private fun handle(control: WatchAudioBridgeWire.Control) {
        when (control) {
            is WatchAudioBridgeWire.Control.StartedControl -> {
                val info = control.started
                onNote("started", mapOf("inputRate" to info.inputRate, "outputRate" to info.outputRate, "voice" to info.voice))
                // Other rates would play at the wrong speed and send mis-framed audio.
                if (info.inputRate != RATE || info.outputRate != RATE) {
                    refusal = LiveServerClose(4400, L10n.tr("Hermes sends Grok's audio in a format this Watch build can't play. Update Conduit and the conduit_push plugin together."))
                    fail(IOException(L10n.tr("Lost the connection to Hermes.")))
                    return
                }
                // A host that started another engine on this stream isn't talking to Grok.
                if (info.engine.isNotEmpty() && info.engine != WatchAudioBridgeWire.GROK) {
                    refusal = LiveServerClose(4400, L10n.tr("Hermes started %s, not Grok. Update Conduit and the conduit_push plugin together.", info.engine))
                    fail(IOException(L10n.tr("Lost the connection to Hermes.")))
                    return
                }
                started.complete(Unit)
            }
            is WatchAudioBridgeWire.Control.Ended -> {
                onNote("ended", mapOf("reason" to control.reason))
                fail(IOException(L10n.tr("Lost the connection to Hermes.")))
            }
            is WatchAudioBridgeWire.Control.Error -> {
                onNote("hostError", mapOf("code" to control.code, "message" to control.message.take(200)))
                // Worth another try when xAI or the host dropped; otherwise the host or xAI refused what a retry would send again.
                if (control.code != "unreachable" && control.code != "failed") refusal = LiveServerClose(4403, control.message)
                fail(IOException(L10n.tr("Lost the connection to Hermes.")))
            }
        }
    }

    private fun fail(error: IOException) {
        if (failure != null) return
        failure = error
        socket?.close()
        started.completeExceptionally(error)
        inbox.close(error)
    }

    companion object {
        /** How long the host may take to start xAI's session: this, not the session's setup timeout, bounds a bridge's first connect. */
        const val START_WAIT_MS = 20_000L
        /** The rate the host's Grok runs at (GROK_WATCH_RATE). */
        const val RATE = GrokLiveProtocol.INPUT_SAMPLE_RATE
        /** The relay's close code for a grant that ended. */
        const val GRANT_ENDED = 4010
    }
}
