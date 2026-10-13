package com.brandonthomas.oldground.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point on the old map scan (pixels, x right, y down) or in Mercator meters. */
data class XY(val x: Double, val y: Double)

/** Longitude, latitude in degrees. */
data class LngLat(val lng: Double, val lat: Double)

/**
 * Web Mercator (EPSG:3857). All alignment math runs in Mercator meters, so distances
 * are roughly uniform and results line up exactly with map tiles.
 */
object Mercator {
    const val R = 6378137.0
    val WORLD = 2 * PI * R
    val HALF = PI * R
    private const val MAX_LAT = 85.0511287798

    fun toMerc(p: LngLat): XY {
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT)
        return XY(R * p.lng * PI / 180, R * ln(tan(PI / 4 + lat * PI / 360)))
    }

    fun toLngLat(m: XY): LngLat =
        LngLat(m.x / R * 180 / PI, (2 * atan(exp(m.y / R)) - PI / 2) * 180 / PI)

    /** Ground meters per Mercator meter at a latitude. */
    fun scale(lat: Double) = cos(lat * PI / 180)

    fun distanceMeters(a: LngLat, b: LngLat): Double {
        val r = PI / 180
        val dLat = (b.lat - a.lat) * r
        val dLng = (b.lng - a.lng) * r
        val s = sin(dLat / 2).let { it * it } + cos(a.lat * r) * cos(b.lat * r) * sin(dLng / 2).let { it * it }
        return 2 * 6371008.8 * asin(min(1.0, sqrt(s)))
    }

    /** Initial bearing, degrees clockwise from true north. */
    fun bearing(a: LngLat, b: LngLat): Double {
        val r = PI / 180
        val y = sin((b.lng - a.lng) * r) * cos(b.lat * r)
        val x = cos(a.lat * r) * sin(b.lat * r) - sin(a.lat * r) * cos(b.lat * r) * cos((b.lng - a.lng) * r)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    fun formatDistance(m: Double, metric: Boolean): String {
        if (metric) return if (m < 1000) "${m.roundToInt()} m" else String.format("%.2f km", m / 1000)
        val ft = m * 3.28084
        return if (ft < 1000) "${ft.roundToInt()} ft" else String.format("%.2f mi", m / 1609.344)
    }

    private val DIRS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun compass(deg: Double) = DIRS[(((deg + 22.5) % 360) / 45).toInt() % 8]
}
