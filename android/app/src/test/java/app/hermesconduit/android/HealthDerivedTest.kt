package app.hermesconduit.android

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.hermesconduit.android.model.HealthDerived
import app.hermesconduit.android.model.HealthEventKind
import app.hermesconduit.android.model.HealthGrain
import app.hermesconduit.android.model.HealthMetric
import app.hermesconduit.android.model.HealthStats
import app.hermesconduit.android.ui.health.HealthConnectSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HealthDerivedTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun overlappingSessionsCountOnce() {
        val merged = HealthDerived.merged(
            listOf(
                date("2026-10-13T10:30:00Z") to date("2026-10-13T11:00:00Z"),
                date("2026-10-13T08:00:00Z") to date("2026-10-13T09:00:00Z"),
                date("2026-10-13T08:30:00Z") to date("2026-10-13T09:30:00Z"),
                // Empty: dropped.
                date("2026-10-13T12:00:00Z") to date("2026-10-13T12:00:00Z"),
            ),
        )
        assertEquals(
            listOf(date("2026-10-13T08:00:00Z") to date("2026-10-13T09:30:00Z"), date("2026-10-13T10:30:00Z") to date("2026-10-13T11:00:00Z")),
            merged,
        )
    }

    @Test
    fun aRideIsCutAtLocalHoursAndDays() {
        // 07:40 to 09:10 Berlin time (UTC+2), from a range starting at 08:00 local.
        val ride = listOf(date("2026-10-13T05:40:00Z") to date("2026-10-13T07:10:00Z"))
        val hourly = HealthDerived.slices(ride, HealthGrain.HOUR, date("2026-10-13T06:00:00Z"), date("2026-10-14T00:00:00Z"), zone)
        assertEquals(2, hourly.size)
        assertEquals(HealthDerived.Slice(date("2026-10-13T06:00:00Z"), date("2026-10-13T07:00:00Z"), date("2026-10-13T06:00:00Z"), date("2026-10-13T07:00:00Z")), hourly[0])
        assertEquals(HealthDerived.Slice(date("2026-10-13T07:00:00Z"), date("2026-10-13T08:00:00Z"), date("2026-10-13T07:00:00Z"), date("2026-10-13T07:10:00Z")), hourly[1])

        // Across local midnight: two days.
        val night = listOf(date("2026-10-13T21:30:00Z") to date("2026-10-13T22:30:00Z"))
        val daily = HealthDerived.slices(night, HealthGrain.DAY, date("2026-10-12T22:00:00Z"), date("2026-10-14T22:00:00Z"), zone)
        assertEquals(listOf(date("2026-10-12T22:00:00Z"), date("2026-10-13T22:00:00Z")), daily.map { it.bucketStart })
        assertEquals(listOf(date("2026-10-13T22:00:00Z"), date("2026-10-14T22:00:00Z")), daily.map { it.bucketEnd })
        assertEquals(date("2026-10-13T22:00:00Z"), daily[0].end)
        assertEquals(date("2026-10-13T22:00:00Z"), daily[1].start)
    }

    @Test
    fun sumsAddSlicesPerBucketAndSkipThoseWithoutData() {
        val rides = listOf(
            date("2026-10-13T06:00:00Z") to date("2026-10-13T06:20:00Z"),
            date("2026-10-13T06:40:00Z") to date("2026-10-13T07:20:00Z"),
            date("2026-10-13T09:00:00Z") to date("2026-10-13T09:30:00Z"),
        )
        val slices = HealthDerived.slices(rides, HealthGrain.HOUR, date("2026-10-12T22:00:00Z"), date("2026-10-13T22:00:00Z"), zone)
        val meters = listOf(3_000.0, 4_000.0, 5_000.0, null)
        val buckets = HealthDerived.sums(slices.zip(meters))
        assertEquals(2, buckets.size)
        assertEquals(date("2026-10-13T06:00:00Z"), buckets[0].start)
        assertEquals(date("2026-10-13T07:00:00Z"), buckets[0].end)
        assertEquals(7_000.0, buckets[0].sum!!, 0.0)
        assertEquals(5_000.0, buckets[1].sum!!, 0.0)
        assertNull(buckets[0].avg)
    }

    @Test
    fun walkingHeartRateIsTheMeanInsideTheWalk() {
        val readings = listOf(
            HealthStats.Reading(date("2026-10-13T07:59:00Z"), 60.0),
            HealthStats.Reading(date("2026-10-13T08:00:00Z"), 90.0),
            HealthStats.Reading(date("2026-10-13T08:15:00Z"), 100.0),
            HealthStats.Reading(date("2026-10-13T08:30:00Z"), 110.0),
            HealthStats.Reading(date("2026-10-13T08:31:00Z"), 150.0),
        )
        assertEquals(100.0, HealthDerived.mean(readings, date("2026-10-13T08:00:00Z"), date("2026-10-13T08:30:00Z"))!!, 1e-9)
        assertNull(HealthDerived.mean(readings, date("2026-10-13T12:00:00Z"), date("2026-10-13T13:00:00Z")))

        // Two walks a day: one daily bucket, as iOS sends walking heart rate daily only.
        assertFalse(HealthMetric.WALKING_HEART_RATE_AVERAGE.hasHourlyBuckets)
        val walks = listOf(HealthStats.Reading(date("2026-10-13T06:00:00Z"), 100.0), HealthStats.Reading(date("2026-10-13T16:00:00Z"), 110.0))
        val daily = HealthStats.buckets(walks, HealthGrain.DAY, date("2026-10-12T22:00:00Z"), date("2026-10-13T22:00:00Z"), zone)
        assertEquals(1, daily.size)
        assertEquals(105.0, daily[0].avg!!, 1e-9)
        assertEquals(100.0, daily[0].min!!, 0.0)
        assertEquals(110.0, daily[0].max!!, 0.0)
    }

    @Test
    fun wristTemperatureIsTheBaselinePlusTheMeanDeviation() {
        assertEquals(34.75, HealthDerived.skinTemperature(34.5, listOf(0.1, 0.4))!!, 1e-9)
        assertEquals(34.2, HealthDerived.skinTemperature(34.5, listOf(-0.3))!!, 1e-9)
        assertNull("No baseline: no absolute temperature", HealthDerived.skinTemperature(null, listOf(0.2)))
        assertNull(HealthDerived.skinTemperature(34.5, emptyList()))
        assertEquals("degC", HealthMetric.WRIST_TEMPERATURE.unit)
    }

    @Test
    fun aSeriesGoesAsOneReadingPerSample() {
        val readings = listOf(HealthStats.Reading(date("2026-10-13T08:00:00Z"), 72.0), HealthStats.Reading(date("2026-10-13T08:01:00Z"), 75.0))
        val events = HealthDerived.seriesEvents("rec", HealthMetric.HEART_RATE, "Watch", readings)
        assertEquals(listOf("rec.0", "rec.1"), events.map { it.id })
        assertTrue(events.all { it.kind == HealthEventKind.SAMPLE && it.metric == "heart_rate" && it.unit == "count/min" && it.source == "Watch" })
        assertEquals(listOf(72.0, 75.0), events.map { it.value })
        assertEquals(date("2026-10-13T08:01:00Z"), events[1].start)
        assertEquals(events[1].start, events[1].end)
        assertTrue(events.all { it.data.isEmpty() })

        val deleted = HealthDerived.seriesIDs("rec")
        assertEquals(HealthDerived.MAX_SERIES_SAMPLES, deleted.size)
        assertTrue(deleted.containsAll(events.map { it.id }))
        assertEquals("rec.distance_cycling", HealthDerived.sessionID("rec", HealthMetric.DISTANCE_CYCLING))
    }

    @Test
    fun sessionTypesForDerivedMetrics() {
        assertTrue(ExerciseSessionRecord.EXERCISE_TYPE_BIKING in HealthConnectSource.CYCLING_TYPES)
        assertTrue(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY in HealthConnectSource.CYCLING_TYPES)
        assertEquals(setOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING), HealthConnectSource.WALKING_TYPES)
        assertEquals(setOf(HealthMetric.DISTANCE_CYCLING, HealthMetric.WALKING_HEART_RATE_AVERAGE), HealthConnectSource.SESSION_METRICS)
    }

    private fun date(text: String): Instant = Instant.parse(text)
}
