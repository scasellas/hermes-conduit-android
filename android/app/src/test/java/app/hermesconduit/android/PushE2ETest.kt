package app.hermesconduit.android

import app.hermesconduit.android.data.MemoryTextStore
import app.hermesconduit.android.model.AndroidPushWire
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HermesCallRequest
import app.hermesconduit.android.model.LocationLocateRequest
import app.hermesconduit.android.model.PushAction
import app.hermesconduit.android.model.PushDispatch
import app.hermesconduit.android.model.PushE2E
import app.hermesconduit.android.model.PushKeyRecord
import app.hermesconduit.android.model.PushSeenStore
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sealed pushes over UnifiedPush (model/PushE2E.kt, PushDispatch.kt). The
 * vectors are iOS ConduitTests/NotificationE2ETests.swift's, sealed by
 * hermes-conduit-notifier's e2e.py with fixed nonces: Android opens exactly
 * what the plugin sends iOS.
 */
class PushE2ETest {
    private val kid = "0123456789abcdef0123456789abcdef"
    private val secret = ByteArray(32) { it.toByte() }
    private val installationID = "11111111-2222-3333-4444-555555555555"
    private val gatewayID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val dashboardID = "0F5C8A34-1B2D-4E5F-8A9B-0C1D2E3F4A5B"
    private val issuedAt = 1_760_000_000L
    private val now = (issuedAt + 60) * 1000

    private val record = PushKeyRecord(kid, secret, installationID, gatewayID, dashboardID, "coder", issuedAt * 1000)

    /** e2e.seal_event for an input.needed clarify ("Deploy to prod?", Yes/No, session sess-1, profile coder). */
    private val envelope = buildJsonObject {
        put("v", 1)
        put("kid", kid)
        put("msg", "input:9ab3405eb5333adb44e353fcd81508e8")
        put("iat", issuedAt)
        put("tok", "5d671a3554c84fb7")
        put("z", 1)
        put("req", "conduit-push-abc123def456")
        put("n", "BwcHBwcHBwcHBwcH")
        put("ct", "YEtXcEZp_hUYxXaIBmF-kC5gT4i7IomTbFRQmwF8LiFNeAzHOrZSd-VdKXI8WBkdjNd9GCKsCqePYurZuUJCINjnT2wiVZAGyBa2rq2UZfKo9399Ql8p-f4Rywl3wgNKh33TNk8pGP0EvdOuI6zi3Haw-c1-bCrheVS1BkufqRdPe2WPDPElQCiw0RU6PRCeEwLbFd7G30C0RKQDmExz-ZyYrQ")
    }

    /** e2e.seal_answer("Yes", request conduit-push-abc123def456, question q0). */
    private val pythonAnswer = "e2e1.0123456789abcdef0123456789abcdef.CQkJCQkJCQkJCQkJ.ql5wF3j3B9LkSwjxp3TxEr5fYg"

    /** What the plugin POSTs to the endpoint: routing stub and envelope (the iOS userInfo without `aps`). */
    private fun delivered(envelope: JsonObject = this.envelope, type: String = "input.needed"): JsonObject = buildJsonObject {
        put("conduit", buildJsonObject {
            put("type", type); put("e2e", 1); put("gateway_id", "push-server-says-anything"); put("dashboard_id", "99999999-9999-4999-8999-999999999999")
        })
        put("conduit_e2e", envelope)
    }

    private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))

    private fun untrusted(evaluation: PushE2E.Evaluation) = assertTrue("expected untrusted, got $evaluation", evaluation is PushE2E.Evaluation.Untrusted)

    // region Derivation and wire format

    @Test
    fun keyDerivationMatchesThePlugin() {
        val keys = PushE2E.keys(secret)
        assertEquals("a5a8884943a780bbf4f846cf294f149d8c668f58312ca26841ec7f78e5a1a099", SealCrypto.hex(keys.push))
        assertEquals("08c9b2cf01c5f94ed56658fc2a8b90d25b0361ab943f9efa4a857f3c44409b27", SealCrypto.hex(keys.answer))
    }

    @Test
    fun pluginSealedPushOpensAndRoutesFromVerifiedDataOnly() {
        val evaluation = PushE2E.evaluate(delivered(), listOf(record), now)
        val verified = evaluation as PushE2E.Evaluation.Verified
        assertEquals("Hermes", verified.content["title"].str)
        assertEquals("Deploy to prod?", verified.content["body"].str)
        assertEquals("input:9ab3405eb5333adb44e353fcd81508e8".let { "$kid:$it" }, verified.envelope.replayKey)

        val action = PushDispatch.plan(evaluation) as PushAction.Notify
        assertEquals("input.needed", action.type)
        assertEquals("sess-1", action.sessionId)
        assertEquals("coder", action.profile)
        // The pairing the key belongs to, never the routing stub.
        assertEquals(dashboardID, action.dashboardId)
        assertEquals("Hermes", action.title)
        assertEquals("Deploy to prod?", action.body)
        // A clarify card answers through the relay on iOS; Android shows the notification only.
        assertNull(action.approval)
        assertEquals(gatewayID, verified.routingPayload["gateway_id"].str)
    }

    @Test
    fun pluginSealedAnswerOpensWithTheAnswerKeyAndAAD() {
        val parts = pythonAnswer.removePrefix(PushE2E.ANSWER_PREFIX).split(".")
        val aad = PushE2E.answerAAD(kid, installationID, gatewayID, "conduit-push-abc123def456", "q0")
        val opened = SealCrypto.open(parts[1], parts[2], PushE2E.keys(secret).answer, aad)
        assertEquals("Yes", opened.decodeToString())
    }

    @Test
    fun appSealedAnswerMatchesThePluginByteForByte() {
        val nonce = ByteArray(12) { 9 }
        assertEquals(pythonAnswer, PushE2E.sealAnswer("Yes", record, "conduit-push-abc123def456", "q0", nonce))
        val other = PushE2E.sealAnswer("Blue", record, "conduit-push-abc123def456", "q1")
        val parts = other.removePrefix(PushE2E.ANSWER_PREFIX).split(".")
        val wrong = PushE2E.answerAAD(kid, installationID, gatewayID, "conduit-push-abc123def456", "q0")
        assertTrue(runCatching { SealCrypto.open(parts[1], parts[2], PushE2E.keys(secret).answer, wrong) }.isFailure)
    }

    // endregion

    // region Trust

    @Test
    fun tamperedStaleOrForeignEnvelopesAreUntrustedAndOnlyWake() {
        untrusted(PushE2E.evaluate(delivered(type = "approval.needed"), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(envelope.with("tok", "0000000000000000")), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(envelope.with("req", "conduit-push-000000000000")), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(envelope.with("ct", "AAAA" + envelope["ct"].str!!.drop(4))), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(), listOf(record), (issuedAt * 1000) + PushE2E.MAX_AGE_MS + 1_000))
        untrusted(PushE2E.evaluate(delivered(), listOf(record), (issuedAt * 1000) - PushE2E.MAX_CLOCK_SKEW_MS - 1_000))
        // Another pairing's key, or none at all.
        untrusted(PushE2E.evaluate(delivered(), listOf(record.copy(secret = ByteArray(32) { 1 })), now))
        untrusted(PushE2E.evaluate(delivered(), emptyList(), now))
        // A malformed envelope, a number where a string belongs, an old version.
        untrusted(PushE2E.evaluate(JsonObject(delivered() + ("conduit_e2e" to JsonPrimitive("garbage"))), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(JsonObject(envelope + ("iat" to JsonPrimitive("1760000000")))), listOf(record), now))
        untrusted(PushE2E.evaluate(delivered(JsonObject(envelope + ("v" to JsonPrimitive(2)))), listOf(record), now))

        val wake = PushDispatch.plan(PushE2E.evaluate(delivered(type = "approval.needed"), listOf(record), now)) as PushAction.Wake
        assertEquals("approval.needed", wake.type)
        assertEquals("unverified", wake.reason)
    }

    @Test
    fun plaintextIsNeverTrustedAndAPlainWakeOnlyWakes() {
        val plaintext = buildJsonObject { put("conduit", buildJsonObject { put("type", "response.ready"); put("session_id", "sess-1") }) }
        untrusted(PushE2E.evaluate(plaintext, listOf(record), now))
        untrusted(PushE2E.evaluate(plaintext, emptyList(), now))
        val wake = PushE2E.parseMessage("""{"conduit":{"type":"wake","reason":"too_large"}}""".toByteArray())!!
        assertEquals(PushAction.Wake("wake", "wake"), PushDispatch.plan(PushE2E.evaluate(wake, listOf(record), now)))
        assertNull(PushE2E.parseMessage("not json".toByteArray()))
    }

    // endregion

    // region Dispatch

    private fun sealed(type: String, content: JsonObject, compress: Boolean = false, req: String = "") =
        PushE2E.evaluate(PushTestSeal.message(record, type, content, issuedAt, compress = compress, req = req), listOf(record), now)

    @Test
    fun notificationTypesNotifyWithTheirApprovalCard() {
        val content = buildJsonObject {
            put("title", "Approval needed"); put("body", "git push --force"); put("session_id", "rt-9"); put("stored_session_id", "s-9")
            put("decision", buildJsonObject {
                put("kind", "approval"); put("session_key", "rt-9"); put("description", "Force-push main?")
                put("choices", kotlinx.serialization.json.JsonArray(listOf("once", "deny", " ").map(::JsonPrimitive)))
            })
        }
        val action = PushDispatch.plan(sealed("approval.needed", content, compress = true)) as PushAction.Notify
        assertEquals("rt-9", action.sessionId)
        assertEquals("s-9", action.durableSessionId)
        assertEquals("coder", action.profile) // the key's profile when the content names none
        val approval = action.approval!!
        assertEquals("rt-9", approval.sessionId)
        assertEquals("Force-push main?", approval.description)
        assertEquals(listOf("once", "deny"), approval.choices)

        for (type in listOf("response.ready", "turn.failed", "background_task.finished", "input.needed")) {
            val notify = PushDispatch.plan(sealed(type, buildJsonObject { put("session_id", "s"); put("profile", "work") })) as PushAction.Notify
            assertEquals(type, notify.type)
            assertEquals("work", notify.profile)
            assertNull(notify.title)
        }
        // Nothing to open: wake instead.
        assertEquals("no session", (PushDispatch.plan(sealed("response.ready", buildJsonObject { put("title", "x") })) as PushAction.Wake).reason)
    }

    @Test
    fun callsRingWithTheAuthenticatedIssueTime() {
        val content = buildJsonObject {
            put("session_id", "rt-job")
            put("call", buildJsonObject { put("id", "call-00001"); put("kind", "done"); put("title", "Nightly build") })
        }
        val ring = PushDispatch.plan(sealed(HermesCallRequest.TYPE, content)) as PushAction.Ring
        assertEquals("rt-job", ring.target.sessionId)
        assertEquals("call-00001", ring.target.call.id)
        assertEquals("Nightly build", ring.target.call.title)
        assertEquals(dashboardID, ring.target.dashboardID)
        assertEquals(issuedAt * 1000, ring.target.sentAtMs)
    }

    @Test
    fun locationRequestsAnswerForTheKeysDashboard() {
        val locate = PushDispatch.plan(sealed(LocationLocateRequest.TYPE, buildJsonObject { put("locate", buildJsonObject { put("id", "loc_12345") }) })) as PushAction.Locate
        assertEquals(LocationLocateRequest("loc_12345", "coder", gatewayID, dashboardID, LocationLocateRequest.LOCATE), locate.request)

        val mapsID = "m_abcdefghijklmnop"
        val maps = PushDispatch.plan(sealed(LocationLocateRequest.TYPE, buildJsonObject { put("profile", "work"); put("maps", buildJsonObject { put("id", mapsID) }) })) as PushAction.Locate
        assertEquals(LocationLocateRequest.MAPS, maps.request.kind)
        assertEquals("work", maps.request.profile)
        assertEquals(mapsID, maps.request.id)

        assertEquals("no request", (PushDispatch.plan(sealed(LocationLocateRequest.TYPE, buildJsonObject { put("maps", buildJsonObject { put("id", "../etc") }) })) as PushAction.Wake).reason)
    }

    @Test
    fun unknownTypesAndBadDecisionsWake() {
        assertEquals(PushAction.Wake("kanban.changed", "unhandled"), PushDispatch.plan(sealed("kanban.changed", buildJsonObject { put("x", 1) })))
        // A decision bound to another request than the envelope's is a forgery.
        val decision = buildJsonObject { put("session_id", "s"); put("decision", buildJsonObject { put("kind", "clarify"); put("request_id", "conduit-push-other") }) }
        assertTrue(sealed("input.needed", decision, req = "conduit-push-mine") is PushE2E.Evaluation.Untrusted)
    }

    // endregion

    // region Replays

    @Test
    fun seenStoreRecordsEachMessageOncePerUse() {
        val store = PushSeenStore(MemoryTextStore())
        assertTrue(store.insert("k:m", "delivered", now))
        assertFalse(store.insert("k:m", "delivered", now))
        assertTrue(store.insert("k:m", "rang", now))
        assertTrue(store.insert("k:m", "delivered", now + PushSeenStore.RETENTION_MS + 1))

        // Claims: one at a time, released on failure, final once answered.
        assertTrue(store.claim("k:l", "locate", 30_000, now))
        assertFalse(store.claim("k:l", "locate", 30_000, now + 1_000))
        assertTrue(store.claim("k:l", "locate", 30_000, now + 31_000))
        store.remove("k:l", "locate")
        assertTrue(store.claim("k:l", "locate", 30_000, now + 32_000))
        store.markAnswered("k:l", "locate", now + 33_000)
        assertFalse(store.claim("k:l", "locate", 30_000, now + 99_000))
    }

    // endregion

    // region Registration

    @Test
    fun registrationBodyCarriesWhatIOSRegisters() {
        val keys = AndroidPushWire.PreferenceKeys("a", "i", "r", "f", "b", "cs", "as", "p", "d")
        val prefs = AndroidPushWire.preferences(true, { it != "p" }, keys)
        val endpoint = AndroidPushWire.Endpoint("https://ntfy.example/upAbC?up=1", "BPub", "authSecret")
        val body = AndroidPushWire.registerBody(installationID, endpoint, prefs, kid, secret, "0.1.0", "Google Pixel 8")
        assertEquals(installationID, body["installation_id"].str)
        assertEquals("https://ntfy.example/upAbC?up=1", body["endpoint"].str)
        assertEquals("android-unifiedpush", body["platform"].str)
        assertEquals("0.1.0", body["app_version"].str)
        assertEquals("Google Pixel 8", body["device"].str)
        val e2e = body["e2e"].obj!!
        assertEquals(kid, e2e["kid"].str)
        assertEquals(SealCrypto.base64Url(secret), e2e["secret"].str)
        assertEquals("BPub", body["webpush"].obj!!["p256dh"].str)
        val sent = body["preferences"].obj!!
        assertEquals(
            setOf("enabled", "approval_needed", "input_needed", "response_ready", "turn_failed", "background_task_finished", "completion_sound", "attention_sound", "show_previews", "decision_cards"),
            sent.keys,
        )
        assertEquals("false", sent["show_previews"].toString())
        assertNull(AndroidPushWire.registerBody(installationID, endpoint.copy(auth = null), prefs, kid, secret, "0.1.0", "x")["webpush"])
    }

    @Test
    fun aRegistrationCountsOnlyWhenThePluginConfirmsTheKey() {
        fun outcome(json: String) = AndroidPushWire.registerOutcome(ConduitJson.parseToJsonElement(json).obj!!, installationID, kid)
        assertEquals(
            AndroidPushWire.RegisterOutcome.Registered(gatewayID),
            outcome("""{"ok":true,"installation_id":"$installationID","gateway_id":"$gatewayID","kid":"$kid"}"""),
        )
        assertTrue(outcome("""{"ok":true,"gateway_id":"$gatewayID","kid":"other"}""") is AndroidPushWire.RegisterOutcome.Refused)
        assertTrue(outcome("""{"ok":true,"kid":"$kid"}""") is AndroidPushWire.RegisterOutcome.Refused)
        assertTrue(outcome("""{"ok":true,"installation_id":"other","gateway_id":"g","kid":"$kid"}""") is AndroidPushWire.RegisterOutcome.Refused)
        assertTrue(outcome("""{"ok":false,"error":"nope"}""") is AndroidPushWire.RegisterOutcome.Refused)
    }

    @Test
    fun keyRecordsRoundTrip() {
        val text = PushKeyRecord.encodeAll(listOf(record, record.copy(kid = "f".repeat(32), dashboardID = null, profile = null)))
        val back = PushKeyRecord.decodeAll(text)
        assertEquals(2, back.size)
        assertEquals(record, back[0])
        assertNull(back[1].profile)
        assertNotEquals(record, back[1])
        assertTrue(PushKeyRecord.decodeAll("[{\"kid\":\"x\"}]").isEmpty())
    }

    // endregion
}
