package com.brandonthomas.oldground.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** Files attached to a site's notes (deeds, PDFs, scans), kept on the phone in the notes folder. */
object NoteFiles {
    fun dir(ctx: Context) = File(ctx.filesDir, "oldground/notes").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Copies a picked file in. Null if it couldn't be read. */
    fun import(ctx: Context, uri: Uri): NoteFile? = runCatching {
        val cr = ctx.contentResolver
        val shown = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "File"
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val ext = shown.substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
        val name = Repo.newId() + if (ext.isNotEmpty()) ".$ext" else ""
        cr.openInputStream(uri)!!.use { input -> file(ctx, name).outputStream().use { input.copyTo(it) } }
        NoteFile(name, shown, mime)
    }.getOrNull()

    fun delete(ctx: Context, f: NoteFile) { file(ctx, f.file).delete() }

    /** Opens the file in whatever app handles it, else offers to share it. */
    fun open(ctx: Context, f: NoteFile) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file(ctx, f.file))
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(view) }.onFailure {
            val send = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { ctx.startActivity(Intent.createChooser(send, f.name)) }
        }
    }
}
