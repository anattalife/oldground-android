package com.brandonthomas.oldground.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.data.Permissions
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.Sites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A landowner's finds report: tick the finds to include, check the PDF, then send it. */
@Composable
fun ReportScreen(nav: Nav, siteId: String, debug: String? = null) {
    val ctx = LocalContext.current
    val data by Repo.data.collectAsState()
    val site = data.sites.firstOrNull { it.id == siteId } ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val metric = data.settings.metric
    val finds = remember(data.sites, data.pins) { Sites.findsBySite(data.sites, data.pins)[site.id].orEmpty().sortedByDescending { it.createdAt } }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(finds.map { it.id }) } }
    var preview by remember { mutableStateOf(debug == "preview") }
    var pdf by remember { mutableStateOf<File?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    val owner = site.permission?.ownerName
    // A test screenshot of the preview leaves out a couple of finds, as someone would.
    LaunchedEffect(debug) { if (debug == "preview" && finds.size > 2) chosen.removeAll(finds.takeLast(1).map { it.id }) }

    LaunchedEffect(preview) {
        if (!preview) return@LaunchedEffect
        pages = emptyList()
        val picked = finds.filter { it.id in chosen }
        val f = withContext(Dispatchers.IO) { Permissions.findsReport(ctx, site, picked, metric) }
        pdf = f
        pages = withContext(Dispatchers.IO) { renderPages(f, ctx.resources.displayMetrics.widthPixels) }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton({ if (preview) preview = false else nav.back() }, "Back", size = 38.dp) { GlyphIcon(Glyph.BACK, C.ink, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(if (preview) "Preview" else "Finds report", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Text(
                    if (preview) "${pages.size.takeIf { it > 0 }?.let { "$it page${if (it == 1) "" else "s"} · " } ?: ""}${chosen.size} find${if (chosen.size == 1) "" else "s"}"
                    else listOfNotNull(site.name, owner?.let { "for $it" }).joinToString(" · "),
                    fontSize = 12.5.sp, color = C.muted, maxLines = 1,
                )
            }
        }
        if (!preview) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${chosen.size} of ${finds.size} finds included", Modifier.weight(1f), fontSize = 13.sp, color = C.muted)
                val all = chosen.size == finds.size
                Text(if (all) "Select none" else "Select all", Modifier.clickable { chosen.clear(); if (!all) chosen.addAll(finds.map { it.id }) }.padding(4.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.accent)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(finds, key = { it.id }) { p ->
                    val on = p.id in chosen
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(C.panel)
                            .clickable { if (on) chosen.remove(p.id) else chosen.add(p.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(on, { if (it) chosen.add(p.id) else chosen.remove(p.id) }, colors = CheckboxDefaults.colors(checkedColor = C.accent))
                        val photo = p.photos.firstOrNull()
                        if (photo != null) PhotoThumb(photo, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), px = 160)
                        else Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(C.soft), contentAlignment = Alignment.Center) { PinBadge(p, 26.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (on) C.ink else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(p.createdAt)), p.depthIn?.let { depthLabel(it, metric) }).joinToString(" · "),
                                fontSize = 12.5.sp, color = C.muted, maxLines = 1,
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(C.panel).padding(14.dp)) {
                Pill(
                    if (chosen.isEmpty()) "Pick at least one find" else "Preview report (${chosen.size})", { preview = true },
                    Modifier.fillMaxWidth(), kind = "primary", enabled = chosen.isNotEmpty(),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pages.isEmpty()) item { Text("Making the PDF…", Modifier.fillMaxWidth().padding(24.dp), color = C.muted) }
                items(pages) { b ->
                    Image(
                        b.asImageBitmap(), "Report page",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(b.width.toFloat() / b.height).shadow(3.dp, RoundedCornerShape(4.dp)).background(Color.White),
                        contentScale = ContentScale.Fit,
                    )
                }
                item { Box(Modifier.size(4.dp)) }
            }
            Row(Modifier.fillMaxWidth().background(C.panel).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Change finds", { preview = false }, Modifier.weight(1f), kind = "soft")
                Pill("Send", {
                    pdf?.let { Permissions.share(ctx, it, "Finds from ${site.name}", site.permission?.ownerEmail) }
                }, Modifier.weight(1f), kind = "primary", enabled = pdf != null && pages.isNotEmpty())
            }
        }
    }
}

/** Each page of a PDF as an image [widthPx] wide. */
private fun renderPages(f: File, widthPx: Int): List<Bitmap> = runCatching {
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            (0 until r.pageCount).map { i ->
                r.openPage(i).use { page ->
                    val w = widthPx.coerceAtMost(1400)
                    val h = (w.toFloat() * page.height / page.width).toInt()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}.getOrDefault(emptyList())
