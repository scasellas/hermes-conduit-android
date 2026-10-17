package app.hermesconduit.android

import app.hermesconduit.android.model.HermesCallOpening
import app.hermesconduit.android.model.HermesCallRequest
import app.hermesconduit.wearwire.HermesRingDevice
import app.hermesconduit.wearwire.HermesRingIds
import app.hermesconduit.wearwire.HermesRingLedger
import app.hermesconduit.wearwire.HermesRingOutcome
import app.hermesconduit.wearwire.HermesRingSettleResult
import app.hermesconduit.wearwire.HermesWatchRingPlan
import app.hermesconduit.wearwire.PhoneContext
import app.hermesconduit.wearwire.WatchCallVoices
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WatchVoiceWire.Message
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls from Hermes ringing the watch too (iOS HermesRingTests, designs/hermes-calls-watch.md). */
class HermesRingTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val ring = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun ringIdsAreTheRelays() {
        repeat(20) { assertTrue(HermesRingIds.isID(HermesRingIds.newID())) }
        assertTrue(HermesRingIds.isID("abc-_DEF0123456789xyzQ"))
        assertFalse(HermesRingIds.isID("short"))
        assertFalse(HermesRingIds.isID("abc+/DEF0123456789xyzQ"))
        assertFalse(HermesRingIds.isID("abcdefghijklmnopqrstuvw"))
    }

    @Test
    fun theFirstSettleStands() {
        val ledger = HermesRingLedger(lifeMs = 1_000)
        // A ring the phone never sent (or forgot) fails, as the relay's does.
        assertEquals(HermesRingSettleResult.Failed, ledger.settle(ring, HermesRingDevice.WATCH, HermesRingOutcome.ANSWERED, 0))
        ledger.open(ring, 0)
        assertNull(ledger.settled(ring, 10))
        assertEquals(HermesRingSettleResult.Settled, ledger.settle(ring, HermesRingDevice.WATCH, HermesRingOutcome.ANSWERED, 10))
        assertEquals(HermesRingOutcome.ANSWERED to HermesRingDevice.WATCH, ledger.settled(ring, 20))
        // The phone's answer comes second: the call is on the watch.
        assertEquals(
            HermesRingSettleResult.AlreadySettled(HermesRingOutcome.ANSWERED, HermesRingDevice.WATCH),
            ledger.settle(ring, HermesRingDevice.PHONE, HermesRingOutcome.ANSWERED, 30),
        )
        // Opened again (a ring sent twice), it keeps how it was settled.
        ledger.open(ring, 40)
        assertEquals(HermesRingOutcome.ANSWERED to HermesRingDevice.WATCH, ledger.settled(ring, 50))
        // Kept as long as the relay keeps a ring, then forgotten.
        assertTrue(ledger.knows(ring, 999))
        assertFalse(ledger.knows(ring, 1_000))
        assertEquals(HermesRingSettleResult.Failed, ledger.settle(ring, HermesRingDevice.PHONE, HermesRingOutcome.DECLINED, 1_000))
    }

    @Test
    fun theLedgerKeepsItsNewestRings() {
        val ledger = HermesRingLedger()
        val ids = List(HermesRingLedger.MAXIMUM_RINGS + 1) { HermesRingIds.newID() }
        ids.forEach { ledger.open(it, 0) }
        assertFalse(ledger.knows(ids.first(), 1))
        assertTrue(ledger.knows(ids.last(), 1))
    }

    @Test
    fun settleRepliesAreTheRelays() {
        // 2xx: settled; a 409's `settled`: the other device got there first; anything else failed.
        assertEquals(HermesRingSettleResult.Settled, HermesRingSettleResult.decode("""{"ok":true}""".toByteArray()))
        assertEquals(
            HermesRingSettleResult.AlreadySettled(HermesRingOutcome.DECLINED, HermesRingDevice.PHONE),
            HermesRingSettleResult.decode("""{"settled":{"outcome":"declined","by":"phone"}}""".toByteArray()),
        )
        assertEquals(HermesRingSettleResult.Failed, HermesRingSettleResult.decode("""{"settled":{"outcome":"maybe","by":"phone"}}""".toByteArray()))
        assertEquals(HermesRingSettleResult.Failed, HermesRingSettleResult.decode("""{"ok":false}""".toByteArray()))
        assertEquals(HermesRingSettleResult.Failed, HermesRingSettleResult.decode("nope".toByteArray()))
        assertEquals(HermesRingSettleResult.Failed, HermesRingSettleResult.decode(null))
        listOf(
            HermesRingSettleResult.Settled,
            HermesRingSettleResult.AlreadySettled(HermesRingOutcome.ANSWERED, HermesRingDevice.WATCH),
            HermesRingSettleResult.Failed,
        ).forEach { assertEquals(it, HermesRingSettleResult.decode(it.encoded())) }
    }

    @Test
    fun ringMessagesRoundTrip() {
        fun roundTrip(message: WearMessage): WearMessage? {
            val (path, bytes) = WearWire.encode(message)
            return WearWire.decode(path, bytes)
        }
        listOf(
            WearMessage.Ring(ring, "Fam · Weekly report", 1_700_000_000_000),
            WearMessage.Ring(ring, "Hermes", null),
            WearMessage.RingSettle(ring, HermesRingDevice.WATCH, HermesRingOutcome.DECLINED),
            WearMessage.RingSettled(ring, HermesRingOutcome.ANSWERED, HermesRingDevice.PHONE),
        ).forEach { assertEquals(it, roundTrip(it)) }
        // The stop names the ring as the relay's does.
        val (_, stop) = WearWire.encode(WearMessage.RingSettled(ring, HermesRingOutcome.DECLINED, HermesRingDevice.PHONE))
        assertEquals(obj("""{"id":"$ring","settled":"declined","by":"phone"}"""), obj(stop.decodeToString()))
        // A ring id that isn't one, or a caller left blank.
        assertNull(WearWire.decode(WearWire.RING, """{"id":"x","caller":"Hermes"}""".toByteArray()))
        assertEquals(WearMessage.Ring(ring, "Hermes", null), WearWire.decode(WearWire.RING, """{"id":"$ring","caller":"  "}""".toByteArray()))
        assertNull(WearWire.decode(WearWire.RING_SETTLE, """{"id":"$ring","by":"tablet","outcome":"answered"}""".toByteArray()))
        // A newer phone's ring isn't misread.
        assertNull(WearWire.decode(WearWire.RING, """{"v":99,"id":"$ring","caller":"Hermes"}""".toByteArray()))
    }

    @Test
    fun theWireIsTheIPhones() {
        // A watch call answering a call from Hermes names its ring.
        assertEquals(obj("""{"directStart":{"callID":7,"version":2,"ring":"$ring"}}"""), obj(WatchVoiceWire.encode(Message.DirectStart(7, 2, ring)).decodeToString()))
        assertEquals(obj("""{"grokStart":{"callID":7,"version":2,"ring":"$ring"}}"""), obj(WatchVoiceWire.encode(Message.GrokStart(7, 2, ring)).decodeToString()))
        assertEquals(Message.DirectStart(7, 2, ring), WatchVoiceWire.decode("""{"directStart":{"callID":7,"version":2,"ring":"$ring"}}""".toByteArray()))
        // callsToken has no call id; a missing optional is left out, as Swift's encoder does.
        assertEquals(obj("""{"callsToken":{"token":"node-1","engine":"geminiLive"}}"""),
            obj(WatchVoiceWire.encode(Message.CallsToken("node-1", engine = WatchCallVoices.GEMINI_LIVE)).decodeToString()))
        assertEquals(obj("""{"callsToken":{}}"""), obj(WatchVoiceWire.encode(Message.CallsToken(null)).decodeToString()))
        assertEquals(Message.CallsToken("t", "k", "gptLive"), WatchVoiceWire.decode("""{"callsToken":{"token":"t","key":"k","engine":"gptLive"}}""".toByteArray()))
        assertEquals(Message.CallsToken(null), WatchVoiceWire.decode("""{"callsToken":{}}""".toByteArray()))
    }

    @Test
    fun phoneContextSaysWhetherCallsRing() {
        val context = PhoneContext(signedIn = true, connected = true, voiceApprovals = false, profile = "default", callsRing = true)
        assertTrue(PhoneContext.decode(context.encoded())!!.callsRing)
        // An older phone doesn't say: calls don't ring there as far as the watch knows.
        assertFalse(PhoneContext.decode("""{"signed_in":true}""")!!.callsRing)
    }

    @Test
    fun callsRingTheWatchOnlyAlongsideThePhoneAndWhileItsVoiceCanStart() {
        val ready = mapOf(WatchCallVoices.GEMINI_LIVE to true, WatchCallVoices.GPT_LIVE to false)
        assertTrue(HermesWatchRingPlan.ringsWatch(true, "node", WatchCallVoices.GEMINI_LIVE, ready))
        assertFalse(HermesWatchRingPlan.ringsWatch(false, "node", WatchCallVoices.GEMINI_LIVE, ready))
        assertFalse(HermesWatchRingPlan.ringsWatch(true, null, WatchCallVoices.GEMINI_LIVE, ready))
        assertFalse(HermesWatchRingPlan.ringsWatch(true, "", WatchCallVoices.GEMINI_LIVE, ready))
        assertFalse(HermesWatchRingPlan.ringsWatch(true, "node", WatchCallVoices.GPT_LIVE, ready))
        // Not checked yet, or a watch that doesn't say: it rings, as on iOS.
        assertTrue(HermesWatchRingPlan.ringsWatch(true, "node", WatchCallVoices.GROK_LIVE, ready))
        assertTrue(HermesWatchRingPlan.ringsWatch(true, "node", null, ready))
        // The turn-by-turn voice through the phone can't open with the call.
        assertFalse(HermesWatchRingPlan.ringsWatch(true, "node", WatchCallVoices.PHONE, ready))
        assertTrue(WatchCallVoices.rings(null, emptyMap()))
        assertFalse(WatchCallVoices.rings("gptLive", mapOf("gptLive" to false)))
    }

    @Test
    fun theWatchRingsOnlyWhenFreeAndOnTime() {
        val now = 1_000_000L
        assertTrue(HermesWatchRingPlan.ringsHere(now - 5_000, now, busy = false, settled = false))
        assertTrue(HermesWatchRingPlan.ringsHere(null, now, busy = false, settled = false))
        assertFalse(HermesWatchRingPlan.ringsHere(now - HermesWatchRingPlan.LATE_AFTER_MS - 1, now, busy = false, settled = false))
        assertTrue(HermesWatchRingPlan.ringsHere(now - HermesWatchRingPlan.LATE_AFTER_MS, now, busy = false, settled = false))
        assertFalse(HermesWatchRingPlan.ringsHere(now, now, busy = true, settled = false))
        // The phone's stop came first.
        assertFalse(HermesWatchRingPlan.ringsHere(now, now, busy = false, settled = true))
    }

    @Test
    fun aWatchCallOpensWithTheCallButCantAnswerForTheUser() {
        val done = HermesCallOpening(HermesCallRequest.Kind.DONE, "Tests", "All green.")
        // Nothing waits on the user: the watch opens as the phone does.
        assertEquals(done.openingTurn, done.watchOpeningTurn)
        val approval = HermesCallOpening(HermesCallRequest.Kind.APPROVAL, null, null, reason = "Deploy")
        assertTrue(approval.watchOpeningTurn.contains("where to answer"))
        val block = approval.instructionBlock(delegation = false, canAnswer = false)
        assertTrue(block.contains("Conduit on their phone"))
        assertFalse(block.contains("iPhone"))
        assertFalse(block.contains("answer_approval"))
    }
}
