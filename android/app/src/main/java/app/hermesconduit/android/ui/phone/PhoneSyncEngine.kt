package app.hermesconduit.android.ui.phone

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.TextStore
import app.hermesconduit.android.model.CanonicalJson
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.PhoneAutoAdded
import app.hermesconduit.android.model.PhoneCalendarAccess
import app.hermesconduit.android.model.PhoneCalendarWindow
import app.hermesconduit.android.model.PhoneChange
import app.hermesconduit.android.model.PhoneChangePrompt
import app.hermesconduit.android.model.PhoneChangeResult
import app.hermesconduit.android.model.PhoneChangeWording
import app.hermesconduit.android.model.PhoneDates
import app.hermesconduit.android.model.PhoneFocusSnapshot
import app.hermesconduit.android.model.PhonePerson
import app.hermesconduit.android.model.PhoneSessionKey
import app.hermesconduit.android.model.PhoneSyncCrypto
import app.hermesconduit.android.model.PhoneSyncLimits
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.PhoneSyncRules
import app.hermesconduit.android.model.PhoneSyncState
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.long
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

/** The calendar reading and writing a pass needs; a fake stands in for tests. */
interface PhoneCalendarSource {
    val eventAccess: PhoneCalendarAccess
    /** Android has no system reminders: false there. */
    val remindersAuthorized: Boolean
    /** Calendars, each `{id, title, kind, color, source, writable, shared, default}`. */
    fun calendars(eventsReadable: Boolean, reminders: Boolean): List<JsonObject>
    suspend fun events(from: Instant, to: Instant, hidden: Set<String>, details: Boolean, emails: Boolean, limit: Int): List<JsonObject>
    suspend fun reminders(hidden: Set<String>, details: Boolean, completedSince: Instant, limit: Int): List<JsonObject>
    /** Applies one change; the id of the item it made or touched. */
    suspend fun apply(change: PhoneChange, window: Pair<Instant, Instant>): String?
    suspend fun removeAdded(itemID: String, isEvent: Boolean, window: Pair<Instant, Instant>)
    fun calendarTitle(id: String?, forEvents: Boolean): String?
    /** The platform id behind a shared calendar id, for leaving it out. */
    fun platformCalendarID(forShared: String): String?
}

interface PhoneStatusSource {
    /** The `status` object an upload carries. */
    suspend fun snapshot(): JsonObject
    /** Do Not Disturb as a Focus snapshot, when it can be read. */
    fun focus(): PhoneFocusSnapshot?
}

interface PhoneAlarmSource {
    val isSupported: Boolean
    val authorized: Boolean
    /** The alarms and timers Conduit set that are still on the phone; null when unknown. */
    fun alarms(now: Instant, zone: ZoneId): List<JsonObject>?
    suspend fun apply(change: PhoneChange, now: Instant): String
    fun cancel(id: String)
}

class PhoneKeyStore(private val store: TextStore) {
    fun load(): PhoneSessionKey? = store.load()?.let { runCatching { ConduitJson.decodeFromString(PhoneSessionKey.serializer(), it) }.getOrNull() }
    fun save(key: PhoneSessionKey) = store.save(ConduitJson.encodeToString(PhoneSessionKey.serializer(), key))
    fun clear() = store.save(null)
}

/**
 * One calendar & phone pass (iOS PhoneSyncEngine): make sure this phone and
 * the Hermes profile share a key, send what changed since the last pass, then
 * trade results for the changes Hermes is waiting on. The phone remembers a
 * hash of each item it last sent and uploads only the difference; everything
 * goes whole again once a day, when the choices change, or when the host's
 * store is new. Hashes move only after the host took the batch.
 */
class PhoneSyncEngine(
    val calendarSource: PhoneCalendarSource,
    val statusSource: PhoneStatusSource,
    val alarmSource: PhoneAlarmSource,
    private val store: TextStore,
    private val keys: PhoneKeyStore,
    private val preferencesStore: TextStore,
    private val peopleStore: TextStore,
    var zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
    private val deviceName: () -> String = { "Android" },
) {
    private val serial = SyncSupport.serialDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _state = MutableStateFlow(PhoneSyncState.decode(store.load()))
    val state: StateFlow<PhoneSyncState> = _state
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    private val _preferences = MutableStateFlow(PhoneSyncPreferences.decode(preferencesStore.load()))
    val preferences: StateFlow<PhoneSyncPreferences> = _preferences
    /** Changes waiting for the user's answer, oldest first. */
    private val _prompts = MutableStateFlow<List<PhoneChangePrompt>>(emptyList())
    val prompts: StateFlow<List<PhoneChangePrompt>> = _prompts
    /** The latest addition made without asking, until the banner goes. */
    val autoAdded = MutableStateFlow<PhoneAutoAdded?>(null)
    private val _people = MutableStateFlow(PhonePerson.decodeAll(peopleStore.load()))
    val people: StateFlow<List<PhonePerson>> = _people

    private var running: Deferred<SyncOutcome>? = null
    private var turnOffs = 0
    /** Changes being applied right now, so an exchange that lands meanwhile doesn't hand one out again. */
    private val applying = mutableSetOf<String>()

    private var current: PhoneSyncState
        get() = _state.value
        set(value) { _state.value = value }

    private var saved: String? = null
    private fun persist() {
        val text = current.encode()
        if (text == saved) return
        if (store.save(text)) saved = text
    }

    fun savePreferences(preferences: PhoneSyncPreferences) {
        preferencesStore.save(preferences.encode())
        _preferences.value = preferences
    }

    fun savePeople(people: List<PhonePerson>) {
        val kept = people.take(PhonePerson.MAXIMUM)
        peopleStore.save(if (kept.isEmpty()) null else PhonePerson.encodeAll(kept))
        _people.value = kept
    }

    /** Runs a pass, or joins the one already running and runs once more after it. */
    suspend fun sync(deadline: Instant, request: SyncRequest): SyncOutcome = withContext(serial) {
        val turnOffsAtStart = turnOffs
        running?.let { joined ->
            runCatching { joined.await() }
            if (turnOffs != turnOffsAtStart) return@withContext SyncOutcome.Off
            if (running === joined) running = null
            running?.let { newer -> return@withContext runCatching { newer.await() }.getOrElse { SyncOutcome.Unfinished } }
        }
        val task = scope.async { pass(deadline, request) }
        running = task
        _isSyncing.value = true
        try {
            task.await()
        } catch (e: CancellationException) {
            task.cancel()
            throw e
        } finally {
            if (running === task) {
                running = null
                _isSyncing.value = false
            }
        }
    }

    suspend fun note(pause: SyncPause?) = withContext(serial) {
        if (current.pause == pause) return@withContext
        current = current.copy(pause = pause)
        persist()
    }

    suspend fun turnOff(deleteFromHost: SyncRequest?): Boolean = withContext(serial) {
        turnOffs += 1
        running?.let { task ->
            task.cancel()
            runCatching { task.await() }
            if (running === task) {
                running = null
                _isSyncing.value = false
            }
        }
        _prompts.value = emptyList()
        autoAdded.value = null
        keys.clear()
        var deleted = false
        if (deleteFromHost != null) {
            try {
                deleteFromHost("POST", "$BASE/wipe", emptyMap(), buildJsonObject { put("device_id", current.deviceID) })
                deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (SyncSupport.httpCode(e) == 404) deleted = true else _lastError.value = SyncSupport.describe(e)
            }
        }
        current = current.startedOver()
        persist()
        deleted
    }

    // region The pass

    private suspend fun pass(deadline: Instant, request: SyncRequest): SyncOutcome {
        val preferences = _preferences.value
        val destination = preferences.destination
        if (!preferences.isOn || destination == null) return SyncOutcome.Off
        if (current.destination != destination) {
            keys.clear()
            current = current.startedOver().copy(destination = destination)
        }
        current = current.copy(lastAttempt = now().toEpochMilli(), pause = null)
        _lastError.value = null
        try {
            val key = ensureKey(request)
            checkTime(deadline)
            val pending = upload(preferences, key, request)
            checkTime(deadline)
            if (pending > 0 || current.unreported.isNotEmpty() || _prompts.value.isNotEmpty()) exchange(key, request)
            current = current.copy(lastSync = now().toEpochMilli())
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
                    keys.clear()
                    current = current.forgotSent()
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

    private suspend fun checkTime(deadline: Instant) {
        coroutineContext.ensureActive()
        if (now() >= deadline) throw DeadlineReached()
    }

    private suspend fun ensureKey(request: SyncRequest): PhoneSessionKey {
        val status = request("GET", "$BASE/status", mapOf("device_id" to current.deviceID), null)
        val generation = status["generation"].str
        val hostKid = status["device"].obj?.get("kid").str
        val known = current.generation
        if (generation != null && known != null && known != generation) current = current.forgotSent()
        keys.load()?.takeIf { it.kid == hostKid }?.let { key ->
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
        if (hostPublic == null || kid == null) throw IllegalStateException(L10n.tr("The host didn't return a key."))
        val key = PhoneSyncCrypto.agree(pair.privateKey, current.deviceID, hostPublic, kid)
        coroutineContext.ensureActive()
        keys.save(key)
        current = current.forgotSent().copy(generation = answer["generation"].str ?: generation)
        return key
    }

    /** The calendar window this pass shares. */
    val window: Pair<Instant, Instant>
        get() {
            val today = now().atZone(zone()).toLocalDate().atStartOfDay(zone())
            return today.minusDays(PhoneCalendarWindow.PAST_DAYS.toLong()).toInstant() to
                today.plusDays(_preferences.value.calendarWindow.days + 1L).toInstant()
        }

    /** Sends what changed; the number of changes the host has waiting. */
    private suspend fun upload(preferences: PhoneSyncPreferences, key: PhoneSessionKey, request: SyncRequest): Int {
        val at = now()
        val nowMs = at.toEpochMilli()
        val full = current.fingerprint != preferences.fingerprint ||
            (current.lastFullSync?.let { nowMs - it > PhoneSyncLimits.FULL_SYNC_INTERVAL_MS } ?: true)
        val window = this.window
        val batch = linkedMapOf<String, JsonElement>(
            "sent_at" to JsonPrimitive(PhoneDates.format(at, zone())),
            "time_zone" to JsonPrimitive(zone().id),
            "sharing" to PhoneSyncRules.effectiveSharing(preferences, calendarSource.eventAccess, calendarSource.remindersAuthorized, alarmSource.authorized),
        )
        var next = current

        val readsEvents = preferences.calendarAccess == PhoneCalendarAccess.FULL && calendarSource.eventAccess == PhoneCalendarAccess.FULL
        val readsReminders = preferences.reminders && calendarSource.remindersAuthorized
        val calendars = calendarSource.calendars(readsEvents, readsReminders).filter { entry ->
            preferences.hiddenCalendars.none { PhoneSyncRules.hashedID("c_", it) == entry["id"].str }
        }
        val calendarsHash = PhoneSyncRules.hash(JsonArray(calendars))
        if (full || calendarsHash != current.calendarsHash) {
            batch["calendars"] = JsonArray(calendars)
            next = next.copy(calendarsHash = calendarsHash)
        }

        if (readsEvents) {
            val events = calendarSource.events(window.first, window.second, preferences.hiddenCalendars, preferences.details,
                preferences.details && preferences.attendeeEmails, PhoneSyncLimits.MAX_EVENTS)
            val section = PhoneSyncRules.diff(events, current.eventHashes, full, events.size >= PhoneSyncLimits.MAX_EVENTS)
            if (full || section.upsert.isNotEmpty() || section.delete.isNotEmpty()) {
                batch["events"] = buildJsonObject {
                    put("full", full)
                    put("from", PhoneDates.format(window.first, zone()))
                    put("to", PhoneDates.format(window.second, zone()))
                    put("upsert", JsonArray(section.upsert))
                    put("delete", JsonArray(section.delete.map(::JsonPrimitive)))
                }
            }
            next = next.copy(eventHashes = section.hashes)
        } else {
            // The host drops every stored event when sharing says write-only or off.
            next = next.copy(eventHashes = emptyMap())
        }

        if (readsReminders) {
            val since = at.minusSeconds(14L * 86_400)
            val reminders = calendarSource.reminders(preferences.hiddenCalendars, preferences.details, since, 1_000)
            val section = PhoneSyncRules.diff(reminders, current.reminderHashes, full, reminders.size >= 1_000)
            if (full || section.upsert.isNotEmpty() || section.delete.isNotEmpty()) {
                batch["reminders"] = buildJsonObject {
                    put("full", full)
                    put("upsert", JsonArray(section.upsert))
                    put("delete", JsonArray(section.delete.map(::JsonPrimitive)))
                }
            }
            next = next.copy(reminderHashes = section.hashes)
        } else {
            next = next.copy(reminderHashes = emptyMap())
        }

        var statusSent = false
        if (preferences.status) {
            val status = statusSource.snapshot()
            val hash = PhoneSyncRules.hash(JsonObject(status - "at"))
            val stale = current.statusSentAt?.let { nowMs - it > PhoneSyncLimits.STATUS_REFRESH_MS } ?: true
            if (full || hash != current.statusHash || stale) {
                batch["status"] = JsonObject(status + ("at" to JsonPrimitive(PhoneDates.format(at, zone()))))
                next = next.copy(statusHash = hash)
                statusSent = true
            }
        } else {
            next = next.copy(statusHash = null)
        }
        val focus = if (preferences.focus) statusSource.focus() else null
        if (preferences.focus && focus != null) {
            val comparable = JsonObject(focus.json - "at")
            val hash = PhoneSyncRules.hash(comparable)
            if (full || hash != current.focusHash) {
                batch["focus"] = focus.json
                next = next.copy(focusHash = hash)
            }
        } else if (!preferences.focus) {
            next = next.copy(focusHash = null)
        }
        if (preferences.alarms && alarmSource.authorized) {
            val alarms = alarmSource.alarms(at, zone())
            if (alarms != null) {
                val hash = PhoneSyncRules.hash(JsonArray(alarms))
                if (full || hash != current.alarmsHash) {
                    batch["alarms"] = JsonArray(alarms)
                    next = next.copy(alarmsHash = hash)
                }
            }
        } else {
            next = next.copy(alarmsHash = null)
        }
        if (preferences.contacts) {
            val contacts = JsonArray(_people.value.map { it.json })
            val hash = PhoneSyncRules.hash(contacts)
            if (full || hash != current.contactsHash) {
                batch["contacts"] = contacts
                next = next.copy(contactsHash = hash)
            }
        } else {
            next = next.copy(contactsHash = null)
        }

        val batchID = SealCrypto.compactUuid()
        batch["batch_id"] = JsonPrimitive(batchID)
        val body = PhoneSyncCrypto.envelope(CanonicalJson.bytes(JsonObject(batch)), key, current.deviceID, batchID)
        val answer = request("POST", "$BASE/ingest", emptyMap(), body)
        // Taken: what was sent is now what the host has.
        current = current.copy(
            eventHashes = next.eventHashes,
            reminderHashes = next.reminderHashes,
            calendarsHash = next.calendarsHash,
            statusHash = next.statusHash,
            focusHash = next.focusHash,
            alarmsHash = next.alarmsHash,
            contactsHash = next.contactsHash,
            statusSentAt = if (statusSent) nowMs else current.statusSentAt,
            fingerprint = preferences.fingerprint,
            lastFullSync = if (full) nowMs else current.lastFullSync,
        )
        persist()
        return answer["pending"].long?.toInt() ?: 0
    }

    // endregion

    // region Changes from Hermes

    private suspend fun exchange(key: PhoneSessionKey, request: SyncRequest) {
        val callID = SealCrypto.compactUuid()
        val reporting = current.unreported
        val payload = buildJsonObject {
            put("call_id", callID)
            put("results", JsonArray(reporting.map { it.json }))
        }
        val body = PhoneSyncCrypto.exchangeEnvelope(CanonicalJson.bytes(payload), "changes", key, current.deviceID, callID)
        val answer = request("POST", "$BASE/exchange", emptyMap(), body)
        val opened = PhoneSyncCrypto.openAnswer(answer, "changes", key, current.deviceID, callID)
        val json = runCatching { ConduitJson.parseToJsonElement(opened.decodeToString()).obj }.getOrNull()
        if (json == null || json["call_id"].str != callID) throw SealCrypto.Failure("malformedAnswer")
        current = current.copy(unreported = current.unreported.filter { it !in reporting })
        val changes = json["changes"].arr.orEmpty().mapNotNull { it.obj?.let(PhoneChange::parse) }
        val waiting = changes.map { it.id }.toSet()
        // Answers the host no longer asks about are settled.
        current = current.copy(answered = current.answered.filter { it.key in waiting || it.value in current.unreported })
        val fresh = mutableListOf<PhoneChangePrompt>()
        for (change in changes) {
            if (change.id in applying) continue
            val known = current.answered[change.id]
            if (known != null) {
                // Answered, but the report was lost: say it again.
                if (known !in current.unreported) current = current.copy(unreported = current.unreported + known)
                continue
            }
            if (change.auto && change.kind.addsWithoutAsking && _preferences.value.autoAdd) {
                applyNow(change, auto = true)
            } else {
                fresh += PhoneChangePrompt(change, calendarTitle(change), alarmTitle(change))
            }
        }
        _prompts.value = fresh
        persist()
    }

    /** The user's answer to a card (or to the question in a voice call). */
    suspend fun answer(prompt: PhoneChangePrompt, approve: Boolean) = withContext(serial) {
        _prompts.value = _prompts.value.filterNot { it.id == prompt.id }
        if (approve) applyNow(prompt.change, auto = false) else record(PhoneChangeResult(prompt.id, PhoneChangeResult.DISMISSED))
    }

    private fun calendarTitle(change: PhoneChange): String? {
        if (change.kind.isAlarm) return null
        return calendarSource.calendarTitle(change.fields.calendarID ?: change.fields.listID, change.kind.isEvent)
    }

    private fun alarmTitle(change: PhoneChange): String? {
        if (change.kind != app.hermesconduit.android.model.PhoneChangeKind.CANCEL_ALARM) return null
        val alarm = alarmSource.alarms(now(), zone())?.firstOrNull { it["id"].str?.lowercase() == change.alarmID?.lowercase() } ?: return null
        val ring = alarm["fires_at"].str?.let(PhoneDates::parse)?.let {
            java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT).format(it.atZone(zone()))
        }
        val parts = listOfNotNull(alarm["label"].str, ring)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private suspend fun applyNow(change: PhoneChange, auto: Boolean) {
        if (current.answered[change.id] != null || !applying.add(change.id)) return
        try {
            if (change.kind.isAlarm) {
                applyAlarm(change, auto)
                return
            }
            try {
                val itemID = calendarSource.apply(change, window)
                record(PhoneChangeResult(change.id, PhoneChangeResult.APPLIED, itemID))
                // Counted as sent, so the next upload sends the new item or its deletion.
                if (change.kind.isAddition && itemID != null) {
                    current = if (change.kind.isEvent) {
                        if (current.eventHashes[itemID] == null) current.copy(eventHashes = current.eventHashes + (itemID to "")) else current
                    } else if (current.reminderHashes[itemID] == null) current.copy(reminderHashes = current.reminderHashes + (itemID to "")) else current
                    persist()
                }
                if (auto && itemID != null) {
                    autoAdded.value = PhoneAutoAdded(change.id, itemID, change.fields.title.orEmpty(),
                        calendarSource.calendarTitle(change.fields.calendarID ?: change.fields.listID, change.kind.isEvent), change.kind.isEvent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(PhoneChangeResult(change.id, PhoneChangeResult.FAILED, note = SyncSupport.describe(e)))
                if (!auto) _lastError.value = SyncSupport.describe(e)
            }
        } finally {
            applying.remove(change.id)
        }
    }

    private suspend fun applyAlarm(change: PhoneChange, auto: Boolean) {
        try {
            val alarmID = alarmSource.apply(change, now())
            record(PhoneChangeResult(change.id, PhoneChangeResult.APPLIED, alarmID))
            if (auto) {
                autoAdded.value = PhoneAutoAdded(change.id, alarmID, PhoneChangeWording.alarmSummary(change.fields, change.kind, zone()), null,
                    isEvent = false, isAlarm = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(PhoneChangeResult(change.id, PhoneChangeResult.FAILED, note = SyncSupport.describe(e)))
            if (!auto) _lastError.value = SyncSupport.describe(e)
        }
    }

    private fun record(result: PhoneChangeResult) {
        current = current.copy(answered = current.answered + (result.id to result), unreported = current.unreported + result)
        persist()
    }

    // endregion

    // region Live voice calls

    class VoiceNotReady : Exception("The calendar isn't ready for voice yet.")
    class NotAnAddition : Exception("Voice adds only.")

    /** One sealed read for a live voice call (`op: "voice"`, at `path`). */
    suspend fun voiceRead(tool: String, arguments: JsonObject, provider: String, path: String, request: SyncRequest): JsonElement {
        val key = keys.load()
        if (!_preferences.value.isOn || key == null) throw VoiceNotReady()
        val deviceID = current.deviceID
        val callID = SealCrypto.compactUuid()
        val payload = buildJsonObject {
            put("call_id", callID); put("tool", tool); put("args", arguments); put("provider", provider)
        }
        val body = PhoneSyncCrypto.exchangeEnvelope(CanonicalJson.bytes(payload), "voice", key, deviceID, callID)
        val answer = request("POST", path, emptyMap(), body)
        val opened = PhoneSyncCrypto.openAnswer(answer, "voice", key, deviceID, callID)
        val json = runCatching { ConduitJson.parseToJsonElement(opened.decodeToString()).obj }.getOrNull()
        if (json == null || json["call_id"].str != callID) throw SealCrypto.Failure("malformedAnswer")
        return json["result"] ?: JsonNull
    }

    /** Adds an event or alarm the user asked for in a voice call, straight away. */
    suspend fun addFromVoice(change: PhoneChange): String? = withContext(serial) {
        if (!change.kind.addsWithoutAsking) throw NotAnAddition()
        if (!_preferences.value.isOn) throw VoiceNotReady()
        if (change.kind.isAlarm) {
            if (!_preferences.value.alarms) throw VoiceNotReady()
            return@withContext alarmSource.apply(change, now())
        }
        val itemID = calendarSource.apply(change, window)
        if (itemID != null) {
            current = if (change.kind.isEvent) {
                if (current.eventHashes[itemID] == null) current.copy(eventHashes = current.eventHashes + (itemID to "")) else current
            } else if (current.reminderHashes[itemID] == null) current.copy(reminderHashes = current.reminderHashes + (itemID to "")) else current
            persist()
        }
        itemID
    }

    /** The banner's Undo: takes back an addition made without asking. */
    suspend fun undo(added: PhoneAutoAdded) = withContext(serial) {
        if (autoAdded.value == added) autoAdded.value = null
        try {
            if (added.isAlarm) alarmSource.cancel(added.itemID) else calendarSource.removeAdded(added.itemID, added.isEvent, window)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = SyncSupport.describe(e)
        }
    }

    /** Whether a change was answered here (and how), for voice. */
    fun answered(id: String): PhoneChangeResult? = current.answered[id]

    // endregion

    companion object {
        const val BASE = "${SyncSupport.PLUGIN_BASE}/phone"
    }
}
