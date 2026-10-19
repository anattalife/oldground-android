package com.brandonthomas.oldground.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.geo.LngLat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon as GPolygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** What a map shows underneath everything else. */
sealed class Base {
    /** A real-world map: Streets, Topo, Satellite or LiDAR. */
    data class Geo(val layer: Layer, val lidarKey: String = "") : Base()
    /** The old map scan in its own flat frame (tiles built on the phone). */
    data class Scan(val raster: Sources.Raster) : Base()
}

/** The data drawn on top of a map, in drawing order. */
enum class Data { LAND, SITES, TRACKS, GUIDE, MESH, ANCHORS, PINS, DRAFT, GHOST, GPS }

/** A camera position. [zoom] is on the app's scale (Google's zoom minus one). */
data class Cam(val center: LngLat, val zoom: Double, val bearing: Double)

/**
 * One Google map plus everything the app draws on it. Holds the wanted state (base,
 * overlay, data) and applies it once the map is ready, so callers can set things at any time.
 *
 * Zoom: the app's zoom numbers come from its earlier map engine, one less than Google's for
 * the same view. This class converts both ways, so screens never see Google's numbers.
 */
class OgMap(context: Context, @Suppress("UNUSED_PARAMETER") texture: Boolean = false, val interactive: Boolean = true) {
    private val mapView: MapView
    /** The view to put on screen: the map plus its compass and credits. */
    val view: FrameLayout
    var map: GoogleMap? = null
        private set

    private val density = context.resources.displayMetrics.density
    private val compass = ImageView(context)
    private val credits = TextView(context)

    private var base: Base? = null
    private var baseTiles: TileOverlay? = null
    private var baseKey: String? = null
    private var background: TileOverlay? = null
    private var backgroundColor: Int? = null
    private var storedAerial: TileOverlay? = null
    private var overlay: Sources.Raster? = null
    private var overlayTiles: TileOverlay? = null
    private var overlayOpacity = 1f
    private val data = HashMap<Data, FeatureCollection>()
    private val drawn = HashMap<Data, MutableList<Any>>()
    private val images = HashMap<String, Bitmap>()
    private var pendingCam: Cam? = null
    private var gpsMarkers: Pair<Marker, Marker>? = null

    var onMove: ((Cam, Boolean) -> Unit)? = null
    var onIdle: ((Cam, Boolean) -> Unit)? = null
    /** A tap: where, the pin/anchor id hit (if any), and the public land hit (if any). */
    var onTap: ((LngLat, String?, Map<String, String>?) -> Unit)? = null
    /** Press and hold on the map. */
    var onLongTap: ((LngLat) -> Unit)? = null
    /** Called whenever the map stops moving (several listeners allowed). */
    val idleListeners = ArrayList<() -> Unit>()
    var onReady: ((OgMap) -> Unit)? = null
    var rotateEnabled = true
        set(v) { field = v; map?.uiSettings?.isRotateGesturesEnabled = v && interactive }
    var compassTopPx = 0
        set(v) { field = v; placeCompass() }
    /** Space kept free at the bottom (for the buttons over the map); Google's logo sits above it. */
    var bottomInsetPx = 0
        set(v) {
            field = v
            map?.setPadding(0, 0, 0, v)
            (credits.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = v; credits.layoutParams = it }
        }

    private var userGesture = false

    @SuppressLint("ClickableViewAccessibility")
    private inner class Frame(c: Context) : FrameLayout(c) {
        // A map you can't move doesn't take touches, so a tap reaches whatever holds it.
        override fun dispatchTouchEvent(ev: MotionEvent?) = if (interactive) super.dispatchTouchEvent(ev) else false
    }

    init {
        val opts = GoogleMapOptions()
            .compassEnabled(false)
            .mapToolbarEnabled(false)
            .zoomControlsEnabled(false)
            .tiltGesturesEnabled(false)
            .rotateGesturesEnabled(interactive)
            .scrollGesturesEnabled(interactive)
            .zoomGesturesEnabled(interactive)
        mapView = MapView(context, opts)
        mapView.onCreate(null)
        view = Frame(context)
        view.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        compass.setImageBitmap(compassBitmap())
        compass.visibility = android.view.View.GONE
        compass.setOnClickListener {
            map?.let { m -> m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0f).build())) }
        }
        view.addView(compass, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.TOP or Gravity.END))
        placeCompass()

        credits.textSize = 9f
        credits.setTextColor(Color.argb(200, 30, 30, 30))
        credits.setBackgroundColor(Color.argb(140, 255, 255, 255))
        credits.setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        credits.visibility = android.view.View.GONE
        view.addView(credits, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))

        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = rotateEnabled && interactive
            m.uiSettings.isMyLocationButtonEnabled = false
            m.uiSettings.isIndoorLevelPickerEnabled = false
            m.isBuildingsEnabled = false
            m.setIndoorEnabled(false)
            runCatching { m.setMapStyle(MapStyleOptions(QUIET_STYLE)) }
            m.setOnCameraMoveStartedListener { reason -> userGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE }
            m.setOnCameraMoveListener {
                updateCompass()
                onMove?.invoke(cam(), userGesture)
            }
            m.setOnCameraIdleListener {
                updateCompass()
                layoutLabels()
                onIdle?.invoke(cam(), userGesture)
                userGesture = false
                for (l in idleListeners.toList()) l()
            }
            m.setOnMapLongClickListener { ll -> onLongTap?.invoke(LngLat(ll.longitude, ll.latitude)) }
            m.setOnMapClickListener { ll -> tapAt(LngLat(ll.longitude, ll.latitude), null) }
            m.setOnMarkerClickListener { mk ->
                val id = mk.tag as? String
                tapAt(LngLat(mk.position.longitude, mk.position.latitude), id)
                true
            }
            if (bottomInsetPx > 0) m.setPadding(0, 0, 0, bottomInsetPx)
            pendingCam?.let { setCamera(it.center, it.zoom, it.bearing) }
            pendingCam = null
            applyBase()
            applyOverlay()
            for (d in Data.entries) draw(d)
            onReady?.invoke(this)
        }
    }

    private fun tapAt(at: LngLat, id: String?) {
        val land = if (id != null) null else landAt(at)
        onTap?.invoke(at, id, land)
    }

    private fun placeCompass() {
        val lp = compass.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = compassTopPx + (8 * density).toInt()
        lp.rightMargin = (12 * density).toInt()
        compass.layoutParams = lp
    }

    private fun updateCompass() {
        val b = map?.cameraPosition?.bearing ?: 0f
        val turned = abs(((b + 540) % 360) - 180) > 0.5f
        val show = interactive && turned
        compass.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        compass.rotation = -b
    }

    fun cam(): Cam {
        val c = map?.cameraPosition ?: return pendingCam ?: Cam(LngLat(0.0, 0.0), 1.0, 0.0)
        return Cam(LngLat(c.target.longitude, c.target.latitude), c.zoom - ZOOM_OFFSET, c.bearing.toDouble())
    }

    fun setCamera(center: LngLat, zoom: Double? = null, bearing: Double? = null) {
        val m = map
        if (m == null) {
            val p = pendingCam
            pendingCam = Cam(center, zoom ?: p?.zoom ?: 15.0, bearing ?: p?.bearing ?: 0.0)
            return
        }
        val now = m.cameraPosition
        val pos = CameraPosition(
            LatLng(center.lat, center.lng),
            zoom?.let { (it + ZOOM_OFFSET).toFloat() } ?: now.zoom,
            0f,
            bearing?.toFloat() ?: now.bearing,
        )
        m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    fun visibleBounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.southwest.longitude, b.southwest.latitude, b.northeast.longitude, b.northeast.latitude)
    }

    fun screenPoint(geo: LngLat): PointF? = map?.projection?.toScreenLocation(LatLng(geo.lat, geo.lng))?.let { PointF(it.x.toFloat(), it.y.toFloat()) }

    // ---------- base ----------

    fun setBase(b: Base, settings: com.brandonthomas.oldground.data.Settings) {
        val key = when (b) {
            is Base.Geo -> "geo-${b.layer}-" + (if (b.layer == Layer.LIDAR) Sources.raster(Layer.LIDAR, settings, null)?.id else "")
            is Base.Scan -> "scan-${b.raster.id}-${b.raster.url}"
        }
        base = b
        if (b is Base.Geo && b.layer == Layer.LIDAR) lidarRaster = Sources.raster(Layer.LIDAR, settings, null)
        if (key == baseKey) return
        baseKey = key
        applyBase()
    }

    private var lidarRaster: Sources.Raster? = null

    /** Rechecks things that depend on having signal (Satellite falls back to saved USGS imagery). */
    fun refreshBase() = applyBase()

    private fun applyBase() {
        val m = map ?: return
        val b = base ?: return
        baseTiles?.remove(); baseTiles = null
        storedAerial?.remove(); storedAerial = null
        var bg: Int? = null
        var tiles: Sources.Raster? = null
        when (b) {
            is Base.Geo -> when (b.layer) {
                Layer.STREETS -> m.mapType = GoogleMap.MAP_TYPE_NORMAL
                // Satellite with road and place names (shops and businesses stay hidden, see QUIET_STYLE).
                Layer.AERIAL -> m.mapType = GoogleMap.MAP_TYPE_HYBRID
                Layer.TOPO -> { m.mapType = GoogleMap.MAP_TYPE_NONE; bg = BG; tiles = Sources.raster(Layer.TOPO, com.brandonthomas.oldground.data.Settings(), null) }
                Layer.LIDAR -> { m.mapType = GoogleMap.MAP_TYPE_SATELLITE; tiles = lidarRaster }
                Layer.OLDMAP -> { m.mapType = GoogleMap.MAP_TYPE_NONE; bg = BG }
            }
            is Base.Scan -> { m.mapType = GoogleMap.MAP_TYPE_NONE; bg = SCAN_BG; tiles = b.raster }
        }
        setBackground(bg)
        tiles?.let { r -> baseTiles = m.addTileOverlay(TileOverlayOptions().tileProvider(Tiles.Provider(r)).zIndex(Z_BASE).fadeIn(false)) }
        // Without signal Google's imagery can't load: show saved USGS imagery instead.
        val satellite = b is Base.Geo && (b.layer == Layer.AERIAL || b.layer == Layer.LIDAR)
        if (satellite && !Tiles.online()) {
            storedAerial = m.addTileOverlay(TileOverlayOptions().tileProvider(Tiles.Provider(Sources.usgsAerial, storedOnly = true)).zIndex(Z_BASE - 1).fadeIn(false))
        }
        updateCredits()
    }

    private fun setBackground(color: Int?) {
        if (color == backgroundColor && (color == null) == (background == null)) return
        background?.remove(); background = null
        backgroundColor = color
        val m = map ?: return
        if (color != null) background = m.addTileOverlay(TileOverlayOptions().tileProvider(SolidTiles(color)).zIndex(Z_BG).fadeIn(false))
    }

    private fun updateCredits() {
        val usgs = listOfNotNull(
            (base as? Base.Geo)?.let { g -> if (g.layer == Layer.TOPO || storedAerial != null) Sources.USGS_ATTRIB else if (g.layer == Layer.LIDAR) "USGS 3DEP" else null },
            overlay?.attribution?.takeIf { it.isNotEmpty() },
        ).distinct()
        credits.text = usgs.joinToString(" · ")
        credits.visibility = if (usgs.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ---------- overlay ----------

    fun setOverlay(r: Sources.Raster?, opacity: Float = 1f) {
        val changed = r != overlay
        overlay = r
        overlayOpacity = opacity
        if (changed) applyOverlay() else overlayTiles?.transparency = 1f - opacity
    }

    private fun applyOverlay() {
        val m = map ?: return
        overlayTiles?.remove(); overlayTiles = null
        val r = overlay
        if (r != null) {
            overlayTiles = m.addTileOverlay(
                TileOverlayOptions().tileProvider(Tiles.Provider(r)).zIndex(Z_OVERLAY).transparency(1f - overlayOpacity).fadeIn(false),
            )
        }
        updateCredits()
    }

    // ---------- images (photo pins) ----------

    fun hasImage(id: String) = id in images

    /** Adds map images by id (pin photos). */
    fun addImages(add: Map<String, Bitmap>) {
        val fresh = add.filterKeys { it !in images }
        if (fresh.isEmpty()) return
        images.putAll(fresh)
        draw(Data.PINS)
    }

    // ---------- data ----------

    fun setData(d: Data, fc: FeatureCollection) {
        data[d] = fc
        draw(d)
    }

    private fun clear(d: Data) {
        if (d == Data.PINS) pinLabels.clear()
        if (d == Data.SITES) siteLabels.clear()
        drawn.remove(d)?.forEach {
            when (it) {
                is Marker -> it.remove()
                is Polyline -> it.remove()
                is GPolygon -> it.remove()
            }
        }
    }

    private fun draw(d: Data) {
        val m = map ?: return
        if (d == Data.GPS) return drawGps(m)
        clear(d)
        val fc = data[d] ?: return
        val out = ArrayList<Any>()
        val z = Z_DATA + d.ordinal
        for (f in fc.features()) when (d) {
            Data.LAND -> {
                val color = landColor(f.string("Mang_Type"))
                for (p in polygons(f.geometry)) out.add(m.addPolygon(polygonOptions(p).fillColor(alpha(color, 0.16f)).strokeColor(alpha(color, 0.9f)).strokeWidth(2 * density).zIndex(z)))
            }
            Data.SITES -> for (p in polygons(f.geometry)) {
                out.add(m.addPolygon(polygonOptions(p).fillColor(alpha(TEAL, 0.10f)).strokeWidth(0f).zIndex(z)))
                out.add(m.addPolyline(PolylineOptions().addAll(ll(p.rings.first())).color(alpha(Color.WHITE, 0.8f)).width(4.5f * density).zIndex(z)))
                out.add(m.addPolyline(PolylineOptions().addAll(ll(p.rings.first())).color(TEAL).width(2.5f * density).zIndex(z + 0.1f)))
                val name = f.string("name").orEmpty()
                if (name.isNotEmpty()) centroid(p.rings.first())?.let { c ->
                    val mk = m.addMarker(MarkerOptions().position(c).icon(icon("label|$name") { textBitmap(name, 12f, Color.parseColor("#0b5a54")) }).anchor(0.5f, 0.5f).zIndex(z))!!
                    siteLabels.add(SiteLabel(mk, labelW(name, 12f), labelH(12f)))
                    out.add(mk)
                }
            }
            Data.DRAFT -> when (val g = f.geometry) {
                is Polygon -> {
                    out.add(m.addPolygon(polygonOptions(g).fillColor(alpha(TEAL, 0.22f)).strokeWidth(0f).zIndex(z)))
                    out.add(m.addPolyline(PolylineOptions().addAll(ll(g.rings.first())).color(Color.WHITE).width(5 * density).zIndex(z)))
                    out.add(m.addPolyline(PolylineOptions().addAll(ll(g.rings.first())).color(TEAL).width(3 * density).jointType(JointType.ROUND).zIndex(z + 0.1f)))
                }
                is LineString -> {
                    out.add(m.addPolyline(PolylineOptions().addAll(ll(g.points)).color(Color.WHITE).width(5 * density).zIndex(z)))
                    out.add(m.addPolyline(PolylineOptions().addAll(ll(g.points)).color(TEAL).width(3 * density).zIndex(z + 0.1f)))
                }
                is Point -> {
                    val last = f.boolean("last") == true
                    val ic = if (last) icon("corner-last") { circleBitmap(10f, TEAL, Color.WHITE, 3f) } else icon("corner") { circleBitmap(8f, Color.WHITE, TEAL, 3f) }
                    out.add(m.addMarker(MarkerOptions().position(LatLng(g.lat, g.lng)).icon(ic).anchor(0.5f, 0.5f).zIndex(z))!!)
                }
                else -> {}
            }
            Data.GUIDE -> (f.geometry as? LineString)?.let { g ->
                out.add(m.addPolyline(PolylineOptions().addAll(ll(g.points)).color(TEAL).width(3 * density).pattern(listOf(Dash(4.5f * density), Gap(4.5f * density))).zIndex(z)))
            }
            Data.TRACKS -> (f.geometry as? LineString)?.let { g ->
                val color = alpha(parseColor(f.string("c") ?: "#ff6d00"), (f.number("o") ?: 0.85).toFloat())
                out.add(
                    m.addPolyline(
                        PolylineOptions().addAll(ll(g.points)).color(color).width(((f.number("w") ?: 3.0) * density).toFloat())
                            .jointType(JointType.ROUND).startCap(RoundCap()).endCap(RoundCap()).zIndex(z),
                    ),
                )
            }
            Data.MESH -> (f.geometry as? LineString)?.let { g ->
                out.add(m.addPolyline(PolylineOptions().addAll(ll(g.points)).color(parseColor(f.string("color") ?: "#1e5ac8")).width(((f.number("w") ?: 1.2) * density).toFloat()).zIndex(z)))
            }
            Data.ANCHORS -> (f.geometry as? Point)?.let { g ->
                val color = parseColor(f.string("color") ?: "#1e5ac8")
                val r = (f.number("r") ?: 7.0).toFloat()
                val mk = m.addMarker(MarkerOptions().position(LatLng(g.lat, g.lng)).icon(icon("anchor|$color|$r") { circleBitmap(r, color, Color.WHITE, 2f) }).anchor(0.5f, 0.5f).zIndex(z))!!
                mk.tag = f.string("id")
                out.add(mk)
            }
            Data.PINS -> (f.geometry as? Point)?.let { g ->
                val name = f.string("name").orEmpty()
                val photo = f.string("photo")?.let { images[it] }
                val color = parseColor(f.string("color") ?: "#D4A017")
                val pl = PinLabel(null, f.string("photo").takeIf { photo != null }, photo, color, name)
                val (ic, anchorY) = pinIcon(pl, withLabel = false)
                val mk = m.addMarker(MarkerOptions().position(LatLng(g.lat, g.lng)).icon(ic).anchor(0.5f, anchorY).zIndex(z))!!
                mk.tag = f.string("id")
                pl.marker = mk
                pinLabels.add(pl)
                out.add(mk)
            }
            Data.GHOST -> (f.geometry as? Point)?.let { g ->
                val color = parseColor(f.string("color") ?: "#0f766e")
                out.add(m.addMarker(MarkerOptions().position(LatLng(g.lat, g.lng)).icon(icon("ghost|$color") { ghostBitmap(color) }).anchor(0.5f, 0.5f).zIndex(z))!!)
            }
            Data.GPS -> {}
        }
        drawn[d] = out
        if (d == Data.PINS || d == Data.SITES) layoutLabels()
    }

    // ---------- labels ----------

    private class PinLabel(var marker: Marker?, val photoKey: String?, val photo: Bitmap?, val color: Int, val name: String, var showing: Boolean = false)
    private class SiteLabel(val marker: Marker, val w: Float, val h: Float)
    private val pinLabels = ArrayList<PinLabel>()
    private val siteLabels = ArrayList<SiteLabel>()

    private fun pinIcon(p: PinLabel, withLabel: Boolean): Pair<BitmapDescriptor, Float> {
        val name = if (withLabel) p.name else ""
        val spec = "pin|${p.color}|${p.photoKey}|$name"
        return pinIcons.getOrPut(spec) { pinBitmap(p.photo, p.color, name).let { (b, a) -> BitmapDescriptorFactory.fromBitmap(b) to a } }
    }

    private fun labelW(text: String, sizeSp: Float) = textPaint(sizeSp, Color.BLACK).measureText(text) + 8 * density
    private fun labelH(sizeSp: Float) = sizeSp * density * 1.5f

    /**
     * Shows each name only where it has room, like the map's own labels: pins first, then
     * site names; a name that would cover a pin or another name is left off until you zoom in.
     */
    private fun layoutLabels() {
        val m = map ?: return
        val proj = m.projection
        val taken = ArrayList<android.graphics.RectF>()
        val pins = pinLabels.filter { it.marker != null }
        val at = pins.map { proj.toScreenLocation(it.marker!!.position) }
        pins.forEachIndexed { i, p ->
            val r = (p.photo?.width ?: (2 * (8 + 2.5f) * density + 2).toInt()) / 2f
            taken.add(android.graphics.RectF(at[i].x - r, at[i].y - r, at[i].x + r, at[i].y + r))
        }
        pins.forEachIndexed { i, p ->
            var show = false
            if (p.name.isNotEmpty()) {
                val r = (p.photo?.height ?: (2 * (8 + 2.5f) * density + 2).toInt()) / 2f
                val w = labelW(p.name, 13f)
                val box = android.graphics.RectF(at[i].x - w / 2, at[i].y + r, at[i].x + w / 2, at[i].y + r + labelH(13f))
                show = taken.none { android.graphics.RectF.intersects(it, box) }
                if (show) taken.add(box)
            }
            if (show != p.showing) {
                p.showing = show
                val (ic, anchorY) = pinIcon(p, show)
                p.marker!!.setIcon(ic)
                p.marker!!.setAnchor(0.5f, anchorY)
            }
        }
        for (l in siteLabels) {
            val c = proj.toScreenLocation(l.marker.position)
            val box = android.graphics.RectF(c.x - l.w / 2, c.y - l.h / 2, c.x + l.w / 2, c.y + l.h / 2)
            val show = taken.none { android.graphics.RectF.intersects(it, box) }
            if (show) taken.add(box)
            l.marker.isVisible = show
        }
    }

    /** The GPS dot moves often, so its markers are moved rather than redrawn. */
    private fun drawGps(m: GoogleMap) {
        val f = data[Data.GPS]?.features()?.firstOrNull()
        val g = f?.geometry as? Point
        if (g == null) {
            gpsMarkers?.let { (a, b) -> a.remove(); b.remove() }
            gpsMarkers = null
            return
        }
        val at = LatLng(g.lat, g.lng)
        val heading = (f.number("heading") ?: 0.0).toFloat()
        val hasHeading = (f.number("hasHeading") ?: 0.0) > 0.5
        val existing = gpsMarkers
        if (existing == null) {
            val arrow = m.addMarker(MarkerOptions().position(at).icon(icon("gps-arrow") { arrowBitmap() }).anchor(0.5f, 0.5f).flat(true).rotation(heading).visible(hasHeading).zIndex(Z_DATA + 20))!!
            val dot = m.addMarker(MarkerOptions().position(at).icon(icon("gps-dot") { gpsBitmap() }).anchor(0.5f, 0.5f).zIndex(Z_DATA + 21))!!
            gpsMarkers = arrow to dot
        } else {
            val (arrow, dot) = existing
            arrow.position = at; arrow.rotation = heading; arrow.isVisible = hasHeading
            dot.position = at
        }
    }

    // ---------- public land under a tap ----------

    private fun landAt(at: LngLat): Map<String, String>? {
        val fc = data[Data.LAND] ?: return null
        for (f in fc.features()) for (p in polygons(f.geometry)) {
            if (inside(p.rings.first(), at) && p.rings.drop(1).none { inside(it, at) }) return f.propertyStrings()
        }
        return null
    }

    private fun inside(ring: List<Point>, g: LngLat): Boolean {
        var c = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > g.lat) != (b.lat > g.lat) && g.lng < (b.lng - a.lng) * (g.lat - a.lat) / (b.lat - a.lat) + a.lng) c = !c
            j = i
        }
        return c
    }

    // ---------- drawing helpers ----------

    private fun polygons(g: Geometry?): List<Polygon> = when (g) {
        is Polygon -> listOf(g)
        is MultiPolygon -> g.polygons
        else -> emptyList()
    }

    private fun ll(pts: List<Point>) = pts.map { LatLng(it.lat, it.lng) }

    private fun polygonOptions(p: Polygon): PolygonOptions {
        val o = PolygonOptions().addAll(ll(p.rings.first()))
        for (h in p.rings.drop(1)) if (h.size >= 3) o.addHole(ll(h))
        return o
    }

    private fun centroid(ring: List<Point>): LatLng? {
        if (ring.isEmpty()) return null
        return LatLng(ring.sumOf { it.lat } / ring.size, ring.sumOf { it.lng } / ring.size)
    }

    private val icons = HashMap<String, BitmapDescriptor>()
    private val pinIcons = HashMap<String, Pair<BitmapDescriptor, Float>>()
    private fun icon(key: String, make: () -> Bitmap): BitmapDescriptor = icons.getOrPut(key) { BitmapDescriptorFactory.fromBitmap(make()) }

    private fun circleBitmap(radiusDp: Float, fill: Int, stroke: Int, strokeDp: Float): Bitmap {
        val r = radiusDp * density
        val s = strokeDp * density
        val size = (2 * (r + s) + 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = stroke; c.drawCircle(size / 2f, size / 2f, r + s / 2, p)
        p.color = fill; c.drawCircle(size / 2f, size / 2f, r - s / 2, p)
        return bmp
    }

    private fun ghostBitmap(color: Int): Bitmap {
        val r = 15 * density
        val size = (2 * r + 8 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        p.color = Color.WHITE; p.strokeWidth = 5 * density; c.drawCircle(size / 2f, size / 2f, r, p)
        p.color = color; p.strokeWidth = 2.5f * density; c.drawCircle(size / 2f, size / 2f, r, p)
        return bmp
    }

    private fun gpsBitmap(): Bitmap {
        val halo = 22 * density
        val size = (2 * halo).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(56, 26, 115, 232); c.drawCircle(size / 2f, size / 2f, halo, p)
        p.color = Color.WHITE; c.drawCircle(size / 2f, size / 2f, 8.5f * density, p)
        p.color = Color.rgb(26, 115, 232); c.drawCircle(size / 2f, size / 2f, 5.5f * density, p)
        return bmp
    }

    private fun arrowBitmap(): Bitmap {
        val size = (64 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Path().apply {
            moveTo(size / 2f, size * 0.02f)
            lineTo(size / 2f + 10 * density, size / 2f - 8 * density)
            lineTo(size / 2f - 10 * density, size / 2f - 8 * density)
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 115, 232) })
        return bmp
    }

    private fun compassBitmap(): Bitmap {
        val size = (40 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(size / 2f, size / 2f, size / 2f - density, p)
        val cx = size / 2f; val cy = size / 2f
        p.color = Color.rgb(192, 57, 43)
        c.drawPath(Path().apply { moveTo(cx, cy - 13 * density); lineTo(cx + 5 * density, cy); lineTo(cx - 5 * density, cy); close() }, p)
        p.color = Color.rgb(120, 130, 128)
        c.drawPath(Path().apply { moveTo(cx, cy + 13 * density); lineTo(cx + 5 * density, cy); lineTo(cx - 5 * density, cy); close() }, p)
        return bmp
    }

    private fun textPaint(sizeSp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.color = color
        textAlign = Paint.Align.CENTER
    }

    /** Text with a white halo, like the map's own labels. */
    private fun textBitmap(text: String, sizeSp: Float, color: Int): Bitmap {
        val fill = textPaint(sizeSp, color)
        val halo = textPaint(sizeSp, Color.WHITE).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * density; strokeJoin = Paint.Join.ROUND }
        val w = (fill.measureText(text) + 8 * density).toInt().coerceAtLeast(1)
        val h = (fill.textSize * 1.5f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val y = h / 2f - (fill.descent() + fill.ascent()) / 2
        c.drawText(text, w / 2f, y, halo)
        c.drawText(text, w / 2f, y, fill)
        return bmp
    }

    /** A pin (its photo, or a coloured dot) with its name underneath. Returns the image and where the pin's centre sits (0..1 down it). */
    private fun pinBitmap(photo: Bitmap?, color: Int, name: String): Pair<Bitmap, Float> {
        val mark = photo ?: circleBitmap(8f, color, Color.WHITE, 2.5f)
        val label = name.takeIf { it.isNotEmpty() }?.let { textBitmap(it, 13f, Color.parseColor("#1f2622")) }
        val w = maxOf(mark.width, label?.width ?: 0)
        val h = mark.height + (label?.height ?: 0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawBitmap(mark, (w - mark.width) / 2f, 0f, null)
        label?.let { c.drawBitmap(it, (w - it.width) / 2f, mark.height.toFloat(), null) }
        return bmp to (mark.height / 2f) / h
    }

    private fun landColor(type: String?) = Color.parseColor(
        when (type) {
            "FED" -> "#2E8B57"
            "STAT" -> "#1E73BE"
            "LOC" -> "#8E44AD"
            "TRIB" -> "#8D6E63"
            "DIST" -> "#00897B"
            else -> "#F57C00"
        },
    )

    private fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun parseColor(s: String): Int = runCatching {
        val t = s.trim()
        if (t.startsWith("rgba(") || t.startsWith("rgb(")) {
            val v = t.substringAfter('(').substringBefore(')').split(',').map { it.trim().toFloat() }
            Color.argb(((v.getOrNull(3) ?: 1f) * 255).toInt(), v[0].toInt(), v[1].toInt(), v[2].toInt())
        } else Color.parseColor(t)
    }.getOrDefault(Color.GRAY)

    /** A plain colour under tile layers (Topo, old maps), where Google draws no map. */
    private class SolidTiles(color: Int) : TileProvider {
        private val bytes: ByteArray = run {
            val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            b.eraseColor(color)
            val o = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, o)
            o.toByteArray()
        }
        override fun getTile(x: Int, y: Int, zoom: Int) = Tile(256, 256, bytes)
    }

    // ---------- lifecycle ----------

    fun onStart() = mapView.onStart()
    fun onResume() {
        mapView.onResume()
        // Signal may have come or gone while away.
        if (base != null) applyBase()
    }
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() {
        onMove = null; onIdle = null; onTap = null; onLongTap = null; onReady = null
        idleListeners.clear()
        mapView.onDestroy()
    }
    fun onLowMemory() = mapView.onLowMemory()

    companion object {
        const val ZOOM_OFFSET = 1.0
        private val TEAL = Color.parseColor("#0f766e")
        private val BG = Color.parseColor("#e9e6dc")
        private val SCAN_BG = Color.parseColor("#ddd8c8")
        private const val Z_BG = -30f
        private const val Z_BASE = -20f
        private const val Z_OVERLAY = -10f
        private const val Z_DATA = 0f

        /** Streets without shops, businesses and transit icons, which only clutter a detecting map. */
        private const val QUIET_STYLE = """[
            {"featureType":"poi.business","stylers":[{"visibility":"off"}]},
            {"featureType":"poi.attraction","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
            {"featureType":"transit","elementType":"labels.icon","stylers":[{"visibility":"off"}]}
        ]"""
    }
}
