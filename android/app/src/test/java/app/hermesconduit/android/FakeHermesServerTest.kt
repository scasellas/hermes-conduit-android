package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.net.DashboardApi
import app.hermesconduit.android.net.GatewayClient
import app.hermesconduit.android.net.SessionCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Drives the REST + JSON-RPC clients against a scripted stand-in for the
 * Hermes dashboard, covering sign-in, the ticketed socket and one streamed turn.
 */
class FakeHermesServerTest {
    private val server = MockWebServer()
    private val rpcLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val cookie = request.getHeader("Cookie").orEmpty()
                return when {
                    path == "/api/auth/providers" -> json("""{"providers":[{"name":"basic","supports_password":true}]}""")
                    path == "/auth/password-login" -> {
                        val body = ConduitJson.parseToJsonElement(request.body.readUtf8()).obj!!
                        if (body["password"].str == "right") {
                            json("{}").addHeader("Set-Cookie", "hermes_session=abc; Path=/; HttpOnly")
                        } else {
                            MockResponse().setResponseCode(401)
                        }
                    }
                    "hermes_session=abc" !in cookie && path.startsWith("/api/") && path != "/api/ws" ->
                        MockResponse().setResponseCode(401)
                    path == "/api/auth/ws-ticket" -> json("""{"ticket":"t-1"}""")
                    path == "/api/profiles" -> json("""{"profiles":["work",{"name":"lab"}]}""")
                    path == "/api/profiles/sessions" -> json(
                        """{"sessions":[{"session_id":"rt","id":"st-1","title":"First","message_count":2,"last_active":1700000000}],"has_more":false,"total":1}""",
                    )
                    path == "/api/sessions/st-1/messages" -> json(
                        """{"messages":[{"id":1,"role":"user","content":"hi"},{"id":2,"role":"assistant","content":"hello"}],"pagination":{"order":"latest"}}""",
                    )
                    path == "/api/ws" && request.requestUrl!!.queryParameter("ticket") == "t-1" ->
                        MockResponse().withWebSocketUpgrade(gateway)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val gateway = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = ConduitJson.parseToJsonElement(text).obj!!
            val method = frame["method"].str
            val id = frame["id"].str
            if (method == null) return
            rpcLog += "$method ${frame["params"] ?: "{}"}"
            fun reply(result: String) = webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            fun event(type: String, payload: String) =
                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt-new","payload":$payload}}""")
            when (method) {
                "client.capabilities" -> reply("""{"declines_not_shown":true}""")
                "session.create" -> reply("""{"session_id":"rt-new","stored_session_id":"st-new"}""")
                "prompt.submit" -> {
                    reply("""{"status":"streaming"}""")
                    event("message.start", "{}")
                    event("tool.start", """{"name":"terminal","tool_id":"c1","args_text":"ls"}""")
                    event("tool.complete", """{"name":"terminal","tool_id":"c1","output":"a.txt"}""")
                    event("message.delta", """{"text":"Done"}""")
                    event("message.complete", """{"message_id":"m1","content":"Done."}""")
                    event("session.busy", """{"busy":false}""")
                }
                else -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method"}}""")
            }
        }
    }

    private fun api() = DashboardApi(server.url("/").toString(), SessionCookieJar())

    @Test
    fun wrongPasswordIsReported() = runBlocking<Unit> {
        val api = api()
        val error = runCatching { api.passwordLogin("me", "wrong") }.exceptionOrNull()
        assertEquals("Incorrect username or password.", error?.message)
        assertThrows(AuthExpiredException::class.java) { runBlocking { api.webSocketTicket() } }
    }

    @Test
    fun signInListAndStreamATurn() = runBlocking<Unit> {
        val api = api()
        assertTrue(api.authProviders().supportsPassword)
        api.passwordLogin("me", "right")

        assertEquals(listOf("default", "work", "lab"), api.profiles())
        val page = api.sessions("default")
        assertEquals("st-1", page.sessions.single().id)
        assertEquals(false, page.hasMore)
        assertEquals(listOf("hi", "hello"), api.messages("st-1", "default").messages.map { it.content })

        val client = GatewayClient(api.http, scope, profile = "work")
        val ready = scope.launch { client.events.first { it is StreamEvent.GatewayReady } }
        client.connect(api.baseUrl, api.webSocketTicket())
        withTimeout(5_000) { ready.join() }

        val created = client.request("session.create", buildJsonObject { put("cols", 96) }).obj!!
        assertEquals("rt-new", created["session_id"].str)

        val collected = mutableListOf<StreamEvent>()
        val collector = scope.launch {
            client.events.takeWhile { !(it is StreamEvent.Busy && !it.busy) }.toList(collected)
        }
        val submitted = client.request(
            "prompt.submit",
            buildJsonObject {
                put("session_id", "rt-new")
                put("text", "go")
            },
        ).obj!!
        assertEquals("streaming", submitted["status"].str)
        withTimeout(5_000) { collector.join() }

        assertEquals(
            listOf("MessageStart", "ToolStart", "ToolComplete", "MessageDelta", "MessageComplete"),
            collected.map { it::class.simpleName },
        )
        // Capabilities go out unscoped; other calls carry the active profile.
        assertTrue(rpcLog.any { it.startsWith("client.capabilities") && "profile" !in it && "server_requests" in it })
        assertTrue(rpcLog.any { it.startsWith("prompt.submit") && "\"profile\":\"work\"" in it })
        // Authenticated REST calls carried the session cookie.
        assertTrue(requests.filter { it.path!!.startsWith("/api/profiles") }.all { "hermes_session=abc" in it.getHeader("Cookie").orEmpty() })
        client.close()
    }
}
