package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maps lookups answered on this phone (iOS MapsEngine's request and answer
 * side, designs/maps-v1-contract.md). Answers are for a model, not UI copy.
 * The "me" rules: the user's own position never goes into an answer, at Area
 * distances from "me" round to 100 m and routes from "me" have no steps,
 * inside a private place a request using "me" is refused, and a private
 * saved place is never an origin or destination.
 */
data class MapsFailure(val code: String, val note: String) : Exception(note) {
    companion object {
        fun notFound(note: String = "Nothing matched that.") = MapsFailure("not_found", note)
        fun noRoute(note: String = "Maps has no route for that.") = MapsFailure("no_route", note)
        val PRIVATE_PLACE = MapsFailure("private", "That involves a private place.")
        val PAUSED = MapsFailure("paused", "Location sharing is paused or off on the phone.")
        fun noLocation(note: String = "The phone couldn't get its position.") = MapsFailure("no_location", note)
        val TIMEOUT = MapsFailure("timeout", "Maps didn't answer in time.")
        fun failed(note: String = "The lookup failed.") = MapsFailure("failed", note)
        const val PLACES_ONLY = "Location detail is Places only, so nothing can be worked out from where the user is."
    }
}

/** A place in a request: exactly one of these. */
sealed class MapsPlaceRef {
    data object Me : MapsPlaceRef()
    data class Saved(val id: String) : MapsPlaceRef()
    data class Text(val text: String) : MapsPlaceRef()
    data class PlaceID(val id: String) : MapsPlaceRef()
    data class Coordinate(val latitude: Double, val longitude: Double) : MapsPlaceRef()

    companion object {
        const val MAX_TEXT_LENGTH = 200

        fun parse(value: JsonElement?): MapsPlaceRef? {
            val o = value as? JsonObject ?: return null
            if (o["me"].strictBool == true) return Me
            o["saved"].str?.let { return MapsText.clean(it, 40).ifEmpty { null }?.let(::Saved) }
            o["text"].str?.let { return MapsText.clean(it, MAX_TEXT_LENGTH).ifEmpty { null }?.let(::Text) }
            o["place_id"].str?.let { return MapsText.clean(it, MAX_TEXT_LENGTH).ifEmpty { null }?.let(::PlaceID) }
            val lat = o["lat"].double
            val lon = o["lon"].double
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) return Coordinate(lat, lon)
            return null
        }
    }
}

object MapsText {
    /** Control characters become spaces; trimmed and cut to `limit`. */
    fun clean(text: String, limit: Int): String =
        text.map { if (Character.getType(it) == Character.CONTROL.toInt()) ' ' else it }.joinToString("").trim().take(limit)

    fun cleanOrNil(text: String?, limit: Int): String? = text?.let { clean(it, limit).ifEmpty { null } }
}

data class MapsRequest(
    val id: String,
    val action: String,
    val expiresAt: Instant? = null,
    val detail: LocationPrecision = LocationPrecision.PRECISE,
    val query: String? = null,
    val near: MapsPlaceRef? = null,
    val limit: Int = 10,
    val from: MapsPlaceRef? = null,
    val to: List<MapsPlaceRef> = emptyList(),
    val mode: String = "driving",
    val departAt: Instant? = null,
    val arriveBy: Instant? = null,
    val alternatives: Boolean = true,
    val steps: Boolean = true,
) {
    val usesMe: Boolean get() = near == MapsPlaceRef.Me || from == MapsPlaceRef.Me || MapsPlaceRef.Me in to

    /** The request with only what its action uses, or why it can't run. */
    fun checked(): Result<MapsRequest> {
        var request = this
        when (action) {
            SEARCH, LOOKUP -> {
                if (query == null) return Result.failure(MapsFailure.failed("$action needs a query."))
                request = request.copy(limit = if (action == SEARCH) limit.coerceIn(1, MAX_SEARCH_RESULTS) else MAX_LOOKUP_RESULTS, from = null, to = emptyList())
            }
            REVERSE_GEOCODE -> {
                if (near == null) return Result.failure(MapsFailure.failed("reverse_geocode needs near."))
                request = request.copy(query = null, from = null, to = emptyList())
            }
            DIRECTIONS -> {
                if (from == null) return Result.failure(MapsFailure.failed("directions needs from."))
                if (to.size != 1) return Result.failure(MapsFailure.failed("directions needs one place in to."))
                request = request.copy(near = null, query = null)
            }
            TRAVEL_TIMES -> {
                if (from == null) return Result.failure(MapsFailure.failed("travel_times needs from."))
                if (to.size !in 1..MAX_DESTINATIONS) return Result.failure(MapsFailure.failed("travel_times needs 1 to $MAX_DESTINATIONS places in to."))
                request = request.copy(near = null, query = null)
            }
            else -> return Result.failure(MapsFailure.failed("action is search, lookup, reverse_geocode, directions or travel_times."))
        }
        if (request.departAt != null && request.arriveBy != null) request = request.copy(departAt = null)
        return Result.success(request)
    }

    companion object {
        const val SEARCH = "search"
        const val LOOKUP = "lookup"
        const val REVERSE_GEOCODE = "reverse_geocode"
        const val DIRECTIONS = "directions"
        const val TRAVEL_TIMES = "travel_times"
        val ACTIONS = listOf(SEARCH, LOOKUP, REVERSE_GEOCODE, DIRECTIONS, TRAVEL_TIMES)
        val MODES = listOf("driving", "walking", "transit", "cycling")
        const val MAX_SEARCH_RESULTS = 25
        const val MAX_LOOKUP_RESULTS = 5
        const val MAX_DESTINATIONS = 10
        const val MAX_QUERY_LENGTH = 200

        /** A sealed request body, with the contract's defaults: `near` and `from` are "me" when left out (not for lookup). */
        fun parse(json: JsonObject): Result<MapsRequest> {
            val id = json["id"].str ?: return Result.failure(MapsFailure.failed("The request has no id."))
            val action = json["action"].str?.takeIf { it in ACTIONS }
                ?: return Result.failure(MapsFailure.failed("action is search, lookup, reverse_geocode, directions or travel_times."))
            var request = MapsRequest(id, action)
            request = request.copy(
                expiresAt = json["expires_at"].str?.let(::parseIsoInstant),
                detail = LocationPrecision.of(json["detail"].str) ?: request.detail,
                query = json["query"].str?.let { MapsText.clean(it, MAX_QUERY_LENGTH).ifEmpty { null } },
                limit = json["limit"].long?.toInt() ?: request.limit,
                departAt = json["depart_at"].str?.let(::parseIsoInstant),
                arriveBy = json["arrive_by"].str?.let(::parseIsoInstant),
                alternatives = json["alternatives"].strictBool ?: true,
                steps = json["steps"].strictBool ?: true,
            )
            json["mode"].str?.let { name ->
                val mode = name.lowercase().takeIf { it in MODES } ?: return Result.failure(MapsFailure.failed("mode is driving, walking, transit or cycling."))
                request = request.copy(mode = mode)
            }
            val meByDefault: MapsPlaceRef? = if (action == LOOKUP) null else MapsPlaceRef.Me
            fun ref(key: String): Result<MapsPlaceRef?> {
                val value = json[key]
                if (value == null || value is JsonNull) return Result.success(meByDefault)
                return MapsPlaceRef.parse(value)?.let { Result.success(it) } ?: Result.failure(MapsFailure.failed("$key isn't a place this phone can read."))
            }
            request = request.copy(near = ref("near").getOrElse { return Result.failure(it) }, from = ref("from").getOrElse { return Result.failure(it) })
            val toValue = json["to"]
            if (toValue != null && toValue !is JsonNull) {
                val list = (toValue as? JsonArray)?.toList() ?: listOf(toValue)
                val refs = list.map { MapsPlaceRef.parse(it) ?: return Result.failure(MapsFailure.failed("to isn't a place this phone can read.")) }
                request = request.copy(to = refs)
            }
            return request.checked()
        }
    }
}

/** The answer (contract section 4). */
sealed class MapsAnswer {
    data class Ok(val result: JsonObject) : MapsAnswer()
    data class Failed(val failure: MapsFailure) : MapsAnswer()

    fun json(id: String): JsonObject = when (this) {
        is Ok -> buildJsonObject { put("id", id); put("ok", true); put("result", result) }
        is Failed -> buildJsonObject { put("id", id); put("ok", false); put("error", failure.code); put("note", failure.note) }
    }

    /** The answer's JSON, cut to fit [MAXIMUM_BYTES]: steps go first, then half the places or results at a time. */
    fun plaintext(id: String): ByteArray {
        var answer = this
        repeat(8) {
            val data = CanonicalJson.bytes(answer.json(id))
            if (data.size <= MAXIMUM_BYTES) return data
            val current = answer as? Ok ?: return@repeat
            answer = Ok(trimmed(current.result))
        }
        return CanonicalJson.bytes(Failed(MapsFailure.failed("The answer was too long.")).json(id))
    }

    companion object {
        const val MAXIMUM_BYTES = 64_000

        fun trimmed(result: JsonObject): JsonObject {
            val routes = result["routes"].arr
            if (routes != null && routes.any { it.obj?.containsKey("steps") == true }) {
                return JsonObject(result + ("routes" to JsonArray(routes.map { route -> route.obj?.let { JsonObject(it - "steps") } ?: route })))
            }
            val next = result.toMutableMap()
            for (key in listOf("places", "results", "routes")) {
                val list = result[key].arr ?: continue
                if (list.size > 1) next[key] = JsonArray(list.take(list.size / 2))
            }
            return JsonObject(next)
        }
    }
}

/** How a live voice call's maps tool reads its arguments (iOS MapsVoiceTool's pure parts). */
object MapsVoiceRules {
    const val NOT_SHARED = "The user doesn't share their location with this voice mode on this Hermes profile, so lookups from where they are or " +
        "their saved places aren't available. Lookups between named places and addresses still work. They can allow " +
        "it in Conduit's Settings > Location."

    /** A place as the model names it: "me", a saved place's name, "place_id:…", "lat,lon", or text to look for. */
    fun ref(raw: String, places: List<LocationSavedPlace>): MapsPlaceRef? {
        val text = MapsText.clean(raw, MapsPlaceRef.MAX_TEXT_LENGTH)
        if (text.isEmpty()) return null
        val lowered = text.lowercase()
        if (lowered in setOf("me", "my location", "current location", "here", "where i am")) return MapsPlaceRef.Me
        if (lowered.startsWith("place_id:")) return text.drop("place_id:".length).trim().ifEmpty { null }?.let(MapsPlaceRef::PlaceID)
        places.firstOrNull { it.name.trim().equals(text, ignoreCase = true) }?.let { return MapsPlaceRef.Saved(it.id) }
        val parts = text.split(',').map { it.trim() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) return MapsPlaceRef.Coordinate(lat, lon)
        }
        return MapsPlaceRef.Text(text)
    }

    /** `to` as one place, a JSON list, or a list. */
    fun destinations(value: JsonElement?): List<String> {
        if (value is JsonArray) return value.mapNotNull { it.str }
        val text = value.str?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (text.startsWith("[")) runCatching { ConduitJson.parseToJsonElement(text) as? JsonArray }.getOrNull()?.let { list -> return list.mapNotNull { it.str } }
        return listOf(text)
    }

    fun usesLocation(request: MapsRequest): Boolean =
        (listOfNotNull(request.near, request.from) + request.to).any { it == MapsPlaceRef.Me || it is MapsPlaceRef.Saved }

    fun localDate(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val trimmed = text.trim()
        parseIsoInstant(trimmed)?.let { return it }
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm")) {
            runCatching { LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()?.let { return it.atZone(zone).toInstant() }
        }
        return null
    }

    /** The request the arguments make. `meByDefault`: "near" and "from" left out mean the user's position. */
    fun makeRequest(action: String, arguments: JsonObject, places: List<LocationSavedPlace>, meByDefault: Boolean): Result<MapsRequest> {
        var request = MapsRequest("voice", action, detail = LocationPrecision.PRECISE, query = arguments["query"].str?.let { MapsText.cleanOrNil(it, MapsRequest.MAX_QUERY_LENGTH) })
        val near = arguments["near"].str?.let { ref(it, places) }
        request = when {
            near != null -> request.copy(near = near)
            (action == MapsRequest.SEARCH || action == MapsRequest.REVERSE_GEOCODE) && meByDefault -> request.copy(near = MapsPlaceRef.Me)
            action == MapsRequest.REVERSE_GEOCODE -> return Result.failure(MapsFailure("no_location", NOT_SHARED))
            else -> request
        }
        val from = arguments["from"].str?.let { ref(it, places) }
        request = when {
            from != null -> request.copy(from = from)
            action == MapsRequest.DIRECTIONS || action == MapsRequest.TRAVEL_TIMES -> request.copy(from = MapsPlaceRef.Me)
            else -> request
        }
        request = request.copy(to = destinations(arguments["to"]).mapNotNull { ref(it, places) })
        arguments["mode"].str?.let { name ->
            val mode = name.lowercase().takeIf { it in MapsRequest.MODES } ?: return Result.failure(MapsFailure.failed("mode is driving, walking, transit or cycling."))
            request = request.copy(mode = mode)
        }
        request = request.copy(
            departAt = arguments["depart_at"].str?.let { localDate(it) },
            arriveBy = arguments["arrive_by"].str?.let { localDate(it) },
        )
        arguments["limit"].long?.let { request = request.copy(limit = it.toInt()) }
        return request.checked()
    }
}
