package app.hermesconduit.android.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.LocationArea
import app.hermesconduit.android.model.LocationPrecision
import app.hermesconduit.android.model.LocationRawFix
import app.hermesconduit.android.model.LocationSavedPlace
import app.hermesconduit.android.model.LocationSharingSettings
import app.hermesconduit.android.model.SealCrypto
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task -> if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null) }
}

fun Location.rawFix(): LocationRawFix = LocationRawFix(
    latitude = latitude,
    longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy.toDouble().coerceAtLeast(0.0) else 0.0,
    timestampMs = time,
    speed = if (hasSpeed() && speed >= 0) speed.toDouble() else null,
    course = if (hasBearing()) bearing.toDouble() else null,
)

/**
 * One position at a time, from the fused location provider (iOS
 * LocationFixProvider), and the area a position is in from the on-phone
 * geocoder.
 */
class LocationFixProvider(private val context: Context) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var cachedArea: Pair<LocationRawFix, LocationArea>? = null

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val canLocate: Boolean get() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    /** Only approximate location (iOS Precise Location off). */
    val isApproximate: Boolean get() = canLocate && !granted(Manifest.permission.ACCESS_FINE_LOCATION)
    /** "Allow all the time" (iOS Always). */
    val hasBackground: Boolean
        get() = canLocate && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    @SuppressLint("MissingPermission")
    suspend fun currentFix(precision: LocationPrecision, maxAgeSeconds: Long = 60, timeoutMs: Long = 10_000): LocationRawFix? {
        if (!canLocate) return null
        val wanted = desiredAccuracy(precision)
        runCatching { client.lastLocation.awaitOrNull() }.getOrNull()?.let { known ->
            val age = System.currentTimeMillis() - known.time
            if (age <= maxAgeSeconds * 1000 && known.hasAccuracy() && known.accuracy <= acceptableCachedAccuracy(wanted)) return known.rawFix()
        }
        val priority = if (precision == LocationPrecision.PRECISE && !isApproximate) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs) { client.getCurrentLocation(priority, cancel.token).awaitOrNull()?.rawFix() }
        } catch (_: SecurityException) {
            null
        } finally {
            cancel.cancel()
        }
    }

    /** Neighbourhood, city and country, or null when the geocoder doesn't answer in time. */
    suspend fun area(fix: LocationRawFix, timeoutMs: Long = 1_500): LocationArea? {
        cachedArea?.let { (at, area) ->
            if (app.hermesconduit.android.model.distanceMeters(at.latitude, at.longitude, fix.latitude, fix.longitude) < 300 &&
                kotlin.math.abs(at.timestampMs - fix.timestampMs) < 1_800_000) return area
        }
        if (!Geocoder.isPresent()) return null
        val found = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        } ?: return null
        val area = LocationArea(
            name = found.subLocality ?: found.locality,
            locality = found.locality,
            region = found.adminArea,
            country = found.countryName,
            countryCode = found.countryCode,
        )
        if (!area.isEmpty) cachedArea = fix to area
        return area
    }

    companion object {
        fun desiredAccuracy(precision: LocationPrecision): Double = if (precision == LocationPrecision.PRECISE) 10.0 else 100.0

        /** A cached fix good enough for the asked accuracy: Precise only when close to it. */
        fun acceptableCachedAccuracy(accuracy: Double): Double = minOf(maxOf(accuracy, 65.0), 2 * accuracy)
    }
}

/**
 * The background signals (iOS LocationBackgroundMonitor): geofences for the
 * saved places' arrive and leave, and longer moves through low-power
 * updates with a 500 m minimum. Android has no visits API, so visits aren't
 * reported. Both need "Allow all the time".
 */
class LocationBackgroundMonitor(private val context: Context) {
    private val geofencing by lazy { LocationServices.getGeofencingClient(context) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var callCallback: LocationCallback? = null

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(context, LocationEventsReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun configure(settings: LocationSharingSettings, places: List<LocationSavedPlace>, bound: Boolean, provider: LocationFixProvider) {
        val on = bound && settings.background && provider.hasBackground
        val geofences = pendingIntent(ACTION_GEOFENCE)
        val moves = pendingIntent(ACTION_MOVE)
        runCatching { geofencing.removeGeofences(geofences) }
        runCatching { fused.removeLocationUpdates(moves) }
        if (!on) return
        // Approximate-only positions can't tell saved places apart.
        if (!provider.isApproximate && places.isNotEmpty()) {
            val fences = places.take(LocationSavedPlace.MAX_COUNT).map { place ->
                Geofence.Builder()
                    .setRequestId(place.id)
                    .setCircularRegion(place.latitude, place.longitude, place.radius.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofences(fences)
                .build()
            runCatching { geofencing.addGeofences(request, geofences) }
        }
        if (settings.backgroundPrecision != LocationPrecision.PLACES) {
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15 * 60_000L)
                .setMinUpdateDistanceMeters(SIGNIFICANT_DISTANCE)
                .setMinUpdateIntervalMillis(5 * 60_000L)
                .build()
            runCatching { fused.requestLocationUpdates(request, moves) }
        }
    }

    /** A voice call running: a position each 200 m while Conduit runs. */
    @SuppressLint("MissingPermission")
    fun startCallUpdates(onMove: (LocationRawFix) -> Unit) {
        if (callCallback != null) return
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onMove(it.rawFix()) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L).setMinUpdateDistanceMeters(CALL_MOVE_DISTANCE.toFloat()).build()
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }.onSuccess { callCallback = callback }
    }

    fun stopCallUpdates() {
        callCallback?.let { runCatching { fused.removeLocationUpdates(it) } }
        callCallback = null
    }

    companion object {
        const val ACTION_GEOFENCE = "app.hermesconduit.android.location.GEOFENCE"
        const val ACTION_MOVE = "app.hermesconduit.android.location.MOVE"
        const val CALL_MOVE_DISTANCE = 200.0
        const val SIGNIFICANT_DISTANCE = 500f

        /** What the monitored places are, without their coordinates. */
        fun monitoredDigest(places: List<LocationSavedPlace>): String =
            SealCrypto.hex(SealCrypto.sha256(places.take(LocationSavedPlace.MAX_COUNT).joinToString(";") { "${it.id}@${it.latitude},${it.longitude},${it.radius}" }.toByteArray()))
    }
}

/** Geofence transitions, longer moves, and setting the signals up again after a reboot or an update. */
class LocationEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        app.hermes.scope.launch {
            try {
                val sharing = LocationSharing.of(app)
                when (intent.action) {
                    LocationBackgroundMonitor.ACTION_GEOFENCE -> {
                        val event = GeofencingEvent.fromIntent(intent)
                        if (event != null && !event.hasError()) {
                            val inside = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
                            val at = event.triggeringLocation?.time ?: System.currentTimeMillis()
                            event.triggeringGeofences.orEmpty().forEach { sharing.placeStateChanged(it.requestId, inside, at) }
                        }
                    }
                    LocationBackgroundMonitor.ACTION_MOVE -> LocationResult.extractResult(intent)?.lastLocation?.let { sharing.movedSignificantly(it.rawFix()) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> sharing.activateAtLaunch()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
