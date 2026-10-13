package com.brandonthomas.oldground.geo

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Standard XYZ map tiles in Web Mercator. Both the old map scan and the aligned overlay
 * are cut into these, so the map engine shows them like any other map layer.
 *
 * The scan is placed in its own flat "scan frame": pixel (x, y) sits at a fixed number
 * of Mercator meters from the origin, so a scan can be shown and zoomed with the same
 * map engine, with no distortion.
 */
object TileMath {
    const val TILE = 256

    fun span(z: Int) = Mercator.WORLD / 2.0.pow(z)

    /** Mercator bounds of a tile: minX, minY, maxX, maxY. */
    fun bounds(z: Int, x: Int, y: Int): DoubleArray {
        val s = span(z)
        val minX = -Mercator.HALF + x * s
        val maxY = Mercator.HALF - y * s
        return doubleArrayOf(minX, maxY - s, minX + s, maxY)
    }

    /** Tile column/row range covering a Mercator box: x0, y0, x1, y1 (inclusive). */
    fun range(z: Int, minX: Double, minY: Double, maxX: Double, maxY: Double): IntArray {
        val s = span(z)
        val n = 1 shl z
        fun col(mx: Double) = floor((mx + Mercator.HALF) / s).toInt().coerceIn(0, n - 1)
        fun row(my: Double) = floor((Mercator.HALF - my) / s).toInt().coerceIn(0, n - 1)
        return intArrayOf(col(minX), row(maxY), col(maxX), row(minY))
    }

    fun log2(v: Double) = ln(v) / ln(2.0)

    // ---- scan frame ----
    /** Zoom at which one scan pixel is one tile pixel. */
    const val SCAN_ZOOM = 20
    val SCAN_MERC_PER_PX = Mercator.WORLD / (TILE * 2.0.pow(SCAN_ZOOM))

    fun scanToMerc(p: XY, w: Int, h: Int) = XY((p.x - w / 2.0) * SCAN_MERC_PER_PX, -(p.y - h / 2.0) * SCAN_MERC_PER_PX)
    fun mercToScan(m: XY, w: Int, h: Int) = XY(m.x / SCAN_MERC_PER_PX + w / 2.0, -m.y / SCAN_MERC_PER_PX + h / 2.0)
    fun scanToLngLat(p: XY, w: Int, h: Int) = Mercator.toLngLat(scanToMerc(p, w, h))
    fun lngLatToScan(g: LngLat, w: Int, h: Int) = mercToScan(Mercator.toMerc(g), w, h)

    /** Lowest zoom worth building for something this many Mercator meters across. */
    fun fitZoom(extentMerc: Double): Int = floor(log2(Mercator.WORLD / extentMerc)).toInt().coerceIn(0, 22)
}
