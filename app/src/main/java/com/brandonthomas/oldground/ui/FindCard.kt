package com.brandonthomas.oldground.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.geo.Mercator
import com.brandonthomas.oldground.location.Fix
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val DIR_WORDS = mapOf(
    "N" to "north", "NE" to "northeast", "E" to "east", "SE" to "southeast",
    "S" to "south", "SW" to "southwest", "W" to "west", "NW" to "northwest",
)

/** One line of find details, e.g. "Coin · Copper · 6 in · VDI 78". */
fun findSummary(p: Pin, metric: Boolean): String =
    listOfNotNull(p.category, p.material, p.depthIn?.let { depthLabel(it, metric) }, p.vdi?.let { "VDI $it" })
        .ifEmpty { listOf(p.kind.label) }.joinToString(" · ")

@Composable
fun Stars(rating: Int, size: Int = 12) {
    Row {
        for (i in 1..5) Text("★", fontSize = size.sp, color = if (i <= rating) Color(0xFFD4A017) else Color(0xFFD5DBD9))
    }
}

/** What you see when you tap a pin: photos, details, where it is from you, and Guide me. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FindCard(
    p: Pin,
    fix: Fix?,
    metric: Boolean,
    mapName: String?,
    guiding: Boolean,
    onGuide: () -> Unit,
    onEdit: () -> Unit,
    onPost: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = C.panel) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (p.photos.isNotEmpty()) {
                val pager = rememberPagerState { p.photos.size }
                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp))) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { i -> PhotoThumb(p.photos[i], Modifier.fillMaxSize(), px = 900) }
                    if (p.photos.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${p.photos.size}",
                            Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xB314201D)).padding(horizontal = 9.dp, vertical = 4.dp),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        )
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in p.photos.indices) Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color(0x80FFFFFF)))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.createdAt))
                    Text(listOfNotNull("Found $date".takeIf { p.kind == PinKind.FIND } ?: date, mapName).joinToString(" · "), fontSize = 13.sp, color = C.muted)
                }
                if (p.rating > 0) Stars(p.rating, 17)
            }
            val tags = buildList {
                add((p.category ?: p.kind.label) to true)
                p.material?.let { add(it to false) }
                p.depthIn?.let { add("${depthLabel(it, metric)} deep" to false) }
                p.vdi?.let { add("VDI $it" to false) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((t, main) in tags) Text(
                    t,
                    Modifier.clip(RoundedCornerShape(50)).background(if (main) Color(0xFFFDF3DC) else C.soft).padding(horizontal = 11.dp, vertical = 5.dp),
                    color = if (main) Color(0xFF8A5A00) else C.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                )
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.accentSoft).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val bearing = fix?.let { Mercator.bearing(it.geo, p.geo) }
                Arrow(bearing?.toFloat() ?: 0f, bearing != null)
                Column(Modifier.weight(1f)) {
                    if (fix != null && bearing != null) {
                        val d = Mercator.distanceMeters(fix.geo, p.geo)
                        Text("${Mercator.formatDistance(d, metric)} ${DIR_WORDS[Mercator.compass(bearing)]}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = C.accent)
                        Text("From where you're standing", fontSize = 12.5.sp, color = Color(0xFF3E6B65))
                    } else {
                        Text("Finding your location…", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.accent)
                        Text("%.5f, %.5f".format(Locale.US, p.lat, p.lng), fontSize = 12.5.sp, color = Color(0xFF3E6B65))
                    }
                }
                Pill(if (guiding) "Stop" else "Guide me", onGuide, kind = "primary", small = true)
            }
            if (!p.detector.isNullOrBlank()) Column {
                Text("Detector", fontSize = 12.sp, color = C.muted)
                Text(p.detector, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
            }
            if (p.note.isNotBlank()) Text(p.note, fontSize = 14.sp, color = C.ink, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Edit", onEdit, Modifier.weight(1f), kind = "soft")
                Pill("Share", { share(ctx, p, metric) }, Modifier.weight(1f), kind = "soft")
                if (onPost != null && p.kind == com.brandonthomas.oldground.data.PinKind.FIND && p.photos.isNotEmpty()) Pill("Post", onPost, Modifier.weight(1f), kind = "tint")
            }
        }
    }
}

@Composable
private fun Arrow(deg: Float, known: Boolean) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp).rotate(deg)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2, 0f); lineTo(w * 0.82f, h); lineTo(w / 2, h * 0.8f); lineTo(w * 0.18f, h); close()
            }
            drawPath(path, if (known) Color(0xFF0F766E) else Color(0xFF9FB3AD))
        }
    }
}

/** Sends the photos and details to another app (Messages, email, Facebook…). */
fun share(ctx: Context, p: Pin, metric: Boolean) {
    val text = buildString {
        append(p.name)
        append("\n").append(findSummary(p, metric))
        if (p.note.isNotBlank()) append("\n").append(p.note)
        append("\nhttps://maps.google.com/?q=%.6f,%.6f".format(Locale.US, p.lat, p.lng))
    }
    val uris = ArrayList(p.photos.mapNotNull { n ->
        val f = Photos.file(ctx, n)
        if (f.exists()) FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) else null
    })
    val send = if (uris.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain")
    else Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").apply {
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    send.putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, p.name)
    ctx.startActivity(Intent.createChooser(send, "Share ${p.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
