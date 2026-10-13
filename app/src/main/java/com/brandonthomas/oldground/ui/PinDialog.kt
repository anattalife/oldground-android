package com.brandonthomas.oldground.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.data.FIND_CATEGORIES
import com.brandonthomas.oldground.data.FIND_MATERIALS
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Pin
import com.brandonthomas.oldground.data.PinKind
import com.brandonthomas.oldground.geo.LngLat
import com.brandonthomas.oldground.geo.Mercator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** A pin being made or edited. */
data class PinDraft(
    val geo: LngLat,
    val name: String,
    val kind: PinKind,
    val note: String,
    val editing: String?,
    val photos: List<String> = emptyList(),
    val category: String? = null,
    val material: String? = null,
    val depthIn: Double? = null,
    val vdi: Int? = null,
    val detector: String? = null,
    val rating: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun of(p: Pin) = PinDraft(p.geo, p.name, p.kind, p.note, p.id, p.photos, p.category, p.material, p.depthIn, p.vdi, p.detector, p.rating, p.createdAt)
    }
}

private const val MAX_PHOTOS = 4

/** Depth as people say it: inches, or centimetres when metric. */
fun depthLabel(inches: Double, metric: Boolean): String =
    if (metric) "${(inches * 2.54).roundToInt()} cm" else "${if (inches % 1.0 == 0.0) inches.toInt().toString() else "%.1f".format(Locale.US, inches)} in"

/** Add or edit a pin: photos, name, type and, for finds, the details. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PinDialog(
    d: PinDraft,
    distance: Double?,
    metric: Boolean,
    lastDetector: String = "",
    siteName: String? = null,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (PinDraft) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(d.name) }
    var kind by remember { mutableStateOf(d.kind) }
    var note by remember { mutableStateOf(d.note) }
    val photos = remember { mutableStateListOf<String>().apply { addAll(d.photos) } }
    val added = remember { mutableStateListOf<String>() }
    var category by remember { mutableStateOf(d.category) }
    var material by remember { mutableStateOf(d.material) }
    var depthIn by remember { mutableStateOf(d.depthIn) }
    var vdi by remember { mutableStateOf(d.vdi?.toString() ?: "") }
    var detector by remember { mutableStateOf(d.detector ?: lastDetector) }
    var editDetector by remember { mutableStateOf(detector.isBlank()) }
    var rating by remember { mutableStateOf(d.rating) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun addPhoto(uri: Uri) {
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { Photos.import(ctx, uri) }
            if (n != null) { photos.add(n); added.add(n) }
            busy = false
        }
    }
    val cameraFile = remember { File(ctx.cacheDir, "camera-pin.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) addPhoto(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addPhoto(uri) }

    fun cancel() {
        // Photos taken for a pin that's never saved aren't kept.
        added.forEach { Photos.delete(ctx, it) }
        onCancel()
    }
    fun save() {
        val removed = d.photos.filter { it !in photos }
        removed.forEach { Photos.delete(ctx, it) }
        val isFind = kind == PinKind.FIND
        onSave(
            d.copy(
                name = name.trim(), kind = kind, note = note.trim(), photos = photos.toList(),
                category = if (isFind) category else null, material = if (isFind) material else null,
                depthIn = if (isFind) depthIn else null, vdi = if (isFind) vdi.trim().toIntOrNull() else null,
                detector = if (isFind) detector.trim().ifBlank { null } else null, rating = rating,
            ),
        )
    }

    val title = when {
        d.editing != null && kind == PinKind.FIND -> "Edit find"
        d.editing != null -> "Edit pin"
        kind == PinKind.FIND -> "New find"
        else -> "New pin"
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent)

    ModalBottomSheet(
        onDismissRequest = { cancel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.panel,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", Modifier.clickable { cancel() }.padding(vertical = 8.dp), color = C.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Pill("Save", { save() }, kind = "primary", small = true, enabled = !busy)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                // ---- photos ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in photos) PhotoTile(p) { photos.remove(p) }
                    if (photos.size < MAX_PHOTOS) {
                        AddTile("Take photo", Glyph.CAMERA) { takePhoto.launch(cameraUri) }
                        if (photos.size < MAX_PHOTOS - 1 || photos.isEmpty()) AddTile("Choose", Glyph.IMAGE) {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                }
                if (busy) Text("Adding photo…", color = C.muted, fontSize = 12.sp)

                LabeledField(if (kind == PinKind.FIND) "What is it" else "Name") {
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                        placeholder = { Text(if (kind == PinKind.FIND) "e.g. 1864 Indian Head cent" else "e.g. Miller homestead") })
                }
                LabeledField("Pin type") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in PinKind.entries) SoftChip(k.label, k == kind, dot = k.color) { kind = k }
                    }
                }
                if (kind == PinKind.FIND) {
                    LabeledField("Category") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in FIND_CATEGORIES) SoftChip(c, c == category) { category = if (category == c) null else c }
                        }
                    }
                    LabeledField("Material") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in FIND_MATERIALS) SoftChip(m, m == material) { material = if (material == m) null else m }
                        }
                    }
                    LabeledField("Depth") {
                        val steps = if (metric) listOf(5, 10, 15, 20, 25).map { it to it / 2.54 } else listOf(2, 4, 6, 8, 10).map { it to it.toDouble() }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((i, pair) in steps.withIndex()) {
                                val (shown, inches) = pair
                                val on = depthIn?.let { abs(it - inches) < 0.3 } == true
                                val label = if (metric) "$shown${if (i == steps.lastIndex) "+" else ""}" else "$shown\"${if (i == steps.lastIndex) "+" else ""}"
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) C.accent else C.soft)
                                        .clickable { depthIn = if (on) null else inches }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, color = if (on) Color.White else C.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (depthIn != null && steps.none { abs(it.second - depthIn!!) < 0.3 }) {
                            Text(depthLabel(depthIn!!, metric), color = C.muted, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        LabeledField("VDI", Modifier.width(110.dp)) {
                            OutlinedTextField(vdi, { v -> vdi = v.filter { it.isDigit() || it == '-' }.take(4) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(10.dp), colors = fieldColors)
                        }
                        LabeledField("Rating", Modifier.weight(1f)) {
                            Row {
                                for (i in 1..5) Text(
                                    "★", Modifier.clip(CircleShape).clickable { rating = if (rating == i) 0 else i }.padding(horizontal = 3.dp, vertical = 4.dp),
                                    fontSize = 26.sp, color = if (i <= rating) hexColor("#D4A017") else Color(0xFFD5DBD9),
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.soft).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Detector", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.muted)
                                if (!editDetector) Text(detector, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
                            }
                            if (!editDetector) Text("Change", Modifier.clickable { editDetector = true }.padding(6.dp), color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (editDetector) OutlinedTextField(detector, { detector = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. XP Deus II · Program 4 · Sens 92") }, shape = RoundedCornerShape(10.dp), colors = fieldColors)
                    }
                }
                LabeledField("Notes") {
                    OutlinedTextField(note, { note = it }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                }
                if (kind == PinKind.FIND && siteName != null) Text(
                    "Saved to $siteName", Modifier.fillMaxWidth(), color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.createdAt)) +
                        " · " + String.format(Locale.US, "%.5f, %.5f", d.geo.lat, d.geo.lng) +
                        (distance?.let { " · ${Mercator.formatDistance(it, metric)} from you" } ?: ""),
                    color = C.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    Text("Delete pin", Modifier.align(Alignment.CenterHorizontally).clickable { confirmDelete = true }.padding(8.dp), color = C.bad, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete this pin?", "\"${name.ifBlank { "Pin" }}\" and its photos will be removed.", "Delete",
            onCancel = { confirmDelete = false }) {
            confirmDelete = false
            photos.forEach { Photos.delete(ctx, it) }
            onDelete()
        }
    }
}

@Composable
fun LabeledField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.muted)
        content()
    }
}

@Composable
fun SoftChip(label: String, on: Boolean, dot: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) C.accent else C.soft).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(9.dp).clip(CircleShape).background(hexColor(dot)).border(1.dp, Color.White, CircleShape))
        Text(label, fontSize = 13.sp, color = if (on) Color.White else C.ink, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
    }
}

/** A photo from the photos folder, cropped square. */
@Composable
fun PhotoThumb(name: String, modifier: Modifier = Modifier, px: Int = 240) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { Photos.thumb(ctx, name, px)?.asImageBitmap() } }
    Box(modifier.background(C.line)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun PhotoTile(name: String, onRemove: () -> Unit) {
    Box(Modifier.size(74.dp).clip(RoundedCornerShape(14.dp))) {
        PhotoThumb(name, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0xB314201D)).clickable(onClickLabel = "Remove photo", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun AddTile(label: String, glyph: Glyph, onClick: () -> Unit) {
    Column(
        Modifier.size(74.dp).clip(RoundedCornerShape(14.dp)).background(C.soft).border(1.5.dp, Color(0xFF9FB3AD), RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, C.accent, Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = C.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}
