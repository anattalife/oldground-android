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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.SITE_ACCESS
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.iconId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.data.Visit
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.location.LocationHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

/** Which list shows on a site's page. */
private enum class SiteTab(val label: String) { VISITS("Visits"), POINTS("Points"), NOTES("Notes") }

/**
 * One site, as its own little map: the outline with its points and paths, then its visits,
 * points and notes. Permission sites have a one-line landowner bar that opens everything
 * about the owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val points = remember(site, data.pins) { data.pins.filter { Sites.contains(site.ring, it.geo) }.sortedByDescending { it.createdAt } }
    val visits = remember(site, finds, trackPts) { Sites.visits(site, finds, trackPts) }
    var tab by remember { mutableStateOf(when (debug) { "points" -> SiteTab.POINTS; "notes", "note" -> SiteTab.NOTES; else -> SiteTab.VISITS }) }
    var photoMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(debug == "edit") }
    var owner by remember { mutableStateOf(debug == "owner") }
    var noteOpen by remember { mutableStateOf<Pair<Boolean, com.brandonthomas.oldground.data.SiteNote?>?>(if (debug == "note") true to site.notes.firstOrNull() else null) }
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
    val visitingHere = data.visits.lastOrNull { it.endedAt == null }?.siteId == site.id

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
        // ---- name ----
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton({ nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(site.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = fix?.let { f ->
                    if (Sites.contains(site.ring, f.geo)) "you're here"
                    else Sites.center(site.ring)?.let { c -> "${Mercator.formatDistance(Mercator.distanceMeters(f.geo, c), metric)} ${Mercator.compass(Mercator.bearing(f.geo, c))}" }
                }
                Text(
                    listOfNotNull(site.access, Sites.formatArea(Sites.areaM2(site.ring), metric), "${visits.size} visit${if (visits.size == 1) "" else "s"}", where).joinToString(" · "),
                    fontSize = 12.5.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Edit", Modifier.clip(RoundedCornerShape(50)).clickable { editing = true }.padding(horizontal = 8.dp, vertical = 6.dp), color = C.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- the site's own map ----
            val sel = visits.firstOrNull { it.day == selected }
            SiteMap(site, visits, sel, points, Modifier.fillMaxWidth().height(200.dp)) { nav.go(Screen.Hunt(null, siteId = site.id)) }

            // ---- add ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visitingHere) Pill("End visit", { com.brandonthomas.oldground.location.VisitControl.end(ctx) }, Modifier.weight(1f), kind = "danger", small = true)
                else Pill("+ Visit", { nav.go(Screen.Hunt(null, siteId = site.id, startVisit = true)) }, Modifier.weight(1f), kind = "primary", small = true)
                Pill("+ Point", { nav.go(Screen.Hunt(null, siteId = site.id, addPoint = true)) }, Modifier.weight(1f), kind = "tint", small = true)
                Pill("+ Note", { tab = SiteTab.NOTES; noteOpen = true to null }, Modifier.weight(1f), kind = "tint", small = true)
            }

            // ---- landowner ----
            if (site.access == "Permission" || site.permission != null) OwnerBar(site, onWriting = { nav.go(Screen.PermissionSlip(site.id)) }, onOwner = { owner = true })

            Segmented(
                tab,
                SiteTab.entries.map { t ->
                    t to "${t.label} ${when (t) { SiteTab.VISITS -> visits.size; SiteTab.POINTS -> points.size; SiteTab.NOTES -> site.notes.size }}"
                },
                { tab = it },
                Modifier.fillMaxWidth(),
            )

            when (tab) {
                SiteTab.VISITS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                data.settings.recordPath -> "Your path shows on the map after a visit"
                                else -> "Path recording is off in Settings"
                            },
                            Modifier.weight(1f), fontSize = 12.sp, color = C.muted, maxLines = 1,
                        )
                        if (sel != null) Text(
                            "Open ›", Modifier.clickable { nav.go(Screen.Visit(site.id, sel.day.toString())) }.padding(start = 8.dp),
                            color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    if (visits.isEmpty()) Empty("No visits yet. Tap + Visit when you get there: the time, your path and what you find show up here by day.")
                    for (v in visits) VisitCard(v, v.day == selected) { selected = if (selected == v.day) null else v.day }
                    FindsPhotoRow(site.photo, busy) { photoMenu = true }
                }
                SiteTab.POINTS -> Column(Modifier.fillMaxWidth().card(16, 2)) {
                    if (points.isEmpty()) Empty("No points yet. Tap + Point to mark a house site, a spring, a grave, anything worth finding again.", Modifier.padding(14.dp))
                    points.forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        PointRow(p, metric) { nav.go(Screen.Hunt(p.mapId, pinId = p.id, keepOldMap = true)) }
                    }
                }
                SiteTab.NOTES -> Column(Modifier.fillMaxWidth().card(16, 2)) {
                    if (site.notes.isEmpty()) Empty("No notes yet. Keep deeds, old maps, history and leads here with + Note.", Modifier.padding(14.dp))
                    site.notes.forEachIndexed { i, n ->
                        if (i > 0) Divider()
                        NoteRow(n) { noteOpen = true to n }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
    if (owner) ModalBottomSheet(
        onDismissRequest = { owner = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Box(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            PermissionCard(nav, site, finds, metric, Modifier.fillMaxWidth())
        }
    }
    noteOpen?.let { (_, n) -> NoteSheet(site.id, n) { noteOpen = null } }
    if (editing) EditSiteSheet(
        site,
        onDismiss = { editing = false },
        onRedraw = { editing = false; nav.go(Screen.Hunt(null, draw = site.id)) },
        onDelete = {
            editing = false
            site.photo?.let { Photos.delete(ctx, it) }
            site.permission?.let { p -> com.brandonthomas.oldground.data.Permissions.file(ctx, p.signature).delete(); com.brandonthomas.oldground.data.Permissions.file(ctx, p.pdf).delete() }
            for (n in site.notes) { n.photos.forEach { Photos.delete(ctx, it) }; n.files.forEach { com.brandonthomas.oldground.data.NoteFiles.delete(ctx, it) } }
            Repo.deleteSite(site.id)
            nav.back()
        },
    )
}

/** The landowner in one line: not in writing yet (with a way to get it), or who signed and until when. */
@Composable
private fun OwnerBar(site: Site, onWriting: () -> Unit, onOwner: () -> Unit) {
    val st = com.brandonthomas.oldground.data.Permissions.status(site)
    val p = site.permission
    val (badge, bg, fg) = when (st.level) {
        com.brandonthomas.oldground.data.Permissions.Level.OK -> Triple("Signed", C.accentSoft, C.accent)
        com.brandonthomas.oldground.data.Permissions.Level.SOON -> Triple("Expiring", Color(0xFFFEF3E2), Color(0xFFB45309))
        com.brandonthomas.oldground.data.Permissions.Level.EXPIRED -> Triple("Expired", C.badSoft, C.badInk)
        com.brandonthomas.oldground.data.Permissions.Level.NONE -> Triple("Verbal", Color(0xFFFEF3E2), Color(0xFFB45309))
    }
    Row(
        Modifier.fillMaxWidth().card(14, 2).clickable { if (p == null) onWriting() else onOwner() }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(badge, Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = fg)
        val line = when {
            p == null -> "Not in writing yet"
            p.expiresAt == null -> "${p.ownerName} · until withdrawn"
            else -> "${p.ownerName} · to ${SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(p.expiresAt))}"
        }
        Text(line, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (p == null) "Get it in writing ›" else "Owner ›",
            Modifier.clip(RoundedCornerShape(50)).background(C.accentSoft).padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = C.accent, maxLines = 1,
        )
    }
}

@Composable
private fun Empty(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = C.muted, fontSize = 13.5.sp, lineHeight = 19.sp)
}

@Composable
private fun PointRow(p: Pin, metric: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val photo = p.photos.firstOrNull()
        if (photo != null) PhotoThumb(photo, Modifier.size(34.dp).clip(CircleShape), px = 120) else PinBadge(p, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (p.isFind) listOfNotNull("Find", SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(p.createdAt)), p.depthIn?.let { depthLabel(it, metric) }).joinToString(" · ")
            else p.note.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: com.brandonthomas.oldground.data.PinIcons.icon(p.iconId).label
            Text(sub, fontSize = 12.5.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(16.dp))
    }
}

@Composable
private fun NoteRow(n: com.brandonthomas.oldground.data.SiteNote, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val photo = n.photos.firstOrNull()
        if (photo != null) PhotoThumb(photo, Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), px = 120)
        else Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(C.soft), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.BOOK, C.muted, Modifier.size(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(n.title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val extras = listOfNotNull(
                n.photos.size.takeIf { it > 0 }?.let { "$it photo${if (it == 1) "" else "s"}" },
                n.files.size.takeIf { it > 0 }?.let { "$it file${if (it == 1) "" else "s"}" },
            )
            Text(
                (extras + listOfNotNull(n.text.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() && it != n.title })).joinToString(" · ")
                    .ifBlank { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(n.createdAt)) },
                fontSize = 12.5.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        n.label?.let { Text(it, Modifier.clip(RoundedCornerShape(50)).background(C.soft).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.muted) }
    }
}

/** The one photo of everything found here, laid out together. */
@Composable
private fun FindsPhotoRow(photo: String?, busy: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(14, 2).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (photo != null) PhotoThumb(photo, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), px = 160)
        else Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.CAMERA, C.muted, Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text("Finds photo", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.ink)
            Text(if (busy) "Adding photo…" else "One picture of everything found here, laid out together", fontSize = 12.sp, color = C.muted, maxLines = 2)
        }
        Text(if (photo == null) "Add" else "Change", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.accent)
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
                    PinBadge(p, 26.dp)
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

/** The site's own map: its outline, every visit's path (newest bright, older faded) and its points. A chosen visit stands out. */
@Composable
private fun SiteMap(site: Site, visits: List<Visit>, sel: Visit?, points: List<Pin>, modifier: Modifier, onClick: () -> Unit) {
    val conf = LocalConfiguration.current
    val m = rememberOgMap("cover-${site.id}", interactive = false)
    LaunchedEffect(m, site.outline) {
        val b = Sites.bounds(site.ring) ?: return@LaunchedEffect
        m.setCamera(Sites.center(site.ring)!!, Sites.fitZoom(b, conf.screenWidthDp - 28f, 200f, 0.8), 0.0)
        m.setBase(Base.Geo(Layer.AERIAL, "cover"), Repo.current.settings)
        m.setData(Data.SITES, Features.sites(listOf(site.copy(name = "")), { it }))
    }
    LaunchedEffect(m, visits, sel, points) {
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
        val shown = if (sel != null) sel.finds else points
        m.setData(Data.PINS, Features.pins(shown.map { it.copy(name = "", photos = emptyList()) }, { it }))
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF6B7A45)).clickable(onClick = onClick)) {
        MapBox(m, Modifier.fillMaxSize())
        Text(
            "Open map ›", Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xF0FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = C.accent,
        )
    }
}
