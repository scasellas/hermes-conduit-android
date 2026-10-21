package app.hermesconduit.android

import app.hermesconduit.android.data.MemoryTextStore
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.LocationBinding
import app.hermesconduit.android.model.LocationCrypto
import app.hermesconduit.android.model.LocationLocateRequest
import app.hermesconduit.android.model.LocationSessionKey
import app.hermesconduit.android.model.LocationSharedConfig
import app.hermesconduit.android.model.LocationSharingSettings
import app.hermesconduit.android.model.MapsAnswer
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.ui.location.LocationKeyStore
import app.hermesconduit.android.ui.location.LocationSharing
import app.hermesconduit.android.ui.location.LocationSyncEngine
import app.hermesconduit.android.ui.location.MapsLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A maps request that came by push (LocationSharing.answerMaps, iOS
 * LocationPushService.answerMaps): fetched sealed from the host for the
 * bound dashboard only, answered here, and the sealed answer posted back.
 */
class LocationPushAnswerTest {
    private val at = 1_760_430_000_000L
    private val dashboard = "0F5C8A34-1B2D-4E5F-8A9B-0C1D2E3F4A5B"
    private val mapsID = "m_0123456789abcdefgh"

    private class Host(val key: LocationSessionKey, val request: JsonObject) {
        val calls = mutableListOf<Pair<String, String>>()
        var posted: JsonObject? = null
        var queries = mutableListOf<Map<String, String?>>()

        suspend fun handle(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
            calls += method to path
            queries += query
            val id = path.substringAfterLast("/")
            return if (method == "GET") {
                val sealed = SealCrypto.seal(request.toString().toByteArray(), key.mapsRequestKey!!, LocationCrypto.mapsAAD("maps-request", key.kid, key.deviceID, id))
                buildJsonObject { put("v", 1); put("nonce", sealed.nonce); put("ct", sealed.ct) }
            } else {
                posted = body as JsonObject
                buildJsonObject { put("ok", true) }
            }
        }

        fun answer(id: String): JsonObject {
            val opened = SealCrypto.open(posted!!, key.mapsAnswerKey!!, LocationCrypto.mapsAAD("maps-answer", key.kid, key.deviceID, id))
            return ConduitJson.parseToJsonElement(opened.decodeToString()).obj!!
        }
    }

    private fun sharing(config: LocationSharedConfig, key: LocationSessionKey, host: Host): LocationSharing {
        val keys = LocationKeyStore(MemoryTextStore())
        keys.save(key)
        return LocationSharing(
            configStore = MemoryTextStore(config.encode()),
            unsentStore = MemoryTextStore(),
            engine = LocationSyncEngine(MemoryTextStore(), keys, now = { at }),
            fixes = null,
            monitor = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            requestProvider = { binding -> if (binding.dashboardID == dashboard) host::handle else null },
            now = { at },
        )
    }

    private fun config(background: Boolean = true, pausedUntil: Long? = null) = LocationSharedConfig(
        binding = LocationBinding(dashboard, "coder", "hermes.example.com"),
        settings = LocationSharingSettings(background = background, pausedUntil = pausedUntil),
        deviceID = "device-push-1",
    )

    private fun key() = LocationSessionKey("kid-push-1", SealCrypto.randomBytes(32), "device-push-1", mapsRequestKey = SealCrypto.randomBytes(32), mapsAnswerKey = SealCrypto.randomBytes(32))

    private fun request(id: String = mapsID, dashboardID: String? = dashboard, profile: String? = "coder") =
        LocationLocateRequest(id, profile, "gw", dashboardID, LocationLocateRequest.MAPS)

    private val lookupRequest = buildJsonObject { put("id", mapsID); put("action", "lookup"); put("query", "Ritz London") }

    @Test
    fun aMapsRequestIsFetchedAnsweredAndSealedBack() = runBlocking {
        val key = key()
        val host = Host(key, lookupRequest)
        var looked = 0
        val lookup: MapsLookup = { maps, me, _, _ ->
            looked += 1
            assertEquals("Ritz London", maps.query)
            assertEquals(null, me)
            MapsAnswer.Ok(buildJsonObject { put("places", kotlinx.serialization.json.JsonArray(emptyList())) })
        }
        assertTrue(sharing(config(), key, host).answerMaps(request(), lookup))
        assertEquals(1, looked)
        assertEquals(listOf("GET" to "api/plugins/conduit_push/location/maps/$mapsID", "POST" to "api/plugins/conduit_push/location/maps/$mapsID"), host.calls)
        assertEquals(mapOf("device_id" to "device-push-1", "kid" to "kid-push-1"), host.queries.first())
        val answer = host.answer(mapsID)
        assertEquals(mapsID, answer["id"].str)
        assertEquals(true, answer["ok"].bool)
    }

    @Test
    fun whileSharingIsPausedTheAnswerIsPaused() = runBlocking {
        val key = key()
        val host = Host(key, lookupRequest)
        val lookup: MapsLookup = { _, _, _, _ -> error("no lookup while paused") }
        assertTrue(sharing(config(pausedUntil = at + 3_600_000), key, host).answerMaps(request(), lookup))
        val answer = host.answer(mapsID)
        assertEquals(false, answer["ok"].bool)
        assertEquals("paused", answer["error"].str)
    }

    @Test
    fun onlyTheBoundDashboardAndProfileAreAnswered() = runBlocking {
        val key = key()
        val host = Host(key, lookupRequest)
        val lookup: MapsLookup = { _, _, _, _ -> error("not for this phone") }
        val sharing = sharing(config(), key, host)
        assertFalse(sharing.answerMaps(request(dashboardID = "11111111-1111-4111-8111-111111111111"), lookup))
        assertFalse(sharing.answerMaps(request(profile = "work"), lookup))
        assertFalse(sharing.answerMaps(request(id = "m_bad/../x"), lookup))
        // A locate needs a fix, and this phone has no location provider here.
        assertFalse(sharing.answerLocate(LocationLocateRequest("loc_12345", "coder", "gw", dashboard)))
        assertTrue(host.calls.isEmpty())
        // A key from before maps can't open the request.
        val old = key.copy(mapsRequestKey = null, mapsAnswerKey = null)
        assertFalse(sharing(config(), old, Host(key, lookupRequest)).answerMaps(request(), lookup))
    }

    @Test
    fun aRequestForAnotherIdIsNotAnswered() = runBlocking {
        val key = key()
        val host = Host(key, buildJsonObject { put("id", "m_someoneelses000000"); put("action", "lookup"); put("query", "x") })
        val lookup: MapsLookup = { _, _, _, _ -> error("not this request") }
        assertFalse(sharing(config(), key, host).answerMaps(request(), lookup))
        assertEquals(listOf("GET" to "api/plugins/conduit_push/location/maps/$mapsID"), host.calls)
    }
}
