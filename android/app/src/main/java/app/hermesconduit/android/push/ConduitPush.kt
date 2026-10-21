package app.hermesconduit.android.push

import android.content.Context
import android.os.Build
import app.hermesconduit.android.BuildConfig
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.DeviceKeyStore
import app.hermesconduit.android.data.FileTextStore
import app.hermesconduit.android.data.TextStore
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.AndroidPushWire
import app.hermesconduit.android.model.LocationLocateRequest
import app.hermesconduit.android.model.PushE2E
import app.hermesconduit.android.model.PushKeyRecord
import app.hermesconduit.android.model.PushSeenStore
import app.hermesconduit.android.model.str
import app.hermesconduit.android.notify.NotificationPrefs
import app.hermesconduit.android.ui.device.SyncSupport
import app.hermesconduit.android.ui.gateway.requestJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID

/**
 * Push wake-up through UnifiedPush (Settings > Notifications > Push through
 * UnifiedPush): Android's counterpart of the iOS app's APNs registration with
 * the push relay, without a relay. The user picks a distributor (ntfy and the
 * like); the connector hands back an endpoint URL, which goes to the Hermes
 * notifier plugin with the installation id, the notification preferences and
 * a fresh end-to-end key (`POST …/push/android/register`, docs/ANDROID_PUSH.md).
 * The plugin then POSTs sealed pushes to that endpoint, and
 * [ConduitPushHandler] opens them, even while Conduit is closed.
 *
 * A plugin that answers 404 doesn't know the route yet: Settings says so and
 * notifications keep coming through the background connection. One per
 * process.
 */
class ConduitPush internal constructor(
    private val context: Context,
    private val keyStore: TextStore,
    seenStore: TextStore,
) {
    private val hermes = context.hermes
    private val settings = context.appSettings
    private val mutex = Mutex()
    val seen = PushSeenStore(seenStore)

    sealed class State {
        data object Off : State()
        /** Waiting for the distributor's endpoint. */
        data object Waiting : State()
        /** Sending the endpoint to the plugin. */
        data object Registering : State()
        data object Registered : State()
        /** The plugin has no Android push route (404). */
        data object Unsupported : State()
        data class Failed(val message: String) : State()
    }

    data class Distributor(val packageName: String, val label: String)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state

    private fun initialState(): State = when {
        !enabled -> State.Off
        endpoint == null -> State.Waiting
        settings.getString(STATUS_KEY, "") == STATUS_UNSUPPORTED -> State.Unsupported
        records().isNotEmpty() -> State.Registered
        else -> State.Registering
    }

    val enabled: Boolean get() = settings.getBool(ENABLED_KEY, false)

    /** This phone's push installation: one id for every registration, as iOS's relay installation. */
    val installationID: String
        get() = settings.getString(INSTALLATION_KEY, "").ifEmpty {
            UUID.randomUUID().toString().lowercase().also { settings.set(INSTALLATION_KEY, it) }
        }

    val endpoint: AndroidPushWire.Endpoint?
        get() {
            val json = settings.getString(ENDPOINT_KEY, "").ifEmpty { null }
                ?.let { runCatching { app.hermesconduit.android.model.ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            val url = json["url"].str ?: return null
            return AndroidPushWire.Endpoint(url, json["p256dh"].str, json["auth"].str, json["temporary"].str == "true")
        }

    private fun saveEndpoint(endpoint: AndroidPushWire.Endpoint?) {
        settings.set(ENDPOINT_KEY, endpoint?.let {
            buildJsonObject {
                put("url", it.url)
                it.p256dh?.let { key -> put("p256dh", key) }
                it.auth?.let { auth -> put("auth", auth) }
                put("temporary", it.temporary)
            }.toString()
        }.orEmpty())
    }

    fun records(): List<PushKeyRecord> = PushKeyRecord.decodeAll(keyStore.load())

    private fun saveRecords(records: List<PushKeyRecord>) {
        keyStore.save(if (records.isEmpty()) null else PushKeyRecord.encodeAll(records))
    }

    /** Pushes reach this phone for `dashboardID` and `profile`: the plugin holds a key for them and the endpoint. */
    fun wakes(dashboardID: String?, profile: String?): Boolean = enabled && endpoint != null && records().any {
        it.dashboardID == dashboardID && LocationLocateRequest.normalizedProfile(it.profile) == LocationLocateRequest.normalizedProfile(profile)
    }

    // region Distributors

    /** The installed distributors (ntfy, Sunup, NextPush…), with their app names. */
    fun distributors(): List<Distributor> {
        val pm = context.packageManager
        return runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList()).map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() ?: pkg
            Distributor(pkg, label)
        }.sortedBy { it.label.lowercase() }
    }

    /** The distributor in use, if it's still installed. */
    fun distributor(): String? = runCatching { UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context) }.getOrNull()

    // endregion

    // region Turning on and off

    /** Registers with `distributor`; the endpoint comes back to [ConduitPushService.onNewEndpoint]. */
    fun enable(distributor: String) {
        settings.set(ENABLED_KEY, true)
        settings.set(STATUS_KEY, "")
        _state.value = State.Waiting
        runCatching {
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context, INSTANCE, L10n.tr("Notifications from Hermes"))
        }.onFailure { _state.value = State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Unregisters at the plugin (best effort), at the distributor, and forgets the keys. */
    suspend fun disable() {
        val registered = records()
        settings.set(ENABLED_KEY, false)
        runCatching { UnifiedPush.unregister(context, INSTANCE) }
        saveEndpoint(null)
        settings.set(STATUS_KEY, "")
        unregisterAtPlugins(registered)
        mutex.withLock { saveRecords(emptyList()) }
        _state.value = State.Off
    }

    private suspend fun unregisterAtPlugins(records: List<PushKeyRecord>) {
        val dashboard = hermes.connection.value?.id
        // Only the dashboard in use can be reached; another's registration
        // goes the first time the plugin's POST to the endpoint fails.
        for (record in records.filter { it.dashboardID == dashboard }) {
            try {
                hermes.requestJson(
                    "POST", "${SyncSupport.PLUGIN_BASE}/${AndroidPushWire.UNREGISTER}",
                    SyncSupport.profileQuery(record.profile ?: "default"), AndroidPushWire.unregisterBody(record.installationID), TIMEOUT_S,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: the endpoint answers 404/410 from now on and the plugin drops it.
            }
        }
    }

    // endregion

    // region Connector events

    fun newEndpoint(endpoint: AndroidPushWire.Endpoint) {
        if (!enabled) return
        saveEndpoint(endpoint)
        hermes.scope.launch { sync() }
    }

    fun registrationFailed(reason: FailedReason) {
        if (!enabled) return
        _state.value = State.Failed(
            when (reason) {
                FailedReason.NETWORK -> L10n.tr("The distributor couldn't register: no network. Try again when you're online.")
                FailedReason.ACTION_REQUIRED -> L10n.tr("The distributor needs you to open it and finish setting it up.")
                FailedReason.VAPID_REQUIRED -> L10n.tr("This distributor needs a server key Hermes doesn't have. Choose another distributor.")
                FailedReason.INTERNAL_ERROR -> L10n.tr("The distributor couldn't register. Try again.")
            },
        )
    }

    /** The distributor dropped the registration (it was uninstalled, or the user removed it there). */
    fun unregistered() {
        if (!enabled) return
        val registered = records()
        saveEndpoint(null)
        settings.set(ENABLED_KEY, false)
        hermes.scope.launch {
            unregisterAtPlugins(registered)
            mutex.withLock { saveRecords(emptyList()) }
        }
        _state.value = State.Failed(L10n.tr("The distributor removed Conduit's registration. Choose a distributor to turn push on again."))
    }

    // endregion

    // region The plugin

    /**
     * Sends the endpoint to the plugin of the dashboard and profile in use
     * (again on every connect, so preferences, a new endpoint, a profile
     * switch or a plugin update reach it). The key it confirms is kept for
     * opening pushes; a 404 means the plugin can't send Android pushes yet.
     */
    suspend fun sync() = mutex.withLock {
        if (!enabled) return@withLock
        val endpoint = endpoint ?: return@withLock
        if (!hermes.isSignedIn) return@withLock
        val dashboard = hermes.connection.value?.id ?: return@withLock
        val profile = hermes.profile.value
        val installation = installationID
        val records = records()
        val existing = records.firstOrNull { it.installationID == installation && it.dashboardID == dashboard && sameProfile(it.profile, profile) }
        val (kid, secret) = existing?.let { it.kid to it.secret } ?: PushE2E.newSecret()
        val keys = AndroidPushWire.PreferenceKeys(
            NotificationPrefs.APPROVAL, NotificationPrefs.INPUT, NotificationPrefs.RESPONSE, NotificationPrefs.FAILED,
            NotificationPrefs.BACKGROUND_TASK, NotificationPrefs.COMPLETION_SOUND, NotificationPrefs.ATTENTION_SOUND,
            NotificationPrefs.PREVIEWS, NotificationPrefs.DECISION_CARDS,
        )
        val preferences = AndroidPushWire.preferences(settings.getBool(NotificationPrefs.ENABLED, false), { NotificationPrefs.get(settings, it) }, keys)
        val body = AndroidPushWire.registerBody(installation, endpoint, preferences, kid, secret, BuildConfig.VERSION_NAME, deviceName())
        _state.value = State.Registering
        val response = try {
            hermes.requestJson("POST", "${SyncSupport.PLUGIN_BASE}/${AndroidPushWire.REGISTER}", SyncSupport.profileQuery(profile), body, TIMEOUT_S)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SyncSupport.httpCode(e) == 404) {
                settings.set(STATUS_KEY, STATUS_UNSUPPORTED)
                _state.value = State.Unsupported
            } else {
                _state.value = State.Failed(SyncSupport.describe(e))
            }
            return@withLock
        }
        when (val outcome = AndroidPushWire.registerOutcome(response, installation, kid)) {
            is AndroidPushWire.RegisterOutcome.Refused -> _state.value = State.Failed(L10n.tr("The plugin didn't confirm the registration (%s).", outcome.detail))
            is AndroidPushWire.RegisterOutcome.Registered -> {
                // Turned off or moved meanwhile: that registration is gone.
                if (!enabled || installationID != installation) return@withLock
                val record = PushKeyRecord(kid, secret, installation, outcome.gatewayID, dashboard, profile, System.currentTimeMillis())
                saveRecords(records.filterNot { it.dashboardID == dashboard && sameProfile(it.profile, profile) } + record)
                settings.set(STATUS_KEY, STATUS_REGISTERED)
                _state.value = State.Registered
            }
        }
    }

    private fun sameProfile(a: String?, b: String?) = LocationLocateRequest.normalizedProfile(a) == LocationLocateRequest.normalizedProfile(b)

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" }

    // endregion

    /** Sends the registration again on every connect (a new profile, preferences or plugin). */
    fun install() {
        if (installed) return
        installed = true
        hermes.scope.launch { hermes.connected.collect { if (enabled) runCatching { sync() } } }
    }

    private var installed = false

    companion object {
        const val INSTANCE = "default"
        const val ENABLED_KEY = "push.unifiedpush.enabled"
        const val ENDPOINT_KEY = "push.unifiedpush.endpoint"
        const val INSTALLATION_KEY = "push.installationId"
        const val STATUS_KEY = "push.unifiedpush.status"
        const val STATUS_REGISTERED = "registered"
        const val STATUS_UNSUPPORTED = "unsupported"
        const val TIMEOUT_S = 15L

        @Volatile private var instance: ConduitPush? = null

        fun get(context: Context): ConduitPush = instance ?: synchronized(this) {
            instance ?: ConduitPush(
                context.applicationContext,
                DeviceKeyStore.store(context, "push-e2e.v1"),
                FileTextStore.of(context, "Push", "seen.json"),
            ).also { instance = it }
        }

        /** Whether any distributor app is installed, without making the instance. */
        fun hasDistributor(context: Context): Boolean = runCatching { UnifiedPush.getDistributors(context).isNotEmpty() }.getOrDefault(false)
    }
}
