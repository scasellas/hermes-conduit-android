package app.hermesconduit.android.ui.health

import android.content.Context
import android.os.Build
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.hermes
import kotlinx.coroutines.launch
import app.hermesconduit.android.data.DeviceKeyStore
import app.hermesconduit.android.data.FileTextStore
import app.hermesconduit.android.model.HealthCategory
import app.hermesconduit.android.model.HealthHistoryDepth
import app.hermesconduit.android.model.HealthSyncPreferences
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PendingDeletion
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.ui.device.DeviceSharing
import app.hermesconduit.android.ui.device.NotReadyException
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.device.SyncSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

enum class HealthSyncReason { CONNECTED, FOREGROUND, HEALTH_CHANGED, BACKGROUND_REFRESH, MANUAL, TURNED_ON, VOICE_CALL }

/**
 * When health data syncs, and with which connection (iOS AppState+HealthSync).
 * A pass runs when Conduit connects to the host or comes to the foreground,
 * on the hourly background pass, and on "Sync now". It goes only to the
 * dashboard and profile sharing was turned on for, over that dashboard's own
 * connection: never through the push relay.
 */
class HealthSync private constructor(private val context: Context) {
    val source = HealthConnectSource(context)
    val engine = HealthSyncEngine(
        source = source,
        store = FileTextStore.of(context, "HealthSync"),
        keys = HealthKeyStore(DeviceKeyStore.store(context, "health-key.v1")),
        deviceName = { "${Build.MANUFACTURER} ${Build.MODEL}".trim() },
    )
    private val settings = context.appSettings

    private val _preferences = MutableStateFlow(HealthSyncPreferences.decode(settings.getString(HealthSyncPreferences.STORAGE_KEY, "").ifEmpty { null }))
    val preferences: StateFlow<HealthSyncPreferences> = _preferences
    private var updateGeneration = 0

    private fun save(preferences: HealthSyncPreferences) {
        settings.set(HealthSyncPreferences.STORAGE_KEY, preferences.encode())
        _preferences.value = preferences
        DeviceSharing.scheduleBackground(context)
    }

    /** Runs a pass when sharing is on and its dashboard is the one in use. */
    suspend fun run(reason: HealthSyncReason): SyncOutcome {
        finishDeletionLeftOnHost()
        val started = Instant.now()
        val preferences = _preferences.value
        val destination = preferences.destination
        if (!preferences.isOn || destination == null) return SyncOutcome.Off
        val active = DeviceSharing.activeDashboardID(context)
        if (engine.state.value.pause == SyncPause.OTHER_DASHBOARD && (active == null || active == destination.dashboardID)) engine.note(null)
        // Coming back to the app often isn't news; the hourly pass covers the rest.
        val last = engine.state.value.lastAttempt
        if (reason == HealthSyncReason.FOREGROUND && last != null && System.currentTimeMillis() - last < 180_000) return SyncOutcome.Unfinished
        active ?: return SyncOutcome.Unfinished
        if (active != destination.dashboardID) {
            engine.note(SyncPause.OTHER_DASHBOARD)
            return SyncOutcome.Paused(SyncPause.OTHER_DASHBOARD)
        }
        val background = !app.hermesconduit.android.notify.ForegroundState.visible
        // A few minutes in the background (WorkManager allows ten), longer on screen so a first sync brings in history.
        val deadline = started.plusSeconds(if (background) 240 else 120)
        val request = DeviceSharing.request(context, destination.dashboardID, destination.profile, deadline) ?: return SyncOutcome.Unfinished
        if (_preferences.value != preferences) return SyncOutcome.Off
        return engine.sync(preferences, deadline, request)
    }

    /** Turns sharing on for the dashboard and profile in use (after Health Connect's permission sheet). */
    suspend fun turnOn(categories: Set<HealthCategory>, detailed: Set<HealthCategory>, history: HealthHistoryDepth) {
        val dashboardID = DeviceSharing.activeDashboardID(context) ?: throw NotReadyException()
        finishDeletionLeftOnHost()?.let { error -> if (owed(dashboardID) != null) throw error }
        save(
            _preferences.value.copy(
                destination = SyncDestination(dashboardID, DeviceSharing.activeProfile(context), DeviceSharing.activeHost(context)),
                categories = categories.map { it.raw }.toSet(),
                detailed = detailed.intersect(categories).map { it.raw }.toSet(),
                history = history.raw,
            ),
        )
        // Not waited on: turning on is done once the choice is saved.
        context.hermes.scope.launch { run(HealthSyncReason.TURNED_ON) }
    }

    /** Changes what's shared (new categories asked Health Connect first, in the page). */
    suspend fun update(categories: Set<HealthCategory>, detailed: Set<HealthCategory>, history: HealthHistoryDepth) {
        if (_preferences.value.destination == null || categories.isEmpty()) return
        updateGeneration += 1
        save(
            _preferences.value.copy(
                categories = categories.map { it.raw }.toSet(),
                detailed = detailed.intersect(categories).map { it.raw }.toSet(),
                history = history.raw,
            ),
        )
        context.hermes.scope.launch { run(HealthSyncReason.MANUAL) }
    }

    /**
     * Turns sharing off. With `deleteFromHost`, this phone's health data is
     * deleted on the host first. False when the host couldn't be reached: the
     * deletion then runs the next time Conduit connects to that dashboard.
     */
    suspend fun turnOff(deleteFromHost: Boolean): Boolean {
        val destination = _preferences.value.destination
        val deviceID = engine.state.value.deviceID
        val request = if (deleteFromHost && destination != null) DeviceSharing.request(context, destination.dashboardID, destination.profile) else null
        // Off before anything waits, so a pass queued meanwhile finds it off.
        save(HealthSyncPreferences())
        DeviceSharing.clearVoiceReads(context, VoiceReadLog.HEALTH_KEY)
        val deleted = engine.turnOff(request)
        if (deleteFromHost && !deleted && destination != null) {
            storeDeletions(deletions().filter { it.dashboardID != destination.dashboardID } + PendingDeletion(destination.dashboardID, destination.profile, deviceID))
        }
        return deleted || !deleteFromHost
    }

    fun setVoiceRead(mode: LiveVoiceMode, allowed: Boolean) {
        val preferences = _preferences.value
        if (!preferences.isOn) return
        save(preferences.withVoiceRead(mode, allowed))
    }

    // region Deletions owed

    private fun deletions(): List<PendingDeletion> = PendingDeletion.decodeAll(settings.getString(PENDING_KEY, "").ifEmpty { null })
    private fun storeDeletions(list: List<PendingDeletion>) = settings.set(PENDING_KEY, if (list.isEmpty()) "" else PendingDeletion.encodeAll(list))
    private fun owed(dashboardID: String) = deletions().firstOrNull { it.dashboardID == dashboardID }

    /** Deletes what this phone left on a host when sharing was turned off offline. Why it couldn't, or null. */
    suspend fun finishDeletionLeftOnHost(): Exception? {
        val active = DeviceSharing.activeDashboardID(context) ?: return null
        val pending = owed(active) ?: return null
        val request = DeviceSharing.request(context, pending.dashboardID, pending.profile) ?: return NotReadyException()
        return try {
            request("POST", "${HealthSyncEngine.BASE}/wipe", emptyMap(), buildJsonObject { put("device_id", pending.deviceID) })
            storeDeletions(deletions() - pending)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SyncSupport.httpCode(e) == 404) {
                storeDeletions(deletions() - pending)
                null
            } else e
        }
    }

    // endregion

    companion object {
        const val CAPABILITY = "health-sync"
        private const val PENDING_KEY = "conduit.healthSync.pendingDeletions.v1"
        @Volatile private var instance: HealthSync? = null

        fun of(context: Context): HealthSync = instance ?: synchronized(this) {
            instance ?: HealthSync(context.applicationContext).also { instance = it }
        }
    }
}
