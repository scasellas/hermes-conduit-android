package app.hermesconduit.android.voice

import app.hermesconduit.android.core.live.GeminiLiveAvailability
import app.hermesconduit.android.core.live.GeminiLiveProtocol
import app.hermesconduit.android.core.live.GeminiLiveSession
import app.hermesconduit.android.core.live.GeminiLiveToken
import app.hermesconduit.android.core.live.GeminiLiveTokenProviding
import app.hermesconduit.android.core.live.LiveSessionState
import app.hermesconduit.android.core.live.LiveSocket
import app.hermesconduit.android.core.live.LiveVoiceClientMessage
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.obj
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeLiveSocket : LiveSocket {
    val sent = mutableListOf<String>()
    val incoming = Channel<String>(Channel.UNLIMITED)
    var closed = false

    override suspend fun send(text: String) {
        if (closed) throw IOException("closed")
        sent += text
    }
    override suspend fun receive(): String = incoming.receiveCatching().getOrNull() ?: throw IOException("closed")
    override fun close() {
        closed = true
        incoming.close()
    }
}

class GeminiLiveSessionTest {
    private class Tokens : GeminiLiveTokenProviding {
        var minted = 0
        override suspend fun availability() = GeminiLiveAvailability.Available("model")
        override suspend fun freshToken(): GeminiLiveToken {
            minted++
            return GeminiLiveToken("tok-$minted", null, null, "models/test", "wss://example.invalid/live")
        }
    }

    @Test
    fun sendsSetupWithTheTokenAndIsReadyOnSetupComplete() = runTest {
        val tokens = Tokens()
        val sockets = mutableListOf<Pair<String, FakeLiveSocket>>()
        val states = mutableListOf<LiveSessionState>()
        val events = mutableListOf<GeminiLiveProtocol.ServerEvent>()
        val session = GeminiLiveSession(
            scope = backgroundScope,
            tokens = tokens,
            systemInstruction = "be brief",
            functions = emptyList(),
            voice = "Kore",
            openSocket = { url -> FakeLiveSocket().also { sockets += url to it } },
            reconnectDelay = {},
        )
        session.onStateChange = { states += it }
        session.onEvent = { events += it }
        session.start()
        runCurrent()
        assertEquals(1, sockets.size)
        assertTrue("the single-use token rides on the URL", sockets[0].first.contains("tok-1"))
        val setup = ConduitJson.parseToJsonElement(sockets[0].second.sent.first()).obj!!
        assertTrue(setup.containsKey("setup"))
        assertEquals(LiveSessionState.Connecting, session.state)

        sockets[0].second.incoming.send("""{"setupComplete":{}}""")
        runCurrent()
        assertEquals(LiveSessionState.Ready, session.state)
        assertTrue(session.isReady)

        var sent = false
        session.send(LiveVoiceClientMessage.TextTurn("hello"), onSent = { sent = true }, onFailure = {})
        runCurrent()
        assertTrue(sent)
        assertTrue(sockets[0].second.sent.last().contains("\"clientContent\""))

        sockets[0].second.incoming.send("""{"serverContent":{"outputTranscription":{"text":"Hi"}}}""")
        runCurrent()
        assertEquals(GeminiLiveProtocol.ServerEvent.OutputTranscription("Hi"), events.last())

        session.stop()
        runCurrent()
        assertTrue(sockets[0].second.closed)
    }
}
