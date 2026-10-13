package com.brandonthomas.oldground.geo

import kotlin.math.abs

/** X = a*x + b*y + c ; Y = d*x + e*y + f */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun apply(p: XY) = XY(a * p.x + b * p.y + c, d * p.x + e * p.y + f)
    val det get() = a * e - b * d

    fun invert(): Affine? {
        val det = det
        if (abs(det) < 1e-15) return null
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    companion object {
        /** Least-squares affine (3+ non-collinear points; exact for 3). Points are centered for stability. */
        fun fit(src: List<XY>, dst: List<XY>): Affine? {
            val n = src.size
            if (n < 3) return null
            var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
            for (i in 0 until n) { sx += src[i].x; sy += src[i].y; dx += dst[i].x; dy += dst[i].y }
            sx /= n; sy /= n; dx /= n; dy /= n
            var xx = 0.0; var xy = 0.0; var yy = 0.0; var xX = 0.0; var yX = 0.0; var xY = 0.0; var yY = 0.0
            for (i in 0 until n) {
                val x = src[i].x - sx; val y = src[i].y - sy
                val X = dst[i].x - dx; val Y = dst[i].y - dy
                xx += x * x; xy += x * y; yy += y * y
                xX += x * X; yX += y * X; xY += x * Y; yY += y * Y
            }
            val det = xx * yy - xy * xy
            if (abs(det) < 1e-12 * (xx * yy + 1e-30)) return null
            val a = (xX * yy - yX * xy) / det
            val b = (yX * xx - xX * xy) / det
            val d = (xY * yy - yY * xy) / det
            val e = (yY * xx - xY * xy) / det
            return Affine(a, b, dx - a * sx - b * sy, d, e, dy - d * sx - e * sy)
        }

        /** Least-squares similarity (scale, rotation, shift; no mirroring). Works with 2+ points. */
        fun fitSimilarity(src: List<XY>, dst: List<XY>): Affine? {
            val n = src.size
            if (n < 2) return null
            var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
            for (i in 0 until n) { sx += src[i].x; sy += src[i].y; dx += dst[i].x; dy += dst[i].y }
            sx /= n; sy /= n; dx /= n; dy /= n
            var num1 = 0.0; var num2 = 0.0; var den = 0.0
            for (i in 0 until n) {
                val x = src[i].x - sx; val y = src[i].y - sy
                val X = dst[i].x - dx; val Y = dst[i].y - dy
                num1 += x * X + y * Y
                num2 += x * Y - y * X
                den += x * x + y * y
            }
            if (den < 1e-12) return null
            val a = num1 / den
            val b = num2 / den
            return Affine(a, -b, dx - a * sx + b * sy, b, a, dy - b * sx - a * sy)
        }

        /** Twice the signed area; positive = counter-clockwise in a Y-up frame. */
        fun orient(p: XY, q: XY, r: XY) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
    }
}
