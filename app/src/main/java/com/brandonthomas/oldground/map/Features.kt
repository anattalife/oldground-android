package com.brandonthomas.oldground.map

import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.data.colorHex
import com.brandonthomas.oldground.data.iconId
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.location.Fix

/**
 * Builds map features. [place] turns a real-world position into the map's own frame:
 * the identity for real maps, or the old map's scan frame (null when off the sheet).
 */
object Features {
    val empty: FeatureCollection get() = FeatureCollection.fromFeatures(emptyList())

    private fun pt(g: LngLat) = Point.fromLngLat(g.lng, g.lat)

    fun pins(pins: List<Pin>, place: (LngLat) -> LngLat?): FeatureCollection =
        FeatureCollection.fromFeatures(pins.mapNotNull { p ->
            val g = place(p.geo) ?: return@mapNotNull null
            Feature.fromGeometry(pt(g)).apply {
                addStringProperty("id", p.id)
                addStringProperty("name", p.name)
                addStringProperty("color", p.colorHex)
                addStringProperty("icon", p.iconId)
                p.photos.firstOrNull()?.let { addStringProperty("photo", photoImageId(it)) }
            }
        })

    private fun ring(pts: List<LngLat>) = (pts + pts.first()).map(::pt)

    /** Site outlines (with the name as a label). */
    fun sites(sites: List<Site>, place: (LngLat) -> LngLat?): FeatureCollection =
        FeatureCollection.fromFeatures(sites.mapNotNull { s ->
            val r = s.ring.mapNotNull(place)
            if (r.size < 3 || r.size != s.ring.size) return@mapNotNull null
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring(r)))).apply {
                addStringProperty("id", s.id)
                addStringProperty("name", s.name)
            }
        })

    /** An outline being drawn: the shape so far plus its corners (the newest one marked). */
    fun draft(corners: List<LngLat>, place: (LngLat) -> LngLat?): FeatureCollection {
        val c = corners.mapNotNull(place)
        val f = ArrayList<Feature>()
        when {
            c.size >= 3 -> f.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring(c)))))
            c.size == 2 -> f.add(Feature.fromGeometry(LineString.fromLngLats(c.map(::pt))))
        }
        c.forEachIndexed { i, g -> f.add(Feature.fromGeometry(pt(g)).apply { if (i == c.lastIndex) addBooleanProperty("last", true) }) }
        return FeatureCollection.fromFeatures(f)
    }

    /** Map image id for a pin's first photo. */
    fun photoImageId(photo: String) = "pin-photo-$photo"

    /** A dashed line from you to the pin you're being guided to. */
    fun guide(from: LngLat?, to: LngLat?): FeatureCollection =
        if (from == null || to == null) empty
        else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(listOf(pt(from), pt(to)))))

    fun tracks(tracks: Collection<List<TrackPt>>, place: (LngLat) -> LngLat?): FeatureCollection =
        FeatureCollection.fromFeatures(tracks.mapNotNull { pts ->
            val line = pts.mapNotNull { place(LngLat(it.lng, it.lat))?.let(::pt) }
            if (line.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(line)).apply {
                addStringProperty("c", "#ff6d00"); addNumberProperty("o", 0.85); addNumberProperty("w", 3)
            }
        })

    /** Paths drawn with their own colour, opacity and width (a site's visits, newest brightest). */
    data class Path(val pts: List<TrackPt>, val color: String, val opacity: Double, val width: Double)

    fun paths(paths: List<Path>): FeatureCollection =
        FeatureCollection.fromFeatures(paths.mapNotNull { p ->
            val line = p.pts.map { pt(LngLat(it.lng, it.lat)) }
            if (line.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(line)).apply {
                addStringProperty("c", p.color); addNumberProperty("o", p.opacity); addNumberProperty("w", p.width)
            }
        })

    fun gps(fix: Fix?, place: (LngLat) -> LngLat?, mapBearing: Double = 0.0): FeatureCollection {
        val g = fix?.let { place(it.geo) } ?: return empty
        return FeatureCollection.fromFeature(Feature.fromGeometry(pt(g)).apply {
            addNumberProperty("heading", (fix.heading ?: 0f).toDouble() + mapBearing)
            addNumberProperty("hasHeading", if (fix.heading != null) 1 else 0)
        })
    }

    fun ghost(g: LngLat?, color: String): FeatureCollection =
        if (g == null) empty else FeatureCollection.fromFeature(Feature.fromGeometry(pt(g)).apply { addStringProperty("color", color) })

    data class AnchorDot(val id: String, val at: LngLat, val color: String, val selected: Boolean)

    fun anchors(dots: List<AnchorDot>): FeatureCollection =
        FeatureCollection.fromFeatures(dots.map { a ->
            Feature.fromGeometry(pt(a.at)).apply {
                addStringProperty("id", a.id)
                addStringProperty("color", a.color)
                addNumberProperty("r", if (a.selected) 10 else 7)
            }
        })

    fun mesh(tris: List<Pair<List<LngLat>, Boolean>>): FeatureCollection =
        FeatureCollection.fromFeatures(tris.map { (corners, folded) ->
            Feature.fromGeometry(LineString.fromLngLats((corners + corners.first()).map(::pt))).apply {
                addStringProperty("color", if (folded) "#c0392b" else "rgba(30,90,200,0.75)")
                addNumberProperty("w", if (folded) 2.5 else 1.2)
            }
        })
}
