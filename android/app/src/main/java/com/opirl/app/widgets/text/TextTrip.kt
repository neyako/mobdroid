package com.opirl.app.widgets.text

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Fix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double?,
)

fun distanceBetween(from: Fix, to: Fix): Double {
    val radius = 6_371_000.0
    val latitude = Math.toRadians(to.latitude - from.latitude)
    val longitude = Math.toRadians(to.longitude - from.longitude)
    val a = sin(latitude / 2) * sin(latitude / 2) +
        cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * sin(longitude / 2) * sin(longitude / 2)
    return 2 * radius * atan2(sqrt(a), sqrt(1 - a))
}

class TripSnapshot(
    val distance: Double,
    val splitDistance: Double,
    val ascent: Double,
    val descent: Double,
    val splitAscent: Double,
    val splitDescent: Double,
    val slope: Double,
    val averageSpeed: Double,
)

class Trip(private var startTime: Long) {
    var distance = 0.0
        private set
    var splitDistance = 0.0
        private set
    var ascent = 0.0
        private set
    var descent = 0.0
        private set
    var splitAscent = 0.0
        private set
    var splitDescent = 0.0
        private set
    var slope = 0.0
        private set
    private var last: Fix? = null
    private var altitudeReference: Double? = null
    private var previousSlopeAltitude: Double? = null
    private var previousSlopeDistance = 0.0
    private var startDistance = 0.0

    fun averageSpeed(now: Long): Double {
        val elapsed = (now - startTime) / 1000.0
        return if (elapsed > 0) (distance - startDistance) / elapsed else 0.0
    }

    fun snapshot(now: Long) = TripSnapshot(distance, splitDistance, ascent, descent, splitAscent, splitDescent, slope, averageSpeed(now))

    fun update(fix: Fix) {
        updateDistance(fix)
        updateAltitude(fix)
        updateSlope(fix)
    }

    private fun updateDistance(fix: Fix) {
        val previous = last
        if (previous == null) {
            last = fix
            return
        }
        val moved = distanceBetween(previous, fix)
        if (moved > previous.horizontalAccuracy) {
            distance += moved
            splitDistance += moved
            last = fix
        }
    }

    private fun updateAltitude(fix: Fix) {
        val accuracy = fix.verticalAccuracy ?: return
        if (accuracy <= 0) return
        val reference = altitudeReference
        if (reference == null) {
            altitudeReference = fix.altitude
            return
        }
        val delta = fix.altitude - reference
        if (abs(delta) < maxOf(accuracy, 3.0)) return
        if (delta > 0) {
            ascent += delta
            splitAscent += delta
        } else {
            descent -= delta
            splitDescent -= delta
        }
        altitudeReference = fix.altitude
    }

    private fun updateSlope(fix: Fix) {
        val deltaDistance = distance - previousSlopeDistance
        if (deltaDistance == 0.0) return
        previousSlopeDistance = distance
        val deltaAltitude = fix.altitude - (previousSlopeAltitude ?: fix.altitude)
        previousSlopeAltitude = fix.altitude
        slope = 0.7 * slope + 0.3 * (100 * deltaAltitude / deltaDistance)
    }

    fun split() {
        splitDistance = 0.0
        splitAscent = 0.0
        splitDescent = 0.0
    }

    fun reset(now: Long) {
        distance = 0.0
        ascent = 0.0
        descent = 0.0
        last = null
        altitudeReference = null
        slope = 0.0
        previousSlopeAltitude = null
        previousSlopeDistance = 0.0
        startTime = now
        startDistance = 0.0
        split()
    }
}

class GForceTracker {
    private var now = 0.0
    private var max = 0.0
    private var recentMax = 0.0
    private var recentMaxNow = 0.0
    private var progress = 0.0
    private var previous = 0L
    private var seen = false

    @Synchronized
    fun restart() {
        recentMax = 0.0
        recentMaxNow = 0.0
        progress = 0.0
        previous = 0
    }

    @Synchronized
    fun onAcceleration(x: Double, y: Double, z: Double, timestampNanos: Long) {
        now = sqrt(x * x + y * y + z * z)
        seen = true
        if (now > max) max = now
        if (now > recentMaxNow) {
            progress = 0.0
            recentMax = now
        } else if (previous != 0L) {
            progress = minOf(progress + (timestampNanos - previous).coerceIn(0, 1_000_000_000) / 1e9 / RECENT_SECONDS, 1.0)
        }
        previous = timestampNanos
        val eased = progress * progress * progress * progress
        recentMaxNow = maxOf((1 - eased) * recentMax, now)
    }

    @Synchronized
    fun latest(): GForce? = if (seen) GForce(now, recentMaxNow, max) else null

    private companion object {
        const val RECENT_SECONDS = 10.0
    }
}
