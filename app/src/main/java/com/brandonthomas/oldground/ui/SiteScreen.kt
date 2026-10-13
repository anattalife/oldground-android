package com.brandonthomas.oldground.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.brandonthomas.oldground.map.Features
import com.brandonthomas.oldground.map.Data
import com.brandonthomas.oldground.map.Base
import com.brandonthomas.oldground.data.Layer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.SITE_ACCESS
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.data.Visit
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.location.LocationHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

/** One site: the photo of everything found there, its totals, and each visit. */
@Composable
fun SiteScreen(nav: Nav, siteId: String, debug: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val site = data.sites.firstOrNull { it.id == siteId }
    if (site == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val metric = data.settings.metric
    val finds = remember(data.sites, data.pins) { Sites.findsBySite(data.sites, data.pins)[site.id].orEmpty() }
    val visits = remember(site, finds, trackPts) { Sites.visits(site, finds, trackPts) }
    var photoMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(debug == "edit") }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<java.time.LocalDate?>(if (debug == "visit") visits.firstOrNull { it.tracks.isNotEmpty() }?.day else null) }

    fun setPhoto(uri: Uri) {
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { Photos.import(ctx, uri) }
            if (n != null) {
                val old = site.photo
                Repo.updateSite(site.id) { it.copy(photo = n) }
                old?.let { Photos.delete(ctx, it) }
            }
            busy = false
        }
    }
    val cameraFile = remember { File(ctx.cacheDir, "camera-site.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) setPhoto(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::setPhoto) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        // ---- the finds photo ----
        Box(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF5E4A35))) {
            if (site.photo != null) PhotoThumb(site.photo, Modifier.fillMaxSize(), px = 1000)
            else Column(Modifier.align(Alignment.Center).padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlyphIcon(Glyph.CAMERA, Color.White, Modifier.size(34.dp))
                Text("Take one photo of all your finds laid out together", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
                Pill("Add finds photo", { photoMenu = true }, kind = "plain", small = true)
            }
            Box(Modifier.statusBarsPadding().padding(12.dp)) { RoundButton({ nav.back() }, "Back") { GlyphIcon(Glyph.BACK, C.ink) } }
            if (site.photo != null) Text(
                if (busy) "Adding photo…" else "Change finds photo",
                Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0xB814201D)).clickable { photoMenu = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(site.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                    val where = fix?.let { f ->
                        if (Sites.contains(site.ring, f.geo)) "you're here"
                        else Sites.center(site.ring)?.let { c -> "${Mercator.formatDistance(Mercator.distanceMeters(f.geo, c), metric)} ${Mercator.compass(Mercator.bearing(f.geo, c))}" }
                    }
                    Text(listOfNotNull(site.access, Sites.formatArea(Sites.areaM2(site.ring), metric), where).joinToString(" · "), fontSize = 13.sp, color = C.muted)
                }
                Pill("Edit", { editing = true }, kind = "soft", small = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${visits.size}", "visits", Modifier.weight(1f))
                Stat("${finds.size}", "finds", Modifier.weight(1f))
                Stat("${finds.count { it.material == "Silver" }}", "silver", Modifier.weight(1f))
                Stat(visits.sumOf { it.detectingMs }.takeIf { it > 0 }?.let(Sites::formatDuration) ?: "–", "detecting", Modifier.weight(1f))
            }
            if (site.access == "Permission" || site.permission != null) PermissionCard(nav, site, finds, metric)
            val visitingHere = data.visits.lastOrNull { it.endedAt == null }?.siteId == site.id
            Column(Modifier.fillMaxWidth().card(18).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visits", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                    if (visitingHere) Text(
                        "End visit", Modifier.clickable { com.brandonthomas.oldground.location.VisitControl.end(ctx) }.padding(4.dp),
                        color = C.bad, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                    ) else Text(
                        "+ Start", Modifier.clickable { nav.go(Screen.Hunt(null, siteId = site.id, startVisit = true)) }.padding(4.dp),
                        color = C.accent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
                val sel = visits.firstOrNull { it.day == selected }
                CoverageMap(site, visits, sel, Modifier.fillMaxWidth().height(190.dp)) { nav.go(Screen.Hunt(null, siteId = site.id)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            sel != null -> listOfNotNull(
                                sel.day.format(DAY),
                                sel.detectingMs.takeIf { it > 0 }?.let(Sites::formatDuration),
                                sel.walkedM.takeIf { it > 0 }?.let { Mercator.formatDistance(it, metric) },
                                "${sel.finds.size} find${if (sel.finds.size == 1) "" else "s"}",
                            ).joinToString(" · ")
                            visits.any { it.tracks.isNotEmpty() } -> "Your paths: newest bright, older ones fade"
                            data.settings.recordPath -> "Your path shows here after a visit"
                            else -> "Path recording is off in Settings"
                        },
                        Modifier.weight(1f), fontSize = 12.sp, color = C.muted, maxLines = 1,
                    )
                    if (sel != null) Text(
                        "Open ›", Modifier.clickable { nav.go(Screen.Visit(site.id, sel.day.toString())) }.padding(start = 8.dp),
                        color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (visits.isEmpty()) Text(
                    "No visits yet. Tap + Start when you get there. The time, your path and the finds you log inside the outline show up here by day.",
                    color = C.muted, fontSize = 14.sp, lineHeight = 20.sp,
                )
                for (v in visits) VisitCard(v, v.day == selected) { selected = if (selected == v.day) null else v.day }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (photoMenu) ChoiceSheet(
        "Finds photo",
        listOfNotNull(
            Choice("Take a photo") { takePhoto.launch(cameraUri) },
            Choice("Choose from gallery") { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            site.photo?.let { old -> Choice("Remove photo", danger = true) { Repo.updateSite(site.id) { it.copy(photo = null) }; Photos.delete(ctx, old) } },
        ),
    ) { photoMenu = false }
    if (editing) EditSiteSheet(
        site,
        onDismiss = { editing = false },
        onRedraw = { editing = false; nav.go(Screen.Hunt(null, draw = site.id)) },
        onDelete = {
            editing = false
            site.photo?.let { Photos.delete(ctx, it) }
            site.permission?.let { p -> com.brandonthomas.oldground.data.Permissions.file(ctx, p.signature).delete(); com.brandonthomas.oldground.data.Permissions.file(ctx, p.pdf).delete() }
            Repo.deleteSite(site.id)
            nav.back()
        },
    )
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.card(14, 2).padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, maxLines = 1)
        Text(label, fontSize = 11.5.sp, color = C.muted, maxLines = 1)
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d")

@Composable
private fun VisitCard(v: Visit, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFFFDF1E4) else C.soft.copy(alpha = 0.5f))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(v.day.format(DAY), Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = C.ink)
            Text(
                listOfNotNull(v.detectingMs.takeIf { it > 0 }?.let(Sites::formatDuration), "${v.finds.size} find${if (v.finds.size == 1) "" else "s"}").joinToString(" · "),
                fontSize = 12.5.sp, color = C.muted,
            )
        }
        if (v.finds.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val show = if (v.finds.size > 5) v.finds.take(4) else v.finds
            for (p in show) {
                val photo = p.photos.firstOrNull()
                if (photo != null) PhotoThumb(photo, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), px = 160)
                else Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(hexColor(p.kind.color)))
                }
            }
            if (v.finds.size > show.size) Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) {
                Text("+${v.finds.size - show.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.muted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditSiteSheet(site: Site, onDismiss: () -> Unit, onRedraw: () -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(site.name) }
    var access by remember { mutableStateOf(site.access) }
    var note by remember { mutableStateOf(site.note) }
    var confirm by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", Modifier.clickable(onClick = onDismiss).padding(vertical = 8.dp), color = C.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Edit site", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Pill("Save", {
                    Repo.updateSite(site.id) { it.copy(name = name.trim().ifBlank { it.name }, access = access, note = note.trim()) }
                    onDismiss()
                }, kind = "primary", small = true)
            }
            LabeledField("Name") {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
            }
            LabeledField("Access") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in SITE_ACCESS) SoftChip(a, a == access) { access = a }
                }
            }
            LabeledField("Notes") {
                OutlinedTextField(
                    note, { note = it }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    placeholder = { Text("Landowner, where to park, what's been found…") },
                )
            }
            Pill("Redraw the outline", onRedraw, Modifier.fillMaxWidth(), kind = "soft")
            Text(
                "Delete site", Modifier.align(Alignment.CenterHorizontally).clickable { confirm = true }.padding(8.dp),
                color = C.bad, fontWeight = FontWeight.Bold,
            )
        }
    }
    if (confirm) ConfirmDialog(
        "Delete this site?", "\"${site.name}\" and its outline will be removed. Its finds stay on the map and under All finds.", "Delete",
        onCancel = { confirm = false },
    ) { confirm = false; onDelete() }
}

/** The site with every visit's path: newest bright, older ones faded; a chosen visit stands out with its finds. */
@Composable
private fun CoverageMap(site: Site, visits: List<Visit>, sel: Visit?, modifier: Modifier, onClick: () -> Unit) {
    val conf = LocalConfiguration.current
    val m = rememberOgMap("cover-${site.id}", interactive = false)
    LaunchedEffect(m, site.outline) {
        val b = Sites.bounds(site.ring) ?: return@LaunchedEffect
        m.setCamera(Sites.center(site.ring)!!, Sites.fitZoom(b, conf.screenWidthDp - 60f, 190f, 0.85), 0.0)
        m.setBase(Base.Geo(Layer.AERIAL, "cover"), Repo.current.settings)
        m.setData(Data.SITES, Features.sites(listOf(site.copy(name = "")), { it }))
    }
    LaunchedEffect(m, visits, sel) {
        // visits are newest first: the newest is solid, each older one fainter (never below 0.25).
        val n = visits.size
        val paths = visits.withIndex().reversed().flatMap { (i, v) ->
            val o = if (n <= 1) 1.0 else 1.0 - 0.75 * i / (n - 1)
            v.tracks.map { pts ->
                when {
                    sel == null -> Features.Path(pts, "#ff8a3d", o, 3.0)
                    v.day == sel.day -> Features.Path(pts, "#ffe14d", 1.0, 4.0)
                    else -> Features.Path(pts, "#ffffff", 0.22, 2.0)
                }
            }
        }.sortedBy { if (sel != null && it.color == "#ffe14d") 1 else 0 }
        m.setData(Data.TRACKS, Features.paths(paths))
        m.setData(Data.PINS, Features.pins((sel?.finds ?: emptyList()).map { it.copy(name = "", photos = emptyList()) }, { it }))
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF6B7A45)).clickable(onClick = onClick)) {
        MapBox(m, Modifier.fillMaxSize())
    }
}
