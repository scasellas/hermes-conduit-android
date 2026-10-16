package app.hermesconduit.android.ui.phone

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.data.DeviceKeyStore
import app.hermesconduit.android.data.FileTextStore
import app.hermesconduit.android.data.SettingsTextStore
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.PendingDeletion
import app.hermesconduit.android.model.PhoneChangePrompt
import app.hermesconduit.android.model.PhonePerson
import app.hermesconduit.android.model.PhoneSyncPreferences
import app.hermesconduit.android.model.SyncDestination
import app.hermesconduit.android.model.SyncPause
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.ui.device.DeviceSharing
import app.hermesconduit.android.ui.device.NotReadyException
import app.hermesconduit.android.ui.device.SyncOutcome
import app.hermesconduit.android.ui.device.SyncSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

enum class PhoneSyncReason { CONNECTED, FOREGROUND, MESSAGE_SENT, CALENDAR_CHANGED, BACKGROUND_REFRESH, ANSWERED, MANUAL, TURNED_ON, VOICE_CALL }

/**
 * When the calendar and phone status sync, and with which connection (iOS
 * AppState+PhoneSync). A pass rides every moment Conduit runs anyway:
 * connecting to the host, coming to the foreground, the calendar changing
 * while Conduit runs, and the hourly background pass. Android has no push
 * from the host for Hermes' changes, so they arrive on those passes.
 */
class PhoneSync private constructor(private val context: Context) {
    private val settings = context.appSettings
    val engine = PhoneSyncEngine(
        calendarSource = CalendarContractSource(context),
        statusSource = AndroidPhoneStatusSource(context),
        alarmSource = AlarmClockSource(context),
        store = FileTextStore.of(context, "PhoneSync"),
        keys = PhoneKeyStore(DeviceKeyStore.store(context, "phone-key.v1")),
        preferencesStore = SettingsTextStore(settings, PhoneSyncPreferences.STORAGE_KEY),
        peopleStore = SettingsTextStore(settings, PhonePerson.STORAGE_KEY),
        deviceName = { "${Build.MANUFACTURER} ${Build.MODEL}".trim() },
    )
    val preferences: StateFlow<PhoneSyncPreferences> get() = engine.preferences
    private var observer: ContentObserver? = null
    private var calendarChange: Job? = null

    /** Runs a pass when sharing is on and its dashboard is the one in use. */
    suspend fun run(reason: PhoneSyncReason): SyncOutcome {
        finishDeletionLeftOnHost()
        val started = Instant.now()
        val preferences = engine.preferences.value
        val destination = preferences.destination
        if (!preferences.isOn || destination == null) return SyncOutcome.Off
        val active = DeviceSharing.activeDashboardID(context)
        if (engine.state.value.pause == SyncPause.OTHER_DASHBOARD && (active == null || active == destination.dashboardID)) engine.note(null)
        val last = engine.state.value.lastAttempt
        val frequent = reason == PhoneSyncReason.FOREGROUND || reason == PhoneSyncReason.MESSAGE_SENT ||
            reason == PhoneSyncReason.CALENDAR_CHANGED || reason == PhoneSyncReason.VOICE_CALL
        if (frequent && last != null && System.currentTimeMillis() - last < 30_000) return SyncOutcome.Unfinished
        active ?: return SyncOutcome.Unfinished
        if (active != destination.dashboardID) {
            engine.note(SyncPause.OTHER_DASHBOARD)
            return SyncOutcome.Paused(SyncPause.OTHER_DASHBOARD)
        }
        val deadline = started.plusSeconds(if (ForegroundState.visible) 60 else 120)
        val request = DeviceSharing.request(context, destination.dashboardID, destination.profile, deadline) ?: return SyncOutcome.Unfinished
        if (engine.preferences.value.destination != destination) return SyncOutcome.Off
        return engine.sync(deadline, request)
    }

    /** Turns sharing on for the dashboard and profile in use, with these choices (permissions asked by the page). */
    suspend fun turnOn(requested: PhoneSyncPreferences) {
        val dashboardID = DeviceSharing.activeDashboardID(context) ?: throw NotReadyException()
        finishDeletionLeftOnHost()?.let { error -> if (owed(dashboardID) != null) throw error }
        var choices = requested
        if (choices.alarms && !engine.alarmSource.authorized) choices = choices.withAlarms(false)
        engine.savePreferences(
            choices.copy(
                voiceReaders = null,
                destination = SyncDestination(dashboardID, DeviceSharing.activeProfile(context), DeviceSharing.activeHost(context)),
            ),
        )
        startObservers()
        DeviceSharing.scheduleBackground(context)
        context.hermes.scope.launch { run(PhoneSyncReason.TURNED_ON) }
    }

    /** Changes what's shared while sharing is on. */
    suspend fun update(choices: PhoneSyncPreferences) {
        val current = engine.preferences.value
        val destination = current.destination ?: return
        val next = choices.copy(destination = destination, voiceReaders = current.voiceReaders)
        if (!next.sharesAnything) {
            // Nothing left shared is the narrowest sharing: what the host holds goes too.
            turnOff(deleteFromHost = true)
            return
        }
        engine.savePreferences(next)
        context.hermes.scope.launch { run(PhoneSyncReason.MANUAL) }
    }

    fun setVoiceUse(mode: LiveVoiceMode, allowed: Boolean) {
        val preferences = engine.preferences.value
        if (!preferences.isOn) return
        engine.savePreferences(preferences.withVoiceUse(mode, allowed))
    }

    suspend fun turnOff(deleteFromHost: Boolean): Boolean {
        val destination = engine.preferences.value.destination
        val deviceID = engine.state.value.deviceID
        val request = if (deleteFromHost && destination != null) DeviceSharing.request(context, destination.dashboardID, destination.profile) else null
        engine.savePreferences(PhoneSyncPreferences())
        DeviceSharing.clearVoiceReads(context, VoiceReadLog.PHONE_KEY)
        val deleted = engine.turnOff(request)
        DeviceSharing.scheduleBackground(context)
        if (deleteFromHost && !deleted && destination != null) {
            storeDeletions(deletions().filter { it.dashboardID != destination.dashboardID } + PendingDeletion(destination.dashboardID, destination.profile, deviceID))
        }
        return deleted || !deleteFromHost
    }

    /** The user's answer to a change card; reported to the host right away. */
    fun answer(prompt: PhoneChangePrompt, approve: Boolean) {
        context.hermes.scope.launch {
            engine.answer(prompt, approve)
            run(PhoneSyncReason.ANSWERED)
        }
    }

    /** The calendar changing while Conduit runs. */
    fun startObservers() {
        if (observer != null || !engine.preferences.value.isOn) return
        val handler = Handler(Looper.getMainLooper())
        val created = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                calendarChange?.cancel()
                calendarChange = context.hermes.scope.launch {
                    // The provider reports a burst of changes at once.
                    delay(5_000)
                    run(PhoneSyncReason.CALENDAR_CHANGED)
                }
            }
        }
        runCatching { context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, created) }
            .onSuccess { observer = created }
    }

    // region Deletions owed

    private fun deletions(): List<PendingDeletion> = PendingDeletion.decodeAll(settings.getString(PENDING_KEY, "").ifEmpty { null })
    private fun storeDeletions(list: List<PendingDeletion>) = settings.set(PENDING_KEY, if (list.isEmpty()) "" else PendingDeletion.encodeAll(list))
    private fun owed(dashboardID: String) = deletions().firstOrNull { it.dashboardID == dashboardID }

    suspend fun finishDeletionLeftOnHost(): Exception? {
        val active = DeviceSharing.activeDashboardID(context) ?: return null
        val pending = owed(active) ?: return null
        val request = DeviceSharing.request(context, pending.dashboardID, pending.profile) ?: return NotReadyException()
        return try {
            request("POST", "${PhoneSyncEngine.BASE}/wipe", emptyMap(), buildJsonObject { put("device_id", pending.deviceID) })
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
        const val CAPABILITY = "phone-sync"
        const val ALARMS_CAPABILITY = "phone-alarms"
        const val CONTACTS_CAPABILITY = "phone-contacts"
        private const val PENDING_KEY = "conduit.phoneSync.pendingDeletions.v1"
        @Volatile private var instance: PhoneSync? = null

        fun of(context: Context): PhoneSync = instance ?: synchronized(this) {
            instance ?: PhoneSync(context.applicationContext).also {
                instance = it
                it.startObservers()
            }
        }
    }
}
