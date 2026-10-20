package com.brandonthomas.oldground.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/** Permission slips and landowner reports: the wording, the PDFs, sharing them, and expiry. */
object Permissions {
    fun dir(ctx: Context) = File(ctx.filesDir, "oldground/permissions").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(ms))

    /** The slip's wording, paragraph by paragraph (the same text is shown when signing). */
    fun wording(siteName: String, owner: String, hunter: String, startsAt: Long, expiresAt: Long?, conditions: List<String>, extra: String): List<String> {
        val until = expiresAt?.let { "until ${date(it)}" } ?: "until I withdraw it"
        val out = mutableListOf(
            "I, ${owner.ifBlank { "the owner" }}, give ${hunter.ifBlank { "the visitor" }} permission to explore my property, “$siteName”, shown below, and to search for and dig up objects there, including with a metal detector, from ${date(startsAt)} $until.",
        )
        val all = conditions + listOfNotNull(extra.trim().takeIf { it.isNotEmpty() })
        if (all.isNotEmpty()) out += "Conditions:\n" + all.joinToString("\n") { "•  $it" }
        out += "I can withdraw this permission at any time by telling ${hunter.ifBlank { "the visitor" }}."
        return out
    }

    // ---------- expiry ----------

    enum class Level { NONE, OK, SOON, EXPIRED }

    data class Status(val level: Level, val text: String)

    fun status(site: Site, now: Long = System.currentTimeMillis()): Status {
        val p = site.permission ?: return Status(Level.NONE, "Not in writing yet")
        val exp = p.expiresAt ?: return Status(Level.OK, "Signed by ${p.ownerName} · until withdrawn")
        val days = ((exp - now) / 86_400_000L).toInt()
        return when {
            exp < now -> Status(Level.EXPIRED, "Expired ${date(exp)}")
            days <= 30 -> Status(Level.SOON, if (days <= 1) "Expires tomorrow" else "Expires in $days days")
            else -> Status(Level.OK, "Signed by ${p.ownerName} · good until ${date(exp)}")
        }
    }

    /** Midnight at the end of the day [months] from now. */
    fun monthsFromNow(months: Int): Long = Calendar.getInstance().apply {
        add(Calendar.MONTH, months)
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0)
    }.timeInMillis

    // ---------- PDFs ----------

    private const val W = 612 // US Letter, points
    private const val H = 792
    private const val M = 54f

    private class Writer(val doc: PdfDocument) {
        var page: PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        var y = M
        var n = 1
        val canvas get() = page.canvas
        fun ensure(h: Float) {
            if (y + h <= H - M) return
            doc.finishPage(page)
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create())
            y = M
        }
        fun text(t: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(20, 32, 29), gapAfter: Float = 10f) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, (W - 2 * M).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
            ensure(layout.height.toFloat())
            canvas.save(); canvas.translate(M, y); layout.draw(canvas); canvas.restore()
            y += layout.height + gapAfter
        }
        fun finish(out: File) {
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
            doc.close()
        }
    }

    /** The outline of the site, scaled into a box, with north up. */
    private fun drawOutline(c: Canvas, ring: List<com.brandonthomas.oldground.geo.LngLat>, box: RectF, dots: List<com.brandonthomas.oldground.geo.LngLat> = emptyList()) {
        if (ring.size < 3) return
        val minX = ring.minOf { it.lng }; val maxX = ring.maxOf { it.lng }
        val minY = ring.minOf { it.lat }; val maxY = ring.maxOf { it.lat }
        val kx = kotlin.math.cos(Math.toRadians((minY + maxY) / 2))
        val spanX = max((maxX - minX) * kx, 1e-9); val spanY = max(maxY - minY, 1e-9)
        val s = min((box.width() - 20) / spanX, (box.height() - 20) / spanY)
        val ox = box.left + (box.width() - spanX * s).toFloat() / 2
        val oy = box.top + (box.height() - spanY * s).toFloat() / 2
        val path = Path()
        ring.forEachIndexed { i, p ->
            val x = ox + ((p.lng - minX) * kx * s).toFloat()
            val y = oy + ((maxY - p.lat) * s).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawRoundRect(box, 8f, 8f, Paint().apply { color = Color.rgb(241, 244, 243) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 15, 118, 110) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 118, 110); style = Paint.Style.STROKE; strokeWidth = 2f })
        c.drawText("N ↑", box.right - 30, box.top + 16, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.GRAY })
        // Numbered finds, matching the list below.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 83, 9) }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        dots.forEachIndexed { i, p ->
            val x = ox + ((p.lng - minX) * kx * s).toFloat()
            val y = oy + ((maxY - p.lat) * s).toFloat()
            c.drawCircle(x, y, 8f, rim); c.drawCircle(x, y, 6.6f, fill)
            c.drawText("${i + 1}", x, y + 2.8f, num)
        }
    }

    fun slipPdf(ctx: Context, site: Site, p: Permission, metric: Boolean): File {
        val w = Writer(PdfDocument())
        w.text("Permission to search", 22f, bold = true, gapAfter = 4f)
        w.text(site.name, 13f, color = Color.rgb(85, 99, 95), gapAfter = 18f)
        for (para in wording(site.name, p.ownerName, p.hunterName, p.startsAt, p.expiresAt, p.conditions, p.extra)) w.text(para, 12.5f, gapAfter = 12f)
        // The property.
        w.ensure(190f)
        val box = RectF(M, w.y, M + 220f, w.y + 160f)
        drawOutline(w.canvas, site.ring, box)
        val c = Sites.center(site.ring)
        val info = listOfNotNull(
            "Property: ${site.name}",
            "About ${Sites.formatArea(Sites.areaM2(site.ring), metric)}",
            c?.let { "Near %.5f, %.5f".format(it.lat, it.lng) },
        )
        var ty = w.y + 14f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(42, 55, 51) }
        for (l in info) { w.canvas.drawText(l, box.right + 16f, ty, tp); ty += 16f }
        w.y = box.bottom + 24f
        // Signatures.
        w.ensure(150f)
        val sig = BitmapFactory.decodeFile(file(ctx, p.signature).path)
        if (sig != null) {
            val sh = 70f; val sw = sh * sig.width / sig.height
            w.canvas.drawBitmap(sig, null, RectF(M, w.y, M + min(sw, 300f), w.y + sh), Paint(Paint.FILTER_BITMAP_FLAG))
            w.y += sh + 2f
        }
        w.canvas.drawLine(M, w.y, M + 300f, w.y, Paint().apply { color = Color.rgb(140, 152, 147); strokeWidth = 1f })
        w.y += 14f
        w.text("Signed by ${p.ownerName}, owner, on ${date(p.signedAt)}", 11.5f, gapAfter = 2f)
        listOfNotNull(p.ownerPhone.takeIf { it.isNotBlank() }, p.ownerEmail.takeIf { it.isNotBlank() }).takeIf { it.isNotEmpty() }?.let { w.text(it.joinToString(" · "), 11f, color = Color.rgb(85, 99, 95), gapAfter = 12f) }
        w.text("Detectorist: ${p.hunterName}", 11.5f, gapAfter = 24f)
        w.text("Made with OldGround · oldgroundapp.com", 9f, color = Color.rgb(138, 152, 147), gapAfter = 0f)
        val out = file(ctx, p.pdf)
        w.finish(out)
        return out
    }

    /** “Finds from your land”: every find at a site, with a photo, for the landowner. */
    fun findsReport(ctx: Context, site: Site, finds: List<Pin>, metric: Boolean): File {
        val w = Writer(PdfDocument())
        w.text("Finds from your land", 22f, bold = true, gapAfter = 4f)
        val owner = site.permission?.ownerName
        w.text(listOfNotNull(site.name, owner?.let { "for $it" }, date(System.currentTimeMillis())).joinToString(" · "), 13f, color = Color.rgb(85, 99, 95), gapAfter = 8f)
        w.text(
            if (finds.isEmpty()) "Nothing found yet." else "${finds.size} find${if (finds.size == 1) "" else "s"} so far. Thank you for letting me search your property.",
            12.5f, gapAfter = 14f,
        )
        val sorted = finds.sortedBy { it.createdAt }
        if (site.ring.size >= 3 && sorted.isNotEmpty()) {
            w.ensure(214f)
            drawOutline(w.canvas, site.ring, RectF(M, w.y, W - M, w.y + 200f), sorted.map { it.geo })
            w.y += 214f
        }
        for ((i, f) in sorted.withIndex()) {
            w.ensure(118f)
            val top = w.y
            val photo = f.photos.firstOrNull()?.let { Photos.thumb(ctx, it, 300) }
            if (photo != null) w.canvas.drawBitmap(photo, null, RectF(M, top, M + 100f, top + 100f), Paint(Paint.FILTER_BITMAP_FLAG))
            else w.canvas.drawRoundRect(RectF(M, top, M + 100f, top + 100f), 8f, 8f, Paint().apply { color = Color.rgb(241, 244, 243) })
            val x = M + 116f
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(20, 32, 29) }
            w.canvas.drawText("${i + 1}.  ${f.name.take(60)}", x, top + 18f, tp)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(85, 99, 95) }
            val details = listOfNotNull(f.category, f.material, f.depthIn?.let { if (metric) "${(it * 2.54).toInt()} cm deep" else "${if (it % 1.0 == 0.0) it.toInt() else it} in deep" }).joinToString(" · ")
            w.canvas.drawText(date(f.createdAt), x, top + 36f, sp)
            if (details.isNotBlank()) w.canvas.drawText(details, x, top + 52f, sp)
            if (f.note.isNotBlank()) w.canvas.drawText(f.note.replace('\n', ' ').take(80), x, top + 68f, sp)
            w.y = top + 112f
        }
        w.y += 8f
        w.text("Made with OldGround · oldgroundapp.com", 9f, color = Color.rgb(138, 152, 147), gapAfter = 0f)
        val out = File(ctx.cacheDir, "Finds from ${site.name.replace(Regex("[^A-Za-z0-9 _-]"), "")}.pdf")
        w.finish(out)
        return out
    }

    // ---------- sharing ----------

    private fun uri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** Opens the share sheet with the PDF (text, email, save to Files…). */
    fun share(ctx: Context, f: File, subject: String, to: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        to?.takeIf { it.contains("@") }?.let { send.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        ctx.startActivity(Intent.createChooser(send, subject))
    }

    /** Opens the PDF in a viewer. */
    fun view(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }.onFailure { share(ctx, f, f.nameWithoutExtension) }
    }

    /** Saves a drawn signature (white background, so it shows in any viewer). */
    fun saveSignature(ctx: Context, bmp: Bitmap, name: String) {
        file(ctx, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
