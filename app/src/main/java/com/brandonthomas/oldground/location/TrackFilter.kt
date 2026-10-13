package com.brandonthomas.oldground.location

import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator

/**
 * Turns raw GPS fixes into a path that doesn't wander while you stand still.
 * A phone's position drifts by several metres even when it isn't moving, so a point is
 * only kept once you've moved farther than the fix's own uncertainty (and at least
 * [MIN_MOVE]), fixes the phone rates as poor are dropped, and the kept point is the
 * average of the last few fixes rather than the latest, jumpier one.
 */
class TrackFilter(private var last: TrackPt? = null) {
    private val recent = ArrayDeque<TrackPt>()

    /** Returns the point to record, or null to skip this fix. */
    fun offer(lng: Double, lat: Double, timeMs: Long, accuracyM: Float?, speedMps: Float?): TrackPt? {
        if (accuracyM != null && accuracyM > MAX_ACCURACY) return null
        // The phone says you're standing still: drift, not movement.
        if (speedMps != null && speedMps >= 0f && speedMps < STILL_SPEED) return null
        recent.addLast(TrackPt(lng, lat, timeMs))
        while (recent.size > AVERAGE_OF) recent.removeFirst()
        val avg = TrackPt(recent.sumOf { it.lng } / recent.size, recent.sumOf { it.lat } / recent.size, timeMs)
        val prev = last
        if (prev == null) {
            // Start once the phone has a steady fix.
            if (recent.size < AVERAGE_OF) return null
            last = avg
            return avg
        }
        val gate = maxOf(MIN_MOVE, 1.5 * (accuracyM ?: 0f).toDouble())
        if (Mercator.distanceMeters(LngLat(prev.lng, prev.lat), LngLat(avg.lng, avg.lat)) < gate) return null
        last = avg
        return avg
    }

    companion object {
        const val MAX_ACCURACY = 15f
        const val STILL_SPEED = 0.15f
        const val MIN_MOVE = 6.0
        const val AVERAGE_OF = 5
    }
}
