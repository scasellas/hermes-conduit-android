package app.hermesconduit.android.ui.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.LiveVoiceMode
import app.hermesconduit.android.model.LocationPrecision
import app.hermesconduit.android.model.LocationPrecisionRules
import app.hermesconduit.android.model.LocationRawFix
import app.hermesconduit.android.model.LocationSavedPlace
import app.hermesconduit.android.model.MapsAnswer
import app.hermesconduit.android.model.MapsFailure
import app.hermesconduit.android.model.MapsPlaceRef
import app.hermesconduit.android.model.MapsRequest
import app.hermesconduit.android.model.MapsText
import app.hermesconduit.android.model.MapsVoiceRules
import app.hermesconduit.android.model.VoiceHostTool
import app.hermesconduit.android.model.VoiceReadLog
import app.hermesconduit.android.model.CanonicalJson
import app.hermesconduit.android.model.distanceMeters
import app.hermesconduit.android.model.jsonOf
import app.hermesconduit.android.model.str
import app.hermesconduit.android.notify.ConduitNotifications
import app.hermesconduit.android.notify.ForegroundState
import app.hermesconduit.android.ui.device.DeviceSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.cos

/** Where "me" is for one request, or why it can't be used. */
sealed class MapsMe {
    data class At(val fix: LocationRawFix, val precision: LocationPrecision) : MapsMe()
    data class Unavailable(val failure: MapsFailure) : MapsMe()
}

/**
 * Maps lookups answered on this phone with Android's Geocoder, the MapKit
 * counterpart of iOS MapsEngine: search, lookup and reverse geocoding.
 * Android has no on-device routing, so directions and travel times answer
 * `no_route` and the model asks Hermes or opens Maps (navigate). The "me"
 * rules are the same as on iOS.
 */
class MapsEngine(private val context: Context) {
    private data class Resolved(val latitude: Double, val longitude: Double, val json: JsonObject, val isMe: Boolean)

    private inner class Run(val request: MapsRequest, val me: MapsMe?, val places: List<LocationSavedPlace>) {
        var meFix: LocationRawFix? = null
        var mePrecision = LocationPrecision.PRECISE
        val meAtArea get() = mePrecision != LocationPrecision.PRECISE

        fun checkMe() {
            when (val m = me ?: throw MapsFailure.noLocation()) {
                is MapsMe.Unavailable -> throw m.failure
                is MapsMe.At -> {
                    val precision = m.precision.capped(request.detail)
                    if (precision == LocationPrecision.PLACES) throw MapsFailure.noLocation(MapsFailure.PLACES_ONLY)
                    if (LocationPrecisionRules.containingPrivatePlace(m.fix, places) != null) throw MapsFailure.PRIVATE_PLACE
                    meFix = m.fix
                    mePrecision = precision
                }
            }
        }

        suspend fun meJSON(): JsonObject {
            val fix = meFix ?: return buildJsonObject { put("name", "Current location") }
            val address = reverse(fix.latitude, fix.longitude)
            return if (meAtArea) {
                buildJsonObject { put("name", address?.let(::areaName) ?: "Current area") }
            } else {
                buildJsonObject {
                    put("name", "Current location")
                    address?.let(::addressText)?.let { put("address", it) }
                }
            }
        }

        fun hint(): Pair<Double, Double>? = meFix?.let { it.latitude to it.longitude }

        suspend fun resolve(ref: MapsPlaceRef, near: Pair<Double, Double>?): Resolved = when (ref) {
            MapsPlaceRef.Me -> {
                val fix = meFix ?: throw MapsFailure.noLocation()
                Resolved(fix.latitude, fix.longitude, meJSON(), true)
            }
            is MapsPlaceRef.Saved -> {
                val place = places.firstOrNull { it.id == ref.id } ?: throw MapsFailure.notFound("There's no saved place with that id on this phone.")
                if (place.isPrivate) throw MapsFailure.PRIVATE_PLACE
                val name = MapsText.clean(place.name, LocationSavedPlace.MAX_NAME_LENGTH)
                Resolved(place.latitude, place.longitude, buildJsonObject { put("name", name.ifEmpty { "Saved place" }) }, false)
            }
            is MapsPlaceRef.Text -> {
                val found = search(ref.text, near, RESOLVE_SPAN).firstOrNull() ?: throw MapsFailure.notFound("Nothing matched ${ref.text}.")
                Resolved(found.latitude, found.longitude, placeJSON(found, null), false)
            }
            is MapsPlaceRef.PlaceID -> throw MapsFailure.notFound("Place ids aren't available on this phone; look it up by name instead.")
            is MapsPlaceRef.Coordinate -> Resolved(ref.latitude, ref.longitude, buildJsonObject {
                put("name", "%.5f, %.5f".format(Locale.US, ref.latitude, ref.longitude))
                put("lat", rounded(ref.latitude)); put("lon", rounded(ref.longitude))
            }, false)
        }

        fun placeJSON(address: Address, origin: Resolved?): JsonObject = buildJsonObject {
            put("name", MapsText.cleanOrNil(address.featureName, 120) ?: MapsText.cleanOrNil(address.getAddressLine(0), 120) ?: "Place")
            put("lat", rounded(address.latitude))
            put("lon", rounded(address.longitude))
            addressText(address)?.let { put("address", it) }
            MapsText.cleanOrNil(address.phone, 40)?.let { put("phone", it) }
            address.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { put("url", it.take(300)) }
            if (origin != null) {
                val meters = distanceMeters(origin.latitude, origin.longitude, address.latitude, address.longitude)
                put("distance_m", if (origin.isMe) LocationPrecisionRules.roundedDistance(meters, if (meAtArea) 100.0 else 10.0) else Math.round(meters).toInt())
            }
        }

        suspend fun result(): JsonObject {
            if (request.usesMe) checkMe()
            return when (request.action) {
                MapsRequest.SEARCH, MapsRequest.LOOKUP -> {
                    val centre = request.near?.let { resolve(it, hint()) }
                    val span = if (request.action == MapsRequest.SEARCH) SEARCH_SPAN else RESOLVE_SPAN
                    val found = search(request.query.orEmpty(), centre?.let { it.latitude to it.longitude } ?: hint(), span)
                    if (found.isEmpty()) throw MapsFailure.notFound()
                    val limit = if (request.action == MapsRequest.SEARCH) request.limit else MapsRequest.MAX_LOOKUP_RESULTS
                    buildJsonObject { put("places", JsonArray(found.take(limit).map { placeJSON(it, centre) })) }
                }
                MapsRequest.REVERSE_GEOCODE -> {
                    val near = request.near ?: throw MapsFailure.failed("reverse_geocode needs near.")
                    val place = when (near) {
                        MapsPlaceRef.Me -> meJSON()
                        is MapsPlaceRef.Saved -> resolve(near, null).json
                        is MapsPlaceRef.Coordinate -> {
                            val address = reverse(near.latitude, near.longitude) ?: throw MapsFailure.notFound()
                            buildJsonObject {
                                put("name", MapsText.cleanOrNil(address.featureName, 120) ?: "%.5f, %.5f".format(Locale.US, near.latitude, near.longitude))
                                put("lat", rounded(near.latitude)); put("lon", rounded(near.longitude))
                                addressText(address)?.let { put("address", it) }
                            }
                        }
                        else -> resolve(near, hint()).json
                    }
                    buildJsonObject { put("places", JsonArray(listOf(place))) }
                }
                else -> throw MapsFailure.noRoute("This phone can't work out routes or travel times itself. Ask Hermes, or open Maps with navigate.")
            }
        }
    }

    suspend fun answer(request: MapsRequest, me: MapsMe?, places: List<LocationSavedPlace>, deadlineMs: Long): MapsAnswer =
        withTimeoutOrNull(deadlineMs) {
            try {
                MapsAnswer.Ok(Run(request, me, places).result())
            } catch (e: MapsFailure) {
                MapsAnswer.Failed(e)
            } catch (e: Exception) {
                MapsAnswer.Failed(MapsFailure.failed("Maps couldn't answer just now."))
            }
        } ?: MapsAnswer.Failed(MapsFailure.TIMEOUT)

    /** The place `ref` names, to open Maps there. Never "me": directions start there. */
    suspend fun destination(ref: MapsPlaceRef, places: List<LocationSavedPlace>, near: Pair<Double, Double>?): Result<Triple<Double, Double, String>> {
        if (ref == MapsPlaceRef.Me) return Result.failure(MapsFailure.failed("Directions start from where the user is; give where to go."))
        val run = Run(MapsRequest("navigate", MapsRequest.LOOKUP), null, places)
        return withTimeoutOrNull(NAVIGATE_DEADLINE_MS) {
            runCatching { run.resolve(ref, near).let { Triple(it.latitude, it.longitude, it.json["name"].str ?: "Place") } }
        } ?: Result.failure(MapsFailure.TIMEOUT)
    }

    private suspend fun search(query: String, centre: Pair<Double, Double>?, span: Double): List<Address> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) throw MapsFailure.failed("This phone has no geocoder for maps lookups.")
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val found = if (centre != null) {
            val dLat = span / 2 / 111_320.0
            val dLon = span / 2 / (111_320.0 * cos(Math.toRadians(centre.first)).coerceAtLeast(0.01))
            runCatching {
                geocoder.getFromLocationName(query, MapsRequest.MAX_SEARCH_RESULTS, centre.first - dLat, centre.second - dLon, centre.first + dLat, centre.second + dLon)
            }.getOrNull().orEmpty().ifEmpty { runCatching { geocoder.getFromLocationName(query, MapsRequest.MAX_SEARCH_RESULTS) }.getOrNull().orEmpty() }
        } else {
            runCatching { geocoder.getFromLocationName(query, MapsRequest.MAX_SEARCH_RESULTS) }.getOrNull().orEmpty()
        }
        found.filter { it.hasLatitude() && it.hasLongitude() }.let { list ->
            centre?.let { c -> list.sortedBy { distanceMeters(c.first, c.second, it.latitude, it.longitude) } } ?: list
        }
    }

    private suspend fun reverse(latitude: Double, longitude: Double): Address? = withTimeoutOrNull(AREA_NAME_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            @Suppress("DEPRECATION")
            runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
        }
    }

    companion object {
        const val SEARCH_SPAN = 10_000.0
        const val RESOLVE_SPAN = 50_000.0
        const val AREA_NAME_TIMEOUT_MS = 1_500L
        const val NAVIGATE_DEADLINE_MS = 7_000L

        fun rounded(value: Double) = if (value.isFinite()) LocationPrecisionRules.rounded(value, 5) else 0.0

        fun addressText(address: Address): String? {
            val street = listOfNotNull(MapsText.cleanOrNil(address.subThoroughfare, 100), MapsText.cleanOrNil(address.thoroughfare, 100)).joinToString(" ")
            val town = listOfNotNull(MapsText.cleanOrNil(address.locality, 60), MapsText.cleanOrNil(address.postalCode, 60)).joinToString(" ")
            val parts = listOf(street, town, MapsText.cleanOrNil(address.countryName, 60).orEmpty()).filter { it.isNotEmpty() }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.take(300) ?: MapsText.cleanOrNil(address.getAddressLine(0), 300)
        }

        fun areaName(address: Address): String? {
            val parts = mutableListOf<String>()
            for (part in listOf(address.subLocality, address.locality, address.countryName)) {
                val clean = MapsText.cleanOrNil(part, 80) ?: continue
                if (clean !in parts) parts += clean
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
}

/** Opens a maps app with directions (iOS MapsNavigator): at once on screen, behind a notification otherwise. */
object MapsNavigator {
    sealed class Outcome {
        data class Opened(val name: String) : Outcome()
        data class Notified(val name: String) : Outcome()
        data class Failed(val failure: MapsFailure) : Outcome()
    }

    fun intent(latitude: Double, longitude: Double, mode: String): Intent {
        val flag = when (mode) { "walking" -> "w"; "cycling" -> "b"; "transit" -> "r"; else -> "d" }
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=%.6f,%.6f&mode=%s".format(Locale.US, latitude, longitude, flag)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun fallback(latitude: Double, longitude: Double, name: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:%.6f,%.6f?q=%.6f,%.6f(%s)".format(Locale.US, latitude, longitude, latitude, longitude, Uri.encode(name))))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context, latitude: Double, longitude: Double, name: String, mode: String): Outcome {
        val primary = intent(latitude, longitude, mode)
        val target = if (primary.resolveActivity(context.packageManager) != null) primary else fallback(latitude, longitude, name)
        if (ForegroundState.visible) {
            return runCatching { context.startActivity(target); Outcome.Opened(name) }
                .getOrElse { Outcome.Failed(MapsFailure.failed("Maps couldn't be opened.")) }
        }
        if (!ConduitNotifications.canPost(context)) return Outcome.Failed(MapsFailure.failed("Conduit isn't in front and can't post notifications, so Maps couldn't be opened."))
        ConduitNotifications.ensureChannels(context)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = ConduitNotifications.builder(context, ConduitNotifications.CHANNEL_ATTENTION)
            .setContentTitle(L10n.tr("Directions to %s", name))
            .setContentText(L10n.tr("Tap to open Maps."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        ConduitNotifications.post(context, NOTIFICATION_ID, notification)
        return Outcome.Notified(name)
    }

    private const val NOTIFICATION_ID = 0x4d415053
}

/**
 * The maps tool a live voice call uses (iOS MapsVoiceTool), answered on this
 * phone. "me" and saved places need the mode's location consent at each call.
 */
object MapsVoiceTool {
    const val NAME = "maps"
    const val KEY = "maps"
    const val LOOKUP_DEADLINE_MS = 7_000L
    const val ANSWER_NOTE = "From maps on the user's phone. Answer by ear: names, rough distances and times, rounded. Never read out coordinates or ids."

    val tool = VoiceHostTool(
        NAME,
        "Maps on the user's phone, answered at once. search: places matching a query or category near " +
            "somewhere (default near the user). lookup: one named place or address, best match first. reverse_geocode: " +
            "what is at a place (default the user's position). directions and travel_times aren't available on this phone: ask Hermes. " +
            "navigate: open the maps app with directions to one place from where the user is, when they ask to go there " +
            "or to open it in Maps (behind a notification when Conduit isn't on screen). " +
            "Places are \"me\" (where the user is), one of their saved places by name, an address or a place's name, " +
            "or \"lat,lon\".",
        jsonOf(mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("search", "lookup", "reverse_geocode", "directions", "travel_times", "navigate")),
                "query" to mapOf("type" to "string", "description" to "search: what to find, like pharmacy or EV charger. lookup: the place or address."),
                "near" to mapOf("type" to "string", "description" to "search, lookup, reverse_geocode: where. search and reverse_geocode default to me."),
                "from" to mapOf("type" to "string", "description" to "directions, travel_times: the start; default me."),
                "to" to mapOf("type" to "string", "list" to true, "description" to "navigate: the destination."),
                "mode" to mapOf("type" to "string", "enum" to listOf("driving", "walking", "transit", "cycling"), "description" to "navigate: default driving."),
                "limit" to mapOf("type" to "integer", "description" to "search: how many places, 1 to 25; default 10."),
            ),
            "required" to listOf("action"),
        )) as JsonObject,
        KEY,
    )

    /** One call of the tool on `mode`. `sharedHere`: the call is on the dashboard and profile the location is shared with. */
    suspend fun run(context: Context, arguments: JsonObject, mode: LiveVoiceMode, sharing: LocationSharing, sharedHere: Boolean): Map<String, String> {
        val locationAllowed = sharedHere && sharing.letsVoiceRead(mode) && sharing.settings.talking
        val places = if (locationAllowed) sharing.places else emptyList()
        val engine = MapsEngine(context)
        val action = arguments["action"].str?.lowercase()
        if (action == "navigate") {
            val targets = MapsVoiceRules.destinations(arguments["to"])
            val place = targets.singleOrNull()?.let { MapsVoiceRules.ref(it, places) } ?: return mapOf("error" to "navigate needs one place in to.")
            if (place == MapsPlaceRef.Me) return mapOf("error" to "Directions start from where the user is; give where to go.")
            if (!locationAllowed && MapsVoiceRules.ref(targets[0], sharing.places) is MapsPlaceRef.Saved) return mapOf("error" to MapsVoiceRules.NOT_SHARED)
            val travel = arguments["mode"].str?.lowercase() ?: "driving"
            if (travel !in MapsRequest.MODES) return mapOf("error" to "mode is driving, walking, transit or cycling.")
            val hint = if (locationAllowed && sharing.settings.talkingPrecision != LocationPrecision.PLACES) {
                sharing.fixes?.currentFix(LocationPrecision.AREA, 300, 2_000)?.let { it.latitude to it.longitude }
            } else null
            val destination = engine.destination(place, places, hint).getOrElse { e ->
                val failure = e as? MapsFailure ?: MapsFailure.failed()
                return mapOf("error" to "${failure.code}: ${failure.note}")
            }
            DeviceSharing.recordVoiceRead(context, VoiceReadLog.LOCATION_KEY, mode, "maps navigate")
            return when (val outcome = MapsNavigator.open(context, destination.first, destination.second, destination.third, travel)) {
                is MapsNavigator.Outcome.Opened -> mapOf("result" to "Maps is open on the phone with directions to ${outcome.name}; tell the user to tap Start there. The call keeps going.")
                is MapsNavigator.Outcome.Notified -> mapOf("result" to "Conduit isn't on screen, so a notification is waiting: tapping it opens Maps with directions to ${outcome.name}. Tell the user.")
                is MapsNavigator.Outcome.Failed -> mapOf("error" to "${outcome.failure.code}: ${outcome.failure.note}")
            }
        }
        if (action == null || action !in MapsRequest.ACTIONS) return mapOf("error" to "action is search, lookup, reverse_geocode, directions, travel_times or navigate.")
        val request = MapsVoiceRules.makeRequest(action, arguments, places, locationAllowed).getOrElse { e ->
            return mapOf("error" to ((e as? MapsFailure)?.note ?: "The lookup failed."))
        }
        if (!locationAllowed && MapsVoiceRules.usesLocation(request)) return mapOf("error" to MapsVoiceRules.NOT_SHARED)
        var me: MapsMe? = null
        if (request.usesMe) {
            var precision = sharing.settings.talkingPrecision
            if (sharing.approximate) precision = precision.capped(LocationPrecision.AREA)
            me = if (precision == LocationPrecision.PLACES) {
                MapsMe.Unavailable(MapsFailure.noLocation(MapsFailure.PLACES_ONLY))
            } else {
                sharing.fixes?.currentFix(precision, 30, 5_000)?.let { MapsMe.At(it, precision) } ?: MapsMe.Unavailable(MapsFailure.noLocation())
            }
        }
        val answer = engine.answer(request, me, places, LOOKUP_DEADLINE_MS)
        DeviceSharing.recordVoiceRead(context, VoiceReadLog.LOCATION_KEY, mode, "maps $action")
        return when (answer) {
            is MapsAnswer.Failed -> mapOf("error" to "${answer.failure.code}: ${answer.failure.note}")
            is MapsAnswer.Ok -> modelResult(answer.result)
        }
    }

    /** The answer for the model, cut (steps first) to fit a call. */
    fun modelResult(result: JsonObject): Map<String, String> {
        var current = result
        repeat(6) {
            val text = CanonicalJson.encode(current)
            if (text.length <= VoiceHostTool.MAXIMUM_RESULT_CHARACTERS) return mapOf("result" to text, "note" to ANSWER_NOTE)
            current = MapsAnswer.trimmed(current)
        }
        return mapOf("error" to "That answer is too long for a call. Ask for fewer places.")
    }
}
