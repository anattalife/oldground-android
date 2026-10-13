package com.brandonthomas.oldground.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.Layer
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Site
import com.brandonthomas.oldground.data.Sites
import com.brandonthomas.oldground.data.Visit
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.map.Base
import com.brandonthomas.oldground.map.Data
import com.brandonthomas.oldground.map.Features
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One day at a site: where you walked, how long, and what you found. */
@Composable
fun VisitScreen(nav: Nav, siteId: String, day: String) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    val trackPts by Repo.trackPoints.collectAsState()
    val site = data.sites.firstOrNull { it.id == siteId }
    val date = runCatching { LocalDate.parse(day) }.getOrNull()
    if (site == null || date == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val metric = data.settings.metric
    val finds = remember(data.sites, data.pins) { Sites.findsBySite(data.sites, data.pins)[site.id].orEmpty() }
    val visit = remember(site, finds, trackPts, date) { Sites.visits(site, finds, trackPts).firstOrNull { it.day == date } }
        ?: Visit(date, emptyList(), 0, 0.0, emptyList())

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton({ nav.back() }, "Back") { GlyphIcon(Glyph.BACK, C.ink) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(site.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            }
            Pill("Share", { shareVisit(ctx, site, visit, metric) }, kind = "soft", small = true)
        }
        ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(6.dp))
            VisitMap(site, visit, Modifier.fillMaxWidth().height(200.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisitStat(if (visit.detectingMs > 0) Sites.formatDuration(visit.detectingMs) else "–", "detecting", Modifier.weight(1f))
                VisitStat(if (visit.walkedM > 0) Mercator.formatDistance(visit.walkedM, metric) else "–", "walked", Modifier.weight(1f))
                VisitStat("${visit.finds.size}", if (visit.finds.size == 1) "find" else "finds", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text("Finds this visit", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
            Spacer(Modifier.height(8.dp))
            if (visit.finds.isEmpty()) Text("Nothing logged this day.", color = C.muted, fontSize = 14.sp)
            for (p in visit.finds) {
                FindRow(p, metric) { openPin(nav, p) }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A small still map of the site with that day's track and finds. */
@Composable
private fun VisitMap(site: Site, visit: Visit, modifier: Modifier) {
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    val markerPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    val m = rememberOgMap("visit-${site.id}-${visit.day}", interactive = false)
    LaunchedEffect(m) {
        val b = Sites.bounds(site.ring) ?: return@LaunchedEffect
        m.setCamera(Sites.center(site.ring)!!, Sites.fitZoom(b, conf.screenWidthDp - 32f, 200f, 0.85), 0.0)
        m.setBase(Base.Geo(Layer.AERIAL, "visit"), Repo.current.settings)
        m.setData(Data.SITES, Features.sites(listOf(site.copy(name = "")), { it }))
        m.setData(Data.TRACKS, Features.tracks(visit.tracks, { it }))
        val firsts = visit.finds.mapNotNull { it.photos.firstOrNull() }.distinct()
        val imgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            firsts.mapNotNull { n -> Photos.mapMarker(ctx, n, markerPx)?.let { Features.photoImageId(n) to it } }.toMap()
        }
        m.addImages(imgs)
        // No labels on the small map: names would crowd it.
        m.setData(Data.PINS, Features.pins(visit.finds.map { it.copy(name = "") }, { it }))
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF6B7A45))) {
        MapBox(m, Modifier.fillMaxSize())
        Text(
            "Track and finds",
            Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xB814201D)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun VisitStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.card(14, 2).padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink, maxLines = 1)
        Text(label, fontSize = 11.5.sp, color = C.muted, maxLines = 1)
    }
}

/** A find in a list: photo, name, details and stars. */
@Composable
fun FindRow(p: Pin, metric: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().card(16).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val photo = p.photos.firstOrNull()
        if (photo != null) PhotoThumb(photo, Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)), px = 200)
        else Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(C.soft), contentAlignment = Alignment.Center) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(hexColor(p.kind.color)))
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(findSummary(p, metric), color = C.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.rating > 0) Stars(p.rating)
    }
}

private fun shareVisit(ctx: Context, site: Site, v: Visit, metric: Boolean) {
    val text = buildString {
        append("${site.name}, ${v.day.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))}")
        val stats = listOfNotNull(
            v.detectingMs.takeIf { it > 0 }?.let { Sites.formatDuration(it) + " detecting" },
            "${v.finds.size} find${if (v.finds.size == 1) "" else "s"}",
        )
        append("\n").append(stats.joinToString(" · "))
        for (p in v.finds) append("\n• ${p.name} (${findSummary(p, metric)})")
    }
    val uris = ArrayList(v.finds.flatMap { it.photos.take(1) }.mapNotNull { n ->
        val f = Photos.file(ctx, n)
        if (f.exists()) FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) else null
    })
    val send = if (uris.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain")
    else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").apply {
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    send.putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, site.name)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share this visit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
