package app.hermesconduit.android.core.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

/** A live session's WebSocket, as Gemini Live and Grok Live use it. A seam so sessions run against fakes in tests. */
interface LiveSocket {
    /** Throws when the socket can't take it. */
    suspend fun send(text: String)
    /** The next frame's payload (binary frames as UTF-8). Throws when the socket closes. */
    suspend fun receive(): String
    fun close()
    /**
     * How the server closed the socket, waiting up to `withinMs` for it to be
     * reported (it can land after `receive()` throws). Null when the
     * connection simply dropped.
     */
    suspend fun serverClose(withinMs: Long): LiveServerClose? = null
}

/** [LiveSocket] on OkHttp: frames go into a channel the session reads. */
class OkHttpLiveSocket(client: OkHttpClient, request: LiveSocketRequest) : LiveSocket {
    private val frames = Channel<String>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<LiveServerClose?>()
    @Volatile private var failed: Throwable? = null
    private val socket: WebSocket

    init {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.trySend(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.trySend(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(LiveServerClose(code, reason.trim()))
                webSocket.close(1000, null)
                frames.close(IOException("closed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(LiveServerClose(code, reason.trim()))
                frames.close(IOException("closed"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed = t
                // The upgrade itself was refused.
                val status = response?.code
                closed.complete(if (status != null && status != 101) LiveServerClose(status, "", isHttpStatus = true) else null)
                frames.close(t)
            }
        })
    }

    override suspend fun send(text: String) {
        failed?.let { throw IOException(it) }
        if (!socket.send(text)) throw IOException("The live connection is closed.")
    }

    override suspend fun receive(): String = frames.receive()

    override fun close() {
        socket.close(1000, null)
        frames.close()
    }

    override suspend fun serverClose(withinMs: Long): LiveServerClose? {
        if (closed.isCompleted) return closed.getCompleted()
        return withTimeoutOrNull(withinMs) { closed.await() }
    }
}

/** The reconnect backoff both sessions use: 1, 2, 4, 8, 8… seconds. */
val liveReconnectDelay: suspend (Int) -> Unit = { attempt -> delay(minOf(8, 1 shl attempt) * 1_000L) }
