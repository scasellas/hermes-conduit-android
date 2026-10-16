package app.hermesconduit.android.ui.location

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.TextStore
import app.hermesconduit.android.model.LocationBatch
import app.hermesconduit.android.model.LocationCrypto
import app.hermesconduit.android.model.LocationHostStatus
import app.hermesconduit.android.model.LocationIDs
import app.hermesconduit.android.model.LocationPrecisionRules
import app.hermesconduit.android.model.LocationRead
import app.hermesconduit.android.model.LocationRule
import app.hermesconduit.android.model.LocationSessionKey
import app.hermesconduit.android.model.LocationSharedConfig
import app.hermesconduit.android.model.LocationSyncState
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.AuthExpiredException
import app.hermesconduit.android.ui.device.SyncRequest
import app.hermesconduit.android.ui.device.SyncSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The agreed key's storage. */
class LocationKeyStore(private val store: TextStore) {
    fun load(): LocationSessionKey? = LocationSessionKey.decode(store.load())
    fun save(key: LocationSessionKey): Boolean = store.save(key.encode())
    fun clear() = store.save(null)
}

/**
 * Talks to the notifier plugin's location routes for the profile sharing is
 * bound to (iOS LocationSyncEngine): agrees the key, sends sealed batches,
 * and reads what Settings > Location shows (status, the read log, place
 * rules) or deletes (a rule, the host's copy).
 */
class LocationSyncEngine(
    private val store: TextStore,
    val keys: LocationKeyStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val deviceName: () -> String = { "Android" },
) {
    enum class SendOutcome { SENT, REFUSED, FAILED }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(LocationSyncState.decode(store.load()))
    val state: StateFlow<LocationSyncState> = _state
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    /** A key agreed this run that couldn't be stored: used until Conduit quits. */
    private var unsavedKey: LocationSessionKey? = null

    private var current: LocationSyncState
        get() = _state.value
        set(value) { _state.value = value }

    private fun persist() { store.save(current.encode()) }

    class KeyRouteMissing : Exception()

    /** Sends one batch, agreeing a key first if needed, and again once if the host says it lost it. */
    suspend fun send(batch: LocationBatch, config: LocationSharedConfig, request: SyncRequest): SendOutcome = mutex.withLock {
        var batch = batch
        current = current.copy(lastAttempt = now())
        try {
            val voice = config.readsByVoice(now())
            // Maps requests reach a phone only through background sharing.
            val maps = config.settings.background
            var key = ensureKey(config.deviceID, voice, maps, request)
            if (current.placesDigest != LocationPrecisionRules.placesDigest(config.places)) batch = batch.copy(places = config.places)
            val answer = try {
                post(batch, key, request)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (SyncSupport.httpCode(e) != 409 || !needsNewKey(SyncSupport.httpDetail(e))) throw e
                // unknown_key or bad_seal: the host forgot or replaced this phone's key. Agree a new one, once.
                clearKey()
                key = ensureKey(config.deviceID, voice, maps, request)
                batch = batch.copy(places = config.places)
                post(batch, key, request)
            }
            acknowledge(batch, answer)
            SendOutcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthExpiredException) {
            current = current.copy(pause = SyncPause.SIGN_IN_REQUIRED)
            SendOutcome.FAILED
        } catch (_: KeyRouteMissing) {
            current = current.copy(pause = SyncPause.PLUGIN_MISSING)
            SendOutcome.FAILED
        } catch (e: Exception) {
            val detail = SyncSupport.httpDetail(e)
            when (SyncSupport.httpCode(e)) {
                404 -> {
                    // A bare 404 could be a proxy or a restarting host: the status route tells.
                    if (pluginIsMissing(config.deviceID, request)) current = current.copy(pause = SyncPause.PLUGIN_MISSING) else _lastError.value = detail
                    SendOutcome.FAILED
                }
                400, 413 -> {
                    current = current.copy(pause = SyncPause.refused(detail))
                    SendOutcome.REFUSED
                }
                409 -> {
                    // layer_off and the like: nothing to retry.
                    _lastError.value = detail
                    SendOutcome.REFUSED
                }
                else -> {
                    _lastError.value = SyncSupport.describe(e)
                    SendOutcome.FAILED
                }
            }
        } finally {
            persist()
        }
    }

    private suspend fun pluginIsMissing(deviceID: String, request: SyncRequest): Boolean = try {
        request("GET", "$BASE/status", statusQuery(deviceID), null)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncSupport.httpCode(e) == 404
    }

    private suspend fun post(batch: LocationBatch, key: LocationSessionKey, request: SyncRequest): JsonObject =
        request("POST", "$BASE/ingest", emptyMap(), LocationCrypto.ingestBody(batch, key))

    private fun acknowledge(batch: LocationBatch, answer: JsonObject) {
        val at = now()
        var next = current.copy(lastSent = at, pause = null)
        _lastError.value = null
        batch.places?.let { next = next.copy(placesDigest = LocationPrecisionRules.placesDigest(it)) }
        batch.fix?.let { next = next.copy(lastFixSent = at, lastFixSource = it.source.raw) }
        answer["generation"].str?.let { generation ->
            // A new store on the host has none of the places.
            if (next.generation != null && next.generation != generation && batch.places == null) next = next.copy(placesDigest = null)
            next = next.copy(generation = generation)
        }
        current = next
    }

    private suspend fun ensureKey(deviceID: String, voice: Boolean, maps: Boolean, request: SyncRequest): LocationSessionKey {
        for (key in listOfNotNull(keys.load(), unsavedKey)) {
            if (key.deviceID == deviceID && (!voice || key.readsByVoice) && (!maps || key.answersMaps)) return key
        }
        val pair = SealCrypto.newKeyPair()
        val answer = try {
            request("POST", "$BASE/key", emptyMap(), buildJsonObject {
                put("device_id", deviceID)
                put("device_name", deviceName())
                put("public_key", SealCrypto.base64Url(pair.publicKey))
            })
        } catch (e: Exception) {
            if (SyncSupport.httpCode(e) == 404) throw KeyRouteMissing()
            throw e
        }
        val hostPublic = answer["host_public_key"].str
        val kid = answer["kid"].str
        if (hostPublic == null || kid == null) throw IllegalStateException(L10n.tr("The host didn't return a location key."))
        val key = LocationCrypto.agree(pair.privateKey, deviceID, hostPublic, kid)
        unsavedKey = if (keys.save(key)) null else key
        current = current.copy(placesDigest = null, generation = answer["generation"].str ?: current.generation)
        return key
    }

    /** This phone's key for a live voice call's read of its own location. */
    fun voiceReadKey(deviceID: String): LocationSessionKey? =
        listOfNotNull(keys.load(), unsavedKey).firstOrNull { it.deviceID == deviceID && it.readsByVoice }

    private fun clearKey() {
        keys.clear()
        unsavedKey = null
    }

    // region Reading and deleting

    suspend fun status(deviceID: String, request: SyncRequest): LocationHostStatus = LocationHostStatus.parse(request("GET", "$BASE/status", statusQuery(deviceID), null))

    suspend fun reads(request: SyncRequest): List<LocationRead> = LocationRead.parse(request("GET", "$BASE/reads", mapOf("limit" to "50"), null))

    suspend fun rules(request: SyncRequest): List<LocationRule> = LocationRule.parse(request("GET", "$BASE/rules", emptyMap(), null))

    suspend fun deleteRule(id: String, request: SyncRequest) {
        request("DELETE", "$BASE/rules/$id", emptyMap(), null)
    }

    /** Deletes this phone's location data on the host, or everything the profile has. The key goes too. */
    suspend fun wipe(deviceID: String, everything: Boolean, request: SyncRequest) {
        try {
            request("POST", "$BASE/wipe", emptyMap(), buildJsonObject { if (everything) put("all", true) else put("device_id", deviceID) })
        } catch (e: Exception) {
            if (e is CancellationException || SyncSupport.httpCode(e) != 404) throw e
        }
        forgetHost()
    }

    fun forgetHost() {
        clearKey()
        current = LocationSyncState()
        persist()
    }

    // endregion

    companion object {
        const val BASE = "${SyncSupport.PLUGIN_BASE}/location"

        fun statusQuery(deviceID: String): Map<String, String?> = mapOf("device_id" to deviceID.takeIf(LocationIDs::isValidWireID))

        /** The host's 409s that mean it has no usable key for this phone. */
        fun needsNewKey(detail: String): Boolean {
            val lowered = detail.lowercase()
            return "unknown_key" in lowered || "bad_seal" in lowered || "unknown key" in lowered
        }
    }
}
