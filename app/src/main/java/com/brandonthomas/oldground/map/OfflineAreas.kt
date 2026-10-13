package com.brandonthomas.oldground.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Saves map areas on the phone for use without signal. Old maps are always on the phone
 * already; this saves Topo, LiDAR and, for Satellite, the USGS imagery (Google's own map
 * and imagery can't be stored). Tiles go to files/offline/<layer>/z/x/y; the list of
 * areas is files/offline/areas.json.
 */
object OfflineAreas {
    @Serializable
    data class Area(val id: Long, val name: String, val bounds: List<Double>, val layers: List<String>, val minZ: Int, val maxZ: Int)

    /** Tile zooms saved: wide enough to find your way, close enough for a field (the map enlarges past the last). */
    private const val MIN_Z = 11
    private const val MAX_Z = 17

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())

    private fun file(context: Context) = File(context.filesDir, "offline/areas.json")

    private fun read(context: Context): List<Area> = runCatching {
        json.decodeFromString<List<Area>>(file(context).readText())
    }.getOrDefault(emptyList())

    private fun write(context: Context, areas: List<Area>) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(areas))
        tmp.renameTo(f)
    }

    fun list(context: Context, done: (List<Area>) -> Unit) {
        Repo.scope.launch {
            val areas = lock.withLock { read(context) }
            main.post { done(areas) }
        }
    }

    /** Deletes an area and the tiles no other saved area still needs. */
    fun delete(context: Context, name: String, done: () -> Unit) {
        Repo.scope.launch {
            lock.withLock {
                val all = read(context)
                val gone = all.filter { it.name == name }
                val keep = all.filter { it.name != name }
                for (a in gone) for (layer in a.layers) forEachTile(a.bounds, a.minZ, a.maxZ) { z, x, y ->
                    val needed = keep.any { k -> layer in k.layers && z in k.minZ..k.maxZ && covers(k.bounds, z, x, y) }
                    if (!needed) File(Tiles.offlineDir(layer), "$z/$x/$y").delete()
                }
                write(context, keep)
            }
            main.post(done)
        }
    }

    /** Rough tile count, so huge downloads can be refused up front. */
    fun estimateTiles(w: Double, s: Double, e: Double, n: Double, minZ: Int, maxZ: Int): Long {
        fun x(lon: Double, z: Int) = (lon + 180) / 360 * 2.0.pow(z)
        fun y(lat: Double, z: Int): Double {
            val r = Math.toRadians(lat)
            return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / Math.PI) / 2 * 2.0.pow(z)
        }
        var total = 0L
        for (z in minZ..maxZ) total += (floor(x(e, z)) - floor(x(w, z)) + 1).toLong() * (floor(y(s, z)) - floor(y(n, z)) + 1).toLong()
        return total
    }

    /** Downloads the given layers for an area. [progress] gets 0..100; [finished] gets an error or null. Both run on the main thread. */
    fun download(
        context: Context, name: String, bounds: DoubleArray, layers: List<Layer>, settings: Settings,
        progress: (Int) -> Unit, finished: (String?) -> Unit,
    ) {
        val rasters = layers.distinct().mapNotNull { if (it == Layer.STREETS) null else Sources.raster(it, settings, null) }
            .filter { !it.url.startsWith("file://") }
        if (rasters.isEmpty()) {
            main.post { finished("Google's street map can't be saved for offline use. Switch to Satellite, Topo or LiDAR to save this area.") }
            return
        }
        val b = bounds.toList()
        val jobs = ArrayList<Triple<Sources.Raster, Int, Pair<Int, Int>>>()
        for (r in rasters) {
            val top = minOf(MAX_Z, r.maxZoom.toInt())
            forEachTile(b, MIN_Z, top) { z, x, y -> jobs.add(Triple(r, z, x to y)) }
        }
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        Repo.scope.launch(Dispatchers.IO) {
            var lastPct = -1
            jobs.chunked(maxOf(1, jobs.size / 6 + 1)).map { part ->
                async {
                    for ((r, z, xy) in part) {
                        if (!Tiles.saveOffline(r, z, xy.first, xy.second)) failed.incrementAndGet()
                        val pct = 100 * done.incrementAndGet() / jobs.size
                        if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                    }
                }
            }.awaitAll()
            lock.withLock {
                val all = read(context).filter { it.name != name }
                write(context, all + Area(System.currentTimeMillis(), name, b, rasters.map { it.id }, MIN_Z, rasters.maxOf { minOf(MAX_Z, it.maxZoom.toInt()) }))
            }
            // A few missing tiles (sea, outside the US) are normal; only report a mostly failed download.
            val err = if (failed.get() > jobs.size / 3) "Many map tiles couldn't be downloaded. Check your signal and try again." else null
            main.post { finished(err) }
        }
    }

    private fun covers(b: List<Double>, z: Int, x: Int, y: Int): Boolean =
        x in Tiles.col(b[0], z)..Tiles.col(b[2], z) && y in Tiles.row(b[3], z)..Tiles.row(b[1], z)

    private inline fun forEachTile(b: List<Double>, minZ: Int, maxZ: Int, f: (Int, Int, Int) -> Unit) {
        for (z in minZ..maxZ) {
            for (x in Tiles.col(b[0], z)..Tiles.col(b[2], z)) for (y in Tiles.row(b[3], z)..Tiles.row(b[1], z)) f(z, x, y)
        }
    }
}
