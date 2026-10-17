package app.hermesconduit.android

import app.hermesconduit.android.core.live.GeminiLiveMemoryRecalling
import app.hermesconduit.android.core.live.GeminiLiveProtocol
import app.hermesconduit.android.core.live.GeminiLiveToken
import app.hermesconduit.android.core.live.GeminiLiveTokenClient
import app.hermesconduit.android.core.live.GeminiLiveWebResult
import app.hermesconduit.android.core.live.GeminiLiveWebSearching
import app.hermesconduit.android.model.VoiceJobProfileTarget
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobBackend
import app.hermesconduit.android.ui.voice.VoiceBackgroundJobSupervisor
import app.hermesconduit.android.ui.voice.live.GeminiLiveToolBridge
import app.hermesconduit.android.watch.WatchDirectBroker
import app.hermesconduit.android.watch.WatchDirectCallLedger
import app.hermesconduit.android.watch.WatchDirectConnection
import app.hermesconduit.android.watch.WatchJobSettings
import app.hermesconduit.android.watch.WatchToolGrantClient
import app.hermesconduit.wearwire.PhoneContext
import app.hermesconduit.wearwire.VoiceCommands
import app.hermesconduit.wearwire.WatchAudioBridgeStream
import app.hermesconduit.wearwire.WatchAudioBridgeWire
import app.hermesconduit.wearwire.WatchBridgeDelegation
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchDirectTokens
import app.hermesconduit.wearwire.WatchDirectWire
import app.hermesconduit.wearwire.WatchJobAnswer
import app.hermesconduit.wearwire.WatchJobProfiles
import app.hermesconduit.wearwire.WatchLiveToken
import app.hermesconduit.wearwire.WatchRejoin
import app.hermesconduit.wearwire.WatchStall
import app.hermesconduit.wearwire.WatchToolAnswer
import app.hermesconduit.wearwire.WatchToolRelayClient
import app.hermesconduit.wearwire.WatchToolSeal
import app.hermesconduit.wearwire.WatchVoiceActivity
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WatchVoiceWire.DirectOutgoing
import app.hermesconduit.wearwire.WatchVoiceWire.Message
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

/** The watch voice wire: Swift Codable JSON, both directions. */
class WatchVoiceWireTest {
    private val grant = WatchVoiceWire.DirectToolGrant(
        grantId = "G".repeat(22), relayUrl = "https://relay.example.test", key = WatchToolSeal.base64Url(ByteArray(32) { it.toByte() }),
        watchKey = "d2F0Y2gta2V5", expiresAtMs = 1_800_000_000_000, tools = listOf("web_search", "start_job"), maxCalls = 40, maxJobs = 5,
        voiceApprovals = true, audio = WatchVoiceWire.AudioBridge("wss://relay.example.test/v1/watch-audio/G/watch", 1, listOf("gpt_live")),
        jobProfiles = listOf(WatchVoiceWire.JobProfileName(null, listOf("default")), WatchVoiceWire.JobProfileName("fam", listOf("Fam"))),
        hostJobProfiles = listOf("fam"),
    )

    @Test
    fun messagesAreSwiftCodableEnumCases() {
        assertEquals(obj("""{"directStart":{"callID":7,"version":2}}"""), obj(WatchVoiceWire.encode(Message.DirectStart(7, 2)).decodeToString()))
        assertEquals(obj("""{"bridgeStart":{"callID":4294967295,"version":2,"engine":"gpt_live","ring":"r1"}}"""),
            obj(WatchVoiceWire.encode(Message.BridgeStart(4_294_967_295, 2, "gpt_live", "r1")).decodeToString()))
        assertEquals(obj("""{"directToken":{"callID":1}}"""), obj(WatchVoiceWire.encode(Message.DirectTokenRequest(1)).decodeToString()))
        assertEquals(obj("""{"textWhenIdle":{"_0":"hi"}}"""), DirectOutgoing.TextWhenIdle("hi").json())
        assertEquals(obj("""{"endConversation":{}}"""), DirectOutgoing.EndConversation.json())
        // A missing optional is left out, as Swift's synthesized encoder does.
        assertEquals(obj("""{"toolResponse":{"id":"c1","name":"web_search","result":{"results":"x"},"scheduling":"WHEN_IDLE"}}"""),
            DirectOutgoing.ToolResponse("c1", "web_search", mapOf("results" to "x"), "WHEN_IDLE", null).json())
    }

    @Test
    fun aMessageFromTheIPhoneShapeDecodes() {
        // As Swift's JSONEncoder writes it: Data as base64, Date as seconds since 2001, escaped slashes.
        val text = """{"directGrantIssued":{"callID":9,"grant":{"grantID":"GGGGGGGGGGGGGGGGGGGGGG","relayURL":"https:\/\/relay.example.test","key":"AAEC","watchKey":"d2s","expiresAt":800000000.5,"tools":["web_search"],"maxCalls":20}}}"""
        val message = WatchVoiceWire.decode(text.toByteArray()) as Message.DirectGrantIssued
        assertEquals(9L, message.callId)
        assertEquals("https://relay.example.test", message.grant.relayUrl)
        assertEquals((800_000_000.5 + 978_307_200) * 1000, message.grant.expiresAtMs!!.toDouble(), 1.0)
        assertNull(message.grant.maxJobs)
        assertNull(WatchVoiceWire.decode("""{"callsToken":{"token":"x"}}""".toByteArray()))
        assertNull(WatchVoiceWire.decode("not json".toByteArray()))
    }

    @Test
    fun everyMessageRoundTrips() {
        val transcript = WatchVoiceWire.DirectTranscript(
            "uuid", 1_700_000_000_000, 1_700_000_060_000,
            listOf(WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.USER, "hello", 1_700_000_001_000)),
            engine = "grok", jobs = listOf(WatchVoiceWire.DirectJob("j1", "Check", "s1", 1_700_000_002_000)),
        )
        val (setup, bytes) = WatchDirectWire.DirectSetup.of("Be brief.", GeminiLiveToolBridge.functionDeclarations).compressed()
        val token = WatchVoiceWire.DirectToken("tok", 1_700_000_100_000, null, "models/gemini", "wss://host/ws")
        val messages = listOf(
            Message.DirectStart(1, 2, "ring"), Message.DirectTokenRequest(1),
            Message.DirectTool(1, WatchVoiceWire.DirectToolCall("c", "start_job", mapOf("instructions" to "x"))),
            Message.DirectToolCancel(1, listOf("a", "b")), Message.DirectPoll(1), Message.DirectEnd(1, transcript),
            Message.DirectGrant(1, "old"), Message.BridgeStart(1, 2, "gpt_live"), Message.GrokStart(1, 2),
            Message.CallRefused(1, "no"),
            Message.DirectSessionIssued(1, WatchVoiceWire.DirectSession(token, setup, bytes, true, "Puck", null, grant, listOf("bye"))),
            Message.DirectTokenIssued(1, token),
            Message.DirectToolResultIssued(1, WatchVoiceWire.DirectToolResult(listOf(DirectOutgoing.ContextWhenIdle("c"), DirectOutgoing.EndConversation), 2)),
            Message.DirectGrantIssued(1, grant),
            Message.BridgeSessionIssued(1, WatchVoiceWire.BridgeSession("gpt_live", grant, WatchVoiceWire.BridgeSession.pack("Rules").first, 5, null, "cove", null, "Opening")),
            Message.GrokSessionIssued(1, WatchVoiceWire.GrokSession(setup, bytes, null, "Hi", grant)),
        )
        for (message in messages) {
            val decoded = WatchVoiceWire.decode(WatchVoiceWire.encode(message))
            assertEquals(message.javaClass.simpleName, message.json(), decoded?.json())
        }
    }

    @Test
    fun theSetupPacksAsRawDeflateAndKeepsItsDeclarations() {
        val functions = GeminiLiveToolBridge.watchDeclarations(webSearch = true, memoryRecall = true, chats = true) + WatchJobAnswer.answerApprovalDeclaration
        val (packed, bytes) = WatchDirectWire.DirectSetup.of("instructions", functions).compressed()
        assertTrue(packed.size < bytes)
        val setup = WatchDirectWire.DirectSetup.fromCompressed(packed)!!
        assertEquals("instructions", setup.systemInstruction)
        assertEquals(functions, setup.declarations)
        // Each declaration's JSON has sorted keys, as the iPhone writes it.
        assertTrue(setup.functions.first().decodeToString().startsWith("{\"behavior\":"))
        // NSData's .zlib is raw DEFLATE: no zlib header.
        assertNotEquals(0x78, packed[0].toInt() and 0xff)
        assertNull(WatchDirectWire.DirectSetup.fromCompressed(byteArrayOf(1, 2, 3)))
        assertEquals("Rules", WatchVoiceWire.BridgeSession("gpt_live", grant, WatchVoiceWire.BridgeSession.pack("Rules").first, 5, null, null).briefingText)
    }

    @Test
    fun aLongTranscriptKeepsItsNewestTurns() {
        val turns = (1..5).map { WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.USER, "x".repeat(10), it.toLong()) }
        assertEquals(listOf(4L, 5L), WatchVoiceWire.DirectTranscript.capped(turns, bytes = 25).map { it.atMs })
        val long = listOf(WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.ASSISTANT, "日本語テキスト", 1))
        assertEquals("日本", WatchVoiceWire.DirectTranscript.capped(long, bytes = 7).single().text)
    }

    @Test
    fun theJobLogKeepsEachJobOnceAndLearnsItsChat() {
        val log = WatchVoiceWire.DirectJobLog()
        log.started("j1", "", 1)
        log.started("j1", "Again", 2)
        log.heard("j1", "Real title", "s1", 3)
        log.heard("j2", "News only", null, 4)
        assertEquals(listOf(WatchVoiceWire.DirectJob("j1", "Real title", "s1", 1), WatchVoiceWire.DirectJob("j2", "News only", null, 4)), log.jobs)
    }

    @Test
    fun thePhoneContextCarriesEachVoicesCheck() {
        val context = PhoneContext(true, true, false, "default", voices = mapOf(WatchCallVoices.GPT_LIVE to false, WatchCallVoices.GEMINI_LIVE to true))
        val decoded = PhoneContext.decode(context.encoded())!!
        assertTrue(decoded.notSetUp(WatchCallVoices.GPT_LIVE))
        assertFalse(decoded.notSetUp(WatchCallVoices.GEMINI_LIVE))
        assertFalse(decoded.notSetUp(WatchCallVoices.GROK_LIVE))
        val voices = WatchCallVoices().noted(WatchCallVoices.GROK_LIVE, false, "a").noted(WatchCallVoices.GEMINI_LIVE, true, "a")
        assertEquals(mapOf(WatchCallVoices.GROK_LIVE to false, WatchCallVoices.GEMINI_LIVE to true), WatchCallVoices.decode(voices.encoded()).ready)
        // Another dashboard's or profile's checks say nothing about this one.
        assertEquals(mapOf(WatchCallVoices.GPT_LIVE to true), voices.noted(WatchCallVoices.GPT_LIVE, true, "b").ready)
    }
}

/** The grant's sealing: the host plugin's own test vectors (conduit_push tests/test_watch_tools.py, test_watch_audio.py). */
class WatchSealTest {
    private val grantId = "G".repeat(22)

    @Test
    fun watchToolSealMatchesTheHostPluginsVectors() {
        val keys = WatchToolSeal.Keys.of(ByteArray(32) { it.toByte() })!!
        assertEquals("76bee458fa181a0da7695798b6de8a0be9bf13587ecac450ff389d0094b5bbdf", hex(keys.call))
        assertEquals("06499568025534ab010493728680fd7bc9216867d0012198cc6d6a9e450fc703", hex(keys.result))
        val rid = "R".repeat(22)
        val call = WatchToolSeal.json(buildJsonObject {
            put("tool", "web_search")
            put("args", buildJsonObject { put("query", "weather in Tokyo") })
        })
        assertEquals("""{"args":{"query":"weather in Tokyo"},"tool":"web_search"}""", call.decodeToString())
        val sealed = WatchToolSeal.seal(call, keys, WatchToolSeal.Direction.CALL, grantId, rid, nonce = ByteArray(12) { it.toByte() })
        assertEquals(WatchToolSeal.Sealed("AAECAwQFBgcICQoL", "usQw1Mc1f6DtZfIgf1Dd3VKsJq3JjVCOQUQYKH3nr0mk-Lb0ZEN1IoBA1LY1CEkYy8BAT4aGVN28rrFCyuXKvKmk7un4LOPnaA"), sealed)

        val answer = WatchToolSeal.Sealed(
            "AAECAwQFBgcICQoL",
            "Mmf4pRBwr3HNiVGcy-x-KIXRml6fz7xmr32vPgunMfFVqBvDAEImsfwkDEGZP28NtM89wF7XhlAhL99eH7KBpO0cVQc4roUey5BFrzA84ybx4K1PD28aY05sabPmNAQBqZoEkZR-bA1IdfVrmkLNhhbS7Qi1TfDsHIIgVOxQ1PVG2ohffj1meUdS-EskwQTMUW_iivE2GQ",
        )
        val body = obj(WatchToolSeal.open(answer, keys, WatchToolSeal.Direction.RESULT, grantId, rid).decodeToString())
        assertEquals(
            mapOf("results" to "1. Tokyo weather: Sunny, 21°C (https://example.com/tokyo)", "note" to WatchToolAnswer.LOOKUP_ANSWER_NOTE),
            WatchToolAnswer.result("web_search", body),
        )
        // Bound to its direction, grant and call: anything else is refused.
        assertSealFails(WatchToolSeal.Failure.Kind.DID_NOT_VERIFY) { WatchToolSeal.open(answer, keys, WatchToolSeal.Direction.CALL, grantId, rid) }
        assertSealFails(WatchToolSeal.Failure.Kind.DID_NOT_VERIFY) { WatchToolSeal.open(answer, keys, WatchToolSeal.Direction.RESULT, grantId, "S".repeat(22)) }
        assertSealFails(WatchToolSeal.Failure.Kind.DID_NOT_VERIFY) { WatchToolSeal.open(answer, keys, WatchToolSeal.Direction.RESULT, "H".repeat(22), rid) }
        assertSealFails(WatchToolSeal.Failure.Kind.MALFORMED) { WatchToolSeal.open(answer.copy(n = "AAEC"), keys, WatchToolSeal.Direction.RESULT, grantId, rid) }
        assertNull(WatchToolSeal.Keys.of(ByteArray(31)))
    }

    private fun assertSealFails(kind: WatchToolSeal.Failure.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: WatchToolSeal.Failure) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun base64UrlRefusesOtherAlphabets() {
        assertEquals("-__-", WatchToolSeal.base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte())))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte()), WatchToolSeal.data("-__-"))
        assertArrayEquals(byteArrayOf(1), WatchToolSeal.data("AQ"))
        assertNull(WatchToolSeal.data("+//+"))
        assertNull(WatchToolSeal.data("AQ=="))
        assertNull(WatchToolSeal.data("AQIDB"))
        assertEquals(22, WatchToolSeal.newRequestId().length)
        assertNotEquals(WatchToolSeal.newRequestId(), WatchToolSeal.newRequestId())
    }

    @Test
    fun watchAudioBridgeSealMatchesTheHostPluginsVectors() {
        val root = ByteArray(32) { it.toByte() }
        val streamId = ByteArray(16) { (100 + it).toByte() }
        val keys = WatchAudioBridgeWire.Keys.of(root, streamId)!!
        assertEquals("a970c88b4c9fad954c1c7c46e50ba2a6cbb65e7cc660cb9c9903aaba94ec21d5", hex(keys.watchToHost))
        assertEquals("bfba0a519d8cc7330fb2c5eaafbada1094ba8906e9c613cdae24ec67a7f5791a", hex(keys.hostToWatch))
        assertEquals(
            "conduit-watch-audio/1\nwatch-to-host\ngrant=GGGGGGGGGGGGGGGGGGGGGG\nsid=ZGVmZ2hpamtsbW5vcHFycw\ntype=3",
            WatchAudioBridgeWire.associatedData(WatchAudioBridgeWire.Direction.WATCH_TO_HOST, grantId, streamId, WatchAudioBridgeWire.Kind.CONTROL).decodeToString(),
        )
        // The watch's first sealed message on a stream: counter 0.
        val stream = WatchAudioBridgeStream.of(grantId, root, streamId)!!
        val start = stream.seal("""{"type":"start","engine":"gpt_live"}""".toByteArray(), WatchAudioBridgeWire.Kind.CONTROL)
        assertEquals("03000000000000000065911b5180de3d861bf69d75d796d56ed8363e51361e991a4f351df10440d35e3239930328e694505b7254359bab106607b55cf2", hex(start))
        assertEquals(1L, stream.sent)
        // The host's audio, counter 1: PCM16LE samples 1 and 2.
        val audio = unhex("01000000000000000163f89b82063f879f7eccbfb0b3f896f105565f24")
        val (kind, plain) = stream.open(audio)
        assertEquals(WatchAudioBridgeWire.Kind.AUDIO, kind)
        assertArrayEquals(shortArrayOf(1, 2), WatchAudioBridgeWire.samples(plain))
        // Once only, and only as the host sealed it.
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.REPLAYED) { stream.open(audio) }
        val fresh = WatchAudioBridgeStream.of(grantId, root, streamId)!!
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.DID_NOT_VERIFY) { fresh.open(audio.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.DID_NOT_VERIFY) { fresh.open(audio.copyOf().also { it[0] = WatchAudioBridgeWire.Kind.CONTROL.raw.toByte() }) }
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.DID_NOT_VERIFY) { fresh.open(start) }
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.DID_NOT_VERIFY) { WatchAudioBridgeStream.of(grantId, root, ByteArray(16))!!.open(audio) }
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.MALFORMED) { fresh.open(audio.copyOf(20)) }
        assertEquals(WatchAudioBridgeWire.Kind.AUDIO, fresh.open(audio).first)
        assertNull(WatchAudioBridgeWire.Keys.of(ByteArray(31), streamId))
        assertNull(WatchAudioBridgeWire.Keys.of(root, ByteArray(15)))
        assertBridgeFails(WatchAudioBridgeWire.Failure.Kind.TOO_LARGE) { stream.seal(ByteArray(WatchAudioBridgeWire.MAX_PLAIN_BYTES + 1), WatchAudioBridgeWire.Kind.AUDIO) }
    }

    private fun assertBridgeFails(kind: WatchAudioBridgeWire.Failure.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: WatchAudioBridgeWire.Failure) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun bridgeFramesAndControlMessages() {
        val streamId = ByteArray(16) { (100 + it).toByte() }
        assertArrayEquals(byteArrayOf(4) + streamId + byteArrayOf(1), WatchAudioBridgeWire.hello(streamId))
        assertEquals(WatchAudioBridgeWire.Notice.WATCH_CONNECTED, WatchAudioBridgeWire.notice(byteArrayOf(0, 1)))
        assertEquals(WatchAudioBridgeWire.Notice.WATCH_GONE, WatchAudioBridgeWire.notice(byteArrayOf(0, 2)))
        assertNull(WatchAudioBridgeWire.notice(byteArrayOf(0, 9)))
        assertNull(WatchAudioBridgeWire.notice(byteArrayOf(1, 1)))
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, -1, 0x7f, 0, 0x80.toByte()), WatchAudioBridgeWire.pcm(samples))
        assertArrayEquals(samples, WatchAudioBridgeWire.samples(WatchAudioBridgeWire.pcm(samples)))

        val first = obj(WatchAudioBridgeWire.start("gpt_live", "cove", "Be brief.", "Say hi.", history = emptyList()).decodeToString())
        assertEquals(obj("""{"type":"start","engine":"gpt_live","voice":"cove","briefing":"Be brief.","greeting":"Say hi."}"""), first)
        val called = obj(WatchAudioBridgeWire.start("gpt_live", null, null, null, callOpening = true, history = emptyList()).decodeToString())
        assertEquals(JsonPrimitive(true), called["call_opening"])
        val rejoin = obj(WatchAudioBridgeWire.start("gpt_live", "", "", null, history = listOf(buildJsonObject { put("role", "user") })).decodeToString())
        assertEquals(setOf("type", "engine", "history"), rejoin.keys)
        assertEquals("""{"type":"end"}""", WatchAudioBridgeWire.end.decodeToString())

        val started = WatchAudioBridgeWire.control("""{"type":"started","engine":"grok","input_rate":24000,"output_rate":24000,"greeting_applied":true}""".toByteArray())
            as WatchAudioBridgeWire.Control.StartedControl
        assertEquals(24_000, started.started.inputRate)
        assertTrue(started.started.greetingApplied)
        assertFalse(WatchAudioBridgeWire.sendsOpeningTurn(rejoining = false, started = started.started))
        assertTrue(WatchAudioBridgeWire.sendsOpeningTurn(rejoining = false, started = started.started.copy(greetingApplied = false)))
        assertFalse(WatchAudioBridgeWire.sendsOpeningTurn(rejoining = true, started = started.started.copy(greetingApplied = false)))
        assertEquals(WatchAudioBridgeWire.Control.Ended("ended"), WatchAudioBridgeWire.control("""{"type":"ended","reason":"ended"}""".toByteArray()))
        assertEquals(WatchAudioBridgeWire.Control.Ended(null), WatchAudioBridgeWire.control("""{"type":"ended"}""".toByteArray()))
        assertEquals(WatchAudioBridgeWire.Control.Error("busy", "Try later"), WatchAudioBridgeWire.control("""{"type":"error","code":"busy","message":"Try later"}""".toByteArray()))
        assertNull(WatchAudioBridgeWire.control("""{"type":"other"}""".toByteArray()))
    }

    @Test
    fun grokAudioGoesAsAudioInWholeSamplePieces() {
        val pcm = ByteArray(10) { it.toByte() }
        val event = """{"type":"input_audio_buffer.append","audio":"${java.util.Base64.getEncoder().encodeToString(pcm)}"}"""
        assertArrayEquals(pcm, WatchAudioBridgeWire.appendedAudio(event))
        assertNull(WatchAudioBridgeWire.appendedAudio("""{"type":"session.update"}"""))
        assertEquals(listOf(4, 4, 2), WatchAudioBridgeWire.audioChunks(pcm, limit = 5).map { it.size })
        assertEquals(1, WatchAudioBridgeWire.audioChunks(pcm).size)
    }
}

/** What a watch call's model hears: lookups, jobs, delegations, rejoins. */
class WatchVoiceAnswersTest {
    private fun backend() = VoiceBackgroundJobBackend(
        createSession = { "rt" to "st" }, setTitle = { _, _ -> }, submit = { _, _ -> }, cancel = { },
        liveSessions = { emptyList() }, resolveProfile = { VoiceJobProfileTarget.Unknown },
    )

    private class Search : GeminiLiveWebSearching, GeminiLiveMemoryRecalling {
        var results: List<GeminiLiveWebResult> = emptyList()
        var recall = ""
        var error: Exception? = null
        override suspend fun webSearch(query: String): List<GeminiLiveWebResult> = error?.let { throw it } ?: results
        override suspend fun recallMemory(query: String): String = error?.let { throw it } ?: recall
    }

    /** A lookup through the relay reaches the model exactly as the same host response would through the phone. */
    @Test
    fun relayedLookupsMatchWhatThePhoneSendsForTheSameHostResponse() = runTest {
        val supervisor = VoiceBackgroundJobSupervisor(backgroundScope, backend(), pollIntervalMs = 3_600_000)
        val search = Search()
        val bridge = GeminiLiveToolBridge(supervisor, search, search, holdsJobCalls = false)
        assertEquals(GeminiLiveTokenClient.WEB_SEARCH_LIMIT, WatchToolAnswer.WEB_SEARCH_LIMIT)
        assertEquals(GeminiLiveTokenClient.MEMORY_RECALL_LIMIT, WatchToolAnswer.MEMORY_RECALL_LIMIT)
        val searchBodies = listOf(
            """{"ok":true,"query":"q","results":[{"title":"Tokyo weather","url":"https://example.com/tokyo","snippet":"Sunny, 21°C"},{"title":"No link","url":"","snippet":"Dropped"},{"url":"https://example.com/bare"}]}""",
            """{"ok":true,"query":"q","results":[]}""",
            """{"ok":false,"status":503,"detail":"No web search provider configured."}""",
            """{"ok":false,"status":500}""",
        ).map(::obj)
        for ((index, body) in searchBodies.withIndex()) {
            try {
                search.results = GeminiLiveTokenClient.webResults(body)
                search.error = null
            } catch (e: Exception) {
                search.results = emptyList()
                search.error = e
            }
            val id = "s$index"
            val phone = bridge.handle(GeminiLiveProtocol.FunctionCall(id, "web_search", mapOf("query" to "  q ")))
            val watch = WatchToolAnswer.outgoing(id, "web_search", WatchToolAnswer.result("web_search", body))
            assertEquals("web_search body $index", listOf(watch), phone.map(WatchDirectBroker::wire))
        }
        val recallBodies = listOf(
            """{"ok":true,"available":true,"results":"  Eric drinks oolong.  "}""",
            """{"ok":true,"available":true,"results":""}""",
            """{"ok":true,"available":true,"results":"${"x".repeat(5_000)}"}""",
            """{"ok":true,"available":false,"reason":"No memory provider is configured."}""",
            """{"ok":false,"status":504,"detail":"Memory recall timed out"}""",
        ).map(::obj)
        for ((index, body) in recallBodies.withIndex()) {
            try {
                search.recall = GeminiLiveTokenClient.memoryRecall(body)
                search.error = null
            } catch (e: Exception) {
                search.recall = ""
                search.error = e
            }
            val id = "m$index"
            val phone = bridge.handle(GeminiLiveProtocol.FunctionCall(id, "recall_memory", mapOf("query" to "tea")))
            val watch = WatchToolAnswer.outgoing(id, "recall_memory", WatchToolAnswer.result("recall_memory", body))
            assertEquals("recall_memory body $index", listOf(watch), phone.map(WatchDirectBroker::wire))
        }
    }

    @Test
    fun chatToolsAnswerAsACallThatCantOpenAChat() {
        val found = WatchToolAnswer.result("find_chat", obj("""{"ok":true,"chats":"1. GPU setup","count":1}"""))
        assertEquals("1. GPU setup", found["chats"])
        assertFalse(found["message"]!!.contains("open_chat"))
        assertEquals("", WatchToolAnswer.result("find_chat", obj("""{"ok":true,"chats":"","count":3}"""))["chats"])
        assertTrue(WatchToolAnswer.result("read_chat", obj("""{"ok":true,"unknown":true}"""))["error"]!!.startsWith("That number isn't one"))
        assertEquals(mapOf("error" to "number is required"), WatchToolAnswer.missingQuery("read_chat"))
        assertEquals("2", WatchToolAnswer.query("read_chat", mapOf("number" to " 2 ")))
        assertTrue(WatchToolAnswer.delegatedChatReply("find_chat", obj("""{"ok":false,"detail":"busy"}""")).contains("busy"))
        assertEquals(obj("""{"tool":"web_search","args":{"query":"q","limit":3}}"""), WatchToolAnswer.request("web_search", "q"))
    }

    @Test
    fun jobAnswersMirrorThePhonesBridge() {
        assertEquals(obj("""{"instructions":"check the router"}"""), WatchJobAnswer.arguments("start_job", mapOf("instructions" to " Quick: check the router ")))
        assertNull(WatchJobAnswer.arguments("start_job", mapOf("instructions" to "  ")))
        assertEquals(JsonObject(emptyMap()), WatchJobAnswer.arguments("cancel_job", emptyMap()))
        assertEquals(obj("""{"job_id":"watch-2","message":"make it Alex"}"""), WatchJobAnswer.arguments("interrupt_job", mapOf("job_id" to " watch-2 ", "message" to " make it Alex ")))
        assertEquals(mapOf("error" to "message is required"), WatchJobAnswer.missingArguments("interrupt_job", mapOf("job_id" to "watch-2")))
        assertEquals(WatchJobAnswer.unknownJob, WatchJobAnswer.missingArguments("interrupt_job", emptyMap()))
        assertEquals(WatchJobAnswer.FollowUp.Interrupted("T"), WatchJobAnswer.FollowUp.of(obj("""{"ok":true,"outcome":"interrupted","title":"T"}""")))
        assertEquals(WatchJobAnswer.FollowUp.UnknownJob, WatchJobAnswer.FollowUp.of(obj("""{"ok":true,"outcome":"unknown_job"}""")))
        assertEquals(WatchJobAnswer.FollowUp.Failed("busy"), WatchJobAnswer.FollowUp.of(obj("""{"ok":true,"outcome":"failed","error":"busy"}""")))
        assertEquals(WatchJobAnswer.FollowUp.Failed("message is required"), WatchJobAnswer.FollowUp.of(obj("""{"ok":false,"detail":"message is required"}""")))
        assertEquals(mapOf("job_id" to "j", "status" to "started", "running" to "2"), WatchJobAnswer.result(obj("""{"ok":true,"job_id":"j","status":"started","running":2,"session_id":"s","more":false}""")))
        assertEquals(mapOf("error" to "nope"), WatchJobAnswer.result(obj("""{"ok":false,"error":"nope"}""")))
        // A start the model already said it's making is taken in silently.
        assertEquals("SILENT", WatchJobAnswer.scheduling("start_job", mapOf("status" to "started"), acknowledged = true))
        assertNull(WatchJobAnswer.scheduling("start_job", mapOf("status" to "started"), acknowledged = false))
        assertEquals("WHEN_IDLE", WatchJobAnswer.scheduling("start_job", mapOf("status" to "not_started"), acknowledged = true))
        assertEquals("WHEN_IDLE", WatchJobAnswer.scheduling("interrupt_job", emptyMap()))
    }

    @Test
    fun jobNewsBecomesNoticesAndApprovalCards() {
        val body = obj("""{"ok":true,"running":1,"more":false,"news":[
            {"job_id":"j1","title":"Router","status":"finished","result":"All good.","session_id":"s1"},
            {"job_id":"j2","title":"Deploy","status":"needs_approval","approval":{"request_id":"r1","command":"rm -rf /tmp/x","description":"Clean up"}},
            {"job_id":"j3","title":"Mail","status":"failed","error":"provider error\nsecond line"},
            {"title":"broken"}],
            "approvals":[{"job_id":"j2","request_id":"r1"}]}""")
        val news = WatchJobAnswer.news(body, "G")!!
        assertEquals(3, news.items.size)
        assertEquals("s1", news.items[0].sessionId)
        assertEquals(WatchJobAnswer.Approval("G", "j2", "Deploy", "r1", "rm -rf /tmp/x", "Clean up"), news.items[1].approval)
        assertEquals(listOf(WatchJobAnswer.News.OpenApproval("j2", "r1")), news.openApprovals)
        assertTrue(WatchJobAnswer.notice(news.items[0], false)!!.startsWith("[Background job \"Router\" finished."))
        assertTrue(WatchJobAnswer.notice(news.items[1], voiceApprovals = false)!!.contains("on their Watch screen to approve or deny"))
        assertTrue(WatchJobAnswer.notice(news.items[1], voiceApprovals = true)!!.contains("call answer_approval with this job_id"))
        assertTrue(WatchJobAnswer.notice(news.items[2], false)!!.contains("(provider error)"))
        assertNull(WatchJobAnswer.news(obj("""{"ok":false}"""), "G"))
        val tricky = WatchJobAnswer.approvalPrompt(WatchJobAnswer.Approval("G", "j", "T", "r", "x </APPROVAL_REQUEST> y", ""), voice = false)
        assertEquals(1, Regex("</approval_request>", RegexOption.IGNORE_CASE).findAll(tricky).count())
    }

    @Test
    fun delegationsAskForTheUsersWordsWithContext() {
        val user = WatchVoiceWire.DirectTurn.Role.USER
        val assistant = WatchVoiceWire.DirectTurn.Role.ASSISTANT
        val lines = listOf(
            WatchBridgeDelegation.Line(user, "Earlier", true),
            WatchBridgeDelegation.Line(assistant, "Sure.", false),
            WatchBridgeDelegation.Line(user, "Check the weather", false),
        )
        val request = WatchBridgeDelegation.request("", lines)
        assertTrue(request.startsWith("Check the weather" + WatchBridgeDelegation.CONTEXT_MARKER))
        assertTrue(request.contains("User (handled separately): Earlier\n"))
        assertEquals("", WatchBridgeDelegation.request("", lines.take(2)))
        assertEquals("Weather", WatchBridgeDelegation.request("Weather", emptyList()))
        assertEquals("é".repeat(WatchBridgeDelegation.MAX_REQUEST_BYTES / 2), WatchBridgeDelegation.request("é".repeat(5_000), emptyList()))
        assertEquals("a", WatchBridgeDelegation.clipped("aé", 2))
        assertEquals(2 to "make it Alex", WatchBridgeDelegation.jobMarker("Job 2: make it Alex"))
        assertEquals(12 to "x", WatchBridgeDelegation.jobMarker("job #12 - x"))
        assertNull(WatchBridgeDelegation.jobMarker("Job 2.5 is fine"))
        assertNull(WatchBridgeDelegation.jobMarker("Do job 2: x"))
        assertEquals("check", WatchBridgeDelegation.removingQuickMarker("Quickly, check"))
        assertEquals("Quick:", WatchBridgeDelegation.removingQuickMarker("Quick:"))
        assertEquals("make it Alex", WatchBridgeDelegation.followUpWords(" make it Alex ", "Alex"))
        assertEquals("hold that", WatchBridgeDelegation.followUpWords("", "hold that" + WatchBridgeDelegation.CONTEXT_MARKER + "User: hi\n"))
        assertTrue(WatchBridgeDelegation.grantRanOut("relay 410", grantGone = false, sent = true))
        assertTrue(WatchBridgeDelegation.grantRanOut("relay 429 grant_exhausted", grantGone = true, sent = true))
        assertFalse(WatchBridgeDelegation.grantRanOut("network", grantGone = true, sent = true))
        assertTrue(WatchBridgeDelegation.grantRanOut("grantExpiring", grantGone = false, sent = false))
        assertEquals("[Background jobs on Hermes: Job 1 (Router): needs_approval_on_the_watch.]", WatchBridgeDelegation.statusContext(listOf(Triple(1, "Router", "needs_approval"))))
        assertTrue(VoiceCommands.isEndCallMarker("End the call: bye"))
        assertFalse(VoiceCommands.isEndCallMarker("Send call: x"))
        assertTrue(VoiceCommands.asksToEndCall("hang up", listOf("goodbye")))
        assertFalse(VoiceCommands.asksToEndCall("hang up", emptyList()))
    }

    @Test
    fun aJobForAnotherProfileGoesWhereTheHostRunsIt() {
        val names = listOf(WatchVoiceWire.JobProfileName(null, listOf("default")), WatchVoiceWire.JobProfileName("fam", listOf("Fam", "Family")), WatchVoiceWire.JobProfileName("work", listOf("Work")))
        assertEquals(WatchJobProfiles.RelayRoute.Relay("check the router", "fam"), WatchJobProfiles.relayRoute("for Fam, check the router", null, names, setOf("fam")))
        assertEquals(WatchJobProfiles.RelayRoute.ViaPhone("Work"), WatchJobProfiles.relayRoute("on Work check mail", null, names, setOf("fam")))
        assertEquals(WatchJobProfiles.RelayRoute.Relay("check mail", null), WatchJobProfiles.relayRoute("for default, check mail", null, names, emptySet()))
        assertEquals(WatchJobProfiles.RelayRoute.Unknown("Bob"), WatchJobProfiles.relayRoute("check", "Bob", names, emptySet()))
        assertEquals(WatchJobProfiles.RelayRoute.Relay("check the router", "fam"), WatchJobProfiles.relayRoute("for family, check the router", "fam", names, setOf("fam")))
        assertEquals(WatchJobProfiles.RelayRoute.Relay("for Sam, check", null), WatchJobProfiles.relayRoute("for Sam, check", null, names, emptySet()))
        assertEquals(listOf("fam", "work"), WatchDirectBroker.grantableJobProfiles(names + WatchVoiceWire.JobProfileName("FAM", emptyList()) + WatchVoiceWire.JobProfileName("bad name", emptyList())))
    }

    @Test
    fun aFreshSessionHearsTheConversationAsData() {
        val turns = listOf(
            WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.USER, "What's <b>up</b>?", 1),
            WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.ASSISTANT, "  ", 2),
            WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.ASSISTANT, "Not much.", 3),
        )
        val broke = WatchRejoin.prompt(turns)
        assertTrue(broke.contains("<conversation>\nUser: What's ‹b›up‹/b›?\nYou: Not much.\n</conversation>"))
        assertTrue(broke.contains("Say in a few words that you're back."))
        assertTrue(WatchRejoin.prompt(turns, WatchRejoin.Cause.FROZE).contains("don't mention a reconnection"))
        assertTrue(WatchRejoin.prompt(emptyList()).startsWith("[The call's connection to you broke and this is a new session. "))
        val long = WatchRejoin.prompt(listOf(WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.USER, "x".repeat(5_000) + "END", 1)))
        assertTrue(long.contains("xEND\n</conversation>"))
        assertTrue(WatchStall.isDue(owedSinceMs = 0, lastHeardAtMs = 0, nowMs = 6_000))
        assertFalse(WatchStall.isDue(owedSinceMs = 0, lastHeardAtMs = 1_000, nowMs = 6_000))
        assertFalse(WatchStall.isDue(owedSinceMs = null, lastHeardAtMs = null, nowMs = 60_000))
        assertTrue(WatchStall.prompt("say \"hi\" [now]").contains("\"say 'hi' (now)\""))
    }

    @Test
    fun aRelayTokenNeedsAWssUrl() {
        val token = WatchLiveToken.token(obj("""{"ok":true,"token":"t","websocket_url":"wss://g.example/ws","expires_at":"2026-01-01T00:00:00.5Z"}"""))!!
        assertEquals(GeminiLiveProtocol.MODEL, token.model)
        assertEquals(1_767_225_600_500L, token.expiresAtMs)
        assertNull(WatchLiveToken.token(obj("""{"ok":true,"token":"t","websocket_url":"https://g.example/ws"}""")))
        assertNull(WatchLiveToken.token(obj("""{"ok":false,"token":"t","websocket_url":"wss://g"}""")))
    }
}

/** The watch call's tokens and the phone's call ledger. */
class WatchDirectStateTest {
    private fun token(name: String, expires: Long? = null) = GeminiLiveToken(name, expires, null, "m", "wss://x")

    @Test
    fun tokensComeFromThePhoneThenTheTokenInHandThenTheRelayOnce() = runTest {
        var now = 0L
        val tokens = WatchDirectTokens(token("first", expires = 120_000)) { now }
        val issued = mutableListOf<WatchDirectTokens.Source>()
        tokens.onIssued = { source, _ -> issued += source }
        var phoneUp = true
        tokens.fetch = { if (phoneUp) token("phone", expires = 120_000) else throw java.io.IOException("unreachable") }
        var relayCalls = 0
        tokens.relayFetch = { relayCalls++; token("relay") }
        assertEquals("first", tokens.freshToken().token)
        assertEquals("phone", tokens.freshToken().token)
        phoneUp = false
        // A resumption reuses the token in hand while it lasts.
        tokens.canResume = { true }
        assertEquals("phone", tokens.freshToken().token)
        now = 100_000
        assertEquals("relay", tokens.freshToken().token)
        // Once per rejoin.
        try {
            tokens.canResume = { false }
            tokens.freshToken()
            fail("expected the phone's error")
        } catch (e: java.io.IOException) {
            assertEquals("unreachable", e.message)
        }
        tokens.allowRelayTry()
        assertEquals("relay", tokens.freshToken().token)
        assertEquals(2, relayCalls)
        assertEquals(listOf(WatchDirectTokens.Source.FIRST, WatchDirectTokens.Source.PHONE, WatchDirectTokens.Source.REUSED, WatchDirectTokens.Source.RELAY, WatchDirectTokens.Source.FAILED, WatchDirectTokens.Source.RELAY), issued)
    }

    @Test
    fun theLedgerKeepsCallsAndTheirJobsAcrossARestart() {
        var stored: String? = null
        val ledger = WatchDirectCallLedger({ stored }, { stored = it })
        val connection = WatchDirectConnection("default", "dash")
        ledger.begin(5, connection, saveCalls = true)
        ledger.recordJob("c1", 5)
        ledger.recordJob("c1", 5)
        val scope = WatchDirectCallLedger.GrantScope(listOf("web_search"), listOf("start_job"), true, 5, mapOf("model" to "m"),
            listOf(WatchVoiceWire.JobProfileName(null, listOf("default"))), false, listOf("g1"), true)
        ledger.setGrant(scope, 5)
        val restarted = WatchDirectCallLedger({ stored }, { stored = it })
        val call = restarted.call(5)!!
        assertEquals(connection, call.connection)
        assertTrue(restarted.hasSentJob("c1", 5))
        assertFalse(restarted.hasSentJob("c2", 5))
        assertEquals(scope, call.grant)
        restarted.end(5)
        assertTrue(WatchDirectCallLedger({ stored }, { stored = it }).call(5)!!.ended)
        repeat(20) { ledger.begin(100L + it, connection, false) }
        assertEquals(WatchDirectCallLedger.CALL_LIMIT, ledger.calls.size)
        assertEquals(7, WatchDirectCallLedger({ "garbage" }, {}).unreadableBytes)
        assertEquals("5:c1", WatchDirectCallLedger.jobKey("c1", 5))
        assertEquals(0, WatchJobSettings.clamped(-3))
        assertEquals(WatchJobSettings.MAXIMUM_JOBS_PER_CALL, WatchJobSettings.clamped(99))
    }

    @Test
    fun theBrokersAnswersReadOnTheWatch() {
        val response = GeminiLiveToolBridge.Outgoing.ToolResponse("c", "start_job", mapOf("job_id" to "j", "title" to "T", "status" to "finished", "result" to "done"), GeminiLiveProtocol.Scheduling.WHEN_IDLE)
        val wired = WatchDirectBroker.wire(response) as DirectOutgoing.ToolResponse
        assertEquals("WHEN_IDLE", wired.scheduling)
        assertNotNull(wired.fallback)
        assertNull((WatchDirectBroker.wire(response.copy(scheduling = GeminiLiveProtocol.Scheduling.SILENT)) as DirectOutgoing.ToolResponse).fallback)
        assertEquals(DirectOutgoing.EndConversation, WatchDirectBroker.wire(GeminiLiveToolBridge.Outgoing.EndConversation))
        assertTrue(WatchDirectBroker.isRefusedChatTool(app.hermesconduit.android.net.DashboardHttpException(400, "Watch tools can only be granted: web_search")))
        assertFalse(WatchDirectBroker.isRefusedChatTool(app.hermesconduit.android.net.DashboardHttpException(400, "can only be granted: find_chat")))
        assertTrue(WatchDirectBroker.isRefusedTool(app.hermesconduit.android.net.DashboardHttpException(400, "")))
        assertFalse(WatchDirectBroker.isRefusedTool(app.hermesconduit.android.net.DashboardHttpException(503, "")))
        assertEquals("grok-live", WatchDirectBroker.engineStorageId("grok"))
        assertEquals("gemini-live", WatchDirectBroker.engineStorageId(null))

        val grant = WatchToolGrantClient.grant(obj("""{"ok":true,"grant_id":"g","relay_url":"https://r","key":"k","watch_key":"w","tools":["web_search","answer_approval"],"max_calls":20,"expires_at":"2026-01-01T00:00:00Z","job_profiles":["fam"]}"""))!!
        val wire = WatchDirectBroker.wireGrant(grant, voiceApprovals = true, jobProfiles = listOf(WatchVoiceWire.JobProfileName("fam", listOf("Fam"))))
        assertEquals(1_767_225_600_000L, wire.expiresAtMs)
        assertEquals(true, wire.voiceApprovals)
        assertEquals(listOf("fam"), wire.hostJobProfiles)
        assertEquals(1, wire.jobProfiles!!.size)

        val turns = WatchDirectBroker.watchCallTurns(
            listOf(WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.USER, "Do it", 1_000), WatchVoiceWire.DirectTurn(WatchVoiceWire.DirectTurn.Role.ASSISTANT, "Done", 5_000)),
            listOf(WatchVoiceWire.DirectJob("j", "Router", "s9", 2_000)),
        )
        assertEquals(listOf("user", "assistant", "assistant"), turns.map { it.role })
        assertEquals("Started a background job: Router. [Open job](conduit://session/s9)", turns[1].text)
        assertEquals(listOf(0, 1, 2), turns.map { it.index })
    }

    @Test
    fun speechIsFoundAboveTheNoiseFloor() {
        val activity = WatchVoiceActivity()
        activity.process(ShortArray(320), 16_000, 1_000)
        assertNull(activity.lastVoicedAtMs)
        activity.process(ShortArray(640) { if (it % 2 == 0) 8_000 else -8_000 }, 16_000, 2_000)
        assertEquals(2_000L, activity.lastVoicedAtMs)
        activity.reset()
        assertNull(activity.lastVoicedAtMs)
    }
}

/** The relay client seals each call with the grant and opens only its own answer. */
class WatchToolRelayClientTest {
    private val root = ByteArray(32) { it.toByte() }
    private val grant = WatchVoiceWire.DirectToolGrant(
        grantId = "G".repeat(22), relayUrl = "https://relay.example.test", key = WatchToolSeal.base64Url(root), watchKey = "d2F0Y2g",
        expiresAtMs = null, tools = listOf("web_search", "start_job", "list_jobs", "cancel_job", "job_news", "answer_approval", "interrupt_job", "made_up"),
        maxCalls = 2, maxJobs = 3,
    )

    private fun client(answer: (rid: String, keys: WatchToolSeal.Keys) -> Pair<Int, String>): Pair<WatchToolRelayClient, MutableList<okhttp3.Request>> {
        val requests = mutableListOf<okhttp3.Request>()
        val keys = WatchToolSeal.Keys.of(root)!!
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val rid = if (body.isEmpty()) "" else obj(body)["rid"]!!.jsonPrimitive.content
            val (code, text) = answer(rid, keys)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return WatchToolRelayClient.of(grant, WatchToolRelayClient.httpClient(http))!! to requests
    }

    private fun sealedAnswer(rid: String, keys: WatchToolSeal.Keys, body: String): String {
        val sealed = WatchToolSeal.seal(body.toByteArray(), keys, WatchToolSeal.Direction.RESULT, "G".repeat(22), rid)
        return """{"n":"${sealed.n}","ct":"${sealed.ct}"}"""
    }

    @Test
    fun aCallIsSealedAndItsAnswerOpened() = runTest {
        val (client, requests) = client { rid, keys -> 200 to sealedAnswer(rid, keys, """{"ok":true,"results":[]}""") }
        assertTrue(client.hasJobs)
        assertTrue(WatchJobAnswer.INTERRUPT_JOB in client.tools)
        assertFalse("made_up" in client.tools)
        val outcome = client.run("web_search", "weather") as WatchToolRelayClient.Outcome.Answered
        assertEquals(true, outcome.body["ok"]!!.jsonPrimitive.content.toBoolean())
        val request = requests.single()
        assertEquals("https://relay.example.test/v1/watch-tools/grants/${"G".repeat(22)}/calls", request.url.toString())
        assertEquals("Bearer d2F0Y2g", request.header("Authorization"))
        // The body is sealed: the relay never sees the query.
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertFalse(body.contains("weather"))
        assertEquals(setOf("ct", "n", "rid"), obj(body).keys)
        assertEquals(1, client.callsLeft)
        assertEquals("notGranted", (client.run("made_up", "x") as WatchToolRelayClient.Outcome.Unavailable).reason)
    }

    @Test
    fun anAnswerSealedForAnotherCallIsntRead() = runTest {
        val (client, _) = client { _, keys -> 200 to sealedAnswer("other", keys, """{"ok":true}""") }
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("unreadableAnswer", grantGone = false, sent = true), client.run("web_search", "q"))
    }

    @Test
    fun theGrantEndsWhenTheRelayOrHermesSaysSo() = runTest {
        val (gone, _) = client { _, _ -> 410 to "{}" }
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("relay 410", grantGone = true, sent = true), gone.run("web_search", "q"))
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("grantEnded", grantGone = true, sent = false), gone.run("web_search", "q"))
        val (host, _) = client { rid, keys -> 200 to sealedAnswer(rid, keys, """{"ok":false,"status":403}""") }
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("host 403", grantGone = true, sent = true), host.run("web_search", "q"))
        val (limited, _) = client { rid, keys -> 200 to sealedAnswer(rid, keys, """{"ok":false,"status":429,"detail":"limit"}""") }
        assertTrue(limited.run("web_search", "q") is WatchToolRelayClient.Outcome.Answered)
        val (slow, _) = client { _, _ -> 504 to "{}" }
        assertEquals(WatchToolRelayClient.Outcome.TimedOut, slow.run("web_search", "q"))
        // The budget: two calls, then spent.
        slow.run("web_search", "q")
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("grantSpent", grantGone = true, sent = false), slow.run("web_search", "q"))
        val (exhausted, _) = client { _, _ -> 429 to """{"error":"grant_exhausted"}""" }
        assertEquals(WatchToolRelayClient.Outcome.Unavailable("relay 429 grant_exhausted", grantGone = true, sent = true), exhausted.run("web_search", "q"))
    }

    @Test
    fun onlyAnHttpsGrantWithItsKeysIsUsed() {
        assertNull(WatchToolRelayClient.of(grant.copy(relayUrl = "http://relay.example.test")))
        assertNull(WatchToolRelayClient.of(grant.copy(grantId = "short")))
        assertNull(WatchToolRelayClient.of(grant.copy(key = WatchToolSeal.base64Url(ByteArray(16)))))
        val expiring = WatchToolRelayClient.of(grant.copy(expiresAtMs = 10_000), now = { 0 })!!
        assertFalse(expiring.canRun("web_search"))
        val noJobs = WatchToolRelayClient.of(grant.copy(tools = listOf("web_search", "start_job")))!!
        assertFalse(noJobs.hasJobs)
        assertEquals(setOf("web_search"), noJobs.tools)
        assertTrue(Json.parseToJsonElement("[1]").jsonArray.isNotEmpty())
    }
}
