package app.hermesconduit.android.ui.health

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.TextStore
import app.hermesconduit.android.model.CanonicalJson
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.HealthBatch
import app.hermesconduit.android.model.HealthBucket
import app.hermesconduit.android.model.HealthCategory
import app.hermesconduit.android.model.HealthEvent
import app.hermesconduit.android.model.HealthGrain
import app.hermesconduit.android.model.HealthHistoryDepth
import app.hermesconduit.android.model.HealthMetric
import app.hermesconduit.android.model.HealthSessionKey
import app.hermesconduit.android.model.HealthSleepNights
import app.hermesconduit.android.model.HealthSleepSegment
import app.hermesconduit.android.model.HealthSyncCrypto
import app.hermesconduit.android.model.HealthSyncLimits
import app.hermesconduit.android.model.HealthSyncPreferences
import app.hermesconduit.android.model.HealthSyncState
import app.hermesconduit.android.model.HealthWindow
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.ui.device.DeadlineReached
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.device.SyncRequest
import app.hermesconduit.android.ui.device.SyncSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

/** What the sync engine needs from Health Connect. Tests use a fake. */
interface HealthDataSource {
    val isAvailable: Boolean
    /** Hourly or daily buckets for `metric` in [from, to). */
    suspend fun buckets(metric: HealthMetric, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket>
    suspend fun sleepSegments(from: Instant, to: Instant): List<HealthSleepSegment>
    /** New and deleted events since `anchor` for one stream, no earlier than `since`. `anchor` goes back unchanged. */
    suspend fun changes(stream: HealthEventStream, since: Instant, anchor: String?, limit: Int): HealthEventChanges
    /** The earliest date Health keeps, for "All history". */
    fun earliestDate(): Instant
}

/** A stream of events with its own anchor. */
sealed class HealthEventStream(val key: String) {
    data object Workouts : HealthEventStream("workouts")
    data object Sleep : HealthEventStream("sleep")
    data class Samples(val metric: HealthMetric) : HealthEventStream("samples.${metric.raw}")
}

data class HealthEventChanges(val added: List<HealthEvent>, val deleted: List<String>, val anchor: String?, val more: Boolean)

/** The agreed key's storage. */
class HealthKeyStore(private val store: TextStore) {
    fun load(): HealthSessionKey? = store.load()?.let { runCatching { ConduitJson.decodeFromString(HealthSessionKey.serializer(), it) }.getOrNull() }
    fun save(key: HealthSessionKey) = store.save(ConduitJson.encodeToString(HealthSessionKey.serializer(), key))
    fun clear() = store.save(null)
}

/**
 * One health sync pass (iOS HealthSyncEngine): make sure this phone and the
 * Hermes profile share a key, send the recent days again, then new and
 * deleted workouts, sleep stages and raw readings, then one more step of
 * older history. Every piece is re-readable, so there is no outbox: an
 * anchor or the history cursor moves only after the host acknowledged the
 * batch.
 */
class HealthSyncEngine(
    val source: HealthDataSource,
    private val store: TextStore,
    private val keys: HealthKeyStore,
    var zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
    private val deviceName: () -> String = { "Android" },
) {
    private val serial = SyncSupport.serialDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow(HealthSyncState.decode(store.load()))
    val state: StateFlow<HealthSyncState> = _state
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var running: Pair<HealthSyncPreferences, Deferred<SyncOutcome>>? = null
    private var turnOffs = 0

    private var current: HealthSyncState
        get() = _state.value
        set(value) { _state.value = value }

    private fun persist() { store.save(current.encode()) }

    /** Runs a pass, or joins the one already running. A pass with other choices is followed by one with these. */
    suspend fun sync(preferences: HealthSyncPreferences, deadline: Instant, request: SyncRequest): SyncOutcome = withContext(serial) {
        val turnOffsAtStart = turnOffs
        while (true) {
            val joined = running ?: break
            val outcome = runCatching { joined.second.await() }.getOrElse { SyncOutcome.Unfinished }
            if (joined.first == preferences) return@withContext outcome
            if (turnOffs != turnOffsAtStart) return@withContext SyncOutcome.Off
            if (running === joined) running = null
        }
        val task = scope.async { pass(preferences, deadline, request) }
        val entry = preferences to task
        running = entry
        _isSyncing.value = true
        val outcome = try {
            task.await()
        } catch (e: CancellationException) {
            task.cancel()
            throw e
        } finally {
            if (running === entry) {
                running = null
                _isSyncing.value = false
            }
        }
        outcome
    }

    /** Records a pause found outside a pass (another dashboard in use). */
    suspend fun note(pause: SyncPause?) = withContext(serial) {
        if (current.pause == pause) return@withContext
        current = current.copy(pause = pause)
        persist()
    }

    /** This phone's id and agreed key, for a live voice call's read. Nil until a key that reads is agreed. */
    fun voiceReadKey(): Pair<String, HealthSessionKey>? {
        val key = keys.load()?.takeIf { it.readsByVoice } ?: return null
        return current.deviceID to key
    }

    /** Sharing turned off: forget the key, anchors and cursor, optionally deleting this phone's data on the host first. */
    suspend fun turnOff(deleteFromHost: SyncRequest?): Boolean = withContext(serial) {
        turnOffs += 1
        running?.let { entry ->
            entry.second.cancel()
            runCatching { entry.second.await() }
            if (running === entry) {
                running = null
                _isSyncing.value = false
            }
        }
        keys.clear()
        var deleted = false
        if (deleteFromHost != null) {
            try {
                deleteFromHost("POST", "$BASE/wipe", emptyMap(), buildJsonObject { put("device_id", current.deviceID) })
                deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No health sync on this host any more: nothing left to delete.
                if (SyncSupport.httpCode(e) == 404) deleted = true else _lastError.value = SyncSupport.describe(e)
            }
        }
        current = current.startedOver()
        persist()
        deleted
    }

    private suspend fun pass(preferences: HealthSyncPreferences, deadline: Instant, request: SyncRequest): SyncOutcome {
        val destination = preferences.destination
        if (!preferences.isOn || destination == null) return SyncOutcome.Off
        if (current.destination != destination) {
            // Health data never follows the user to another destination.
            keys.clear()
            current = current.startedOver().copy(destination = destination)
        }
        current = current.copy(lastAttempt = now().toEpochMilli(), pause = null)
        _lastError.value = null
        try {
            val key = ensureKey(request)
            prepareHistory(preferences.historyDepth)
            val session = Session(key, preferences, request, deadline)
            session.sendRecentDays()
            session.sendEvents()
            session.sendHistory()
            current = current.copy(lastSync = now().toEpochMilli(), pause = null)
            _lastError.value = null
            return SyncOutcome.Synced
        } catch (e: CancellationException) {
            persist()
            throw e
        } catch (_: DeadlineReached) {
            return SyncOutcome.Unfinished
        } catch (_: AuthExpiredException) {
            current = current.copy(pause = SyncPause.SIGN_IN_REQUIRED)
            return SyncOutcome.Paused(SyncPause.SIGN_IN_REQUIRED)
        } catch (e: Exception) {
            val detail = SyncSupport.httpDetail(e)
            return when (SyncSupport.httpCode(e)) {
                404 -> {
                    current = current.copy(pause = SyncPause.PLUGIN_MISSING)
                    SyncOutcome.Paused(SyncPause.PLUGIN_MISSING)
                }
                409 -> {
                    // The host lost or replaced this phone's key: set it up again next pass.
                    keys.clear()
                    _lastError.value = detail
                    SyncOutcome.Unfinished
                }
                400, 413 -> {
                    current = current.copy(pause = SyncPause.refused(detail))
                    SyncOutcome.Paused(SyncPause.refused(detail))
                }
                else -> {
                    _lastError.value = SyncSupport.describe(e)
                    SyncOutcome.Failed(SyncSupport.describe(e))
                }
            }
        } finally {
            persist()
        }
    }

    private suspend fun ensureKey(request: SyncRequest): HealthSessionKey {
        val status = request("GET", "$BASE/status", mapOf("device_id" to current.deviceID), null)
        val generation = status["generation"].str
        val hostKid = status["device"].obj?.get("kid").str
        val known = current.generation
        if (generation != null && known != null && known != generation) restart()
        // A key agreed before voice reads is set up again, once.
        keys.load()?.takeIf { it.kid == hostKid && it.readsByVoice }?.let { key ->
            current = current.copy(generation = generation ?: current.generation)
            return key
        }
        val pair = SealCrypto.newKeyPair()
        val answer = request("POST", "$BASE/key", emptyMap(), buildJsonObject {
            put("device_id", current.deviceID)
            put("device_name", deviceName())
            put("public_key", SealCrypto.base64Url(pair.publicKey))
        })
        val hostPublic = answer["host_public_key"].str
        val kid = answer["kid"].str
        if (hostPublic == null || kid == null) throw IllegalStateException(L10n.tr("The host didn't return a health key."))
        val key = HealthSyncCrypto.agree(pair.privateKey, current.deviceID, hostPublic, kid)
        coroutineContext.ensureActive()
        keys.save(key)
        restart()
        current = current.copy(generation = answer["generation"].str ?: generation)
        return key
    }

    private fun restart() {
        current = current.copy(anchors = emptyMap(), backfillCursor = null, historyStart = null, history = null)
    }

    private fun startOfDay(instant: Instant): Instant = instant.atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant()
    private fun plusDays(instant: Instant, days: Long): Instant = instant.atZone(zone()).plusDays(days).toInstant()

    /** Fixes how far back sharing reaches; another choice starts the anchors over. */
    private fun prepareHistory(depth: HealthHistoryDepth) {
        val today = startOfDay(now())
        val start = depth.days?.let { plusDays(today, -it.toLong()) } ?: startOfDay(source.earliestDate())
        if (current.historyStart != null && current.history == depth.raw) return
        if (current.historyStart != null) current = current.copy(anchors = emptyMap())
        current = current.copy(historyStart = start.toEpochMilli(), history = depth.raw)
    }

    private fun acknowledged(update: (HealthSyncState) -> HealthSyncState) {
        current = update(current)
        persist()
    }

    private inner class Session(
        val key: HealthSessionKey,
        val preferences: HealthSyncPreferences,
        val request: SyncRequest,
        val deadline: Instant,
    ) {
        val categories = preferences.categorySet

        suspend fun checkTime() {
            coroutineContext.ensureActive()
            if (now() >= deadline) throw DeadlineReached()
        }

        fun newBatch() = HealthBatch(
            computedAt = now(),
            deviceName = deviceName(),
            zone = zone(),
            categories = HealthCategory.entries.filter { it in categories },
            detailed = HealthCategory.entries.filter { it in preferences.detailedSet && it in categories },
        )

        suspend fun send(batch: HealthBatch) {
            for (part in batch.split()) {
                checkTime()
                val plaintext = CanonicalJson.bytes(part.json())
                val body = HealthSyncCrypto.envelope(plaintext, key, current.deviceID, part.batchID)
                request("POST", "$BASE/ingest", emptyMap(), body)
            }
        }

        suspend fun windows(from: Instant, to: Instant): List<HealthWindow> {
            val windows = mutableListOf<HealthWindow>()
            val today = startOfDay(now())
            val hourlyFloor = plusDays(today, -HealthSyncLimits.HOURLY_HISTORY_DAYS)
            for (category in HealthCategory.entries.filter { it in categories }) {
                for (metric in category.metrics) {
                    checkTime()
                    val daily = source.buckets(metric, HealthGrain.DAY, from, to, zone())
                    windows += HealthWindow(metric.raw, HealthGrain.DAY, metric.unit, from, to, daily)
                    if (metric.hasHourlyBuckets && to > hourlyFloor) {
                        val start = maxOf(from, hourlyFloor)
                        val hourly = source.buckets(metric, HealthGrain.HOUR, start, to, zone())
                        windows += HealthWindow(metric.raw, HealthGrain.HOUR, metric.unit, start, to, hourly)
                    }
                }
            }
            if (HealthCategory.SLEEP in categories) {
                checkTime()
                val (rangeFrom, rangeTo) = HealthSleepNights.window(from, to, zone())
                val segments = source.sleepSegments(rangeFrom, rangeTo)
                windows += HealthWindow("sleep", HealthGrain.NIGHT, "min", rangeFrom, rangeTo, HealthSleepNights.summaries(segments, zone()))
            }
            return windows
        }

        suspend fun sendRecentDays() {
            val today = startOfDay(now())
            val from = plusDays(today, -(HealthSyncLimits.RECOMPUTED_DAYS - 1))
            val to = plusDays(today, 1)
            send(newBatch().copy(windows = windows(from, to)))
            if (current.backfillCursor == null) acknowledged { it.copy(backfillCursor = from.toEpochMilli()) }
        }

        suspend fun sendEvents() {
            val since = current.historyStart?.let(Instant::ofEpochMilli) ?: return
            val streams = mutableListOf<HealthEventStream>()
            if (HealthCategory.WORKOUTS in categories) streams += HealthEventStream.Workouts
            if (HealthCategory.SLEEP in categories) streams += HealthEventStream.Sleep
            for (category in HealthCategory.entries.filter { it in categories && it in preferences.detailedSet }) {
                streams += category.metrics.map { HealthEventStream.Samples(it) }
            }
            for (stream in streams) {
                var more = true
                while (more) {
                    checkTime()
                    val page = source.changes(stream, since, current.anchors[stream.key], HealthSyncLimits.SAMPLES_PER_QUERY)
                    val batch = newBatch().copy(events = page.added, deleted = page.deleted)
                    if (!batch.isEmpty) send(batch)
                    acknowledged { state ->
                        state.copy(anchors = if (page.anchor == null) state.anchors - stream.key else state.anchors + (stream.key to page.anchor))
                    }
                    more = page.more
                }
            }
        }

        suspend fun sendHistory() {
            val target = current.historyStart?.let(Instant::ofEpochMilli) ?: return
            while (true) {
                val cursor = current.backfillCursor?.let(Instant::ofEpochMilli) ?: return
                if (cursor <= target) return
                checkTime()
                val start = maxOf(target, plusDays(cursor, -HealthSyncLimits.BACKFILL_CHUNK_DAYS))
                send(newBatch().copy(windows = windows(start, cursor)))
                acknowledged { it.copy(backfillCursor = start.toEpochMilli()) }
            }
        }
    }

    companion object {
        const val BASE = "${SyncSupport.PLUGIN_BASE}/health"
    }
}
