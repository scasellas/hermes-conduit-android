package app.hermesconduit.android

import app.hermesconduit.android.data.MemoryTextStore
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HealthBatch
import app.hermesconduit.android.model.HealthBucket
import app.hermesconduit.android.model.HealthCategory
import app.hermesconduit.android.model.HealthEvent
import app.hermesconduit.android.model.HealthEventKind
import app.hermesconduit.android.model.HealthGrain
import app.hermesconduit.android.model.HealthHistoryDepth
import app.hermesconduit.android.model.HealthMetric
import app.hermesconduit.android.model.HealthAggregation
import app.hermesconduit.android.model.HealthSleepNights
import app.hermesconduit.android.model.HealthSleepSegment
import app.hermesconduit.android.model.HealthSleepStage
import app.hermesconduit.android.model.HealthSyncCrypto
import app.hermesconduit.android.model.HealthSyncPreferences
import app.hermesconduit.android.model.HealthSyncState
import app.hermesconduit.android.model.HealthValue
import app.hermesconduit.android.model.HealthWindow
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.health.HealthDataSource
import app.hermesconduit.android.ui.health.HealthEventChanges
import app.hermesconduit.android.ui.health.HealthEventStream
import app.hermesconduit.android.ui.health.HealthKeyStore
import app.hermesconduit.android.ui.health.HealthSyncEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.ZoneOffset

/** Port of ConduitTests/HealthSyncTests.swift. */
class HealthSyncTest {
    private val gmt: ZoneId = ZoneOffset.UTC

    private fun date(text: String) = Instant.parse(text)

    @Test
    fun sealingMatchesTheNotifierVector() {
        val raw = SealCrypto.base64UrlDecoded(VECTOR_DEVICE_PRIVATE)!!
        val device = SealCrypto.KeyPair(raw)
        assertEquals("NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ", SealCrypto.base64Url(device.publicKey))
        val hostPublic = SealCrypto.base64UrlDecoded(VECTOR_HOST_PUBLIC)!!
        assertEquals(VECTOR_KID, HealthSyncCrypto.keyID(device.publicKey, hostPublic))

        val key = HealthSyncCrypto.agree(raw, "device-vector-1", VECTOR_HOST_PUBLIC, VECTOR_KID)
        assertEquals("b5b0006ac3d4b1ff1c5bc4d29245fbe723ea03ffdd9ee3a38b8458bef47c2332", SealCrypto.hex(key.keyBytes))

        val envelope = HealthSyncCrypto.envelope("""{"batch_id":"batch-vector-1"}""".toByteArray(), key, "device-vector-1", "batch-vector-1", ByteArray(12))
        assertEquals("AAAAAAAAAAAAAAAA", envelope["nonce"].str)
        assertEquals("TcrUbI4YW2m833vWYhvn-tbMWAmZ4-XCdXzRzWT4wxEnlx8N7Z45UyvsHAt6", envelope["ct"].str)
        assertEquals(VECTOR_KID, envelope["kid"].str)

        try {
            HealthSyncCrypto.agree(raw, "device-vector-1", VECTOR_HOST_PUBLIC, "0000")
            fail("An answer for another key is refused")
        } catch (_: SealCrypto.Failure) {
        }
    }

    @Test
    fun nightPicksTheWatchAndNamesTheMorning() {
        val watch = listOf(
            segment("w1", HealthSleepStage.CORE, "2026-10-12T23:00:00Z", "2026-10-13T02:00:00Z", "Pixel Watch", true),
            segment("w2", HealthSleepStage.AWAKE, "2026-10-13T02:00:00Z", "2026-10-13T02:10:00Z", "Pixel Watch", true),
            segment("w3", HealthSleepStage.DEEP, "2026-10-13T02:10:00Z", "2026-10-13T03:10:00Z", "Pixel Watch", true),
            segment("w4", HealthSleepStage.REM, "2026-10-13T03:10:00Z", "2026-10-13T06:10:00Z", "Pixel Watch", true),
        )
        val other = listOf(segment("o1", HealthSleepStage.ASLEEP, "2026-10-12T22:00:00Z", "2026-10-13T08:00:00Z", "Sleep App", false))
        val nights = HealthSleepNights.summaries(other + watch, gmt)
        assertEquals(1, nights.size)
        val night = nights.first()
        assertEquals("2026-10-13", night.date)
        assertEquals("Pixel Watch", night.source)
        assertEquals(HealthValue.Num(420.0), night.data["asleep"])
        assertEquals(HealthValue.Num(60.0), night.data["deep"])
        assertEquals(HealthValue.Num(180.0), night.data["rem"])
        assertEquals(HealthValue.Num(180.0), night.data["core"])
        assertEquals(HealthValue.Num(10.0), night.data["awake"])
        assertEquals(HealthValue.Num(1.0), night.data["wake_count"])
        assertEquals("No in-bed samples: first to last", HealthValue.Num(430.0), night.data["in_bed"])
        assertEquals(HealthValue.Text("2026-10-12T23:00:00Z"), night.data["bedtime"])
        assertNull(night.data["unspecified"])

        val phoneOnly = HealthSleepNights.summaries(
            other + segment("p1", HealthSleepStage.ASLEEP, "2026-10-13T00:00:00Z", "2026-10-13T05:00:00Z", "Phone", false), gmt,
        )
        assertEquals("Sleep App", phoneOnly.first().source)
        assertEquals(HealthValue.Num(600.0), phoneOnly.first().data["unspecified"])

        assertEquals(date("2026-10-14T00:00:00Z"), HealthSleepNights.nightEnding(date("2026-10-13T13:00:00Z"), gmt))
        val (from, to) = HealthSleepNights.window(date("2026-10-10T00:00:00Z"), date("2026-10-13T00:00:00Z"), gmt)
        assertEquals(date("2026-10-09T12:00:00Z"), from)
        assertEquals(date("2026-10-12T12:00:00Z"), to)
    }

    @Test
    fun batchSplitKeepsWindowsWhole() {
        val bucket = HealthBucket(date("2026-10-13T00:00:00Z"), date("2026-10-13T01:00:00Z"), sum = 12.0)
        val batch = HealthBatch(
            computedAt = date("2026-10-13T12:00:00Z"), deviceName = "Pixel", zone = gmt,
            categories = listOf(HealthCategory.ACTIVITY), detailed = emptyList(),
            windows = (0 until 3).map { index ->
                HealthWindow("steps", HealthGrain.HOUR, "count", date("2026-10-13T00:00:00Z"), date("2026-10-14T00:00:00Z"), List(4 + index) { bucket })
            },
            deleted = listOf("a", "b"),
        )
        val parts = batch.split(limit = 8)
        assertEquals(listOf(listOf(4), listOf(5), listOf(6)), parts.map { part -> part.windows.map { it.buckets.size } })
        assertEquals(listOf("a", "b"), parts.flatMap { it.deleted })
        assertTrue(parts.all { it.itemCount <= 8 })
        assertEquals(3, parts.map { it.batchID }.toSet().size)

        val json = parts[0].json()
        assertEquals(listOf("activity"), json["categories"].arr?.map { it.str })
        val window = json["windows"].arr?.first()?.obj
        assertEquals("2026-10-13T00:00:00Z", window?.get("from").str)
        assertEquals(12.0, window?.get("buckets").arr?.first()?.obj?.get("sum")?.jsonPrimitive?.double)
    }

    @Test
    fun passSetsUpAKeySendsSealedBatchesAndMovesItsCursors() = runBlocking {
        val host = FakeHealthHost()
        val engine = makeEngine()
        val preferences = preferences()

        val outcome = engine.sync(preferences, Instant.MAX, host::handle)
        assertEquals(SyncOutcome.Synced, outcome)
        assertEquals(1, host.keyRequests)
        assertTrue(host.batches.isNotEmpty())
        val metrics = host.batches.flatMap { batch -> batch["windows"].arr.orEmpty().mapNotNull { it.obj?.get("metric").str } }.toSet()
        assertTrue("steps" in metrics)
        assertTrue("sleep" in metrics)
        assertFalse("Only the categories turned on", "heart_rate" in metrics)
        assertEquals("All 30 days are in", engine.state.value.historyStart, engine.state.value.backfillCursor)
        assertEquals("w-anchor", engine.state.value.anchors["workouts"])
        assertNull("No raw readings without detailed readings", engine.state.value.anchors["samples.steps"])
        assertNotNull(engine.state.value.lastSync)

        engine.sync(preferences, Instant.MAX, host::handle)
        assertEquals(1, host.keyRequests)

        host.forgetKey()
        engine.sync(preferences, Instant.MAX, host::handle)
        assertEquals(2, host.keyRequests)
    }

    @Test
    fun unreadableHealthStateKeepsTheDeviceID() {
        val state = HealthSyncState.decode("""{"deviceID":"phone1","anchors":"not a map"}""")
        assertEquals("phone1", state.deviceID)
        assertTrue(state.anchors.isEmpty())
    }

    @Test
    fun refusedIngestKeepsTheCursorAndPauses() = runBlocking {
        val host = FakeHealthHost()
        host.ingestError = DashboardHttpException(413, "too_big")
        val engine = makeEngine()
        val outcome = engine.sync(preferences(), Instant.MAX, host::handle)
        assertEquals(SyncOutcome.Paused(SyncPause.refused("too_big")), outcome)
        assertNull("Nothing acknowledged, nothing moves", engine.state.value.backfillCursor)
        assertTrue(engine.state.value.anchors.isEmpty())

        host.ingestError = DashboardHttpException(404, "")
        assertEquals(SyncOutcome.Paused(SyncPause.PLUGIN_MISSING), engine.sync(preferences(), Instant.MAX, host::handle))

        host.ingestError = java.io.IOException("timed out")
        val failed = engine.sync(preferences(), Instant.MAX, host::handle)
        assertNull(engine.state.value.pause)
        assertTrue(failed is SyncOutcome.Failed)
        assertNotNull(engine.lastError.value)
    }

    @Test
    fun turningOffDeletesFromTheHostAndForgetsTheKey() = runBlocking {
        val host = FakeHealthHost()
        val keyStore = MemoryTextStore()
        val engine = makeEngine(keyStore)
        engine.sync(preferences(), Instant.MAX, host::handle)
        val deviceID = engine.state.value.deviceID
        assertTrue(engine.turnOff(host::handle))
        assertEquals(listOf(deviceID), host.wiped)
        assertNull(engine.state.value.historyStart)
        assertTrue(engine.state.value.anchors.isEmpty())
        assertNull(keyStore.load())
        assertEquals("Same device after turning off", deviceID, engine.state.value.deviceID)

        assertFalse("Deletion asked for but no way to reach the host is not a deletion", makeEngine().turnOff(null))
    }

    @Test
    fun metricsMatchTheNotifier() {
        assertEquals(HealthAggregation.SUM, HealthMetric.STEPS.aggregation)
        assertEquals(HealthAggregation.STATS, HealthMetric.HEART_RATE.aggregation)
        assertEquals("mL/(kg*min)", HealthMetric.VO2_MAX.unit)
        assertFalse(HealthCategory.SLEEP.hasDetailedReadings)
        assertEquals(null, HealthHistoryDepth.ALL.days)
        assertEquals(HealthHistoryDepth.DAYS30, HealthHistoryDepth.of("nonsense"))
    }

    // region Helpers

    private fun makeEngine(keyStore: MemoryTextStore = MemoryTextStore()) = HealthSyncEngine(
        source = FakeHealthSource(),
        store = MemoryTextStore(),
        keys = HealthKeyStore(keyStore),
        zone = { gmt },
        now = { date("2026-10-13T12:00:00Z") },
    )

    private fun preferences() = HealthSyncPreferences(
        destination = SyncDestination("00000000-0000-0000-0000-000000000001", "default", "hermes.local"),
        categories = setOf("activity", "sleep", "workouts"),
        history = HealthHistoryDepth.DAYS30.raw,
    )

    private fun segment(id: String, stage: HealthSleepStage, start: String, end: String, source: String, watch: Boolean) =
        HealthSleepSegment(id, stage, date(start), date(end), source, watch)

    private class FakeHealthSource : HealthDataSource {
        override val isAvailable = true
        override suspend fun buckets(metric: HealthMetric, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket> =
            listOf(HealthBucket(from, from.atZone(zone).let { if (grain == HealthGrain.HOUR) it.plusHours(1) else it.plusDays(1) }.toInstant(),
                sum = if (metric.aggregation == HealthAggregation.SUM) 100.0 else null))

        override suspend fun sleepSegments(from: Instant, to: Instant): List<HealthSleepSegment> = emptyList()

        override suspend fun changes(stream: HealthEventStream, since: Instant, anchor: String?, limit: Int): HealthEventChanges {
            if (stream != HealthEventStream.Workouts || anchor != null) return HealthEventChanges(emptyList(), emptyList(), anchor ?: stream.key, false)
            val workout = HealthEvent("W1", HealthEventKind.WORKOUT, "workout", since, since.plusSeconds(1800), data = mapOf("activity" to HealthValue.Text("running")))
            return HealthEventChanges(listOf(workout), emptyList(), "w-anchor", false)
        }

        override fun earliestDate(): Instant = Instant.ofEpochSecond(1_600_000_000)
    }

    /** The notifier's health routes, enough to agree a key and open batches. */
    private class FakeHealthHost {
        private val hostKey = SealCrypto.newKeyPair()
        private var session: ByteArray? = null
        private var kid: String? = null
        var keyRequests = 0
        val batches = mutableListOf<JsonObject>()
        val wiped = mutableListOf<String>()
        var ingestError: Exception? = null

        fun forgetKey() {
            session = null
            kid = null
        }

        suspend fun handle(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
            val base = HealthSyncEngine.BASE
            val o = body?.obj
            return when (path) {
                "$base/status" -> buildJsonObject {
                    put("generation", "g1")
                    kid?.let { k -> put("device", buildJsonObject { put("kid", k) }) }
                }
                "$base/key" -> {
                    keyRequests += 1
                    val deviceID = o!!["device_id"].str!!
                    val devicePublic = SealCrypto.base64UrlDecoded(o["public_key"].str!!)!!
                    val k = HealthSyncCrypto.keyID(devicePublic, hostKey.publicKey)
                    val shared = SealCrypto.sharedSecret(hostKey.privateKey, devicePublic)
                    val info = HealthSyncCrypto.INGEST_INFO.toByteArray() + "\n$deviceID\n".toByteArray() + devicePublic + hostKey.publicKey
                    session = SealCrypto.hkdf(shared, HealthSyncCrypto.SALT.toByteArray(), info)
                    kid = k
                    buildJsonObject { put("host_public_key", SealCrypto.base64Url(hostKey.publicKey)); put("kid", k); put("generation", "g1") }
                }
                "$base/ingest" -> {
                    ingestError?.let { throw it }
                    val s = session
                    if (s == null || o!!["kid"].str != kid) throw DashboardHttpException(409, "unknown_key")
                    val aad = HealthSyncCrypto.aad(kid!!, o["device_id"].str!!, o["batch_id"].str!!)
                    val plaintext = SealCrypto.open(o, s, aad)
                    batches += ConduitJson.parseToJsonElement(plaintext.decodeToString()).obj!!
                    buildJsonObject { put("ok", true) }
                }
                "$base/wipe" -> {
                    wiped += o!!["device_id"].str!!
                    buildJsonObject { put("ok", true) }
                }
                else -> throw DashboardHttpException(404, path)
            }
        }
    }

    companion object {
        const val VECTOR_HOST_PUBLIC = "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8"
        const val VECTOR_DEVICE_PRIVATE = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
        const val VECTOR_KID = "568703226d4ca837141b5eebba642b64"
    }
    // endregion
}
