package com.brandonthomas.oldground.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import android.view.View
import com.brandonthomas.oldground.geo.LngLat
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The left side of Swipe: one tile layer (an old map, LiDAR, Topo or USGS imagery) drawn
 * straight onto a plain view that follows the live map underneath. A Google map can't be
 * clipped at the divider, but a plain view can, so this is what the divider cuts.
 * It also draws pins and your position, so they don't vanish on this side.
 */
@SuppressLint("ViewConstructor")
class SwipeLayer(context: Context) : View(context) {
    /** The live map this layer lines up with. */
    var source: OgMap? = null
        set(v) { field = v; invalidate() }

    var raster: Sources.Raster? = null
        set(v) {
            if (v == field) return
            field = v
            provider = v?.let { Tiles.Provider(it) }
            cache.evictAll()
            loading.clear()
            missing.clear()
            invalidate()
        }

    var pins: List<Pair<LngLat, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    var gps: LngLat? = null
        set(v) { field = v; invalidate() }

    private var provider: Tiles.Provider? = null
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    /** Tiles that came back empty (off the old map, no data), not asked for again. */
    private val missing = HashSet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val d = resources.displayMetrics.density

    /** Call whenever the live map moves. */
    fun refresh() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        val m = source?.map ?: return
        val p = provider ?: return
        val proj = m.projection
        val box = proj.visibleRegion.latLngBounds
        var z = m.cameraPosition.zoom.roundToInt().coerceIn(0, 22)
        var x0: Int; var x1: Int; var y0: Int; var y1: Int
        while (true) {
            x0 = Tiles.col(box.southwest.longitude, z); x1 = Tiles.col(box.northeast.longitude, z)
            y0 = Tiles.row(box.northeast.latitude, z); y1 = Tiles.row(box.southwest.latitude, z)
            if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 80 || z == 0) break
            z--
        }
        val src = floatArrayOf(0f, 0f, 256f, 0f, 0f, 256f)
        val dst = FloatArray(6)
        for (x in x0..x1) for (y in y0..y1) {
            val key = "$z/$x/$y"
            val bmp = cache.get(key)
            if (bmp == null) {
                load(p, key, x, y, z)
                continue
            }
            val nw = proj.toScreenLocation(LatLng(Tiles.tileLat(y, z), Tiles.tileLng(x, z)))
            val ne = proj.toScreenLocation(LatLng(Tiles.tileLat(y, z), Tiles.tileLng(x + 1, z)))
            val sw = proj.toScreenLocation(LatLng(Tiles.tileLat(y + 1, z), Tiles.tileLng(x, z)))
            dst[0] = nw.x.toFloat(); dst[1] = nw.y.toFloat()
            dst[2] = ne.x.toFloat(); dst[3] = ne.y.toFloat()
            dst[4] = sw.x.toFloat(); dst[5] = sw.y.toFloat()
            src[2] = bmp.width.toFloat(); src[5] = bmp.height.toFloat()
            matrix.setPolyToPoly(src, 0, dst, 0, 3)
            canvas.drawBitmap(bmp, matrix, paint)
        }
        for ((at, color) in pins) {
            val s = proj.toScreenLocation(LatLng(at.lat, at.lng))
            dot.color = Color.WHITE; canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 9.5f * d, dot)
            dot.color = color; canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 7f * d, dot)
        }
        gps?.let { g ->
            val s = proj.toScreenLocation(LatLng(g.lat, g.lng))
            dot.color = Color.argb(56, 26, 115, 232); canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 22f * d, dot)
            dot.color = Color.WHITE; canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 8.5f * d, dot)
            dot.color = Color.rgb(26, 115, 232); canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), 5.5f * d, dot)
        }
    }

    private fun load(p: Tiles.Provider, key: String, x: Int, y: Int, z: Int) {
        if (key in missing || !loading.add(key)) return
        pool.execute {
            val data = runCatching { p.getTile(x, y, z)?.data }.getOrNull()
            val bmp = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            post {
                loading.remove(key)
                if (provider !== p) return@post
                if (bmp != null) {
                    cache.put(key, bmp)
                    invalidate()
                } else missing.add(key)
            }
        }
    }

    companion object {
        private val pool = Executors.newFixedThreadPool(4)
    }
}
