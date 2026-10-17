package app.hermesconduit.android.integration

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted stand-in for the Hermes dashboard: the REST API (password sign-in,
 * profiles, sessions, history) and the JSON-RPC gateway socket at `/api/ws`.
 * Every RPC is logged; tests push gateway events with [event] and override
 * answers with [answer].
 */
class FakeHermes : Closeable {
    data class Rpc(val method: String, val params: JsonObject)

    val server = MockWebServer()
    val rpcs: MutableList<Rpc> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** The chat list `api/profiles/sessions` returns. */
    @Volatile var sessionsJson = """[{"session_id":"rt-st-1","id":"st-1","title":"First chat","message_count":2,"last_active":1700000000,"preview":"hello"}]"""

    /** Each stored chat's history (`api/sessions/<id>/messages`). */
    val histories = mutableMapOf(
        "st-1" to """[{"id":1,"role":"user","content":"What is on the list?"},{"id":2,"role":"assistant","content":"Milk and eggs."}]""",
    )

    /** Gateway events a `prompt.submit` streams back: (type, payload) pairs. */
    @Volatile var turn: (sessionId: String, text: String) -> List<Pair<String, String>> = { _, text ->
        listOf(
            "message.start" to "{}",
            "message.delta" to """{"text":"Echo: "}""",
            "message.complete" to """{"message_id":"m-reply","content":"Echo: $text"}""",
            "session.busy" to """{"busy":false}""",
        )
    }

    /** Custom JSON-RPC results by method (the raw `result` JSON), checked before the defaults. */
    val answers = mutableMapOf<String, (JsonObject) -> String?>()

    /** Custom REST responses by path, checked before the defaults. */
    val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    fun start(): FakeHermes {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        return this
    }

    override fun close() {
        sockets.forEach { it.close(1000, null) }
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun route(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.requestUrl!!.encodedPath
        val cookie = request.getHeader("Cookie").orEmpty()
        routes[path]?.let { return it(request) }
        return when {
            path == "/api/auth/providers" -> json("""{"providers":[{"name":"basic","supports_password":true}]}""")
            path == "/api/status" -> json("""{"version":"0.0-test"}""")
            path == "/auth/password-login" -> {
                val body = ConduitJson.parseToJsonElement(request.body.readUtf8()).obj!!
                if (body["password"].str == PASSWORD) {
                    json("{}").addHeader("Set-Cookie", "hermes_session=abc; Path=/; HttpOnly")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
            "hermes_session=abc" !in cookie && path.startsWith("/api/") && path != "/api/ws" -> MockResponse().setResponseCode(401)
            path == "/api/auth/ws-ticket" -> json("""{"ticket":"t-1"}""")
            path == "/api/profiles" -> json("""{"profiles":["work"]}""")
            path == "/api/profiles/sessions" -> json("""{"sessions":$sessionsJson,"has_more":false,"total":1}""")
            path.startsWith("/api/sessions/") && path.endsWith("/messages") -> {
                val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                json("""{"messages":${histories[id] ?: "[]"},"pagination":{"order":"latest"}}""")
            }
            path == "/api/ws" && request.requestUrl!!.queryParameter("ticket") == "t-1" -> MockResponse().withWebSocketUpgrade(gateway)
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** Pushes a gateway `event` notification to every open socket. */
    fun event(type: String, sessionId: String, payload: String = "{}") {
        val frame = """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sessionId","payload":$payload}}"""
        sockets.forEach { it.send(frame) }
    }

    val connected: Boolean get() = sockets.isNotEmpty()

    fun calls(method: String): List<Rpc> = synchronized(rpcs) { rpcs.filter { it.method == method } }

    private val gateway = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            sockets -= webSocket
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = ConduitJson.parseToJsonElement(text).obj!!
            val method = frame["method"].str ?: return
            val id = frame["id"].toString()
            val params = frame["params"].obj ?: JsonObject(emptyMap())
            rpcs += Rpc(method, params)
            fun reply(result: String) = webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            answers[method]?.invoke(params)?.let { reply(it); return }
            when (method) {
                "client.capabilities" -> reply("""{"declines_not_shown":true}""")
                "session.create" -> reply("""{"session_id":"rt-new","stored_session_id":"st-new"}""")
                "session.resume" -> reply("""{"session_id":"rt-${params["session_id"].str}","running":false,"info":{"model":"test-model"}}""")
                "session.active_list" -> reply("""{"sessions":[]}""")
                "approval.pending" -> reply("""{"approvals":[]}""")
                "approval.respond" -> reply("""{"ok":true}""")
                "prompt.submit" -> {
                    val session = params["session_id"].str.orEmpty()
                    reply("""{"status":"streaming"}""")
                    turn(session, params["text"].str.orEmpty()).forEach { (type, payload) ->
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","payload":$payload}}""")
                    }
                }
                else -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method"}}""")
            }
        }
    }

    companion object {
        const val USER = "me"
        const val PASSWORD = "right"
    }
}
