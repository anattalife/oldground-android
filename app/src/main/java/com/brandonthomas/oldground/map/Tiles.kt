package com.brandonthomas.oldground.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.brandonthomas.oldground.geo.TileMath
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Where map tiles come from and where they're kept on the phone.
 *  - offline/<layer>/z/x/y: saved areas (kept until the area is deleted)
 *  - cache/tiles/<layer>/z/x/y: everything else seen recently (trimmed to a size limit)
 */
object Tiles {
    private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024
    private const val SIZE = TileMath.TILE

    lateinit var app: Context
        private set
    private var writesSinceTrim = 0

    fun init(context: Context) {
        app = context.applicationContext
        Thread { trim() }.start()
    }

    fun offlineDir(layerKey: String) = File(app.filesDir, "offline/${safe(layerKey)}")
    private fun cacheDir(layerKey: String) = File(app.cacheDir, "tiles/${safe(layerKey)}")
    private fun safe(k: String) = k.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun online(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The web address of one tile. */
    fun url(template: String, z: Int, x: Int, y: Int): String {
        if (template.contains("{bbox-epsg-3857}")) {
            val b = TileMath.bounds(z, x, y)
            val bbox = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", b[0], b[1], b[2], b[3])
            return template.replace("{bbox-epsg-3857}", bbox)
        }
        return template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
    }

    /**
     * One tile's image bytes: saved area first, then the cache, then the network (unless
     * [storedOnly]). Null when it isn't available.
     */
    fun bytes(r: Sources.Raster, z: Int, x: Int, y: Int, storedOnly: Boolean = false): ByteArray? {
        if (r.url.startsWith("file://")) {
            val f = File(url(r.url.removePrefix("file://"), z, x, y))
            return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
        }
        val rel = "$z/$x/$y"
        File(offlineDir(r.id), rel).takeIf { it.exists() }?.let { f -> runCatching { f.readBytes() }.getOrNull()?.let { return it } }
        val cached = File(cacheDir(r.id), rel)
        if (cached.exists()) runCatching { cached.readBytes() }.getOrNull()?.let { return it }
        if (storedOnly) return null
        val data = fetch(url(r.url, z, x, y)) ?: return null
        save(cached, data)
        return data
    }

    /** Downloads one tile into a saved area. True when it's there. */
    fun saveOffline(r: Sources.Raster, z: Int, x: Int, y: Int): Boolean {
        val f = File(offlineDir(r.id), "$z/$x/$y")
        if (f.exists()) return true
        val data = bytes(r, z, x, y) ?: return false
        save(f, data)
        return true
    }

    private fun fetch(u: String): ByteArray? = runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "OldGround-Android")
        try {
            if (c.responseCode != 200) return@runCatching null
            if (c.contentType?.startsWith("image/") == false) return@runCatching null
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun save(f: File, data: ByteArray) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(data)
            tmp.renameTo(f)
        }
        if (++writesSinceTrim > 500) {
            writesSinceTrim = 0
            Thread { trim() }.start()
        }
    }

    /** Keeps the tile cache under its limit, oldest first. Saved areas are never trimmed. */
    @Synchronized
    private fun trim() {
        val root = File(app.cacheDir, "tiles")
        val files = root.walkTopDown().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= CACHE_LIMIT_BYTES * 0.8) break
            total -= f.length()
            f.delete()
        }
    }

    // ---------- tile maths for saved areas ----------

    fun col(lng: Double, z: Int) = floor((lng + 180) / 360 * (1 shl z)).toInt()
    fun row(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }
    fun tileLng(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360 - 180
    fun tileLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    // ---------- Google map tile layer ----------

    /**
     * Feeds a Google map one of our tile layers. Past the layer's last zoom it enlarges the
     * nearest tile (Google's map won't do that by itself); outside the layer's area or below
     * its first zoom it gives nothing. With [storedOnly] it uses only tiles already on the
     * phone (for Satellite without signal).
     */
    class Provider(private val r: Sources.Raster, private val storedOnly: Boolean = false) : TileProvider {
        private val zoomed = LruCache<String, ByteArray>(64)

        override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
            if (zoom < r.minZoom.toInt()) return TileProvider.NO_TILE
            if (!inBounds(x, y, zoom)) return TileProvider.NO_TILE
            val max = r.maxZoom.toInt()
            if (zoom <= max) {
                val b = bytes(r, zoom, x, y, storedOnly) ?: return TileProvider.NO_TILE
                return Tile(SIZE, SIZE, b)
            }
            val dz = (zoom - max).coerceAtMost(8)
            val key = "$zoom/$x/$y"
            zoomed.get(key)?.let { return Tile(SIZE, SIZE, it) }
            val px = x shr dz
            val py = y shr dz
            val parent = bytes(r, max, px, py, storedOnly) ?: return TileProvider.NO_TILE
            val src = BitmapFactory.decodeByteArray(parent, 0, parent.size) ?: return TileProvider.NO_TILE
            val part = SIZE shr dz
            val ox = (x - (px shl dz)) * part
            val oy = (y - (py shl dz)) * part
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Rect(ox, oy, ox + part.coerceAtLeast(1), oy + part.coerceAtLeast(1)), Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.PNG, 100, bos)
            out.recycle()
            val data = bos.toByteArray()
            zoomed.put(key, data)
            return Tile(SIZE, SIZE, data)
        }

        private fun inBounds(x: Int, y: Int, z: Int): Boolean {
            val b = r.bounds ?: return true
            val w = tileLng(x, z); val e = tileLng(x + 1, z)
            val n = tileLat(y, z); val s = tileLat(y + 1, z)
            return e >= b[0] && w <= b[2] && n >= b[1] && s <= b[3]
        }
    }
}
