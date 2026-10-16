package app.hermesconduit.android.net

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.StreamEventParser
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RpcException(val code: Int, message: String, val reason: String? = null) : IOException(message)

/**
 * JSON-RPC 2.0 client for the gateway socket at `/api/ws?ticket=…`.
 *
 * Responses are matched to requests by integer id; `event` notifications are
 * parsed into [StreamEvent]s; server→client requests (string `srq-…` ids)
 * become approval/clarify events or are declined.
 */
class GatewayClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val profile: String,
    private val cloudflare: CloudflareAccess? = null,
    private val customHeaders: Map<String, String> = emptyMap(),
) {
    enum class State { CONNECTING, CONNECTED, CLOSED }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private var socket: WebSocket? = null
    private var keepAlive: Job? = null
    @Volatile private var lastInboundMs = System.currentTimeMillis()

    /** The `client.capabilities` reply, once the gateway is ready. */
    @Volatile var capabilities: JsonObject? = null
        private set

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<StreamEvent> = _events

    /** Opens the socket; suspends until it is open or fails (15 s). */
    suspend fun connect(baseUrl: String, ticket: String) {
        val opened = CompletableDeferred<Unit>()
        val url = ConnectionUrlPolicy.webSocketUrl(baseUrl, "api/ws", mapOf("ticket" to ticket))
        val builder = Request.Builder().url(url).header("X-Conduit-Client", "conduit")
        if (url.startsWith("wss://")) {
            cloudflare?.apply(builder)
            CustomHeaders.apply(builder, customHeaders)
        }
        socket = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.CONNECTED
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = System.currentTimeMillis()
                handleFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(IOException("Connection closed ($code)."))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = if (response?.code == 401 || response?.code == 403) AuthExpiredException() else t
                opened.completeExceptionally(error)
                closed(error)
            }
        })
        try {
            withTimeout(OPEN_TIMEOUT_MS) { opened.await() }
        } catch (e: Exception) {
            close()
            throw if (e is kotlinx.coroutines.TimeoutCancellationException) IOException("The gateway didn't answer.") else e
        }
        startKeepAlive()
    }

    fun close() {
        keepAlive?.cancel()
        socket?.close(1000, null)
        closed(IOException("Disconnected."))
    }

    private fun closed(error: Throwable) {
        if (_state.value == State.CLOSED) return
        _state.value = State.CLOSED
        keepAlive?.cancel()
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = timeoutFor(method)): JsonElement {
        val id = nextId.getAndIncrement()
        val scoped = if (method in UNSCOPED || params.containsKey("profile") || profile == "default") params
        else JsonObject(params + ("profile" to JsonPrimitive(profile)))
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (scoped.isNotEmpty()) put("params", scoped)
        }
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val sent = socket?.send(frame.toString()) == true
        if (!sent) {
            pending.remove(id)
            throw IOException("Not connected to the gateway.")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Answers a server→client request (e.g. a single clarify question). */
    fun respond(id: String, result: JsonElement) {
        socket?.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString())
    }

    private fun respondError(id: String, code: Int, message: String) {
        socket?.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString())
    }

    private fun handleFrame(text: String) {
        val frame = runCatching { ConduitJson.parseToJsonElement(text).obj }.getOrNull() ?: return
        val method = frame["method"].str
        val idElement = frame["id"]
        when {
            method == "event" -> {
                val event = StreamEventParser.parse(frame["params"]) ?: return
                if (event is StreamEvent.GatewayReady) {
                    scope.launch {
                        runCatching { request("client.capabilities", buildJsonObject { put("server_requests", true) }) }
                            .onSuccess { capabilities = it.obj }
                    }
                }
                _events.tryEmit(event)
            }
            method != null && idElement is JsonPrimitive && idElement.isString -> handleServerRequest(idElement.content, method, frame["params"].obj)
            idElement != null && idElement !is JsonNull -> {
                val id = idElement.long?.toInt() ?: return
                val deferred = pending.remove(id) ?: return
                val error = frame["error"].obj
                if (error != null) {
                    deferred.completeExceptionally(
                        RpcException(
                            code = error["code"].long?.toInt() ?: 0,
                            message = error["message"].str ?: "The gateway reported an error.",
                            reason = error["data"].obj?.get("reason").str,
                        ),
                    )
                } else {
                    deferred.complete(frame["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun handleServerRequest(id: String, method: String, params: JsonObject?) {
        val sessionId = params?.get("session_id").str.orEmpty()
        when (method) {
            "approval" -> {
                // Not answered with a frame: approval.respond resolves it and
                // the gateway withdraws it with request.cancel.
                val approval = params?.let { MessageNormalizer.approvalRequest(it, sessionId) } ?: return
                _events.tryEmit(StreamEvent.Approval(approval.sessionId, approval))
            }
            "clarify" -> {
                val clarify = params?.let { MessageNormalizer.clarifyRequest(it, sessionId, serverRequestId = id) }
                if (clarify == null) respondError(id, -32602, "unreadable clarify request")
                else _events.tryEmit(StreamEvent.Clarify(sessionId, clarify))
            }
            "sudo", "secret", "vault.unlock_prompt" -> {
                val prompt = MessageNormalizer.inputPrompt(id, method, params)
                if (prompt == null) respond(id, buildJsonObject { put("value", "") })
                else _events.tryEmit(StreamEvent.InputPromptRequest(prompt.sessionId, prompt))
            }
            "preview.read", "preview.act", "terminal.read", "window.read", "tour" ->
                // Window-owned requests: a gateway that understands "not shown" gets 4404.
                if (capabilities?.get("declines_not_shown").let { it is JsonPrimitive && it.content == "true" }) {
                    respondError(id, 4404, "not shown")
                } else {
                    respondError(id, -32601, "no handler for server request: $method")
                }
            else -> respondError(id, -32601, "no handler for server request: $method")
        }
    }

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            var failures = 0
            while (isActive && _state.value == State.CONNECTED) {
                delay(PING_IDLE_MS / 3)
                if (System.currentTimeMillis() - lastInboundMs < PING_IDLE_MS) continue
                val alive = try {
                    request("ping", timeoutMs = PING_TIMEOUT_MS)
                    true
                } catch (e: RpcException) {
                    e.code == -32601
                } catch (_: Exception) {
                    false
                }
                failures = if (alive) 0 else failures + 1
                if (failures >= 2) {
                    socket?.cancel()
                    closed(IOException("The gateway stopped responding."))
                }
            }
        }
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val PING_IDLE_MS = 15_000L
        private const val PING_TIMEOUT_MS = 10_000L
        private val UNSCOPED = setOf("client.capabilities", "ping", "profiles.list")

        fun timeoutFor(method: String): Long = when (method) {
            "prompt.submit" -> 180_000
            "session.compress" -> 660_000
            "llm.oneshot" -> 90_000
            "file.attach" -> 120_000
            else -> 30_000
        }
    }
}
