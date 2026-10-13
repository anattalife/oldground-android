package com.brandonthomas.oldground.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.Nav
import com.brandonthomas.oldground.Screen
import com.brandonthomas.oldground.data.OldMap
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.map.OfflineAreas
import com.brandonthomas.oldground.map.TileJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Old maps tab: your scans of plat maps and atlases. Open one to put it on top of the Map tab. */
@Composable
fun OldMapsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Repo.data.collectAsState()
    val progress by TileJobs.progress.collectAsState()

    var pending by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var menuFor by remember { mutableStateOf<OldMap?>(null) }
    var renaming by remember { mutableStateOf<OldMap?>(null) }
    var deleting by remember { mutableStateOf<OldMap?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var preparing by remember { mutableStateOf<String?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pending = uri to "Old map"
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = uri to (uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Old map")
    }
    val cameraFile = remember { File(ctx.cacheDir, "camera-map.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending = cameraUri to "Photographed map"
    }

    fun import(uri: Uri, name: String) {
        scope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(it, null, o)
                        o.outWidth.toLong() * o.outHeight
                    }
                }.getOrDefault(0L)
            }
            if (size > TileJobs.MAX_SCAN_PIXELS) {
                message = "This scan is too large" to "It's ${size / 1_000_000} megapixels. Reduce it below ${TileJobs.MAX_SCAN_PIXELS / 1_000_000} megapixels and import it again."
                return@launch
            }
            val result = Repo.importMap(ctx, uri, name)
            val map = result.getOrElse {
                message = "Can't open that file" to (it.message ?: "Choose a JPEG, PNG or WebP scan.")
                return@launch
            }
            preparing = map.id
            TileJobs.ensureScan(map)
            // Open the editor once the first levels are ready; the rest keep building.
            var waited = 0
            while (true) {
                val scan = Repo.map(map.id)?.scan
                if (scan != null && scan.zDone >= minOf(scan.zMin + 2, scan.zMax)) break
                val running = TileJobs.progress.value.containsKey("scan:${map.id}")
                if (!running && waited > 3000) {
                    preparing = null
                    message = "Couldn't prepare this map" to "The scan couldn't be read. Try saving it as a JPEG and importing it again."
                    return@launch
                }
                delay(250)
                waited += 250
            }
            preparing = null
            nav.go(Screen.Align(map.id))
        }
    }

    var addOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        TabHeader("Old maps", nav) { Pill("+ Add map", { addOpen = true }, kind = "primary", small = true) }
        ScrollColumn(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Scans of plat maps, atlases and county maps. Line one up once, then open it on the map.",
                color = C.muted, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 12.dp),
            )
            if (data.maps.isEmpty()) {
                Column(Modifier.fillMaxWidth().card().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Add your first old map", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Import a scan of a plat map, county atlas or old topo. Then line it up with today's map by placing anchors on spots both maps share: crossroads, churches, creek crossings.",
                        color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Pill("Add a map", { addOpen = true }, kind = "primary")
                }
            }
            for (m in data.maps) {
                MapRow(
                    m,
                    building = progress["scan:${m.id}"] ?: progress["warp:${m.id}"],
                    onOpen = { nav.go(if (m.aligned) Screen.Hunt(m.id, keepOldMap = false) else Screen.Align(m.id)) },
                    onMenu = { menuFor = m },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (addOpen) {
        ChoiceSheet("Add an old map", listOf(
            Choice("From Photos") { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Choice("From Files") { pickFile.launch(arrayOf("image/*")) },
            Choice("Take a photo of a paper map") { takePhoto.launch(cameraUri) },
        )) { addOpen = false }
    }

    pending?.let { (uri, name) ->
        NameDialog("Name this map", name, "e.g. 1875 Licking County atlas", onCancel = { pending = null }) { n ->
            pending = null
            import(uri, n)
        }
    }
    menuFor?.let { m ->
        ChoiceSheet(m.name, listOf(
            Choice("Line up (anchors)") { nav.go(Screen.Align(m.id)) },
            Choice("Rename") { renaming = m },
            Choice("Delete", danger = true) { deleting = m },
        )) { menuFor = null }
    }
    renaming?.let { m ->
        NameDialog("Rename map", m.name, onCancel = { renaming = null }) { n ->
            Repo.updateMap(m.id) { it.copy(name = n) }
            renaming = null
        }
    }
    deleting?.let { m ->
        ConfirmDialog("Delete this map?", "The scan and its anchors are removed from this device.", "Delete", { deleting = null }) {
            TileJobs.cancel(m.id)
            Repo.deleteMap(m.id)
            deleting = null
        }
    }
    message?.let { (t, b) -> MessageDialog(t, b) { message = null } }
    if (preparing != null) {
        val p = progress["scan:$preparing"] ?: 0f
        Dialog(onDismissRequest = {}) {
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(C.panel).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = C.accent)
                Spacer(Modifier.height(12.dp))
                Text("Preparing your map", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.ink)
                Spacer(Modifier.height(6.dp))
                Text("Cutting the scan into tiles so it stays sharp when you zoom in. ${(p * 100).toInt()}%", color = C.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.ink)
}

/** A small picture of an old map's scan. */
@Composable
fun MapThumb(m: OldMap, modifier: Modifier = Modifier) {
    val thumb by produceState<ImageBitmap?>(null, m.id, m.image) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = Repo.mapFile(m.id, m.image)
                var sample = 1
                while (maxOf(m.width, m.height) / (sample * 2) >= 480) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(C.line)) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun MapRow(m: OldMap, building: Float?, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().card().clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MapThumb(m, Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(m.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.ink, maxLines = 1)
            val (label, color) = when {
                building != null -> "Preparing ${(building * 100).toInt()}%" to C.muted
                m.aligned -> "Lined up · ${m.anchors.size} anchors" to C.muted
                else -> "Add anchors to line it up" to C.check
            }
            Text(label, color = color, fontSize = 13.sp, fontWeight = if (m.aligned || building != null) FontWeight.Normal else FontWeight.SemiBold)
        }
        if (building == null) Pill(if (m.aligned) "Open" else "Line up", onOpen, kind = if (m.aligned) "primary" else "soft", small = true)
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Map options", onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.MORE, C.faint) }
    }
}
