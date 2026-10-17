package app.hermesconduit.android.model

import app.hermesconduit.android.core.L10n
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

/** Swift's `.rounded()`: halves away from zero. */
fun roundHalfAway(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

/**
 * Health data for Hermes (iOS HealthSyncModels): the user turns on sharing
 * per category; Conduit reads Health Connect and sends it straight to one
 * Hermes profile on one dashboard, sealed end to end. The raw values and
 * units must match METRICS in the notifier's health_store.py.
 */
enum class HealthCategory(val raw: String, val title: String, val summary: String) {
    ACTIVITY("activity", "Activity", "Steps, distance, active energy, exercise time, flights climbed"),
    HEART("heart", "Heart", "Heart rate, resting and walking heart rate, heart rate variability"),
    SLEEP("sleep", "Sleep", "Time asleep and in bed, sleep stages"),
    WORKOUTS("workouts", "Workouts", "Each workout: type, time, duration, energy, distance, heart rate"),
    BODY("body", "Body measurements", "Weight, body fat, height"),
    VITALS("vitals", "Vitals", "Blood oxygen, respiratory rate, blood pressure, sleeping wrist temperature"),
    FITNESS("fitness", "Fitness", "Cardio fitness (VO2 max), walking speed");

    /** Sleep and workouts are always sent as their own events. */
    val hasDetailedReadings: Boolean get() = this != SLEEP && this != WORKOUTS

    val metrics: List<HealthMetric> get() = HealthMetric.entries.filter { it.category == this }

    companion object {
        fun of(raw: String): HealthCategory? = entries.firstOrNull { it.raw == raw }
    }
}

enum class HealthAggregation { SUM, STATS }

enum class HealthMetric(val raw: String, val category: HealthCategory, val unit: String, val hasHourlyBuckets: Boolean = false) {
    STEPS("steps", HealthCategory.ACTIVITY, "count", true),
    DISTANCE_WALKING_RUNNING("distance_walking_running", HealthCategory.ACTIVITY, "m", true),
    DISTANCE_CYCLING("distance_cycling", HealthCategory.ACTIVITY, "m", true),
    ACTIVE_ENERGY("active_energy", HealthCategory.ACTIVITY, "kcal", true),
    EXERCISE_TIME("exercise_time", HealthCategory.ACTIVITY, "min", true),
    STAND_TIME("stand_time", HealthCategory.ACTIVITY, "min", true),
    FLIGHTS_CLIMBED("flights_climbed", HealthCategory.ACTIVITY, "count", true),
    HEART_RATE("heart_rate", HealthCategory.HEART, "count/min", true),
    RESTING_HEART_RATE("resting_heart_rate", HealthCategory.HEART, "count/min"),
    WALKING_HEART_RATE_AVERAGE("walking_heart_rate_average", HealthCategory.HEART, "count/min"),
    HEART_RATE_VARIABILITY("heart_rate_variability", HealthCategory.HEART, "ms"),
    OXYGEN_SATURATION("oxygen_saturation", HealthCategory.VITALS, "%"),
    RESPIRATORY_RATE("respiratory_rate", HealthCategory.VITALS, "count/min"),
    BLOOD_PRESSURE_SYSTOLIC("blood_pressure_systolic", HealthCategory.VITALS, "mmHg"),
    BLOOD_PRESSURE_DIASTOLIC("blood_pressure_diastolic", HealthCategory.VITALS, "mmHg"),
    WRIST_TEMPERATURE("wrist_temperature", HealthCategory.VITALS, "degC"),
    BODY_MASS("body_mass", HealthCategory.BODY, "kg"),
    BODY_FAT_PERCENTAGE("body_fat_percentage", HealthCategory.BODY, "%"),
    HEIGHT("height", HealthCategory.BODY, "m"),
    VO2_MAX("vo2_max", HealthCategory.FITNESS, "mL/(kg*min)"),
    WALKING_SPEED("walking_speed", HealthCategory.FITNESS, "m/s"),
    SIX_MINUTE_WALK_DISTANCE("six_minute_walk_distance", HealthCategory.FITNESS, "m");

    val aggregation: HealthAggregation get() = if (category == HealthCategory.ACTIVITY) HealthAggregation.SUM else HealthAggregation.STATS
}

enum class HealthHistoryDepth(val raw: String, val title: String, val days: Int?) {
    DAYS30("days30", "30 days", 30),
    DAYS90("days90", "90 days", 90),
    YEAR("year", "1 year", 365),
    ALL("all", "All history", null);

    companion object {
        val DEFAULT = DAYS30
        fun of(raw: String?): HealthHistoryDepth = entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

object HealthSyncLimits {
    const val HOURLY_HISTORY_DAYS = 30L
    /** Recent days are recomputed on every pass: watches often sync late. */
    const val RECOMPUTED_DAYS = 3L
    const val MAX_ITEMS_PER_BATCH = 2_000
    const val BACKFILL_CHUNK_DAYS = 30L
    const val SAMPLES_PER_QUERY = 1_000
}

/** Where data goes: one profile on one saved dashboard (health and phone alike). */
@Serializable
data class SyncDestination(val dashboardID: String, val profile: String, val host: String)

@Serializable
data class HealthSyncPreferences(
    val destination: SyncDestination? = null,
    val categories: Set<String> = emptySet(),
    val detailed: Set<String> = emptySet(),
    val history: String = HealthHistoryDepth.DEFAULT.raw,
    val voiceReaders: List<String>? = null,
) {
    val isOn: Boolean get() = destination != null && categorySet.isNotEmpty()
    val categorySet: Set<HealthCategory> get() = categories.mapNotNull(HealthCategory::of).toSet()
    val detailedSet: Set<HealthCategory> get() = detailed.mapNotNull(HealthCategory::of).toSet()
    val historyDepth: HealthHistoryDepth get() = HealthHistoryDepth.of(history)

    fun letsVoiceRead(mode: LiveVoiceMode): Boolean = isOn && mode.storageID in voiceReaders.orEmpty()

    fun withVoiceRead(mode: LiveVoiceMode, allowed: Boolean): HealthSyncPreferences {
        val readers = voiceReaders.orEmpty().toMutableSet()
        if (allowed) readers += mode.storageID else readers -= mode.storageID
        return copy(voiceReaders = readers.sorted().ifEmpty { null })
    }

    companion object {
        const val STORAGE_KEY = "conduit.healthSync.preferences.v1"
        fun decode(text: String?): HealthSyncPreferences =
            text?.let { runCatching { ConduitJson.decodeFromString(serializer(), it) }.getOrNull() } ?: HealthSyncPreferences()
    }

    fun encode(): String = ConduitJson.encodeToString(serializer(), this)
}

/** A deletion owed on a dashboard the host couldn't be reached on (health and phone alike). */
@Serializable
data class PendingDeletion(val dashboardID: String, val profile: String, val deviceID: String) {
    companion object {
        fun decodeAll(text: String?): List<PendingDeletion> =
            text?.let { runCatching { ConduitJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer()), it) }.getOrNull() }.orEmpty()

        fun encodeAll(list: List<PendingDeletion>): String =
            ConduitJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), list)
    }
}

/** Why sharing is paused (health and phone use the same kinds, with their own words). */
@Serializable
data class SyncPause(val kind: String, val detail: String? = null) {
    companion object {
        val SIGN_IN_REQUIRED = SyncPause("signInRequired")
        val PLUGIN_MISSING = SyncPause("pluginMissing")
        val OTHER_DASHBOARD = SyncPause("otherDashboard")
        fun refused(detail: String) = SyncPause("refused", detail)
    }

    fun healthMessage(): String = when (kind) {
        "signInRequired" -> L10n.tr("Paused: sign in to this dashboard again. Sharing catches up when you open Conduit.")
        "pluginMissing" -> L10n.tr("Paused: update the Conduit plugin on this host to share health data.")
        "otherDashboard" -> L10n.tr("Waiting: health data goes only to the dashboard it was turned on for. Switch back to it to sync.")
        else -> L10n.tr("Paused: the host refused health data (%s).", detail.orEmpty())
    }

    fun phoneMessage(): String = when (kind) {
        "signInRequired" -> L10n.tr("Paused: sign in to this dashboard again. Sharing catches up when you open Conduit.")
        "pluginMissing" -> L10n.tr("Paused: update the Conduit plugin on this host to share your calendar and phone status.")
        "otherDashboard" -> L10n.tr("Waiting: this goes only to the dashboard it was turned on for. Switch back to it to sync.")
        else -> L10n.tr("Paused: the host refused the upload (%s).", detail.orEmpty())
    }
}

/**
 * What live voice calls read of the shared data: when, which mode, which
 * kind of read, never the values. Kept on the phone for a week.
 */
@Serializable
data class VoiceReadLog(val entries: List<Entry> = emptyList()) {
    @Serializable
    data class Entry(val at: Long, val mode: String, val action: String) {
        val modeName: String get() = LiveVoiceMode.of(mode)?.displayName ?: mode
    }

    fun pruned(nowMs: Long): VoiceReadLog = copy(entries = entries.filter { nowMs - it.at <= KEPT_FOR_MS })

    fun recording(mode: LiveVoiceMode, action: String, nowMs: Long): VoiceReadLog {
        val next = pruned(nowMs).entries + Entry(nowMs, mode.storageID, action.take(40))
        return VoiceReadLog(next.takeLast(MAXIMUM_ENTRIES))
    }

    fun encode(): String = ConduitJson.encodeToString(serializer(), this)

    companion object {
        const val HEALTH_KEY = "conduit.healthSync.voiceReads.v1"
        const val PHONE_KEY = "conduit.phoneSync.voiceReads.v1"
        const val LOCATION_KEY = "conduit.location.voiceReads.v1"
        const val KEPT_FOR_MS = 7L * 24 * 3600 * 1000
        const val MAXIMUM_ENTRIES = 200
        fun decode(text: String?, nowMs: Long): VoiceReadLog =
            (text?.let { runCatching { ConduitJson.decodeFromString(serializer(), it) }.getOrNull() } ?: VoiceReadLog()).pruned(nowMs)
    }
}

// region Batch

enum class HealthGrain(val raw: String) { HOUR("hour"), DAY("day"), NIGHT("night") }

sealed class HealthValue {
    data class Num(val value: Double) : HealthValue()
    data class Text(val value: String) : HealthValue()

    val json: JsonElement get() = when (this) {
        is Num -> JsonPrimitive(value)
        is Text -> JsonPrimitive(value)
    }
}

data class HealthBucket(
    val start: Instant,
    val end: Instant,
    val sum: Double? = null,
    val min: Double? = null,
    val avg: Double? = null,
    val max: Double? = null,
    /** A night: the morning it ended (local date). */
    val date: String? = null,
    val data: Map<String, HealthValue> = emptyMap(),
    val source: String? = null,
)

data class HealthWindow(
    val metric: String,
    val grain: HealthGrain,
    val unit: String,
    val from: Instant,
    val to: Instant,
    val buckets: List<HealthBucket>,
)

enum class HealthEventKind(val raw: String) { WORKOUT("workout"), SLEEP("sleep"), SAMPLE("sample") }

data class HealthEvent(
    val id: String,
    val kind: HealthEventKind,
    val metric: String,
    val start: Instant,
    val end: Instant,
    val value: Double? = null,
    val unit: String? = null,
    val data: Map<String, HealthValue> = emptyMap(),
    val source: String? = null,
)

/** ISO 8601 with the local offset, so "the 13th" stays the user's 13th on the host. */
class HealthDateFormat(private val zone: ZoneId) {
    fun string(instant: Instant): String = format(instant, zone)
    fun day(instant: Instant): String = instant.atZone(zone).toLocalDate().toString()

    companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        fun format(instant: Instant, zone: ZoneId): String = FORMATTER.format(instant.atZone(zone).withNano(0))
    }
}

data class HealthBatch(
    val batchID: String = SealCrypto.compactUuid(),
    val computedAt: Instant,
    val deviceName: String,
    val zone: ZoneId,
    val categories: List<HealthCategory>,
    val detailed: List<HealthCategory>,
    val windows: List<HealthWindow> = emptyList(),
    val events: List<HealthEvent> = emptyList(),
    val deleted: List<String> = emptyList(),
) {
    val itemCount: Int get() = windows.sumOf { it.buckets.size } + events.size + deleted.size
    val isEmpty: Boolean get() = windows.isEmpty() && events.isEmpty() && deleted.isEmpty()

    fun json(): JsonObject {
        val format = HealthDateFormat(zone)
        fun values(data: Map<String, HealthValue>) = JsonObject(data.mapValues { it.value.json })
        return buildJsonObject {
            put("batch_id", batchID)
            put("computed_at", format.string(computedAt))
            put("device", buildJsonObject { put("name", deviceName); put("time_zone", zone.id) })
            put("categories", JsonArray(categories.map { it.raw }.sorted().map(::JsonPrimitive)))
            put("detailed", JsonArray(detailed.map { it.raw }.sorted().map(::JsonPrimitive)))
            put("windows", buildJsonArray {
                windows.forEach { window ->
                    add(buildJsonObject {
                        put("metric", window.metric)
                        put("grain", window.grain.raw)
                        put("unit", window.unit)
                        put("from", format.string(window.from))
                        put("to", format.string(window.to))
                        put("buckets", buildJsonArray {
                            window.buckets.forEach { bucket ->
                                add(buildJsonObject {
                                    put("start", format.string(bucket.start))
                                    put("end", format.string(bucket.end))
                                    bucket.sum?.let { put("sum", it) }
                                    bucket.min?.let { put("min", it) }
                                    bucket.avg?.let { put("avg", it) }
                                    bucket.max?.let { put("max", it) }
                                    bucket.date?.let { put("date", it) }
                                    bucket.source?.let { put("source", it.take(120)) }
                                    if (bucket.data.isNotEmpty()) put("data", values(bucket.data))
                                })
                            }
                        })
                    })
                }
            })
            put("events", buildJsonArray {
                events.forEach { event ->
                    add(buildJsonObject {
                        put("id", event.id)
                        put("kind", event.kind.raw)
                        put("metric", event.metric)
                        put("start", format.string(event.start))
                        put("end", format.string(event.end))
                        event.value?.let { put("value", it) }
                        event.unit?.let { put("unit", it) }
                        event.source?.let { put("source", it.take(120)) }
                        if (event.data.isNotEmpty()) put("data", values(event.data))
                    })
                }
            })
            put("deleted", JsonArray(deleted.map(::JsonPrimitive)))
        }
    }

    /** Splits into batches the host takes, keeping each window whole. A window bigger than the limit goes alone. */
    fun split(limit: Int = HealthSyncLimits.MAX_ITEMS_PER_BATCH): List<HealthBatch> {
        val batches = mutableListOf<HealthBatch>()
        var windows = mutableListOf<HealthWindow>()
        var events = mutableListOf<HealthEvent>()
        var deleted = mutableListOf<String>()
        fun count() = windows.sumOf { it.buckets.size } + events.size + deleted.size
        fun flush() {
            if (windows.isNotEmpty() || events.isNotEmpty() || deleted.isNotEmpty()) {
                batches += copy(batchID = SealCrypto.compactUuid(), windows = windows, events = events, deleted = deleted)
            }
            windows = mutableListOf(); events = mutableListOf(); deleted = mutableListOf()
        }
        for (window in this.windows) {
            if (count() + window.buckets.size > limit) flush()
            windows += window
        }
        for (event in this.events) {
            if (count() + 1 > limit) flush()
            events += event
        }
        for (id in this.deleted) {
            if (count() + 1 > limit) flush()
            deleted += id
        }
        flush()
        return batches
    }
}

// endregion

// region Sleep

enum class HealthSleepStage(val raw: String) {
    IN_BED("in_bed"), ASLEEP("asleep"), CORE("core"), DEEP("deep"), REM("rem"), AWAKE("awake");

    val isAsleep: Boolean get() = this == ASLEEP || this == CORE || this == DEEP || this == REM
}

data class HealthSleepSegment(
    val id: String,
    val stage: HealthSleepStage,
    val start: Instant,
    val end: Instant,
    val source: String,
    /** Recorded by a watch. */
    val fromWatch: Boolean,
)

/**
 * The nightly summary rule: a night runs noon to noon and is named by the
 * morning it ends. One source per night: a watch first, otherwise the source
 * with the most time asleep. Fixed and deterministic (iOS HealthSleepNights).
 */
object HealthSleepNights {
    private fun startOfDay(instant: Instant, zone: ZoneId): ZonedDateTime = instant.atZone(zone).toLocalDate().atStartOfDay(zone)

    fun nightEnding(instant: Instant, zone: ZoneId): Instant {
        val day = startOfDay(instant, zone)
        val noon = day.plusHours(12).toInstant()
        return if (instant < noon) day.toInstant() else day.plusDays(1).toInstant()
    }

    /** Noon before the first morning to noon before the end. */
    fun window(firstMorning: Instant, endMorning: Instant, zone: ZoneId): Pair<Instant, Instant> =
        startOfDay(firstMorning, zone).minusHours(12).toInstant() to startOfDay(endMorning, zone).minusHours(12).toInstant()

    private fun seconds(segment: HealthSleepSegment): Double = Duration.between(segment.start, segment.end).toMillis() / 1000.0

    fun summaries(segments: List<HealthSleepSegment>, zone: ZoneId): List<HealthBucket> {
        val format = HealthDateFormat(zone)
        val byNight = segments.groupBy { nightEnding(it.start, zone) }
        return byNight.keys.sorted().mapNotNull { morning ->
            val (source, chosen) = chosenSource(byNight[morning].orEmpty()) ?: return@mapNotNull null
            val night = chosen.sortedBy { it.start }
            val first = night.firstOrNull() ?: return@mapNotNull null
            val last = night.maxByOrNull { it.end } ?: return@mapNotNull null
            fun minutes(stages: Set<HealthSleepStage>) = roundHalfAway(night.filter { it.stage in stages }.sumOf(::seconds) / 60)
            val asleep = minutes(setOf(HealthSleepStage.ASLEEP, HealthSleepStage.CORE, HealthSleepStage.DEEP, HealthSleepStage.REM))
            val explicitInBed = minutes(setOf(HealthSleepStage.IN_BED))
            val data = linkedMapOf<String, HealthValue>(
                "asleep" to HealthValue.Num(asleep),
                "in_bed" to HealthValue.Num(
                    if (explicitInBed > 0) explicitInBed else roundHalfAway(Duration.between(first.start, last.end).toMillis() / 60_000.0),
                ),
                "awake" to HealthValue.Num(minutes(setOf(HealthSleepStage.AWAKE))),
                "wake_count" to HealthValue.Num(night.count { it.stage == HealthSleepStage.AWAKE }.toDouble()),
                "bedtime" to HealthValue.Text(format.string(first.start)),
                "wake_time" to HealthValue.Text(format.string(last.end)),
            )
            if (night.any { it.stage == HealthSleepStage.CORE || it.stage == HealthSleepStage.DEEP || it.stage == HealthSleepStage.REM }) {
                data["core"] = HealthValue.Num(minutes(setOf(HealthSleepStage.CORE)))
                data["deep"] = HealthValue.Num(minutes(setOf(HealthSleepStage.DEEP)))
                data["rem"] = HealthValue.Num(minutes(setOf(HealthSleepStage.REM)))
            }
            val unspecified = minutes(setOf(HealthSleepStage.ASLEEP))
            if (unspecified > 0) data["unspecified"] = HealthValue.Num(unspecified)
            HealthBucket(start = first.start, end = last.end, date = format.day(morning), data = data, source = source)
        }
    }

    private fun chosenSource(segments: List<HealthSleepSegment>): Pair<String, List<HealthSleepSegment>>? {
        val bySource = segments.groupBy { it.source }
        // Ties fall back to the name (the first alphabetically), so the same data always picks the same source.
        val comparator = Comparator<Map.Entry<String, List<HealthSleepSegment>>> { left, right ->
            val l = score(left.value)
            val r = score(right.value)
            when {
                l.first != r.first -> l.first.compareTo(r.first)
                l.second != r.second -> l.second.compareTo(r.second)
                l.third != r.third -> l.third.compareTo(r.third)
                else -> right.key.compareTo(left.key)
            }
        }
        return bySource.entries.maxWithOrNull(comparator)?.let { it.key to it.value }
    }

    private fun score(group: List<HealthSleepSegment>): Triple<Int, Double, Double> =
        Triple(if (group.any { it.fromWatch }) 1 else 0, group.filter { it.stage.isAsleep }.sumOf(::seconds), group.sumOf(::seconds))
}

/** Discrete readings bucketed by hour or day into min, average and max. */
object HealthStats {
    data class Reading(val at: Instant, val value: Double)

    fun buckets(readings: List<Reading>, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket> {
        val inRange = readings.filter { it.at >= from && it.at < to }
        if (inRange.isEmpty()) return emptyList()
        return inRange.groupBy { bucketStart(it.at, grain, zone) }.toSortedMap().map { (start, group) ->
            val end = bucketEnd(start, grain, zone)
            val values = group.map { it.value }
            HealthBucket(start = start, end = end, min = values.min(), avg = values.average(), max = values.max())
        }
    }

    fun bucketStart(at: Instant, grain: HealthGrain, zone: ZoneId): Instant {
        val local = at.atZone(zone)
        return if (grain == HealthGrain.HOUR) local.withMinute(0).withSecond(0).withNano(0).toInstant()
        else local.toLocalDate().atStartOfDay(zone).toInstant()
    }

    fun bucketEnd(start: Instant, grain: HealthGrain, zone: ZoneId): Instant =
        if (grain == HealthGrain.HOUR) start.atZone(zone).plusHours(1).toInstant() else start.atZone(zone).plusDays(1).toInstant()
}

/**
 * Metrics Health Connect has no record for, made from the records it has:
 * cycling distance is the distance inside biking sessions, walking heart
 * rate the heart rate inside walking sessions, wrist temperature a skin
 * temperature record's baseline plus its deltas. Each still goes as the iOS
 * metric, in its unit and buckets.
 */
object HealthDerived {
    /** A part of a time range that falls in one hourly or daily bucket. */
    data class Slice(val bucketStart: Instant, val bucketEnd: Instant, val start: Instant, val end: Instant)

    /** Overlapping ranges joined, so two apps recording one ride count it once. Sorted by start. */
    fun merged(ranges: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for ((start, end) in ranges.filter { it.second > it.first }.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            else merged += start to end
        }
        return merged
    }

    /** `ranges` (merged first) cut at bucket boundaries, inside [from, to). */
    fun slices(ranges: List<Pair<Instant, Instant>>, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<Slice> {
        val slices = mutableListOf<Slice>()
        for ((rangeStart, rangeEnd) in merged(ranges)) {
            var start = maxOf(rangeStart, from)
            val end = minOf(rangeEnd, to)
            while (start < end) {
                val bucketStart = HealthStats.bucketStart(start, grain, zone)
                val bucketEnd = HealthStats.bucketEnd(bucketStart, grain, zone)
                val sliceEnd = minOf(end, bucketEnd)
                slices += Slice(bucketStart, bucketEnd, start, sliceEnd)
                start = sliceEnd
            }
        }
        return slices
    }

    /** One sum bucket per bucket that has a value; a slice without data (null) adds nothing. */
    fun sums(values: List<Pair<Slice, Double?>>): List<HealthBucket> =
        values.filter { it.second != null }.groupBy { it.first.bucketStart }.toSortedMap().map { (start, group) ->
            HealthBucket(start, group.first().first.bucketEnd, sum = group.sumOf { it.second ?: 0.0 })
        }

    /** The mean of the readings in [start, end], or null without any. */
    fun mean(readings: List<HealthStats.Reading>, start: Instant, end: Instant): Double? =
        readings.filter { it.at >= start && it.at <= end }.map { it.value }.takeIf { it.isNotEmpty() }?.average()

    /**
     * iOS sends Apple's sleeping wrist temperature, an absolute temperature
     * (the Health app shows it against a baseline, HealthKit stores the
     * value). Health Connect keeps a baseline and deviations from it, so the
     * value is the baseline plus the mean deviation. Without a baseline
     * there is no absolute value to send, and a bare deviation would read as
     * a temperature near 0 °C.
     */
    fun skinTemperature(baselineCelsius: Double?, deltasCelsius: List<Double>): Double? {
        if (baselineCelsius == null || deltasCelsius.isEmpty()) return null
        return baselineCelsius + deltasCelsius.average()
    }

    /**
     * A series record (heart rate, speed) goes as one reading per sample, as
     * iOS sends one per HealthKit sample. Each takes the record's id and its
     * index, so a deleted record's readings can be named (see [seriesIDs]).
     */
    fun seriesEvents(id: String, metric: HealthMetric, source: String?, readings: List<HealthStats.Reading>): List<HealthEvent> =
        readings.mapIndexed { index, reading ->
            HealthEvent("$id.$index", HealthEventKind.SAMPLE, metric.raw, reading.at, reading.at, reading.value, metric.unit, source = source)
        }

    /** The readings a deleted series record had sent. Health Connect names only the record, so up to [MAX_SERIES_SAMPLES]. */
    fun seriesIDs(id: String): List<String> = (0 until MAX_SERIES_SAMPLES).map { "$id.$it" }

    /** A derived reading made from a whole session: the session's id and the metric, apart from the workout's own id. */
    fun sessionID(id: String, metric: HealthMetric): String = "$id.${metric.raw}"

    /**
     * Deletions name this many readings of a series record. A record with
     * more (rare: apps write a series an hour or so long) keeps its later
     * readings on the host after Health Connect deletes it.
     */
    const val MAX_SERIES_SAMPLES = 1_000
}

// endregion

/** The agreed health key (iOS `HealthSyncCrypto.SessionKey`), base64url in storage. */
@Serializable
data class HealthSessionKey(
    val kid: String,
    val key: String,
    val voiceRequestKey: String? = null,
    val voiceAnswerKey: String? = null,
) {
    val keyBytes: ByteArray get() = SealCrypto.base64UrlDecoded(key) ?: ByteArray(0)
    val readsByVoice: Boolean get() = voiceRequestKey != null && voiceAnswerKey != null
}

/** iOS HealthSyncCrypto: must match health_store.py in the notifier. */
object HealthSyncCrypto {
    const val SALT = "conduit-health-v1"
    const val INGEST_INFO = "conduit-health-v1 ingest device-to-host"
    const val AAD_TAG = "conduit-health/1"
    const val VOICE_REQUEST_INFO = "conduit-health-v1 voice device-to-host"
    const val VOICE_ANSWER_INFO = "conduit-health-v1 voice host-to-device"
    const val KID_LABEL = "conduit-health-kid\n"

    fun keyID(devicePublic: ByteArray, hostPublic: ByteArray) = SealCrypto.keyID(KID_LABEL, devicePublic, hostPublic)

    fun agree(devicePrivate: ByteArray, deviceID: String, hostPublicKey: String, kid: String): HealthSessionKey {
        val (ingest, request, answer) = SealCrypto.agree(
            devicePrivate, deviceID, hostPublicKey, kid, KID_LABEL, SALT, listOf(INGEST_INFO, VOICE_REQUEST_INFO, VOICE_ANSWER_INFO),
        )
        return HealthSessionKey(kid, SealCrypto.base64Url(ingest), SealCrypto.base64Url(request), SealCrypto.base64Url(answer))
    }

    fun aad(kid: String, deviceID: String, batchID: String) = SealCrypto.aad(AAD_TAG, "ingest", "kid=$kid", "dev=$deviceID", "batch=$batchID")

    fun envelope(plaintext: ByteArray, key: HealthSessionKey, deviceID: String, batchID: String, nonce: ByteArray = SealCrypto.randomBytes(12)): JsonObject {
        val sealed = SealCrypto.seal(plaintext, key.keyBytes, aad(key.kid, deviceID, batchID), nonce)
        return buildJsonObject {
            put("v", 1)
            put("device_id", deviceID)
            put("kid", key.kid)
            put("batch_id", batchID)
            put("nonce", sealed.nonce)
            put("ct", sealed.ct)
        }
    }

    fun voiceRequest(tool: String, plaintext: ByteArray, key: HealthSessionKey, deviceID: String, callID: String): JsonObject {
        val requestKey = key.voiceRequestKey?.let(SealCrypto::base64UrlDecoded) ?: throw SealCrypto.Failure("noVoiceKey")
        return VoiceReadSeal.request(AAD_TAG, tool, plaintext, requestKey, key.kid, deviceID, callID)
    }

    fun openVoiceAnswer(response: JsonObject, tool: String, key: HealthSessionKey, deviceID: String, callID: String): JsonElement {
        val answerKey = key.voiceAnswerKey?.let(SealCrypto::base64UrlDecoded) ?: throw SealCrypto.Failure("noVoiceKey")
        return VoiceReadSeal.openAnswer(response, AAD_TAG, tool, answerKey, key.kid, deviceID, callID)
    }
}

/** What a health pass remembers between runs: anchors, a date cursor and the host's store generation. No values. */
@Serializable
data class HealthSyncState(
    val deviceID: String = SealCrypto.compactUuid(),
    val destination: SyncDestination? = null,
    val generation: String? = null,
    /** Epoch ms. */
    val historyStart: Long? = null,
    val history: String? = null,
    /** The oldest day already sent (epoch ms). */
    val backfillCursor: Long? = null,
    val anchors: Map<String, String> = emptyMap(),
    val lastSync: Long? = null,
    val lastAttempt: Long? = null,
    val pause: SyncPause? = null,
) {
    fun startedOver(): HealthSyncState = HealthSyncState(deviceID = deviceID)

    fun encode(): String = ConduitJson.encodeToString(serializer(), this)

    companion object {
        /** Unreadable (an older shape, a damaged file): start over, as the same device. */
        fun decode(text: String?): HealthSyncState {
            if (text == null) return HealthSyncState()
            runCatching { return ConduitJson.decodeFromString(serializer(), text) }
            return HealthSyncState(deviceID = salvageDeviceID(text) ?: SealCrypto.compactUuid())
        }

        fun salvageDeviceID(text: String): String? =
            Regex("\"deviceID\"\\s*:\\s*\"([A-Za-z0-9_-]{4,64})\"").find(text)?.groupValues?.get(1)
    }
}

/** Parses a host date: ISO 8601 with an offset (fractional seconds allowed). */
fun parseIsoInstant(text: String): Instant? =
    runCatching { java.time.OffsetDateTime.parse(text.trim()).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text.trim()) }.getOrNull()

/** Midnight of a local date in `zone`. */
fun LocalDate.startIn(zone: ZoneId): Instant = atStartOfDay(zone).toInstant()
