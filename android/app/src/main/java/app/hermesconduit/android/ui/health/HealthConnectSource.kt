package app.hermesconduit.android.ui.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.hermesconduit.android.model.HealthBucket
import app.hermesconduit.android.model.HealthCategory
import app.hermesconduit.android.model.HealthDerived
import app.hermesconduit.android.model.HealthEvent
import app.hermesconduit.android.model.HealthEventKind
import app.hermesconduit.android.model.HealthGrain
import app.hermesconduit.android.model.HealthMetric
import app.hermesconduit.android.model.HealthSleepSegment
import app.hermesconduit.android.model.HealthSleepStage
import app.hermesconduit.android.model.HealthStats
import app.hermesconduit.android.model.HealthValue
import app.hermesconduit.android.model.roundHalfAway
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads Health Connect for health sync, the Android counterpart of iOS
 * HealthKitSource: totals through Health Connect's own aggregation (which
 * merges apps by the user's priority), discrete metrics bucketed here from
 * their readings, and workouts, sleep and raw readings through changes
 * tokens, which also report deletions. Read-only: Conduit never writes.
 *
 * A stream's anchor is `r\n<changes token>\n<page token>` while the first
 * read pages through what's there, then `c\n<changes token>`.
 */
class HealthConnectSource(private val context: Context) : HealthDataSource {
    private val labels = mutableMapOf<String, String>()

    override val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    // region Types and permissions

    /** The record a metric's readings, and the changes of its detailed readings, come from. */
    private fun recordType(metric: HealthMetric): KClass<out Record>? = when (metric) {
        HealthMetric.STEPS -> StepsRecord::class
        HealthMetric.DISTANCE_WALKING_RUNNING -> DistanceRecord::class
        HealthMetric.ACTIVE_ENERGY -> ActiveCaloriesBurnedRecord::class
        HealthMetric.EXERCISE_TIME -> ExerciseSessionRecord::class
        HealthMetric.FLIGHTS_CLIMBED -> FloorsClimbedRecord::class
        HealthMetric.HEART_RATE -> HeartRateRecord::class
        HealthMetric.RESTING_HEART_RATE -> RestingHeartRateRecord::class
        HealthMetric.HEART_RATE_VARIABILITY -> HeartRateVariabilityRmssdRecord::class
        HealthMetric.OXYGEN_SATURATION -> OxygenSaturationRecord::class
        HealthMetric.RESPIRATORY_RATE -> RespiratoryRateRecord::class
        HealthMetric.BLOOD_PRESSURE_SYSTOLIC, HealthMetric.BLOOD_PRESSURE_DIASTOLIC -> BloodPressureRecord::class
        HealthMetric.BODY_MASS -> WeightRecord::class
        HealthMetric.BODY_FAT_PERCENTAGE -> BodyFatRecord::class
        HealthMetric.HEIGHT -> HeightRecord::class
        HealthMetric.VO2_MAX -> Vo2MaxRecord::class
        HealthMetric.WALKING_SPEED -> SpeedRecord::class
        HealthMetric.WRIST_TEMPERATURE -> SkinTemperatureRecord::class
        // Made from sessions (HealthDerived): a ride's or walk's changes come with its session.
        HealthMetric.DISTANCE_CYCLING, HealthMetric.WALKING_HEART_RATE_AVERAGE -> ExerciseSessionRecord::class
        // Health Connect has no record for these: no stand hours (Apple Watch only), and no six-minute
        // walk test, neither as a record nor as an exercise type.
        HealthMetric.STAND_TIME, HealthMetric.SIX_MINUTE_WALK_DISTANCE -> null
    }

    /** Every record a metric reads. */
    private fun readTypes(metric: HealthMetric): List<KClass<out Record>> = when (metric) {
        HealthMetric.DISTANCE_CYCLING -> listOf(ExerciseSessionRecord::class, DistanceRecord::class)
        HealthMetric.WALKING_HEART_RATE_AVERAGE -> listOf(ExerciseSessionRecord::class, HeartRateRecord::class)
        else -> listOfNotNull(recordType(metric))
    }

    /** The read permissions sharing `categories` asks for, plus background and history reads. */
    fun permissions(categories: Set<HealthCategory>): Set<String> {
        val types = mutableSetOf<KClass<out Record>>()
        for (category in categories) {
            category.metrics.flatMapTo(types, ::readTypes)
            when (category) {
                HealthCategory.SLEEP -> types += SleepSessionRecord::class
                HealthCategory.WORKOUTS -> types += listOf(
                    ExerciseSessionRecord::class, ActiveCaloriesBurnedRecord::class, DistanceRecord::class, HeartRateRecord::class,
                )
                else -> Unit
            }
        }
        // Skin temperature is newer than Health Connect on some phones.
        if (!feature(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) types -= SkinTemperatureRecord::class
        val permissions = types.map { HealthPermission.getReadPermission(it) }.toMutableSet()
        if (feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        if (feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        return permissions
    }

    private fun feature(id: Int): Boolean = runCatching {
        client.features.getFeatureStatus(id) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    suspend fun granted(): Set<String> = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    private suspend fun readable(metric: HealthMetric): Boolean {
        val types = readTypes(metric)
        val granted = granted()
        return types.isNotEmpty() && types.all { HealthPermission.getReadPermission(it) in granted }
    }

    // endregion

    // region HealthDataSource

    override suspend fun buckets(metric: HealthMetric, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket> {
        if (!readable(metric)) return emptyList()
        return when {
            metric == HealthMetric.DISTANCE_CYCLING -> cyclingDistance(grain, from, to, zone)
            metric == HealthMetric.WALKING_HEART_RATE_AVERAGE -> HealthStats.buckets(walkingHeartRates(from, to), grain, from, to, zone)
            metric.aggregation == app.hermesconduit.android.model.HealthAggregation.SUM -> sums(metric, grain, from, to, zone)
            else -> HealthStats.buckets(readings(metric, from, to), grain, from, to, zone)
        }
    }

    /** Sessions of `types` overlapping [from, to); a session may have started the day before. */
    private suspend fun sessions(types: Set<Int>, from: Instant, to: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, from.minus(Duration.ofDays(1)), to)
            .filter { it.exerciseType in types && it.endTime > from && it.startTime < to }

    /**
     * iOS's cycling distance, from Health Connect's distance inside biking
     * sessions: Health Connect's own total for each part of a ride in a
     * bucket, so apps are merged by the user's priority as in the other sums.
     */
    private suspend fun cyclingDistance(grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket> {
        val rides = sessions(CYCLING_TYPES, from, to).map { it.startTime to it.endTime }
        return HealthDerived.sums(HealthDerived.slices(rides, grain, from, to, zone).map { it to distance(it.start, it.end) })
    }

    private suspend fun distance(from: Instant, to: Instant): Double? =
        client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(from, to)))[DistanceRecord.DISTANCE_TOTAL]?.inMeters

    /**
     * An approximation of iOS's walking heart rate average, which Apple
     * computes from heart rate while the user walks during the day: here
     * the mean heart rate during each walking session (overlapping sessions
     * as one), as a reading at the session's start. Walks nobody recorded
     * as a session don't count, and hikes are left out (climbing raises
     * the heart rate).
     */
    private suspend fun walkingHeartRates(from: Instant, to: Instant): List<HealthStats.Reading> =
        HealthDerived.merged(sessions(WALKING_TYPES, from, to).map { it.startTime to it.endTime }).mapNotNull { (start, end) ->
            meanHeartRate(start, end)?.let { HealthStats.Reading(start, it) }
        }

    private suspend fun meanHeartRate(from: Instant, to: Instant): Double? =
        HealthDerived.mean(readAll(HeartRateRecord::class, from, to).flatMap { readingsOf(HealthMetric.HEART_RATE, it) }, from, to)

    private fun sumMetric(metric: HealthMetric): AggregateMetric<*>? = when (metric) {
        HealthMetric.STEPS -> StepsRecord.COUNT_TOTAL
        HealthMetric.DISTANCE_WALKING_RUNNING -> DistanceRecord.DISTANCE_TOTAL
        HealthMetric.ACTIVE_ENERGY -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        HealthMetric.EXERCISE_TIME -> ExerciseSessionRecord.EXERCISE_DURATION_TOTAL
        HealthMetric.FLIGHTS_CLIMBED -> FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        else -> null
    }

    private fun sumValue(value: Any?): Double? = when (value) {
        is Long -> value.toDouble()
        is Double -> value
        is androidx.health.connect.client.units.Length -> value.inMeters
        is androidx.health.connect.client.units.Energy -> value.inKilocalories
        is Duration -> value.toMillis() / 60_000.0
        else -> null
    }

    private suspend fun sums(metric: HealthMetric, grain: HealthGrain, from: Instant, to: Instant, zone: ZoneId): List<HealthBucket> {
        val aggregate = sumMetric(metric) ?: return emptyList()
        return if (grain == HealthGrain.HOUR) {
            client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(setOf(aggregate), TimeRangeFilter.between(from, to), Duration.ofHours(1)),
            ).mapNotNull { group ->
                val sum = sumValue(group.result[aggregate]) ?: return@mapNotNull null
                HealthBucket(group.startTime, group.endTime, sum = sum)
            }
        } else {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(aggregate),
                    TimeRangeFilter.between(from.atZone(zone).toLocalDateTime(), to.atZone(zone).toLocalDateTime()),
                    Period.ofDays(1),
                ),
            ).mapNotNull { group ->
                val sum = sumValue(group.result[aggregate]) ?: return@mapNotNull null
                HealthBucket(group.startTime.atZone(zone).toInstant(), group.endTime.atZone(zone).toInstant(), sum = sum)
            }
        }
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val all = mutableListOf<T>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1_000, pageToken = page))
            all += response.records
            page = response.pageToken
        } while (page != null && all.size < 50_000)
        return all
    }

    /** Discrete readings of a metric, in the host's unit. */
    private suspend fun readings(metric: HealthMetric, from: Instant, to: Instant): List<HealthStats.Reading> {
        val type = recordType(metric) ?: return emptyList()
        return readAll(type, from, to).flatMap { record -> readingsOf(metric, record) }
    }

    private fun readingsOf(metric: HealthMetric, record: Record): List<HealthStats.Reading> = when (record) {
        is HeartRateRecord -> record.samples.map { HealthStats.Reading(it.time, it.beatsPerMinute.toDouble()) }
        is RestingHeartRateRecord -> listOf(HealthStats.Reading(record.time, record.beatsPerMinute.toDouble()))
        is HeartRateVariabilityRmssdRecord -> listOf(HealthStats.Reading(record.time, record.heartRateVariabilityMillis))
        is OxygenSaturationRecord -> listOf(HealthStats.Reading(record.time, record.percentage.value))
        is RespiratoryRateRecord -> listOf(HealthStats.Reading(record.time, record.rate))
        is BloodPressureRecord -> listOf(
            HealthStats.Reading(
                record.time,
                if (metric == HealthMetric.BLOOD_PRESSURE_SYSTOLIC) record.systolic.inMillimetersOfMercury else record.diastolic.inMillimetersOfMercury,
            ),
        )
        is WeightRecord -> listOf(HealthStats.Reading(record.time, record.weight.inKilograms))
        is BodyFatRecord -> listOf(HealthStats.Reading(record.time, record.percentage.value))
        is HeightRecord -> listOf(HealthStats.Reading(record.time, record.height.inMeters))
        is Vo2MaxRecord -> listOf(HealthStats.Reading(record.time, record.vo2MillilitersPerMinuteKilogram))
        is SpeedRecord -> record.samples.map { HealthStats.Reading(it.time, it.speed.inMetersPerSecond) }
        // Any location (a ring's finger too): the comparable night-time skin temperature.
        is SkinTemperatureRecord -> listOfNotNull(
            HealthDerived.skinTemperature(record.baseline?.inCelsius, record.deltas.map { it.delta.inCelsius })?.let { HealthStats.Reading(record.startTime, it) },
        )
        else -> emptyList()
    }

    override suspend fun sleepSegments(from: Instant, to: Instant): List<HealthSleepSegment> {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted()) return emptyList()
        return readAll(SleepSessionRecord::class, from, to).filter { it.startTime >= from }.flatMap(::segments)
    }

    private fun segments(session: SleepSessionRecord): List<HealthSleepSegment> {
        val source = label(session.metadata.dataOrigin.packageName)
        val watch = session.metadata.device?.type == Device.TYPE_WATCH || session.metadata.device?.type == Device.TYPE_RING ||
            session.metadata.device?.type == Device.TYPE_FITNESS_BAND
        val stages = session.stages
        if (stages.isEmpty()) {
            return listOf(HealthSleepSegment("${session.metadata.id}.0", HealthSleepStage.ASLEEP, session.startTime, session.endTime, source, watch))
        }
        return stages.mapIndexedNotNull { index, stage ->
            val kind = when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthSleepStage.CORE
                SleepSessionRecord.STAGE_TYPE_DEEP -> HealthSleepStage.DEEP
                SleepSessionRecord.STAGE_TYPE_REM -> HealthSleepStage.REM
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> HealthSleepStage.ASLEEP
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthSleepStage.AWAKE
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> return@mapIndexedNotNull null
                else -> HealthSleepStage.IN_BED
            }
            HealthSleepSegment("${session.metadata.id}.$index", kind, stage.startTime, stage.endTime, source, watch)
        }
    }

    override suspend fun changes(stream: HealthEventStream, since: Instant, anchor: String?, limit: Int): HealthEventChanges {
        val type: KClass<out Record> = when (stream) {
            HealthEventStream.Workouts -> ExerciseSessionRecord::class
            HealthEventStream.Sleep -> SleepSessionRecord::class
            is HealthEventStream.Samples -> recordType(stream.metric) ?: return HealthEventChanges(emptyList(), emptyList(), anchor, false)
        }
        val readable = if (stream is HealthEventStream.Samples) readable(stream.metric) else HealthPermission.getReadPermission(type) in granted()
        if (!readable) return HealthEventChanges(emptyList(), emptyList(), anchor, false)
        val parts = anchor?.split('\n')
        if (parts == null || parts[0] == "r") {
            // First read: what's there now, page by page; changes after it come from the token taken first.
            val token = parts?.getOrNull(1) ?: client.getChangesToken(ChangesTokenRequest(setOf(type)))
            val response = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.after(since), pageSize = limit, pageToken = parts?.getOrNull(2)?.ifEmpty { null }),
            )
            val added = response.records.flatMap { events(stream, it) }
            val next = response.pageToken
            return HealthEventChanges(added, emptyList(), if (next != null) "r\n$token\n$next" else "c\n$token", next != null)
        }
        val response = client.getChanges(parts.getOrElse(1) { "" })
        if (response.changesTokenExpired) {
            // Tokens last about a month: read everything again (the host's writes are idempotent).
            return HealthEventChanges(emptyList(), emptyList(), null, true)
        }
        val added = mutableListOf<HealthEvent>()
        val deleted = mutableListOf<String>()
        for (change in response.changes) {
            when (change) {
                is UpsertionChange -> if (startOf(change.record) >= since) added += events(stream, change.record)
                is DeletionChange -> deleted += deletedIDs(stream, change.recordId)
            }
        }
        return HealthEventChanges(added, deleted, "c\n${response.nextChangesToken}", response.hasMore)
    }

    override fun earliestDate(): Instant = Instant.now().minus(Duration.ofDays(3650))

    // endregion

    // region Mapping

    private fun startOf(record: Record): Instant = when (record) {
        is ExerciseSessionRecord -> record.startTime
        is SleepSessionRecord -> record.startTime
        is HeartRateRecord -> record.startTime
        is SpeedRecord -> record.startTime
        is StepsRecord -> record.startTime
        is DistanceRecord -> record.startTime
        is ActiveCaloriesBurnedRecord -> record.startTime
        is FloorsClimbedRecord -> record.startTime
        is SkinTemperatureRecord -> record.startTime
        else -> readingsOf(HealthMetric.HEART_RATE, record).firstOrNull()?.at
            ?: (record as? BloodPressureRecord)?.time ?: Instant.EPOCH
    }

    /** A sleep session's stages and a series record's readings each have their own id; a deletion names the record only. */
    private fun deletedIDs(stream: HealthEventStream, id: String): List<String> = when {
        stream == HealthEventStream.Sleep -> (0 until MAX_STAGES).map { "$id.$it" }
        stream !is HealthEventStream.Samples -> listOf(id)
        stream.metric in SESSION_METRICS -> listOf(HealthDerived.sessionID(id, stream.metric))
        recordType(stream.metric) == HeartRateRecord::class || recordType(stream.metric) == SpeedRecord::class -> HealthDerived.seriesIDs(id)
        else -> listOf(id)
    }

    private suspend fun events(stream: HealthEventStream, record: Record): List<HealthEvent> = when (stream) {
        HealthEventStream.Workouts -> (record as? ExerciseSessionRecord)?.let { listOf(workout(it)) }.orEmpty()
        HealthEventStream.Sleep -> (record as? SleepSessionRecord)?.let { session ->
            segments(session).take(MAX_STAGES).map { segment ->
                HealthEvent(segment.id, HealthEventKind.SLEEP, "sleep", segment.start, segment.end,
                    data = mapOf("stage" to HealthValue.Text(segment.stage.raw)), source = segment.source)
            }
        }.orEmpty()
        is HealthEventStream.Samples -> samples(stream.metric, record)
    }

    private suspend fun samples(metric: HealthMetric, record: Record): List<HealthEvent> = when {
        metric in SESSION_METRICS -> listOfNotNull((record as? ExerciseSessionRecord)?.let { sessionSample(metric, it) })
        // One reading per sample, as iOS sends one per HealthKit sample.
        record is HeartRateRecord || record is SpeedRecord ->
            HealthDerived.seriesEvents(record.metadata.id, metric, label(record.metadata.dataOrigin.packageName), readingsOf(metric, record))
        else -> listOfNotNull(sample(metric, record))
    }

    /** A ride's distance or a walk's mean heart rate (see [cyclingDistance], [walkingHeartRates]); nothing for other sessions. */
    private suspend fun sessionSample(metric: HealthMetric, session: ExerciseSessionRecord): HealthEvent? {
        val value = when (metric) {
            HealthMetric.DISTANCE_CYCLING -> if (session.exerciseType in CYCLING_TYPES) distance(session.startTime, session.endTime) else null
            HealthMetric.WALKING_HEART_RATE_AVERAGE -> if (session.exerciseType in WALKING_TYPES) meanHeartRate(session.startTime, session.endTime) else null
            else -> null
        } ?: return null
        return HealthEvent(HealthDerived.sessionID(session.metadata.id, metric), HealthEventKind.SAMPLE, metric.raw, session.startTime, session.endTime,
            value, metric.unit, source = label(session.metadata.dataOrigin.packageName))
    }

    private fun sample(metric: HealthMetric, record: Record): HealthEvent? {
        val source = label(record.metadata.dataOrigin.packageName)
        val id = record.metadata.id
        fun event(start: Instant, end: Instant, value: Double, data: Map<String, HealthValue> = emptyMap()) =
            HealthEvent(id, HealthEventKind.SAMPLE, metric.raw, start, end, value, metric.unit, data, source)
        return when (record) {
            is StepsRecord -> event(record.startTime, record.endTime, record.count.toDouble())
            is DistanceRecord -> event(record.startTime, record.endTime, record.distance.inMeters)
            is ActiveCaloriesBurnedRecord -> event(record.startTime, record.endTime, record.energy.inKilocalories)
            is FloorsClimbedRecord -> event(record.startTime, record.endTime, record.floors)
            is ExerciseSessionRecord -> event(record.startTime, record.endTime, Duration.between(record.startTime, record.endTime).toMillis() / 60_000.0)
            is SkinTemperatureRecord -> readingsOf(metric, record).firstOrNull()?.let { event(record.startTime, record.endTime, it.value) }
            else -> readingsOf(metric, record).firstOrNull()?.let { event(it.at, it.at, it.value) }
        }
    }

    private suspend fun workout(session: ExerciseSessionRecord): HealthEvent {
        val data = linkedMapOf<String, HealthValue>(
            "activity" to HealthValue.Text(activityName(session.exerciseType)),
            "duration_min" to HealthValue.Num(roundHalfAway(Duration.between(session.startTime, session.endTime).toMillis() / 60_000.0 * 10) / 10),
        )
        runCatching {
            val result = client.aggregate(
                AggregateRequest(
                    setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, DistanceRecord.DISTANCE_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                    TimeRangeFilter.between(session.startTime, session.endTime),
                ),
            )
            result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { data["energy_kcal"] = HealthValue.Num(roundHalfAway(it.inKilocalories)) }
            result[DistanceRecord.DISTANCE_TOTAL]?.takeIf { it.inMeters > 0 }?.let { data["distance_m"] = HealthValue.Num(roundHalfAway(it.inMeters)) }
            result[HeartRateRecord.BPM_AVG]?.let { data["avg_heart_rate"] = HealthValue.Num(it.toDouble()) }
            result[HeartRateRecord.BPM_MAX]?.let { data["max_heart_rate"] = HealthValue.Num(it.toDouble()) }
        }
        return HealthEvent(session.metadata.id, HealthEventKind.WORKOUT, "workout", session.startTime, session.endTime,
            data = data, source = label(session.metadata.dataOrigin.packageName))
    }

    @Suppress("DEPRECATION")
    private fun label(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    // endregion

    companion object {
        const val MAX_STAGES = 64
        val CYCLING_TYPES = setOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)
        val WALKING_TYPES = setOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING)
        /** Metrics made from sessions, one reading per ride or walk. */
        val SESSION_METRICS = setOf(HealthMetric.DISTANCE_CYCLING, HealthMetric.WALKING_HEART_RATE_AVERAGE)

        fun activityName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength_training"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "functional_strength_training"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "stair_climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP, ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "cross_training"
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "mind_and_body"
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "flexibility"
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial_arts"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "snowboarding"
            ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "paddling"
            ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "surfing"
            ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "wheelchair"
            else -> "other_$type"
        }
    }
}
