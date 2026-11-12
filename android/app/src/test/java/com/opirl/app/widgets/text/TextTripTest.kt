package com.opirl.app.widgets.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextTripTest {
    private fun fix(latitude: Double, altitude: Double = 100.0, accuracy: Double = 5.0, vertical: Double? = 3.0) =
        Fix(latitude, 0.0, altitude, accuracy, vertical)

    @Test
    fun movementInsideTheAccuracyIsNotCounted() {
        val trip = Trip(0)
        trip.update(fix(0.0))
        trip.update(fix(0.00002))
        assertEquals(0.0, trip.distance)
        trip.update(fix(0.0001))
        assertEquals(11.12, trip.distance, 0.02)
        assertEquals(trip.distance, trip.splitDistance)
    }

    @Test
    fun altitudeChangesCountOnceTheyPassTheAccuracyAndThreeMeters() {
        val trip = Trip(0)
        trip.update(fix(0.0, altitude = 100.0))
        trip.update(fix(0.0, altitude = 101.0))
        assertEquals(0.0, trip.ascent)
        trip.update(fix(0.0, altitude = 104.0))
        assertEquals(4.0, trip.ascent)
        trip.update(fix(0.0, altitude = 99.0))
        assertEquals(5.0, trip.descent)
        assertEquals(4.0, trip.splitAscent)
        val blind = Trip(0)
        blind.update(fix(0.0, vertical = null))
        blind.update(fix(0.0, altitude = 150.0, vertical = null))
        assertEquals(0.0, blind.ascent)
    }

    @Test
    fun theSlopeIsSmoothedOverTheLastFixes() {
        val trip = Trip(0)
        trip.update(fix(0.0, altitude = 100.0))
        trip.update(fix(0.001, altitude = 105.0))
        assertEquals(0.0, trip.slope)
        trip.update(fix(0.002, altitude = 110.0))
        assertEquals(1.35, trip.slope, 0.02)
    }

    @Test
    fun averageSpeedIsTheDistanceOverTheTimeSinceTheStart() {
        val trip = Trip(1_000)
        assertEquals(0.0, trip.averageSpeed(1_000))
        trip.update(fix(0.0))
        trip.update(fix(0.001))
        assertEquals(1.112, trip.averageSpeed(101_000), 0.002)
    }

    @Test
    fun aSplitKeepsTheTotalsAndAResetStartsOver() {
        val trip = Trip(0)
        trip.update(fix(0.0, altitude = 100.0))
        trip.update(fix(0.001, altitude = 110.0))
        trip.update(fix(0.002, altitude = 120.0))
        trip.split()
        assertEquals(0.0, trip.splitDistance)
        assertEquals(0.0, trip.splitAscent)
        assertEquals(222.4, trip.distance, 0.1)
        assertEquals(20.0, trip.ascent)
        trip.reset(50_000)
        assertEquals(0.0, trip.distance)
        assertEquals(0.0, trip.ascent)
        assertEquals(0.0, trip.slope)
        trip.update(fix(0.003))
        assertEquals(0.0, trip.distance)
        assertEquals(0.0, trip.averageSpeed(50_000))
    }

    @Test
    fun theRecentGForceMaximumFadesOverTenSeconds() {
        val tracker = GForceTracker()
        assertNull(tracker.latest())
        tracker.restart()
        tracker.onAcceleration(0.0, 0.0, 1.0, 1_000_000_000)
        tracker.onAcceleration(0.0, 0.0, 2.0, 1_100_000_000)
        assertEquals(2.0, tracker.latest()?.recentMax)
        for (step in 1..50) tracker.onAcceleration(0.0, 0.0, 1.0, 1_100_000_000 + step * 100_000_000L)
        assertEquals(1.875, tracker.latest()?.recentMax ?: 0.0, 0.001)
        for (step in 51..120) tracker.onAcceleration(0.0, 0.0, 1.0, 1_100_000_000 + step * 100_000_000L)
        assertEquals(1.0, tracker.latest()?.recentMax)
        assertEquals(2.0, tracker.latest()?.max)
        assertEquals(1.0, tracker.latest()?.now)
    }
}
