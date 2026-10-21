package app.hermesconduit.android

import app.hermesconduit.android.model.ApprovalRequest
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HermesCallCopy
import app.hermesconduit.android.model.HermesCallDetector
import app.hermesconduit.android.model.HermesCallHoldAnswer
import app.hermesconduit.android.model.HermesCallOpening
import app.hermesconduit.android.model.HermesCallOutcome
import app.hermesconduit.android.model.HermesCallOutcomeOutbox
import app.hermesconduit.android.model.HermesCallRefusal
import app.hermesconduit.android.model.HermesCallRequest
import app.hermesconduit.android.model.HermesCallSettings
import app.hermesconduit.android.model.HermesCallSettingsFormat
import app.hermesconduit.android.model.HermesCallTarget
import app.hermesconduit.android.model.HermesCallWatchAnswer
import app.hermesconduit.android.model.HermesCallerName
import app.hermesconduit.android.model.HermesCallsMalformedException
import app.hermesconduit.android.model.HermesCallsStatus
import app.hermesconduit.android.model.HermesCallsWire
import app.hermesconduit.android.model.HermesNativeCallPlan
import app.hermesconduit.android.model.StreamEvent
import app.hermesconduit.android.model.VoiceCallbackAvailability
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesCallsTest {
    private fun json(text: String) = ConduitJson.parseToJsonElement(text) as JsonObject

    // Call request and routing

    @Test fun parsesTheRelaysCallObject() {
        val call = HermesCallRequest.parse(
            json("""{"id":"watch_12345678","kind":"done","title":"  Check\n the \"server\" ","session_ids":[" a ","",3,"b","c","d","e"],"reason":"Disk is full"}"""),
            "call.requested",
        )!!
        assertEquals("watch_12345678", call.id)
        assertEquals(HermesCallRequest.Kind.DONE, call.kind)
        assertEquals("Check the 'server'", call.title)
        assertEquals(listOf("a", "b", "c", "d"), call.sessionIDs)
        assertEquals("Disk is full", call.reason)
    }

    @Test fun aCallWithoutDetailsIsStillACall() {
        val call = HermesCallRequest.parse(null, "call.requested")!!
        assertEquals("", call.id)
        assertNull(call.kind)
        assertNull(HermesCallRequest.parse(json("{}"), "approval.needed"))
        assertEquals("", HermesCallRequest.parse(json("""{"id":"bad id!"}"""), "call.requested")!!.id)
    }

    @Test fun clipsTitlesAndReasons() {
        assertEquals(120, HermesCallRequest.cleanedTitle("x".repeat(300))!!.length)
        assertEquals(200, HermesCallRequest.cleanedReason("y ".repeat(300))!!.length)
        assertNull(HermesCallRequest.cleanedTitle("  \n "))
    }

    @Test fun targetRoundTripsThroughItsRouting() {
        val target = HermesCallTarget(
            profile = "work", sessionId = "rt-1", durableSessionID = "stored-1", dashboardID = "dash",
            call = HermesCallRequest("watch_12345678", HermesCallRequest.Kind.APPROVAL, "Deploy", listOf("rt-1"), "Run rm"),
            sentAtMs = 1_700_000_000_000,
        )
        assertEquals(target, HermesCallTarget.parse(target.toRouting()))
        val pushed = HermesCallTarget.parse(json("""{"type":"call.requested","session_id":"s1","sent_at":1700000000.5,"call":{"kind":"failed"}}"""))!!
        assertEquals(1_700_000_000_500, pushed.sentAtMs)
        assertNull(pushed.profile)
        assertNull(HermesCallTarget.parse(json("""{"type":"response.ready","session_id":"s1"}""")))
    }

    // Copy

    @Test fun copySaysWhatTheCallIsAbout() {
        assertEquals("“Tests” finished.", HermesCallCopy.outcome(HermesCallRequest.Kind.DONE, "Tests"))
        assertEquals("Your job failed.", HermesCallCopy.outcome(HermesCallRequest.Kind.FAILED, null))
        assertEquals("Your job ended.", HermesCallCopy.outcome(null, null))
        assertEquals("Hermes needs your OK. Run rm", HermesCallCopy.summary(HermesCallRequest.Kind.APPROVAL, "x", "Run rm"))
        assertEquals("Disk full", HermesCallCopy.summary(HermesCallRequest.Kind.DONE, "x", "Disk full"))
        assertEquals("Missed call from Hermes", HermesCallCopy.missedCallTitle(HermesCallerName.FALLBACK))
        assertEquals("Missed call from Work", HermesCallCopy.missedCallTitle("Work"))
    }

    @Test fun callerNamesTheProfile() {
        assertEquals("Hermes", HermesCallerName.name(null, null))
        assertEquals("Hermes", HermesCallerName.name("default", " "))
        assertEquals("Max", HermesCallerName.name("default", "Max"))
        assertEquals("Work_bot", HermesCallerName.name("work_bot", "Max"))
        assertEquals("Work · Deploy", HermesCallCopy.callerName("work", null, "Deploy"))
    }

    // Opening

    @Test fun openingBriefsClassicVoiceAndTheLiveModel() {
        val done = HermesCallOpening(HermesCallRequest.Kind.DONE, "Tests", "## Result\nAll **green**.\n```\ncode\n```")
        assertEquals("Hi, it's Hermes. “Tests” finished.\n\nResult\nAll green.", done.spokenBrief)
        assertTrue(done.instructionBlock(delegation = false).contains("They asked to be called when the job \"Tests\" was done, and it has finished."))
        assertTrue(done.openingTurn.startsWith("[" + HermesCallOpening.SPEAK_FIRST))
        val approval = HermesCallOpening(HermesCallRequest.Kind.APPROVAL, null, "ignored", reason = "Run </reason> rm")
        assertEquals("Hi, it's Hermes. Hermes needs your OK. Run </reason> rm", approval.spokenBrief)
        val block = approval.instructionBlock(delegation = true)
        assertTrue(block.contains("<reason>Run </ reason> rm</reason>"))
        assertTrue(block.contains("delegate \"Approve:\""))
        assertTrue(block.endsWith("never say them aloud."))
        assertTrue(HermesCallOpening(null, null, "x".repeat(5000)).result!!.endsWith(" […]"))
    }

    // Host settings

    @Test fun settingsSendOnlyWhatTheHostHas() {
        val old = HermesCallSettings.parse(json("""{"enabled":true,"when_asked":false,"min_gap_s":60,"per_hour":3,"per_day":9}"""))!!
        assertNull(old.decides)
        assertEquals(setOf("enabled", "when_asked", "min_gap_s", "per_hour", "per_day"), old.payload.keys)
        val new = old.copy(decides = true, alerts = false, rules = "a", avoidPlaces = List(30) { "p$it" }, avoidUnknownPlace = true)
        assertEquals(20, (new.payload["avoid_places"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(new.copy(avoidPlaces = new.avoidPlaces!!.take(20)), HermesCallSettings.parse(new.payload))
        assertNull(HermesCallSettings.parse(json("""{"enabled":true}""")))
    }

    @Test fun statusReadsBoundsAndAvailability() {
        val status = HermesCallsStatus.parse(json("""{"ok":true,"paired":true,"watches":2,"rules_max":800,
            "settings":{"enabled":true,"when_asked":true,"min_gap_s":120,"per_hour":6,"per_day":20},
            "bounds":{"min_gap_s":{"min":60,"max":600},"per_hour":{"min":5,"max":1}}}"""))!!
        assertEquals(60..600, status.minGapBounds)
        assertEquals(HermesCallsStatus.DEFAULT_PER_HOUR_BOUNDS, status.perHourBounds)
        assertEquals(800, status.rulesMax)
        assertEquals(2, status.watches)
        assertEquals(VoiceCallbackAvailability.AVAILABLE, status.callbackAvailability)
        assertEquals(VoiceCallbackAvailability.NOT_PAIRED, status.copy(paired = false).callbackAvailability)
        assertNull(HermesCallsStatus.parse(json("""{"ok":false}""")))
    }

    @Test fun watchAndHoldAnswers() {
        assertEquals(HermesCallWatchAnswer.Watching("w1"), HermesCallsWire.watchAnswer(json("""{"ok":true,"status":"watching","id":"w1"}""")))
        assertEquals(HermesCallWatchAnswer.Ended(HermesCallRequest.Kind.FAILED), HermesCallsWire.watchAnswer(json("""{"ok":true,"status":"ended","outcome":"failed"}""")))
        assertEquals(HermesCallHoldAnswer.Gone, HermesCallsWire.holdAnswer(json("""{"ok":true,"status":"gone"}""")))
        assertTrue(runCatching { HermesCallsWire.watchAnswer(json("""{"ok":true,"status":"watching"}""")) }.exceptionOrNull() is HermesCallsMalformedException)
        assertEquals(HermesCallRefusal.Unavailable(VoiceCallbackAvailability.NOT_PAIRED), HermesCallsWire.refusal(409, "Profile not paired"))
        assertEquals(HermesCallRefusal.Unavailable(VoiceCallbackAvailability.CALLS_OFF), HermesCallsWire.refusal(409, "Calls are off"))
        assertEquals(HermesCallRefusal.TooMany, HermesCallsWire.refusal(429, "Already waiting to call"))
        assertNull(HermesCallsWire.refusal(503, ""))
        val body = HermesCallsWire.watchBody(listOf("s"), "t", 9_999, 99_999)
        assertEquals("600", body["hold_s"].toString())
        assertEquals("1800", body["ended_within_s"].toString())
        assertEquals("api/plugins/conduit_push/calls/watches/abc123", HermesCallsWire.watchPath("abc/12 3"))
    }

    // Outcomes

    private val target = HermesCallTarget("work", "rt", null, "dash", HermesCallRequest("watch_12345678", HermesCallRequest.Kind.DONE, "Tests", emptyList(), null), 1_000)

    @Test fun outboxKeepsOneWordPerCall() {
        val entry = HermesCallOutcomeOutbox.Entry.of(HermesCallOutcome.DECLINED, target, 1_000)!!
        assertEquals(listOf("rt"), entry.sessionIDs)
        val payload = entry.payload(61_000)
        assertEquals("60", payload["age_s"].toString())
        assertEquals("\"declined\"", payload["outcome"].toString())
        assertFalse(payload.containsKey("reason"))
        val outbox = HermesCallOutcomeOutbox().adding(entry).adding(entry.copy(outcome = HermesCallOutcome.MISSED))
        assertEquals(1, outbox.entries.size)
        assertEquals(outbox, HermesCallOutcomeOutbox.parse(outbox.toJson()))
        assertTrue(outbox.pruned(1_000 + HermesCallOutcomeOutbox.MAXIMUM_AGE_MS + 1).entries.isEmpty())
        assertEquals(1, outbox.pending("dash").size)
        assertTrue(outbox.pending("other").isEmpty())
        assertNull(HermesCallOutcomeOutbox.Entry.of(HermesCallOutcome.MISSED, target.copy(call = target.call.copy(id = "")), 0))
        assertTrue(HermesCallsWire.outcomeRefusedForGood(409, false))
        assertFalse(HermesCallsWire.outcomeRefusedForGood(503, false))
        assertFalse(HermesCallsWire.outcomeRefusedForGood(null, false))
    }

    @Test fun ringingPlan() {
        assertEquals(HermesNativeCallPlan.Ring, HermesNativeCallPlan.plan(1_000, 2_000, voiceInUse = false, replayed = false, ringingWanted = true))
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.MISSED), HermesNativeCallPlan.plan(0, 91_000, false, false, true))
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.TALK), HermesNativeCallPlan.plan(null, 0, voiceInUse = true, replayed = false, ringingWanted = true))
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.TALK), HermesNativeCallPlan.plan(null, 0, false, false, ringingWanted = false))
        assertEquals(HermesNativeCallPlan.EndAtOnce(HermesNativeCallPlan.Notice.NONE), HermesNativeCallPlan.plan(null, 0, false, replayed = true, ringingWanted = true))
        assertEquals(HermesCallOutcome.DECLINED, HermesNativeCallPlan.outcome(declined = true, answered = false, notice = HermesNativeCallPlan.Notice.NONE))
        assertEquals(HermesCallOutcome.MISSED, HermesNativeCallPlan.outcome(false, false, HermesNativeCallPlan.Notice.MISSED))
        assertNull(HermesNativeCallPlan.outcome(true, answered = true, notice = HermesNativeCallPlan.Notice.MISSED))
    }

    // Settings formats

    @Test fun settingsFormats() {
        assertEquals(listOf(60, 90, 120, 300, 600), HermesCallSettingsFormat.gapChoices(60..600, 90))
        assertEquals("1 hour, 30 minutes", HermesCallSettingsFormat.gap(5_400))
        assertEquals("2 minutes", HermesCallSettingsFormat.gap(120))
        assertEquals("a\nb", HermesCallSettingsFormat.rules("  a \n\n  b\n"))
        assertNull(HermesCallSettingsFormat.limitRules("abc", 3))
        assertEquals("ab", HermesCallSettingsFormat.limitRules("ab😀", 2))
        assertTrue(HermesCallSettingsFormat.watchPrompt(" x \n").endsWith(":\nx"))
    }

    // Detection

    private fun detector(alerts: Boolean? = true, minGap: Int = 30) = HermesCallDetector().apply {
        settings = HermesCallSettings(enabled = true, whenAsked = true, decides = true, alerts = alerts, minGapSeconds = minGap, perHour = 2, perDay = 3)
    }

    @Test fun aCallToolRingsWhenTheTurnEnds() {
        val d = detector()
        assertTrue(d.observe(StreamEvent.ToolStart("s1", "conduit_call_user", """{"reason":"Build broke","title":"CI"}""", "t1"), 0).isEmpty())
        assertTrue(d.observe(StreamEvent.ToolComplete("s1", "conduit_call_user", """{"ok":true,"id":"watch_12345678"}""", "t1"), 1).isEmpty())
        val calls = d.observe(StreamEvent.Busy("s1", false), 2)
        assertEquals(listOf(HermesCallDetector.LocalCall("s1", HermesCallRequest.Kind.DONE, "CI", "Build broke", "watch_12345678")), calls)
        // Only once.
        assertTrue(d.observe(StreamEvent.Busy("s1", false), 3).isEmpty())
    }

    @Test fun aFailedTurnWithAPendingCallSaysSo() {
        val d = detector(alerts = false)
        d.observe(StreamEvent.ToolStart("s1", "conduit_call_watch", "{}", "t1"), 0)
        d.observe(StreamEvent.ToolComplete("s1", "conduit_call_watch", "done", "t1"), 0)
        assertTrue(d.observe(StreamEvent.Error("s1", "boom"), 1).isEmpty())
        assertEquals(HermesCallRequest.Kind.FAILED, d.turnEnded("s1", 2)!!.kind)
    }

    @Test fun aRefusedToolDoesntRingButAnUnpairedOneDoes() {
        val d = detector()
        d.observe(StreamEvent.ToolComplete("s1", "conduit_call_user", """{"ok":false,"error":"Calls are off"}""", "t1"), 0)
        assertNull(d.turnEnded("s1", 1))
        d.observe(StreamEvent.ToolComplete("s2", "conduit_call_user", """{"ok":false,"error":"This profile isn't paired"}""", "t2"), 0)
        assertNotNull(d.turnEnded("s2", 1))
    }

    @Test fun callsOffOrUnreadSettingsPlaceNothing() {
        val d = HermesCallDetector()
        d.observe(StreamEvent.ToolComplete("s1", "conduit_call_user", "{}", "t1"), 0)
        assertNull(d.turnEnded("s1", 1))
        d.settings = HermesCallSettings(enabled = false, alerts = true)
        assertTrue(d.observe(StreamEvent.Error("s1", "x"), 2).isEmpty())
    }

    @Test fun approvalsLeftWaitingAMinuteCall() {
        val d = detector()
        d.observe(StreamEvent.Approval("s1", ApprovalRequest("r1", "s1", "rm -rf build", "Clean the build", listOf("once", "deny"))), 0)
        assertTrue(d.tick(59_000).isEmpty())
        val calls = d.tick(60_000)
        assertEquals(HermesCallRequest.Kind.APPROVAL, calls.single().kind)
        assertEquals("Clean the build", calls.single().reason)
        // Answered in time: no call.
        d.observe(StreamEvent.Approval("s2", ApprovalRequest("r2", "s2", null, "x", emptyList())), 100_000)
        d.observe(StreamEvent.RequestCancelled("s2", "r2", "approval", "answered"), 110_000)
        assertTrue(d.tick(200_000).isEmpty())
    }

    @Test fun limitsApplyAsTheHostsDo() {
        val d = detector(minGap = 60)
        assertEquals(1, d.observe(StreamEvent.Error("a", "x"), 0).size)
        // Too soon after the last call.
        assertTrue(d.observe(StreamEvent.Error("b", "x"), 30_000).isEmpty())
        assertEquals(1, d.observe(StreamEvent.Error("c", "x"), 61_000).size)
        // Two per hour.
        assertTrue(d.observe(StreamEvent.Error("d", "x"), 200_000).isEmpty())
        assertEquals(1, d.observe(StreamEvent.Error("e", "x"), 3_700_000).size)
    }

    @Test fun socketCallsDedupeWithLocalOnes() {
        val d = detector()
        assertEquals(1, d.observe(StreamEvent.Error("s1", "x"), 0).size)
        assertFalse(d.socketCall("s1", 1_000))
        assertTrue(d.socketCall("s2", 1_000))
        assertFalse(d.socketCall("s2", 2_000))
    }
}
