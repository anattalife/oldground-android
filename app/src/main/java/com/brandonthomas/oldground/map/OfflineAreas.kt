package com.brandonthomas.oldground.map

import android.content.Context
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.Settings
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Saves map areas on the phone for use without signal. Old maps are always on the phone
 * already; this is for Streets, Topo, Satellite and LiDAR.
 */
object OfflineAreas {
    data class Area(val id: Long, val name: String, val region: OfflineRegion)

    private fun meta(name: String) = name.toByteArray(Charsets.UTF_8)

    fun list(context: Context, done: (List<Area>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val areas = (offlineRegions ?: emptyArray()).map { Area(it.id, String(it.metadata, Charsets.UTF_8), it) }
                // Several regions (one per layer) can share a name; show each name once.
                done(areas.distinctBy { it.name })
            }
            override fun onError(error: String) = done(emptyList())
        })
    }

    fun delete(context: Context, name: String, done: () -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val matching = (offlineRegions ?: emptyArray()).filter { String(it.metadata, Charsets.UTF_8) == name }
                if (matching.isEmpty()) return done()
                var left = matching.size
                for (r in matching) r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { if (--left == 0) done() }
                    override fun onError(error: String) { if (--left == 0) done() }
                })
            }
            override fun onError(error: String) = done()
        })
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

    /** Downloads the given layers for an area. [progress] gets 0..100; [finished] gets an error or null. */
    fun download(
        context: Context, name: String, bounds: DoubleArray, layers: List<Layer>, settings: Settings,
        progress: (Int) -> Unit, finished: (String?) -> Unit,
    ) {
        val styles = layers.distinct().mapNotNull { styleUrl(context, it, settings) }
        if (styles.isEmpty()) return finished("Nothing to save for these layers.")
        val pct = IntArray(styles.size)
        var left = styles.size
        var failed: String? = null
        val ll = LatLngBounds.Builder().include(LatLng(bounds[3], bounds[2])).include(LatLng(bounds[1], bounds[0])).build()
        val density = context.resources.displayMetrics.density
        styles.forEachIndexed { i, url ->
            val def = OfflineTilePyramidRegionDefinition(url, ll, 10.0, 17.0, density)
            OfflineManager.getInstance(context).createOfflineRegion(def, meta(name), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            pct[i] = if (status.requiredResourceCount > 0) (100 * status.completedResourceCount / status.requiredResourceCount).toInt() else 0
                            progress(pct.average().toInt())
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                if (--left == 0) finished(failed)
                            }
                        }
                        override fun onError(error: OfflineRegionError) { failed = error.message }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            failed = "Too many tiles. Zoom in closer and try again."
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            if (--left == 0) finished(failed)
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) {
                    failed = error
                    if (--left == 0) finished(failed)
                }
            })
        }
    }

    private fun styleUrl(context: Context, layer: Layer, s: Settings): String? {
        val json = when (layer) {
            Layer.STREETS -> return Sources.STREETS_STYLE
            Layer.OLDMAP -> return null
            // Satellite: save USGS imagery (Esri tiles aren't stored on the phone).
            Layer.AERIAL -> Sources.rasterStyleJson(Sources.usgsAerial)
            else -> Sources.rasterStyleJson(Sources.raster(layer, s, null))
        }
        val f = File(context.filesDir, "styles/${layer.name.lowercase()}-${json.hashCode()}.json")
        f.parentFile?.mkdirs()
        f.writeText(json)
        return "file://${f.absolutePath}"
    }
}
