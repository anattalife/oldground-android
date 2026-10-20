package com.brandonthomas.oldground.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.brandonthomas.oldground.data.NOTE_LABELS
import com.brandonthomas.oldground.data.NoteFile
import com.brandonthomas.oldground.data.NoteFiles
import com.brandonthomas.oldground.data.Photos
import com.brandonthomas.oldground.data.Repo
import com.brandonthomas.oldground.data.SiteNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Add or edit a note in a site: title, label, text, photos and files. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteSheet(siteId: String, note: SiteNote?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(note?.title ?: "") }
    var label by remember { mutableStateOf(note?.label) }
    var text by remember { mutableStateOf(note?.text ?: "") }
    val photos = remember { mutableStateListOf<String>().apply { addAll(note?.photos.orEmpty()) } }
    val files = remember { mutableStateListOf<NoteFile>().apply { addAll(note?.files.orEmpty()) } }
    // Added in this sheet: removed again if it's cancelled.
    val addedPhotos = remember { mutableStateListOf<String>() }
    val addedFiles = remember { mutableStateListOf<NoteFile>() }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = C.line, focusedBorderColor = C.accent)

    fun addPhoto(uri: Uri) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { Photos.import(ctx, uri) }?.let { photos.add(it); addedPhotos.add(it) }
            busy = false
        }
    }
    fun addFile(uri: Uri) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { NoteFiles.import(ctx, uri) }?.let { files.add(it); addedFiles.add(it) }
            busy = false
        }
    }
    val cameraFile = remember { File(ctx.cacheDir, "camera-note.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) addPhoto(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::addPhoto) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::addFile) }

    fun cancel() {
        addedPhotos.forEach { Photos.delete(ctx, it) }
        addedFiles.forEach { NoteFiles.delete(ctx, it) }
        onDismiss()
    }
    fun save() {
        note?.photos?.filter { it !in photos }?.forEach { Photos.delete(ctx, it) }
        note?.files?.filter { it !in files }?.forEach { NoteFiles.delete(ctx, it) }
        val n = SiteNote(
            id = note?.id ?: Repo.newId(),
            title = title.trim().ifBlank { text.trim().lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: label ?: "Note" },
            text = text.trim(), label = label, createdAt = note?.createdAt ?: System.currentTimeMillis(),
            photos = photos.toList(), files = files.toList(),
        )
        Repo.updateSite(siteId) { s -> s.copy(notes = if (note == null) listOf(n) + s.notes else s.notes.map { if (it.id == n.id) n else it }) }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { cancel() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.panel, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", Modifier.clickable { cancel() }.padding(vertical = 8.dp), color = C.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (note == null) "New note" else "Note", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = C.ink)
                Pill("Save", { save() }, kind = "primary", small = true, enabled = !busy && (title.isNotBlank() || text.isNotBlank() || photos.isNotEmpty() || files.isNotEmpty()))
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                OutlinedTextField(title, { title = it.take(100) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    placeholder = { Text("Title, e.g. 1872 deed, J. Miller") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in NOTE_LABELS) SoftChip(l, l == label) { label = if (label == l) null else l }
                }
                OutlinedTextField(text, { text = it.take(10_000) }, minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    placeholder = { Text("What you found out, where it came from, who told you…") })
                // Photos.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in photos) Box(Modifier.size(70.dp).clip(RoundedCornerShape(12.dp))) {
                        PhotoThumb(p, Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0xB314201D)).clickable { photos.remove(p) },
                            contentAlignment = Alignment.Center,
                        ) { Text("×", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    }
                    SmallAdd("Photo", Glyph.CAMERA) { takePhoto.launch(cameraUri) }
                    SmallAdd("Gallery", Glyph.IMAGE) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    SmallAdd("File", Glyph.PLUS) { pickFile.launch(arrayOf("*/*")) }
                }
                if (busy) Text("Adding…", color = C.muted, fontSize = 12.sp)
                // Files.
                for (f in files) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.soft).clickable { NoteFiles.open(ctx, f) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(f.name.substringAfterLast('.', "FILE").uppercase().take(4), Modifier.clip(RoundedCornerShape(6.dp)).background(C.accentSoft).padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = C.accent)
                    Text(f.name, Modifier.weight(1f), fontSize = 13.5.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Remove", Modifier.clickable { files.remove(f) }.padding(4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = C.muted)
                }
                if (note != null) Text(
                    "Delete note", Modifier.align(Alignment.CenterHorizontally).clickable { confirmDelete = true }.padding(8.dp),
                    color = C.bad, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    if (confirmDelete && note != null) ConfirmDialog("Delete this note?", "\"${note.title}\" and its photos and files will be removed.", "Delete", onCancel = { confirmDelete = false }) {
        confirmDelete = false
        (note.photos + addedPhotos).forEach { Photos.delete(ctx, it) }
        (note.files + addedFiles).forEach { NoteFiles.delete(ctx, it) }
        Repo.updateSite(siteId) { s -> s.copy(notes = s.notes.filter { it.id != note.id }) }
        onDismiss()
    }
}

@Composable
private fun SmallAdd(label: String, glyph: Glyph, onClick: () -> Unit) {
    Column(
        Modifier.size(70.dp).clip(RoundedCornerShape(12.dp)).background(C.soft).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, C.accent, Modifier.size(19.dp))
        Text(label, color = C.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}
