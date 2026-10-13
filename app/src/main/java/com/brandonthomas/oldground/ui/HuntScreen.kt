package com.brandonthomas.oldground.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.brandonthomas.oldground.MapRequest
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.LidarStyle
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.SITE_ACCESS
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Settings
import com.brandonthomas.oldground.data.Slot
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.Georef
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.TileMath
import com.brandonthomas.oldground.geo.XY
import com.brandonthomas.oldground.location.Fix
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.location.TrackService
import com.brandonthomas.oldground.map.Base
import com.brandonthomas.oldground.map.Cam
import com.brandonthomas.oldground.map.Data
import com.brandonthomas.oldground.map.Features
import com.brandonthomas.oldground.map.OfflineAreas
import com.brandonthomas.oldground.map.OgMap
import com.brandonthomas.oldground.map.PublicLand
import com.brandonthomas.oldground.map.Sources
import com.brandonthomas.oldground.map.SwipeFrame
import com.brandonthomas.oldground.map.TileJobs
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class Mode(val label: String, val hint: String) {
    OVERLAY("Overlay", "one map over another"),
    SWIPE("Swipe", "drag a divider between two maps"),
    SPLIT("Split", "two maps, one above the other"),
    DRIVE("Drive", "one map that follows and turns with you"),
}

/** Converts between real-world positions and the open old map's scan frame. */
class ScanFrame(val map: OldMap, val georef: Georef) {
    private val w = map.width
    private val h = map.height
    /** World -> scan-frame position, or null when off the sheet. */
    fun place(g: LngLat): LngLat? = georef.geoToImg(g)?.let { (img, on) -> if (on) TileMath.scanToLngLat(img, w, h) else null }
    /** World -> scan-frame position, even off the sheet (for lines and syncing). */
    fun placeAny(g: LngLat): LngLat? = georef.geoToImg(g)?.first?.let { TileMath.scanToLngLat(it, w, h) }
    fun toGeo(s: LngLat): LngLat? = georef.imgToGeo(TileMath.lngLatToScan(s, w, h))
    /** Zoom difference: scan zoom = map zoom + this, near a world position. */
    fun zoomShift(g: LngLat): Double {
        val img = georef.geoToImg(g)?.first ?: return 0.0
        val mpp = georef.mercPerImgPx(img) ?: return 0.0
        return TileMath.log2(mpp / TileMath.SCAN_MERC_PER_PX)
    }
    /** Which way is north on the scan, degrees clockwise from the scan's "up". */
    fun northAngle(g: LngLat): Double {
        val a = georef.geoToImg(g)?.first ?: return 0.0
        val b = georef.geoToImg(LngLat(g.lng, g.lat + 0.0002))?.first ?: return 0.0
        return Math.toDegrees(atan2(b.x - a.x, -(b.y - a.y)))
    }
    fun ready() = map.scan?.ready == true
    fun base(): Base? = map.scan?.takeIf { it.ready }?.let { Base.Scan(Sources.tiles(map.id, it, "scan-${it.zDone}")) }
}

/**
 * The hunt screen. Four views, each showing layers you choose (LiDAR, Satellite, Topo,
 * Streets, and the old map when one is open). Tap a ▾ label to change a layer.
 *   Overlay - one layer over another, with a fade slider
 *   Swipe   - drag a divider between two layers
 *   Split   - two panes, one above the other, kept in sync
 *   Drive   - one full-screen layer that follows you
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HuntScreen(
    nav: Nav,
    /** The old map on top (null: today's maps only). */
    mapId: String?,
    /** The latest thing asked to be shown here (a find, a site, outline a site…). */
    request: MapRequest?,
    /** False while another tab covers the map: its pop-up sheets stay closed. */
    visible: Boolean = true,
    onPickOldMap: (String?) -> Unit = {},
) {
    // The request this map opened with (decides where it first looks).
    val first = remember { request }
    val startMode = first?.mode
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val progress by TileJobs.progress.collectAsState()
    val settings = data.settings
    val map = data.maps.firstOrNull { it.id == mapId }
    val georef = remember(map?.anchors, map?.width, map?.height) { map?.let { Georef(it.anchorPoints(), it.width, it.height) } }
    val frame = remember(map, georef) { if (map != null && georef?.ready == true) ScanFrame(map, georef) else null }
    val hasOld = frame != null

    var mode by rememberSaveable { mutableStateOf(Mode.entries.firstOrNull { it.name == startMode } ?: Mode.OVERLAY) }
    val chosen = remember { mutableStateMapOf<Slot, Layer>() }
    var picking by remember { mutableStateOf<Slot?>(null) }
    var follow by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<PinDraft?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDownload by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var landInfo by remember { mutableStateOf<PublicLand.Info?>(null) }
    var cardPin by remember { mutableStateOf<String?>(null) }
    var guideTo by remember { mutableStateOf<String?>(null) }
    // Outlining a site: "new" or the id of the site being redrawn.
    var drawing by remember { mutableStateOf<String?>(null) }
    val redraw = drawing?.takeIf { it != "new" }?.let { id -> data.sites.firstOrNull { it.id == id } }
    val corners = remember { mutableStateListOf<LngLat>() }
    var siteName by remember { mutableStateOf("") }
    var siteAccess by remember { mutableStateOf(SITE_ACCESS.first()) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    /** A site just saved while standing in it: (id, name), offering Start visit. */
    var offerVisit by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(offerVisit) { if (offerVisit != null) { kotlinx.coroutines.delay(10000); offerVisit = null } }
    var modePicking by remember { mutableStateOf(false) }
    var oldMapPicking by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(savedNote) {
        if (savedNote != null) { kotlinx.coroutines.delay(4000); savedNote = null }
    }
    val camMemory = remember { arrayOfNulls<Cam>(1) }
    val userTookOver = remember { BooleanArray(1) }

    // Make sure tiles exist (and keep building in the background).
    LaunchedEffect(map?.anchors) {
        map?.let {
            TileJobs.ensureScan(it)
            if (it.aligned) TileJobs.ensureWarp(it)
        }
    }

    fun slot(k: Slot): Layer {
        chosen[k]?.let { if (it != Layer.OLDMAP || hasOld) return it }
        if (hasOld && k.primary) return Layer.OLDMAP
        return settings.slots[k] ?: k.default
    }
    fun choose(k: Slot, l: Layer) {
        chosen[k] = l
        if (l != Layer.OLDMAP) Repo.updateSettings { it.copy(slots = it.slots + (k to l)) }
    }

    val focus = first?.pinId?.let { id -> data.pins.firstOrNull { it.id == id } }
    val focusSite = (first?.siteId ?: first?.draw?.takeIf { it != "new" })?.let { id -> data.sites.firstOrNull { it.id == id } }
    val conf = LocalConfiguration.current
    val initial = remember {
        val siteBounds = focusSite?.let { Sites.bounds(it.ring) }
        if (focus == null && siteBounds != null) {
            Cam(Sites.center(focusSite.ring)!!, Sites.fitZoom(siteBounds, conf.screenWidthDp.toFloat(), conf.screenHeightDp * 0.55f), 0.0)
        } else {
            val c = focus?.geo ?: map?.anchors?.takeIf { it.isNotEmpty() }?.let { a ->
                LngLat(a.map { it.lng }.average(), a.map { it.lat }.average())
            } ?: LocationHub.fix.value?.geo ?: LngLat(-82.99, 39.96)
            Cam(c, if (first?.draw == "new" || focus != null) 17.5 else 16.0, 0.0)
        }
    }
    fun startCam() = camMemory[0] ?: initial

    // Maps currently on screen, with how to place real-world positions on each.
    val live = remember { mutableStateMapOf<String, Pair<OgMap, ScanFrame?>>() }

    /** Moves every map on screen to a real-world spot (placed on the scan for old-map panes). */
    fun flyTo(g: LngLat, zoom: Double? = null) {
        follow = false
        userTookOver[0] = true
        for ((name, p) in live) {
            if (name == "upper") continue
            val (m, scan) = p
            if (scan != null) scan.placeAny(g)?.let { m.setCamera(it, zoom?.let { z -> z + scan.zoomShift(g) }) }
            else m.setCamera(g, zoom)
        }
    }
    fun startOutline(d: String) {
        val site = d.takeIf { it != "new" }?.let { id -> Repo.current.sites.firstOrNull { it.id == id } }
        drawing = d
        corners.clear()
        site?.let { corners.addAll(it.ring) }
        siteName = site?.name ?: ""
        siteAccess = site?.access ?: SITE_ACCESS.first()
        pinMode = false
        cardPin = null
    }

    // Something asked to be shown here: a find, a site, a new outline, a visit to start.
    LaunchedEffect(request?.token) {
        val r = request ?: return@LaunchedEffect
        if (!com.brandonthomas.oldground.Once.first("map-request-${r.token}")) return@LaunchedEffect
        kotlinx.coroutines.delay(250) // let the maps attach
        r.mode?.let { m -> Mode.entries.firstOrNull { it.name == m }?.let { mode = it } }
        r.draw?.let { startOutline(it) }
        r.notice?.let { savedNote = it }
        val site = (r.siteId ?: r.draw?.takeIf { it != "new" })?.let { id -> Repo.current.sites.firstOrNull { it.id == id } }
        val pin = r.pinId?.let { id -> Repo.current.pins.firstOrNull { it.id == id } }
        when {
            pin != null -> {
                flyTo(pin.geo, 17.5)
                if (r.debug == null) cardPin = pin.id
            }
            site != null -> Sites.bounds(site.ring)?.let { b ->
                flyTo(Sites.center(site.ring)!!, Sites.fitZoom(b, conf.screenWidthDp.toFloat(), conf.screenHeightDp * 0.55f))
            }
            r.draw == "new" -> LocationHub.fix.value?.let { flyTo(it.geo, 17.5) }
        }
        if (r.startVisit && r.siteId != null) {
            if (Repo.activeVisit?.siteId != r.siteId) com.brandonthomas.oldground.location.VisitControl.start(ctx, r.siteId)
            follow = true
        }
        // Used by the automated screenshots to open a sheet or dialog.
        if (r.debug == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1300)
        when (r.debug) {
            "more" -> moreOpen = true
            "pin" -> pin?.let { draft = PinDraft.of(it) }
            "card" -> cardPin = pin?.id
            "new" -> pin?.let { draft = PinDraft(it.geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null) }
            "oldmaps" -> oldMapPicking = true
            "modes" -> modePicking = true
            "search" -> searching = true
            "place" -> pinMode = true
            "drawdemo" -> {
                // Four corners as if tapped, on open ground next to the seeded sites.
                val c = LngLat(-82.99080, 39.96060)
                startOutline("new")
                flyTo(c, 16.7)
                corners.addAll(listOf(LngLat(c.lng - 0.0010, c.lat + 0.0006), LngLat(c.lng + 0.0008, c.lat + 0.0008), LngLat(c.lng + 0.0010, c.lat - 0.0005), LngLat(c.lng - 0.0007, c.lat - 0.0007)))
                siteName = "Hendricks pasture"
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = visible && (drawing != null || pinMode)) {
        if (pinMode) pinMode = false else { drawing = null; corners.clear(); nav.tab(com.brandonthomas.oldground.Tab.SITES) }
    }

    // ---- shared map behaviour ----
    fun onTapAt(scan: ScanFrame?): (LngLat, String?, Map<String, String>?) -> Unit = { at, hit, land ->
        val geo = if (scan != null) scan.toGeo(at) else at
        when {
            drawing != null -> if (geo != null) corners.add(geo)
            pinMode && geo != null -> {
                pinMode = false
                draft = PinDraft(geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null)
            }
            hit != null -> cardPin = hit
            land != null -> landInfo = PublicLand.describe(land)
        }
    }
    /** Press and hold: drop a find right there (not while outlining a site). */
    fun onLongAt(scan: ScanFrame?): (LngLat) -> Unit = { at ->
        val geo = if (scan != null) scan.toGeo(at) else at
        if (geo != null && drawing == null) {
            pinMode = false
            draft = PinDraft(geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null)
        }
    }
    fun rememberCam(c: Cam, scan: ScanFrame?) {
        camMemory[0] = if (scan == null) c else scan.toGeo(c.center)?.let { g -> Cam(g, c.zoom - scan.zoomShift(g), 0.0) } ?: camMemory[0]
    }

    val statusTop = with(density) { WindowInsets.statusBars.getTop(this) }
    val compassTop = statusTop + with(density) { 112.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(Color(0xFFE9E6DC))) {
        key(mode, hasOld) {
            when (mode) {
                Mode.OVERLAY -> {
                    val m = rememberOgMap("overlay")
                    val top = slot(Slot.OVERLAY_TOP)
                    val bottom = slot(Slot.OVERLAY_BOTTOM)
                    LaunchedEffect(Unit) {
                        m.compassTopPx = compassTop
                        startCam().let { m.setCamera(it.center, it.zoom, it.bearing) }
                    }
                    LaunchedEffect(bottom, settings.lidarStyle, settings.lidarAzimuth) { m.setBase(Base.Geo(bottom, lidarKey(settings)), settings) }
                    LaunchedEffect(top, settings, map?.warp?.zDone, map?.warp?.dir) {
                        m.setOverlay(Sources.raster(top, settings, map), settings.overlayOpacity)
                    }
                    LiveMap("main", m, null, live)
                    SideEffect {
                        m.onTap = onTapAt(null)
                        m.onLongTap = onLongAt(null)
                        m.onMove = { c, user -> rememberCam(c, null); if (user) { follow = false; userTookOver[0] = true } }
                    }
                    MapBox(m, Modifier.fillMaxSize())
                    FlowRow(
                        Modifier.statusBarsPadding().padding(top = 52.dp, start = 10.dp, end = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LayerTag(top.label, { picking = Slot.OVERLAY_TOP })
                        Text("over", color = C.ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(50)).background(Color(0xD9FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp))
                        LayerTag(bottom.label, { picking = Slot.OVERLAY_BOTTOM })
                    }
                }

                Mode.SWIPE -> {
                    val lower = rememberOgMap("swipe-lower")
                    val upper = rememberOgMap("swipe-upper", texture = true, interactive = false)
                    val left = slot(Slot.SWIPE_LEFT)
                    val right = slot(Slot.SWIPE_RIGHT)
                    LaunchedEffect(Unit) {
                        lower.compassTopPx = compassTop
                        lower.rotateEnabled = false
                        startCam().let {
                            lower.setCamera(it.center, it.zoom, 0.0)
                            upper.setCamera(it.center, it.zoom, 0.0)
                        }
                    }
                    LaunchedEffect(right, settings.lidarStyle, settings.lidarAzimuth) { lower.setBase(Base.Geo(right, lidarKey(settings)), settings) }
                    LaunchedEffect(left, right, settings, map?.warp?.zDone, map?.warp?.dir) {
                        if (left == Layer.STREETS) {
                            upper.setBase(Base.Geo(Layer.STREETS), settings)
                            upper.setOverlay(null)
                        } else {
                            upper.setBase(Base.Geo(right, lidarKey(settings)), settings)
                            upper.setOverlay(Sources.raster(left, settings, map), 1f)
                        }
                    }
                    LiveMap("main", lower, null, live)
                    LiveMap("upper", upper, null, live)
                    SideEffect {
                        lower.onTap = onTapAt(null)
                        lower.onLongTap = onLongAt(null)
                        lower.onMove = { c, user ->
                            upper.setCamera(c.center, c.zoom, c.bearing)
                            rememberCam(c, null)
                            if (user) { follow = false; userTookOver[0] = true }
                        }
                        lower.onIdle = { c, _ -> upper.setCamera(c.center, c.zoom, c.bearing) }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = constraints.maxWidth
                        var dividerPx by remember { mutableFloatStateOf(widthPx / 2f) }
                        AndroidView(
                            factory = { SwipeFrame(it, lower.view, upper.view) },
                            update = { it.setDivider(dividerPx.roundToInt()) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Divider handle: only this strip takes touches.
                        Box(
                            Modifier
                                .offset { IntOffset(dividerPx.roundToInt() - with(density) { 24.dp.roundToPx() }, 0) }
                                .width(48.dp)
                                .fillMaxHeight()
                                .pointerInput(widthPx) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        dividerPx = (dividerPx + drag.x).coerceIn(24f, widthPx - 24f)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(Color.White))
                            Box(
                                Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(C.panel),
                                contentAlignment = Alignment.Center,
                            ) { Text("‹ ›", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 52.dp, start = 10.dp, end = 10.dp)) {
                        LayerTag(left.label, { picking = Slot.SWIPE_LEFT }, Modifier.align(Alignment.TopStart))
                        LayerTag(right.label, { picking = Slot.SWIPE_RIGHT }, Modifier.align(Alignment.TopEnd))
                    }
                }

                Mode.SPLIT -> {
                    val topLayer = slot(Slot.SPLIT_TOP)
                    val bottomLayer = slot(Slot.SPLIT_BOTTOM)
                    val leader = remember { arrayOf("top") }
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 50.dp)) {
                        val panes = listOf("top" to topLayer, "bottom" to bottomLayer)
                        val maps = HashMap<String, Pair<OgMap, ScanFrame?>>()
                        for ((side, layer) in panes) {
                            val scan = if (layer == Layer.OLDMAP) frame else null
                            key(side, layer, scan != null) {
                                val m = rememberOgMap("split-$side-$layer")
                                maps[side] = m to scan
                                LaunchedEffect(Unit) {
                                    m.rotateEnabled = false
                                    val c = startCam()
                                    if (scan != null) {
                                        scan.placeAny(c.center)?.let { m.setCamera(it, c.zoom + scan.zoomShift(c.center), 0.0) }
                                    } else m.setCamera(c.center, c.zoom, 0.0)
                                }
                                LaunchedEffect(layer, settings.lidarStyle, settings.lidarAzimuth, map?.scan?.zDone) {
                                    val b = if (scan != null) scan.base() else Base.Geo(layer, lidarKey(settings))
                                    b?.let { m.setBase(it, settings) }
                                }
                                LiveMap(side, m, scan, live)
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    MapBox(m, Modifier.fillMaxSize())
                                    LayerTag(layer.label, { picking = if (side == "top") Slot.SPLIT_TOP else Slot.SPLIT_BOTTOM }, Modifier.padding(8.dp))
                                }
                            }
                            if (side == "top") Box(Modifier.fillMaxWidth().height(3.dp).background(C.accent))
                        }
                        SideEffect {
                            for ((side, pair) in maps) {
                                val (m, scan) = pair
                                val otherSide = if (side == "top") "bottom" else "top"
                                m.onTap = onTapAt(scan)
                                m.onLongTap = onLongAt(scan)
                                m.onMove = { c, user ->
                                    if (user) {
                                        leader[0] = side
                                        follow = false
                                        userTookOver[0] = true
                                    }
                                    if (leader[0] == side) {
                                        rememberCam(c, scan)
                                        val geo = if (scan != null) scan.toGeo(c.center) else c.center
                                        val geoZoom = if (scan != null && geo != null) c.zoom - scan.zoomShift(geo) else c.zoom
                                        val other = maps[otherSide]
                                        if (geo != null && other != null) {
                                            val (om, oscan) = other
                                            if (oscan != null) oscan.placeAny(geo)?.let { om.setCamera(it, geoZoom + oscan.zoomShift(geo), 0.0) }
                                            else om.setCamera(geo, geoZoom, 0.0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Mode.DRIVE -> {
                    val layer = slot(Slot.DRIVE)
                    val scan = if (layer == Layer.OLDMAP) frame else null
                    key(layer, scan != null) {
                        val m = rememberOgMap("drive-$layer")
                        LaunchedEffect(Unit) {
                            follow = true
                            m.compassTopPx = compassTop
                            m.rotateEnabled = scan == null
                            val c = fix?.geo?.let { Cam(it, maxOf(startCam().zoom, 17.0), 0.0) } ?: startCam()
                            if (scan != null) scan.placeAny(c.center)?.let { m.setCamera(it, c.zoom + scan.zoomShift(c.center), 0.0) }
                            else m.setCamera(c.center, c.zoom, 0.0)
                        }
                        LaunchedEffect(layer, settings.lidarStyle, settings.lidarAzimuth, map?.scan?.zDone) {
                            val b = if (scan != null) scan.base() else Base.Geo(layer, lidarKey(settings))
                            b?.let { m.setBase(it, settings) }
                        }
                        LiveMap("main", m, scan, live)
                        SideEffect {
                            m.onTap = onTapAt(scan)
                            m.onLongTap = onLongAt(scan)
                            m.onMove = { c, user -> rememberCam(c, scan); if (user) follow = false }
                        }
                        MapBox(m, Modifier.fillMaxSize())
                        if (scan != null && fix != null && scan.place(fix!!.geo) == null) {
                            Toast("You're off the edge of this map", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 92.dp), C.bad)
                        }
                    }
                    LayerTag(layer.label, { picking = Slot.DRIVE }, Modifier.statusBarsPadding().padding(top = 52.dp, start = 10.dp))
                }
            }
        }

        // ---- keep data on every map current ----
        for ((_, pair) in live) {
            val (m, scan) = pair
            MapDataSync(
                m, scan, data.pins, trackPts, fix, settings.publicLand, guideTo?.let { id -> data.pins.firstOrNull { it.id == id } },
                // While redrawing, the old outline is hidden so only the new one shows.
                if (drawing != null && redraw != null) data.sites.filter { it.id != redraw.id } else data.sites,
                if (drawing != null) corners.toList() else emptyList(),
            )
        }

        // ---- follow me ----
        LaunchedEffect(fix, follow, mode) {
            val f = fix ?: return@LaunchedEffect
            // Without an old map, open on where you are once GPS finds you.
            if (!follow && mapId == null && first?.pinId == null && first?.siteId == null && first?.draw == null && !userTookOver[0] && camMemory[0] == null) {
                userTookOver[0] = true
                for ((_, p) in live) if (p.second == null) p.first.setCamera(f.geo)
                return@LaunchedEffect
            }
            if (!follow) return@LaunchedEffect
            for ((name, p) in live) {
                val (m, scan) = p
                if (name == "upper") continue
                if (scan != null) scan.placeAny(f.geo)?.let { m.setCamera(it) }
                else if (mode == Mode.DRIVE && f.heading != null) m.setCamera(f.geo, bearing = f.heading.toDouble())
                else m.setCamera(f.geo)
            }
        }

        // ---- top bar: one slim row ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (drawing != null) {
                RoundButton({ drawing = null; corners.clear(); nav.tab(com.brandonthomas.oldground.Tab.SITES) }, "Cancel", size = 38.dp) {
                    GlyphIcon(Glyph.CLOSE, C.ink, Modifier.size(18.dp))
                }
                Text(
                    if (corners.size < 3) "Tap each corner of the field or yard" else "Keep tapping corners, or save",
                    Modifier.weight(1f).card(radius = 999).padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1,
                )
            } else {
                RoundButton({ searching = true }, "Search a place", size = 38.dp) { GlyphIcon(Glyph.SEARCH, C.ink, Modifier.size(19.dp)) }
                MapChip("${mode.label} ▾", { modePicking = true })
                MapChip("${map?.name ?: "No old map"} ▾", { oldMapPicking = true }, Modifier.widthIn(max = 190.dp), highlight = map != null)
                Spacer(Modifier.weight(1f))
                RoundButton({ moreOpen = true }, "Layers", size = 38.dp) { GlyphIcon(Glyph.LAYERS, C.ink, Modifier.size(19.dp)) }
            }
        }

        // ---- status toasts ----
        val building = mapId?.let { progress["warp:$it"] ?: progress["scan:$it"] }
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (building != null) Toast("Preparing old map… ${(building * 100).toInt()}%")
            download?.let { Toast("Saving area offline… $it%") }
            savedNote?.let { Toast(it, color = C.accent) }
            offerVisit?.let { (id, name) ->
                Row(
                    Modifier.shadow(4.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(C.ink).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("$name saved", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Start visit",
                        Modifier.clip(RoundedCornerShape(50)).background(C.accent).clickable {
                            offerVisit = null
                            com.brandonthomas.oldground.location.VisitControl.start(ctx, id)
                            follow = true
                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                    )
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { offerVisit = null }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.CLOSE, Color.White, Modifier.size(14.dp))
                    }
                }
            }
            if (pinMode) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Toast("Tap the spot on the map", color = C.accent)
                Pill("My spot", {
                    pinMode = false
                    val f = LocationHub.fix.value
                    if (f == null) message = "No GPS fix yet" to "Wait for the blue dot, or tap the map where you want the pin."
                    else draft = PinDraft(f.geo, "", com.brandonthomas.oldground.data.PinKind.FIND, "", null)
                })
            }
        }

        // ---- bottom controls ----
        val recordingSince = data.recording?.let { r -> data.tracks.firstOrNull { it.id == r }?.startedAt }
        if (drawing != null) DrawPanel(
            corners.size, Sites.areaM2(corners), settings.metric, siteName, { siteName = it }, siteAccess, { siteAccess = it },
            editing = redraw != null,
            onUndo = { if (corners.isNotEmpty()) corners.removeAt(corners.lastIndex) },
            onSave = {
                val outline = corners.map { listOf(it.lng, it.lat) }
                val name = siteName.trim().ifBlank { "Site ${data.sites.size + 1}" }
                if (redraw != null) {
                    Repo.updateSite(redraw.id) { it.copy(name = name, access = siteAccess, outline = outline) }
                    drawing = null
                    corners.clear()
                    nav.tab(com.brandonthomas.oldground.Tab.SITES)
                } else {
                    val id = Repo.newId()
                    Repo.addSite(Site(id, name, siteAccess, outline, createdAt = System.currentTimeMillis()))
                    drawing = null
                    corners.clear()
                    // Saving only saves: a site is often planned at home. If you're standing in it,
                    // starting a visit is offered, never automatic.
                    val inside = LocationHub.fix.value?.let { f -> Sites.contains(outline.map { LngLat(it[0], it[1]) }, f.geo) } == true
                    if (inside && Repo.activeVisit == null) offerVisit = id to name else savedNote = "$name saved"
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) else MapControls(
            settings, follow, pinMode, recordingSince, fix, data.pins, data.sites, data.visits.lastOrNull { it.endedAt == null },
            // + Find: tap where you found it, or use your GPS spot.
            onFind = { pinMode = !pinMode },
            onOpenPin = { p -> cardPin = p.id },
            onOpenSite = { s -> nav.go(Screen.Site(s.id)) },
            guided = guideTo?.let { id -> data.pins.firstOrNull { it.id == id } },
            onLocate = {
                val f = LocationHub.fix.value
                if (!follow && f != null) for ((name, p) in live) {
                    if (name == "upper") continue
                    val (m, scan) = p
                    if (scan != null) scan.placeAny(f.geo)?.let { m.setCamera(it) } else m.setCamera(f.geo)
                }
                follow = !follow
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ---- sheets and dialogs (not while another tab covers the map) ----
    if (!visible) return
    if (modePicking) ChoiceSheet("View", Mode.entries.map { m -> Choice(m.label + "  ·  " + m.hint, m == mode) { mode = m } }) { modePicking = false }
    if (oldMapPicking) OldMapSheet(
        maps = data.maps, current = map, here = fix?.geo, overlay = mode == Mode.OVERLAY && slot(Slot.OVERLAY_TOP) == Layer.OLDMAP,
        opacity = settings.overlayOpacity,
        onOpacity = { v -> Repo.updateSettings { it.copy(overlayOpacity = v) } },
        onPick = { id -> oldMapPicking = false; if (id != mapId) onPickOldMap(id) },
        onLineUp = { id -> oldMapPicking = false; nav.go(Screen.Align(id)) },
        onAdd = { oldMapPicking = false; nav.go(Screen.OldMapsRoot) },
        onDismiss = { oldMapPicking = false },
    )
    if (searching) SearchSheet(fix?.geo, onPick = { g -> searching = false; flyTo(g, 16.0) }) { searching = false }
    picking?.let { k ->
        val current = slot(k)
        ChoiceSheet(k.title, k.options.filter { it != Layer.OLDMAP || hasOld }.map { l -> Choice(l.label, l == current) { choose(k, l) } }) { picking = null }
    }
    if (moreOpen) {
        ModalBottomSheet(onDismissRequest = { moreOpen = false }, containerColor = C.panel) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode == Mode.OVERLAY) {
                    var v by remember { mutableFloatStateOf(settings.overlayOpacity) }
                    SectionTitle("${slot(Slot.OVERLAY_TOP).label} strength ${(v * 100).roundToInt()}%")
                    SlimSlider(v, { v = it }, { Repo.updateSettings { it.copy(overlayOpacity = v) } }, Modifier.fillMaxWidth())
                }
                SectionTitle("LiDAR look")
                Segmented(settings.lidarStyle, LidarStyle.entries.map { it to it.label }, { v -> Repo.updateSettings { it.copy(lidarStyle = v) } })
                if (settings.lidarStyle == LidarStyle.SUN) {
                    var az by remember { mutableFloatStateOf(settings.lidarAzimuth.toFloat()) }
                    Text("Sun ${az.roundToInt()}°", color = C.ink)
                    Slider(
                        value = az, onValueChange = { az = it }, valueRange = 0f..345f, steps = 22,
                        onValueChangeFinished = { Repo.updateSettings { it.copy(lidarAzimuth = az.roundToInt()) } },
                        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                    )
                }
                SectionTitle("Public land")
                Segmented(settings.publicLand, listOf(true to "Show", false to "Hide"), { v -> Repo.updateSettings { it.copy(publicLand = v) } })
                Text("Federal, state, county and city land from USGS. Tap an area to see who manages it.", color = C.muted, fontSize = 13.sp)
                Pill("Save this area for offline", {
                    moreOpen = false
                    val main = live["main"]?.takeIf { it.second == null } ?: live.values.firstOrNull { it.second == null }
                    val b = main?.first?.visibleBounds()
                    if (b == null) {
                        message = "Nothing to save here" to "Old maps are already stored on this device. Switch a pane to a map layer to save it."
                    } else {
                        val layers = visibleLayers(mode, ::slot).filter { it != Layer.OLDMAP }.ifEmpty { listOf(Layer.AERIAL) }
                        val tiles = OfflineAreas.estimateTiles(b[0], b[1], b[2], b[3], 10, 17) * layers.size
                        if (tiles > 20000) message = "Area too large" to "Zoom in closer to the area you'll explore and try again."
                        else confirmDownload = "${layers.joinToString(" and ") { it.label }}, about $tiles tiles. Old maps are always available offline." to {
                            download = 0
                            val name = "${map?.name ?: "Saved area"} ${java.text.DateFormat.getDateInstance().format(java.util.Date())}"
                            if (settings.publicLand) Repo.scope.launch { PublicLand.prefetch(ctx, b) }
                            OfflineAreas.download(ctx, name, b, layers, settings, { download = it }) { err ->
                                download = null
                                if (err != null) message = "Download stopped" to err
                            }
                        }
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
    confirmDownload?.let { (text, go) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Save this area for offline use?") },
            text = { Text(text) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDownload = null; go() }) { Text("Download") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } },
            containerColor = C.panel,
        )
    }
    draft?.let { d ->
        PinDialog(d, fix?.let { Mercator.distanceMeters(it.geo, d.geo) }, settings.metric, settings.lastDetector, Sites.siteAt(data.sites, d.geo)?.name,
            onCancel = { draft = null },
            onDelete = d.editing?.let { id -> { Repo.deletePin(id); if (guideTo == id) guideTo = null; draft = null } },
        ) { s ->
            if (s.editing != null) Repo.updatePin(s.editing) {
                it.copy(
                    name = s.name.ifBlank { it.name }, kind = s.kind, note = s.note, photos = s.photos, category = s.category,
                    material = s.material, depthIn = s.depthIn, vdi = s.vdi, detector = s.detector, rating = s.rating,
                )
            } else Repo.addPin(
                Pin(
                    Repo.newId(), s.name.ifBlank { if (s.kind == com.brandonthomas.oldground.data.PinKind.FIND) s.category ?: "Find" else "Pin" },
                    s.kind, s.geo.lng, s.geo.lat, s.note, mapId, System.currentTimeMillis(), s.photos, s.category, s.material,
                    s.depthIn, s.vdi, s.detector, s.rating,
                ),
            )
            if (!s.detector.isNullOrBlank() && s.detector != settings.lastDetector) Repo.updateSettings { it.copy(lastDetector = s.detector) }
            draft = null
        }
    }
    cardPin?.let { id -> data.pins.firstOrNull { it.id == id } }?.let { p ->
        FindCard(
            p, fix, settings.metric, p.mapId?.let { mid -> data.maps.firstOrNull { it.id == mid }?.name }, guideTo == p.id,
            onGuide = { guideTo = if (guideTo == p.id) null else p.id; cardPin = null },
            onEdit = { cardPin = null; draft = PinDraft.of(p) },
            onPost = { cardPin = null; nav.go(Screen.Compose(p.id)) },
            onDismiss = { cardPin = null },
        )
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
    landInfo?.let { i ->
        MessageDialog(i.title, listOf(i.manager, i.access).filter { it.isNotBlank() }.joinToString(" · ") + "\n\n" + i.advice) { landInfo = null }
    }
}

private fun lidarKey(s: Settings) = "${s.lidarStyle}-${s.lidarAzimuth}"

private fun visibleLayers(mode: Mode, slot: (Slot) -> Layer): List<Layer> = when (mode) {
    Mode.OVERLAY -> listOf(slot(Slot.OVERLAY_TOP), slot(Slot.OVERLAY_BOTTOM))
    Mode.SWIPE -> listOf(slot(Slot.SWIPE_LEFT), slot(Slot.SWIPE_RIGHT))
    Mode.SPLIT -> listOf(slot(Slot.SPLIT_TOP), slot(Slot.SPLIT_BOTTOM))
    Mode.DRIVE -> listOf(slot(Slot.DRIVE))
}

/** Registers a map as on screen while this is in the composition. */
@Composable
private fun LiveMap(name: String, m: OgMap, scan: ScanFrame?, live: MutableMap<String, Pair<OgMap, ScanFrame?>>) {
    androidx.compose.runtime.DisposableEffect(m, scan) {
        live[name] = m to scan
        onDispose { if (live[name]?.first === m) live.remove(name) }
    }
}

/** Pins, tracks and the GPS dot on one map, kept current. */
@Composable
private fun MapDataSync(
    m: OgMap, scan: ScanFrame?, pins: List<Pin>, tracks: Map<String, List<TrackPt>>, fix: Fix?, publicLand: Boolean, guided: Pin?,
    sites: List<Site> = emptyList(), drawCorners: List<LngLat> = emptyList(),
) {
    val ctx = LocalContext.current
    val markerPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    // Public land on real-world maps, refreshed whenever the map stops moving.
    androidx.compose.runtime.DisposableEffect(m, scan, publicLand) {
        if (scan != null || !publicLand) {
            m.setData(Data.LAND, Features.empty)
            onDispose { }
        } else {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
            var job: kotlinx.coroutines.Job? = null
            val refresh: () -> Unit = {
                job?.cancel()
                job = scope.launch {
                    val b = m.visibleBounds() ?: return@launch
                    val fc = try {
                        PublicLand.features(ctx, b, m.cam().zoom)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch // keep what's shown
                    }
                    m.setData(Data.LAND, fc ?: Features.empty)
                }
            }
            m.idleListeners.add(refresh)
            refresh()
            onDispose {
                m.idleListeners.remove(refresh)
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }
    val place: (LngLat) -> LngLat? = { g -> if (scan == null) g else scan.place(g) }
    val placeLine: (LngLat) -> LngLat? = { g -> if (scan == null) g else scan.placeAny(g) }
    LaunchedEffect(m, scan, sites) { m.setData(Data.SITES, Features.sites(sites, placeLine)) }
    LaunchedEffect(m, scan, drawCorners) { m.setData(Data.DRAFT, Features.draft(drawCorners, placeLine)) }
    LaunchedEffect(m, scan, pins) {
        // Round photo markers first, so photo pins never show without their picture.
        val firsts = pins.mapNotNull { it.photos.firstOrNull() }.distinct().filter { !m.hasImage(Features.photoImageId(it)) }
        if (firsts.isNotEmpty()) {
            val imgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                firsts.mapNotNull { n -> Photos.mapMarker(ctx, n, markerPx)?.let { Features.photoImageId(n) to it } }.toMap()
            }
            m.addImages(imgs)
        }
        m.setData(Data.PINS, Features.pins(pins.map { p -> if (p.photos.isNotEmpty() && !m.hasImage(Features.photoImageId(p.photos.first()))) p.copy(photos = emptyList()) else p }, place))
    }
    LaunchedEffect(m, scan, fix, guided) {
        val from = fix?.geo?.let(placeLine)
        val to = guided?.geo?.let(placeLine)
        m.setData(Data.GUIDE, Features.guide(from, to))
    }
    LaunchedEffect(m, scan, tracks) { m.setData(Data.TRACKS, Features.tracks(tracks.values, placeLine)) }
    LaunchedEffect(m, scan, fix) {
        val north = if (scan != null && fix != null) scan.northAngle(fix.geo) else 0.0
        m.setData(Data.GPS, Features.gps(fix, place, north))
    }
}

@Composable
private fun MapChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Box(
        modifier.height(38.dp).card(radius = 999).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (highlight) C.accent else C.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * The few controls the map needs, floating so the map gets the screen: Follow, the visit
 * (Start visit inside a site, or its clock and End), + Add pin, and a card while guiding.
 */
@Composable
private fun MapControls(
    settings: Settings, follow: Boolean, pinMode: Boolean, recordingSince: Long?, fix: Fix?, pins: List<Pin>, sites: List<Site>,
    visit: com.brandonthomas.oldground.data.VisitSession?,
    onFind: () -> Unit, onOpenPin: (Pin) -> Unit, onOpenSite: (Site) -> Unit, onLocate: () -> Unit, modifier: Modifier, guided: Pin? = null,
) {
    val ctx = LocalContext.current
    val here = fix?.let { f -> Sites.siteAt(sites, f.geo) }
    // Starting a visit asks once for permission to show the "visit in progress" notification.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        here?.let { com.brandonthomas.oldground.location.VisitControl.start(ctx, it.id) }
    }
    var confirmEnd by remember { mutableStateOf(false) }
    val since = visit?.startedAt ?: recordingSince
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val nearest = guided ?: fix?.let { f -> pins.minByOrNull { Mercator.distanceMeters(f.geo, it.geo) } }
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(46.dp).shadow(5.dp, CircleShape).clip(CircleShape).background(if (follow) C.accent else C.panel)
                    .clickable(onClickLabel = if (follow) "Stop following" else "Follow me", onClick = onLocate),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.FOLLOW, if (follow) Color.White else C.ink, Modifier.size(21.dp)) }
            Spacer(Modifier.weight(1f))
            // Visits: "Start visit" when you're inside a site; while one is on, its clock and End visit.
            val visitSite = visit?.let { v -> sites.firstOrNull { it.id == v.siteId } }
            if (since != null || here != null) Row(
                Modifier.height(46.dp).widthIn(max = 200.dp).shadow(5.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(C.panel)
                    .clickable(onClickLabel = if (since != null) "End visit" else "Start a visit") {
                        when {
                            since != null -> confirmEnd = true
                            here != null && Build.VERSION.SDK_INT >= 33 && settings.recordPath &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            here != null -> com.brandonthomas.oldground.location.VisitControl.start(ctx, here.id)
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (since != null) {
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(C.bad))
                    Text("${clock(now - since)} · End", color = C.badInk, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                } else {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(C.accent))
                    Text("Start visit", color = C.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (confirmEnd) ConfirmDialog(
                "End this visit?",
                (visitSite?.let { "Your visit to ${it.name}" } ?: "Recording") + " stops at ${clock(now - (since ?: now))}." +
                    if (visit != null) " Its time and path are saved with the site." else "",
                "End visit", { confirmEnd = false },
            ) {
                confirmEnd = false
                com.brandonthomas.oldground.location.VisitControl.end(ctx)
            }
            Row(
                Modifier.height(46.dp).shadow(5.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(if (pinMode) C.panel else C.accent)
                    .clickable(onClickLabel = if (pinMode) "Cancel" else "Add a pin", onClick = onFind).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!pinMode) GlyphIcon(Glyph.PLUS, Color.White, Modifier.size(16.dp))
                Text(if (pinMode) "Cancel" else "Add pin", color = if (pinMode) C.ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        // Only while guiding to a find: how far and which way.
        if (guided != null) Row(
            Modifier.fillMaxWidth().height(58.dp).card(radius = 16, elevation = 5)
                .then(if (nearest != null) Modifier.clickable { onOpenPin(nearest) } else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val photo = nearest?.photos?.firstOrNull()
            when {
                photo != null -> PhotoThumb(photo, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)), px = 140)
                nearest != null -> Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(11.dp).clip(CircleShape).background(hexColor(nearest.kind.color)))
                }
            }
            Column(Modifier.weight(1f)) {
                val top = when {
                    fix == null -> "Finding your location…"
                    nearest == null -> "No finds yet. Tap + Find, or hold a finger on the map."
                    else -> {
                        val d = Mercator.formatDistance(Mercator.distanceMeters(fix.geo, nearest.geo), settings.metric)
                        val dir = Mercator.compass(Mercator.bearing(fix.geo, nearest.geo))
                        "${nearest.name} · $d $dir"
                    }
                }
                Text(
                    (if (guided != null) "Guiding to " else "") + top,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (guided != null) C.accent else C.ink, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row {
                    if (here != null) Text(
                        "In ${here.name} ›", Modifier.clickable { onOpenSite(here) },
                        color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    val acc = fix?.accuracy?.let { "GPS ±${Mercator.formatDistance(it.toDouble(), settings.metric)}" }
                    if (acc != null) Text((if (here != null) "  ·  " else "") + acc, color = C.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Which old map lies on top: the ones that cover you first, then the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OldMapSheet(
    maps: List<com.brandonthomas.oldground.data.OldMap>, current: com.brandonthomas.oldground.data.OldMap?, here: LngLat?,
    overlay: Boolean, opacity: Float, onOpacity: (Float) -> Unit,
    onPick: (String?) -> Unit, onLineUp: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Old map on top", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, modifier = Modifier.padding(start = 4.dp))
            if (overlay && current != null) {
                var v by remember { mutableFloatStateOf(opacity) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("Strength ${(v * 100).roundToInt()}%", Modifier.width(118.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
                    SlimSlider(v, { v = it }, { onOpacity(v) }, Modifier.weight(1f))
                }
            }
            fun center(m: com.brandonthomas.oldground.data.OldMap) = m.anchors.takeIf { it.isNotEmpty() }?.let { a -> LngLat(a.map { it.lng }.average(), a.map { it.lat }.average()) }
            val sorted = maps.sortedWith(compareBy<com.brandonthomas.oldground.data.OldMap> { !it.aligned }.thenBy { m ->
                val c = center(m); if (here != null && c != null) Mercator.distanceMeters(here, c) else Double.MAX_VALUE
            })
            for (m in sorted) {
                val on = m.id == current?.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) C.accentSoft else Color.Transparent)
                        .clickable { if (m.aligned) onPick(m.id) else onLineUp(m.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MapThumb(m, Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold, fontSize = 15.sp, color = if (on) C.accent else C.ink, maxLines = 1)
                        val c = center(m)
                        val where = if (here != null && c != null) {
                            val d = Mercator.distanceMeters(here, c)
                            if (d < 1500) "Near you" else "${Mercator.formatDistance(d, false)} away"
                        } else null
                        Text(
                            if (!m.aligned) "Not lined up yet · tap to add anchors" else listOfNotNull(where, "${m.anchors.size} anchors").joinToString(" · "),
                            fontSize = 12.5.sp, color = if (m.aligned) C.muted else C.check,
                        )
                    }
                    if (on) Text("✓", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current == null) C.accentSoft else Color.Transparent)
                    .clickable { onPick(null) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.LAYERS, C.muted) }
                Column(Modifier.weight(1f)) {
                    Text("No old map", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (current == null) C.accent else C.ink)
                    Text("Just LiDAR, satellite, topo and public land", fontSize = 12.5.sp, color = C.muted)
                }
                if (current == null) Text("✓", color = C.accent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
            Pill("+ Add an old map", onAdd, Modifier.fillMaxWidth(), kind = "soft")
        }
    }
}

/** Find a town, road or place (or type coordinates) and fly there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(near: LngLat?, onPick: (LngLat) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Go to a place", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, modifier = Modifier.padding(start = 4.dp))
            PlaceSearch(near, onPick, Modifier.fillMaxWidth())
        }
    }
}

/** The panel shown while outlining a site. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawPanel(
    cornerCount: Int, areaM2: Double, metric: Boolean,
    name: String, onName: (String) -> Unit, access: String, onAccess: (String) -> Unit,
    editing: Boolean, onUndo: () -> Unit, onSave: () -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent)
    Column(
        modifier.fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color(0x5514201D), spotColor = Color(0x5514201D))
            .clip(shape).background(C.panel).imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD5DBD9)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Redraw outline" else "New site", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            Text(
                if (cornerCount < 3) "$cornerCount of 3+ corners" else "$cornerCount corners · ${Sites.formatArea(areaM2, metric)}",
                Modifier.clip(RoundedCornerShape(50)).background(C.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        LabeledField("Name") {
            androidx.compose.material3.OutlinedTextField(
                name, onName, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                placeholder = { Text("e.g. Miller homestead") },
            )
        }
        LabeledField("Access") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in SITE_ACCESS) SoftChip(a, a == access) { onAccess(a) }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton("Undo corner", C.soft, C.ink, cornerCount > 0, onUndo, Modifier.weight(1f))
            PanelButton(if (editing) "Save outline" else "Save and explore", C.accent, Color.White, cornerCount >= 3, onSave, Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun PanelButton(label: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) fg else fg.copy(alpha = 0.6f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}
