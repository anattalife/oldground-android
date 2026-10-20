package com.brandonthomas.oldground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.AppData
import com.brandonthomas.oldground.data.FIND_MATERIALS
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.data.TrackPt
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.location.Fix
import com.brandonthomas.oldground.location.LocationHub
import java.time.format.DateTimeFormatter

/** Show a pin on the Map tab with its card open (the old map on top stays as it is). */
fun openPin(nav: Nav, p: Pin) = nav.go(Screen.Hunt(null, p.id))

/** The top of a tab's first page: its title, the settings gear, and one main action. */
@Composable
fun TabHeader(title: String, nav: Nav, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
        RoundButton({ nav.go(Screen.Settings) }, "Settings", size = 38.dp) { GlyphIcon(Glyph.GEAR, C.ink, Modifier.size(20.dp)) }
        action()
    }
}

/** Sites tab: every place you detect, each holding its finds and visits. */
@Composable
fun SitesRoot(nav: Nav) {
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val fix by LocationHub.fix.collectAsState()
    val bySite = remember(data.sites, data.pins) { Sites.findsBySite(data.sites, data.pins) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TabHeader("Sites", nav) { Pill("+ New site", { nav.go(Screen.Hunt(null, draw = "new")) }, kind = "primary", small = true) }
        SitesTab(nav, data, bySite, trackPts, fix)
    }
}

/** Finds tab: every find by site, plus other pins. (Recorded paths live with their visits on each site.) */
@Composable
fun FindsRoot(nav: Nav, startSub: Int = 0) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    var sub by rememberSaveable { mutableIntStateOf(startSub) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val bySite = remember(data.sites, data.pins) { Sites.findsBySite(data.sites, data.pins) }
    val findCount = bySite.values.sumOf { it.size }
    val otherCount = data.pins.count { it.kind != PinKind.FIND }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TabHeader("Finds", nav) { Pill("Export", { message = exportGpx(ctx) }, kind = "tint", small = true) }
        Segmented(
            sub, listOf(0 to "Finds $findCount", 1 to "Other points $otherCount"), { sub = it },
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        when (sub) {
            0 -> AllFindsTab(nav, data, bySite)
            else -> PinsTracksTab(nav, tracks = false)
        }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
}

@Composable
private fun SitesTab(nav: Nav, data: AppData, bySite: Map<String?, List<Pin>>, trackPts: Map<String, List<TrackPt>>, fix: Fix?) {
    val metric = data.settings.metric
    ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        if (data.sites.isEmpty()) {
            Column(Modifier.fillMaxWidth().card(18).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No sites yet", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = C.ink)
                Text(
                    "Tap New site and outline a field, yard or park on the map. Every find you log inside it is saved to that site, with each day you go back kept as a visit.",
                    color = C.muted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
        for (site in data.sites) {
            val finds = bySite[site.id].orEmpty()
            val visits = remember(site, finds, trackPts) { Sites.visits(site, finds, trackPts) }
            SiteCard(site, finds, visits.size, visits.firstOrNull()?.day?.format(DateTimeFormatter.ofPattern("MMM d")), fix, metric) { nav.go(Screen.Site(site.id)) }
            Spacer(Modifier.height(12.dp))
        }
        val loose = bySite[null].orEmpty()
        if (loose.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().card(18).clickable { nav.go(Screen.FindsRoot(0)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(C.soft), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(hexColor("#D4A017")))
                }
                Column(Modifier.weight(1f)) {
                    Text("Not in a site", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = C.ink)
                    Text("${loose.size} find${if (loose.size == 1) "" else "s"} logged outside any outline", fontSize = 13.sp, color = C.muted)
                }
                GlyphIcon(Glyph.CHEVRON, C.faint, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun SiteCard(site: Site, finds: List<Pin>, visitCount: Int, lastVisit: String?, fix: Fix?, metric: Boolean, onClick: () -> Unit) {
    val center = Sites.center(site.ring)
    val inside = fix != null && Sites.contains(site.ring, fix.geo)
    val silver = finds.count { it.material == "Silver" }
    val gold = finds.count { it.material == "Gold" }
    val best = listOfNotNull(silver.takeIf { it > 0 }?.let { "$it silver" }, gold.takeIf { it > 0 }?.let { "$it gold" }).joinToString(" · ")
    val sub = listOfNotNull(
        "$visitCount visit${if (visitCount == 1) "" else "s"}",
        lastVisit?.let { "last $it" },
        best.ifEmpty { site.access.lowercase() },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().card(18).clickable(onClick = onClick)) {
        if (site.photo != null) {
            Box(Modifier.fillMaxWidth().height(128.dp)) {
                PhotoThumb(site.photo, Modifier.fillMaxSize(), px = 800)
                Text(
                    "${finds.size} finds",
                    Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xB814201D)).padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (site.photo == null) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.soft).border(1.5.dp, Color(0xFF9FB3AD), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.CAMERA, C.accent, Modifier.size(24.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(site.name, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (site.photo == null) "$sub · ${finds.size} finds" else sub,
                    fontSize = 13.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val ps = com.brandonthomas.oldground.data.Permissions.status(site)
                if (ps.level == com.brandonthomas.oldground.data.Permissions.Level.SOON || ps.level == com.brandonthomas.oldground.data.Permissions.Level.EXPIRED) Text(
                    "Permission: ${ps.text.replaceFirstChar { it.lowercase() }}",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (ps.level == com.brandonthomas.oldground.data.Permissions.Level.EXPIRED) C.badInk else Color(0xFFB45309), maxLines = 1,
                )
            }
            if (inside) {
                Text("You're here", Modifier.clip(RoundedCornerShape(50)).background(C.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp), color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else if (fix != null && center != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(Mercator.formatDistance(Mercator.distanceMeters(fix.geo, center), metric), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = C.ink)
                    Text(Mercator.compass(Mercator.bearing(fix.geo, center)), fontSize = 12.sp, color = C.muted)
                }
            }
        }
    }
}

private enum class FindFilter(val label: String) { ALL("Everything"), MATERIAL(""), COINS("Coins"), TOP("★★★★+") }

@Composable
private fun AllFindsTab(nav: Nav, data: AppData, bySite: Map<String?, List<Pin>>) {
    var filter by remember { mutableStateOf(FindFilter.ALL) }
    var material by remember { mutableStateOf<String?>(null) }
    val all = bySite.values.flatten()
    fun keep(p: Pin) = when (filter) {
        FindFilter.ALL -> true
        FindFilter.MATERIAL -> p.material == material
        FindFilter.COINS -> p.category == "Coin"
        FindFilter.TOP -> p.rating >= 4
    }
    ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubChip("Everything", filter == FindFilter.ALL) { filter = FindFilter.ALL }
            val counts = all.mapNotNull { it.material }.groupingBy { it }.eachCount()
            for (m in listOf("Silver", "Gold") + FIND_MATERIALS.filter { it != "Silver" && it != "Gold" }) counts[m]?.let { n ->
                SubChip("$m $n", filter == FindFilter.MATERIAL && material == m) { filter = FindFilter.MATERIAL; material = m }
            }
            all.count { it.category == "Coin" }.takeIf { it > 0 }?.let { n -> SubChip("Coins $n", filter == FindFilter.COINS) { filter = FindFilter.COINS } }
            SubChip("★★★★+", filter == FindFilter.TOP) { filter = FindFilter.TOP }
        }
        Spacer(Modifier.height(12.dp))
        if (all.isEmpty()) Text("No finds yet. On the map, tap Add point during a visit to log one.", color = C.muted, modifier = Modifier.padding(vertical = 16.dp))
        val groups = data.sites.map { it to bySite[it.id].orEmpty() } + (null to bySite[null].orEmpty())
        var shown = 0
        for ((site, finds) in groups) {
            val list = finds.filter(::keep).sortedByDescending { it.createdAt }
            if (list.isEmpty()) continue
            shown += list.size
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(site?.name ?: "Not in a site", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = C.ink)
                Text("${list.size} find${if (list.size == 1) "" else "s"}", fontSize = 12.5.sp, color = C.muted)
            }
            // Up to two rows per site; the rest are one tap away on the site's page.
            val max = if (site == null) list.size else 8
            val cells = list.take(if (list.size > max) max - 1 else max)
            val more = list.size - cells.size
            val tiles: List<Pin?> = cells + (if (more > 0) listOf(null) else emptyList())
            for (row in tiles.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in row) {
                        if (p != null) FindTile(p, Modifier.weight(1f)) { openPin(nav, p) }
                        else MoreTile(more, Modifier.weight(1f)) { site?.let { nav.go(Screen.Site(it.id)) } }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (all.isNotEmpty() && shown == 0) Text("No finds match.", color = C.muted, modifier = Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun FindTile(p: Pin, modifier: Modifier, onClick: () -> Unit) {
    val photo = p.photos.firstOrNull()
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(C.soft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (photo != null) PhotoThumb(photo, Modifier.fillMaxSize(), px = 240)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            PinBadge(p, 24.dp)
            Spacer(Modifier.height(4.dp))
            Text(p.name, fontSize = 10.sp, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
        }
    }
}

@Composable
fun MoreTile(n: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(C.line).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("+$n", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = C.muted)
    }
}

@Composable
fun SubChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) C.accentSoft else C.panel)
            .border(1.dp, if (on) C.accentSoft else C.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 12.5.sp, color = if (on) C.accent else C.ink, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
    )
}
