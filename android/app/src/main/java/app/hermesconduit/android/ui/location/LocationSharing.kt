package app.hermesconduit.android.ui.location

import android.content.Context
import android.os.Build
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.DeviceKeyStore
import app.hermesconduit.android.data.FileTextStore
import app.hermesconduit.android.data.TextStore
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.LocationBatch
import app.hermesconduit.android.model.LocationBinding
import app.hermesconduit.android.model.LocationFixRecord
import app.hermesconduit.android.model.LocationFixSource
import app.hermesconduit.android.model.LocationPlaceEvent
import app.hermesconduit.android.model.LocationPrecision
import app.hermesconduit.android.model.LocationRawFix
import app.hermesconduit.android.model.LocationSavedPlace
import app.hermesconduit.android.model.LocationSessionKey
import app.hermesconduit.android.model.LocationSharedConfig
import app.hermesconduit.android.model.LocationSharingSettings
import app.hermesconduit.android.model.LocationUnsent
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.ui.device.DeviceSharing
import app.hermesconduit.android.ui.device.NotReadyException
import app.hermesconduit.android.ui.device.SyncRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class LocationPermissionException : Exception(
    L10n.tr("Conduit doesn't have permission to use your location. Allow it in the Settings app under Apps > Conduit > Permissions > Location."),
)

/** How long sending a message waits for its fix. */
object LocationTiming {
    const val TALKING_WAIT_MS = 2_500L
    /** A background fix when Conduit opens, if the last one is older than this. */
    const val APP_FIX_INTERVAL_MS = 15L * 60 * 1000
    const val CALL_CHECK_INTERVAL_MS = 15_000L
}

/**
 * Location sharing as a whole (iOS LocationSharingController): the user's
 * choices and saved places, when a fix is taken (a message sent, a voice
 * call, a saved place's arrive or leave, a longer move, Conduit opened), the
 * latest unsent fix and events, and sending them to the dashboard and profile
 * sharing is bound to. Nothing is taken or sent while a layer is off or
 * sharing is paused. A message never waits more than [LocationTiming.TALKING_WAIT_MS].
 *
 * The agent's on-demand locate and maps requests reach an iPhone as a
 * location push through the relay; Android has no push relay, so Hermes
 * uses the last position sent (Settings says so).
 */
class LocationSharing internal constructor(
    private val configStore: TextStore,
    private val unsentStore: TextStore,
    val engine: LocationSyncEngine,
    val fixes: LocationFixProvider?,
    private val monitor: LocationBackgroundMonitor?,
    private val scope: CoroutineScope,
    /** The connection to a binding's dashboard, or null when there is none. */
    var requestProvider: (LocationBinding) -> SyncRequest? = { null },
    var now: () -> Long = { System.currentTimeMillis() },
) {
    private val _config: MutableStateFlow<LocationSharedConfig>
    val config: StateFlow<LocationSharedConfig> get() = _config
    /** The config file couldn't be read (damaged). Nothing is sent or saved over it until the user starts over. */
    private val _configUnreadable = MutableStateFlow(false)
    val configUnreadable: StateFlow<Boolean> = _configUnreadable
    private val _configSaveFailed = MutableStateFlow(false)
    val configSaveFailed: StateFlow<Boolean> = _configSaveFailed
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending
    /** Bumped when permissions may have changed, so pages read them again. */
    val permissionsVersion = MutableStateFlow(0)

    private var unsent: LocationUnsent = LocationUnsent.decode(unsentStore.load())
    private val sendMutex = Mutex()
    private var callWatch: Job? = null
    private var lastCallFix: LocationRawFix? = null
    private var activated = false

    init {
        val text = configStore.load()
        val decoded = text?.let(LocationSharedConfig::decode)
        _config = MutableStateFlow(decoded ?: LocationSharedConfig())
        if (text != null && decoded == null) _configUnreadable.value = true
    }

    val settings: LocationSharingSettings get() = _config.value.settings
    val places: List<LocationSavedPlace> get() = _config.value.places
    val binding: LocationBinding? get() = _config.value.binding
    val approximate: Boolean get() = fixes?.isApproximate ?: false

    fun isPaused(): Boolean = settings.isPaused(now())

    private fun save(change: (LocationSharedConfig) -> LocationSharedConfig) {
        if (_configUnreadable.value) return
        _config.value = change(_config.value)
        writeConfig()
    }

    private fun writeConfig() {
        _configSaveFailed.value = !configStore.save(_config.value.encode())
    }

    fun retryConfigSave() {
        if (_configSaveFailed.value && !_configUnreadable.value) writeConfig()
    }

    /** A fresh config, keeping the device id if it can still be found, so the host's copy can be deleted. */
    fun startOver() {
        val deviceID = configStore.load()?.let(LocationSharedConfig::salvageDeviceID)
        _config.value = deviceID?.let { LocationSharedConfig(deviceID = it) } ?: LocationSharedConfig()
        _configUnreadable.value = false
        writeConfig()
        unsent = LocationUnsent()
        saveUnsent()
        voiceCallEnded()
        reconfigureMonitors()
    }

    private fun saveUnsent() {
        unsentStore.save(unsent.encode())
    }

    // region Launch and foreground

    /** At launch (and after a reboot): the background signals are set up again. */
    fun activateAtLaunch() {
        if (activated) {
            reconfigureMonitors()
            return
        }
        activated = true
        if (_configUnreadable.value || binding == null || !settings.background) return
        refreshAuthorization()
        reconfigureMonitors()
    }

    /** Conduit came to the foreground: permissions may have changed, and anything unsent goes now. */
    fun appBecameActive() {
        if (_configUnreadable.value) return
        refreshAuthorization()
        retryConfigSave()
        clearExpiredPause(send = false)
        if (binding == null) return
        reconfigureMonitors()
        val lastFix = engine.state.value.lastFixSent
        if (settings.shares(LocationFixSource.APP, now()) && fixes?.hasBackground == true &&
            (lastFix == null || now() - lastFix > LocationTiming.APP_FIX_INTERVAL_MS)
        ) {
            scope.launch { shareFix(LocationFixSource.APP) }
        } else {
            sendWaiting()
        }
    }

    /** Connected to a dashboard: send what's waiting if it's the bound one. */
    fun hostConnected() {
        if (_configUnreadable.value || binding == null) return
        sendWaiting()
    }

    private fun sendWaiting() {
        if (unsent.isEmpty && !_config.value.settingsPending) return
        val settingsOnly = _config.value.settingsPending
        scope.launch { flush(settingsOnly) }
    }

    fun refreshAuthorization() {
        permissionsVersion.value += 1
        val approximateNow = approximate
        if (_config.value.approximateOnly != approximateNow && binding != null) save { it.copy(approximateOnly = approximateNow) }
    }

    fun reconfigureMonitors() {
        val provider = fixes ?: return
        monitor?.configure(settings, places, binding != null && !_configUnreadable.value, provider)
    }

    // endregion

    // region Layer 1: talking to Hermes

    /**
     * Before a message goes out: starts a fix and waits for its upload, or
     * [LocationTiming.TALKING_WAIT_MS], whichever is first. Returns at once
     * while this layer is off or paused. The fix goes on its own after that.
     */
    suspend fun shareBeforeSending(source: LocationFixSource = LocationFixSource.MESSAGE) {
        if (_configUnreadable.value || binding == null || !settings.shares(source, now()) || fixes?.canLocate != true) return
        val upload = scope.launch { shareFix(source) }
        withTimeoutOrNull(LocationTiming.TALKING_WAIT_MS) { upload.join() }
    }

    /** A voice call started: one fix now, another whenever the phone has moved 200 m, until `stillActive` says it's over. */
    fun voiceCallStarted(stillActive: () -> Boolean) {
        if (callWatch != null || _configUnreadable.value || binding == null || !settings.shares(LocationFixSource.CALL, now()) || fixes?.canLocate != true) return
        lastCallFix = null
        scope.launch { shareFix(LocationFixSource.CALL) }
        monitor?.startCallUpdates { fix -> movedDuringCall(fix) }
        callWatch = scope.launch {
            while (true) {
                delay(LocationTiming.CALL_CHECK_INTERVAL_MS)
                if (!stillActive() || !settings.shares(LocationFixSource.CALL, now())) {
                    voiceCallEnded()
                    return@launch
                }
            }
        }
    }

    fun voiceCallEnded() {
        callWatch?.cancel()
        callWatch = null
        lastCallFix = null
        monitor?.stopCallUpdates()
    }

    private fun movedDuringCall(fix: LocationRawFix) {
        if (callWatch == null || !settings.shares(LocationFixSource.CALL, now())) return
        val last = lastCallFix
        if (last != null && app.hermesconduit.android.model.distanceMeters(last.latitude, last.longitude, fix.latitude, fix.longitude) < LocationBackgroundMonitor.CALL_MOVE_DISTANCE) return
        lastCallFix = fix
        scope.launch { share(fix, LocationFixSource.CALL) }
    }

    // endregion

    // region Layer 2: in the background

    suspend fun placeStateChanged(placeID: String, inside: Boolean, atMs: Long) {
        if (_configUnreadable.value) return
        val place = places.firstOrNull { it.id == placeID } ?: return
        val before = unsent.inside[placeID]
        unsent = unsent.copy(inside = unsent.inside + (placeID to inside))
        saveUnsent()
        // The first answer for a place is where the phone is, not a move.
        if (before == null || before == inside || !settings.shares(LocationFixSource.PLACE, now())) return
        val event = LocationPlaceEvent(kind = if (inside) LocationPlaceEvent.ARRIVE else LocationPlaceEvent.LEAVE, placeID = place.id, atMs = atMs, isPrivate = place.isPrivate)
        shareFix(LocationFixSource.PLACE, listOf(event))
    }

    suspend fun movedSignificantly(fix: LocationRawFix) {
        if (_configUnreadable.value || !settings.shares(LocationFixSource.SIGNIFICANT, now())) return
        share(fix, LocationFixSource.SIGNIFICANT)
    }

    // endregion

    // region Taking and sending

    /** Takes a fix for `source` (with any events) and sends it. True when the host has it. */
    suspend fun shareFix(source: LocationFixSource, events: List<LocationPlaceEvent> = emptyList()): Boolean {
        if (!settings.shares(source, now())) return false
        if (events.isNotEmpty()) {
            // Kept before the fix, so nothing loses an arrive or leave.
            unsent = unsent.keeping(null, events)
            saveUnsent()
        }
        val precision = settings.precision(source)
        val fix = fixes?.currentFix(precision, if (source.isTalking) 30 else 120, if (source.isTalking) 8_000 else 15_000)
        return share(fix, source, events)
    }

    suspend fun share(fix: LocationRawFix?, source: LocationFixSource, events: List<LocationPlaceEvent> = emptyList()): Boolean {
        if (_configUnreadable.value || binding == null || !settings.shares(source, now())) return false
        val record = fix?.let { raw ->
            val area = if (settings.precision(source) == LocationPrecision.PLACES) null else fixes?.area(raw)
            LocationFixRecord(source = source, raw = raw, area = area)
        }
        if (record == null && events.isEmpty()) return false
        unsent = unsent.keeping(record, events)
        saveUnsent()
        return flush()
    }

    /** Sends the unsent fix and events (or just the sharing choices and places), one batch at a time. */
    suspend fun flush(settingsOnly: Boolean = false): Boolean {
        if (settingsOnly && binding != null && !_config.value.settingsPending) save { it.copy(settingsPending = true) }
        return sendMutex.withLock { sendNow(settingsOnly) }
    }

    private suspend fun sendNow(settingsOnly: Boolean): Boolean {
        if (_configUnreadable.value) return false
        val binding = binding ?: return false
        val at = now()
        // What a layer turned off or a pause no longer allows is dropped, not held for later.
        unsent.fix?.let { if (!settings.shares(it.source, at)) unsent = unsent.copy(fix = null) }
        if (!settings.shares(LocationFixSource.PLACE, at)) unsent = unsent.copy(events = emptyList())
        saveUnsent()
        val pending = unsent
        val wantsSettings = settingsOnly || _config.value.settingsPending
        if (pending.isEmpty && !wantsSettings) return true
        val request = requestProvider(binding) ?: return false
        val config = _config.value
        val batch = LocationBatch(
            sentAtMs = at,
            settings = config.settings,
            approximateOnly = approximate || config.approximateOnly,
            knownPlaces = config.places,
            fix = pending.fix,
            events = pending.events,
        )
        _isSending.value = true
        val outcome = try {
            engine.send(batch, config, request)
        } finally {
            _isSending.value = false
        }
        if (outcome == LocationSyncEngine.SendOutcome.FAILED) return false
        // The host has these choices (or won't take them). A change made meanwhile stays pending.
        val now = _config.value
        if (now.settingsPending && now.settings == config.settings && now.places == config.places) save { it.copy(settingsPending = false) }
        unsent = unsent.removing(pending.fix?.id, pending.events.map { it.id }.toSet())
        saveUnsent()
        return outcome == LocationSyncEngine.SendOutcome.SENT
    }

    // endregion

    // region Choices

    enum class Layer { TALKING, BACKGROUND }

    /** Turns a layer on for `destination` (the page asked for permission first), binding sharing when nothing is on yet. */
    suspend fun turnOn(layer: Layer, destination: LocationBinding) {
        if (_configUnreadable.value) throw IllegalStateException(L10n.tr("Unlock your phone and try again."))
        if (fixes?.canLocate == false) throw LocationPermissionException()
        refreshAuthorization()
        val current = _config.value
        if (current.binding == null || (!current.settings.isOn && current.binding != destination)) {
            if (current.binding != destination) engine.forgetHost()
            save { it.copy(binding = destination) }
            refreshAuthorization()
        }
        save { config ->
            config.copy(settings = when (layer) {
                Layer.TALKING -> config.settings.copy(talking = true)
                Layer.BACKGROUND -> config.settings.copy(background = true)
            })
        }
        reconfigureMonitors()
        flush(settingsOnly = true)
    }

    suspend fun turnOff(layer: Layer) {
        save { config ->
            val settings = when (layer) {
                Layer.TALKING -> config.settings.copy(talking = false)
                Layer.BACKGROUND -> config.settings.copy(background = false)
            }
            // Sharing off: turning it on again asks each voice mode again.
            config.copy(settings = settings, voiceReaders = if (settings.isOn) config.voiceReaders else null)
        }
        if (layer == Layer.TALKING) voiceCallEnded()
        reconfigureMonitors()
        flush(settingsOnly = true)
    }

    suspend fun setPrecision(precision: LocationPrecision, layer: Layer) {
        save { config ->
            config.copy(settings = when (layer) {
                Layer.TALKING -> config.settings.copy(talkingPrecision = precision)
                Layer.BACKGROUND -> config.settings.copy(backgroundPrecision = precision)
            })
        }
        reconfigureMonitors()
        flush(settingsOnly = true)
    }

    suspend fun setMapsHost(allowed: Boolean) {
        if (settings.mapsHost == allowed) return
        save { it.copy(settings = it.settings.copy(mapsHost = allowed)) }
        flush(settingsOnly = true)
    }

    suspend fun setRetention(days: Int) {
        if (days !in LocationSharingSettings.RETENTION_CHOICES) return
        save { it.copy(settings = it.settings.copy(retentionDays = days)) }
        flush(settingsOnly = true)
    }

    /** Pauses until `untilMs` ([LocationSharingSettings.UNTIL_TURNED_ON]: until turned back on), or resumes with null. */
    suspend fun pause(untilMs: Long?) {
        save { it.copy(settings = it.settings.copy(pausedUntil = untilMs)) }
        if (untilMs != null) {
            unsent = unsent.copy(fix = null, events = emptyList())
            saveUnsent()
            voiceCallEnded()
        }
        flush(settingsOnly = true)
        if (untilMs == null) prepareVoiceReads()
    }

    /** Resumes a pause whose time has passed, and tells the host. */
    fun clearExpiredPause(send: Boolean = true) {
        val until = settings.pausedUntil ?: return
        if (until > now()) return
        save { it.copy(settings = it.settings.copy(pausedUntil = null), settingsPending = it.binding != null || it.settingsPending) }
        if (send && binding != null) scope.launch {
            flush(settingsOnly = true)
            prepareVoiceReads()
        }
    }

    // endregion

    // region Live voice calls

    fun letsVoiceRead(mode: LiveVoiceMode): Boolean = !_configUnreadable.value && _config.value.letsVoiceRead(mode.storageID, now())

    fun allowsVoiceRead(mode: LiveVoiceMode): Boolean = mode.storageID in _config.value.voiceReaders.orEmpty()

    suspend fun setVoiceRead(mode: LiveVoiceMode, allowed: Boolean) {
        if (_configUnreadable.value || binding == null || (!settings.isOn && allowed)) return
        save { it.withVoiceRead(mode.storageID, allowed) }
        if (allowed) prepareVoiceReads()
    }

    fun voiceReadKey(): LocationSessionKey? {
        if (_configUnreadable.value || binding == null) return null
        return engine.voiceReadKey(_config.value.deviceID)
    }

    /** A key agreed before voice reads is set up again now (a send does it), with a fresh position when sharing allows one. */
    suspend fun prepareVoiceReads() {
        if (voiceReadKey() != null || !_config.value.readsByVoice(now())) return
        if (fixes?.canLocate == true) {
            if (shareFix(LocationFixSource.APP)) return
            if (callWatch != null && shareFix(LocationFixSource.CALL)) return
        }
        flush(settingsOnly = true)
    }

    // endregion

    // region Saved places

    suspend fun savePlace(place: LocationSavedPlace) {
        save { config ->
            val index = config.places.indexOfFirst { it.id == place.id }
            config.copy(places = when {
                index >= 0 -> config.places.toMutableList().also { it[index] = place.normalized() }
                config.places.size < LocationSavedPlace.MAX_COUNT -> config.places + place.normalized()
                else -> config.places
            })
        }
        // A moved place starts from where the phone is.
        unsent = unsent.copy(inside = unsent.inside - place.id)
        saveUnsent()
        reconfigureMonitors()
        flush(settingsOnly = true)
    }

    suspend fun removePlace(id: String) {
        save { config -> config.copy(places = config.places.filter { it.id != id }) }
        unsent = unsent.copy(inside = unsent.inside - id, events = unsent.events.filter { it.placeID != id })
        saveUnsent()
        reconfigureMonitors()
        flush(settingsOnly = true)
    }

    // endregion

    // region Host

    fun hostRequest(): SyncRequest? = binding?.let(requestProvider)

    /** Deletes what this phone (or, with `everything`, every device and the place rules) sent to the host. */
    suspend fun wipe(everything: Boolean) {
        val request = hostRequest() ?: throw NotReadyException()
        engine.wipe(_config.value.deviceID, everything, request)
        unsent = unsent.copy(fix = null, events = emptyList())
        saveUnsent()
        if (!settings.isOn) save { it.copy(binding = null) }
    }

    // endregion

    companion object {
        const val CAPABILITY = "location-sync"
        const val LOCATE_CAPABILITY = "location-locate"
        const val LOCATION_VOICE_VERSION = "0.28.0"

        /** Whether the running plugin answers live voice reads of the location (older ones serve health only). */
        fun servesLocationVoice(status: app.hermesconduit.android.model.PluginStatus): Boolean =
            status.reports("voice-tools") && !app.hermesconduit.android.model.PluginStatus.isOlder(status.runningVersion, LOCATION_VOICE_VERSION)

        @Volatile private var instance: LocationSharing? = null

        fun of(context: Context): LocationSharing = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): LocationSharing {
            val sharing = LocationSharing(
                configStore = FileTextStore.of(context, "Location", "config.json"),
                unsentStore = FileTextStore.of(context, "Location", "unsent.json"),
                engine = LocationSyncEngine(
                    FileTextStore.of(context, "Location", "state.json"),
                    LocationKeyStore(DeviceKeyStore.store(context, "location-key.v1")),
                    deviceName = { "${Build.MANUFACTURER} ${Build.MODEL}".trim() },
                ),
                fixes = LocationFixProvider(context),
                monitor = LocationBackgroundMonitor(context),
                scope = context.hermes.scope,
            )
            sharing.requestProvider = { binding -> DeviceSharing.request(context, binding.dashboardID, binding.profile) }
            return sharing
        }

        /** Where turning on would bind: the dashboard and profile in use. */
        fun destination(context: Context): LocationBinding? {
            val id = DeviceSharing.activeDashboardID(context) ?: return null
            return LocationBinding(id, DeviceSharing.activeProfile(context), DeviceSharing.activeHost(context))
        }

        /** Whether Conduit is on screen (a background fix then isn't a surprise). */
        val inForeground: Boolean get() = ForegroundState.visible
    }
}
