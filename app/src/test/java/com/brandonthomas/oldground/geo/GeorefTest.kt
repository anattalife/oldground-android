package com.brandonthomas.oldground.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class GeorefTest {
    // A synthetic "old map": 2000x1500 px covering ~2 km in Ohio, rotated 7 degrees, with a
    // hand-drawn wobble so a single affine can't fit it.
    private val W = 2000
    private val H = 1500
    private val center = Mercator.toMerc(LngLat(-82.6, 40.3))
    private val rot = 7 * PI / 180

    private fun truth(p: XY, wobble: Boolean = true): LngLat {
        var x = (p.x - W / 2.0) * 1.1
        var y = -(p.y - H / 2.0) * 1.1
        if (wobble) {
            x += 40 * sin(p.y / 300)
            y += 30 * sin(p.x / 400)
        }
        return Mercator.toLngLat(XY(center.x + x * cos(rot) - y * sin(rot), center.y + x * sin(rot) + y * cos(rot)))
    }

    private fun grid(n: Int, wobble: Boolean = true): List<AnchorPoint> {
        val out = ArrayList<AnchorPoint>()
        var k = 0
        for (i in 0 until n) for (j in 0 until n) {
            val img = XY(100 + i * (W - 200.0) / (n - 1) + (k * 37) % 23, 80 + j * (H - 160.0) / (n - 1) + (k * 53) % 19)
            out.add(AnchorPoint("a${k++}", img, truth(img, wobble)))
        }
        return out
    }

    private fun dist(a: LngLat, b: LngLat) = Mercator.distanceMeters(a, b)

    @Test fun mercatorRoundTrip() {
        val p = LngLat(-82.123456, 40.654321)
        val q = Mercator.toLngLat(Mercator.toMerc(p))
        assertEquals(p.lng, q.lng, 1e-9)
        assertEquals(p.lat, q.lat, 1e-9)
    }

    @Test fun delaunaySquarePlusCentre() {
        val tris = Delaunay.triangulate(listOf(XY(0.0, 0.0), XY(10.0, 0.0), XY(10.0, 10.0), XY(0.0, 10.0), XY(5.0, 5.0)))
        assertEquals(4, tris.size)
    }

    @Test fun twoAnchorsSimilarityExact() {
        val a = listOf(
            AnchorPoint("p", XY(100.0, 80.0), truth(XY(100.0, 80.0), false)),
            AnchorPoint("q", XY(1800.0, 1300.0), truth(XY(1800.0, 1300.0), false)),
        )
        val g = Georef(a, W, H)
        for (an in a) assertTrue(dist(g.imgToGeo(an.img)!!, an.geo) < 0.01)
        assertTrue(dist(g.imgToGeo(XY(400.0, 900.0))!!, truth(XY(400.0, 900.0), false)) < 0.05)
    }

    @Test fun meshExactAtAnchorsAndBeatsAffine() {
        val anchors = grid(6)
        val g = Georef(anchors, W, H)
        assertEquals(0, g.foldCount)
        for (a in anchors) assertTrue(dist(g.imgToGeo(a.img)!!, a.geo) < 0.01)
        var meshErr = 0.0; var affErr = 0.0; var n = 0
        val gm = g.global!!
        var x = 250.0
        while (x < W - 250) {
            var y = 200.0
            while (y < H - 200) {
                val p = XY(x, y)
                meshErr += dist(g.imgToGeo(p)!!, truth(p))
                affErr += dist(Mercator.toLngLat(gm.apply(XY(x, -y))), truth(p))
                n++
                y += 150
            }
            x += 150
        }
        assertTrue("mesh ${meshErr / n} vs affine ${affErr / n}", meshErr < affErr)
    }

    @Test fun geoToImgInverts() {
        val g = Georef(grid(5), W, H)
        for (p in listOf(XY(300.0, 300.0), XY(1000.0, 750.0), XY(1700.0, 1200.0), XY(50.0, 1450.0))) {
            val (img, on) = g.geoToImg(g.imgToGeo(p)!!)!!
            assertTrue("$p -> $img", hypot(img.x - p.x, img.y - p.y) < 0.01)
            assertTrue(on)
        }
        assertTrue(!g.geoToImg(truth(XY(-3000.0, -3000.0), false))!!.second)
    }

    @Test fun misplacedAnchorFlagged() {
        val anchors = grid(5).toMutableList()
        val i = 12
        val m = Mercator.toMerc(anchors[i].geo)
        anchors[i] = anchors[i].copy(geo = Mercator.toLngLat(XY(m.x + 450, m.y - 380)))
        val g = Georef(anchors, W, H)
        val report = g.anchorReport()
        val worst = report.maxByOrNull { it.errorMeters ?: 0.0 }!!
        assertEquals(anchors[i].id, worst.id)
        assertEquals(AnchorStatus.BAD, worst.status)
        assertTrue(g.foldCount > 0)
        assertTrue(report.count { it.id != anchors[i].id && it.status == AnchorStatus.GOOD } >= 18)
    }

    @Test fun scanFrameRoundTrip() {
        val p = XY(1234.5, 678.25)
        val back = TileMath.lngLatToScan(TileMath.scanToLngLat(p, 12000, 9000), 12000, 9000)
        assertEquals(p.x, back.x, 1e-6)
        assertEquals(p.y, back.y, 1e-6)
    }

    @Test fun tileRangeCoversBox() {
        val z = 16
        val b = TileMath.bounds(z, 17000, 24000)
        val r = TileMath.range(z, b[0] + 1, b[1] + 1, b[2] - 1, b[3] - 1)
        assertEquals(listOf(17000, 24000, 17000, 24000), r.toList())
    }

    @Test fun gpxEscapes() {
        val s = Gpx.build(listOf(Gpx.Waypoint("Smith & Sons <homesite>", LngLat(-82.6, 40.3))), emptyList())
        assertTrue(s.contains("Smith &amp; Sons &lt;homesite&gt;"))
        assertTrue(s.contains("lat=\"40.3000000\" lon=\"-82.6000000\""))
    }
}
