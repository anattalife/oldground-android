package com.brandonthomas.oldground.geo

import kotlin.math.abs
import kotlin.math.max

/**
 * Bowyer–Watson Delaunay triangulation. Anchor counts are tens to low hundreds, so this
 * simple version is plenty fast. Returns triangles as index triples into [points].
 */
object Delaunay {
    private class T(val a: Int, val b: Int, val c: Int, val cx: Double, val cy: Double, val r2: Double)

    fun triangulate(points: List<XY>): List<IntArray> {
        val n = points.size
        if (n < 3) return emptyList()
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        val d = max(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1.0
        val mx = (minX + maxX) / 2; val my = (minY + maxY) / 2
        val pts = points + listOf(XY(mx - 20 * d, my - d), XY(mx, my + 20 * d), XY(mx + 20 * d, my - d))

        fun make(a: Int, b: Int, c: Int): T? {
            val (ax, ay) = pts[a]; val (bx, by) = pts[b]; val (cx, cy) = pts[c]
            val dd = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
            if (abs(dd) < 1e-12) return null
            val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
            val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / dd
            val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / dd
            return T(a, b, c, ux, uy, (ax - ux) * (ax - ux) + (ay - uy) * (ay - uy))
        }

        var tris = mutableListOf(make(n, n + 1, n + 2)!!)
        for (i in 0 until n) {
            val (px, py) = pts[i]
            val bad = ArrayList<T>()
            val keep = ArrayList<T>()
            for (t in tris) {
                if ((px - t.cx) * (px - t.cx) + (py - t.cy) * (py - t.cy) <= t.r2 * (1 + 1e-12)) bad.add(t) else keep.add(t)
            }
            // Boundary of the cavity: edges used by exactly one bad triangle.
            val edges = LinkedHashMap<Long, IntArray>()
            for (t in bad) {
                for ((u, v) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                    val key = if (u < v) u.toLong() * 1_000_003 + v else v.toLong() * 1_000_003 + u
                    if (edges.containsKey(key)) edges.remove(key) else edges[key] = intArrayOf(u, v)
                }
            }
            for (e in edges.values) make(e[0], e[1], i)?.let { keep.add(it) }
            tris = keep
        }
        return tris.filter { it.a < n && it.b < n && it.c < n }.map { intArrayOf(it.a, it.b, it.c) }
    }
}
