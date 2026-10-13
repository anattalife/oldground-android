package com.brandonthomas.oldground.geo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/** A matched pair: a pixel on the old map scan and the same spot in the real world. */
data class AnchorPoint(val id: String, val img: XY, val geo: LngLat)

enum class AnchorStatus { GOOD, CHECK, BAD, UNKNOWN }

data class AnchorReport(val id: String, val errorMeters: Double?, val status: AnchorStatus)

/**
 * Turns anchors into a two-way transform between the old map and the world, the same
 * way Maprika's mesh works:
 *  - 2 anchors: similarity (scale, rotate, shift).
 *  - 3+ anchors: a Delaunay triangle mesh over the anchors, each triangle with its own
 *    exact affine, so hand-drawn maps are corrected locally. Virtual points around the
 *    sheet border (placed by the overall best fit) keep the whole sheet covered.
 *  - Folded triangles (flipped between map and world) mean a misplaced or swapped anchor.
 *  - Each anchor gets a leave-one-out error: how far it is from where the others put it.
 */
class Georef(val anchors: List<AnchorPoint>, val width: Int, val height: Int, virtualBorder: Boolean = true) {

    private class Tri(
        val img: Array<XY>, // Y-up image coordinates
        val merc: Array<XY>,
        val fwd: Affine,
        val inv: Affine,
        val folded: Boolean,
        val virtual: Boolean,
    )

    val global: Affine?
    private val globalInv: Affine?
    private val tris = ArrayList<Tri>()
    private var lastHit = 0

    val ready get() = global != null

    init {
        val src = anchors.map { flip(it.img) }
        val dst = anchors.map { Mercator.toMerc(it.geo) }
        var g: Affine? = null
        if (anchors.size >= 3) {
            val aff = Affine.fit(src, dst)
            if (aff != null && aff.det > 0) g = aff // reject mirrored / collinear fits
        }
        if (g == null && anchors.size >= 2) g = Affine.fitSimilarity(src, dst)
        global = g
        globalInv = g?.invert()
        if (g != null && anchors.size >= 3) buildMesh(src, dst, g, virtualBorder)
    }

    private fun buildMesh(src: List<XY>, dst: List<XY>, g: Affine, virtualBorder: Boolean) {
        val pts = ArrayList(src)
        val merc = ArrayList(dst)
        val realCount = pts.size
        if (virtualBorder) {
            val w = width.toDouble(); val h = height.toDouble()
            val m = 0.02 * max(w, h)
            val minD = 0.04 * max(w, h)
            for (i in 0..4) {
                val t = i / 4.0
                for (p in listOf(
                    XY(-m + t * (w + 2 * m), -m), XY(-m + t * (w + 2 * m), h + m),
                    XY(-m, -m + t * (h + 2 * m)), XY(w + m, -m + t * (h + 2 * m)),
                )) {
                    val fp = flip(p)
                    if ((0 until realCount).any { hypot(pts[it].x - fp.x, pts[it].y - fp.y) < minD }) continue
                    if (pts.any { it.x == fp.x && it.y == fp.y }) continue
                    pts.add(fp)
                    merc.add(g.apply(fp))
                }
            }
        }
        for (idx in Delaunay.triangulate(pts)) {
            val img = arrayOf(pts[idx[0]], pts[idx[1]], pts[idx[2]])
            val m = arrayOf(merc[idx[0]], merc[idx[1]], merc[idx[2]])
            val fwd = Affine.fit(img.toList(), m.toList()) ?: continue
            val inv = fwd.invert() ?: continue
            val folded = sign(Affine.orient(img[0], img[1], img[2])) != sign(Affine.orient(m[0], m[1], m[2]))
            tris.add(Tri(img, m, fwd, inv, folded, idx.any { it >= realCount }))
        }
    }

    /** Old-map pixel -> world. */
    fun imgToGeo(p: XY): LngLat? {
        val g = global ?: return null
        val fp = flip(p)
        val t = find(fp) { it.img }
        return Mercator.toLngLat((t?.fwd ?: g).apply(fp))
    }

    /** World -> old-map pixel, and whether it's on the sheet. */
    fun geoToImg(geo: LngLat): Pair<XY, Boolean>? = mercToImg(Mercator.toMerc(geo))

    /** Mercator -> old-map pixel (used when rendering overlay tiles). */
    fun mercToImg(m: XY): Pair<XY, Boolean>? {
        val gi = globalInv ?: return null
        val t = find(m) { it.merc }
        val img = flip((t?.inv ?: gi).apply(m))
        val on = img.x >= 0 && img.y >= 0 && img.x <= width && img.y <= height
        return img to on
    }

    private inline fun find(p: XY, corners: (Tri) -> Array<XY>): Tri? {
        if (tris.isEmpty()) return null
        // Neighbouring lookups usually hit the same triangle; try it first.
        val last = tris[lastHit.coerceIn(0, tris.size - 1)]
        if (!last.folded && inTri(p, corners(last))) return last
        for (i in tris.indices) {
            val t = tris[i]
            if (!t.folded && inTri(p, corners(t))) {
                lastHit = i
                return t
            }
        }
        return null
    }

    /** Triangles between real anchors, in image pixels (y down), for drawing the mesh check. */
    fun meshForDisplay(): List<Pair<Array<XY>, Boolean>> =
        tris.filter { !it.virtual }.map { t -> Array(3) { flip(t.img[it]) } to t.folded }

    val foldCount get() = tris.count { it.folded }

    /** Mercator bounds of the warped sheet. */
    fun mercBounds(): DoubleArray? {
        val g = global ?: return null
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        val pts = if (tris.isEmpty()) {
            listOf(XY(0.0, 0.0), XY(width.toDouble(), 0.0), XY(width.toDouble(), height.toDouble()), XY(0.0, height.toDouble()))
                .map { g.apply(flip(it)) }
        } else tris.filter { !it.folded }.flatMap { it.merc.toList() }
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    /** Mercator meters per scan pixel near a point of the old map. */
    fun mercPerImgPx(p: XY): Double? {
        val a = imgToGeo(p) ?: return null
        val b = imgToGeo(XY(p.x + 10, p.y)) ?: return null
        val c = imgToGeo(XY(p.x, p.y + 10)) ?: return null
        val ma = Mercator.toMerc(a); val mb = Mercator.toMerc(b); val mc = Mercator.toMerc(c)
        val d = (hypot(mb.x - ma.x, mb.y - ma.y) + hypot(mc.x - ma.x, mc.y - ma.y)) / 20
        return d.takeIf { it > 0 }
    }

    /** Leave-one-out error per anchor, in ground meters, with a status flag. */
    fun anchorReport(): List<AnchorReport> {
        if (anchors.size < 3) return anchors.map { AnchorReport(it.id, null, AnchorStatus.UNKNOWN) }
        val errors = anchors.mapIndexed { i, a ->
            val sub = Georef(anchors.filterIndexed { j, _ -> j != i }, width, height, virtualBorder = false)
            val pred = sub.imgToGeo(a.img) ?: return@mapIndexed null
            val pm = Mercator.toMerc(pred); val am = Mercator.toMerc(a.geo)
            hypot(pm.x - am.x, pm.y - am.y) * Mercator.scale(a.geo.lat)
        }
        val valid = errors.filterNotNull().sorted()
        val median = if (valid.isEmpty()) 0.0 else valid[valid.size / 2]
        val ref = max(median, 5.0) // don't flag tiny absolute errors
        return anchors.mapIndexed { i, a ->
            val e = errors[i]
            val status = when {
                e == null -> AnchorStatus.UNKNOWN
                e > 5 * ref -> AnchorStatus.BAD
                e > 2.5 * ref -> AnchorStatus.CHECK
                else -> AnchorStatus.GOOD
            }
            AnchorReport(a.id, e, status)
        }
    }

    companion object {
        private fun flip(p: XY) = XY(p.x, -p.y)

        private fun inTri(p: XY, t: Array<XY>): Boolean {
            val d1 = Affine.orient(t[0], t[1], p)
            val d2 = Affine.orient(t[1], t[2], p)
            val d3 = Affine.orient(t[2], t[0], p)
            val eps = 1e-9 * (abs(d1) + abs(d2) + abs(d3) + 1)
            val neg = d1 < -eps || d2 < -eps || d3 < -eps
            val pos = d1 > eps || d2 > eps || d3 > eps
            return !(neg && pos)
        }
    }
}
