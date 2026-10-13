package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Anchor
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.geo.AnchorReport
import com.brandonthomas.oldground.geo.AnchorStatus
import com.brandonthomas.oldground.geo.Georef
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.geo.TileMath
import com.brandonthomas.oldground.geo.XY
import com.brandonthomas.oldground.location.LocationHub
import com.brandonthomas.oldground.map.Base
import com.brandonthomas.oldground.map.Data
import com.brandonthomas.oldground.map.Features
import com.brandonthomas.oldground.map.Sources
import com.brandonthomas.oldground.map.TileJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

fun statusHex(s: AnchorStatus?) = when (s) {
    AnchorStatus.GOOD -> C.goodHex
    AnchorStatus.CHECK -> C.checkHex
    AnchorStatus.BAD -> C.badHex
    else -> C.unknownHex
}

/** Starting zoom that fits the whole scan on screen. */
fun scanFitZoom(w: Int, h: Int, viewDp: Int): Double {
    val extent = max(w, h) * TileMath.SCAN_MERC_PER_PX
    return TileMath.log2(Mercator.WORLD * viewDp / (512 * extent)) - 0.2
}

/**
 * Anchor editor. Old map on top, today's map below, a crosshair on each; both move on
 * their own. Line both crosshairs up on the same landmark and tap + Anchor. Once two
 * anchors exist, each crosshair shows as a ring on the other map. Each anchor is checked
 * against its neighbours (green / yellow / red), and Mesh shows the triangles, with folds
 * in red.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlignScreen(nav: Nav, mapId: String) {
    val data by Repo.data.collectAsState()
    val progress by TileJobs.progress.collectAsState()
    val map = data.maps.firstOrNull { it.id == mapId }
    if (map == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val settings = data.settings
    val w = map.width
    val h = map.height
    val georef = remember(map.anchors, w, h) { Georef(map.anchorPoints(), w, h) }
    var report by remember { mutableStateOf<List<AnchorReport>>(emptyList()) }
    LaunchedEffect(georef) {
        delay(250)
        report = withContext(Dispatchers.Default) { georef.anchorReport() }
    }
    val statusById = report.associateBy { it.id }

    var selected by remember { mutableStateOf<String?>(null) }
    var showMesh by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var foldWarn by remember { mutableStateOf(false) }

    val top = rememberOgMap("align-top-$mapId")
    val bottom = rememberOgMap("align-bottom-$mapId")
    val screenDp = LocalConfiguration.current.screenWidthDp

    // Starting positions.
    LaunchedEffect(Unit) {
        top.rotateEnabled = false
        top.setCamera(TileMath.scanToLngLat(XY(w / 2.0, h / 2.0), w, h), scanFitZoom(w, h, screenDp), 0.0)
        val start = map.anchors.firstOrNull()?.let { LngLat(it.lng, it.lat) } ?: LocationHub.fix.value?.geo ?: LngLat(-82.99, 39.96)
        bottom.setCamera(start, if (map.anchors.isEmpty()) 13.0 else 16.0, 0.0)
    }

    // Bases.
    val scan = map.scan
    LaunchedEffect(scan?.zDone) {
        if (scan != null && scan.ready) top.setBase(Base.Scan(Sources.tiles(mapId, scan, "scan-${scan.zDone}")), settings)
    }
    LaunchedEffect(settings.alignBase, settings.lidarStyle, settings.lidarAzimuth) {
        bottom.setBase(Base.Geo(settings.alignBase, "${settings.lidarStyle}${settings.lidarAzimuth}"), settings)
    }

    fun toScan(img: XY) = TileMath.scanToLngLat(img, w, h)
    fun topImg() = TileMath.lngLatToScan(top.cam().center, w, h)

    // Anchors, mesh and rings on both maps.
    LaunchedEffect(map.anchors, report, selected, showMesh) {
        top.setData(Data.ANCHORS, Features.anchors(map.anchors.map {
            Features.AnchorDot(it.id, toScan(XY(it.imgX, it.imgY)), statusHex(statusById[it.id]?.status), it.id == selected)
        }))
        bottom.setData(Data.ANCHORS, Features.anchors(map.anchors.map {
            Features.AnchorDot(it.id, LngLat(it.lng, it.lat), statusHex(statusById[it.id]?.status), it.id == selected)
        }))
        top.setData(Data.MESH, if (showMesh) Features.mesh(georef.meshForDisplay().map { (c, f) -> c.map(::toScan) to f }) else Features.empty)
    }
    fun refreshRings() {
        if (!georef.ready) {
            top.setData(Data.GHOST, Features.empty)
            bottom.setData(Data.GHOST, Features.empty)
            return
        }
        bottom.setData(Data.GHOST, Features.ghost(georef.imgToGeo(topImg()), C.badHex))
        top.setData(Data.GHOST, Features.ghost(georef.geoToImg(bottom.cam().center)?.first?.let(::toScan), C.rustHex))
    }
    LaunchedEffect(georef) { refreshRings() }
    SideEffect {
        top.onMove = { _, _ -> if (georef.ready) bottom.setData(Data.GHOST, Features.ghost(georef.imgToGeo(topImg()), C.badHex)) }
        bottom.onMove = { cam, _ ->
            if (georef.ready) top.setData(Data.GHOST, Features.ghost(georef.geoToImg(cam.center)?.first?.let(::toScan), C.rustHex))
        }
        top.onTap = { _, hit, _ -> if (hit != null) selected = hit }
        bottom.onTap = { _, hit, _ -> if (hit != null) selected = hit }
    }

    fun select(a: Anchor) {
        selected = a.id
        top.setCamera(toScan(XY(a.imgX, a.imgY)))
        bottom.setCamera(LngLat(a.lng, a.lat))
    }

    fun addAnchor() {
        val img = topImg()
        if (img.x < 0 || img.y < 0 || img.x > w || img.y > h) {
            message = "Crosshair is off the old map" to "Move the old map so the crosshair sits on a landmark."
            return
        }
        val g = bottom.cam().center
        val a = Anchor(Repo.newId(), img.x, img.y, g.lng, g.lat)
        Repo.updateMap(mapId) { it.copy(anchors = it.anchors + a) }
        selected = a.id
    }

    fun moveSelected() {
        val id = selected ?: return
        val img = topImg()
        val g = bottom.cam().center
        Repo.updateMap(mapId) { m -> m.copy(anchors = m.anchors.map { if (it.id == id) it.copy(imgX = img.x, imgY = img.y, lng = g.lng, lat = g.lat) else it }) }
    }

    fun finish() {
        Repo.map(mapId)?.let { TileJobs.ensureWarp(it) }
        nav.replace(Screen.Hunt(mapId))
    }

    fun done() {
        when {
            map.anchors.size < 2 -> message = "Add at least 2 anchors" to
                "Two anchors line up a well-surveyed map. Old hand-drawn maps need many more, spread across the sheet."
            georef.foldCount > 0 -> foldWarn = true
            else -> finish()
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton({ nav.back() }, "Back") { GlyphIcon(Glyph.BACK, C.ink) }
            Text(map.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.ink, maxLines = 1)
            Pill("Done", { done() }, kind = "primary")
        }

        // ---- old map ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapBox(top, Modifier.fillMaxSize())
            Crosshair(C.bad, Modifier.align(Alignment.Center))
            val sp = progress["scan:$mapId"]
            Toast(if (sp != null) "Old map · sharpening ${(sp * 100).toInt()}%" else "Old map", Modifier.align(Alignment.TopStart).padding(10.dp))
        }

        // ---- tools ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("+ Anchor", ::addAnchor, kind = "primary", small = true)
            Pill("Move", ::moveSelected, kind = "soft", enabled = selected != null, small = true)
            Pill("Delete", {
                val id = selected
                if (id != null) {
                    Repo.updateMap(mapId) { m -> m.copy(anchors = m.anchors.filter { it.id != id }) }
                    selected = null
                }
            }, kind = "soft", enabled = selected != null, small = true)
            Pill("List ${map.anchors.size}", { listOpen = true }, kind = "soft", small = true)
            Pill(if (showMesh) "Mesh ✓" else "Mesh", { showMesh = !showMesh }, kind = if (showMesh) "tint" else "soft", small = true)
        }
        val checkCount = report.count { it.status == AnchorStatus.CHECK || it.status == AnchorStatus.BAD }
        val sel = selected?.let { statusById[it] }
        Text(
            if (map.anchors.size < 2) "${map.anchors.size}/2 anchors · put both crosshairs on the same spot"
            else "${map.anchors.size} anchors · " + (if (checkCount > 0) "$checkCount to check" else "all agree") +
                (if (georef.foldCount > 0) " · ${georef.foldCount} folds" else "") +
                (sel?.errorMeters?.let { " · selected off by ${Mercator.formatDistance(it, settings.metric)}" } ?: ""),
            color = C.muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )

        // ---- today's map ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapBox(bottom, Modifier.fillMaxSize())
            Crosshair(C.accent, Modifier.align(Alignment.Center))
            PlaceSearch(
                near = bottom.cam().center,
                onPick = { bottom.setCamera(it, 15.0) },
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxWidth(),
            )
            Segmented(
                settings.alignBase,
                listOf(Layer.STREETS to "Streets", Layer.TOPO to "Topo", Layer.AERIAL to "Satellite", Layer.LIDAR to "LiDAR"),
                { v -> Repo.updateSettings { it.copy(alignBase = v) } },
                Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 28.dp).fillMaxWidth(),
            )
        }
    }

    if (listOpen) {
        ModalBottomSheet(onDismissRequest = { listOpen = false }, containerColor = C.panel) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                Text("Anchors", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = C.ink)
                Text(
                    "Error = how far each anchor is from where its neighbours say it should be. Red anchors are probably on the wrong landmark.",
                    color = C.muted, modifier = Modifier.padding(vertical = 6.dp),
                )
                ScrollColumn(Modifier.height(360.dp)) {
                    val order = map.anchors.withIndex().sortedByDescending { statusById[it.value.id]?.errorMeters ?: 0.0 }
                    for ((i, a) in order) {
                        val r = statusById[a.id]
                        Row(
                            Modifier.fillMaxWidth().clickable { listOpen = false; select(a) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(statusHex(r?.status)))))
                            Spacer(Modifier.size(12.dp))
                            Text("Anchor ${i + 1}", Modifier.weight(1f), color = C.ink)
                            Text(r?.errorMeters?.let { Mercator.formatDistance(it, settings.metric) } ?: "—", fontWeight = FontWeight.SemiBold, color = C.ink)
                        }
                        Divider()
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
    if (foldWarn) {
        ConfirmDialog(
            "Mesh has folds",
            "Some anchors cross over each other (red triangles in Mesh). Check the red anchors before continuing.",
            "Continue anyway",
            onCancel = { foldWarn = false; showMesh = true },
        ) { foldWarn = false; finish() }
    }
}
