package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Location for Hermes (iOS Shared/Location, designs/location-v1-contract.md).
 * Two opt-in layers: "while I'm talking to Hermes" and "in the background",
 * each with its own precision. Everything goes straight from this phone to
 * one Hermes profile on one dashboard, sealed end to end.
 */
enum class LocationPrecision(val raw: String, val rank: Int) {
    PLACES("places", 0), AREA("area", 1), PRECISE("precise", 2);

    fun capped(other: LocationPrecision): LocationPrecision = if (rank <= other.rank) this else other

    companion object {
        fun of(raw: String?): LocationPrecision? = entries.firstOrNull { it.raw == raw }
    }
}

enum class LocationPlaceKind(val raw: String) {
    HOME("home"), WORK("work"), GYM("gym"), SCHOOL("school"), OTHER("other");

    companion object {
        fun of(raw: String?): LocationPlaceKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** Great-circle distance in metres. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_008.8
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** A place the user saved. Its center and radius never leave the phone. */
data class LocationSavedPlace(
    val id: String = newID(),
    val name: String,
    val kind: LocationPlaceKind,
    val isPrivate: Boolean = false,
    val latitude: Double,
    val longitude: Double,
    val radius: Double = DEFAULT_RADIUS,
) {
    fun normalized() = copy(name = name.take(MAX_NAME_LENGTH), radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS))

    fun distance(latitude: Double, longitude: Double): Double = distanceMeters(this.latitude, this.longitude, latitude, longitude)

    val json: JsonObject get() = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind.raw); put("isPrivate", isPrivate)
        put("latitude", latitude); put("longitude", longitude); put("radius", radius)
    }

    companion object {
        const val MIN_RADIUS = 100.0
        const val MAX_RADIUS = 500.0
        const val DEFAULT_RADIUS = 150.0
        /** Android monitors up to 100 geofences per app; iOS's 20 is kept. */
        const val MAX_COUNT = 20
        const val MAX_NAME_LENGTH = 40

        fun newID(): String = "p_" + SealCrypto.randomHex(6)

        /** Only the coordinate is required: a place without one can't be monitored. */
        fun parse(json: JsonObject): LocationSavedPlace? {
            val latitude = json["latitude"].double ?: return null
            val longitude = json["longitude"].double ?: return null
            return LocationSavedPlace(
                id = json["id"].str ?: newID(),
                name = json["name"].str.orEmpty(),
                kind = LocationPlaceKind.of(json["kind"].str) ?: LocationPlaceKind.OTHER,
                isPrivate = json["isPrivate"].strictBool ?: false,
                latitude = latitude,
                longitude = longitude,
                radius = json["radius"].double ?: DEFAULT_RADIUS,
            ).normalized()
        }
    }
}

/** Why a fix was taken (`fix.source`). */
enum class LocationFixSource(val raw: String) {
    MESSAGE("message"), CALL("call"), VISIT("visit"), PLACE("place"), SIGNIFICANT("significant"), APP("app"), LOCATE("locate");

    val isTalking: Boolean get() = this == MESSAGE || this == CALL

    companion object {
        fun of(raw: String?): LocationFixSource? = entries.firstOrNull { it.raw == raw }
    }
}

/** What the user chose to share; sent with every batch (`sharing`). */
data class LocationSharingSettings(
    val talking: Boolean = false,
    val talkingPrecision: LocationPrecision = LocationPrecision.PRECISE,
    val background: Boolean = false,
    val backgroundPrecision: LocationPrecision = LocationPrecision.AREA,
    val retentionDays: Int = DEFAULT_RETENTION,
    /** Epoch ms; nothing is sent before it. [UNTIL_TURNED_ON] is "until I turn it back on". */
    val pausedUntil: Long? = null,
    /** Whether the host may answer maps lookups itself. */
    val mapsHost: Boolean = true,
) {
    val isOn: Boolean get() = talking || background

    fun isPaused(nowMs: Long): Boolean = pausedUntil?.let { it > nowMs } ?: false

    fun shares(source: LocationFixSource, nowMs: Long): Boolean {
        if (isPaused(nowMs)) return false
        return if (source.isTalking) talking else background
    }

    fun precision(source: LocationFixSource): LocationPrecision = if (source.isTalking) talkingPrecision else backgroundPrecision

    fun json(nowMs: Long, zone: ZoneId): JsonObject = buildJsonObject {
        put("talking", talking)
        put("talking_precision", talkingPrecision.raw)
        put("background", background)
        put("background_precision", backgroundPrecision.raw)
        put("retention_days", if (retentionDays in RETENTION_CHOICES) retentionDays else DEFAULT_RETENTION)
        val paused = pausedUntil
        if (paused != null && isPaused(nowMs)) put("paused_until", LocationTime.format(Instant.ofEpochMilli(minOf(paused, DISTANT_FUTURE_MS)), zone)) else put("paused_until", JsonNull)
        put("time_zone", zone.id)
        put("maps_host", mapsHost)
    }

    val storageJson: JsonObject get() = buildJsonObject {
        put("talking", talking); put("talkingPrecision", talkingPrecision.raw)
        put("background", background); put("backgroundPrecision", backgroundPrecision.raw)
        put("retentionDays", retentionDays)
        pausedUntil?.let { put("pausedUntil", it) }
        put("mapsHost", mapsHost)
    }

    companion object {
        val RETENTION_CHOICES = listOf(0, 1, 7, 30, 365)
        const val DEFAULT_RETENTION = 7
        const val UNTIL_TURNED_ON = Long.MAX_VALUE
        /** What "until I turn it back on" is written as: iOS's distantFuture (year 4001). */
        const val DISTANT_FUTURE_MS = 64_092_211_200_000L

        /** Missing or unknown values fall back to the defaults. */
        fun parse(json: JsonObject?): LocationSharingSettings {
            val d = LocationSharingSettings()
            json ?: return d
            return LocationSharingSettings(
                talking = json["talking"].strictBool ?: d.talking,
                talkingPrecision = LocationPrecision.of(json["talkingPrecision"].str) ?: d.talkingPrecision,
                background = json["background"].strictBool ?: d.background,
                backgroundPrecision = LocationPrecision.of(json["backgroundPrecision"].str) ?: d.backgroundPrecision,
                retentionDays = json["retentionDays"].long?.toInt() ?: d.retentionDays,
                pausedUntil = json["pausedUntil"].long,
                mapsHost = json["mapsHost"].strictBool ?: d.mapsHost,
            )
        }
    }
}

/** Where location goes: one profile on one saved dashboard, fixed when a layer is first turned on. */
data class LocationBinding(val dashboardID: String, val profile: String, val host: String) {
    val json: JsonObject get() = buildJsonObject { put("dashboardID", dashboardID); put("profile", profile); put("host", host) }

    companion object {
        fun parse(json: JsonObject?): LocationBinding? {
            val id = json?.get("dashboardID")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content ?: return null
            return LocationBinding(id, json["profile"].str ?: "default", json["host"].str.orEmpty())
        }
    }
}

/** Everything location sharing keeps on the phone (iOS LocationSharedConfig). Saved places' coordinates live only here. */
data class LocationSharedConfig(
    val binding: LocationBinding? = null,
    val settings: LocationSharingSettings = LocationSharingSettings(),
    val places: List<LocationSavedPlace> = emptyList(),
    /** This phone's id on the host, kept across turning sharing off so the host's copy can still be deleted. */
    val deviceID: String = SealCrypto.randomHex(16),
    val approximateOnly: Boolean = false,
    /** A change of the choices or places the host hasn't had yet. */
    val settingsPending: Boolean = false,
    val voiceReaders: List<String>? = null,
) {
    fun letsVoiceRead(storageID: String, nowMs: Long): Boolean = readsByVoice(nowMs) && storageID in voiceReaders.orEmpty()

    fun readsByVoice(nowMs: Long): Boolean = binding != null && settings.isOn && !settings.isPaused(nowMs) && voiceReaders.orEmpty().isNotEmpty()

    fun withVoiceRead(storageID: String, allowed: Boolean): LocationSharedConfig {
        val readers = voiceReaders.orEmpty().toMutableSet()
        if (allowed) readers += storageID else readers -= storageID
        return copy(voiceReaders = readers.sorted().ifEmpty { null })
    }

    fun encode(): String = CanonicalJson.encode(buildJsonObject {
        binding?.let { put("binding", it.json) }
        put("settings", settings.storageJson)
        put("places", JsonArray(places.map { it.json }))
        put("deviceID", deviceID)
        put("approximateOnly", approximateOnly)
        put("settingsPending", settingsPending)
        voiceReaders?.let { readers -> put("voiceReaders", JsonArray(readers.map(::JsonPrimitive))) }
    })

    companion object {
        /** Tolerates missing keys; a saved place that can't be read is skipped. Null when not JSON at all. */
        fun decode(text: String): LocationSharedConfig? {
            val json = runCatching { ConduitJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val binding = LocationBinding.parse(json["binding"].obj)
            var settings = LocationSharingSettings.parse(json["settings"].obj)
            if (binding == null && json["binding"] != null && json["binding"] !is JsonNull) {
                // A binding that can't be read sends nothing, so the layers show off.
                settings = settings.copy(talking = false, background = false)
            }
            return LocationSharedConfig(
                binding = binding,
                settings = settings,
                places = json["places"].arr.orEmpty().mapNotNull { it.obj?.let(LocationSavedPlace::parse) },
                deviceID = json["deviceID"].str ?: SealCrypto.randomHex(16),
                approximateOnly = json["approximateOnly"].strictBool ?: false,
                settingsPending = json["settingsPending"].strictBool ?: false,
                voiceReaders = json["voiceReaders"].arr?.mapNotNull { it.str },
            )
        }

        fun salvageDeviceID(text: String): String? = Regex("\"deviceID\"\\s*:\\s*\"([A-Za-z0-9_-]{8,64})\"").find(text)?.groupValues?.get(1)
    }
}

/** A saved place's arrive or leave (`events`). */
data class LocationPlaceEvent(
    val id: String = "e_" + SealCrypto.randomHex(12),
    val kind: String,
    val placeID: String,
    val atMs: Long,
    val isPrivate: Boolean,
) {
    fun json(zone: ZoneId): JsonObject = buildJsonObject {
        put("id", id); put("kind", kind); put("place_id", placeID); put("at", LocationTime.format(Instant.ofEpochMilli(atMs), zone)); put("private", isPrivate)
    }

    val storage: JsonObject get() = buildJsonObject {
        put("id", id); put("kind", kind); put("placeID", placeID); put("at", atMs); put("isPrivate", isPrivate)
    }

    companion object {
        const val ARRIVE = "arrive"
        const val LEAVE = "leave"
        fun parse(json: JsonObject): LocationPlaceEvent? {
            return LocationPlaceEvent(
                id = json["id"].str ?: return null,
                kind = json["kind"].str ?: return null,
                placeID = json["placeID"].str ?: return null,
                atMs = json["at"].long ?: return null,
                isPrivate = json["isPrivate"].strictBool ?: false,
            )
        }
    }
}

/** Neighbourhood, city and country from on-phone reverse geocoding. */
data class LocationArea(val name: String? = null, val locality: String? = null, val region: String? = null, val country: String? = null, val countryCode: String? = null) {
    val isEmpty: Boolean get() = listOf(name, locality, region, country, countryCode).all { it.isNullOrEmpty() }

    val json: JsonObject get() = buildJsonObject {
        name?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
        locality?.takeIf { it.isNotEmpty() }?.let { put("locality", it) }
        region?.takeIf { it.isNotEmpty() }?.let { put("region", it) }
        country?.takeIf { it.isNotEmpty() }?.let { put("country", it) }
        countryCode?.takeIf { it.isNotEmpty() }?.let { put("country_code", it) }
    }

    val storage: JsonObject get() = json

    companion object {
        fun parse(json: JsonObject?): LocationArea? = json?.let {
            LocationArea(it["name"].str, it["locality"].str, it["region"].str, it["country"].str, it["country_code"].str)
        }
    }
}

/** A measured position, before any precision rule. */
data class LocationRawFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val timestampMs: Long,
    val speed: Double? = null,
    val course: Double? = null,
) {
    val storage: JsonObject get() = buildJsonObject {
        put("latitude", latitude); put("longitude", longitude); put("accuracy", accuracy); put("timestamp", timestampMs)
        speed?.let { put("speed", it) }; course?.let { put("course", it) }
    }

    companion object {
        fun parse(json: JsonObject?): LocationRawFix? {
            json ?: return null
            return LocationRawFix(
                json["latitude"].double ?: return null, json["longitude"].double ?: return null, json["accuracy"].double ?: 0.0,
                json["timestamp"].long ?: return null, json["speed"].double, json["course"].double,
            )
        }
    }
}

/** A fix as it goes on the wire, built from a raw measurement. */
data class LocationFixRecord(val id: String = LocationIDs.fixID(), val source: LocationFixSource, val raw: LocationRawFix, val area: LocationArea? = null) {
    val storage: JsonObject get() = buildJsonObject {
        put("id", id); put("source", source.raw); put("raw", raw.storage); area?.let { put("area", it.storage) }
    }

    companion object {
        fun parse(json: JsonObject?): LocationFixRecord? {
            json ?: return null
            return LocationFixRecord(
                json["id"].str ?: return null, LocationFixSource.of(json["source"].str) ?: return null,
                LocationRawFix.parse(json["raw"].obj) ?: return null, LocationArea.parse(json["area"].obj),
            )
        }
    }
}

object LocationIDs {
    fun batchID() = "b_" + SealCrypto.randomHex(12)
    fun fixID() = "f_" + SealCrypto.randomHex(12)

    /** device_id and batch_id: `^[A-Za-z0-9_-]{8,64}$`. */
    fun isValidWireID(value: String) = Regex("^[A-Za-z0-9_-]{8,64}$").matches(value)
}

/** Local time with its offset, as the contract writes times. */
object LocationTime {
    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = HealthDateFormat.format(instant, zone)
}

/** The precision rules, applied on the phone before sealing (iOS LocationPrecisionRules). */
object LocationPrecisionRules {
    const val AREA_MINIMUM_ACCURACY = 1_000.0
    const val PLACE_MATCH_MAXIMUM_ACCURACY = 500.0
    const val PRIVATE_PLACE_NAME = "Private place"

    fun containingPlace(raw: LocationRawFix, places: List<LocationSavedPlace>): LocationSavedPlace? {
        if (raw.accuracy > PLACE_MATCH_MAXIMUM_ACCURACY) return null
        return places.map { it to it.distance(raw.latitude, raw.longitude) / it.radius }.filter { it.second <= 1 }.minByOrNull { it.second }?.first
    }

    /** Whatever the accuracy: where a private place is must never follow from a fix sent near it. */
    fun containingPrivatePlace(raw: LocationRawFix, places: List<LocationSavedPlace>): LocationSavedPlace? =
        places.filter { it.isPrivate }.map { it to it.distance(raw.latitude, raw.longitude) / it.radius }.filter { it.second <= 1 }.minByOrNull { it.second }?.first

    fun rounded(value: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        return roundHalfAway(value * scale) / scale
    }

    fun roundedDistance(meters: Double, step: Double): Int = (roundHalfAway(meters / step) * step).toInt()

    fun json(fix: LocationFixRecord, requested: LocationPrecision, places: List<LocationSavedPlace>, approximateOnly: Boolean, zone: ZoneId): JsonObject {
        val precision = if (approximateOnly) requested.capped(LocationPrecision.AREA) else requested
        val map = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(fix.id),
            "at" to JsonPrimitive(LocationTime.format(Instant.ofEpochMilli(fix.raw.timestampMs), zone)),
            "source" to JsonPrimitive(fix.source.raw),
            "precision" to JsonPrimitive(precision.raw),
        )
        containingPrivatePlace(fix.raw, places)?.let { private ->
            map["place_id"] = JsonPrimitive(private.id)
            return JsonObject(map)
        }
        if (!approximateOnly) containingPlace(fix.raw, places)?.let { map["place_id"] = JsonPrimitive(it.id) }
        if (precision == LocationPrecision.PLACES) return JsonObject(map)
        val step = if (precision == LocationPrecision.PRECISE) 10.0 else 100.0
        val distances = places.filter { !it.isPrivate }.associate { it.id to JsonPrimitive(roundedDistance(it.distance(fix.raw.latitude, fix.raw.longitude), step)) }
        if (distances.isNotEmpty()) map["distances_m"] = JsonObject(distances)
        fix.area?.takeIf { !it.isEmpty }?.let { map["area"] = it.json }
        when (precision) {
            LocationPrecision.AREA -> {
                map["lat"] = JsonPrimitive(rounded(fix.raw.latitude, 2))
                map["lon"] = JsonPrimitive(rounded(fix.raw.longitude, 2))
                map["accuracy_m"] = JsonPrimitive(roundHalfAway(maxOf(AREA_MINIMUM_ACCURACY, fix.raw.accuracy)).toInt())
            }
            LocationPrecision.PRECISE -> {
                map["lat"] = JsonPrimitive(rounded(fix.raw.latitude, 6))
                map["lon"] = JsonPrimitive(rounded(fix.raw.longitude, 6))
                map["accuracy_m"] = JsonPrimitive(roundHalfAway(fix.raw.accuracy).toInt())
                fix.raw.speed?.let { map["speed_mps"] = JsonPrimitive(rounded(it, 1)) }
                fix.raw.course?.let { map["course"] = JsonPrimitive(rounded(it, 0)) }
            }
            LocationPrecision.PLACES -> Unit
        }
        return JsonObject(map)
    }

    /** Names and kinds only; a private place without its name or kind. */
    fun placesJSON(places: List<LocationSavedPlace>): JsonArray = JsonArray(places.take(LocationSavedPlace.MAX_COUNT).map { place ->
        buildJsonObject {
            put("id", place.id)
            put("name", if (place.isPrivate) PRIVATE_PLACE_NAME else place.name.take(LocationSavedPlace.MAX_NAME_LENGTH))
            put("kind", if (place.isPrivate) LocationPlaceKind.OTHER.raw else place.kind.raw)
            put("private", place.isPrivate)
        }
    })

    fun placesDigest(places: List<LocationSavedPlace>): String = places.take(LocationSavedPlace.MAX_COUNT).joinToString("\n") { place ->
        val name = if (place.isPrivate) PRIVATE_PLACE_NAME else place.name.take(LocationSavedPlace.MAX_NAME_LENGTH)
        val kind = if (place.isPrivate) LocationPlaceKind.OTHER.raw else place.kind.raw
        listOf(place.id, name, kind, if (place.isPrivate) "1" else "0").joinToString("|")
    }
}

/** One upload's plaintext. */
data class LocationBatch(
    val batchID: String = LocationIDs.batchID(),
    val sentAtMs: Long,
    val settings: LocationSharingSettings,
    val zone: ZoneId = ZoneId.systemDefault(),
    val approximateOnly: Boolean = false,
    /** The saved places, when the host's copy needs replacing. */
    val places: List<LocationSavedPlace>? = null,
    /** All saved places, for naming the place a fix is in and distances. */
    val knownPlaces: List<LocationSavedPlace> = emptyList(),
    val fix: LocationFixRecord? = null,
    val events: List<LocationPlaceEvent> = emptyList(),
    val locateID: String? = null,
    /**
     * What this phone can answer for the host (`sharing.features`). Android
     * gets no location pushes, so the host can't ask it for maps lookups:
     * none by default (iOS sends "maps").
     */
    val features: List<String> = PHONE_FEATURES,
) {
    fun json(): JsonObject {
        val sharing = settings.json(sentAtMs, zone).toMutableMap()
        if (features.isNotEmpty()) sharing["features"] = JsonArray(features.map(::JsonPrimitive))
        return buildJsonObject {
            put("batch_id", batchID)
            put("sent_at", LocationTime.format(Instant.ofEpochMilli(sentAtMs), zone))
            put("sharing", JsonObject(sharing))
            places?.let { put("places", LocationPrecisionRules.placesJSON(it)) }
            fix?.let { put("fix", LocationPrecisionRules.json(it, settings.precision(it.source), knownPlaces, approximateOnly, zone)) }
            if (events.isNotEmpty()) put("events", JsonArray(events.map { it.json(zone) }))
            locateID?.let { put("locate_id", it) }
        }
    }

    fun plaintext(): ByteArray = CanonicalJson.bytes(json())

    companion object {
        val PHONE_FEATURES: List<String> = emptyList()
    }
}

/** The agreed location key. */
data class LocationSessionKey(
    val kid: String,
    val key: ByteArray,
    val deviceID: String,
    val voiceRequestKey: ByteArray? = null,
    val voiceAnswerKey: ByteArray? = null,
    val mapsRequestKey: ByteArray? = null,
    val mapsAnswerKey: ByteArray? = null,
) {
    val readsByVoice: Boolean get() = voiceRequestKey != null && voiceAnswerKey != null
    val answersMaps: Boolean get() = mapsRequestKey != null && mapsAnswerKey != null

    fun encode(): String = CanonicalJson.encode(buildJsonObject {
        put("kid", kid); put("key", SealCrypto.base64Url(key)); put("deviceID", deviceID)
        voiceRequestKey?.let { put("voiceRequestKey", SealCrypto.base64Url(it)) }
        voiceAnswerKey?.let { put("voiceAnswerKey", SealCrypto.base64Url(it)) }
        mapsRequestKey?.let { put("mapsRequestKey", SealCrypto.base64Url(it)) }
        mapsAnswerKey?.let { put("mapsAnswerKey", SealCrypto.base64Url(it)) }
    })

    override fun equals(other: Any?): Boolean = other is LocationSessionKey && encode() == other.encode()
    override fun hashCode(): Int = encode().hashCode()

    companion object {
        fun decode(text: String?): LocationSessionKey? {
            val json = text?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            fun bytes(key: String) = json[key].str?.let(SealCrypto::base64UrlDecoded)
            return LocationSessionKey(
                json["kid"].str ?: return null, bytes("key") ?: return null, json["deviceID"].str ?: return null,
                bytes("voiceRequestKey"), bytes("voiceAnswerKey"), bytes("mapsRequestKey"), bytes("mapsAnswerKey"),
            )
        }
    }
}

/** iOS LocationCrypto, LocationVoiceCrypto and LocationMapsCrypto: must match the notifier's location store. */
object LocationCrypto {
    const val SALT = "conduit-location-v1"
    const val INGEST_INFO = "conduit-location-v1 ingest device-to-host"
    const val VOICE_REQUEST_INFO = "conduit-location-v1 voice device-to-host"
    const val VOICE_ANSWER_INFO = "conduit-location-v1 voice host-to-device"
    const val MAPS_REQUEST_INFO = "conduit-location-v1 maps host-to-device"
    const val MAPS_ANSWER_INFO = "conduit-location-v1 maps device-to-host"
    const val KID_LABEL = "conduit-location-kid\n"
    const val AAD_TAG = "conduit-location/1"

    fun keyID(devicePublic: ByteArray, hostPublic: ByteArray) = SealCrypto.keyID(KID_LABEL, devicePublic, hostPublic)

    fun agree(devicePrivate: ByteArray, deviceID: String, hostPublicKey: String, kid: String): LocationSessionKey {
        val keys = SealCrypto.agree(
            devicePrivate, deviceID, hostPublicKey, kid, KID_LABEL, SALT,
            listOf(INGEST_INFO, VOICE_REQUEST_INFO, VOICE_ANSWER_INFO, MAPS_REQUEST_INFO, MAPS_ANSWER_INFO),
        )
        return LocationSessionKey(kid, keys[0], deviceID, keys[1], keys[2], keys[3], keys[4])
    }

    fun aad(kid: String, deviceID: String, batchID: String) = SealCrypto.aad(AAD_TAG, "ingest", "kid=$kid", "dev=$deviceID", "batch=$batchID")

    fun envelope(plaintext: ByteArray, key: LocationSessionKey, batchID: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val sealed = SealCrypto.seal(plaintext, key.key, aad(key.kid, key.deviceID, batchID), nonce)
        return buildJsonObject {
            put("v", 1); put("device_id", key.deviceID); put("kid", key.kid); put("batch_id", batchID); put("nonce", sealed.nonce); put("ct", sealed.ct)
        }
    }

    /** The ingest body for a batch: a key from before maps can't open a maps request, so no features. */
    fun ingestBody(batch: LocationBatch, key: LocationSessionKey): JsonObject {
        val sealable = if (key.answersMaps) batch else batch.copy(features = emptyList())
        return envelope(sealable.plaintext(), key, sealable.batchID)
    }

    fun voiceAAD(direction: String, tool: String, kid: String, deviceID: String, callID: String) =
        VoiceReadSeal.aad(AAD_TAG, direction, tool, kid, deviceID, callID)

    fun voiceRequest(tool: String, plaintext: ByteArray, key: LocationSessionKey, callID: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val requestKey = key.voiceRequestKey ?: throw SealCrypto.Failure("noVoiceKey")
        return VoiceReadSeal.request(AAD_TAG, tool, plaintext, requestKey, key.kid, key.deviceID, callID, nonce)
    }

    fun openVoiceAnswer(response: JsonObject, tool: String, key: LocationSessionKey, callID: String): JsonElement {
        val answerKey = key.voiceAnswerKey ?: throw SealCrypto.Failure("noVoiceKey")
        return VoiceReadSeal.openAnswer(response, AAD_TAG, tool, answerKey, key.kid, key.deviceID, callID)
    }

    fun mapsAAD(direction: String, kid: String, deviceID: String, id: String) = SealCrypto.aad(AAD_TAG, direction, "kid=$kid", "dev=$deviceID", "id=$id")

    fun openMapsRequest(response: JsonObject, key: LocationSessionKey, id: String): JsonObject {
        val requestKey = key.mapsRequestKey ?: throw SealCrypto.Failure("noMapsKey")
        val plaintext = SealCrypto.open(response, requestKey, mapsAAD("maps-request", key.kid, key.deviceID, id))
        return runCatching { ConduitJson.parseToJsonElement(plaintext.decodeToString()) as? JsonObject }.getOrNull() ?: throw SealCrypto.Failure("malformedRequest")
    }

    fun mapsAnswer(plaintext: ByteArray, key: LocationSessionKey, id: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val answerKey = key.mapsAnswerKey ?: throw SealCrypto.Failure("noMapsKey")
        val sealed = SealCrypto.seal(plaintext, answerKey, mapsAAD("maps-answer", key.kid, key.deviceID, id), nonce)
        return buildJsonObject { put("v", 1); put("device_id", key.deviceID); put("kid", key.kid); put("nonce", sealed.nonce); put("ct", sealed.ct) }
    }
}

/** What couldn't be sent yet: only the latest fix and the last 50 events. */
data class LocationUnsent(
    val fix: LocationFixRecord? = null,
    val events: List<LocationPlaceEvent> = emptyList(),
    /** Whether the phone was last inside each saved place. */
    val inside: Map<String, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean get() = fix == null && events.isEmpty()

    fun keeping(newFix: LocationFixRecord?, newEvents: List<LocationPlaceEvent>): LocationUnsent {
        val nextFix = if (newFix != null && newFix.raw.timestampMs >= (fix?.raw?.timestampMs ?: Long.MIN_VALUE)) newFix else fix
        val merged = events.toMutableList()
        for (event in newEvents) if (merged.none { it.id == event.id }) merged += event
        return copy(fix = nextFix, events = merged.sortedBy { it.atMs }.takeLast(MAX_EVENTS))
    }

    fun removing(fixID: String?, eventIDs: Set<String>): LocationUnsent =
        copy(fix = if (fixID != null && fix?.id == fixID) null else fix, events = events.filter { it.id !in eventIDs })

    fun encode(): String = CanonicalJson.encode(buildJsonObject {
        fix?.let { put("fix", it.storage) }
        put("events", JsonArray(events.map { it.storage }))
        put("inside", JsonObject(inside.mapValues { JsonPrimitive(it.value) }))
    })

    companion object {
        const val MAX_EVENTS = 50
        fun decode(text: String?): LocationUnsent {
            val json = text?.let { runCatching { ConduitJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return LocationUnsent()
            return LocationUnsent(
                LocationFixRecord.parse(json["fix"].obj),
                json["events"].arr.orEmpty().mapNotNull { it.obj?.let(LocationPlaceEvent::parse) },
                json["inside"].obj?.entries?.mapNotNull { entry -> entry.value.strictBool?.let { entry.key to it } }?.toMap().orEmpty(),
            )
        }
    }
}

/** What the location engine remembers: no locations. */
@kotlinx.serialization.Serializable
data class LocationSyncState(
    val generation: String? = null,
    val placesDigest: String? = null,
    val lastSent: Long? = null,
    val lastFixSent: Long? = null,
    val lastFixSource: String? = null,
    val lastAttempt: Long? = null,
    val pause: SyncPause? = null,
) {
    fun encode(): String = ConduitJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): LocationSyncState =
            text?.let { runCatching { ConduitJson.decodeFromString(serializer(), it) }.getOrNull() } ?: LocationSyncState()

        fun message(pause: SyncPause): String = when (pause.kind) {
            "signInRequired" -> L10n.tr("Paused: sign in to this dashboard again. Location is sent again once you open Conduit.")
            "pluginMissing" -> L10n.tr("Paused: update the Conduit plugin on this host to share your location.")
            else -> L10n.tr("Paused: the host refused location data (%s).", pause.detail.orEmpty())
        }
    }
}

/** The host's answer to `/location/status`. */
data class LocationHostStatus(val stored: Boolean, val readsToday: Int, val rules: Int, val lastSync: String?, val locateAvailable: Boolean) {
    companion object {
        fun parse(json: JsonObject) = LocationHostStatus(
            stored = json["stored"].strictBool ?: false,
            readsToday = json["reads_24h"].long?.toInt() ?: 0,
            rules = json["rules"].long?.toInt() ?: 0,
            lastSync = json["device"].obj?.get("last_sync").str,
            locateAvailable = json["locate"].obj?.get("available").strictBool ?: false,
        )
    }
}

/** One time the agent read the location (never the location itself). */
data class LocationRead(val at: String, val action: String, val sessionTitle: String?, val platform: String?, val occurrence: Int = 0) {
    val id: String get() = "$at|$action|${sessionTitle.orEmpty()}|${platform.orEmpty()}|$occurrence"
    val date: Instant? get() = parseIsoInstant(at)

    companion object {
        fun parse(json: JsonObject): List<LocationRead> {
            val seen = mutableMapOf<String, Int>()
            return json["reads"].arr.orEmpty().mapNotNull { element ->
                val entry = element.obj ?: return@mapNotNull null
                val at = entry["at"].str ?: return@mapNotNull null
                val read = LocationRead(at, entry["action"].str.orEmpty(), entry["session_title"].str, entry["platform"].str)
                val base = read.id
                val occurrence = seen[base] ?: 0
                seen[base] = occurrence + 1
                read.copy(occurrence = occurrence)
            }
        }
    }
}

/** A place rule the agent set up ("when I leave Home, Mon-Fri…"). */
data class LocationRule(
    val id: String,
    val what: String,
    val placeID: String?,
    val placeName: String?,
    val event: String,
    val days: String?,
    val hours: String?,
    val calls: Boolean,
    val repeatMode: String?,
    val lastFired: String?,
) {
    val isAnySavedPlace: Boolean get() = placeID == "*"

    companion object {
        fun parse(json: JsonObject): List<LocationRule> = json["rules"].arr.orEmpty().mapNotNull { element ->
            val entry = element.obj ?: return@mapNotNull null
            LocationRule(
                id = entry["id"].str ?: return@mapNotNull null,
                what = entry["what"].str ?: entry["task"].str.orEmpty(),
                placeID = entry["place_id"].str,
                placeName = entry["place_name"].str,
                event = entry["event"].str.orEmpty(),
                days = describe(entry["days"]),
                hours = describe(entry["hours"]),
                calls = entry["call"].strictBool ?: false,
                repeatMode = entry["repeat"].str,
                lastFired = entry["last_fired"].str,
            )
        }

        private fun describe(value: JsonElement?): String? = when (value) {
            is JsonPrimitive -> value.takeIf { it !is JsonNull }?.content?.ifEmpty { null }
            is JsonArray -> value.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.str ?: it.toString() }
            else -> null
        }
    }
}

/**
 * The agent's on-demand locate (iOS LocationLocateRequest). On iOS it comes
 * as a location push the relay sends; Android has no push relay, so this
 * only reads a plain `conduit` routing stub (for a future delivery path)
 * and decides whether this phone answers it.
 */
data class LocationLocateRequest(val id: String, val profile: String?, val gatewayID: String?, val dashboardID: String?, val kind: String = LOCATE) {
    fun isForBinding(config: LocationSharedConfig): Boolean {
        val binding = config.binding ?: return false
        if (dashboardID == null || !dashboardID.equals(binding.dashboardID, ignoreCase = true)) return false
        return normalizedProfile(profile) == normalizedProfile(binding.profile)
    }

    fun isAnswered(config: LocationSharedConfig, nowMs: Long): Boolean = isForBinding(config) && config.settings.shares(LocationFixSource.LOCATE, nowMs)

    companion object {
        const val TYPE = "location.requested"
        const val LOCATE = "locate"
        const val MAPS = "maps"

        fun normalizedProfile(profile: String?): String = profile?.trim()?.ifEmpty { null } ?: "default"

        fun isMapsID(id: String) = Regex("^m_[A-Za-z0-9_-]{16,64}$").matches(id)
        private fun isLocateID(id: String) = Regex("^[A-Za-z0-9_-]{4,80}$").matches(id)

        /** The request in a plain routing stub (`{type, profile, gateway_id, dashboard_id, locate|maps: {id}}`). */
        fun parseStub(stub: JsonObject): LocationLocateRequest? {
            if (stub["type"].str != TYPE) return null
            val locate = stub["locate"].obj?.get("id").str?.takeIf(::isLocateID)
            val maps = stub["maps"].obj?.get("id").str?.takeIf(::isMapsID)
            val (kind, id) = when {
                locate != null -> LOCATE to locate
                maps != null -> MAPS to maps
                else -> return null
            }
            return LocationLocateRequest(id, stub["profile"].str, stub["gateway_id"].str, stub["dashboard_id"].str, kind)
        }
    }
}
