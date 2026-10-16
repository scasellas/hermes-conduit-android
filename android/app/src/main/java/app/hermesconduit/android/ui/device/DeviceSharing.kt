package app.hermesconduit.android.ui.device

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.ui.gateway.requestJson
import app.hermesconduit.android.ui.health.HealthSync
import app.hermesconduit.android.ui.health.HealthSyncReason
import app.hermesconduit.android.ui.location.LocationSharing
import app.hermesconduit.android.ui.phone.PhoneSync
import app.hermesconduit.android.ui.phone.PhoneSyncReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * When health, calendar & phone and location sharing run: on connecting to
 * the host, coming to the foreground, and an hourly WorkManager pass in the
 * background (the iOS background refresh and HealthKit wakes). Each goes only
 * to the dashboard and profile it was turned on for, over that dashboard's
 * own connection.
 */
object DeviceSharing {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val hermes = app.hermes
        hermes.scope.launch {
            hermes.connected.collect {
                HealthSync.of(app).run(HealthSyncReason.CONNECTED)
            }
        }
        hermes.scope.launch { hermes.connected.collect { PhoneSync.of(app).run(PhoneSyncReason.CONNECTED) } }
        hermes.scope.launch { hermes.connected.collect { LocationSharing.of(app).hostConnected() } }
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    hermes.scope.launch {
                        // The connection comes up with the app: give it a moment.
                        delay(1_500)
                        HealthSync.of(app).run(HealthSyncReason.FOREGROUND)
                        PhoneSync.of(app).run(PhoneSyncReason.FOREGROUND)
                        LocationSharing.of(app).appBecameActive()
                    }
                }
            })
        }
        hermes.scope.launch { LocationSharing.of(app).activateAtLaunch() }
        scheduleBackground(app)
    }

    /**
     * Before a message goes out: a location fix when "Share while I'm talking
     * to Hermes" is on (waited on for at most 2.5 s), and a calendar & phone
     * pass in the background.
     */
    suspend fun beforeSending(context: Context) {
        val app = context.applicationContext
        app.hermes.scope.launch { PhoneSync.of(app).run(PhoneSyncReason.MESSAGE_SENT) }
        LocationSharing.of(app).shareBeforeSending()
    }

    /** One hourly pass while any sharing is on; cancelled when none is. */
    fun scheduleBackground(context: Context) {
        val app = context.applicationContext
        val on = HealthSync.of(app).preferences.value.isOn || PhoneSync.of(app).preferences.value.isOn
        val work = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        if (!on) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DeviceSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * The request for a destination: the active dashboard's connection, with
     * the profile scoped, or null when another dashboard (or none) is in use.
     */
    fun request(context: Context, dashboardID: String, profile: String, deadline: Instant? = null): SyncRequest? {
        val hermes = context.hermes
        if (!hermes.isSignedIn || hermes.connection.value?.id != dashboardID) return null
        return { method, path, query, body ->
            if (hermes.connection.value?.id != dashboardID) throw NotReadyException()
            val seconds = deadline?.let { ((it.toEpochMilli() - System.currentTimeMillis()) / 1000).coerceIn(1, 30) } ?: 30
            hermes.requestJson(method, path, query + SyncSupport.profileQuery(profile), body, seconds)
        }
    }

    fun activeDashboardID(context: Context): String? = context.hermes.connection.value?.id
    fun activeProfile(context: Context): String = context.hermes.profile.value

    /** The active dashboard's host, for display ("hermes.local"). */
    fun activeHost(context: Context): String {
        val base = context.hermes.connection.value?.baseUrl.orEmpty()
        return runCatching { android.net.Uri.parse(base).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: base
    }

    // region Voice read logs

    fun voiceReads(context: Context, key: String): VoiceReadLog =
        VoiceReadLog.decode(context.appSettings.getString(key, "").ifEmpty { null }, System.currentTimeMillis())

    fun recordVoiceRead(context: Context, key: String, mode: LiveVoiceMode, action: String) {
        val log = voiceReads(context, key).recording(mode, action, System.currentTimeMillis())
        context.appSettings.set(key, log.encode())
    }

    fun clearVoiceReads(context: Context, key: String) = context.appSettings.set(key, "")

    // endregion

    private const val WORK_NAME = "conduit.device-sync"

    internal fun settings(context: Context): AppSettings = context.appSettings
}

/** The background pass: health and calendar & phone, then anything location left unsent. */
class DeviceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        // A background start connects on its own; give it a moment.
        repeat(10) {
            if (app.hermes.isSignedIn) return@repeat
            delay(1_000)
        }
        val health = HealthSync.of(app).run(HealthSyncReason.BACKGROUND_REFRESH)
        val phone = PhoneSync.of(app).run(PhoneSyncReason.BACKGROUND_REFRESH)
        LocationSharing.of(app).hostConnected()
        return if (health is SyncOutcome.Failed || phone is SyncOutcome.Failed) Result.retry() else Result.success()
    }
}
