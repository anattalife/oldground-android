package com.brandonthomas.oldground.data

import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** One day at a site: the finds logged there and the time spent recording inside the outline. */
data class Visit(
    val day: LocalDate,
    val finds: List<Pin>,
    /** Milliseconds of recorded track inside the outline. */
    val detectingMs: Long,
    /** Metres walked inside the outline. */
    val walkedM: Double,
    /** That day's track points inside the outline, one list per track. */
    val tracks: List<List<TrackPt>>,
)

object Sites {
    /** Two track points further apart than this are separate stretches (a break, or GPS lost). */
    private const val GAP_MS = 5 * 60_000L

    fun contains(ring: List<LngLat>, g: LngLat): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > g.lat) != (b.lat > g.lat) && g.lng < (b.lng - a.lng) * (g.lat - a.lat) / (b.lat - a.lat) + a.lng) inside = !inside
            j = i
        }
        return inside
    }

    /** Ground area in square metres. */
    fun areaM2(ring: List<LngLat>): Double {
        if (ring.size < 3) return 0.0
        val lat0 = ring.map { it.lat }.average()
        val kx = 111_320.0 * cos(lat0 * PI / 180)
        val ky = 110_540.0
        var s = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            s += a.lng * kx * b.lat * ky - b.lng * kx * a.lat * ky
        }
        return abs(s) / 2
    }

    fun formatArea(m2: Double, metric: Boolean): String =
        if (metric) (if (m2 < 10_000) "${m2.toInt()} m²" else "%.1f ha".format(m2 / 10_000))
        else (m2 / 4046.86).let { if (it < 10) "%.1f acres".format(it) else "${it.toInt()} acres" }

    fun center(ring: List<LngLat>): LngLat? =
        if (ring.isEmpty()) null else LngLat(ring.map { it.lng }.average(), ring.map { it.lat }.average())

    /** west, south, east, north */
    fun bounds(ring: List<LngLat>): DoubleArray? =
        if (ring.isEmpty()) null
        else doubleArrayOf(ring.minOf { it.lng }, ring.minOf { it.lat }, ring.maxOf { it.lng }, ring.maxOf { it.lat })

    /** Zoom that fits [b] in a view of [wDp]×[hDp], with some room around it. */
    fun fitZoom(b: DoubleArray, wDp: Float, hDp: Float, fill: Double = 0.75): Double {
        val sw = Mercator.toMerc(LngLat(b[0], b[1]))
        val ne = Mercator.toMerc(LngLat(b[2], b[3]))
        val world = 40_075_016.686
        val spanX = (ne.x - sw.x).coerceAtLeast(20.0)
        val spanY = (ne.y - sw.y).let { abs(it) }.coerceAtLeast(20.0)
        val zx = log2(wDp * fill * world / (512 * spanX))
        val zy = log2(hDp * fill * world / (512 * spanY))
        return minOf(zx, zy).coerceIn(3.0, 19.0)
    }

    private fun log2(v: Double) = Math.log(v) / Math.log(2.0)

    /** The site a place belongs to; where outlines overlap, the smallest one wins. */
    fun siteAt(sites: List<Site>, g: LngLat): Site? =
        sites.filter { contains(it.ring, g) }.minByOrNull { areaM2(it.ring) }

    /** Finds grouped by site id (null = not in a site). */
    fun findsBySite(sites: List<Site>, pins: List<Pin>): Map<String?, List<Pin>> =
        pins.filter { it.kind == PinKind.FIND }.groupBy { siteAt(sites, it.geo)?.id }

    private fun dayOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Every day you found something at the site or recorded a track inside it, newest first. */
    fun visits(
        site: Site,
        finds: List<Pin>,
        trackPoints: Map<String, List<TrackPt>>,
        zone: ZoneId = ZoneId.systemDefault(),
        sessions: List<VisitSession> = Repo.current.visits,
    ): List<Visit> {
        // Time from Start a visit to End visit (or now), by day; preferred over track time.
        val now = System.currentTimeMillis()
        val sessionMs = HashMap<LocalDate, Long>()
        for (v in sessions) if (v.siteId == site.id) {
            val d = dayOf(v.startedAt, zone)
            sessionMs[d] = (sessionMs[d] ?: 0L) + ((v.endedAt ?: now) - v.startedAt).coerceAtLeast(0)
        }
        val ring = site.ring
        val byDayFinds = finds.groupBy { dayOf(it.createdAt, zone) }
        // Track points inside the outline, split into continuous stretches, grouped by day.
        val stretches = HashMap<LocalDate, MutableList<List<TrackPt>>>()
        for (pts in trackPoints.values) {
            var cur = ArrayList<TrackPt>()
            fun flush() {
                if (cur.isNotEmpty()) stretches.getOrPut(dayOf(cur.first().timeMs, zone)) { mutableListOf() }.add(cur)
                cur = ArrayList()
            }
            for (p in pts) {
                if (!contains(ring, LngLat(p.lng, p.lat))) { flush(); continue }
                val last = cur.lastOrNull()
                if (last != null && (p.timeMs - last.timeMs > GAP_MS || dayOf(p.timeMs, zone) != dayOf(last.timeMs, zone))) flush()
                cur.add(p)
            }
            flush()
        }
        val days = (byDayFinds.keys + stretches.keys + sessionMs.keys).toList().distinct().sortedDescending()
        return days.map { day ->
            val tr = stretches[day].orEmpty()
            Visit(
                day = day,
                finds = byDayFinds[day].orEmpty().sortedByDescending { it.createdAt },
                detectingMs = sessionMs[day] ?: tr.sumOf { s -> if (s.size < 2) 0L else s.last().timeMs - s.first().timeMs },
                walkedM = tr.sumOf { s -> s.zipWithNext { a, b -> Mercator.distanceMeters(LngLat(a.lng, a.lat), LngLat(b.lng, b.lat)) }.sum() },
                tracks = tr,
            )
        }
    }

    fun formatDuration(ms: Long): String {
        val mins = ms / 60_000
        return when {
            mins < 60 -> "${mins}m"
            mins % 60 == 0L || mins >= 600 -> "${mins / 60}h"
            else -> "${mins / 60}h ${"%02d".format(mins % 60)}m"
        }
    }
}
