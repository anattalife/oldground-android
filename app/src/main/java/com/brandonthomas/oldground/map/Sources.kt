package com.brandonthomas.oldground.map

import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.LidarStyle
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Pyramid
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Settings
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.XY
import java.io.File
import java.net.URLEncoder

/**
 * Map sources. The map itself is Google's (free on phones): Streets is Google's road map
 * and Satellite is Google's imagery. Everything else is tiles drawn on top of it:
 *  - Topo: USGS The National Map (US government, no key)
 *  - LiDAR: USGS 3DEP bare-earth elevation, shaded by the USGS server
 *  - USGS aerial imagery: what offline areas save for Satellite (Google's imagery can't be
 *    stored on the phone), and Satellite wherever it has to be a tile layer (Swipe's left
 *    side, or Satellite laid over another map)
 *  - Old maps: tiles built on the phone
 */
object Sources {
    private const val USGS = "https://basemap.nationalmap.gov/arcgis/rest/services"
    const val TOPO = "$USGS/USGSTopo/MapServer/tile/{z}/{y}/{x}"
    const val AERIAL = "$USGS/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"
    const val USGS_ATTRIB = "USGS The National Map"

    /** A raster tile layer. [url] holds {z}/{x}/{y} or {bbox-epsg-3857}; file:// for tiles on the phone. */
    data class Raster(val id: String, val url: String, val maxZoom: Float, val minZoom: Float = 0f, val bounds: FloatArray? = null, val attribution: String = "") {
        override fun equals(other: Any?) = other is Raster && other.id == id && other.url == url
        override fun hashCode() = id.hashCode() * 31 + url.hashCode()
    }

    /** USGS aerial imagery. */
    val usgsAerial = Raster("aerial", AERIAL, 16f, attribution = USGS_ATTRIB)

    fun lidar(s: Settings): String {
        val rule = when (s.lidarStyle) {
            LidarStyle.STRETCH -> """{"rasterFunction":"Hillshade Gray-Stretch"}"""
            LidarStyle.MULTI -> """{"rasterFunction":"Hillshade Multidirectional"}"""
            LidarStyle.SLOPE -> """{"rasterFunction":"Slope Map"}"""
            // Moving the sun makes old roads and walls that run parallel to the light appear.
            LidarStyle.SUN -> """{"rasterFunction":"Hillshade","rasterFunctionArguments":{"Azimuth":${s.lidarAzimuth % 360},"Altitude":35,"ZFactor":1},"variableName":"DEM"}"""
        }
        return "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage" +
            "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png&f=image" +
            "&renderingRule=" + URLEncoder.encode(rule, "UTF-8").replace("+", "%20")
    }

    /**
     * A layer as tiles. Streets has none (it's Google's road map). Satellite as tiles is the
     * USGS imagery; as a base map it's Google's (see OgMap).
     */
    fun raster(layer: Layer, s: Settings, map: OldMap?): Raster? = when (layer) {
        Layer.AERIAL -> usgsAerial
        Layer.TOPO -> Raster("topo", TOPO, 16f, attribution = USGS_ATTRIB)
        Layer.LIDAR -> Raster("lidar-${s.lidarStyle}-${s.lidarAzimuth}", lidar(s), 18f, attribution = "USGS 3DEP")
        Layer.OLDMAP -> map?.warp?.takeIf { it.ready }?.let { tiles(map.id, it, "old-${it.dir}-${it.zDone}") }
        Layer.STREETS -> null
    }

    /** Tiles built on the phone (old map overlay or scan). */
    fun tiles(mapId: String, p: Pyramid, id: String): Raster {
        val dir = File(Repo.mapDir(mapId), p.dir).absolutePath
        val sw = Mercator.toLngLat(XY(p.bounds[0], p.bounds[1]))
        val ne = Mercator.toLngLat(XY(p.bounds[2], p.bounds[3]))
        return Raster(
            id, "file://$dir/{z}/{x}/{y}.webp", maxZoom = p.zDone.toFloat(), minZoom = p.zMin.toFloat(),
            bounds = floatArrayOf(sw.lng.toFloat(), sw.lat.toFloat(), ne.lng.toFloat(), ne.lat.toFloat()),
        )
    }
}
