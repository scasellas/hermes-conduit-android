package app.hermesconduit.android

import app.hermesconduit.android.data.MemoryTextStore
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.LocationArea
import app.hermesconduit.android.model.LocationBatch
import app.hermesconduit.android.model.LocationBinding
import app.hermesconduit.android.model.LocationCrypto
import app.hermesconduit.android.model.LocationFixRecord
import app.hermesconduit.android.model.LocationFixSource
import app.hermesconduit.android.model.LocationIDs
import app.hermesconduit.android.model.LocationLocateRequest
import app.hermesconduit.android.model.LocationPlaceEvent
import app.hermesconduit.android.model.LocationPlaceKind
import app.hermesconduit.android.model.LocationPrecision
import app.hermesconduit.android.model.LocationPrecisionRules
import app.hermesconduit.android.model.LocationRawFix
import app.hermesconduit.android.model.LocationRead
import app.hermesconduit.android.model.LocationRule
import app.hermesconduit.android.model.LocationSavedPlace
import app.hermesconduit.android.model.LocationSessionKey
import app.hermesconduit.android.model.LocationSharedConfig
import app.hermesconduit.android.model.LocationSharingSettings
import app.hermesconduit.android.model.LocationUnsent
import app.hermesconduit.android.model.MapsAnswer
import app.hermesconduit.android.model.MapsFailure
import app.hermesconduit.android.model.MapsPlaceRef
import app.hermesconduit.android.model.MapsRequest
import app.hermesconduit.android.model.MapsVoiceRules
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.double
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.long
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.device.SyncRequest
import app.hermesconduit.android.ui.location.LocationFixProvider
import app.hermesconduit.android.ui.location.LocationBackgroundMonitor
import app.hermesconduit.android.ui.location.LocationKeyStore
import app.hermesconduit.android.ui.location.LocationSyncEngine
import app.hermesconduit.android.ui.location.MapsVoiceTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Port of ConduitTests/LocationSharingTests.swift. */
class LocationSharingTest {
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val at = 1_760_430_000_000L
    private val home = LocationSavedPlace("p_home", "Home", LocationPlaceKind.HOME, latitude = 51.5321, longitude = -0.0755, radius = 150.0)
    private val work = LocationSavedPlace("p_work", "Work", LocationPlaceKind.WORK, latitude = 51.5155, longitude = -0.0922, radius = 200.0)
    private val area = LocationArea("Shoreditch", "London", "England", "United Kingdom", "GB")

    private fun fix(lat: Double, lon: Double, accuracy: Double = 12.0, source: LocationFixSource = LocationFixSource.MESSAGE) =
        LocationFixRecord("f_test", source, LocationRawFix(lat, lon, accuracy, at, 1.23, 87.6), area)

    private fun json(fix: LocationFixRecord, precision: LocationPrecision, places: List<LocationSavedPlace> = listOf(home, work), approximate: Boolean = false) =
        LocationPrecisionRules.json(fix, precision, places, approximate, london)

    private fun config(background: Boolean = true, talking: Boolean = false, pausedUntil: Long? = null) = LocationSharedConfig(
        binding = LocationBinding("0F5C8A34-1B2D-4E5F-8A9B-0C1D2E3F4A5B", "coder", "hermes.example.com"),
        settings = LocationSharingSettings(background = background, talking = talking, pausedUntil = pausedUntil),
    )

    private fun vectorKey(): LocationSessionKey =
        LocationCrypto.agree(SealCrypto.base64UrlDecoded(DEVICE_PRIVATE)!!, "device-vector-1", HOST_PUBLIC, KID)

    @Test
    fun keyAgreementMatchesTheNotifierVector() {
        val device = SealCrypto.KeyPair(SealCrypto.base64UrlDecoded(DEVICE_PRIVATE)!!)
        assertEquals("NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ", SealCrypto.base64Url(device.publicKey))
        val key = vectorKey()
        assertEquals(KID, key.kid)
        assertEquals("device-vector-1", key.deviceID)
        assertEquals("e97fd9902f44b9a9c5ffacd40612f58a471a56b5ab28c6e66f87efc04840fa6e", SealCrypto.hex(key.key))
        assertEquals("conduit-location/1\ningest\nkid=$KID\ndev=device-vector-1\nbatch=batch-vector-1",
            LocationCrypto.aad(key.kid, key.deviceID, "batch-vector-1").decodeToString())
        val envelope = LocationCrypto.envelope("""{"batch_id":"batch-vector-1"}""".toByteArray(), key, "batch-vector-1", ByteArray(12))
        assertEquals("upKO2AMUfh95gixFbp-_E9hsTT76TlUyHHlwzdREWwCk0Gf-Nr6Oz98FPEP_", envelope["ct"].str)
        assertEquals("AAAAAAAAAAAAAAAA", envelope["nonce"].str)
        assertEquals("device-vector-1", envelope["device_id"].str)
        assertEquals(KID, envelope["kid"].str)
        assertEquals("batch-vector-1", envelope["batch_id"].str)
        assertEquals(1L, envelope["v"].long)
        try {
            LocationCrypto.agree(SealCrypto.base64UrlDecoded(DEVICE_PRIVATE)!!, "device-vector-1", HOST_PUBLIC, "0".repeat(32))
            fail()
        } catch (e: SealCrypto.Failure) {
            assertEquals("keyIDMismatch", e.reason)
        }
    }

    @Test
    fun placesPrecisionNamesThePlaceAndNothingElse() {
        val json = json(fix(51.5322, -0.0754), LocationPrecision.PLACES)
        assertEquals("p_home", json["place_id"].str)
        assertEquals("places", json["precision"].str)
        assertEquals("message", json["source"].str)
        for (key in listOf("lat", "lon", "accuracy_m", "area", "distances_m", "speed_mps", "course")) assertNull(key, json[key])
        val away = json(fix(51.60, -0.20), LocationPrecision.PLACES)
        assertNull(away["place_id"])
        assertNull(away["lat"])
    }

    @Test
    fun areaPrecisionRoundsToAboutAKilometre() {
        val json = json(fix(51.54367, -0.08123, 30.0), LocationPrecision.AREA)
        assertEquals(51.54, json["lat"].double)
        assertEquals(-0.08, json["lon"].double)
        assertEquals(1_000L, json["accuracy_m"].long)
        assertNull(json["speed_mps"])
        assertNull(json["course"])
        assertNull(json["place_id"])
        assertEquals("London", json["area"].obj?.get("locality").str)
        assertEquals("GB", json["area"].obj?.get("country_code").str)
        val distances = json["distances_m"].obj!!
        assertEquals(setOf("p_home", "p_work"), distances.keys)
        distances.values.forEach { assertEquals(0L, it.long!! % 100) }
        assertEquals(1_800L, json(fix(51.54367, -0.08123, 1_800.0), LocationPrecision.AREA)["accuracy_m"].long)
    }

    @Test
    fun precisePrecisionKeepsTheMeasurement() {
        val json = json(fix(51.5322, -0.0754), LocationPrecision.PRECISE)
        assertEquals(51.5322, json["lat"].double)
        assertEquals(-0.0754, json["lon"].double)
        assertEquals(12L, json["accuracy_m"].long)
        assertEquals("p_home", json["place_id"].str)
        assertEquals(1.2, json["speed_mps"].double)
        assertEquals(88.0, json["course"].double)
        val distances = json["distances_m"].obj!!
        distances.values.forEach { assertEquals(0L, it.long!! % 10) }
        assertTrue(distances["p_home"].long!! < 50)
    }

    @Test
    fun aPrivatePlaceSendsOnlyItsID() {
        val privateHome = home.copy(isPrivate = true)
        for (precision in LocationPrecision.entries) {
            val json = json(fix(51.5322, -0.0754), precision, listOf(privateHome, work))
            assertEquals(precision.raw, "p_home", json["place_id"].str)
            assertEquals(precision.raw, setOf("id", "at", "source", "precision", "place_id"), json.keys)
        }
        val away = json(fix(51.54, -0.08), LocationPrecision.PRECISE, listOf(privateHome, work))
        assertNull(away["distances_m"].obj?.get("p_home"))
        assertNotNull(away["distances_m"].obj?.get("p_work"))
        val listed = LocationPrecisionRules.placesJSON(listOf(privateHome, work))
        assertEquals(LocationPrecisionRules.PRIVATE_PLACE_NAME, listed.first().obj?.get("name").str)
        assertEquals(true, listed.first().obj?.get("private").bool)
        assertEquals("other", listed.first().obj?.get("kind").str)
        assertEquals("work", listed.last().obj?.get("kind").str)
        assertEquals("Work", listed.last().obj?.get("name").str)
    }

    @Test
    fun approximateOnlyNamesNoPlaceAndIsNeverPrecise() {
        val json = json(fix(51.5322, -0.0754), LocationPrecision.PRECISE, approximate = true)
        assertEquals("area", json["precision"].str)
        assertNull(json["place_id"])
        assertEquals(51.53, json["lat"].double)
        val places = json(fix(51.5322, -0.0754), LocationPrecision.PLACES, listOf(work), approximate = true)
        assertEquals("places", places["precision"].str)
        assertEquals(setOf("id", "at", "source", "precision"), places.keys)
    }

    @Test
    fun aPrivatePlaceIsHiddenEvenInARoughFix() {
        val privateHome = home.copy(isPrivate = true)
        for ((accuracy, approximate) in listOf(12.0 to true, 3_000.0 to false, 3_000.0 to true)) {
            val json = json(fix(51.5322, -0.0754, accuracy), LocationPrecision.AREA, listOf(privateHome, work), approximate)
            assertEquals("p_home", json["place_id"].str)
            assertEquals(setOf("id", "at", "source", "precision", "place_id"), json.keys)
        }
    }

    @Test
    fun aStoredConfigurationToleratesMissingKeysAndBadPlaces() {
        val text = """
        {"deviceID": "0123456789abcdef0123456789abcdef", "settings": {"talking": true},
         "places": [{"id": "p_home", "latitude": 51.5, "longitude": -0.07}, {"id": "p_broken", "name": "No coordinate"}]}
        """
        val config = LocationSharedConfig.decode(text)!!
        assertEquals("0123456789abcdef0123456789abcdef", config.deviceID)
        assertTrue(config.settings.talking)
        assertEquals(LocationPrecision.AREA, config.settings.backgroundPrecision)
        assertEquals(LocationSharingSettings.DEFAULT_RETENTION, config.settings.retentionDays)
        assertEquals(listOf("p_home"), config.places.map { it.id })
        assertEquals(LocationPlaceKind.OTHER, config.places.first().kind)
        assertEquals(LocationSavedPlace.DEFAULT_RADIUS, config.places.first().radius, 0.0)
        assertNull(config.binding)

        val unbound = LocationSharedConfig.decode("""{"binding": {"dashboardID": 7}, "settings": {"talking": true, "background": true}}""")!!
        assertNull(unbound.binding)
        assertFalse(unbound.settings.talking || unbound.settings.background)

        val full = config().copy(places = listOf(home))
        assertEquals(full, LocationSharedConfig.decode(full.encode()))
        assertNull("A damaged file is unreadable, not missing", LocationSharedConfig.decode("{not json"))
        assertEquals("0123456789abcdef0123456789abcdef", LocationSharedConfig.salvageDeviceID("""{"deviceID":"0123456789abcdef0123456789abcdef", oops"""))
    }

    @Test
    fun locationBatchShape() {
        val settings = LocationSharingSettings(talking = true, background = true, retentionDays = 30)
        val batch = LocationBatch(
            sentAtMs = at, settings = settings, zone = london,
            places = listOf(home, work), knownPlaces = listOf(home, work),
            fix = fix(51.5322, -0.0754, source = LocationFixSource.LOCATE),
            events = listOf(LocationPlaceEvent("e_1", LocationPlaceEvent.LEAVE, "p_home", at, false)),
            locateID = "l_0123456789abcdef",
        )
        val json = ConduitJson.parseToJsonElement(batch.plaintext().decodeToString()).obj!!
        assertEquals(batch.batchID, json["batch_id"].str)
        assertTrue(LocationIDs.isValidWireID(batch.batchID))
        assertEquals("2025-10-14T09:20:00+01:00", json["sent_at"].str)
        assertEquals("l_0123456789abcdef", json["locate_id"].str)
        val sharing = json["sharing"].obj!!
        assertEquals(true, sharing["talking"].bool)
        assertEquals("precise", sharing["talking_precision"].str)
        assertEquals("area", sharing["background_precision"].str)
        assertEquals(30L, sharing["retention_days"].long)
        assertTrue(sharing["paused_until"] is JsonNull)
        assertEquals("Europe/London", sharing["time_zone"].str)
        val places = json["places"].arr!!
        assertEquals(2, places.size)
        places.forEach { assertEquals(setOf("id", "name", "kind", "private"), it.obj!!.keys) }
        val fixJSON = json["fix"].obj!!
        assertEquals("locate", fixJSON["source"].str)
        assertEquals("A locate answers at the background precision", "area", fixJSON["precision"].str)
        assertEquals(51.53, fixJSON["lat"].double)
        val events = json["events"].arr!!
        assertEquals("leave", events.first().obj?.get("kind").str)
        assertEquals("p_home", events.first().obj?.get("place_id").str)
        assertEquals(false, events.first().obj?.get("private").bool)
    }

    @Test
    fun aSettingsOnlyBatchCarriesNoLocation() {
        assertEquals(setOf("batch_id", "sent_at", "sharing"), LocationBatch(sentAtMs = at, settings = LocationSharingSettings()).json().keys)
    }

    @Test
    fun pauseAndLayersDecideWhatIsShared() {
        var settings = LocationSharingSettings()
        assertFalse(settings.shares(LocationFixSource.MESSAGE, at))
        assertFalse(settings.shares(LocationFixSource.PLACE, at))
        settings = settings.copy(talking = true)
        assertTrue(settings.shares(LocationFixSource.MESSAGE, at))
        assertTrue(settings.shares(LocationFixSource.CALL, at))
        assertFalse(settings.shares(LocationFixSource.VISIT, at))
        assertFalse(settings.shares(LocationFixSource.LOCATE, at))
        settings = settings.copy(background = true)
        assertTrue(settings.shares(LocationFixSource.LOCATE, at))
        assertEquals(LocationPrecision.PRECISE, settings.precision(LocationFixSource.MESSAGE))
        assertEquals(LocationPrecision.AREA, settings.precision(LocationFixSource.SIGNIFICANT))
        settings = settings.copy(pausedUntil = at + 3_600_000)
        assertTrue(settings.isPaused(at))
        assertFalse(settings.shares(LocationFixSource.MESSAGE, at))
        assertEquals(app.hermesconduit.android.model.LocationTime.format(Instant.ofEpochMilli(at + 3_600_000), ZoneId.of("UTC")),
            settings.json(at, ZoneId.of("UTC"))["paused_until"].str)
        assertTrue(settings.shares(LocationFixSource.MESSAGE, at + 3_601_000))
        assertTrue(settings.json(at + 3_601_000, ZoneId.of("UTC"))["paused_until"] is JsonNull)
        settings = settings.copy(pausedUntil = LocationSharingSettings.UNTIL_TURNED_ON)
        assertTrue(settings.isPaused(at + 10L * 365 * 86_400_000))
        assertTrue(settings.json(at, ZoneId.of("UTC"))["paused_until"].str!!.startsWith("4001-"))
    }

    @Test
    fun retentionIsOneOfTheHostsChoices() {
        assertEquals(7, LocationSharingSettings().retentionDays)
        for (days in LocationSharingSettings.RETENTION_CHOICES) {
            assertEquals(days.toLong(), LocationSharingSettings(retentionDays = days).json(at, london)["retention_days"].long)
        }
        assertEquals(7L, LocationSharingSettings(retentionDays = 3).json(at, london)["retention_days"].long)
    }

    @Test
    fun unsentKeepsTheLatestFixAndTheLastFiftyEvents() {
        var unsent = LocationUnsent()
        val newer = fix(51.5, -0.1)
        val older = fix(51.4, -0.2).let { it.copy(id = "f_older", raw = it.raw.copy(timestampMs = at - 600_000)) }
        unsent = unsent.keeping(newer, emptyList()).keeping(older, emptyList())
        assertEquals("f_test", unsent.fix?.id)
        val events = (0 until 60).map { LocationPlaceEvent("e_$it", LocationPlaceEvent.ARRIVE, "p_home", at + it * 1000L, false) }
        unsent = unsent.keeping(null, events)
        assertEquals(LocationUnsent.MAX_EVENTS, unsent.events.size)
        assertEquals("e_10", unsent.events.first().id)
        assertEquals("e_59", unsent.events.last().id)
        unsent = unsent.removing("f_test", unsent.events.take(10).map { it.id }.toSet())
        assertNull(unsent.fix)
        assertEquals(40, unsent.events.size)
        val reread = LocationUnsent.decode(unsent.copy(inside = mapOf("p_home" to true)).encode())
        assertEquals(40, reread.events.size)
        assertEquals(true, reread.inside["p_home"])
    }

    @Test
    fun theHostsKeyErrorsAreToldApartFromOtherConflicts() {
        assertTrue(LocationSyncEngine.needsNewKey("unknown_key"))
        assertTrue(LocationSyncEngine.needsNewKey("bad_seal"))
        assertFalse(LocationSyncEngine.needsNewKey("layer_off"))
    }

    private class FakeLocationHost {
        val hostKey = SealCrypto.newKeyPair()
        var keyRequests = 0
        var ingestError: Exception? = null
        var statusError: Exception? = null
        val handle: SyncRequest = { _, path, _, body ->
            when {
                path.endsWith("/key") -> {
                    keyRequests += 1
                    val devicePublic = SealCrypto.base64UrlDecoded(body!!.obj!!["public_key"].str!!)!!
                    buildJsonObject {
                        put("host_public_key", SealCrypto.base64Url(hostKey.publicKey))
                        put("kid", LocationCrypto.keyID(devicePublic, hostKey.publicKey))
                    }
                }
                path.endsWith("/ingest") -> ingestError?.let { throw it } ?: buildJsonObject { put("ok", true) }
                path.endsWith("/status") -> statusError?.let { throw it } ?: buildJsonObject { put("ok", true) }
                else -> buildJsonObject { put("ok", true) }
            }
        }
    }

    @Test
    fun anIngest404KeepsTheKeyAndIsThePluginMissingOnlyWhenStatusIsGoneToo() = runBlocking {
        val host = FakeLocationHost()
        val keys = LocationKeyStore(MemoryTextStore())
        val engine = LocationSyncEngine(MemoryTextStore(), keys, now = { at })
        val config = config()
        assertEquals(LocationSyncEngine.SendOutcome.SENT, engine.send(LocationBatch(sentAtMs = at, settings = config.settings), config, host.handle))
        val key = keys.load()
        host.ingestError = DashboardHttpException(404, "")
        assertEquals(LocationSyncEngine.SendOutcome.FAILED, engine.send(LocationBatch(sentAtMs = at, settings = config.settings), config, host.handle))
        assertNull("A proxy's 404 isn't a missing plugin", engine.state.value.pause)
        assertEquals(key, keys.load())
        host.statusError = DashboardHttpException(404, "")
        engine.send(LocationBatch(sentAtMs = at, settings = config.settings), config, host.handle)
        assertEquals("pluginMissing", engine.state.value.pause?.kind)
        assertEquals(1, host.keyRequests)
    }

    @Test
    fun voiceSealingMatchesTheNotifierVector() {
        val key = vectorKey()
        assertTrue(key.readsByVoice)
        assertEquals("55cf19b6e1118491dc136b9c94af8af83891b19673744e64c44200411ea93424", SealCrypto.hex(key.voiceRequestKey!!))
        assertEquals("d0c6c9aa90c735b1bcf78368794430d41599835b930730a7ad288fc4a010d53d", SealCrypto.hex(key.voiceAnswerKey!!))
        assertEquals("conduit-location/1\nvoice-request\ntool=conduit_location\nkid=$KID\ndev=device-vector-1\ncall=call-vector-1",
            LocationCrypto.voiceAAD("request", "conduit_location", key.kid, key.deviceID, "call-vector-1").decodeToString())
        val request = """{"call_id":"call-vector-1","args":{"action":"now"},"provider":"gemini-live"}"""
        val envelope = LocationCrypto.voiceRequest("conduit_location", request.toByteArray(), key, "call-vector-1", ByteArray(12))
        assertEquals("Pz9gGtPpraVru4dOPXv0TcJEUmj6vNdCFECj8qkccvaUZOZDCGdFpfh-OY-1QHSBAVebQ0JZuQjZ0WpA5M5eiv7WJvwXkjdfpT0FJQHgX7j13RSFFKcJR1KMReU", envelope["ct"].str)
        assertEquals("conduit_location", envelope["tool"].str)
        assertEquals("call-vector-1", envelope["call_id"].str)
        val answer = buildJsonObject {
            put("nonce", "AAAAAAAAAAAAAAAA")
            put("ct", "4L3Edq2Pqul5L7IkSFV1mcpEe62ksOCr_58lP-hpj0EifhOgs7WlfcyZHScBjna8zBbtma66vY4xJQ6FNc_YzA")
        }
        assertEquals(true, LocationCrypto.openVoiceAnswer(answer, "conduit_location", key, "call-vector-1").obj?.get("ok").bool)
        for ((tool, call) in listOf("conduit_location" to "call-other", "conduit_health" to "call-vector-1")) {
            try {
                LocationCrypto.openVoiceAnswer(answer, tool, key, call)
                fail("An answer is bound to its call and tool")
            } catch (_: SealCrypto.Failure) {
            }
        }
        val old = LocationSessionKey.decode("""{"kid":"k","key":"AAAA","deviceID":"device-vector-1"}""")!!
        assertFalse(old.readsByVoice)
        try {
            LocationCrypto.voiceRequest("conduit_location", ByteArray(0), old, "c")
            fail()
        } catch (e: SealCrypto.Failure) {
            assertEquals("noVoiceKey", e.reason)
        }
    }

    @Test
    fun voiceReadConsentIsPerModeAndOnlyWhileSharing() {
        var config = config(background = false, talking = false).withVoiceRead("gemini-live", true)
        assertFalse(config.letsVoiceRead("gemini-live", at))
        config = config.copy(settings = config.settings.copy(talking = true))
        assertTrue(config.letsVoiceRead("gemini-live", at))
        assertTrue(config.readsByVoice(at))
        assertFalse(config.letsVoiceRead("gpt-live", at))
        config = config.copy(settings = config.settings.copy(pausedUntil = at + 3_600_000))
        assertFalse(config.letsVoiceRead("gemini-live", at))
        assertFalse(config.readsByVoice(at))
        assertTrue(config.letsVoiceRead("gemini-live", at + 7_200_000))
        config = config.copy(settings = config.settings.copy(pausedUntil = null))
        assertEquals(listOf("gemini-live"), LocationSharedConfig.decode(config.encode())?.voiceReaders)
        assertFalse(config.copy(binding = null).letsVoiceRead("gemini-live", at))
        assertNull(config.withVoiceRead("gemini-live", false).voiceReaders)
        assertNull(LocationSharedConfig.decode("""{"deviceID": "0123456789abcdef"}""")?.voiceReaders)
    }

    @Test
    fun aKeyFromBeforeVoiceReadsIsSetUpAgainOnlyOnceAVoiceModeMayRead() = runBlocking {
        val host = FakeLocationHost()
        var config = config()
        val keys = LocationKeyStore(MemoryTextStore())
        val old = LocationSessionKey("old", ByteArray(32), config.deviceID, mapsRequestKey = ByteArray(32), mapsAnswerKey = ByteArray(32))
        assertTrue(keys.save(old))
        val engine = LocationSyncEngine(MemoryTextStore(), keys, now = { at })
        val batch = LocationBatch(sentAtMs = at, settings = config.settings)
        assertEquals(LocationSyncEngine.SendOutcome.SENT, engine.send(batch, config, host.handle))
        assertEquals(0, host.keyRequests)
        assertEquals(old, keys.load())
        assertNull(engine.voiceReadKey(config.deviceID))
        config = config.withVoiceRead("grok-live", true)
        assertEquals(LocationSyncEngine.SendOutcome.SENT, engine.send(batch, config, host.handle))
        assertEquals(1, host.keyRequests)
        assertTrue(engine.voiceReadKey(config.deviceID)!!.readsByVoice)
        assertNull(engine.voiceReadKey("another-device"))
        engine.send(batch, config, host.handle)
        assertEquals(1, host.keyRequests)
    }

    @Test
    fun aKeyFromBeforeMapsIsSetUpAgainOnceWhileSharingInTheBackground() = runBlocking {
        val host = FakeLocationHost()
        val keys = LocationKeyStore(MemoryTextStore())
        val talkingOnly = config(background = false, talking = true)
        val old = LocationSessionKey("old", ByteArray(32), talkingOnly.deviceID)
        keys.save(old)
        val engine = LocationSyncEngine(MemoryTextStore(), keys, now = { at })
        engine.send(LocationBatch(sentAtMs = at, settings = talkingOnly.settings), talkingOnly, host.handle)
        assertEquals(0, host.keyRequests)
        assertEquals(old, keys.load())
        val background = talkingOnly.copy(settings = talkingOnly.settings.copy(background = true))
        val batch = LocationBatch(sentAtMs = at, settings = background.settings)
        assertEquals(LocationSyncEngine.SendOutcome.SENT, engine.send(batch, background, host.handle))
        assertEquals(1, host.keyRequests)
        assertEquals(true, keys.load()?.answersMaps)
        engine.send(batch, background, host.handle)
        assertEquals(1, host.keyRequests)
    }

    @Test
    fun aCachedFixAnswersPreciseOnlyWhenCloseToTheAskedAccuracy() {
        assertEquals(20.0, LocationFixProvider.acceptableCachedAccuracy(10.0), 0.0)
        assertEquals(100.0, LocationFixProvider.acceptableCachedAccuracy(100.0), 0.0)
    }

    @Test
    fun readsKeepDistinctAndStableIDs() {
        val entry = mapOf("at" to "2026-10-14T09:00:00Z", "action" to "now", "platform" to "telegram")
        val reads = LocationRead.parse(jsonOf(mapOf("reads" to listOf(entry, entry, entry))) as JsonObject)
        assertEquals(3, reads.map { it.id }.toSet().size)
        val before = LocationRead.parse(jsonOf(mapOf("reads" to listOf(entry))) as JsonObject)
        val after = LocationRead.parse(jsonOf(mapOf("reads" to listOf(mapOf("at" to "2026-10-14T09:05:00Z", "action" to "locate"), entry))) as JsonObject)
        assertEquals(before.first().id, after.last().id)
    }

    @Test
    fun theMonitoredPlacesDigestHoldsNoCoordinates() {
        val digest = LocationBackgroundMonitor.monitoredDigest(listOf(home, work))
        assertEquals(64, digest.length)
        assertFalse(digest.contains("51.5"))
        assertFalse(digest == LocationBackgroundMonitor.monitoredDigest(listOf(home)))
    }

    @Test
    fun aPlaintextLocateIsAnsweredOnlyForTheBoundProfileWhileSharing() {
        val stub = jsonOf(mapOf("type" to "location.requested", "profile" to "coder", "gateway_id" to "gw-1",
            "dashboard_id" to "0F5C8A34-1B2D-4E5F-8A9B-0C1D2E3F4A5B", "locate" to mapOf("id" to "l_0123456789abcdef"))) as JsonObject
        val request = LocationLocateRequest.parseStub(stub)!!
        assertEquals("l_0123456789abcdef", request.id)
        assertTrue(request.isAnswered(config(), at))
        assertFalse(request.isAnswered(config(background = false, talking = true), at))
        assertFalse(request.isAnswered(config(pausedUntil = at + 60_000), at))
        assertFalse(request.isAnswered(config().copy(binding = config().binding!!.copy(profile = "default")), at))
        assertFalse(request.isAnswered(config().copy(binding = null), at))
        assertNull(LocationLocateRequest.parseStub(jsonOf(mapOf("type" to "turn.completed", "locate" to mapOf("id" to "l_0123456789abcdef"))) as JsonObject))
        val maps = LocationLocateRequest.parseStub(jsonOf(mapOf("type" to "location.requested", "maps" to mapOf("id" to "m_0123456789abcdefgh"))) as JsonObject)
        assertEquals(LocationLocateRequest.MAPS, maps?.kind)
        assertTrue(LocationLocateRequest.isMapsID("m_0123456789abcdefgh"))
        assertFalse(LocationLocateRequest.isMapsID("m_short"))
        assertFalse(LocationLocateRequest.isMapsID("m_0123456789abcdef/../x"))
    }

    @Test
    fun placeRulesCarryAnyPlaceEitherEventAndRepeat() {
        val rules = LocationRule.parse(jsonOf(mapOf("ok" to true, "rules" to listOf(
            mapOf("id" to "r1", "what" to "Remind me to log my workout", "place_id" to "*", "place_name" to "any saved place", "event" to "either", "repeat" to "every", "call" to false),
            mapOf("id" to "r2", "task" to "Daily brief", "place_id" to "p_0123456789ab", "place_name" to "Home", "event" to "leave", "repeat" to "daily", "days" to listOf("mon", "tue"), "call" to true),
            mapOf("what" to "no id"),
        ))) as JsonObject)
        assertEquals(listOf("r1", "r2"), rules.map { it.id })
        assertTrue(rules[0].isAnySavedPlace)
        assertEquals("either", rules[0].event)
        assertEquals("every", rules[0].repeatMode)
        assertFalse(rules[1].isAnySavedPlace)
        assertEquals("Daily brief", rules[1].what)
        assertEquals("mon, tue", rules[1].days)
        assertTrue(rules[1].calls)
    }

    @Test
    fun mapsSealingMatchesTheVector() {
        val key = vectorKey()
        assertTrue(key.answersMaps)
        assertEquals("b56fa57113c019f2b8d9350fb751e9df8202ecc297577700df829f70dcb51c02", SealCrypto.hex(key.mapsRequestKey!!))
        assertEquals("ae7dd388abc512517a706da57dc4908c316adbdcde58e78de7b4447901f7b71c", SealCrypto.hex(key.mapsAnswerKey!!))
        val id = "m_vector0123456789abcd"
        assertEquals("conduit-location/1\nmaps-request\nkid=$KID\ndev=device-vector-1\nid=$id", LocationCrypto.mapsAAD("maps-request", key.kid, key.deviceID, id).decodeToString())
        val sealed = buildJsonObject {
            put("ok", true); put("v", 1); put("nonce", "AAAAAAAAAAAAAAAA")
            put("ct", "3KzdgIkgGzXth_u6OLG69KJdOHs-ZpQ1zZiDXxDHK9uZ0AtkxMhgmNkhL41iwDnHpQXzGDbN22lEZecj-9nl0soF3qC-4eEsiWmQZe-P4WbV9--vJ3krWoZX9yXm9RYEbvtTeAwlewwfp9yGDQleQaQJVr149SEGWfbr-P0j7qze")
        }
        val body = LocationCrypto.openMapsRequest(sealed, key, id)
        assertEquals(id, body["id"].str)
        try {
            LocationCrypto.openMapsRequest(sealed, key, "m_another0123456789abc")
            fail("A request is bound to its id")
        } catch (_: SealCrypto.Failure) {
        }
        val request = MapsRequest.parse(body).getOrThrow()
        assertEquals(MapsRequest.SEARCH, request.action)
        assertEquals("pharmacy", request.query)
        assertEquals(MapsPlaceRef.Me, request.near)
        assertEquals(3, request.limit)
        assertEquals(LocationPrecision.AREA, request.detail)
        assertTrue(request.usesMe)

        val plaintext = MapsAnswer.Failed(MapsFailure("paused", "x")).plaintext(id)
        assertEquals("""{"error":"paused","id":"m_vector0123456789abcd","note":"x","ok":false}""", plaintext.decodeToString())
        val answer = LocationCrypto.mapsAnswer(plaintext, key, id, ByteArray(12))
        assertEquals("yzg2DSNRs2O4KP-JqCiLxMOu8K8CJxxAedJ7XQZOXPKq6QkWnaDPHRwIucRhLCk52UiCzk_wayZV9utToCcaOzy2na1WTgGCDfBCPTvIe-HYvYTYfhY", answer["ct"].str)
        assertEquals("device-vector-1", answer["device_id"].str)
        assertEquals(1L, answer["v"].long)
        val old = LocationSessionKey("k", ByteArray(32), "device-vector-1")
        assertFalse(old.answersMaps)
        try {
            LocationCrypto.openMapsRequest(sealed, old, id)
            fail()
        } catch (e: SealCrypto.Failure) {
            assertEquals("noMapsKey", e.reason)
        }
    }

    @Test
    fun mapsRequestsTakeTheContractDefaultsAndRefuseWhatTheyCantRun() {
        fun parse(value: Any?) = MapsRequest.parse(jsonOf(value) as JsonObject).getOrNull()
        val directions = parse(mapOf("id" to "m_1", "action" to "directions", "to" to mapOf("text" to "Heathrow T5"), "mode" to "transit",
            "arrive_by" to "2026-10-15T07:40:00+01:00", "depart_at" to null))!!
        assertEquals(MapsPlaceRef.Me, directions.from)
        assertEquals(listOf(MapsPlaceRef.Text("Heathrow T5")), directions.to)
        assertEquals("transit", directions.mode)
        assertEquals(Instant.ofEpochSecond(1_792_046_400), directions.arriveBy)
        assertNull(directions.near)
        val lookup = parse(mapOf("id" to "m_2", "action" to "lookup", "query" to "Boots\u0007 Oxford St\n"))!!
        assertNull(lookup.near)
        assertFalse(lookup.usesMe)
        assertEquals("Boots  Oxford St", lookup.query)
        val times = parse(mapOf("id" to "m_3", "action" to "travel_times", "from" to mapOf("saved" to "p_home"),
            "to" to listOf(mapOf("lat" to 51.5, "lon" to -0.12), mapOf("place_id" to "I1234"), mapOf("me" to true))))!!
        assertEquals(MapsPlaceRef.Saved("p_home"), times.from)
        assertEquals(listOf(MapsPlaceRef.Coordinate(51.5, -0.12), MapsPlaceRef.PlaceID("I1234"), MapsPlaceRef.Me), times.to)
        assertTrue(times.usesMe)
        val search = parse(mapOf("id" to "m_4", "action" to "search", "query" to "pharmacy", "limit" to 99))!!
        assertEquals(MapsPlaceRef.Me, search.near)
        assertEquals(25, search.limit)
        assertNull(parse(mapOf("id" to "m_5", "action" to "search")))
        assertNull(parse(mapOf("id" to "m_6", "action" to "directions", "to" to listOf(mapOf("text" to "A"), mapOf("text" to "B")))))
        assertNull(parse(mapOf("id" to "m_7", "action" to "travel_times", "to" to List(11) { mapOf("text" to "A") })))
        assertNull(parse(mapOf("id" to "m_8", "action" to "navigate")))
        assertNull(parse(mapOf("id" to "m_9", "action" to "directions", "to" to mapOf("lat" to 91, "lon" to 0))))
        assertNull(parse(mapOf("id" to "m_10", "action" to "directions", "to" to mapOf("text" to "A"), "mode" to "flying")))
    }

    @Test
    fun aMapsAnswerStaysUnderSixtyFourKilobytes() {
        val step = mapOf("text" to "Turn left onto High Street ".repeat(7), "distance_m" to 400)
        val route = mapOf("name" to "A40", "duration_s" to 1520, "distance_m" to 18400, "steps" to List(60) { step })
        val routes = MapsAnswer.Ok(jsonOf(mapOf("routes" to List(10) { route })) as JsonObject).plaintext("m_1")
        assertTrue(routes.size <= MapsAnswer.MAXIMUM_BYTES)
        assertEquals(true, ConduitJson.parseToJsonElement(routes.decodeToString()).obj?.get("ok").bool)
        val place = mapOf("name" to "Boots ".repeat(20), "address" to "x".repeat(300))
        val places = MapsAnswer.Ok(jsonOf(mapOf("places" to List(400) { place })) as JsonObject).plaintext("m_1")
        assertTrue(places.size <= MapsAnswer.MAXIMUM_BYTES)
        assertFalse(ConduitJson.parseToJsonElement(places.decodeToString()).obj?.get("result").obj?.get("places").arr.isNullOrEmpty())
    }

    @Test
    fun theVoiceMapsToolReadsPlacesAsTheModelSaysThem() {
        val places = listOf(home, work)
        assertEquals(MapsPlaceRef.Me, MapsVoiceRules.ref("me", places))
        assertEquals(MapsPlaceRef.Saved("p_home"), MapsVoiceRules.ref(" home ", places))
        assertEquals(MapsPlaceRef.Coordinate(51.5, -0.12), MapsVoiceRules.ref("51.5, -0.12", places))
        assertEquals(MapsPlaceRef.PlaceID("I1234"), MapsVoiceRules.ref("place_id:I1234", places))
        assertEquals(MapsPlaceRef.Text("12 High St, London"), MapsVoiceRules.ref("12 High St, London", places))
        assertEquals(listOf("Home", "12 High St, London"), MapsVoiceRules.destinations(kotlinx.serialization.json.JsonPrimitive("""["Home", "12 High St, London"]""")))
        assertEquals(listOf("12 High St, London"), MapsVoiceRules.destinations(kotlinx.serialization.json.JsonPrimitive("12 High St, London")))
        assertEquals(listOf("Home", "Work"), MapsVoiceRules.destinations(jsonOf(listOf("Home", "Work"))))

        val converted = MapsVoiceTool.tool.arguments(jsonOf(mapOf("action" to "travel_times", "to" to listOf("Home", "Work"))) as JsonObject)
        assertEquals(listOf("Home", "Work"), converted["to"].arr?.map { it.str })
        assertEquals("Home", MapsVoiceTool.tool.arguments(jsonOf(mapOf("to" to "Home")) as JsonObject)["to"].str)

        fun request(action: String, args: Map<String, Any?>) = MapsVoiceRules.makeRequest(action, jsonOf(args) as JsonObject, places, meByDefault = false)
        val anywhere = request("search", mapOf("query" to "pharmacy")).getOrThrow()
        assertNull(anywhere.near)
        assertFalse(MapsVoiceRules.usesLocation(anywhere))
        assertTrue(MapsVoiceRules.usesLocation(request("directions", mapOf("from" to "Heathrow", "to" to "Home")).getOrThrow()))
        assertFalse(MapsVoiceRules.usesLocation(request("directions", mapOf("from" to "Heathrow", "to" to "Paddington")).getOrThrow()))
        assertTrue("Reverse geocoding needs a place or the user's position", request("reverse_geocode", emptyMap()).isFailure)
    }

    @Test
    fun everyIngestSaysWhetherTheHostMayLookUpMaps() {
        val settings = LocationSharingSettings(background = true)
        assertTrue(settings.mapsHost)
        var sharing = LocationBatch(sentAtMs = at, settings = settings).json()["sharing"].obj!!
        assertNull("This phone can't be asked for maps lookups", sharing["features"])
        assertEquals(true, sharing["maps_host"].bool)
        sharing = LocationBatch(sentAtMs = at, settings = settings.copy(mapsHost = false)).json()["sharing"].obj!!
        assertEquals(false, sharing["maps_host"].bool)
        assertTrue(LocationSharingSettings.parse(jsonOf(mapOf("talking" to true)) as JsonObject).mapsHost)
        assertFalse(LocationSharingSettings.parse(settings.copy(mapsHost = false).storageJson).mapsHost)

        // Features the phone does report are dropped for a key from before maps.
        val batch = LocationBatch(sentAtMs = at, settings = settings, features = listOf("maps"))
        fun sealedSharing(key: LocationSessionKey): JsonObject {
            val body = LocationCrypto.ingestBody(batch, key)
            val plaintext = SealCrypto.open(body, key.key, LocationCrypto.aad(key.kid, key.deviceID, batch.batchID))
            return ConduitJson.parseToJsonElement(plaintext.decodeToString()).obj!!["sharing"].obj!!
        }
        val old = LocationSessionKey("k", ByteArray(32), "device-vector-1")
        assertNull(sealedSharing(old)["features"])
        assertEquals(listOf("maps"), sealedSharing(old.copy(mapsRequestKey = ByteArray(32), mapsAnswerKey = ByteArray(32)))["features"].arr?.map { it.str })
    }

    companion object {
        const val HOST_PUBLIC = "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8"
        const val DEVICE_PRIVATE = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
        const val KID = "4186c0bc93b7b264b5991464ab3e4c1f"
    }
}
