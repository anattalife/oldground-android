package com.brandonthomas.oldground.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Pyramid
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.geo.Georef
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.TileMath
import com.brandonthomas.oldground.geo.XY
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds the tiles for old maps in the background, one zoom level at a time from the
 * overview up. After each level the map record is updated, so the map shows straight
 * away and sharpens as levels finish. Interrupted builds pick up where they stopped.
 *
 *  - scan:<id>  the scan itself, in its own flat frame (anchor editor, Split, Drive)
 *  - warp:<id>  the scan bent through the anchors onto today's map (Overlay, Swipe)
 */
object TileJobs {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            android.util.Log.e("OldGround", "Tile build failed", e)
        },
    )
    private val jobs = HashMap<String, Job>()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Build progress (0..1) by job key ("scan:<id>" / "warp:<id>"). */
    val progress: StateFlow<Map<String, Float>> = _progress

    /** Largest scan accepted: decoding regions of anything bigger gets slow and memory-hungry. */
    const val MAX_SCAN_PIXELS = 250_000_000L

    fun resumeAll(context: Context) {
        for (m in Repo.current.maps) {
            ensureScan(m)
            if (m.aligned) ensureWarp(m)
        }
    }

    // ---------- scan ----------

    fun ensureScan(map: OldMap) {
        val existing = map.scan
        if (existing != null && existing.complete) return
        val key = "scan:${map.id}"
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            jobs[key] = launchScan(key, map)
        }
    }

    private fun launchScan(key: String, map: OldMap): Job {
        return scope.launch {
            val m = prepareScanFile(map) ?: return@launch
            val pyr = m.scan ?: run {
                val s = TileMath.SCAN_MERC_PER_PX
                val hw = m.width / 2.0 * s
                val hh = m.height / 2.0 * s
                val p = Pyramid(
                    dir = "scan",
                    zMin = TileMath.fitZoom(max(m.width, m.height) * s).coerceAtMost(TileMath.SCAN_ZOOM),
                    zMax = TileMath.SCAN_ZOOM,
                    bounds = listOf(-hw, -hh, hw, hh),
                )
                Repo.updateMap(m.id) { it.copy(scan = p) }
                p
            }
            build(key, m, pyr, grid = 1) { merc -> TileMath.mercToScan(merc, m.width, m.height) }
        }
    }

    /**
     * Makes sure the scan can be read piece by piece. Formats Android can't decode in
     * pieces (for example HEIC photos) are converted to JPEG once.
     */
    private fun prepareScanFile(map: OldMap): OldMap? {
        val f = Repo.mapFile(map.id, map.image)
        val ok = runCatching {
            val d = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(f.path)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(f.path, false)
            d?.recycle()
            d != null
        }.getOrDefault(false)
        if (ok) return map
        return runCatching {
            var sample = 1
            while (map.width.toLong() * map.height / (sample.toLong() * sample) > 40_000_000L) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })!!
            val out = Repo.mapFile(map.id, "original-converted.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val updated = map.copy(image = out.name, width = bmp.width, height = bmp.height, scan = null, warp = null)
            bmp.recycle()
            Repo.updateMap(map.id) { updated }
            updated
        }.getOrNull()
    }

    // ---------- overlay ----------

    /** Builds (or rebuilds after anchors change) the overlay. */
    fun ensureWarp(given: OldMap) {
        // Always work from the saved record: a screen may hold an older copy.
        val map = Repo.map(given.id) ?: given
        if (!map.aligned) return
        val key = map.anchorsKey()
        val existing = map.warp
        if (existing != null && existing.key == key && existing.complete) return
        val jobKey = "warp:${map.id}"
        synchronized(jobs) {
            val running = jobs[jobKey]
            if (running?.isActive == true && existing?.key == key) return
            running?.cancel()
            jobs[jobKey] = launchWarp(jobKey, key, existing, map)
        }
    }

    private fun launchWarp(jobKey: String, key: String, existing: Pyramid?, map: OldMap): Job {
        return scope.launch {
            val g = Georef(map.anchorPoints(), map.width, map.height)
            val b = g.mercBounds() ?: return@launch
            val pyr = if (existing != null && existing.key == key) existing else {
                val mpp = g.mercPerImgPx(XY(map.width / 2.0, map.height / 2.0)) ?: return@launch
                val zMax = TileMath.log2(Mercator.WORLD / (TileMath.TILE * mpp)).roundToInt().coerceIn(10, 21)
                val zMin = TileMath.fitZoom(max(b[2] - b[0], b[3] - b[1])).coerceIn(0, zMax)
                val p = Pyramid("warp-${System.currentTimeMillis()}", zMin, zMax, -1, b.toList(), key)
                Repo.updateMap(map.id) { it.copy(warp = p) }
                // The previous overlay's tiles are no longer used.
                existing?.let { File(Repo.mapDir(map.id), it.dir).deleteRecursively() }
                p
            }
            build(jobKey, map, pyr, grid = 16) { merc -> g.mercToImg(merc)?.first }
        }
    }

    // ---------- shared ----------

    private suspend fun build(key: String, map: OldMap, start: Pyramid, grid: Int, toImg: (XY) -> XY?) {
        val dir = File(Repo.mapDir(map.id), start.dir)
        val b = start.bounds
        val levels = ((start.zDone + 1).coerceAtLeast(start.zMin))..start.zMax
        val total = levels.sumOf { z -> TileMath.range(z, b[0], b[1], b[2], b[3]).let { (it[2] - it[0] + 1L) * (it[3] - it[1] + 1L) } }
        var done = 0L
        _progress.update { it + (key to 0f) }
        try {
            TileRenderer(Repo.mapFile(map.id, Repo.map(map.id)?.image ?: map.image).path, map.width, map.height).use { r ->
                for (z in levels) {
                    val t = TileMath.range(z, b[0], b[1], b[2], b[3])
                    for (y in t[1]..t[3]) for (x in t[0]..t[2]) {
                        if (!currentCoroutineContext().isActive) return
                        val f = File(dir, "$z/$x/$y.webp")
                        if (!f.exists()) r.render(z, x, y, grid, f, toImg)
                        done++
                        if (done % 16 == 0L) _progress.update { it + (key to done.toFloat() / total) }
                    }
                    // Only record progress if this is still the map's current tile set.
                    val still = Repo.map(map.id)?.let { m -> if (key.startsWith("scan")) m.scan else m.warp }
                    if (still?.dir != start.dir) return
                    Repo.updateMap(map.id) { m ->
                        if (key.startsWith("scan")) m.copy(scan = m.scan?.copy(zDone = z)) else m.copy(warp = m.warp?.copy(zDone = z))
                    }
                }
            }
        } finally {
            _progress.update { it - key }
        }
    }

    fun cancel(mapId: String) = synchronized(jobs) {
        jobs.remove("scan:$mapId")?.cancel()
        jobs.remove("warp:$mapId")?.cancel()
    }
}
