package app.hermesconduit.android

import app.hermesconduit.android.integration.FakeHermes
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the REST + JSON-RPC clients against [FakeHermes], the scripted
 * stand-in for the Hermes dashboard the Robolectric tests share, covering
 * sign-in, the ticketed socket and one streamed turn.
 */
class FakeHermesServerTest {
    private val fake = FakeHermes()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        fake.histories["st-1"] = """[{"id":1,"role":"user","content":"hi"},{"id":2,"role":"assistant","content":"hello"}]"""
        fake.turn = { _, _ ->
            listOf(
                "message.start" to "{}",
                "tool.start" to """{"name":"terminal","tool_id":"c1","args_text":"ls"}""",
                "tool.complete" to """{"name":"terminal","tool_id":"c1","output":"a.txt"}""",
                "message.delta" to """{"text":"Done"}""",
                "message.complete" to """{"message_id":"m1","content":"Done."}""",
                "session.busy" to """{"busy":false}""",
            )
        }
        fake.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun api() = DashboardApi(fake.baseUrl + "/", SessionCookieJar())

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
        assertTrue(fake.calls("client.capabilities").any { "profile" !in it.params && "server_requests" in it.params })
        assertTrue(fake.calls("prompt.submit").any { it.params["profile"].str == "work" })
        // Authenticated REST calls carried the session cookie.
        assertTrue(fake.requests.filter { it.path!!.startsWith("/api/profiles") }.all { "hermes_session=abc" in it.getHeader("Cookie").orEmpty() })
        client.close()
    }
}
